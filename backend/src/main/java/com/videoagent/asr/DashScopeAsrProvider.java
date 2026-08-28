package com.videoagent.asr;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.provider.ProviderHttpFailure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

public class DashScopeAsrProvider implements AsrProvider {

    private static final String WAV_DATA_URI_PREFIX = "data:audio/wav;base64,";
    private static final long MAX_DATA_URI_CHARS = 10L * 1024 * 1024;
    private static final int MAX_FINAL_SENTENCE_DEBUG_LOGS = 20;

    private static final Logger log = LoggerFactory.getLogger(DashScopeAsrProvider.class);

    private final AsrProviderProperties properties;
    private final AsrResultValidator validator;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public DashScopeAsrProvider(
        AsrProviderProperties properties,
        AsrResultValidator validator
    ) {
        this(properties, validator, restClient(properties), new ObjectMapper());
    }

    DashScopeAsrProvider(
        AsrProviderProperties properties,
        AsrResultValidator validator,
        RestClient restClient,
        ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.validator = validator;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public TranscriptionResult transcribe(AudioSource audioSource) {
        try {
            String audioDataUri = wavDataUri(audioSource);
            DashScopeRequest body = request(audioDataUri);
            List<TranscriptSegment> segments = restClient.post()
                .uri(properties.generationUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .headers(headers -> {
                    headers.setBearerAuth(properties.apiKey());
                    headers.set("X-DashScope-SSE", "enable");
                })
                .body(body)
                .exchange((request, response) -> {
                    String responseContentType = response.getHeaders().getFirst("Content-Type");
                    boolean responseIsSse = responseContentType != null
                        && responseContentType.toLowerCase(Locale.ROOT).startsWith("text/event-stream");
                    log.debug("DashScope ASR response status={} contentType={} responseIsSse={} parser=sse",
                        response.getStatusCode().value(), responseContentType, responseIsSse);
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw ProviderHttpFailure.forStatus(
                            response.getStatusCode().value(),
                            response.getHeaders().getFirst("Retry-After"),
                            "DashScope ASR",
                            "语音转写",
                            ErrorCode.ASR_REQUEST_FAILED,
                            ErrorCode.ASR_PROVIDER_REJECTED
                        );
                    }
                    return parseSse(response.getBody());
                });
            return validator.validate(audioSource, segments);
        } catch (VideoAgentException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            if (isTimeout(exception)) {
                throw new VideoAgentException(ErrorCode.ASR_TIMEOUT, "DashScope ASR 请求超时");
            }
            throw new VideoAgentException(ErrorCode.ASR_REQUEST_FAILED, "DashScope ASR 请求失败");
        } catch (RestClientResponseException exception) {
            throw ProviderHttpFailure.forStatus(
                exception.getStatusCode().value(),
                exception.getResponseHeaders() == null ? null : exception.getResponseHeaders().getFirst("Retry-After"),
                "DashScope ASR",
                "语音转写",
                ErrorCode.ASR_REQUEST_FAILED,
                ErrorCode.ASR_PROVIDER_REJECTED
            );
        } catch (RestClientException exception) {
            if (isTimeout(exception)) {
                throw new VideoAgentException(ErrorCode.ASR_TIMEOUT, "DashScope ASR 请求超时");
            }
            throw new VideoAgentException(
                ErrorCode.ASR_RESPONSE_INVALID,
                "DashScope ASR 返回无法解析的响应"
            );
        } catch (IllegalArgumentException exception) {
            throw new VideoAgentException(
                ErrorCode.ASR_RESPONSE_INVALID,
                "DashScope ASR 返回无效字幕片段"
            );
        }
    }

    private String wavDataUri(AudioSource audioSource) {
        try {
            if (!Files.isRegularFile(audioSource.file())
                || Files.isSymbolicLink(audioSource.file())) {
                throw new VideoAgentException(
                    ErrorCode.ASR_REQUEST_FAILED,
                    "DashScope ASR 输入音频无效"
                );
            }
            long size = Files.size(audioSource.file());
            long encodedChars = 4L * ((size + 2L) / 3L);
            if (size == 0 || encodedChars + WAV_DATA_URI_PREFIX.length() > MAX_DATA_URI_CHARS) {
                throw new VideoAgentException(
                    ErrorCode.ASR_INPUT_TOO_LARGE,
                    "DashScope ASR Base64 音频超过 10MB 输入限制"
                );
            }
            return WAV_DATA_URI_PREFIX + Base64.getEncoder().encodeToString(
                Files.readAllBytes(audioSource.file())
            );
        } catch (VideoAgentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new VideoAgentException(
                ErrorCode.ASR_REQUEST_FAILED,
                "DashScope ASR 无法读取输入音频"
            );
        }
    }

    private DashScopeRequest request(String audioDataUri) {
        return new DashScopeRequest(
            properties.model(),
            new DashScopeInput(List.of(new DashScopeMessage(
                "user",
                List.of(new DashScopeContent(
                    "input_audio",
                    new DashScopeAudio(audioDataUri)
                ))
            ))),
            new DashScopeParameters("wav", "16000", List.of("zh"))
        );
    }

    private List<TranscriptSegment> parseSse(InputStream inputStream) throws IOException {
        List<TranscriptSegment> segments = new ArrayList<>();
        SseDiagnostics diagnostics = new SseDiagnostics();
        try {
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8)
            )) {
                String eventName = null;
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        acceptEvent(eventName, data, segments, diagnostics);
                        eventName = null;
                        data.setLength(0);
                    } else if (line.startsWith("event:")) {
                        eventName = line.substring("event:".length()).strip();
                    } else if (line.startsWith("data:")) {
                        if (!data.isEmpty()) {
                            data.append('\n');
                        }
                        data.append(line.substring("data:".length()).stripLeading());
                    }
                }
                acceptEvent(eventName, data, segments, diagnostics);
            }
            if (segments.isEmpty()) {
                throw new VideoAgentException(
                    ErrorCode.ASR_RESPONSE_INVALID,
                    "DashScope ASR 未返回最终句子"
                );
            }
            return segments;
        } finally {
            diagnostics.logSummary(segments);
        }
    }

    private void acceptEvent(
        String eventName,
        StringBuilder data,
        List<TranscriptSegment> segments,
        SseDiagnostics diagnostics
    ) {
        if (eventName == null && data.isEmpty()) {
            return;
        }
        diagnostics.totalEventCount++;
        if (!"result".equals(eventName)) {
            return;
        }
        diagnostics.resultEventCount++;
        if (data.isEmpty()) {
            return;
        }
        try {
            JsonNode sentence = objectMapper.readTree(data.toString())
                .path("output")
                .path("sentence");
            if (!sentence.path("sentence_end").asBoolean(false)) {
                return;
            }
            diagnostics.finalSentenceCount++;
            JsonNode beginTime = sentence.path("begin_time");
            JsonNode endTime = sentence.path("end_time");
            JsonNode text = sentence.path("text");
            diagnostics.recordFinalSentence(
                sentence.path("sentence_id"), beginTime, endTime, wordCount(sentence)
            );
            if (!beginTime.isIntegralNumber()
                || !endTime.isIntegralNumber()
                || !text.isTextual()
                || text.asText().isBlank()) {
                throw invalidResponse("DashScope ASR 最终句子字段无效");
            }
            segments.add(new TranscriptSegment(
                beginTime.longValue(),
                endTime.longValue(),
                text.asText()
            ));
        } catch (JsonProcessingException exception) {
            throw invalidResponse("DashScope ASR SSE 数据无法解析");
        }
    }

    static int wordCount(JsonNode sentence) {
        JsonNode words = sentence.path("words");
        return words.isArray() ? words.size() : 0;
    }

    private VideoAgentException invalidResponse(String message) {
        return new VideoAgentException(ErrorCode.ASR_RESPONSE_INVALID, message);
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (current instanceof SocketTimeoutException
                || current.getClass().getSimpleName().contains("Timeout")
                || message != null && (
                    message.toLowerCase(java.util.Locale.ROOT).contains("timed out")
                        || message.toLowerCase(java.util.Locale.ROOT).contains("timeout")
                )) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static RestClient restClient(AsrProviderProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());
        return RestClient.builder()
            .requestFactory(requestFactory)
            .build();
    }

    private record DashScopeRequest(
        String model,
        DashScopeInput input,
        DashScopeParameters parameters
    ) {
    }

    private record DashScopeInput(List<DashScopeMessage> messages) {
    }

    private record DashScopeMessage(String role, List<DashScopeContent> content) {
    }

    private record DashScopeContent(
        String type,
        @JsonProperty("input_audio") DashScopeAudio inputAudio
    ) {
    }

    private record DashScopeAudio(String data) {
    }

    private record DashScopeParameters(
        String format,
        @JsonProperty("sample_rate") String sampleRate,
        @JsonProperty("language_hints") List<String> languageHints
    ) {
    }

    private static final class SseDiagnostics {

        private int totalEventCount;
        private int resultEventCount;
        private int finalSentenceCount;
        private final List<FinalSentenceMetadata> finalSentences = new ArrayList<>();

        private void recordFinalSentence(
            JsonNode sentenceId,
            JsonNode beginTime,
            JsonNode endTime,
            int wordCount
        ) {
            if (finalSentences.size() >= MAX_FINAL_SENTENCE_DEBUG_LOGS) {
                return;
            }
            finalSentences.add(new FinalSentenceMetadata(
                sentenceId.isValueNode() ? sentenceId.asText() : null,
                beginTime.isIntegralNumber() ? beginTime.longValue() : null,
                endTime.isIntegralNumber() ? endTime.longValue() : null,
                wordCount
            ));
        }

        private void logSummary(List<TranscriptSegment> segments) {
            long minStartMs = segments.stream().mapToLong(TranscriptSegment::startMs).min().orElse(-1L);
            long maxEndMs = segments.stream().mapToLong(TranscriptSegment::endMs).max().orElse(-1L);
            log.debug("DashScope ASR SSE parsedEvents={} resultEvents={} finalSentences={} returnedSegments={} minStartMs={} maxEndMs={}",
                totalEventCount, resultEventCount, finalSentenceCount, segments.size(), minStartMs, maxEndMs);
            for (int index = 0; index < finalSentences.size(); index++) {
                FinalSentenceMetadata sentence = finalSentences.get(index);
                log.debug("DashScope ASR final sentence index={} sentenceId={} beginTime={} endTime={} wordCount={}",
                    index, sentence.sentenceId(), sentence.beginTime(), sentence.endTime(), sentence.wordCount());
            }
        }
    }

    private record FinalSentenceMetadata(String sentenceId, Long beginTime, Long endTime, int wordCount) {
    }
}
