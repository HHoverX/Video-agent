package com.videoagent.asr;

import com.fasterxml.jackson.annotation.JsonInclude;
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
    private static final TranscriptEvidenceSegmenter evidenceSegmenter = new TranscriptEvidenceSegmenter();

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
                    return parseSse(response.getBody(), audioSource.videoDurationSeconds());
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
            new DashScopeParameters("wav", "16000", properties.languageHints())
        );
    }

    private List<TranscriptSegment> parseSse(
        InputStream inputStream,
        Integer videoDurationSeconds
    ) throws IOException {
        List<TranscriptSegment> segments = new ArrayList<>();
        List<FinalSentenceCandidate> candidates = new ArrayList<>();
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
                        acceptEvent(eventName, data, candidates, diagnostics);
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
                acceptEvent(eventName, data, candidates, diagnostics);
            }
            if (candidates.isEmpty()) {
                throw new VideoAgentException(
                    ErrorCode.ASR_RESPONSE_INVALID,
                    "DashScope ASR 未返回最终句子"
                );
            }
            NormalizationResult normalized = normalizeCandidates(candidates);
            log.debug("DashScope ASR final candidate normalization candidates={} canonical={} duplicates={} superseded={}",
                candidates.size(), normalized.candidates().size(), normalized.duplicates(), normalized.superseded());
            for (FinalSentenceCandidate candidate : normalized.candidates()) {
                refineCandidate(candidate, segments, videoDurationSeconds);
            }
            return segments;
        } finally {
            diagnostics.logSummary(segments);
        }
    }

    private void acceptEvent(
        String eventName,
        StringBuilder data,
        List<FinalSentenceCandidate> candidates,
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
            TranscriptSegment finalSentence = new TranscriptSegment(
                beginTime.longValue(),
                endTime.longValue(),
                text.asText()
            );
            TimedWordsParseResult parsedWords = timedWords(sentence);
            candidates.add(new FinalSentenceCandidate(
                finalSentence,
                sentence.deepCopy(),
                parsedWords,
                sentence.path("sentence_id").isValueNode() ? sentence.path("sentence_id").asText() : null
            ));
        } catch (JsonProcessingException exception) {
            throw invalidResponse("DashScope ASR SSE 数据无法解析");
        }
    }

    private void refineCandidate(
        FinalSentenceCandidate candidate,
        List<TranscriptSegment> segments,
        Integer videoDurationSeconds
    ) {
        TimedWordsParseResult parsedWords = candidate.parsedWords();
        TranscriptSegment finalSentence = candidate.finalSentence();
        if (parsedWords.reason() != null) {
            log.debug("DashScope ASR timed-word parsing fallback rawWordCount={} parsedTimedWordCount={} reason={} wordIndex={}",
                parsedWords.rawWordCount(), parsedWords.parsedTimedWordCount(), parsedWords.reason(), parsedWords.wordIndex());
            log.debug("DashScope ASR evidence refinement fallback reason={} inputTimedWords={}",
                TranscriptEvidenceSegmenter.FallbackReason.INVALID_WORD_FIELD, parsedWords.words().size());
            segments.add(finalSentence);
            return;
        }
        TranscriptEvidenceSegmenter.RefinementResult refinement = evidenceSegmenter.refineWithDiagnostics(
            finalSentence, parsedWords.words(), videoDurationSeconds
        );
        if (refinement.refined()) {
            TranscriptEvidenceSegmenter.DurationPolicy policy = refinement.policy();
            log.debug("DashScope ASR evidence refinement succeeded policyMinMs={} policyTargetMs={} policyMaxMs={} inputTimedWords={} outputSegments={}",
                policy.minMs(), policy.targetMs(), policy.maxMs(), refinement.inputTimedWords(), refinement.segments().size());
        } else {
            logRefinementFallback(refinement);
        }
        segments.addAll(refinement.segments());
    }

    private NormalizationResult normalizeCandidates(List<FinalSentenceCandidate> candidates) {
        List<FinalSentenceCandidate> canonical = new ArrayList<>();
        int duplicates = 0;
        int superseded = 0;
        for (FinalSentenceCandidate candidate : candidates) {
            if (canonical.isEmpty()) {
                canonical.add(candidate);
                continue;
            }
            FinalSentenceCandidate previous = canonical.getLast();
            if (isExactDuplicate(previous, candidate)) {
                duplicates++;
                continue;
            }
            if (isCumulativeSupersession(previous, candidate)) {
                canonical.set(canonical.size() - 1, candidate);
                superseded++;
                continue;
            }
            if (candidate.finalSentence().startMs() >= previous.finalSentence().endMs()) {
                canonical.add(candidate);
                continue;
            }
            throw ambiguousOverlap(previous, candidate);
        }
        return new NormalizationResult(canonical, duplicates, superseded);
    }

    private boolean isExactDuplicate(FinalSentenceCandidate previous, FinalSentenceCandidate candidate) {
        return previous.finalSentence().equals(candidate.finalSentence())
            && previous.providerSnapshot().equals(candidate.providerSnapshot());
    }

    private boolean isCumulativeSupersession(FinalSentenceCandidate previous, FinalSentenceCandidate candidate) {
        List<TranscriptEvidenceSegmenter.TimedWord> earlierWords = previous.parsedWords().words();
        List<TranscriptEvidenceSegmenter.TimedWord> laterWords = candidate.parsedWords().words();
        if (previous.parsedWords().reason() != null || candidate.parsedWords().reason() != null
            || earlierWords.isEmpty() || laterWords.size() <= earlierWords.size()
            || candidate.finalSentence().startMs() != previous.finalSentence().startMs()
            || candidate.finalSentence().endMs() <= previous.finalSentence().endMs()) {
            return false;
        }
        return laterWords.subList(0, earlierWords.size()).equals(earlierWords);
    }

    private VideoAgentException ambiguousOverlap(
        FinalSentenceCandidate previous,
        FinalSentenceCandidate candidate
    ) {
        log.debug("DashScope ASR final candidate normalization ambiguous overlap previousBeginTime={} previousEndTime={} previousWordCount={} candidateBeginTime={} candidateEndTime={} candidateWordCount={}",
            previous.finalSentence().startMs(), previous.finalSentence().endMs(), previous.parsedWords().rawWordCount(),
            candidate.finalSentence().startMs(), candidate.finalSentence().endMs(), candidate.parsedWords().rawWordCount());
        return invalidResponse("DashScope ASR 最终句子快照重叠且无法安全归一化");
    }

    static int wordCount(JsonNode sentence) {
        JsonNode words = sentence.path("words");
        return words.isArray() ? words.size() : 0;
    }

    private TimedWordsParseResult timedWords(JsonNode sentence) {
        JsonNode words = sentence.path("words");
        if (!words.isArray()) {
            return TimedWordsParseResult.failure(0, 0, "INVALID_WORD_TEXT", null);
        }
        if (words.isEmpty()) {
            return TimedWordsParseResult.success(0, List.of());
        }
        List<TranscriptEvidenceSegmenter.TimedWord> timedWords = new ArrayList<>(words.size());
        for (int index = 0; index < words.size(); index++) {
            JsonNode word = words.get(index);
            JsonNode text = word.path("text");
            JsonNode beginTime = word.path("begin_time");
            JsonNode endTime = word.path("end_time");
            JsonNode punctuation = word.path("punctuation");
            JsonNode fixed = word.path("fixed");
            if (!text.isTextual()) {
                logWordStructure(index, text, punctuation);
                return TimedWordsParseResult.failure(words.size(), timedWords.size(), "INVALID_WORD_TEXT", index);
            }
            if (!beginTime.isIntegralNumber() || !endTime.isIntegralNumber()) {
                return TimedWordsParseResult.failure(words.size(), timedWords.size(), "INVALID_WORD_TIME_TYPE", index);
            }
            if (!punctuation.isMissingNode() && !punctuation.isTextual()) {
                return TimedWordsParseResult.failure(words.size(), timedWords.size(), "INVALID_PUNCTUATION", index);
            }
            String punctuationValue = punctuation.isMissingNode() ? "" : punctuation.asText();
            if (text.asText().isEmpty() && punctuationValue.isEmpty()) {
                logWordStructure(index, text, punctuation);
                return TimedWordsParseResult.failure(words.size(), timedWords.size(), "INVALID_WORD_TEXT", index);
            }
            if (!fixed.isMissingNode() && !fixed.isBoolean()) {
                return TimedWordsParseResult.failure(words.size(), timedWords.size(), "INVALID_FIXED", index);
            }
            if (fixed.isBoolean() && !fixed.asBoolean()) {
                return TimedWordsParseResult.failure(words.size(), timedWords.size(), "UNSTABLE_WORD", index);
            }
            timedWords.add(new TranscriptEvidenceSegmenter.TimedWord(
                text.asText(),
                punctuationValue,
                beginTime.longValue(),
                endTime.longValue()
            ));
        }
        return TimedWordsParseResult.success(words.size(), timedWords);
    }

    private void logWordStructure(int wordIndex, JsonNode text, JsonNode punctuation) {
        log.debug("DashScope ASR timed-word structure wordIndex={} textPresent={} textIsString={} textLength={} punctuationPresent={} punctuationIsString={} punctuationLength={}",
            wordIndex,
            !text.isMissingNode(),
            text.isTextual(),
            text.isTextual() ? text.asText().length() : null,
            !punctuation.isMissingNode(),
            punctuation.isTextual(),
            punctuation.isTextual() ? punctuation.asText().length() : null);
    }

    private void logRefinementFallback(TranscriptEvidenceSegmenter.RefinementResult refinement) {
        TranscriptEvidenceSegmenter.EvidenceValidation validation = refinement.validation();
        if (validation == null) {
            log.debug("DashScope ASR evidence refinement fallback reason={} inputTimedWords={}",
                refinement.fallbackReason(), refinement.inputTimedWords());
            return;
        }
        log.debug("DashScope ASR evidence refinement fallback reason={} inputTimedWords={} wordIndex={} beginTime={} endTime={} previousBeginTime={} previousEndTime={} reconstructedLength={} sentenceTextLength={}",
            refinement.fallbackReason(), refinement.inputTimedWords(), validation.wordIndex(),
            validation.beginTime(), validation.endTime(), validation.previousBeginTime(), validation.previousEndTime(),
            validation.reconstructedLength(), validation.sentenceTextLength());
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
        @JsonProperty("language_hints") @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> languageHints
    ) {
    }

    private record FinalSentenceCandidate(
        TranscriptSegment finalSentence,
        JsonNode providerSnapshot,
        TimedWordsParseResult parsedWords,
        String sentenceId
    ) {
    }

    private record NormalizationResult(
        List<FinalSentenceCandidate> candidates,
        int duplicates,
        int superseded
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

    private record TimedWordsParseResult(
        int rawWordCount,
        List<TranscriptEvidenceSegmenter.TimedWord> words,
        int parsedTimedWordCount,
        String reason,
        Integer wordIndex
    ) {
        private static TimedWordsParseResult success(int rawWordCount, List<TranscriptEvidenceSegmenter.TimedWord> words) {
            return new TimedWordsParseResult(rawWordCount, words, words.size(), null, null);
        }

        private static TimedWordsParseResult failure(int rawWordCount, int parsedTimedWordCount, String reason, Integer wordIndex) {
            return new TimedWordsParseResult(rawWordCount, List.of(), parsedTimedWordCount, reason, wordIndex);
        }
    }
}
