UPDATE video_upload_session target
JOIN (
    SELECT id
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY user_id, expected_sha256
                   ORDER BY CASE WHEN status = 'COMPLETING' THEN 0 ELSE 1 END,
                            expires_at DESC, created_at DESC, id DESC
               ) AS row_number_in_hash
        FROM video_upload_session
        WHERE expected_sha256 IS NOT NULL
          AND status IN ('CREATED', 'UPLOADING', 'FAILED', 'COMPLETING')
    ) ranked
    WHERE row_number_in_hash > 1
) duplicate_session ON duplicate_session.id = target.id
SET target.status = 'EXPIRED',
    target.updated_at = CURRENT_TIMESTAMP(3);

ALTER TABLE video_upload_session
    ADD COLUMN active_expected_sha256 CHAR(64)
        GENERATED ALWAYS AS (
            CASE
                WHEN status IN ('CREATED', 'UPLOADING', 'FAILED', 'COMPLETING')
                    THEN expected_sha256
                ELSE NULL
            END
        ) STORED,
    ADD UNIQUE KEY uk_video_upload_active_hash (user_id, active_expected_sha256);
