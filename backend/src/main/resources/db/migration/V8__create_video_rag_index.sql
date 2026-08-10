CREATE TABLE video_rag_index (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    video_id BIGINT UNSIGNED NOT NULL,
    analysis_task_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(32) NOT NULL,
    context_mode VARCHAR(32) NOT NULL,
    transcript_chars INT UNSIGNED NOT NULL DEFAULT 0,
    chunk_count INT UNSIGNED NOT NULL DEFAULT 0,
    embedding_provider VARCHAR(32) NOT NULL DEFAULT '',
    embedding_model VARCHAR(128) NOT NULL DEFAULT '',
    embedding_dimension INT UNSIGNED NOT NULL DEFAULT 0,
    last_error_code VARCHAR(64) NULL,
    last_error_message VARCHAR(1000) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_rag_index_video (video_id),
    KEY idx_rag_index_status (status),
    CONSTRAINT fk_rag_index_video
        FOREIGN KEY (video_id) REFERENCES video (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_rag_index_task
        FOREIGN KEY (analysis_task_id) REFERENCES analysis_task (id)
        ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
