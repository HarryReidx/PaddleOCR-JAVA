package com.example.paddleocr.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ocr")
public class OcrProperties {

    private String uploadDir = "backend/uploads";
    private int queueWorkers = 1;
    private List<String> allowedOrigins = new ArrayList<>();

    public String getUploadDir() {
        return uploadDir;
    }

    public void setUploadDir(String uploadDir) {
        this.uploadDir = uploadDir;
    }

    public int getQueueWorkers() {
        return queueWorkers;
    }

    public void setQueueWorkers(int queueWorkers) {
        this.queueWorkers = queueWorkers;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }
}
