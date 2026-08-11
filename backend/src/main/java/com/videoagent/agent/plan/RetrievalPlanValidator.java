package com.videoagent.agent.plan;

import com.videoagent.agent.config.AgentProperties;
import com.videoagent.agent.context.AgenticQaContext;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Validates an LLM-produced plan before any tool runs. The LLM output is
 * untrusted input. The plan schema is closed (no arbitrary tools, no
 * userId/videoId), the action count is bounded, and every parameter is range
 * checked. An invalid plan fails the whole agentic request (the caller decides
 * whether to fall back to Basic QA).
 */
@Component
public class RetrievalPlanValidator {

    private final AgentProperties properties;

    public RetrievalPlanValidator(AgentProperties properties) {
        this.properties = properties;
    }

    public void validate(RetrievalPlan plan, AgenticQaContext context) {
        if (plan == null || plan.actions() == null || plan.actions().isEmpty()) {
            throw invalid("planner returned no actions");
        }
        if (plan.actions().size() > properties.maxToolCalls()) {
            throw invalid("planner returned " + plan.actions().size()
                + " actions, exceeding AGENT_MAX_TOOL_CALLS=" + properties.maxToolCalls());
        }
        for (RetrievalAction action : plan.actions()) {
            validateAction(action);
        }
    }

    private void validateAction(RetrievalAction action) {
        if (action == null || action.tool() == null) {
            throw invalid("planner returned an action without a valid tool");
        }
        switch (action.tool()) {
            case GET_VIDEO_SUMMARY -> {
                // Must not carry meaningless parameters.
                if (action.query() != null || action.timeMs() != null || action.windowMs() != null) {
                    throw invalid("GET_VIDEO_SUMMARY must not carry parameters");
                }
            }
            case GET_TRANSCRIPT_BY_TIME -> {
                if (action.timeMs() == null) {
                    throw invalid("GET_TRANSCRIPT_BY_TIME requires timeMs");
                }
                if (action.timeMs() < 0) {
                    throw invalid("GET_TRANSCRIPT_BY_TIME timeMs must be non-negative");
                }
                long windowMs = action.windowMs() == null
                    ? properties.timeLookupWindowMs()
                    : action.windowMs();
                if (windowMs <= 0) {
                    throw invalid("GET_TRANSCRIPT_BY_TIME windowMs must be positive");
                }
                if (windowMs > properties.maxTimeWindowMs()) {
                    throw invalid("GET_TRANSCRIPT_BY_TIME windowMs exceeds AGENT_MAX_TIME_WINDOW_MS");
                }
            }
            case SEARCH_TRANSCRIPT -> {
                if (action.query() == null || action.query().isBlank()) {
                    throw invalid("SEARCH_TRANSCRIPT requires a non-blank query");
                }
                if (action.query().length() > 500) {
                    throw invalid("SEARCH_TRANSCRIPT query exceeds 500 chars");
                }
                if (action.timeMs() != null || action.windowMs() != null) {
                    throw invalid("SEARCH_TRANSCRIPT must not carry time parameters");
                }
            }
        }
    }

    private VideoAgentException invalid(String reason) {
        return new VideoAgentException(ErrorCode.INVALID_REQUEST, "检索规划无效：" + reason);
    }
}
