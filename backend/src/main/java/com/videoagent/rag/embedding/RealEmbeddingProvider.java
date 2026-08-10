package com.videoagent.rag.embedding;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.rag.config.EmbeddingProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;

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

    public RealEmbeddingProvider(EmbeddingProperties properties) {
        this(properties, restClient(properties), new ObjectMapper());
    }

    RealEmbeddingProvider(
        EmbeddingProperties properties,
        RestClient restClient,
        ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
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
        List<float[]> all = new ArrayList<>();
        for (int offset = 0; offset < texts.size(); offset += MAX_BATCH_SIZE) {
            List<String> batch = texts.subList(offset, Math.min(texts.size(), offset + MAX_BATCH_SIZE));
            all.addAll(embed(batch));
        }
        return all;
    }

    @Override
    public float[] embedQuery(String text) {
        return embed(List.of(text)).getFirst();
    }

    private List<float[]> embed(List<String> texts) {
        try {
            EmbeddingResponse response = restClient.post()
                .uri(baseUrl() + "/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(properties.apiKey()))
                .body(new EmbeddingRequest(properties.model(), texts))
                .retrieve()
                .body(EmbeddingResponse.class);
            if (response == null || response.data() == null || response.data().size() != texts.size()) {
                throw new VideoAgentException(ErrorCode.EMBEDDING_REQUEST_FAILED, "Embedding 服务返回结果数量不匹配");
            }
            List<float[]> vectors = new ArrayList<>(response.data().size());
            for (EmbeddingData data : response.data()) {
                if (data.embedding() == null || data.embedding().length == 0) {
                    throw new VideoAgentException(ErrorCode.EMBEDDING_REQUEST_FAILED, "Embedding 服务返回空向量");
                }
                vectors.add(data.embedding());
            }
            return vectors;
        } catch (VideoAgentException exception) {
            throw exception;
        } catch (RestClientException exception) {
            log.warn("[embedding] real embedding request failed: {}", exception.getMessage(), exception);
            throw new VideoAgentException(ErrorCode.EMBEDDING_REQUEST_FAILED, "Embedding 服务请求失败", exception);
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
