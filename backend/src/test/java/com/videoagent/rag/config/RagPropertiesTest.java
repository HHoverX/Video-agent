package com.videoagent.rag.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RagPropertiesTest {

    @Test
    void shouldRejectNonPositiveChunkTargetTokens() {
        assertThatThrownBy(() -> new RagProperties(0, 1, 5, 0.0f))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("RAG_CHUNK_TARGET_TOKENS");
    }
}
