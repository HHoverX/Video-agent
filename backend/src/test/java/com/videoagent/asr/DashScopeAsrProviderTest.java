package com.videoagent.asr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

class DashScopeAsrProviderTest {

    private static final String GENERATION_PATH =
        "/api/v1/services/aigc/multimodal-generation/generation";
    private static final String TEST_CREDENTIAL = "unit-test-placeholder";

    @TempDir
    private Path tempDirectory;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldSendOfficialJsonContractAndMapOnlyFinalSentenceMilliseconds() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> sseHeader = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            sseHeader.set(exchange.getRequestHeaders().getFirst("X-DashScope-SSE"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "text/event-stream", """
                id:1
                event:result
                :HTTP_STATUS/200
                data:{"output":{"sentence":{"sentence_id":1,"sentence_end":false,"begin_time":0,"text":"临时结果"}},"request_id":"local-1"}

                id:2
                event:result
                :HTTP_STATUS/200
                data:{"output":{"sentence":{"sentence_id":1,"sentence_end":true,"begin_time":120,"end_time":1250,"text":"第一句"}},"request_id":"local-1"}

                id:3
                event:result
                :HTTP_STATUS/200
                data:{"output":{"sentence":{"sentence_id":2,"sentence_end":true,"begin_time":1250,"end_time":2500,"text":"第二句"}},"request_id":"local-1"}

                """);
        });
        Path audio = createWav("dashscope.wav", 3);

        TranscriptionResult result = provider(Duration.ofSeconds(3)).transcribe(new AudioSource(audio));

        assertThat(result.segments()).hasSize(2);
        assertThat(result.segments()).extracting(TranscriptSegment::startMs)
            .containsExactly(120L, 1_250L);
        assertThat(result.segments()).extracting(TranscriptSegment::endMs)
            .containsExactly(1_250L, 2_500L);
        assertThat(result.segments()).extracting(TranscriptSegment::text)
            .containsExactly("第一句", "第二句");
        assertThat(authorization.get()).isEqualTo("Bearer " + TEST_CREDENTIAL);
        assertThat(sseHeader.get()).isEqualTo("enable");
        assertThat(contentType.get()).startsWith("application/json");

        JsonNode request = objectMapper.readTree(requestBody.get());
        assertThat(request.path("model").asText()).isEqualTo("fun-asr-flash-2026-06-15");
        assertThat(request.path("parameters").path("format").asText()).isEqualTo("wav");
        assertThat(request.path("parameters").path("sample_rate").asText()).isEqualTo("16000");
        assertThat(request.path("parameters").path("language_hints").get(0).asText())
            .isEqualTo("zh");
        String dataUri = request.path("input").path("messages").get(0)
            .path("content").get(0).path("input_audio").path("data").asText();
        assertThat(dataUri).startsWith("data:audio/wav;base64,");
        byte[] decoded = Base64.getDecoder().decode(dataUri.substring(dataUri.indexOf(',') + 1));
        assertThat(decoded).isEqualTo(Files.readAllBytes(audio));
    }

    @Test
    void shouldRejectSseWithoutAnyFinalSentence() throws Exception {
        startServer(exchange -> respond(exchange, 200, "text/event-stream", """
            event:result
            data:{"output":{"sentence":{"sentence_end":false,"begin_time":0,"text":"未完成"}}}

            """));
        Path audio = createWav("no-final.wav", 1);

        assertThatThrownBy(() -> provider(Duration.ofSeconds(3)).transcribe(new AudioSource(audio)))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.ASR_RESPONSE_INVALID)
            );
    }

    @Test
    void shouldRejectMalformedFinalSentenceEvent() throws Exception {
        startServer(exchange -> respond(exchange, 200, "text/event-stream", """
            event:result
            data:{not-json

            """));
        Path audio = createWav("malformed.wav", 1);

        assertThatThrownBy(() -> provider(Duration.ofSeconds(3)).transcribe(new AudioSource(audio)))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.ASR_RESPONSE_INVALID)
            );
    }

    @Test
    void shouldMapHttpFailureWithoutLeakingCredentialAudioOrResponseBody() throws Exception {
        startServer(exchange -> respond(
            exchange,
            403,
            "application/json",
            "{\"message\":\"provider-confidential-diagnostic\"}"
        ));
        Path audio = createWav("http-failure.wav", 1);

        assertThatThrownBy(() -> provider(Duration.ofSeconds(3)).transcribe(new AudioSource(audio)))
            .isInstanceOfSatisfying(VideoAgentException.class, exception -> {
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.ASR_REQUEST_FAILED);
                assertThat(exception.getMessage())
                    .contains("HTTP 403")
                    .doesNotContain(
                        TEST_CREDENTIAL,
                        "data:audio/wav;base64",
                        "provider-confidential-diagnostic"
                    );
            });
    }

    @Test
    void shouldMapTimeoutWithoutLeakingCredentialOrAudio() throws Exception {
        Path audio = createWav("timeout.wav", 1);
        AsrProviderProperties properties = properties(Duration.ofMillis(50), "http://127.0.0.1");
        RestClient timeoutClient = RestClient.builder()
            .requestFactory((uri, method) -> {
                throw new SocketTimeoutException("timed out");
            })
            .build();
        DashScopeAsrProvider provider = new DashScopeAsrProvider(
            properties,
            new AsrResultValidator(),
            timeoutClient,
            objectMapper
        );

        assertThatThrownBy(() -> provider.transcribe(new AudioSource(audio)))
            .isInstanceOfSatisfying(VideoAgentException.class, exception -> {
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.ASR_TIMEOUT);
                assertThat(exception.getMessage())
                    .doesNotContain(TEST_CREDENTIAL, "data:audio/wav;base64");
            });
    }

    private DashScopeAsrProvider provider(Duration timeout) {
        return new DashScopeAsrProvider(
            properties(timeout, "http://127.0.0.1:" + server.getAddress().getPort()),
            new AsrResultValidator()
        );
    }

    private AsrProviderProperties properties(Duration timeout, String baseUrl) {
        return new AsrProviderProperties(
            "dashscope",
            TEST_CREDENTIAL,
            "fun-asr-flash-2026-06-15",
            baseUrl,
            timeout
        );
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(GENERATION_PATH, exchange -> handler.handle(exchange));
        server.start();
    }

    private void respond(
        HttpExchange exchange,
        int status,
        String responseContentType,
        String body
    ) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", responseContentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private Path createWav(String filename, int seconds) throws Exception {
        int sampleRate = 16_000;
        byte[] pcm = new byte[sampleRate * seconds * 2];
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        Path output = tempDirectory.resolve(filename);
        try (AudioInputStream stream = new AudioInputStream(
            new ByteArrayInputStream(pcm),
            format,
            sampleRate * seconds
        )) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, output.toFile());
        }
        return output;
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
