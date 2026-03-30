CREATE TABLE IF NOT EXISTS ocr_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_no VARCHAR(64) NOT NULL UNIQUE,
    mode VARCHAR(16) NOT NULL,
    image_name VARCHAR(255) NOT NULL,
    image_path VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL,
    document_type VARCHAR(64),
    ocr_text MEDIUMTEXT,
    parsed_fields_json LONGTEXT,
    error_message VARCHAR(1000),
    started_at DATETIME NULL,
    finished_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ocr_notification (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_no VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    message VARCHAR(512) NOT NULL,
    payload_json LONGTEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ocr_notification_task_no (task_no),
    INDEX idx_ocr_notification_created_at (created_at)
);
