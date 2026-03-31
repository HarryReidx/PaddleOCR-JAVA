package com.example.paddleocr.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ocr")
public class OcrProperties {

    private String uploadDir = "backend/uploads";
    private int queueWorkers = 2;
    private List<String> allowedOrigins = new ArrayList<>();
    private final Dify dify = new Dify();

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

    public Dify getDify() {
        return dify;
    }

    public static class Dify {
        private boolean enabled = true;
        private String baseUrl = "http://172.24.0.5/v1";
        private String apiKey = "app-hOVKGj7AZLnU3VWDtFX19MHd";
        private String user = "abc-123";
        private int connectTimeoutMillis = 10000;
        private int readTimeoutMillis = 120000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public int getConnectTimeoutMillis() {
            return connectTimeoutMillis;
        }

        public void setConnectTimeoutMillis(int connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis;
        }

        public int getReadTimeoutMillis() {
            return readTimeoutMillis;
        }

        public void setReadTimeoutMillis(int readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
        }
    }
}
