package com.videoagent.rag.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HeuristicTokenEstimatorTest {

    private final HeuristicTokenEstimator estimator = new HeuristicTokenEstimator();

    @Test
    void shouldEstimateRepresentativeChineseEnglishAndTechnicalTextDeterministically() {
        String text = "Redis RocketMQ SHA-256 NullPointerException uploadId 使用 Redis 实现分布式锁";

        int first = estimator.estimateTokens(text);
        int second = estimator.estimateTokens(text);

        assertThat(first).isEqualTo(24);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void shouldUseUnicodeCodePointsAndNotEqualStringLength() {
        assertThat(estimator.estimateTokens("abcdefgh")).isEqualTo(2);
        assertThat(estimator.estimateTokens("中文")).isEqualTo(2);
        assertThat(estimator.estimateTokens("😀")).isEqualTo(1);
        assertThat(estimator.estimateTokens("😀")).isNotEqualTo("😀".length());
    }

    @Test
    void shouldAccountForNumbersPunctuationAndNewlines() {
        assertThat(estimator.estimateTokens("SHA-256")).isEqualTo(3);
        assertThat(estimator.estimateTokens("a\nb")).isEqualTo(3);
        assertThat(estimator.estimateTokens("v1.2!")).isEqualTo(4);
    }

    @Test
    void shouldNotDecreaseWhenTextIsExtended() {
        int shortEstimate = estimator.estimateTokens("Redis");
        int longEstimate = estimator.estimateTokens("Redis RocketMQ NullPointerException");

        assertThat(longEstimate).isGreaterThan(shortEstimate);
    }

    @Test
    void shouldTreatNullEmptyAndWhitespaceOnlyTextAsZero() {
        assertThat(estimator.estimateTokens(null)).isZero();
        assertThat(estimator.estimateTokens("")).isZero();
        assertThat(estimator.estimateTokens(" \t ")).isZero();
    }
}
