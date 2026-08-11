package com.videoagent.agent.planner;

import static org.assertj.core.api.Assertions.assertThat;

import com.videoagent.agent.context.AgenticQaContext;
import com.videoagent.agent.plan.RetrievalAction;
import com.videoagent.agent.plan.RetrievalPlan;
import com.videoagent.agent.plan.RetrievalTool;
import com.videoagent.rag.context.QaContextMode;

import org.junit.jupiter.api.Test;

class MockRetrievalPlannerProviderTest {

    private final MockRetrievalPlannerProvider planner = new MockRetrievalPlannerProvider();
    private final AgenticQaContext context = new AgenticQaContext(
        1L, 7L, 3L, QaContextMode.RAG, true, true, "READY"
    );

    @Test
    void shouldPlanSummaryForSummaryQuestion() {
        RetrievalPlan plan = planner.plan(context, "这个视频主要讲了什么？");
        assertThat(plan.intent()).isEqualTo("SUMMARY");
        assertThat(plan.actions()).hasSize(1);
        assertThat(plan.actions().getFirst().tool()).isEqualTo(RetrievalTool.GET_VIDEO_SUMMARY);
    }

    @Test
    void shouldPlanTimeLookupForTimeQuestion() {
        RetrievalPlan plan = planner.plan(context, "3分20秒在讲什么？");
        assertThat(plan.intent()).isEqualTo("TIME_LOOKUP");
        assertThat(plan.actions()).hasSize(1);
        RetrievalAction action = plan.actions().getFirst();
        assertThat(action.tool()).isEqualTo(RetrievalTool.GET_TRANSCRIPT_BY_TIME);
        assertThat(action.timeMs()).isEqualTo(200_000L);
        assertThat(action.windowMs()).isEqualTo(15_000L);
    }

    @Test
    void shouldPlanSemanticSearchForSemanticQuestion() {
        RetrievalPlan plan = planner.plan(context, "为什么作者认为 Redis 适合保存任务进度？");
        assertThat(plan.intent()).isEqualTo("SEMANTIC_SEARCH");
        assertThat(plan.actions()).hasSize(1);
        assertThat(plan.actions().getFirst().tool()).isEqualTo(RetrievalTool.SEARCH_TRANSCRIPT);
        assertThat(plan.actions().getFirst().query()).contains("Redis");
    }

    @Test
    void shouldPlanMultipleSearchesForComparisonQuestion() {
        RetrievalPlan plan = planner.plan(context, "比较视频中 Redis 和 RocketMQ 的作用。");
        assertThat(plan.intent()).isEqualTo("MULTI_SEARCH");
        assertThat(plan.actions()).hasSize(2);
        assertThat(plan.actions()).allMatch(a -> a.tool() == RetrievalTool.SEARCH_TRANSCRIPT);
        assertThat(plan.actions().get(0).query()).contains("Redis");
        assertThat(plan.actions().get(1).query()).contains("RocketMQ");
    }

    @Test
    void shouldPlanTimeForClockStyleTimestamp() {
        RetrievalPlan plan = planner.plan(context, "10:30 附近说了什么？");
        assertThat(plan.actions()).hasSize(1);
        assertThat(plan.actions().getFirst().timeMs()).isEqualTo(630_000L);
    }

    @Test
    void shouldNeverEmitUserIdOrVideoIdInActions() {
        RetrievalPlan plan = planner.plan(context, "比较 Redis 和 RocketMQ 的作用。");
        for (RetrievalAction action : plan.actions()) {
            // The schema has no userId/videoId fields; assert no such info can
            // even leak through a query.
            assertThat(action.query()).doesNotContain("videoId", "userId");
        }
    }
}
