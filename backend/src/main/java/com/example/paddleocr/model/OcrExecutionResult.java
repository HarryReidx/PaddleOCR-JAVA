package com.example.paddleocr.model;

import java.util.Map;

public record OcrExecutionResult(String text, String documentType, Map<String, String> parsedFields) {
}
