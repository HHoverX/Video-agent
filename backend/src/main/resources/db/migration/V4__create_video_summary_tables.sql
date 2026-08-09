CREATE TABLE video_summary (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    video_id BIGINT UNSIGNED NOT NULL,
    task_id BIGINT UNSIGNED NOT NULL,
    overview TEXT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_video_summary_task (task_id),
    KEY idx_video_summary_video_created (video_id, created_at),
    CONSTRAINT fk_video_summary_video
        FOREIGN KEY (video_id) REFERENCES video (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_video_summary_task
        FOREIGN KEY (task_id) REFERENCES analysis_task (id)
        ON DELETE CASCADE,
    CONSTRAINT chk_video_summary_overview_not_blank
        CHECK (CHAR_LENGTH(TRIM(overview)) > 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE video_chapter (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    video_id BIGINT UNSIGNED NOT NULL,
    task_id BIGINT UNSIGNED NOT NULL,
    chapter_index INT UNSIGNED NOT NULL,
    title VARCHAR(255) NOT NULL,
    summary VARCHAR(2000) NOT NULL,
    start_ms BIGINT UNSIGNED NOT NULL,
    end_ms BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_video_chapter_task_index (task_id, chapter_index),
    KEY idx_video_chapter_video_index (video_id, chapter_index),
    KEY idx_video_chapter_video_start (video_id, start_ms),
    CONSTRAINT fk_video_chapter_video
        FOREIGN KEY (video_id) REFERENCES video (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_video_chapter_task
        FOREIGN KEY (task_id) REFERENCES analysis_task (id)
        ON DELETE CASCADE,
    CONSTRAINT chk_video_chapter_time_range CHECK (end_ms > start_ms),
    CONSTRAINT chk_video_chapter_title_not_blank CHECK (CHAR_LENGTH(TRIM(title)) > 0),
    CONSTRAINT chk_video_chapter_summary_not_blank CHECK (CHAR_LENGTH(TRIM(summary)) > 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE video_key_point (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    video_id BIGINT UNSIGNED NOT NULL,
    task_id BIGINT UNSIGNED NOT NULL,
    point_index INT UNSIGNED NOT NULL,
    content VARCHAR(2000) NOT NULL,
    start_ms BIGINT UNSIGNED NOT NULL,
    end_ms BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_video_key_point_task_index (task_id, point_index),
    KEY idx_video_key_point_video_index (video_id, point_index),
    KEY idx_video_key_point_video_start (video_id, start_ms),
    CONSTRAINT fk_video_key_point_video
        FOREIGN KEY (video_id) REFERENCES video (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_video_key_point_task
        FOREIGN KEY (task_id) REFERENCES analysis_task (id)
        ON DELETE CASCADE,
    CONSTRAINT chk_video_key_point_time_range CHECK (end_ms > start_ms),
    CONSTRAINT chk_video_key_point_content_not_blank CHECK (CHAR_LENGTH(TRIM(content)) > 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
