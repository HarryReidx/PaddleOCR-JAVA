package com.example.paddleocr.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class OcrSchemaUpgradeConfiguration {

    @Bean
    public ApplicationRunner ocrSchemaUpgradeRunner(JdbcTemplate jdbcTemplate) {
        return args -> {
            ensureColumn(jdbcTemplate, "ocr_task", "engine_type",
                "ALTER TABLE ocr_task ADD COLUMN engine_type VARCHAR(32) NOT NULL DEFAULT 'paddleocr-cpu' AFTER mode");
            ensureColumn(jdbcTemplate, "ocr_task", "output_json",
                "ALTER TABLE ocr_task ADD COLUMN output_json LONGTEXT AFTER parsed_fields_json");
        };
    }

    private void ensureColumn(JdbcTemplate jdbcTemplate, String tableName, String columnName, String ddl) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
            Integer.class,
            tableName,
            columnName
        );
        if (count == null || count == 0) {
            jdbcTemplate.execute(ddl);
        }
    }
}
