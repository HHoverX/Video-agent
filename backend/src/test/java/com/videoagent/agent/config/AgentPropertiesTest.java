package com.videoagent.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AgentPropertiesTest {

    @Test
    void shouldApplyDefaults() {
        AgentProperties properties = new AgentProperties(null, 0, 0, 0, 0, 0, null);
        assertThat(properties.plannerProvider()).isEqualTo("mock");
        assertThat(properties.maxToolCalls()).isEqualTo(4);
        assertThat(properties.timeLookupWindowMs()).isEqualTo(15_000);
        assertThat(properties.maxTimeWindowMs()).isEqualTo(120_000);
        assertThat(properties.maxEvidenceItems()).isEqualTo(12);
        assertThat(properties.maxEvidenceChars()).isEqualTo(12_000);
    }

    @Test
    void shouldRejectWhenMaxTimeWindowSmallerThanLookupWindow() {
        assertThatThrownBy(() -> new AgentProperties("mock", 4, 60_000, 10_000, 12, 12_000, ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("AGENT_MAX_TIME_WINDOW_MS");
    }
}
