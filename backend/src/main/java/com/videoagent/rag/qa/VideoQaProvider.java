package com.videoagent.rag.qa;

/**
 * Provides grounded question answering over a given transcript context.
 * Separate from the summary provider because summarization and QA have
 * different prompts, validation and citation semantics. The underlying chat
 * model is reused.
 */
public interface VideoQaProvider {

    VideoQaResult answer(VideoQaRequest request);
}
