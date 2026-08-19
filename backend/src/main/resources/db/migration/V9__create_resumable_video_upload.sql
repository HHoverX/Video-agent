CREATE TABLE video_upload_session (
    id CHAR(36) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    file_size BIGINT UNSIGNED NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    chunk_size BIGINT UNSIGNED NOT NULL,
    total_parts INT UNSIGNED NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    temp_prefix VARCHAR(512) NOT NULL,
    expected_sha256 CHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    last_error VARCHAR(1000) NULL,
    expires_at DATETIME(3) NOT NULL,
    video_id BIGINT UNSIGNED NULL,
    analysis_task_id BIGINT UNSIGNED NULL,
    temp_cleaned_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    cancelled_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_video_upload_object_key (object_key),
    UNIQUE KEY uk_video_upload_video (video_id),
    KEY idx_video_upload_user_created (user_id, created_at),
    KEY idx_video_upload_expiry (status, expires_at),
    CONSTRAINT fk_video_upload_user
        FOREIGN KEY (user_id) REFERENCES app_user (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_video_upload_video
        FOREIGN KEY (video_id) REFERENCES video (id)
        ON DELETE SET NULL,
    CONSTRAINT fk_video_upload_task
        FOREIGN KEY (analysis_task_id) REFERENCES analysis_task (id)
        ON DELETE SET NULL,
    CONSTRAINT chk_video_upload_parts CHECK (total_parts BETWEEN 1 AND 10000),
    CONSTRAINT chk_video_upload_size CHECK (file_size > 0 AND chunk_size > 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE video_rag_index
    ADD COLUMN build_token CHAR(36) NULL,
    ADD COLUMN build_started_at DATETIME(3) NULL;

ALTER TABLE analysis_task
    ADD COLUMN last_failure_stage VARCHAR(32) NULL;

CREATE TABLE video_upload_part (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    upload_id CHAR(36) NOT NULL,
    part_number INT UNSIGNED NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    expected_size BIGINT UNSIGNED NOT NULL,
    actual_size BIGINT UNSIGNED NULL,
    etag VARCHAR(128) NULL,
    checksum_sha256 CHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    completed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_video_upload_part (upload_id, part_number),
    UNIQUE KEY uk_video_upload_part_object (object_key),
    KEY idx_video_upload_part_status (upload_id, status),
    CONSTRAINT fk_video_upload_part_session
        FOREIGN KEY (upload_id) REFERENCES video_upload_session (id)
        ON DELETE CASCADE,
    CONSTRAINT chk_video_upload_part_number CHECK (part_number BETWEEN 1 AND 10000),
    CONSTRAINT chk_video_upload_part_size CHECK (expected_size > 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
