package com.videoagent.rag.embedding;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.rag.config.EmbeddingProperties;
import com.videoagent.provider.ProviderHttpFailure;
import com.videoagent.telemetry.AiUsageMetrics;
import com.videoagent.telemetry.AnalysisTelemetryContext;
import com.videoagent.telemetry.QaTelemetryContext;
import com.videoagent.telemetry.QaTelemetryRoute;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI-compatible embedding provider over HTTP. Fully configured by
 * environment variables; no API key/model/URL is hard-coded. A non-2xx status
 * or malformed response is surfaced as a business error without leaking
 * credentials.
 */
public class RealEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(RealEmbeddingProvider.class);

    private final EmbeddingProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AiUsageMetrics usageMetrics;

    public RealEmbeddingProvider(EmbeddingProperties properties) {
        this(properties, AiUsageMetrics.noop());
    }

    public RealEmbeddingProvider(EmbeddingProperties properties, AiUsageMetrics usageMetrics) {
        this(properties, restClient(properties), new ObjectMapper(), usageMetrics);
    }

    RealEmbeddingProvider(
        EmbeddingProperties properties,
        RestClient restClient,
        ObjectMapper objectMapper
    ) {
        this(properties, restClient, objectMapper, AiUsageMetrics.noop());
    }

    RealEmbeddingProvider(
        EmbeddingProperties properties,
        RestClient restClient,
        ObjectMapper objectMapper,
        AiUsageMetrics usageMetrics
    ) {
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.usageMetrics = usageMetrics == null ? AiUsageMetrics.noop() : usageMetrics;
    }

    private static final int MAX_BATCH_SIZE = 10;

    @Override
    public String providerName() {
        return properties.provider();
    }

    @Override
    public int dimension() {
        return properties.dimension();
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        return embedDocuments(texts, AnalysisTelemetryContext.unavailable());
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts, AnalysisTelemetryContext telemetryContext) {
        AnalysisTelemetryContext context = telemetryContext == null
            ? AnalysisTelemetryContext.unavailable()
            : telemetryContext;
        List<float[]> all = new ArrayList<>();
        int batchCount = (texts.size() + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE;
        for (int offset = 0; offset < texts.size(); offset += MAX_BATCH_SIZE) {
            List<String> batch = texts.subList(offset, Math.min(texts.size(), offset + MAX_BATCH_SIZE));
            all.addAll(embed(batch, context, null, null, offset / MAX_BATCH_SIZE, batchCount));
        }
        return all;
    }

    @Override
    public float[] embedQuery(String text) {
        return embed(List.of(text), AnalysisTelemetryContext.unavailable(), null, null, 0, 1).getFirst();
    }

    @Override
    public float[] embedQuery(
        String text,
        QaTelemetryContext telemetryContext,
        QaTelemetryRoute telemetryRoute
    ) {
        return embed(
            List.of(text),
            AnalysisTelemetryContext.unavailable(),
            telemetryContext,
            telemetryRoute,
            0,
            1
        ).getFirst();
    }

    private List<float[]> embed(
        List<String> texts,
        AnalysisTelemetryContext telemetryContext,
        QaTelemetryContext qaTelemetryContext,
        QaTelemetryRoute qaTelemetryRoute,
        int batchIndex,
        int batchCount
    ) {
        boolean analysisTelemetry = telemetryContext.taskId() != null;
        boolean qaTelemetry = qaTelemetryContext != null && qaTelemetryRoute != null;
        long inputChars = texts.stream().mapToLong(text -> text == null ? 0L : text.length()).sum();
        if (analysisTelemetry) {
            usageMetrics.recordInputScale("embedding_document", properties.provider(), properties.model(), "document",
                "document_count", texts.size());
            usageMetrics.recordInputScale("embedding_document", properties.provider(), properties.model(), "document",
                "input_chars", inputChars);
        }
        if (qaTelemetry) {
            usageMetrics.recordInputScale("qa", "embedding_query", properties.provider(), properties.model(),
                qaTelemetryRoute.value(), "query_chars", inputChars);
        }
        long startedAtNanos = System.nanoTime();
        String outcome = "failure";
        String errorCategory = ErrorCode.EMBEDDING_REQUEST_FAILED.name();
        int httpStatus = -1;
        try {
            EmbeddingResponse response = restClient.post()
                .uri(baseUrl() + "/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(properties.apiKey()))
                .body(new EmbeddingRequest(properties.model(), texts))
                .retrieve()
                .body(EmbeddingResponse.class);
            if (response == null || response.data() == null || response.data().size() != texts.size()) {
                throw new VideoAgentException(ErrorCode.EMBEDDING_RESPONSE_INVALID, "Embedding 服务返回结果数量不匹配");
            }
            List<float[]> vectors = new ArrayList<>(response.data().size());
            for (EmbeddingData data : response.data()) {
                if (data.embedding() == null || data.embedding().length == 0) {
                    throw new VideoAgentException(ErrorCode.EMBEDDING_RESPONSE_INVALID, "Embedding 服务返回空向量");
                }
                vectors.add(data.embedding());
            }
            outcome = "success";
            errorCategory = "none";
            return vectors;
        } catch (VideoAgentException exception) {
            errorCategory = exception.errorCode().name();
            throw exception;
        } catch (RestClientResponseException exception) {
            httpStatus = exception.getStatusCode().value();
            VideoAgentException failure = ProviderHttpFailure.forStatus(
                exception.getStatusCode().value(),
                exception.getResponseHeaders() == null ? null : exception.getResponseHeaders().getFirst("Retry-After"),
                "Embedding",
                "向量化",
                ErrorCode.EMBEDDING_REQUEST_FAILED,
                ErrorCode.EMBEDDING_PROVIDER_REJECTED
            );
            errorCategory = "HTTP_" + httpStatus / 100 + "XX";
            throw failure;
        } catch (ResourceAccessException exception) {
            log.warn("[embedding][errorCode={}][exceptionClass={}] network or timeout failure",
                ErrorCode.EMBEDDING_REQUEST_FAILED, exception.getClass().getSimpleName());
            errorCategory = ErrorCode.EMBEDDING_REQUEST_FAILED.name();
            throw new VideoAgentException(ErrorCode.EMBEDDING_REQUEST_FAILED, "Embedding 服务网络请求失败", exception);
        } catch (RestClientException exception) {
            log.warn("[embedding][errorCode={}][exceptionClass={}] real embedding response parsing failed",
                ErrorCode.EMBEDDING_RESPONSE_INVALID, exception.getClass().getSimpleName());
            errorCategory = ErrorCode.EMBEDDING_RESPONSE_INVALID.name();
            throw new VideoAgentException(ErrorCode.EMBEDDING_RESPONSE_INVALID, "Embedding 服务响应无法解析", exception);
        } finally {
            if (analysisTelemetry) {
                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
                usageMetrics.recordProviderRequest("embedding_document", properties.provider(), properties.model(),
                    "document", outcome, errorCategory, durationMs);
                log.info("event=ai.provider_request scope=analysis stage=embedding_document provider={} model={} taskId={} videoId={} generation={} retryCount={} batchIndex={} batchCount={} documentCount={} inputChars={} durationMs={} outcome={} httpStatus={} errorCategory={}",
                    properties.provider(), properties.model(), telemetryContext.taskId(), telemetryContext.videoId(),
                    telemetryContext.generation(), telemetryContext.retryCount(), batchIndex, batchCount, texts.size(),
                    inputChars, durationMs, outcome, httpStatus < 0 ? null : httpStatus, errorCategory);
            }
            if (qaTelemetry) {
                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
                usageMetrics.recordProviderRequest("qa", "embedding_query", properties.provider(), properties.model(),
                    qaTelemetryRoute.value(), outcome, errorCategory, durationMs);
                log.info("event=ai.provider_request scope=qa stage=embedding_query provider={} model={} requestId={} videoId={} analysisTaskId={} mode={} queryChars={} durationMs={} outcome={} httpStatus={} errorCategory={}",
                    properties.provider(), properties.model(), qaTelemetryContext.requestId(),
                    qaTelemetryContext.videoId(), qaTelemetryContext.analysisTaskId(), qaTelemetryRoute.value(),
                    inputChars, durationMs, outcome, httpStatus < 0 ? null : httpStatus, errorCategory);
            }
        }
    }

    private String baseUrl() {
        String url = properties.baseUrl();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static RestClient restClient(EmbeddingProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    private record EmbeddingRequest(String model, List<String> input) {
    }

    private record EmbeddingResponse(
        @JsonProperty("data") List<EmbeddingData> data
    ) {
    }

    private record EmbeddingData(
        @JsonProperty("embedding") float[] embedding
    ) {
    }
}
