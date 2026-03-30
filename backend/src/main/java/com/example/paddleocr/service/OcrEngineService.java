package com.example.paddleocr.service;

import com.example.paddleocr.model.OcrExecutionResult;
import java.nio.file.Path;

public interface OcrEngineService {
    OcrExecutionResult recognize(Path imagePath);
}
