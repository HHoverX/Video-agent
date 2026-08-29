package com.videoagent.rag.qa;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.summary.provider.SummaryProviderProperties;
import com.videoagent.telemetry.AiUsageMetrics;
import com.videoagent.telemetry.QaTelemetryContext;
import com.videoagent.telemetry.QaTelemetryRoute;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real QA provider backed by the same LangChain4j ChatModel used for summaries.
 * Validates that the returned answer and citation indexes are well-formed; the
 * service layer additionally validates every citation index against the real
 * context before exposing any timestamp.
 */
public class LangChain4jVideoQaProvider implements VideoQaProvider {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jVideoQaProvider.class);

    private final LangChain4jQaAiService aiService;
    private final String provider;
    private final String model;
    private final int configuredMaxRetries;
    private final AiUsageMetrics usageMetrics;

    public LangChain4jVideoQaProvider(LangChain4jQaAiService aiService) {
        this(aiService, "unknown", "unknown", 0, AiUsageMetrics.noop());
    }

    public LangChain4jVideoQaProvider(
        LangChain4jQaAiService aiService,
        SummaryProviderProperties properties,
        AiUsageMetrics usageMetrics
    ) {
        this(
            aiService,
            properties.provider(),
            properties.model(),
            properties.maxRetries(),
            usageMetrics
        );
    }

    LangChain4jVideoQaProvider(
        LangChain4jQaAiService aiService,
        String provider,
        String model,
        int configuredMaxRetries,
        AiUsageMetrics usageMetrics
    ) {
        this.aiService = aiService;
        this.provider = provider;
        this.model = model;
        this.configuredMaxRetries = configuredMaxRetries;
        this.usageMetrics = usageMetrics == null ? AiUsageMetrics.noop() : usageMetrics;
    }

    @Override
    public VideoQaResult answer(VideoQaRequest request) {
        return invoke(request);
    }

    @Override
    public VideoQaResult answer(
        VideoQaRequest request,
        QaTelemetryContext telemetryContext,
        QaTelemetryRoute telemetryRoute
    ) {
        String mode = telemetryRoute == null ? "none" : telemetryRoute.value();
        long questionChars = request == null ? 0L : length(request.question());
        long contextChars = contextChars(request);
        int contextItems = request == null || request.context() == null ? 0 : request.context().size();
        usageMetrics.recordInputScale("qa", "qa_basic", provider, model, mode,
            "question_chars", questionChars);
        usageMetrics.recordInputScale("qa", "qa_basic", provider, model, mode,
            "context_chars", contextChars);
        usageMetrics.recordInputScale("qa", "qa_basic", provider, model, mode,
            "context_items", contextItems);

        long startedAtNanos = System.nanoTime();
        String outcome = "failure";
        String errorCategory = ErrorCode.LLM_SUMMARY_FAILED.name();
        try {
            VideoQaResult result = invoke(request);
            outcome = "success";
            errorCategory = "none";
            return result;
        } catch (VideoAgentException exception) {
            errorCategory = exception.errorCode().name();
            throw exception;
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
            usageMetrics.recordLogicalCall("qa", "qa_basic", provider, model, mode,
                outcome, errorCategory, durationMs);
            log.info("event=ai.logical_call scope=qa stage=qa_basic provider={} model={} requestId={} videoId={} analysisTaskId={} mode={} questionChars={} contextChars={} contextItems={} configuredMaxRetries={} durationMs={} outcome={} errorCategory={}",
                provider, model, telemetryContext == null ? null : telemetryContext.requestId(),
                telemetryContext == null ? null : telemetryContext.videoId(),
                telemetryContext == null ? null : telemetryContext.analysisTaskId(), mode,
                questionChars, contextChars, contextItems,
                configuredMaxRetries, durationMs, outcome, errorCategory);
        }
    }

    private VideoQaResult invoke(VideoQaRequest request) {
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

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    private static long contextChars(VideoQaRequest request) {
        if (request == null || request.context() == null) {
            return 0L;
        }
        return request.context().stream()
            .mapToLong(item -> item == null ? 0L : length(item.text()))
            .sum();
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
