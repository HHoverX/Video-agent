ALTER TABLE video_upload_session
    DROP FOREIGN KEY fk_video_upload_video;

ALTER TABLE video_upload_session
    DROP INDEX uk_video_upload_video;

ALTER TABLE video_upload_session
    ADD KEY idx_video_upload_video (video_id);

ALTER TABLE video_upload_session
    ADD CONSTRAINT fk_video_upload_video
        FOREIGN KEY (video_id) REFERENCES video (id)
        ON DELETE SET NULL;
