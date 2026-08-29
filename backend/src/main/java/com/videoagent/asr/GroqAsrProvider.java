package com.videoagent.asr;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.provider.ProviderHttpFailure;
import com.videoagent.telemetry.AiUsageMetrics;
import com.videoagent.telemetry.AnalysisTelemetryContext;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class GroqAsrProvider implements AsrProvider {

    private final AsrProviderProperties properties;
    private final AsrResultValidator validator;
    private final RestClient restClient;
    private final AiUsageMetrics usageMetrics;

    public GroqAsrProvider(
        AsrProviderProperties properties,
        AsrResultValidator validator
    ) {
        this(properties, validator, AiUsageMetrics.noop());
    }

    public GroqAsrProvider(
        AsrProviderProperties properties,
        AsrResultValidator validator,
        AiUsageMetrics usageMetrics
    ) {
        this(properties, validator, restClient(properties), usageMetrics);
    }

    GroqAsrProvider(
        AsrProviderProperties properties,
        AsrResultValidator validator,
        RestClient restClient
    ) {
        this(properties, validator, restClient, AiUsageMetrics.noop());
    }

    GroqAsrProvider(
        AsrProviderProperties properties,
        AsrResultValidator validator,
        RestClient restClient,
        AiUsageMetrics usageMetrics
    ) {
        this.properties = properties;
        this.validator = validator;
        this.restClient = restClient;
        this.usageMetrics = usageMetrics == null ? AiUsageMetrics.noop() : usageMetrics;
    }

    @Override
    public TranscriptionResult transcribe(AudioSource audioSource) {
        return transcribe(audioSource, AnalysisTelemetryContext.unavailable());
    }

    @Override
    public TranscriptionResult transcribe(
        AudioSource audioSource,
        AnalysisTelemetryContext telemetryContext
    ) {
        telemetryContext = telemetryContext == null ? AnalysisTelemetryContext.unavailable() : telemetryContext;
        long logicalStartedAtNanos = System.nanoTime();
        String logicalOutcome = "failure";
        String logicalErrorCategory = ErrorCode.ASR_REQUEST_FAILED.name();
        Long sourceDurationMs = durationMs(audioSource);
        if (sourceDurationMs != null) {
            usageMetrics.recordInputScale("asr", properties.provider(), properties.model(), "audio",
                "source_duration_ms", sourceDurationMs);
        }
        if (!Files.isRegularFile(audioSource.file())
            || Files.isSymbolicLink(audioSource.file())) {
            VideoAgentException failure = new VideoAgentException(ErrorCode.ASR_REQUEST_FAILED, "Groq ASR 输入音频无效");
            logLogicalCall(telemetryContext, sourceDurationMs, elapsedMs(logicalStartedAtNanos), "failure",
                failure.errorCode().name());
            usageMetrics.recordLogicalCall("asr", properties.provider(), properties.model(), "audio", "failure",
                failure.errorCode().name(), elapsedMs(logicalStartedAtNanos));
            throw failure;
        }

        long inputBytes = fileSize(audioSource);
        usageMetrics.recordInputScale("asr", properties.provider(), properties.model(), "audio", "payload_bytes", inputBytes);
        long providerStartedAtNanos = System.nanoTime();
        String providerOutcome = "failure";
        String providerErrorCategory = ErrorCode.ASR_REQUEST_FAILED.name();
        int httpStatus = -1;
        try {
            GroqTranscriptionResponse response = restClient.post()
                .uri(properties.transcriptionUrl())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .headers(headers -> headers.setBearerAuth(properties.apiKey()))
                .body(requestBody(audioSource))
                .retrieve()
                .body(GroqTranscriptionResponse.class);
            TranscriptionResult result = validator.validate(audioSource, mapSegments(response));
            logicalOutcome = "success";
            logicalErrorCategory = "none";
            providerOutcome = "success";
            providerErrorCategory = "none";
            return result;
        } catch (VideoAgentException exception) {
            logicalErrorCategory = exception.errorCode().name();
            providerErrorCategory = exception.errorCode().name();
            throw exception;
        } catch (ResourceAccessException exception) {
            if (isTimeout(exception)) {
                logicalErrorCategory = ErrorCode.ASR_TIMEOUT.name();
                providerErrorCategory = ErrorCode.ASR_TIMEOUT.name();
                throw new VideoAgentException(ErrorCode.ASR_TIMEOUT, "Groq ASR 请求超时");
            }
            logicalErrorCategory = ErrorCode.ASR_REQUEST_FAILED.name();
            providerErrorCategory = ErrorCode.ASR_REQUEST_FAILED.name();
            throw new VideoAgentException(ErrorCode.ASR_REQUEST_FAILED, "Groq ASR 请求失败");
        } catch (RestClientResponseException exception) {
            httpStatus = exception.getStatusCode().value();
            VideoAgentException failure = ProviderHttpFailure.forStatus(
                exception.getStatusCode().value(),
                exception.getResponseHeaders() == null ? null : exception.getResponseHeaders().getFirst("Retry-After"),
                "Groq ASR",
                "语音转写",
                ErrorCode.ASR_REQUEST_FAILED,
                ErrorCode.ASR_PROVIDER_REJECTED
            );
            logicalErrorCategory = failure.errorCode().name();
            providerErrorCategory = "HTTP_" + httpStatus / 100 + "XX";
            throw failure;
        } catch (RestClientException exception) {
            if (isTimeout(exception)) {
                logicalErrorCategory = ErrorCode.ASR_TIMEOUT.name();
                providerErrorCategory = ErrorCode.ASR_TIMEOUT.name();
                throw new VideoAgentException(ErrorCode.ASR_TIMEOUT, "Groq ASR 请求超时");
            }
            logicalErrorCategory = ErrorCode.ASR_RESPONSE_INVALID.name();
            providerErrorCategory = ErrorCode.ASR_RESPONSE_INVALID.name();
            throw new VideoAgentException(
                ErrorCode.ASR_RESPONSE_INVALID,
                "Groq ASR 返回无法解析的响应"
            );
        } catch (IllegalArgumentException exception) {
            logicalErrorCategory = ErrorCode.ASR_RESPONSE_INVALID.name();
            providerErrorCategory = ErrorCode.ASR_RESPONSE_INVALID.name();
            throw new VideoAgentException(ErrorCode.ASR_RESPONSE_INVALID, "Groq ASR 返回无效字幕片段");
        } finally {
            long providerDurationMs = elapsedMs(providerStartedAtNanos);
            long logicalDurationMs = elapsedMs(logicalStartedAtNanos);
            usageMetrics.recordProviderRequest("asr", properties.provider(), properties.model(), "audio",
                providerOutcome, providerErrorCategory, providerDurationMs);
            usageMetrics.recordLogicalCall("asr", properties.provider(), properties.model(), "audio",
                logicalOutcome, logicalErrorCategory, logicalDurationMs);
            logProviderRequest(telemetryContext, sourceDurationMs, inputBytes, providerDurationMs, providerOutcome,
                httpStatus < 0 ? null : httpStatus, providerErrorCategory);
            logLogicalCall(telemetryContext, sourceDurationMs, logicalDurationMs, logicalOutcome, logicalErrorCategory);
        }
    }

    private MultiValueMap<String, Object> requestBody(AudioSource audioSource) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(audioSource.file()));
        body.add("model", properties.model());
        body.add("response_format", "verbose_json");
        body.add("timestamp_granularities[]", "segment");
        body.add("language", "zh");
        return body;
    }

    private List<TranscriptSegment> mapSegments(GroqTranscriptionResponse response) {
        if (response == null || response.segments() == null || response.segments().isEmpty()) {
            throw new VideoAgentException(ErrorCode.ASR_RESPONSE_INVALID, "Groq ASR 未返回字幕片段");
        }
        List<TranscriptSegment> segments = new ArrayList<>(response.segments().size());
        for (GroqSegment segment : response.segments()) {
            if (segment == null
                || segment.start() == null
                || segment.end() == null
                || !Double.isFinite(segment.start())
                || !Double.isFinite(segment.end())
                || segment.text() == null
                || segment.text().isBlank()) {
                throw new VideoAgentException(ErrorCode.ASR_RESPONSE_INVALID, "Groq ASR 字幕片段字段无效");
            }
            segments.add(new TranscriptSegment(
                Math.round(segment.start() * 1_000),
                Math.round(segment.end() * 1_000),
                segment.text()
            ));
        }
        return segments;
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

    private Long durationMs(AudioSource audioSource) {
        Integer durationSeconds = audioSource.videoDurationSeconds();
        return durationSeconds == null ? null : Math.max(0L, durationSeconds.longValue() * 1_000L);
    }

    private long fileSize(AudioSource audioSource) {
        try {
            return Files.size(audioSource.file());
        } catch (IOException exception) {
            return 0L;
        }
    }

    private long elapsedMs(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private void logLogicalCall(
        AnalysisTelemetryContext context,
        Long sourceDurationMs,
        long durationMs,
        String outcome,
        String errorCategory
    ) {
        org.slf4j.LoggerFactory.getLogger(GroqAsrProvider.class).info(
            "event=ai.logical_call scope=analysis stage=asr provider={} model={} taskId={} videoId={} generation={} retryCount={} sourceDurationMs={} durationMs={} outcome={} errorCategory={}",
            properties.provider(), properties.model(), context.taskId(), context.videoId(), context.generation(),
            context.retryCount(), sourceDurationMs, durationMs, outcome, errorCategory
        );
    }

    private void logProviderRequest(
        AnalysisTelemetryContext context,
        Long sourceDurationMs,
        long inputBytes,
        long durationMs,
        String outcome,
        Integer httpStatus,
        String errorCategory
    ) {
        org.slf4j.LoggerFactory.getLogger(GroqAsrProvider.class).info(
            "event=ai.provider_request scope=analysis stage=asr provider={} model={} taskId={} videoId={} generation={} retryCount={} sourceDurationMs={} inputBytes={} durationMs={} outcome={} httpStatus={} errorCategory={}",
            properties.provider(), properties.model(), context.taskId(), context.videoId(), context.generation(),
            context.retryCount(), sourceDurationMs, inputBytes, durationMs, outcome, httpStatus, errorCategory
        );
    }

    private static RestClient restClient(AsrProviderProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());
        return RestClient.builder()
            .requestFactory(requestFactory)
            .build();
    }

    private record GroqTranscriptionResponse(List<GroqSegment> segments) {
    }

    private record GroqSegment(Double start, Double end, String text) {
    }
}
