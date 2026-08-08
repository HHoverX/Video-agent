CREATE TABLE video_transcript_segment (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    video_id BIGINT UNSIGNED NOT NULL,
    task_id BIGINT UNSIGNED NOT NULL,
    segment_index INT UNSIGNED NOT NULL,
    start_ms BIGINT UNSIGNED NOT NULL,
    end_ms BIGINT UNSIGNED NOT NULL,
    text VARCHAR(2000) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_transcript_task_segment (task_id, segment_index),
    KEY idx_transcript_video_segment (video_id, segment_index),
    KEY idx_transcript_video_start (video_id, start_ms),
    CONSTRAINT fk_transcript_video
        FOREIGN KEY (video_id) REFERENCES video (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_transcript_task
        FOREIGN KEY (task_id) REFERENCES analysis_task (id)
        ON DELETE CASCADE,
    CONSTRAINT chk_transcript_time_range CHECK (end_ms > start_ms),
    CONSTRAINT chk_transcript_text_not_blank CHECK (CHAR_LENGTH(TRIM(text)) > 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
