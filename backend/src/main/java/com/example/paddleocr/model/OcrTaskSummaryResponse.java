package com.example.paddleocr.model;

import java.time.LocalDateTime;

public class OcrTaskSummaryResponse {
    private String taskNo;
    private String mode;
    private String engineType;
    private String status;
    private String imageName;
    private String documentType;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;

    public String getTaskNo() { return taskNo; }
    public void setTaskNo(String taskNo) { this.taskNo = taskNo; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getEngineType() { return engineType; }
    public void setEngineType(String engineType) { this.engineType = engineType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getImageName() { return imageName; }
    public void setImageName(String imageName) { this.imageName = imageName; }
    public String getDocumentType() { return documentType; }
    public void setDocumentType(String documentType) { this.documentType = documentType; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
}
