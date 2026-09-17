package com.videoagent.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class ConversationCompactionExecutorPropertiesTest {

    @Test
    void shouldApplyBoundedExecutorDefaults() {
        ConversationCompactionExecutorProperties properties =
            new ConversationCompactionExecutorProperties(null, null, null, null);

        assertThat(properties.coreSize()).isEqualTo(1);
        assertThat(properties.maxSize()).isEqualTo(2);
        assertThat(properties.queueCapacity()).isEqualTo(100);
        assertThat(properties.keepAlive()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void shouldRejectInvalidExecutorConfiguration() {
        assertThatThrownBy(() -> new ConversationCompactionExecutorProperties(0, 2, 10, Duration.ofSeconds(30)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConversationCompactionExecutorProperties(2, 1, 10, Duration.ofSeconds(30)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConversationCompactionExecutorProperties(1, 2, -1, Duration.ofSeconds(30)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConversationCompactionExecutorProperties(1, 2, 10, Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
