ALTER TABLE video
    ADD UNIQUE KEY uk_video_user_file_hash (user_id, file_hash);
