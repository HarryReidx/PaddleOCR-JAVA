package com.example.paddleocr.service.impl;

import com.example.paddleocr.config.OcrProperties;
import com.example.paddleocr.model.OcrExecutionResult;
import com.example.paddleocr.support.DocumentFieldExtractor;
import com.example.paddleocr.support.JsonSupport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DifyWorkflowOcrServiceImpl {

    private static final List<String> TEXT_KEYS = List.of("text", "ocr_text", "ocrText", "content", "answer", "result", "output", "message");

    private final OcrProperties properties;
    private final DocumentFieldExtractor extractor;
    private final JsonSupport jsonSupport;

    public DifyWorkflowOcrServiceImpl(OcrProperties properties, DocumentFieldExtractor extractor, JsonSupport jsonSupport) {
        this.properties = properties;
        this.extractor = extractor;
        this.jsonSupport = jsonSupport;
    }

    public OcrExecutionResult recognize(String engineType, Path imagePath) {
        if (!properties.getDify().isEnabled()) {
            throw new IllegalStateException("Dify workflow is disabled");
        }

        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.getDify().getConnectTimeoutMillis()))
            .build();

        String sourceUrl = uploadFile(httpClient, imagePath);
        Map<String, Object> workflowResponse = runWorkflow(httpClient, engineType, sourceUrl);
        Map<String, Object> outputs = extractOutputs(workflowResponse);
        String text = extractText(outputs);
        String documentType = extractor.detectDocumentType(text);
        Map<String, String> parsedFields = flattenOutputs(outputs);
        if (parsedFields.isEmpty()) {
            parsedFields = extractor.extract(documentType, text);
        }

        String outputJson = jsonSupport.toJson(outputs.isEmpty() ? workflowResponse : outputs);
        if (text.isBlank()) {
            text = outputJson;
        }
        return new OcrExecutionResult(text, documentType, parsedFields, outputJson);
    }

    private String uploadFile(HttpClient httpClient, Path imagePath) {
        String boundary = "----Codex" + UUID.randomUUID().toString().replace("-", "");
        byte[] requestBody = buildMultipartBody(boundary, imagePath);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(normalizeBaseUrl() + "/files/upload"))
            .timeout(Duration.ofMillis(properties.getDify().getReadTimeoutMillis()))
            .header("Authorization", "Bearer " + properties.getDify().getApiKey())
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
            .build();

        HttpResponse<String> response = send(request, httpClient);
        Map<String, Object> responseMap = jsonSupport.readObjectMap(response.body());
        String sourceUrl = stringValue(responseMap.get("source_url"));
        if (sourceUrl.isBlank()) {
            throw new IllegalStateException("Dify file upload succeeded but source_url is empty");
        }
        return sourceUrl;
    }

    private byte[] buildMultipartBody(String boundary, Path imagePath) {
        try {
            byte[] fileBytes = Files.readAllBytes(imagePath);
            String fileName = imagePath.getFileName().toString();
            String mimeType = probeMimeType(imagePath);
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            writeLine(output, "--" + boundary);
            writeLine(output, "Content-Disposition: form-data; name=\"user\"");
            writeLine(output, "");
            writeLine(output, properties.getDify().getUser());

            writeLine(output, "--" + boundary);
            writeLine(output, "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"");
            writeLine(output, "Content-Type: " + mimeType);
            writeLine(output, "");
            output.write(fileBytes);
            writeLine(output, "");
            writeLine(output, "--" + boundary + "--");
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to build multipart request", ex);
        }
    }

    private void writeLine(ByteArrayOutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> runWorkflow(HttpClient httpClient, String engineType, String sourceUrl) {
        Map<String, Object> imageInput = new LinkedHashMap<>();
        imageInput.put("transfer_method", "remote_url");
        imageInput.put("url", sourceUrl);

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("type", engineType);
        inputs.put("image", imageInput);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inputs", inputs);
        body.put("response_mode", "blocking");
        body.put("user", properties.getDify().getUser());

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(normalizeBaseUrl() + "/workflows/run"))
            .timeout(Duration.ofMillis(properties.getDify().getReadTimeoutMillis()))
            .header("Authorization", "Bearer " + properties.getDify().getApiKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonSupport.toJson(body), StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response = send(request, httpClient);
        return jsonSupport.readObjectMap(response.body());
    }

    private HttpResponse<String> send(HttpRequest request, HttpClient httpClient) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("Dify workflow execution failed: " + response.statusCode() + " " + response.body());
            }
            return response;
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Dify request failed: " + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractOutputs(Map<String, Object> response) {
        Object direct = response.get("outputs");
        if (direct instanceof Map<?, ?> directMap) {
            return (Map<String, Object>) directMap;
        }
        Object data = response.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            Object outputs = ((Map<String, Object>) dataMap).get("outputs");
            if (outputs instanceof Map<?, ?> outputMap) {
                return (Map<String, Object>) outputMap;
            }
        }
        return Map.of();
    }

    private String extractText(Map<String, Object> outputs) {
        for (String key : TEXT_KEYS) {
            String value = stringValue(outputs.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        if (outputs.size() == 1) {
            return stringValue(outputs.values().iterator().next());
        }
        return "";
    }

    private Map<String, String> flattenOutputs(Map<String, Object> outputs) {
        Map<String, String> flattened = new LinkedHashMap<>();
        outputs.forEach((key, value) -> flattened.put(key, stringifyValue(value)));
        return flattened;
    }

    private String stringifyValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String str) {
            return str;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return jsonSupport.toJson(value);
    }

    private String normalizeBaseUrl() {
        String baseUrl = properties.getDify().getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Dify base URL is empty");
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private String probeMimeType(Path imagePath) {
        try {
            String mimeType = Files.probeContentType(imagePath);
            return mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType;
        } catch (Exception ex) {
            return "application/octet-stream";
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
