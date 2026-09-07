CREATE TABLE video_rag_chunk (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    chunk_id VARCHAR(160) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    video_id BIGINT UNSIGNED NOT NULL,
    analysis_task_id BIGINT UNSIGNED NOT NULL,
    chunk_index INT UNSIGNED NOT NULL,
    text LONGTEXT NOT NULL,
    start_ms BIGINT UNSIGNED NOT NULL,
    end_ms BIGINT UNSIGNED NOT NULL,
    source_segment_indexes JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_rag_chunk_task_index (analysis_task_id, chunk_index),
    KEY idx_rag_chunk_user_video (user_id, video_id),
    FULLTEXT KEY ft_rag_chunk_text (text) WITH PARSER ngram,
    CONSTRAINT fk_rag_chunk_video FOREIGN KEY (video_id) REFERENCES video (id) ON DELETE CASCADE,
    CONSTRAINT fk_rag_chunk_task FOREIGN KEY (analysis_task_id) REFERENCES analysis_task (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
