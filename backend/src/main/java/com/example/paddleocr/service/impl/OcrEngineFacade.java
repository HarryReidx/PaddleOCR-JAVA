package com.example.paddleocr.service.impl;

import com.example.paddleocr.model.OcrExecutionResult;
import java.nio.file.Path;
import org.springframework.stereotype.Service;

@Service
public class OcrEngineFacade {

    public static final String ENGINE_PADDLE_OCR_CPU = "paddleocr-cpu";
    public static final String ENGINE_QWEN_VL = "qwen-vl";
    public static final String ENGINE_GLM_OCR = "glm-ocr";
    public static final String ENGINE_PADDLE_OCR_VL = "paddleocr-vl";

    private final RapidOcrEngineServiceImpl rapidOcrEngineService;
    private final DifyWorkflowOcrServiceImpl difyWorkflowOcrService;

    public OcrEngineFacade(
        RapidOcrEngineServiceImpl rapidOcrEngineService,
        DifyWorkflowOcrServiceImpl difyWorkflowOcrService
    ) {
        this.rapidOcrEngineService = rapidOcrEngineService;
        this.difyWorkflowOcrService = difyWorkflowOcrService;
    }

    public OcrExecutionResult recognize(String engineType, Path imagePath) {
        return switch (normalizeEngineType(engineType)) {
            case ENGINE_QWEN_VL, ENGINE_GLM_OCR, ENGINE_PADDLE_OCR_VL -> difyWorkflowOcrService.recognize(normalizeEngineType(engineType), imagePath);
            case ENGINE_PADDLE_OCR_CPU -> rapidOcrEngineService.recognize(imagePath);
            default -> throw new IllegalArgumentException("Unsupported engine type: " + engineType);
        };
    }

    public String normalizeEngineType(String engineType) {
        if (engineType == null || engineType.isBlank()) {
            return ENGINE_PADDLE_OCR_CPU;
        }
        String normalized = engineType.trim().toLowerCase();
        return switch (normalized) {
            case "paddleocr", "paddleocr-cpu", "rapidocr", "rapidocr-java" -> ENGINE_PADDLE_OCR_CPU;
            case ENGINE_QWEN_VL -> ENGINE_QWEN_VL;
            case ENGINE_GLM_OCR -> ENGINE_GLM_OCR;
            case ENGINE_PADDLE_OCR_VL -> ENGINE_PADDLE_OCR_VL;
            default -> normalized;
        };
    }
}
