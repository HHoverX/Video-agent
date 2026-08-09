package com.videoagent.asr;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class GroqAsrProvider implements AsrProvider {

    private final AsrProviderProperties properties;
    private final AsrResultValidator validator;
    private final RestClient restClient;

    public GroqAsrProvider(
        AsrProviderProperties properties,
        AsrResultValidator validator
    ) {
        this(properties, validator, restClient(properties));
    }

    GroqAsrProvider(
        AsrProviderProperties properties,
        AsrResultValidator validator,
        RestClient restClient
    ) {
        this.properties = properties;
        this.validator = validator;
        this.restClient = restClient;
    }

    @Override
    public TranscriptionResult transcribe(AudioSource audioSource) {
        if (!Files.isRegularFile(audioSource.file())
            || Files.isSymbolicLink(audioSource.file())) {
            throw new VideoAgentException(ErrorCode.ASR_REQUEST_FAILED, "Groq ASR 输入音频无效");
        }

        try {
            GroqTranscriptionResponse response = restClient.post()
                .uri(properties.transcriptionUrl())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .headers(headers -> headers.setBearerAuth(properties.apiKey()))
                .body(requestBody(audioSource))
                .retrieve()
                .body(GroqTranscriptionResponse.class);
            return validator.validate(audioSource, mapSegments(response));
        } catch (VideoAgentException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            if (isTimeout(exception)) {
                throw new VideoAgentException(ErrorCode.ASR_TIMEOUT, "Groq ASR 请求超时");
            }
            throw new VideoAgentException(ErrorCode.ASR_REQUEST_FAILED, "Groq ASR 请求失败");
        } catch (RestClientResponseException exception) {
            throw new VideoAgentException(ErrorCode.ASR_REQUEST_FAILED, "Groq ASR 服务返回错误状态");
        } catch (RestClientException exception) {
            if (isTimeout(exception)) {
                throw new VideoAgentException(ErrorCode.ASR_TIMEOUT, "Groq ASR 请求超时");
            }
            throw new VideoAgentException(
                ErrorCode.ASR_RESPONSE_INVALID,
                "Groq ASR 返回无法解析的响应"
            );
        } catch (IllegalArgumentException exception) {
            throw new VideoAgentException(ErrorCode.ASR_RESPONSE_INVALID, "Groq ASR 返回无效字幕片段");
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
