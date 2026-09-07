package com.videoagent.rag.rerank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.videoagent.rag.config.RagProperties;
import com.videoagent.rag.retrieval.HybridCandidate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

class HttpTranscriptRerankerTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void shouldCallCompatibleRerankEndpointAndSortByReturnedScores() throws Exception {
        start(200, """
            {"results":[
              {"index":2,"relevance_score":0.95},
              {"index":0,"relevance_score":0.70},
              {"index":3,"relevance_score":0.40},
              {"index":1,"relevance_score":0.10}
            ]}
            """);
        HttpTranscriptReranker reranker = new HttpTranscriptReranker(properties());

        List<HybridCandidate> result = reranker.rerank("query", List.of(
            candidate("A"), candidate("B"), candidate("C"), candidate("D")
        ));

        assertThat(result).extracting(HybridCandidate::text).containsExactly("C", "A", "D", "B");
        assertThat(result).extracting(HybridCandidate::rerankScore)
            .containsExactly(0.95, 0.70, 0.40, 0.10);
    }

    @Test
    void shouldRejectHttpFailureAndMalformedResponseForCallerFallback() throws Exception {
        start(500, "failure");
        assertThatThrownBy(() -> new HttpTranscriptReranker(properties()).rerank("q", List.of(candidate("A"))))
            .isInstanceOf(RuntimeException.class);
        server.stop(0);
        start(200, "{\"unexpected\":true}");
        assertThatThrownBy(() -> new HttpTranscriptReranker(properties()).rerank("q", List.of(candidate("A"))))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldTimeoutForCallerFallback() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rerank", exchange -> {
            try {
                Thread.sleep(300);
                exchange.sendResponseHeaders(200, 0);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        RagProperties properties = new RagProperties(8000, 2000, 1, 15, 0.0f, 15, 60, 15, 5,
            new RagProperties.Reranker(true, "http://127.0.0.1:" + server.getAddress().getPort(),
                "secret", "rerank-test", Duration.ofMillis(100)));

        assertThatThrownBy(() -> new HttpTranscriptReranker(properties).rerank("q", List.of(candidate("A"))))
            .isInstanceOf(RuntimeException.class);
    }

    private void start(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rerank", exchange -> {
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    private RagProperties properties() {
        return new RagProperties(8000, 2000, 1, 15, 0.0f, 15, 60, 15, 5,
            new RagProperties.Reranker(true, "http://127.0.0.1:" + server.getAddress().getPort(),
                "secret", "rerank-test", Duration.ofSeconds(2)));
    }

    private HybridCandidate candidate(String text) {
        return new HybridCandidate(text, 0, text, 0, 1000, List.of(), 0.5f, null, 0.01, null);
    }
}
