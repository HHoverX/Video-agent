package com.videoagent.rag.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.rag.config.EmbeddingProperties;
import com.videoagent.telemetry.AiUsageMetrics;
import com.videoagent.telemetry.AnalysisTelemetryContext;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

class RealEmbeddingProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldRecordOneExternalProviderRequestPerDocumentBatch() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        startServer(exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody().readAllBytes());
            int documents = request.path("input").size();
            requestCount.incrementAndGet();
            respond(exchange, 200, responseFor(documents));
        });
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RealEmbeddingProvider provider = provider(new AiUsageMetrics(meterRegistry));
        List<String> documents = java.util.stream.IntStream.range(0, 11)
            .mapToObj(index -> "document-" + index)
            .toList();

        List<float[]> vectors = provider.embedDocuments(
            documents,
            new AnalysisTelemetryContext(101L, 7L, 2, 1)
        );

        assertThat(vectors).hasSize(11);
        assertThat(requestCount.get()).isEqualTo(2);
        assertThat(meterRegistry.get("videoagent.ai.provider.requests")
            .tag("stage", "embedding_document").tag("outcome", "success").counter().count()).isEqualTo(2.0d);
        assertThat(meterRegistry.get("videoagent.ai.input.scale")
            .tag("stage", "embedding_document").tag("input_type", "document_count").summary().totalAmount())
            .isEqualTo(11.0d);
        assertThat(meterRegistry.get("videoagent.ai.input.scale")
            .tag("stage", "embedding_document").tag("input_type", "input_chars").summary().totalAmount())
            .isEqualTo(documents.stream().mapToInt(String::length).sum());
    }

    @Test
    void shouldRecordFailureWithoutChangingMappedProviderError() throws Exception {
        startServer(exchange -> respond(exchange, 500, "{}"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        assertThatThrownBy(() -> provider(new AiUsageMetrics(meterRegistry)).embedDocuments(
            List.of("document"),
            new AnalysisTelemetryContext(101L, 7L, 2, 1)
        )).isInstanceOfSatisfying(VideoAgentException.class, exception ->
            assertThat(exception.errorCode()).isEqualTo(ErrorCode.EMBEDDING_REQUEST_FAILED));

        assertThat(meterRegistry.get("videoagent.ai.provider.requests")
            .tag("stage", "embedding_document").tag("outcome", "failure")
            .tag("error_category", "HTTP_5XX").counter().count()).isEqualTo(1.0d);
    }

    private RealEmbeddingProvider provider(AiUsageMetrics usageMetrics) {
        return new RealEmbeddingProvider(new EmbeddingProperties(
            "openai",
            "unit-test-placeholder",
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "text-embedding-test",
            2,
            Duration.ofSeconds(3)
        ), usageMetrics);
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> handler.handle(exchange));
        server.start();
    }

    private String responseFor(int vectorCount) {
        String data = java.util.stream.IntStream.range(0, vectorCount)
            .mapToObj(index -> "{\"embedding\":[1.0,2.0]}")
            .collect(java.util.stream.Collectors.joining(","));
        return "{\"data\":[" + data + "]}";
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
