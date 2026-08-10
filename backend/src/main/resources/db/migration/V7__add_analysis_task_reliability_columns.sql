ALTER TABLE analysis_task
    ADD COLUMN last_error_code VARCHAR(64) NULL,
    ADD COLUMN last_error_message VARCHAR(1000) NULL,
    ADD COLUMN processing_generation INT UNSIGNED NOT NULL DEFAULT 0,
    ADD COLUMN processing_at DATETIME(3) NULL,
    ADD COLUMN retry_not_before DATETIME(3) NULL;
