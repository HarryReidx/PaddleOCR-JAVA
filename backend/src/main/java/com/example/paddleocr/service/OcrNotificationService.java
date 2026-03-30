package com.example.paddleocr.service;

import com.example.paddleocr.model.OcrNotificationResponse;
import java.util.List;
import java.util.Map;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface OcrNotificationService {
    void publish(String taskNo, String eventType, String message, Map<String, Object> payload);
    SseEmitter subscribe();
    List<OcrNotificationResponse> listRecent(int limit);
}
