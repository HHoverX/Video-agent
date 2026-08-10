CREATE TABLE analysis_outbox_event (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    event_key VARCHAR(160) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    task_id BIGINT UNSIGNED NOT NULL,
    video_id BIGINT UNSIGNED NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL,
    published_at DATETIME(3) NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event_key (event_key),
    KEY idx_outbox_status_next_attempt (status, next_attempt_at),
    KEY idx_outbox_task_created (task_id, created_at),
    CONSTRAINT fk_outbox_task
        FOREIGN KEY (task_id) REFERENCES analysis_task (id)
        ON DELETE CASCADE,
    CONSTRAINT chk_outbox_status_not_blank CHECK (CHAR_LENGTH(TRIM(status)) > 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
