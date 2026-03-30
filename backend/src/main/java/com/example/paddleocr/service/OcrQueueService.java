package com.example.paddleocr.service;

import com.example.paddleocr.model.QueueStatusResponse;

public interface OcrQueueService {
    void submit(Long taskId);
    QueueStatusResponse getStatus();
}
