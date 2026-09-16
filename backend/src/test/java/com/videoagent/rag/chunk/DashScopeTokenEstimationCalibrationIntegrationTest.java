package com.videoagent.rag.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Development-only comparison against DashScope's model-reported usage. This
 * test observes approximation drift; production chunking never calls it.
 */
@EnabledIfEnvironmentVariable(named = "VIDEOAGENT_TOKEN_ESTIMATION_CALIBRATION_TEST", matches = "true")
class DashScopeTokenEstimationCalibrationIntegrationTest {

    private final HeuristicTokenEstimator estimator = new HeuristicTokenEstimator();

    @Test
    void shouldCompareLocalEstimatesWithTextEmbeddingV4Usage() {
        String apiKey = System.getenv("EMBEDDING_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
            "EMBEDDING_API_KEY is required for token-estimation calibration");

        String baseUrl = System.getenv().getOrDefault(
            "EMBEDDING_BASE_URL",
            "https://dashscope.aliyuncs.com/compatible-mode/v1"
        );
        String endpoint = (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
            + "/embeddings";
        RestClient client = RestClient.create();

        List<String> samples = List.of(
            "这是一个用于验证字幕分块估算的纯中文句子。",
            "VideoAgent uses Redis for progress cache and RocketMQ for asynchronous analysis.",
            "使用 Redis 实现分布式锁，并通过 uploadId 恢复分片上传。",
            "SHA-256, NullPointerException, CamelCaseIdentifier, version-2026.09.",
            "第一段字幕介绍系统架构。\n第二段字幕讨论 Milvus Dense 与 BM25 混合检索。"
        );

        for (String sample : samples) {
            CalibrationResponse response = client.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(apiKey))
                .body(new CalibrationRequest("text-embedding-v4", sample))
                .retrieve()
                .body(CalibrationResponse.class);

            assertThat(response).isNotNull();
            assertThat(response.usage()).isNotNull();
            int actual = response.usage().totalTokens();
            int estimated = estimator.estimateTokens(sample);
            double ratio = (double) estimated / actual;
            System.out.printf("TOKEN_CALIBRATION estimated=%d actual=%d ratio=%.3f text=%s%n",
                estimated, actual, ratio, sample.replace('\n', ' '));

            assertThat(actual).isPositive();
            assertThat(estimated).isPositive();
            assertThat(ratio).isBetween(0.2, 5.0);
        }
    }

    private record CalibrationRequest(String model, String input) {
    }

    private record CalibrationResponse(Usage usage) {
    }

    private record Usage(@JsonProperty("total_tokens") int totalTokens) {
    }
}
