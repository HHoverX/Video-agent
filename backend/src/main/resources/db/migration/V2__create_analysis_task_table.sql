CREATE TABLE analysis_task (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    video_id BIGINT UNSIGNED NOT NULL,
    analysis_type VARCHAR(32) NOT NULL,
    model_version VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    progress TINYINT UNSIGNED NOT NULL DEFAULT 0,
    retry_count INT UNSIGNED NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(1000) NULL,
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_analysis_task_business (video_id, analysis_type, model_version),
    KEY idx_analysis_task_video_created (video_id, created_at),
    KEY idx_analysis_task_status_updated (status, updated_at),
    CONSTRAINT fk_analysis_task_video
        FOREIGN KEY (video_id) REFERENCES video (id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_analysis_task_progress CHECK (progress <= 100)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
