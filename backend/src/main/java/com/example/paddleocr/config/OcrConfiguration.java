package com.example.paddleocr.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(OcrProperties.class)
public class OcrConfiguration {

    @Bean
    public ThreadPoolTaskExecutor ocrTaskExecutor(OcrProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, properties.getQueueWorkers()));
        executor.setMaxPoolSize(Math.max(1, properties.getQueueWorkers()));
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("ocr-worker-");
        executor.initialize();
        return executor;
    }

    @Bean
    public Path uploadRoot(OcrProperties properties) throws Exception {
        Path path = Paths.get(properties.getUploadDir()).toAbsolutePath().normalize();
        Files.createDirectories(path);
        return path;
    }

    @Bean
    public WebMvcConfigurer corsConfigurer(OcrProperties properties) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                    .allowedOrigins(properties.getAllowedOrigins().toArray(String[]::new))
                    .allowedMethods("*")
                    .allowedHeaders("*");
            }
        };
    }
}
