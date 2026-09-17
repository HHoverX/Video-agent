package com.videoagent.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class ConversationMemoryPropertiesTest {

    @Test
    void shouldApplyDefaults() {
        ConversationMemoryProperties properties = properties(null, null, null, null, null, null, null);

        assertThat(properties.ttl()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.recentTurns()).isEqualTo(6);
        assertThat(properties.compactTriggerTurns()).isEqualTo(10);
        assertThat(properties.compactBatchTurns()).isEqualTo(4);
        assertThat(properties.maxSummaryChars()).isEqualTo(2_000);
        assertThat(properties.maxContextChars()).isEqualTo(6_000);
        assertThat(properties.compactLockTtl()).isEqualTo(Duration.ofMinutes(3));
    }

    @Test
    void shouldRejectInvalidWindowBudgetAndTtlValues() {
        assertThatThrownBy(() -> properties(Duration.ZERO, 6, 10, 4, 2_000, 6_000, Duration.ofMinutes(3)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(Duration.ofHours(24), 0, 10, 4, 2_000, 6_000, Duration.ofMinutes(3)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(Duration.ofHours(24), 6, 6, 4, 2_000, 6_000, Duration.ofMinutes(3)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(Duration.ofHours(24), 6, 10, 3, 2_000, 6_000, Duration.ofMinutes(3)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(Duration.ofHours(24), 6, 10, 4, 6_001, 6_000, Duration.ofMinutes(3)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(Duration.ofHours(24), 6, 10, 4, 2_000, 255, Duration.ofMinutes(3)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(Duration.ofHours(24), 6, 10, 4, 2_000, 6_000, Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private ConversationMemoryProperties properties(
        Duration ttl,
        Integer recentTurns,
        Integer compactTriggerTurns,
        Integer compactBatchTurns,
        Integer maxSummaryChars,
        Integer maxContextChars,
        Duration compactLockTtl
    ) {
        return new ConversationMemoryProperties(
            ttl,
            recentTurns,
            compactTriggerTurns,
            compactBatchTurns,
            maxSummaryChars,
            maxContextChars,
            compactLockTtl
        );
    }
}
