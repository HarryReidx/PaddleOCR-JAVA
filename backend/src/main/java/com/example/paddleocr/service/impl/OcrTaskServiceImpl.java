package com.example.paddleocr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.paddleocr.entity.OcrTaskEntity;
import com.example.paddleocr.mapper.OcrTaskMapper;
import com.example.paddleocr.model.OcrExecutionResult;
import com.example.paddleocr.model.OcrTaskDetailResponse;
import com.example.paddleocr.model.OcrTaskSummaryResponse;
import com.example.paddleocr.service.OcrNotificationService;
import com.example.paddleocr.service.OcrQueueService;
import com.example.paddleocr.service.OcrTaskService;
import com.example.paddleocr.support.JsonSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class OcrTaskServiceImpl implements OcrTaskService {

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String MODE_ASYNC = "ASYNC";
    private static final String STATUS_QUEUED = "QUEUED";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_CANCEL_REQUESTED = "CANCEL_REQUESTED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";

    private final OcrTaskMapper ocrTaskMapper;
    private final OcrEngineFacade ocrEngineFacade;
    private final OcrNotificationService notificationService;
    private final JsonSupport jsonSupport;
    private final Path uploadRoot;
    private final ObjectProvider<OcrQueueService> queueServiceProvider;

    public OcrTaskServiceImpl(
        OcrTaskMapper ocrTaskMapper,
        OcrEngineFacade ocrEngineFacade,
        OcrNotificationService notificationService,
        JsonSupport jsonSupport,
        Path uploadRoot,
        ObjectProvider<OcrQueueService> queueServiceProvider
    ) {
        this.ocrTaskMapper = ocrTaskMapper;
        this.ocrEngineFacade = ocrEngineFacade;
        this.notificationService = notificationService;
        this.jsonSupport = jsonSupport;
        this.uploadRoot = uploadRoot;
        this.queueServiceProvider = queueServiceProvider;
    }

    @Override
    public OcrTaskDetailResponse createSyncTask(MultipartFile file, String engineType) {
        OcrTaskEntity task = createTask(file, "SYNC", STATUS_PROCESSING, engineType);
        processTask(task.getId());
        return getByTaskNo(task.getTaskNo());
    }

    @Override
    public OcrTaskDetailResponse createAsyncTask(MultipartFile file, String engineType) {
        OcrTaskEntity task = createTask(file, MODE_ASYNC, STATUS_QUEUED, engineType);
        notificationService.publish(task.getTaskNo(), "TASK_QUEUED", "Task queued",
            Map.of("taskNo", task.getTaskNo(), "status", task.getStatus(), "engineType", task.getEngineType()));
        queueService().submit(task.getId());
        return getByTaskNo(task.getTaskNo());
    }

    @Override
    public void processTask(Long taskId) {
        OcrTaskEntity task = ocrTaskMapper.selectById(taskId);
        if (task == null || isTerminal(task.getStatus())) {
            return;
        }
        if (isCancellationStatus(task.getStatus())) {
            markCancelled(task, "Task cancelled");
            return;
        }

        if (!STATUS_PROCESSING.equals(task.getStatus())) {
            task.setStatus(STATUS_PROCESSING);
            task.setStartedAt(LocalDateTime.now());
            task.setFinishedAt(null);
            task.setErrorMessage(null);
            ocrTaskMapper.updateById(task);
            notificationService.publish(task.getTaskNo(), "TASK_PROCESSING", "Task processing",
                Map.of("taskNo", task.getTaskNo(), "status", task.getStatus(), "engineType", task.getEngineType()));
        }

        try {
            OcrExecutionResult executionResult = ocrEngineFacade.recognize(task.getEngineType(), Path.of(task.getImagePath()));
            OcrTaskEntity latest = ocrTaskMapper.selectById(taskId);
            if (latest == null) {
                return;
            }
            if (isCancellationStatus(latest.getStatus()) || Thread.currentThread().isInterrupted()) {
                markCancelled(latest, "Task cancelled");
                Thread.interrupted();
                return;
            }

            latest.setStatus(STATUS_COMPLETED);
            latest.setDocumentType(executionResult.documentType());
            latest.setOcrText(executionResult.text());
            latest.setParsedFieldsJson(jsonSupport.toJson(executionResult.parsedFields()));
            latest.setOutputJson(resolveOutputJson(executionResult));
            latest.setFinishedAt(LocalDateTime.now());
            latest.setErrorMessage(null);
            ocrTaskMapper.updateById(latest);

            notificationService.publish(latest.getTaskNo(), "TASK_COMPLETED", "Task completed",
                Map.of(
                    "taskNo", latest.getTaskNo(),
                    "status", latest.getStatus(),
                    "engineType", latest.getEngineType(),
                    "documentType", executionResult.documentType(),
                    "parsedFields", executionResult.parsedFields()
                ));
        } catch (Exception ex) {
            OcrTaskEntity latest = ocrTaskMapper.selectById(taskId);
            if (latest != null && (isCancellationStatus(latest.getStatus()) || Thread.currentThread().isInterrupted())) {
                markCancelled(latest, "Task cancelled");
                Thread.interrupted();
                return;
            }

            if (latest == null) {
                return;
            }
            latest.setStatus(STATUS_FAILED);
            latest.setFinishedAt(LocalDateTime.now());
            latest.setErrorMessage(ex.getMessage());
            ocrTaskMapper.updateById(latest);

            String errorMessage = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            notificationService.publish(latest.getTaskNo(), "TASK_FAILED", "Task failed",
                Map.of(
                    "taskNo", latest.getTaskNo(),
                    "status", latest.getStatus(),
                    "engineType", latest.getEngineType(),
                    "errorMessage", errorMessage
                ));
        }
    }

    @Override
    public OcrTaskDetailResponse cancelTask(String taskNo) {
        OcrTaskEntity task = findTask(taskNo);
        if (isTerminal(task.getStatus()) || STATUS_CANCEL_REQUESTED.equals(task.getStatus())) {
            return toDetail(task);
        }

        if (STATUS_QUEUED.equals(task.getStatus())) {
            queueService().cancel(task.getId());
            markCancelled(task, "Task cancelled");
            return getByTaskNo(taskNo);
        }

        if (STATUS_PROCESSING.equals(task.getStatus())) {
            task.setStatus(STATUS_CANCEL_REQUESTED);
            task.setErrorMessage("Cancellation requested");
            ocrTaskMapper.updateById(task);
            boolean signalled = queueService().cancel(task.getId());
            if (!signalled) {
                markCancelled(task, "Task cancelled");
            } else {
                notificationService.publish(task.getTaskNo(), "TASK_CANCEL_REQUESTED", "Cancellation requested",
                    Map.of("taskNo", task.getTaskNo(), "status", task.getStatus(), "engineType", task.getEngineType()));
            }
            return getByTaskNo(taskNo);
        }

        throw new IllegalStateException("Task cannot be cancelled in status: " + task.getStatus());
    }

    @Override
    public List<Long> recoverPendingAsyncTaskIds() {
        List<OcrTaskEntity> tasks = ocrTaskMapper.selectList(new LambdaQueryWrapper<OcrTaskEntity>()
            .in(OcrTaskEntity::getStatus, STATUS_QUEUED, STATUS_PROCESSING, STATUS_CANCEL_REQUESTED)
            .orderByAsc(OcrTaskEntity::getId));

        return tasks.stream().map(task -> {
            if (STATUS_CANCEL_REQUESTED.equals(task.getStatus())) {
                markCancelled(task, "Task cancelled");
                return null;
            }
            if (!MODE_ASYNC.equals(task.getMode())) {
                task.setStatus(STATUS_FAILED);
                task.setFinishedAt(LocalDateTime.now());
                task.setErrorMessage("Sync task could not be resumed after restart");
                ocrTaskMapper.updateById(task);
                notificationService.publish(task.getTaskNo(), "TASK_FAILED", "Sync task could not be resumed after restart",
                    Map.of("taskNo", task.getTaskNo(), "status", task.getStatus(), "errorMessage", task.getErrorMessage()));
                return null;
            }
            task.setStatus(STATUS_QUEUED);
            task.setStartedAt(null);
            task.setFinishedAt(null);
            task.setErrorMessage(null);
            ocrTaskMapper.updateById(task);
            notificationService.publish(task.getTaskNo(), "TASK_REQUEUED", "Task re-queued after restart",
                Map.of("taskNo", task.getTaskNo(), "status", task.getStatus(), "engineType", task.getEngineType()));
            return task.getId();
        }).filter(id -> id != null).toList();
    }

    @Override
    public OcrTaskDetailResponse getByTaskNo(String taskNo) {
        return toDetail(findTask(taskNo));
    }

    @Override
    public List<OcrTaskSummaryResponse> listRecent(int limit) {
        return ocrTaskMapper.selectList(new LambdaQueryWrapper<OcrTaskEntity>()
                .orderByDesc(OcrTaskEntity::getId)
                .last("limit " + Math.max(1, Math.min(limit, 100))))
            .stream()
            .map(this::toSummary)
            .toList();
    }

    private OcrTaskEntity createTask(MultipartFile file, String mode, String status, String engineType) {
        Path storedFile = storeFile(file);
        OcrTaskEntity task = new OcrTaskEntity();
        task.setTaskNo(UUID.randomUUID().toString().replace("-", ""));
        task.setMode(mode);
        task.setEngineType(ocrEngineFacade.normalizeEngineType(engineType));
        task.setImageName(file.getOriginalFilename() == null ? storedFile.getFileName().toString() : file.getOriginalFilename());
        task.setImagePath(storedFile.toString());
        task.setStatus(status);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        ocrTaskMapper.insert(task);
        return task;
    }

    private Path storeFile(MultipartFile file) {
        String safeName = (file.getOriginalFilename() == null ? "upload.png" : file.getOriginalFilename())
            .replaceAll("[^a-zA-Z0-9._-]", "_");
        Path target = uploadRoot.resolve(FILE_TS.format(LocalDateTime.now()) + "_" + UUID.randomUUID().toString().replace("-", "") + "_" + safeName);
        try {
            Files.copy(file.getInputStream(), target);
            return target;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to save upload file", ex);
        }
    }

    private OcrQueueService queueService() {
        OcrQueueService queueService = queueServiceProvider.getIfAvailable();
        if (queueService == null) {
            throw new IllegalStateException("OCR queue service is not available");
        }
        return queueService;
    }

    private OcrTaskEntity findTask(String taskNo) {
        OcrTaskEntity entity = ocrTaskMapper.selectOne(new LambdaQueryWrapper<OcrTaskEntity>()
            .eq(OcrTaskEntity::getTaskNo, taskNo)
            .last("limit 1"));
        if (entity == null) {
            throw new IllegalArgumentException("Task not found: " + taskNo);
        }
        if (entity.getEngineType() == null || entity.getEngineType().isBlank()) {
            entity.setEngineType(OcrEngineFacade.ENGINE_PADDLE_OCR_CPU);
        }
        return entity;
    }

    private void markCancelled(OcrTaskEntity task, String message) {
        task.setStatus(STATUS_CANCELLED);
        task.setFinishedAt(LocalDateTime.now());
        task.setErrorMessage(message);
        ocrTaskMapper.updateById(task);
        notificationService.publish(task.getTaskNo(), "TASK_CANCELLED", message,
            Map.of("taskNo", task.getTaskNo(), "status", task.getStatus(), "engineType", task.getEngineType()));
    }

    private boolean isTerminal(String status) {
        return STATUS_COMPLETED.equals(status) || STATUS_FAILED.equals(status) || STATUS_CANCELLED.equals(status);
    }

    private boolean isCancellationStatus(String status) {
        return STATUS_CANCEL_REQUESTED.equals(status) || STATUS_CANCELLED.equals(status);
    }

    private String resolveOutputJson(OcrExecutionResult executionResult) {
        if (executionResult.outputJson() != null && !executionResult.outputJson().isBlank()) {
            return executionResult.outputJson();
        }
        return jsonSupport.toJson(executionResult.parsedFields());
    }

    private OcrTaskDetailResponse toDetail(OcrTaskEntity entity) {
        OcrTaskDetailResponse response = new OcrTaskDetailResponse();
        response.setTaskNo(entity.getTaskNo());
        response.setMode(entity.getMode());
        response.setEngineType(entity.getEngineType());
        response.setStatus(entity.getStatus());
        response.setImageName(entity.getImageName());
        response.setDocumentType(entity.getDocumentType());
        response.setOcrText(entity.getOcrText());
        response.setParsedFields(jsonSupport.readStringMap(entity.getParsedFieldsJson()));
        response.setJsonOutput(entity.getOutputJson() == null || entity.getOutputJson().isBlank()
            ? entity.getParsedFieldsJson()
            : entity.getOutputJson());
        response.setErrorMessage(entity.getErrorMessage());
        response.setCreatedAt(entity.getCreatedAt());
        response.setStartedAt(entity.getStartedAt());
        response.setFinishedAt(entity.getFinishedAt());
        return response;
    }

    private OcrTaskSummaryResponse toSummary(OcrTaskEntity entity) {
        OcrTaskSummaryResponse response = new OcrTaskSummaryResponse();
        response.setTaskNo(entity.getTaskNo());
        response.setMode(entity.getMode());
        response.setEngineType(entity.getEngineType());
        response.setStatus(entity.getStatus());
        response.setImageName(entity.getImageName());
        response.setDocumentType(entity.getDocumentType());
        response.setCreatedAt(entity.getCreatedAt());
        response.setFinishedAt(entity.getFinishedAt());
        return response;
    }
}
