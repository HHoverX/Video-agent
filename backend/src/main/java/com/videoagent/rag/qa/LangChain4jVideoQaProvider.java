package com.videoagent.rag.qa;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import java.util.Locale;

/**
 * Real QA provider backed by the same LangChain4j ChatModel used for summaries.
 * Validates that the returned answer and citation indexes are well-formed; the
 * service layer additionally validates every citation index against the real
 * context before exposing any timestamp.
 */
public class LangChain4jVideoQaProvider implements VideoQaProvider {

    private final LangChain4jQaAiService aiService;

    public LangChain4jVideoQaProvider(LangChain4jQaAiService aiService) {
        this.aiService = aiService;
    }

    @Override
    public VideoQaResult answer(VideoQaRequest request) {
        try {
            VideoQaAiResponse response = aiService.answer(prompt(request));
            if (response == null || response.answer() == null || response.answer().isBlank()) {
                throw new VideoAgentException(ErrorCode.LLM_SUMMARY_INVALID, "QA 服务返回空回答");
            }
            return new VideoQaResult(response.answer(), response.citationIndexes());
        } catch (VideoAgentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new VideoAgentException(
                ErrorCode.LLM_SUMMARY_FAILED,
                "LLM 问答调用失败",
                exception
            );
        }
    }

    private String prompt(VideoQaRequest request) {
        StringBuilder context = new StringBuilder();
        for (VideoQaRequest.ContextItem item : request.context()) {
            context.append("[ITEM ")
                .append(item.index())
                .append("]\n[startMs=")
                .append(item.startMs())
                .append(",endMs=")
                .append(item.endMs())
                .append("]\n")
                .append(item.text())
                .append('\n');
        }
        return """
            问题：%s

            <context>
            %s</context>
            """.formatted(
            request.question(),
            context
        );
    }
}
