package com.videoagent.agent.qa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.agent.evidence.EvidenceItem;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RetriableException;

import java.util.List;

/**
 * Real agentic synthesizer backed by the shared LangChain4j ChatModel. The
 * evidence is passed verbatim as untrusted data; the model can only cite the
 * evidence ids it was given.
 */
public class LangChain4jAgenticAnswerProvider implements AgenticAnswerProvider {

    private final LangChain4jAgenticAnswerAiService aiService;
    private final ObjectMapper objectMapper;

    public LangChain4jAgenticAnswerProvider(
        LangChain4jAgenticAnswerAiService aiService,
        ObjectMapper objectMapper
    ) {
        this.aiService = aiService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgenticQaResult synthesize(String question, List<EvidenceItem> evidence) {
        try {
            AgenticQaAiResponse response = aiService.synthesize(prompt(question, evidence));
            if (response == null || response.answer() == null || response.answer().isBlank()) {
                throw new VideoAgentException(ErrorCode.LLM_SUMMARY_INVALID, "问答服务返回空回答");
            }
            return new AgenticQaResult(response.answer(), response.citationEvidenceIds());
        } catch (VideoAgentException exception) {
            throw exception;
        } catch (HttpException exception) {
            int status = exception.statusCode();
            if (status == 408 || status == 429 || status >= 500) {
                throw transientFailure(exception);
            }
            throw providerRejected("LLM 问答服务拒绝了请求 (HTTP " + status + ")", exception);
        } catch (NonRetriableException exception) {
            throw providerRejected("LLM 问答服务拒绝了请求", exception);
        } catch (RetriableException exception) {
            throw transientFailure(exception);
        } catch (RuntimeException exception) {
            throw new VideoAgentException(ErrorCode.INTERNAL_ERROR, "LLM 问答调用发生内部错误", exception);
        }
    }

    private String prompt(String question, List<EvidenceItem> evidence) {
        List<PromptEvidence> promptEvidence = evidence.stream()
            .map(item -> new PromptEvidence(
                item.evidenceId(), item.sourceType().name(), item.text(), item.startMs(), item.endMs()))
            .toList();
        try {
            return objectMapper.writeValueAsString(new AnswerPrompt(question, promptEvidence));
        } catch (JsonProcessingException exception) {
            throw new VideoAgentException(
                ErrorCode.INTERNAL_ERROR,
                "问答 Evidence 序列化失败",
                exception
            );
        }
    }

    private VideoAgentException transientFailure(RuntimeException cause) {
        return new VideoAgentException(ErrorCode.LLM_SUMMARY_FAILED, "LLM 问答服务暂时不可用", cause);
    }

    private VideoAgentException providerRejected(String message, RuntimeException cause) {
        return new VideoAgentException(ErrorCode.LLM_PROVIDER_REJECTED, message, cause);
    }

    private record AnswerPrompt(String question, List<PromptEvidence> evidence) {
    }

    private record PromptEvidence(
        String evidenceId,
        String sourceType,
        String text,
        Long startMs,
        Long endMs
    ) {
    }
}
