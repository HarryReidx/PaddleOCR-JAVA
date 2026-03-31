package com.example.paddleocr.service;

import com.example.paddleocr.model.OcrTaskDetailResponse;
import com.example.paddleocr.model.OcrTaskSummaryResponse;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface OcrTaskService {
    OcrTaskDetailResponse createSyncTask(MultipartFile file, String engineType);
    OcrTaskDetailResponse createAsyncTask(MultipartFile file, String engineType);
    void processTask(Long taskId);
    OcrTaskDetailResponse cancelTask(String taskNo);
    List<Long> recoverPendingAsyncTaskIds();
    OcrTaskDetailResponse getByTaskNo(String taskNo);
    List<OcrTaskSummaryResponse> listRecent(int limit);
}
