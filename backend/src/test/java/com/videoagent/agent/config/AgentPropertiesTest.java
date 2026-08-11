package com.videoagent.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AgentPropertiesTest {

    @Test
    void shouldApplyDefaults() {
        AgentProperties properties = new AgentProperties(null, null, null, null, null, null, null);
        assertThat(properties.plannerProvider()).isEqualTo("mock");
        assertThat(properties.maxToolCalls()).isEqualTo(4);
        assertThat(properties.timeLookupWindowMs()).isEqualTo(15_000);
        assertThat(properties.maxTimeWindowMs()).isEqualTo(120_000);
        assertThat(properties.maxEvidenceItems()).isEqualTo(12);
        assertThat(properties.maxEvidenceChars()).isEqualTo(12_000);
    }

    @Test
    void shouldRejectExplicitNonPositiveValues() {
        assertThatThrownBy(() -> new AgentProperties("mock", -1, 15_000L, 120_000L, 12, 12_000, ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("AGENT_MAX_TOOL_CALLS");
        assertThatThrownBy(() -> new AgentProperties("mock", 4, 0L, 120_000L, 12, 12_000, ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("AGENT_TIME_LOOKUP_WINDOW_MS");
        assertThatThrownBy(() -> new AgentProperties("mock", 4, 15_000L, 120_000L, 12, -10, ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("AGENT_MAX_EVIDENCE_CHARS");
    }

    @Test
    void shouldRejectValuesAboveSafetyCaps() {
        assertThatThrownBy(() -> new AgentProperties("mock", 17, 15_000L, 120_000L, 12, 12_000, ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("16");
        assertThatThrownBy(() -> new AgentProperties("mock", 4, 15_000L, 600_001L, 12, 12_000, ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("600000");
        assertThatThrownBy(() -> new AgentProperties("mock", 4, 15_000L, 120_000L, 65, 12_000, ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("64");
        assertThatThrownBy(() -> new AgentProperties("mock", 4, 15_000L, 120_000L, 12, 65_537, ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("65536");
    }

    @Test
    void shouldRejectWhenMaxTimeWindowSmallerThanLookupWindow() {
        assertThatThrownBy(() -> new AgentProperties("mock", 4, 60_000L, 10_000L, 12, 12_000, ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("AGENT_MAX_TIME_WINDOW_MS");
    }
}
