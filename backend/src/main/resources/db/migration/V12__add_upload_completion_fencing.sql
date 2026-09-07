ALTER TABLE video_upload_session
    ADD COLUMN completing_at DATETIME(3) NULL AFTER status,
    ADD COLUMN completion_token CHAR(36) NULL AFTER completing_at,
    ADD KEY idx_video_upload_completing_timeout (status, completing_at);
