package com.example.paddleocr.service.impl;

import com.example.paddleocr.model.QueueStatusResponse;
import com.example.paddleocr.service.OcrQueueService;
import com.example.paddleocr.service.OcrTaskService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class OcrQueueServiceImpl implements OcrQueueService {

    private static final Logger log = LoggerFactory.getLogger(OcrQueueServiceImpl.class);

    private final BlockingQueue<Long> queue = new LinkedBlockingQueue<>();
    private final Set<Long> pendingTaskIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Long, Thread> activeTasks = new ConcurrentHashMap<>();
    private final ThreadPoolTaskExecutor executor;
    private final ObjectProvider<OcrTaskService> ocrTaskServiceProvider;
    private volatile boolean running = true;

    public OcrQueueServiceImpl(ThreadPoolTaskExecutor executor, ObjectProvider<OcrTaskService> ocrTaskServiceProvider) {
        this.executor = executor;
        this.ocrTaskServiceProvider = ocrTaskServiceProvider;
    }

    @PostConstruct
    public void startWorkers() {
        int workerCount = Math.max(1, executor.getCorePoolSize());
        for (int i = 0; i < workerCount; i++) {
            executor.execute(this::consumeLoop);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverTasksOnStartup() {
        OcrTaskService taskService = ocrTaskServiceProvider.getIfAvailable();
        if (taskService != null) {
            taskService.recoverPendingAsyncTaskIds().forEach(this::submit);
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
    }

    @Override
    public void submit(Long taskId) {
        if (taskId == null || activeTasks.containsKey(taskId) || !pendingTaskIds.add(taskId)) {
            return;
        }
        queue.offer(taskId);
    }

    @Override
    public boolean cancel(Long taskId) {
        boolean pendingRemoved = pendingTaskIds.remove(taskId);
        if (pendingRemoved) {
            queue.remove(taskId);
            return true;
        }
        Thread worker = activeTasks.get(taskId);
        if (worker != null) {
            worker.interrupt();
            return true;
        }
        return false;
    }

    @Override
    public QueueStatusResponse getStatus() {
        return new QueueStatusResponse(pendingTaskIds.size(), activeTasks.size());
    }

    private void consumeLoop() {
        while (running) {
            try {
                Long taskId = queue.poll(1, TimeUnit.SECONDS);
                if (taskId == null) {
                    continue;
                }
                pendingTaskIds.remove(taskId);
                activeTasks.put(taskId, Thread.currentThread());
                try {
                    ocrTaskServiceProvider.getObject().processTask(taskId);
                } finally {
                    activeTasks.remove(taskId);
                    Thread.interrupted();
                }
            } catch (InterruptedException ex) {
                if (!running) {
                    Thread.currentThread().interrupt();
                    return;
                }
                Thread.interrupted();
            } catch (Exception ex) {
                log.error("OCR queue worker failed", ex);
            }
        }
    }
}