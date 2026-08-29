package com.videoagent.agent.qa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.agent.evidence.EvidenceItem;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.summary.provider.SummaryProviderProperties;
import com.videoagent.telemetry.AiUsageMetrics;
import com.videoagent.telemetry.QaTelemetryContext;
import com.videoagent.telemetry.QaTelemetryRoute;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RetriableException;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real agentic synthesizer backed by the shared LangChain4j ChatModel. The
 * evidence is passed verbatim as untrusted data; the model can only cite the
 * evidence ids it was given.
 */
public class LangChain4jAgenticAnswerProvider implements AgenticAnswerProvider {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jAgenticAnswerProvider.class);

    private final LangChain4jAgenticAnswerAiService aiService;
    private final ObjectMapper objectMapper;
    private final String provider;
    private final String model;
    private final int configuredMaxRetries;
    private final AiUsageMetrics usageMetrics;

    public LangChain4jAgenticAnswerProvider(
        LangChain4jAgenticAnswerAiService aiService,
        ObjectMapper objectMapper
    ) {
        this(aiService, objectMapper, "unknown", "unknown", 0, AiUsageMetrics.noop());
    }

    public LangChain4jAgenticAnswerProvider(
        LangChain4jAgenticAnswerAiService aiService,
        ObjectMapper objectMapper,
        SummaryProviderProperties properties,
        AiUsageMetrics usageMetrics
    ) {
        this(
            aiService,
            objectMapper,
            properties.provider(),
            properties.model(),
            properties.maxRetries(),
            usageMetrics
        );
    }

    LangChain4jAgenticAnswerProvider(
        LangChain4jAgenticAnswerAiService aiService,
        ObjectMapper objectMapper,
        String provider,
        String model,
        int configuredMaxRetries,
        AiUsageMetrics usageMetrics
    ) {
        this.aiService = aiService;
        this.objectMapper = objectMapper;
        this.provider = provider;
        this.model = model;
        this.configuredMaxRetries = configuredMaxRetries;
        this.usageMetrics = usageMetrics == null ? AiUsageMetrics.noop() : usageMetrics;
    }

    @Override
    public AgenticQaResult synthesize(String question, List<EvidenceItem> evidence) {
        return invoke(question, evidence);
    }

    @Override
    public AgenticQaResult synthesize(
        String question,
        List<EvidenceItem> evidence,
        QaTelemetryContext telemetryContext,
        int toolActionCount
    ) {
        long questionChars = length(question);
        long evidenceChars = evidenceChars(evidence);
        int evidenceItems = evidence == null ? 0 : evidence.size();
        usageMetrics.recordInputScale("qa", "qa_synthesis", provider, model, QaTelemetryRoute.AGENTIC.value(),
            "question_chars", questionChars);
        usageMetrics.recordInputScale("qa", "qa_synthesis", provider, model, QaTelemetryRoute.AGENTIC.value(),
            "evidence_chars", evidenceChars);
        usageMetrics.recordInputScale("qa", "qa_synthesis", provider, model, QaTelemetryRoute.AGENTIC.value(),
            "evidence_items", evidenceItems);
        usageMetrics.recordInputScale("qa", "qa_synthesis", provider, model, QaTelemetryRoute.AGENTIC.value(),
            "tool_action_count", toolActionCount);

        long startedAtNanos = System.nanoTime();
        String outcome = "failure";
        String errorCategory = ErrorCode.INTERNAL_ERROR.name();
        try {
            AgenticQaResult result = invoke(question, evidence);
            outcome = "success";
            errorCategory = "none";
            return result;
        } catch (VideoAgentException exception) {
            errorCategory = exception.errorCode().name();
            throw exception;
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
            usageMetrics.recordLogicalCall("qa", "qa_synthesis", provider, model, QaTelemetryRoute.AGENTIC.value(),
                outcome, errorCategory, durationMs);
            log.info("event=ai.logical_call scope=qa stage=qa_synthesis provider={} model={} requestId={} videoId={} analysisTaskId={} mode={} questionChars={} evidenceChars={} evidenceItems={} toolActionCount={} configuredMaxRetries={} durationMs={} outcome={} errorCategory={}",
                provider, model, telemetryContext == null ? null : telemetryContext.requestId(),
                telemetryContext == null ? null : telemetryContext.videoId(),
                telemetryContext == null ? null : telemetryContext.analysisTaskId(),
                QaTelemetryRoute.AGENTIC.value(), questionChars, evidenceChars, evidenceItems,
                Math.max(0, toolActionCount), configuredMaxRetries, durationMs, outcome, errorCategory);
        }
    }

    private AgenticQaResult invoke(String question, List<EvidenceItem> evidence) {
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

    private static long length(String value) {
        return value == null ? 0L : value.length();
    }

    private static long evidenceChars(List<EvidenceItem> evidence) {
        if (evidence == null) {
            return 0L;
        }
        return evidence.stream().mapToLong(item -> item == null ? 0L : length(item.text())).sum();
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
