package com.example.paddleocr.service.impl;

import com.example.paddleocr.model.QueueStatusResponse;
import com.example.paddleocr.service.OcrQueueService;
import com.example.paddleocr.service.OcrTaskService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class OcrQueueServiceImpl implements OcrQueueService {

    private static final Logger log = LoggerFactory.getLogger(OcrQueueServiceImpl.class);

    private final BlockingQueue<Long> queue = new LinkedBlockingQueue<>();
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

    @PreDestroy
    public void shutdown() {
        running = false;
    }

    @Override
    public void submit(Long taskId) {
        queue.offer(taskId);
    }

    @Override
    public QueueStatusResponse getStatus() {
        return new QueueStatusResponse(queue.size(), executor.getActiveCount());
    }

    private void consumeLoop() {
        while (running) {
            try {
                Long taskId = queue.poll(1, TimeUnit.SECONDS);
                if (taskId != null) {
                    ocrTaskServiceProvider.getObject().processTask(taskId);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                log.error("OCR queue worker failed", ex);
            }
        }
    }
}
