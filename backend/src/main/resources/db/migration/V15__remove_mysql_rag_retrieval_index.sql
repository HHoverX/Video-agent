UPDATE video_rag_index
SET status = 'NOT_BUILT',
    chunk_count = 0,
    build_token = NULL,
    build_started_at = NULL,
    last_error_code = NULL,
    last_error_message = NULL,
    updated_at = CURRENT_TIMESTAMP(3)
WHERE context_mode = 'RAG';

DROP TABLE video_rag_chunk;
