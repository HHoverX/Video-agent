package com.videoagent.agent.plan;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videoagent.agent.config.AgentProperties;
import com.videoagent.agent.context.AgenticQaContext;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.rag.context.QaContextMode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

class RetrievalPlanValidatorTest {

    private final AgentProperties properties = new AgentProperties("mock", 4, 15_000, 120_000, 12, 12_000, "");
    private RetrievalPlanValidator validator;
    private final AgenticQaContext context = new AgenticQaContext(
        1L, 7L, 3L, QaContextMode.RAG, true, true, "READY"
    );

    @BeforeEach
    void setUp() {
        validator = new RetrievalPlanValidator(properties);
    }

    @Test
    void shouldAcceptValidPlan() {
        RetrievalPlan plan = new RetrievalPlan("SEMANTIC_SEARCH", "SEMANTIC_SEARCH",
            List.of(RetrievalAction.search("Redis 的作用")));
        assertThatCode(() -> validator.validate(plan, context)).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectEmptyActions() {
        RetrievalPlan plan = new RetrievalPlan("SUMMARY", "SUMMARY", List.of());
        assertThatThrownBy(() -> validator.validate(plan, context))
            .isInstanceOfSatisfying(VideoAgentException.class, e ->
                org.assertj.core.api.Assertions.assertThat(e.getMessage()).contains("no actions"));
    }

    @Test
    void shouldRejectTooManyToolCalls() {
        RetrievalPlan plan = new RetrievalPlan("MULTI_SEARCH", "MULTI_SEARCH",
            List.of(
                RetrievalAction.search("a"), RetrievalAction.search("b"),
                RetrievalAction.search("c"), RetrievalAction.search("d"),
                RetrievalAction.search("e")
            ));
        assertThatThrownBy(() -> validator.validate(plan, context))
            .isInstanceOfSatisfying(VideoAgentException.class, e ->
                org.assertj.core.api.Assertions.assertThat(e.getMessage()).contains("AGENT_MAX_TOOL_CALLS"));
    }

    @Test
    void shouldRejectBlankSearchQuery() {
        RetrievalPlan plan = new RetrievalPlan("SEMANTIC_SEARCH", "SEMANTIC_SEARCH",
            List.of(new RetrievalAction(RetrievalTool.SEARCH_TRANSCRIPT, "   ", null, null)));
        assertThatThrownBy(() -> validator.validate(plan, context))
            .isInstanceOf(VideoAgentException.class);
    }

    @Test
    void shouldRejectOversizedSearchQuery() {
        RetrievalPlan plan = new RetrievalPlan("SEMANTIC_SEARCH", "SEMANTIC_SEARCH",
            List.of(new RetrievalAction(RetrievalTool.SEARCH_TRANSCRIPT, "x".repeat(501), null, null)));
        assertThatThrownBy(() -> validator.validate(plan, context))
            .isInstanceOfSatisfying(VideoAgentException.class, e ->
                org.assertj.core.api.Assertions.assertThat(e.getMessage()).contains("500"));
    }

    @Test
    void shouldRejectNegativeTime() {
        RetrievalPlan plan = new RetrievalPlan("TIME_LOOKUP", "TIME_LOOKUP",
            List.of(RetrievalAction.byTime(-1, 15_000)));
        assertThatThrownBy(() -> validator.validate(plan, context))
            .isInstanceOfSatisfying(VideoAgentException.class, e ->
                org.assertj.core.api.Assertions.assertThat(e.getMessage()).contains("non-negative"));
    }

    @Test
    void shouldRejectOversizedWindow() {
        RetrievalPlan plan = new RetrievalPlan("TIME_LOOKUP", "TIME_LOOKUP",
            List.of(RetrievalAction.byTime(60_000, 6 * 60 * 60 * 1000L)));
        assertThatThrownBy(() -> validator.validate(plan, context))
            .isInstanceOfSatisfying(VideoAgentException.class, e ->
                org.assertj.core.api.Assertions.assertThat(e.getMessage()).contains("AGENT_MAX_TIME_WINDOW_MS"));
    }

    @Test
    void shouldRejectMissingTimeForTimeTool() {
        RetrievalPlan plan = new RetrievalPlan("TIME_LOOKUP", "TIME_LOOKUP",
            List.of(new RetrievalAction(RetrievalTool.GET_TRANSCRIPT_BY_TIME, null, null, 15_000L)));
        assertThatThrownBy(() -> validator.validate(plan, context))
            .isInstanceOfSatisfying(VideoAgentException.class, e ->
                org.assertj.core.api.Assertions.assertThat(e.getMessage()).contains("timeMs"));
    }

    @Test
    void shouldRejectSummaryActionWithParameters() {
        RetrievalPlan plan = new RetrievalPlan("SUMMARY", "SUMMARY",
            List.of(new RetrievalAction(RetrievalTool.GET_VIDEO_SUMMARY, "unexpected", null, null)));
        assertThatThrownBy(() -> validator.validate(plan, context))
            .isInstanceOfSatisfying(VideoAgentException.class, e ->
                org.assertj.core.api.Assertions.assertThat(e.getMessage()).contains("must not carry"));
    }

    @Test
    void shouldRejectUnknownToolRepresentation() {
        // A plan cannot even carry an unknown tool because the enum is closed;
        // the real planner maps unknown strings to a business error. Here we
        // verify the validator rejects a null-tool action.
        RetrievalPlan plan = new RetrievalPlan("X", "X", List.of(new RetrievalAction(null, "q", null, null)));
        assertThatThrownBy(() -> validator.validate(plan, context))
            .isInstanceOf(VideoAgentException.class);
    }

    @Test
    void shouldAcceptTimeToolWithDefaultWindow() {
        RetrievalPlan plan = new RetrievalPlan("TIME_LOOKUP", "TIME_LOOKUP",
            List.of(new RetrievalAction(RetrievalTool.GET_TRANSCRIPT_BY_TIME, null, 200_000L, null)));
        assertThatCode(() -> validator.validate(plan, context)).doesNotThrowAnyException();
    }
}
