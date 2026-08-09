package com.videoagent.summary.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.videoagent.asr.TranscriptSegment;
import com.videoagent.summary.service.SummaryResultValidator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

class DeepSeekOpenAiCompatibleProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldUseGenericOpenAiJsonObjectModeForDeepSeekCompatibleApi() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        String structuredResult = """
            {"overview":"真实字幕摘要","chapters":[{"title":"开场","summary":"内容","startMs":0,"endMs":2000}],"keyPoints":[{"content":"重点","startMs":0,"endMs":2000}]}
            """.strip();
        String encodedStructuredResult = objectMapper.writeValueAsString(structuredResult);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, """
                {
                  "id":"local-response",
                  "object":"chat.completion",
                  "created":1,
                  "model":"deepseek-v4-flash",
                  "choices":[{
                    "index":0,
                    "message":{
                      "role":"assistant",
                      "content":%s
                    },
                    "finish_reason":"stop"
                  }],
                  "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}
                }
                """.formatted(encodedStructuredResult));
        });
        server.start();

        VideoSummaryProvider provider = new SummaryProviderConfiguration().videoSummaryProvider(
            new SummaryProviderProperties(
                "openai",
                "unit-test-placeholder",
                "deepseek-v4-flash",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofSeconds(3),
                0,
                "json_object"
            ),
            new SummaryResultValidator()
        );

        VideoSummaryResult result = provider.summarize(new VideoSummaryRequest(
            7L,
            11L,
            List.of(new TranscriptSegment(0, 2_000, "真实语音字幕"))
        ));

        assertThat(result.overview()).isEqualTo("真实字幕摘要");
        JsonNode request = objectMapper.readTree(requestBody.get());
        assertThat(request.path("model").asText()).isEqualTo("deepseek-v4-flash");
        assertThat(request.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(request.path("messages").toString())
            .contains(
                "Simplified Chinese",
                "overview",
                "chapter title",
                "chapter summary",
                "key point content",
                "Keep the JSON field names unchanged"
            );
        assertThat(request.path("messages").toString()).contains("JSON", "真实语音字幕");
        assertThat(authorization.get()).isEqualTo("Bearer unit-test-placeholder");
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
