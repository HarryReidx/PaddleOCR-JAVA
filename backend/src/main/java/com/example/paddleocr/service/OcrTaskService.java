package com.example.paddleocr.service;

import com.example.paddleocr.model.OcrTaskDetailResponse;
import com.example.paddleocr.model.OcrTaskSummaryResponse;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface OcrTaskService {
    OcrTaskDetailResponse createSyncTask(MultipartFile file);
    OcrTaskDetailResponse createAsyncTask(MultipartFile file);
    void processTask(Long taskId);
    OcrTaskDetailResponse getByTaskNo(String taskNo);
    List<OcrTaskSummaryResponse> listRecent(int limit);
}
