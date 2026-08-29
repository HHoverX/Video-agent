package com.videoagent.rag.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.videoagent.telemetry.QaTelemetryContext;
import com.videoagent.telemetry.QaTelemetryRoute;

import org.junit.jupiter.api.Test;

import java.util.List;

class MockEmbeddingProviderTest {

    private final MockEmbeddingProvider provider = new MockEmbeddingProvider();

    @Test
    void shouldBeDeterministicForSameText() {
        float[] a = provider.embedQuery("Redis 用于缓存进度");
        float[] b = provider.embedQuery("Redis 用于缓存进度");
        assertThat(a).containsExactly(b);
    }

    @Test
    void shouldKeepQueryBehaviorThroughTelemetryAwareDefaultOverload() {
        float[] original = provider.embedQuery("Redis 用于缓存进度");
        float[] withContext = provider.embedQuery(
            "Redis 用于缓存进度",
            new QaTelemetryContext("request-1", 7L, 3L),
            QaTelemetryRoute.BASIC_RAG
        );

        assertThat(withContext).containsExactly(original);
    }

    @Test
    void shouldProduceFixedDimension() {
        assertThat(provider.embedQuery("anything").length).isEqualTo(provider.dimension());
        assertThat(provider.embedDocuments(List.of("a", "b"))).allSatisfy(v ->
            assertThat(v.length).isEqualTo(provider.dimension()));
    }

    @Test
    void shouldPutVocabularyTermsAtDistinctPositions() {
        float[] redis = provider.embedQuery("Redis 缓存进度");
        float[] rocket = provider.embedQuery("RocketMQ 异步消息");
        // redis sits in vocabulary positions 0-2, rocketmq in 3-5.
        assertThat(redis[0]).isEqualTo(1.0f);
        assertThat(rocket[3]).isEqualTo(1.0f);
        assertThat(redis).isNotEqualTo(rocket);
    }

    @Test
    void shouldRankRocketmqChunkAboveRedisForAsyncQuestion() {
        // Deterministic retrieval order: the RocketMQ chunk (positions 3-5)
        // must score higher than the Redis chunk for an "async message" query.
        float[] query = provider.embedQuery("哪个组件负责异步消息？");
        float[] redisChunk = provider.embedQuery("Redis 用于缓存进度");
        float[] rocketChunk = provider.embedQuery("RocketMQ 用于异步任务消息");

        double redisScore = cosine(query, redisChunk);
        double rocketScore = cosine(query, rocketChunk);
        assertThat(rocketScore).isGreaterThan(redisScore);
    }

    @Test
    void shouldProduceStableVectorsForInfraTexts() {
        // The exact texts the infra tests rely on must be stable and distinct.
        float[] redis = provider.embedQuery("Redis 用于缓存进度");
        float[] mysql = provider.embedQuery("MySQL 保存业务状态");
        float[] security = provider.embedQuery("Spring Security 用于 JWT");
        assertThat(redis).isNotEqualTo(mysql);
        assertThat(mysql).isNotEqualTo(security);
        assertThat(redis).isNotEqualTo(security);
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
