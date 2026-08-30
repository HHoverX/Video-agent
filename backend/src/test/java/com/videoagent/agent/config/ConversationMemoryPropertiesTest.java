package com.videoagent.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class ConversationMemoryPropertiesTest {

    @Test
    void shouldApplyDefaults() {
        ConversationMemoryProperties properties = new ConversationMemoryProperties(null, null, null);

        assertThat(properties.ttl()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.maxTurns()).isEqualTo(6);
        assertThat(properties.maxHistoryChars()).isEqualTo(6_000);
    }

    @Test
    void shouldRejectNonPositiveValues() {
        assertThatThrownBy(() -> new ConversationMemoryProperties(Duration.ZERO, 6, 6_000))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConversationMemoryProperties(Duration.ofHours(24), 0, 6_000))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConversationMemoryProperties(Duration.ofHours(24), 21, 6_000))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConversationMemoryProperties(Duration.ofHours(24), 6, 255))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConversationMemoryProperties(Duration.ofHours(24), 6, 12_001))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
