package com.videoagent.agent.planner;

import com.videoagent.agent.context.AgenticQaContext;
import com.videoagent.agent.plan.RetrievalAction;
import com.videoagent.agent.plan.RetrievalPlan;
import com.videoagent.agent.plan.RetrievalTool;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import java.util.ArrayList;
import java.util.List;

/**
 * Real retrieval planner backed by the same LangChain4j ChatModel used for QA
 * and summaries. It receives only the question plus a compact metadata summary
 * (has summary, transcript mode, rag status) — never the full transcript — and
 * returns a strict structured plan.
 */
public class LangChain4jRetrievalPlanner implements RetrievalPlannerProvider {

    private final LangChain4jPlannerAiService aiService;

    public LangChain4jRetrievalPlanner(LangChain4jPlannerAiService aiService) {
        this.aiService = aiService;
    }

    @Override
    public RetrievalPlan plan(AgenticQaContext context, String question) {
        try {
            PlannerAiResponse response = aiService.plan(prompt(context, question));
            if (response == null || response.intent() == null || response.actions() == null) {
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
                    case GET_TRANSCRIPT_BY_TIME -> RetrievalAction.byTime(
                        action.timeMs() == null ? 0 : action.timeMs(),
                        action.windowMs() == null ? 0 : action.windowMs()
                    );
                    case SEARCH_TRANSCRIPT -> RetrievalAction.search(action.query());
                });
            }
            return new RetrievalPlan(response.intent(), response.strategyLabel(), actions);
        } catch (VideoAgentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new VideoAgentException(
                ErrorCode.LLM_SUMMARY_FAILED,
                "检索规划调用失败",
                exception
            );
        }
    }

    private RetrievalTool parseTool(String raw) {
        for (RetrievalTool tool : RetrievalTool.values()) {
            if (tool.name().equalsIgnoreCase(raw)) {
                return tool;
            }
        }
        throw new VideoAgentException(ErrorCode.INVALID_REQUEST, "Planner 返回未知工具: " + raw);
    }

    private String prompt(AgenticQaContext context, String question) {
        return """
            问题：%s

            当前视频状态：
            - 是否存在字幕：%s
            - 是否有已生成的摘要：%s
            - 字幕上下文模式：%s
            - RAG 索引状态：%s

            请只规划使用哪些工具来回答该问题，不要直接回答。
            可用的工具：GET_VIDEO_SUMMARY, GET_TRANSCRIPT_BY_TIME, SEARCH_TRANSCRIPT。
            时间问题请把“3分20秒”转换为毫秒 timeMs=200000，并给出合理的 windowMs（默认 15000）。
            比较类问题可以使用多个 SEARCH_TRANSCRIPT 动作分别检索不同主题。
            """.formatted(
            question,
            context.hasTranscript() ? "是" : "否",
            context.hasSummary() ? "是" : "否",
            context.contextMode() == null ? "未知" : context.contextMode().name(),
            context.ragStatus() == null ? "未知" : context.ragStatus()
        );
    }
}
