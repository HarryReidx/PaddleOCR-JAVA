package com.example.paddleocr.service.impl;

import com.example.paddleocr.model.OcrExecutionResult;
import com.example.paddleocr.service.OcrEngineService;
import com.example.paddleocr.support.DocumentFieldExtractor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class RapidOcrEngineServiceImpl implements OcrEngineService {

    private final DocumentFieldExtractor extractor;
    private final Object lock = new Object();
    private volatile Object engine;

    public RapidOcrEngineServiceImpl(DocumentFieldExtractor extractor) {
        this.extractor = extractor;
    }

    @Override
    public OcrExecutionResult recognize(Path imagePath) {
        synchronized (lock) {
            Object engineInstance = getEngine();
            String text = runOcr(engineInstance, imagePath);
            String documentType = extractor.detectDocumentType(text);
            Map<String, String> fields = extractor.extract(documentType, text);
            return new OcrExecutionResult(text, documentType, fields);
        }
    }

    private Object getEngine() {
        if (engine != null) {
            return engine;
        }
        try {
            Class<?> modelClass = Class.forName("io.github.mymonstercat.Model");
            Object model = Enum.valueOf((Class<Enum>) modelClass.asSubclass(Enum.class), "ONNX_PPOCR_V3");
            Class<?> engineClass = Class.forName("io.github.mymonstercat.ocr.InferenceEngine");
            Method getInstance = engineClass.getMethod("getInstance", modelClass);
            engine = getInstance.invoke(null, model);
            return engine;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize RapidOCR engine", ex);
        }
    }

    private String runOcr(Object engineInstance, Path imagePath) {
        Object result = invokeRunOcr(engineInstance, imagePath);
        if (result == null) {
            return "";
        }
        try {
            Method getStrRes = result.getClass().getMethod("getStrRes");
            Object value = getStrRes.invoke(result);
            return value == null ? "" : String.valueOf(value).trim();
        } catch (Exception ignored) {
        }
        try {
            Method getText = result.getClass().getMethod("getText");
            Object value = getText.invoke(result);
            return value == null ? "" : String.valueOf(value).trim();
        } catch (Exception ignored) {
        }
        return String.valueOf(result).trim();
    }

    private Object invokeRunOcr(Object engineInstance, Path imagePath) {
        Class<?> clazz = engineInstance.getClass();
        try {
            Method method = clazz.getMethod("runOcr", String.class);
            return method.invoke(engineInstance, imagePath.toString());
        } catch (Exception ignored) {
        }
        try {
            Method method = clazz.getMethod("runOcr", java.io.File.class);
            return method.invoke(engineInstance, imagePath.toFile());
        } catch (Exception ignored) {
        }
        try {
            Method method = clazz.getMethod("runOcr", Path.class);
            return method.invoke(engineInstance, imagePath);
        } catch (Exception ex) {
            throw new IllegalStateException("RapidOCR invocation failed", ex);
        }
    }
}
