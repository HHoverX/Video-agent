package com.videoagent.agent.planner;

import com.videoagent.agent.context.AgenticQaContext;
import com.videoagent.agent.memory.ConversationHistory;
import com.videoagent.agent.plan.RetrievalAction;
import com.videoagent.agent.plan.RetrievalPlan;
import com.videoagent.telemetry.QaTelemetryContext;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic mock planner for unit/infra tests and local development. The
 * rules are a test double, not the production agent: they route a handful of
 * fixed question shapes to the expected tools so tests are repeatable.
 */
public class MockRetrievalPlannerProvider implements RetrievalPlannerProvider {

    private static final Pattern TIME_PATTERN = Pattern.compile(
        "(\\d{1,2})[:：](\\d{1,2})|(\\d{1,2})\\s*分(\\d{1,2})?\\s*秒?|(\\d{1,2})\\s*分钟"
    );

    @Override
    public RetrievalPlan plan(AgenticQaContext context, String question) {
        return plan(context, question, ConversationHistory.empty(), null);
    }

    @Override
    public RetrievalPlan plan(
        AgenticQaContext context,
        String question,
        ConversationHistory history,
        QaTelemetryContext telemetryContext
    ) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);

        Matcher time = TIME_PATTERN.matcher(q);
        if (time.find()) {
            long timeMs = parseTimeMs(time);
            long windowMs = 15_000;
            return new RetrievalPlan("TIME_LOOKUP", "TIME_LOOKUP",
                List.of(RetrievalAction.byTime(timeMs, windowMs)));
        }

        if (containsAny(q, "总结", "主要讲", "视频介绍了什么", "overview", "summar")) {
            return new RetrievalPlan("SUMMARY", "SUMMARY",
                List.of(RetrievalAction.summary()));
        }

        if (containsAny(q, "比较", "区别", "分别", "对比", "compare", "difference")) {
            return new RetrievalPlan("MULTI_SEARCH", "MULTI_SEARCH",
                List.of(
                    RetrievalAction.search("视频中 Redis 的作用"),
                    RetrievalAction.search("视频中 RocketMQ 的作用")
                ));
        }

        return new RetrievalPlan("SEMANTIC_SEARCH", "SEMANTIC_SEARCH",
            List.of(RetrievalAction.search(contextualQuestion(question, history))));
    }

    private String contextualQuestion(String question, ConversationHistory history) {
        String current = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (history == null || history.turns().isEmpty()
            || !containsAny(current, "它", "这个", "那个", "刚才", "前面", "上述", "这种", "那")) {
            return question;
        }
        String previousQuestion = history.turns().getLast().question();
        if (previousQuestion == null || previousQuestion.isBlank()) {
            return question;
        }
        return previousQuestion + " " + question;
    }

    private long parseTimeMs(Matcher m) {
        if (m.group(1) != null && m.group(2) != null) {
            return (long) Integer.parseInt(m.group(1)) * 60_000
                + (long) Integer.parseInt(m.group(2)) * 1_000;
        }
        if (m.group(3) != null) {
            long minutes = Long.parseLong(m.group(3));
            long seconds = m.group(4) != null && !m.group(4).isBlank()
                ? Long.parseLong(m.group(4))
                : 0;
            return minutes * 60_000 + seconds * 1_000;
        }
        if (m.group(5) != null) {
            return Long.parseLong(m.group(5)) * 60_000;
        }
        return 0;
    }

    private boolean containsAny(String q, String... keywords) {
        for (String keyword : keywords) {
            if (q.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
