UPDATE video_rag_index
SET status = 'NOT_BUILT'
WHERE status = 'NOT_REQUIRED';

ALTER TABLE video_rag_index
    DROP COLUMN context_mode,
    DROP COLUMN transcript_chars;
