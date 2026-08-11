package com.videoagent.agent.plan;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import java.util.List;

/**
 * Stable backend-derived strategy label. Planner-provided intent and labels
 * are untrusted telemetry input and never control execution or API output.
 */
public enum RetrievalStrategy {
    SUMMARY,
    TIME_LOOKUP,
    SEMANTIC_SEARCH,
    MULTI_SEARCH;

    public static RetrievalStrategy derive(List<RetrievalAction> actions) {
        if (actions == null || actions.isEmpty()) {
            throw invalid();
        }
        if (actions.size() == 1) {
            RetrievalTool tool = actions.getFirst().tool();
            return switch (tool) {
                case GET_VIDEO_SUMMARY -> SUMMARY;
                case GET_TRANSCRIPT_BY_TIME -> TIME_LOOKUP;
                case SEARCH_TRANSCRIPT -> SEMANTIC_SEARCH;
            };
        }
        if (actions.stream().allMatch(action -> action.tool() == RetrievalTool.SEARCH_TRANSCRIPT)) {
            return MULTI_SEARCH;
        }
        throw invalid();
    }

    private static VideoAgentException invalid() {
        return new VideoAgentException(
            ErrorCode.INVALID_REQUEST,
            "检索规划无效：multiple actions must all use SEARCH_TRANSCRIPT"
        );
    }
}
