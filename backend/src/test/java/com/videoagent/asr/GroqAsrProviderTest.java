package com.videoagent.asr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

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
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

class GroqAsrProviderTest {

    @TempDir
    private Path tempDirectory;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldSendGroqMultipartRequestAndMapSegmentTimestamps() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                {"segments":[
                  {"start":0.0,"end":1.2346,"text":" 你好 "},
                  {"start":1.2346,"end":2.5,"text":"世界"}
                ]}
                """);
        });
        Path audio = createWav("speech.wav", 3);

        TranscriptionResult result = provider(Duration.ofSeconds(3)).transcribe(new AudioSource(audio));

        assertThat(result.segments()).extracting(TranscriptSegment::startMs)
            .containsExactly(0L, 1_235L);
        assertThat(result.segments()).extracting(TranscriptSegment::endMs)
            .containsExactly(1_235L, 2_500L);
        assertThat(result.segments()).extracting(TranscriptSegment::text)
            .containsExactly("你好", "世界");
        assertThat(authorization.get()).isEqualTo("Bearer unit-test-placeholder");
        assertThat(requestBody.get())
            .contains("name=\"file\"", "filename=\"speech.wav\"")
            .contains("name=\"model\"", "whisper-large-v3-turbo")
            .contains("name=\"response_format\"", "verbose_json")
            .contains("name=\"timestamp_granularities[]\"", "segment")
            .contains("name=\"language\"", "zh");
    }

    @Test
    void shouldRejectSegmentsThatClearlyExceedAudioDuration() throws Exception {
        startServer(exchange -> respond(exchange, 200, """
            {"segments":[{"start":0.0,"end":8.0,"text":"越界片段"}]}
            """));
        Path audio = createWav("short.wav", 2);

        assertThatThrownBy(() -> provider(Duration.ofSeconds(3)).transcribe(new AudioSource(audio)))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode())
                    .as("provider message: %s", exception.getMessage())
                    .isEqualTo(ErrorCode.ASR_RESPONSE_INVALID)
            );
    }

    @Test
    void shouldRejectNonMonotonicSegments() throws Exception {
        startServer(exchange -> respond(exchange, 200, """
            {"segments":[
              {"start":1.0,"end":2.0,"text":"第一段"},
              {"start":0.5,"end":2.5,"text":"乱序片段"}
            ]}
            """));
        Path audio = createWav("unordered.wav", 3);

        assertThatThrownBy(() -> provider(Duration.ofSeconds(3)).transcribe(new AudioSource(audio)))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode())
                    .as("provider message: %s", exception.getMessage())
                    .isEqualTo(ErrorCode.ASR_RESPONSE_INVALID)
            );
    }

    @Test
    void shouldClassifyMalformedGroqJsonAsInvalidResponse() throws Exception {
        startServer(exchange -> respond(exchange, 200, "{not-json"));
        Path audio = createWav("malformed-response.wav", 1);

        assertThatThrownBy(() -> provider(Duration.ofSeconds(3)).transcribe(new AudioSource(audio)))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.ASR_RESPONSE_INVALID)
            );
    }

    @Test
    void shouldMapGroqTimeoutWithoutLeakingRequestConfiguration() throws Exception {
        Path audio = createWav("timeout.wav", 1);
        AsrProviderProperties properties = properties(Duration.ofMillis(50), "http://127.0.0.1");
        RestClient timeoutClient = RestClient.builder()
            .requestFactory((uri, method) -> {
                throw new SocketTimeoutException("timed out");
            })
            .build();
        GroqAsrProvider provider = new GroqAsrProvider(
            properties,
            new AsrResultValidator(),
            timeoutClient
        );

        assertThatThrownBy(() -> provider.transcribe(new AudioSource(audio)))
            .isInstanceOfSatisfying(VideoAgentException.class, exception -> {
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.ASR_TIMEOUT);
                assertThat(exception.getMessage()).doesNotContain("unit-test-placeholder");
            });
    }

    @Test
    void shouldMapGroqHttpFailureWithoutPersistingResponseBody() throws Exception {
        startServer(exchange -> respond(
            exchange,
            401,
            "{\"error\":\"provider-confidential-diagnostic\"}"
        ));
        Path audio = createWav("request-failure.wav", 1);

        assertThatThrownBy(() -> provider(Duration.ofSeconds(3)).transcribe(new AudioSource(audio)))
            .isInstanceOfSatisfying(VideoAgentException.class, exception -> {
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.ASR_REQUEST_FAILED);
                assertThat(exception.getMessage()).doesNotContain("provider-confidential-diagnostic");
            });
    }

    private GroqAsrProvider provider(Duration timeout) {
        return new GroqAsrProvider(
            properties(timeout, "http://127.0.0.1:" + server.getAddress().getPort()),
            new AsrResultValidator()
        );
    }

    private AsrProviderProperties properties(Duration timeout, String baseUrl) {
        return new AsrProviderProperties(
            "groq",
            "unit-test-placeholder",
            "whisper-large-v3-turbo",
            baseUrl,
            timeout
        );
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/audio/transcriptions", exchange -> handler.handle(exchange));
        server.start();
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
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
