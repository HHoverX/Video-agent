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
import java.util.List;
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
                data:{"output":{"sentence":{"sentence_id":1,"sentence_end":true,"begin_time":120,"end_time":1250,"text":"第一句","words":[{"text":"第","begin_time":120,"end_time":600,"punctuation":"","fixed":true},{"text":"一句","begin_time":600,"end_time":1250,"punctuation":"","fixed":true}]}},"request_id":"local-1"}

                id:3
                event:result
                :HTTP_STATUS/200
                data:{"output":{"sentence":{"sentence_id":2,"sentence_end":true,"begin_time":1250,"end_time":2500,"text":"第二句","words":[{"text":"第","begin_time":1250,"end_time":1800,"punctuation":"","fixed":true},{"text":"二句","begin_time":1800,"end_time":2500,"punctuation":"","fixed":true}]}},"request_id":"local-1"}

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
        JsonNode finalSentence = objectMapper.readTree("""
            {"words":[{"text":"第","begin_time":120,"end_time":600,"punctuation":"","fixed":true},{"text":"一句","begin_time":600,"end_time":1250,"punctuation":"","fixed":true}]}
            """);
        assertThat(DashScopeAsrProvider.wordCount(finalSentence)).isEqualTo(2);
        assertThat(DashScopeAsrProvider.wordCount(objectMapper.readTree("{}"))).isZero();
        assertThat(DashScopeAsrProvider.wordCount(objectMapper.readTree("{\"words\":{}}"))).isZero();
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
    void shouldRefineCoarseFinalSentenceWhenTimedWordsAreCompleteAndFixedIsMissing() throws Exception {
        startServer(exchange -> respond(exchange, 200, "text/event-stream", """
            event:result
            data:{"output":{"sentence":{"sentence_end":true,"begin_time":0,"end_time":16000,"text":"ab","words":[{"text":"a","begin_time":0,"end_time":8000,"punctuation":""},{"text":"b","begin_time":8000,"end_time":16000,"punctuation":""}]}}}

            """));
        Path audio = createWav("coarse.wav", 20);

        TranscriptionResult result = provider(Duration.ofSeconds(3))
            .transcribe(new AudioSource(audio, 49));

        assertThat(result.segments()).extracting(TranscriptSegment::startMs).containsExactly(0L, 8_000L);
        assertThat(result.segments()).extracting(TranscriptSegment::endMs).containsExactly(8_000L, 16_000L);
    }

    @Test
    void shouldPreserveCoarseFinalSentenceWhenAWordIsExplicitlyNotFixed() throws Exception {
        startServer(exchange -> respond(exchange, 200, "text/event-stream", """
            event:result
            data:{"output":{"sentence":{"sentence_end":true,"begin_time":0,"end_time":16000,"text":"ab","words":[{"text":"a","begin_time":0,"end_time":8000,"punctuation":"","fixed":false},{"text":"b","begin_time":8000,"end_time":16000,"punctuation":"","fixed":true}]}}}

            """));
        Path audio = createWav("unfixed.wav", 20);

        TranscriptionResult result = provider(Duration.ofSeconds(3))
            .transcribe(new AudioSource(audio, 49));

        assertThat(result.segments()).containsExactly(new TranscriptSegment(0, 16_000, "ab"));
    }

    @Test
    void shouldUsePunctuationOnlyTimedWordsForCoarseSentenceRefinement() throws Exception {
        startServer(exchange -> respond(exchange, 200, "text/event-stream", """
            event:result
            data:{"output":{"sentence":{"sentence_end":true,"begin_time":0,"end_time":18000,"text":"甲。乙。丙。","words":[{"text":"甲","begin_time":0,"end_time":5000,"punctuation":"","fixed":true},{"text":"","begin_time":5000,"end_time":6000,"punctuation":"。","fixed":true},{"text":"乙","begin_time":6000,"end_time":11000,"punctuation":"","fixed":true},{"text":"","begin_time":11000,"end_time":12000,"punctuation":"。","fixed":true},{"text":"丙","begin_time":12000,"end_time":18000,"punctuation":"。","fixed":true}]}}}

            """));
        Path audio = createWav("punctuation-only.wav", 20);

        TranscriptionResult result = provider(Duration.ofSeconds(3))
            .transcribe(new AudioSource(audio, 49));

        assertThat(result.segments()).extracting(TranscriptSegment::text)
            .containsExactly("甲。乙。", "丙。");
    }

    @Test
    void shouldCollapseCumulativeSnapshotsBeforeRefiningTheCanonicalCandidate() throws Exception {
        startServer(exchange -> respond(exchange, 200, "text/event-stream",
            finalEvent(1, 0, 16_000, "ab", words("a", 0, 8_000, "b", 8_000, 16_000))
                + finalEvent(2, 0, 24_000, "abc", words(
                    "a", 0, 8_000, "b", 8_000, 16_000, "c", 16_000, 24_000
                ))
        ));

        TranscriptionResult result = provider(Duration.ofSeconds(3))
            .transcribe(new AudioSource(createWav("cumulative-two.wav", 30), 49));

        assertThat(result.segments()).extracting(TranscriptSegment::startMs)
            .containsExactly(0L, 8_000L, 16_000L);
        assertThat(result.segments()).extracting(TranscriptSegment::endMs)
            .containsExactly(8_000L, 16_000L, 24_000L);
    }

    @Test
    void shouldCollapseTheRealSevenSnapshotCumulativeShapeAndRefineOnlyTheLastSnapshot() throws Exception {
        int[] counts = {69, 107, 168, 222, 284, 313, 339};
        long[] ends = {20_820, 32_630, 55_030, 74_680, 95_870, 105_210, 115_520};
        StringBuilder body = new StringBuilder();
        for (int index = 0; index < counts.length; index++) {
            body.append(finalEvent(
                index + 1,
                1_280,
                ends[index],
                "w".repeat(counts[index]),
                cumulativeWords(counts[index], counts, ends)
            ));
        }
        startServer(exchange -> respond(exchange, 200, "text/event-stream", body.toString()));

        TranscriptionResult result = provider(Duration.ofSeconds(3))
            .transcribe(new AudioSource(createWav("cumulative-seven.wav", 116), 116));

        assertThat(result.segments()).hasSizeLessThan(20);
        assertThat(result.segments().size()).isNotEqualTo(47);
        assertThat(result.segments().getFirst().startMs()).isEqualTo(1_280L);
        assertThat(result.segments().getLast().endMs()).isEqualTo(115_520L);
        assertThat(result.segments()).allSatisfy(segment -> assertThat(segment.text()).isEqualTo("w".repeat(segment.text().length())));
        assertMonotonic(result.segments());
    }

    @Test
    void shouldIgnoreExactDuplicateAndPreserveCumulativeChainFollowedByDistinctSentence() throws Exception {
        String first = finalEvent(1, 0, 8_000, "a", words("a", 0, 8_000));
        String cumulative = finalEvent(2, 0, 16_000, "ab", words("a", 0, 8_000, "b", 8_000, 16_000));
        String distinct = finalEvent(3, 16_500, 24_500, "c", words("c", 16_500, 24_500));
        startServer(exchange -> respond(exchange, 200, "text/event-stream", first + first + cumulative + distinct));

        TranscriptionResult result = provider(Duration.ofSeconds(3))
            .transcribe(new AudioSource(createWav("duplicate-and-distinct.wav", 30), 49));

        assertThat(result.segments()).containsExactly(
            new TranscriptSegment(0, 8_000, "a"),
            new TranscriptSegment(8_000, 16_000, "b"),
            new TranscriptSegment(16_500, 24_500, "c")
        );
        assertMonotonic(result.segments());
    }

    @Test
    void shouldPreserveNonOverlappingFallbackSentencesAndRejectAmbiguousOverlaps() throws Exception {
        startServer(exchange -> respond(exchange, 200, "text/event-stream",
            finalEventWithoutWords(1, 0, 4_000, "fallback one")
                + finalEventWithoutWords(2, 4_500, 8_000, "fallback two")
        ));
        TranscriptionResult fallbackResult = provider(Duration.ofSeconds(3))
            .transcribe(new AudioSource(createWav("fallback-distinct.wav", 10), 49));
        assertThat(fallbackResult.segments()).hasSize(2);

        stopServer();
        startServer(exchange -> respond(exchange, 200, "text/event-stream",
            finalEvent(1, 0, 8_000, "a", words("a", 0, 8_000))
                + finalEvent(2, 4_000, 12_000, "b", words("b", 4_000, 12_000))
        ));
        assertThatThrownBy(() -> provider(Duration.ofSeconds(3))
            .transcribe(new AudioSource(createWav("partial-overlap.wav", 15), 49)))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.ASR_RESPONSE_INVALID)
            );

        stopServer();
        startServer(exchange -> respond(exchange, 200, "text/event-stream",
            finalEventWithoutWords(1, 0, 8_000, "fallback one")
                + finalEventWithoutWords(2, 0, 12_000, "fallback one extended")
        ));
        assertThatThrownBy(() -> provider(Duration.ofSeconds(3))
            .transcribe(new AudioSource(createWav("fallback-overlap.wav", 15), 49)))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.ASR_RESPONSE_INVALID)
            );

        stopServer();
        startServer(exchange -> respond(exchange, 200, "text/event-stream",
            finalEvent(1, 0, 8_000, "a", words("a", 0, 8_000))
                + finalEvent(2, 0, 16_000, "xb", words(
                    "x", 0, 8_000, "b", 8_000, 16_000
                ))
        ));
        assertThatThrownBy(() -> provider(Duration.ofSeconds(3))
            .transcribe(new AudioSource(createWav("incompatible-prefix.wav", 20), 49)))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.ASR_RESPONSE_INVALID)
            );
    }

    @Test
    void shouldUseTimedWordPrefixDespiteSentenceSpacingOrPunctuationFormattingDifferences() throws Exception {
        startServer(exchange -> respond(exchange, 200, "text/event-stream",
            finalEvent(1, 0, 8_000, "Hello,world", words("Hello", 0, 4_000, " world", 4_000, 8_000))
                + finalEvent(2, 0, 16_000, "Hello, world!again", words(
                    "Hello", 0, 4_000, " world", 4_000, 8_000, "again", 8_000, 16_000
                ))
        ));

        TranscriptionResult result = provider(Duration.ofSeconds(3))
            .transcribe(new AudioSource(createWav("formatting-variation.wav", 20), 49));

        assertThat(result.segments()).containsExactly(new TranscriptSegment(0, 16_000, "Hello, world!again"));
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
                // 403 is a deterministic provider rejection, not retryable.
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.ASR_PROVIDER_REJECTED);
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

    private String finalEvent(long sentenceId, long beginMs, long endMs, String text, String words) {
        return """
            event:result
            data:{"output":{"sentence":{"sentence_id":%d,"sentence_end":true,"begin_time":%d,"end_time":%d,"text":"%s","words":[%s]}}}

            """.formatted(sentenceId, beginMs, endMs, text, words);
    }

    private String finalEventWithoutWords(long sentenceId, long beginMs, long endMs, String text) {
        return """
            event:result
            data:{"output":{"sentence":{"sentence_id":%d,"sentence_end":true,"begin_time":%d,"end_time":%d,"text":"%s"}}}

            """.formatted(sentenceId, beginMs, endMs, text);
    }

    private String words(Object... values) {
        if (values.length % 3 != 0) {
            throw new IllegalArgumentException("word values must be text, beginMs, endMs triples");
        }
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < values.length; index += 3) {
            if (!result.isEmpty()) {
                result.append(',');
            }
            result.append("{\"text\":\"")
                .append(values[index])
                .append("\",\"begin_time\":")
                .append(values[index + 1])
                .append(",\"end_time\":")
                .append(values[index + 2])
                .append(",\"punctuation\":\"\",\"fixed\":true}");
        }
        return result.toString();
    }

    private String cumulativeWords(int count, int[] cumulativeCounts, long[] cumulativeEnds) {
        StringBuilder result = new StringBuilder();
        int previousCount = 0;
        long previousEnd = 1_280L;
        int written = 0;
        for (int group = 0; group < cumulativeCounts.length && written < count; group++) {
            int groupCount = cumulativeCounts[group] - previousCount;
            long groupEnd = cumulativeEnds[group];
            for (int offset = 0; offset < groupCount && written < count; offset++) {
                long beginMs = previousEnd + (groupEnd - previousEnd) * offset / groupCount;
                long endMs = previousEnd + (groupEnd - previousEnd) * (offset + 1) / groupCount;
                if (!result.isEmpty()) {
                    result.append(',');
                }
                result.append("{\"text\":\"w\",\"begin_time\":")
                    .append(beginMs)
                    .append(",\"end_time\":")
                    .append(endMs)
                    .append(",\"punctuation\":\"\",\"fixed\":true}");
                written++;
            }
            previousCount = cumulativeCounts[group];
            previousEnd = groupEnd;
        }
        return result.toString();
    }

    private void assertMonotonic(List<TranscriptSegment> segments) {
        for (int index = 1; index < segments.size(); index++) {
            TranscriptSegment previous = segments.get(index - 1);
            TranscriptSegment current = segments.get(index);
            assertThat(current.startMs()).isGreaterThanOrEqualTo(previous.startMs());
            assertThat(current.endMs()).isGreaterThanOrEqualTo(previous.endMs());
        }
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
