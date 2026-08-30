package com.videoagent.agent.planner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.agent.context.AgenticQaContext;
import com.videoagent.agent.memory.ConversationHistory;
import com.videoagent.agent.memory.ConversationTurn;
import com.videoagent.agent.plan.RetrievalAction;
import com.videoagent.agent.plan.RetrievalPlan;
import com.videoagent.agent.plan.RetrievalTool;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.telemetry.AiUsageMetrics;
import com.videoagent.telemetry.QaTelemetryContext;
import com.videoagent.telemetry.QaTelemetryRoute;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RetriableException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real retrieval planner backed by the same LangChain4j ChatModel used for QA
 * and summaries. It receives the current question, bounded conversation
 * history, and a compact metadata summary (has summary, transcript mode, rag
 * status) — never the full transcript — and returns a strict structured plan.
 */
public class LangChain4jRetrievalPlanner implements RetrievalPlannerProvider {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jRetrievalPlanner.class);

    private final LangChain4jPlannerAiService aiService;
    private final ObjectMapper objectMapper;
    private final String provider;
    private final String model;
    private final int configuredMaxRetries;
    private final AiUsageMetrics usageMetrics;

    public LangChain4jRetrievalPlanner(LangChain4jPlannerAiService aiService) {
        this(aiService, new ObjectMapper(), "unknown", "unknown", 0, AiUsageMetrics.noop());
    }

    LangChain4jRetrievalPlanner(
        LangChain4jPlannerAiService aiService,
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
    public RetrievalPlan plan(
        AgenticQaContext context,
        String question,
        ConversationHistory history,
        QaTelemetryContext telemetryContext
    ) {
        String compactState = compactState(context);
        long questionChars = length(question);
        long compactStateChars = compactState.length();
        usageMetrics.recordInputScale("qa", "qa_planner", provider, model, QaTelemetryRoute.AGENTIC.value(),
            "question_chars", questionChars);
        usageMetrics.recordInputScale("qa", "qa_planner", provider, model, QaTelemetryRoute.AGENTIC.value(),
            "compact_state_chars", compactStateChars);

        long startedAtNanos = System.nanoTime();
        String outcome = "failure";
        String errorCategory = ErrorCode.INTERNAL_ERROR.name();
        int plannedActionCount = 0;
        try {
            RetrievalPlan plan = invoke(prompt(question, history, compactState));
            plannedActionCount = plan.actions() == null ? 0 : plan.actions().size();
            outcome = "success";
            errorCategory = "none";
            return plan;
        } catch (VideoAgentException exception) {
            errorCategory = exception.errorCode().name();
            throw exception;
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
            usageMetrics.recordLogicalCall("qa", "qa_planner", provider, model, QaTelemetryRoute.AGENTIC.value(),
                outcome, errorCategory, durationMs);
            log.info("event=ai.logical_call scope=qa stage=qa_planner provider={} model={} requestId={} videoId={} analysisTaskId={} mode={} questionChars={} compactStateChars={} plannedActionCount={} configuredMaxRetries={} durationMs={} outcome={} errorCategory={}",
                provider, model, telemetryContext == null ? null : telemetryContext.requestId(),
                telemetryContext == null ? null : telemetryContext.videoId(),
                telemetryContext == null ? null : telemetryContext.analysisTaskId(),
                QaTelemetryRoute.AGENTIC.value(), questionChars, compactStateChars, plannedActionCount,
                configuredMaxRetries, durationMs, outcome, errorCategory);
        }
    }

    private RetrievalPlan invoke(String prompt) {
        try {
            PlannerAiResponse response = aiService.plan(prompt);
            if (response == null || response.actions() == null) {
                throw new VideoAgentException(ErrorCode.INVALID_REQUEST, "Planner 返回空结果");
            }
            List<RetrievalAction> actions = new ArrayList<>();
            for (PlannerAction action : response.actions()) {
                if (action == null || action.tool() == null) {
                    throw new VideoAgentException(ErrorCode.INVALID_REQUEST, "Planner 返回无效工具");
                }
                RetrievalTool tool = parseTool(action.tool());
                actions.add(switch (tool) {
                    case GET_VIDEO_SUMMARY -> RetrievalAction.summary();
                    case GET_TRANSCRIPT_BY_TIME -> new RetrievalAction(
                        RetrievalTool.GET_TRANSCRIPT_BY_TIME,
                        null,
                        action.timeMs(),
                        action.windowMs()
                    );
                    case SEARCH_TRANSCRIPT -> RetrievalAction.search(action.query());
                });
            }
            return new RetrievalPlan(response.intent(), response.strategyLabel(), actions);
        } catch (VideoAgentException exception) {
            throw exception;
        } catch (HttpException exception) {
            int status = exception.statusCode();
            if (status == 408 || status == 429 || status >= 500) {
                throw transientFailure(exception);
            }
            throw providerRejected("Planner Provider 拒绝了请求 (HTTP " + status + ")", exception);
        } catch (NonRetriableException exception) {
            throw providerRejected("Planner Provider 拒绝了请求", exception);
        } catch (RetriableException exception) {
            throw transientFailure(exception);
        } catch (RuntimeException exception) {
            throw new VideoAgentException(
                ErrorCode.INTERNAL_ERROR,
                "检索规划发生内部错误",
                exception
            );
        }
    }

    private VideoAgentException transientFailure(RuntimeException cause) {
        return new VideoAgentException(
            ErrorCode.AGENT_PLANNER_FAILED,
            "检索规划服务暂时不可用",
            cause
        );
    }

    private VideoAgentException providerRejected(String message, RuntimeException cause) {
        return new VideoAgentException(ErrorCode.LLM_PROVIDER_REJECTED, message, cause);
    }

    private RetrievalTool parseTool(String raw) {
        for (RetrievalTool tool : RetrievalTool.values()) {
            if (tool.name().equalsIgnoreCase(raw)) {
                return tool;
            }
        }
        throw new VideoAgentException(ErrorCode.INVALID_REQUEST, "Planner 返回未知工具: " + raw);
    }

    private String prompt(String question, ConversationHistory history, String compactState) {
        List<ConversationTurn> turns = history == null ? List.of() : history.turns();
        try {
            return objectMapper.writeValueAsString(new PlannerPrompt(question, turns, compactState));
        } catch (JsonProcessingException exception) {
            throw new VideoAgentException(
                ErrorCode.INTERNAL_ERROR,
                "检索规划上下文序列化失败",
                exception
            );
        }
    }

    private String compactState(AgenticQaContext context) {
        return """
            - 是否存在字幕：%s
            - 是否有已生成的摘要：%s
            - 字幕上下文模式：%s
            - RAG 索引状态：%s
            """.formatted(
            context.hasTranscript() ? "是" : "否",
            context.hasSummary() ? "是" : "否",
            context.contextMode() == null ? "未知" : context.contextMode().name(),
            context.ragStatus() == null ? "未知" : context.ragStatus()
        );
    }

    private static long length(String value) {
        return value == null ? 0L : value.length();
    }

    private record PlannerPrompt(
        String currentQuestion,
        List<ConversationTurn> conversationHistory,
        String compactVideoState
    ) {
    }
}
