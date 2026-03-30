package com.example.paddleocr.controller;

import com.example.paddleocr.model.OcrNotificationResponse;
import com.example.paddleocr.model.OcrTaskDetailResponse;
import com.example.paddleocr.model.OcrTaskSummaryResponse;
import com.example.paddleocr.model.QueueStatusResponse;
import com.example.paddleocr.service.OcrNotificationService;
import com.example.paddleocr.service.OcrQueueService;
import com.example.paddleocr.service.OcrTaskService;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Validated
@RestController
@RequestMapping("/api/ocr")
public class OcrController {

    private final OcrTaskService ocrTaskService;
    private final OcrQueueService ocrQueueService;
    private final OcrNotificationService notificationService;

    public OcrController(
        OcrTaskService ocrTaskService,
        OcrQueueService ocrQueueService,
        OcrNotificationService notificationService
    ) {
        this.ocrTaskService = ocrTaskService;
        this.ocrQueueService = ocrQueueService;
        this.notificationService = notificationService;
    }

    @PostMapping(value = "/sync", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public OcrTaskDetailResponse sync(@RequestParam("file") MultipartFile file) {
        return ocrTaskService.createSyncTask(file);
    }

    @PostMapping(value = "/async", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public OcrTaskDetailResponse async(@RequestParam("file") MultipartFile file) {
        return ocrTaskService.createAsyncTask(file);
    }

    @GetMapping("/tasks")
    public List<OcrTaskSummaryResponse> tasks(@RequestParam(defaultValue = "20") int limit) {
        return ocrTaskService.listRecent(limit);
    }

    @GetMapping("/tasks/{taskNo}")
    public OcrTaskDetailResponse task(@PathVariable String taskNo) {
        return ocrTaskService.getByTaskNo(taskNo);
    }

    @GetMapping("/queue")
    public QueueStatusResponse queue() {
        return ocrQueueService.getStatus();
    }

    @GetMapping("/notifications")
    public List<OcrNotificationResponse> notifications(@RequestParam(defaultValue = "20") int limit) {
        return notificationService.listRecent(limit);
    }

    @GetMapping(value = "/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return notificationService.subscribe();
    }
}
