package com.example.paddleocr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.paddleocr.entity.OcrTaskEntity;
import com.example.paddleocr.mapper.OcrTaskMapper;
import com.example.paddleocr.model.OcrExecutionResult;
import com.example.paddleocr.model.OcrTaskDetailResponse;
import com.example.paddleocr.model.OcrTaskSummaryResponse;
import com.example.paddleocr.service.OcrEngineService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class OcrTaskServiceImpl implements OcrTaskService {

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OcrTaskMapper ocrTaskMapper;
    private final OcrEngineService ocrEngineService;
    private final OcrNotificationService notificationService;
    private final JsonSupport jsonSupport;
    private final Path uploadRoot;
    private OcrQueueService queueService;

    public OcrTaskServiceImpl(
        OcrTaskMapper ocrTaskMapper,
        OcrEngineService ocrEngineService,
        OcrNotificationService notificationService,
        JsonSupport jsonSupport,
        Path uploadRoot
    ) {
        this.ocrTaskMapper = ocrTaskMapper;
        this.ocrEngineService = ocrEngineService;
        this.notificationService = notificationService;
        this.jsonSupport = jsonSupport;
        this.uploadRoot = uploadRoot;
    }

    @Autowired
    public void setQueueService(OcrQueueService queueService) {
        this.queueService = queueService;
    }

    @Override
    public OcrTaskDetailResponse createSyncTask(MultipartFile file) {
        OcrTaskEntity task = createTask(file, "SYNC", "PROCESSING");
        processTask(task.getId());
        return getByTaskNo(task.getTaskNo());
    }

    @Override
    public OcrTaskDetailResponse createAsyncTask(MultipartFile file) {
        OcrTaskEntity task = createTask(file, "ASYNC", "QUEUED");
        notificationService.publish(task.getTaskNo(), "TASK_QUEUED", "识别任务已进入队列",
            Map.of("taskNo", task.getTaskNo(), "status", task.getStatus()));
        queueService.submit(task.getId());
        return getByTaskNo(task.getTaskNo());
    }

    @Override
    public void processTask(Long taskId) {
        OcrTaskEntity task = ocrTaskMapper.selectById(taskId);
        if (task == null || "COMPLETED".equals(task.getStatus()) || "FAILED".equals(task.getStatus())) {
            return;
        }

        task.setStatus("PROCESSING");
        task.setStartedAt(LocalDateTime.now());
        ocrTaskMapper.updateById(task);
        notificationService.publish(task.getTaskNo(), "TASK_PROCESSING", "开始识别图片",
            Map.of("taskNo", task.getTaskNo(), "status", task.getStatus()));

        try {
            OcrExecutionResult executionResult = ocrEngineService.recognize(Path.of(task.getImagePath()));
            task.setStatus("COMPLETED");
            task.setDocumentType(executionResult.documentType());
            task.setOcrText(executionResult.text());
            task.setParsedFieldsJson(jsonSupport.toJson(executionResult.parsedFields()));
            task.setFinishedAt(LocalDateTime.now());
            task.setErrorMessage(null);
            ocrTaskMapper.updateById(task);

            notificationService.publish(task.getTaskNo(), "TASK_COMPLETED", "识别完成",
                Map.of(
                    "taskNo", task.getTaskNo(),
                    "status", task.getStatus(),
                    "documentType", executionResult.documentType(),
                    "parsedFields", executionResult.parsedFields()
                ));
        } catch (Exception ex) {
            task.setStatus("FAILED");
            task.setFinishedAt(LocalDateTime.now());
            task.setErrorMessage(ex.getMessage());
            ocrTaskMapper.updateById(task);

            String errorMessage = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            notificationService.publish(task.getTaskNo(), "TASK_FAILED", "识别失败",
                Map.of("taskNo", task.getTaskNo(), "status", task.getStatus(), "errorMessage", errorMessage));
        }
    }

    @Override
    public OcrTaskDetailResponse getByTaskNo(String taskNo) {
        OcrTaskEntity entity = ocrTaskMapper.selectOne(new LambdaQueryWrapper<OcrTaskEntity>()
            .eq(OcrTaskEntity::getTaskNo, taskNo)
            .last("limit 1"));
        if (entity == null) {
            throw new IllegalArgumentException("Task not found: " + taskNo);
        }
        return toDetail(entity);
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

    private OcrTaskEntity createTask(MultipartFile file, String mode, String status) {
        Path storedFile = storeFile(file);
        OcrTaskEntity task = new OcrTaskEntity();
        task.setTaskNo(UUID.randomUUID().toString().replace("-", ""));
        task.setMode(mode);
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
        Path target = uploadRoot.resolve(FILE_TS.format(LocalDateTime.now()) + "_" + safeName);
        try {
            Files.copy(file.getInputStream(), target);
            return target;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to save upload file", ex);
        }
    }

    private OcrTaskDetailResponse toDetail(OcrTaskEntity entity) {
        OcrTaskDetailResponse response = new OcrTaskDetailResponse();
        response.setTaskNo(entity.getTaskNo());
        response.setMode(entity.getMode());
        response.setStatus(entity.getStatus());
        response.setImageName(entity.getImageName());
        response.setDocumentType(entity.getDocumentType());
        response.setOcrText(entity.getOcrText());
        response.setParsedFields(jsonSupport.readStringMap(entity.getParsedFieldsJson()));
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
        response.setStatus(entity.getStatus());
        response.setImageName(entity.getImageName());
        response.setDocumentType(entity.getDocumentType());
        response.setCreatedAt(entity.getCreatedAt());
        response.setFinishedAt(entity.getFinishedAt());
        return response;
    }
}
