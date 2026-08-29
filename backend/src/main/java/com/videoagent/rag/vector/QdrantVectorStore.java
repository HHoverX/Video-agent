package com.videoagent.rag.vector;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.rag.config.QdrantProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimal Qdrant adapter over the REST API. Qdrant is a derived vector index:
 * business truth stays in MySQL. Every point carries userId + videoId metadata,
 * and every search filters on both, so two users with near-identical content
 * can never leak chunks across accounts.
 */
@Component
public class QdrantVectorStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStore.class);

    private final QdrantProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public QdrantVectorStore(QdrantProperties properties) {
        this(properties, RestClient.builder().build(), new ObjectMapper());
    }

    QdrantVectorStore(QdrantProperties properties, RestClient restClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    public void ensureCollection(int dimension) {
        if (collectionExists()) {
            return;
        }
        try {
            restClient.put()
                .uri(properties.baseUrl() + "/collections/" + properties.collection())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                    "vectors", Map.of(
                        "size", dimension,
                        "distance", "Cosine"
                    )
                ))
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientException exception) {
            log.warn("[collection={}][errorCode={}][exceptionClass={}][httpStatus={}] qdrant collection ensure failed",
                properties.collection(), ErrorCode.RAG_INDEX_BUILD_FAILED, exception.getClass().getSimpleName(),
                httpStatus(exception));
            throw new VideoAgentException(ErrorCode.RAG_INDEX_BUILD_FAILED,
                "无法初始化向量集合", exception);
        }
    }

    private boolean collectionExists() {
        try {
            var response = restClient.get()
                .uri(properties.baseUrl() + "/collections/" + properties.collection())
                .retrieve()
                .toBodilessEntity();
            return response.getStatusCode().is2xxSuccessful();
        } catch (RestClientException exception) {
            return false;
        }
    }

    /**
     * Deterministic point id (unsigned integer) derived from videoId, taskId
     * and chunkIndex, so a rebuild replaces the same logical points instead of
     * accumulating duplicates. Qdrant requires unsigned integer or UUID point
     * ids, so we combine the three components into a single 64-bit value.
     */
    public static long pointId(long videoId, long taskId, int chunkIndex) {
        return (videoId << 32) ^ (taskId << 8) ^ (long) chunkIndex;
    }

    public void upsertPoints(long userId, long videoId, long taskId, List<VectorPoint> points) {
        if (points.isEmpty()) {
            return;
        }
        List<Map<String, Object>> body = new ArrayList<>();
        for (VectorPoint point : points) {
            body.add(Map.of(
                "id", pointId(videoId, taskId, point.chunkIndex()),
                "vector", point.vector(),
                "payload", payload(userId, videoId, taskId, point)
            ));
        }
        try {
            restClient.put()
                .uri(properties.baseUrl() + "/collections/" + properties.collection() + "/points")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("points", body))
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientException exception) {
            log.warn("[userId={}][videoId={}][taskId={}][chunkCount={}][errorCode={}][exceptionClass={}][httpStatus={}] qdrant upsert failed",
                userId, videoId, taskId, points.size(), ErrorCode.RAG_INDEX_BUILD_FAILED,
                exception.getClass().getSimpleName(), httpStatus(exception));
            throw new VideoAgentException(ErrorCode.RAG_INDEX_BUILD_FAILED,
                "向量写入失败", exception);
        }
    }

    public void deleteByVideo(long userId, long videoId) {
        try {
            deleteByVideoStrict(userId, videoId);
        } catch (VideoAgentException exception) {
            log.warn("[userId={}][videoId={}][errorCode={}][exceptionClass={}] qdrant delete best-effort failed",
                userId, videoId, exception.errorCode(), exception.getClass().getSimpleName());
        }
    }

    public void deleteByVideoStrict(long userId, long videoId) {
        try {
            restClient.post()
                .uri(properties.baseUrl() + "/collections/" + properties.collection() + "/points/delete")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("filter", matchFilter(Map.of(
                    "userId", userId,
                    "videoId", videoId
                ))))
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientException exception) {
            throw new VideoAgentException(
                ErrorCode.RAG_INDEX_BUILD_FAILED,
                "向量旧索引删除失败",
                exception
            );
        }
    }

    public List<VectorPoint> search(long userId, long videoId, float[] queryVector, int topK) {
        Map<String, Object> body = Map.of(
            "vector", queryVector,
            "limit", topK,
            "with_payload", true,
            "filter", matchFilter(Map.of(
                "userId", userId,
                "videoId", videoId
            ))
        );
        try {
            SearchResponse response = restClient.post()
                .uri(properties.baseUrl() + "/collections/" + properties.collection() + "/points/search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(SearchResponse.class);
            if (response == null || response.result() == null) {
                return List.of();
            }
            List<VectorPoint> results = new ArrayList<>();
            for (ScoredPoint point : response.result()) {
                results.add(VectorPoint.retrieved(
                    payloadInt(point.payload(), "chunkIndex"),
                    payloadString(point.payload(), "text"),
                    payloadLong(point.payload(), "startMs"),
                    payloadLong(point.payload(), "endMs"),
                    point.score()
                ));
            }
            return results;
        } catch (RestClientException exception) {
            log.warn("[userId={}][videoId={}][topK={}][errorCode={}][exceptionClass={}][httpStatus={}] qdrant search failed",
                userId, videoId, topK, ErrorCode.RAG_INDEX_BUILD_FAILED, exception.getClass().getSimpleName(),
                httpStatus(exception));
            throw new VideoAgentException(ErrorCode.RAG_INDEX_BUILD_FAILED,
                "向量检索失败", exception);
        }
    }

    public boolean isReachable() {
        try {
            return restClient.get()
                .uri(properties.baseUrl() + "/livez")
                .retrieve()
                .toBodilessEntity()
                .getStatusCode()
                .is2xxSuccessful();
        } catch (RestClientException exception) {
            return false;
        }
    }

    private Map<String, Object> payload(long userId, long videoId, long taskId, VectorPoint point) {
        return Map.of(
            "userId", userId,
            "videoId", videoId,
            "analysisTaskId", taskId,
            "chunkIndex", point.chunkIndex(),
            "text", point.text(),
            "startMs", point.startMs(),
            "endMs", point.endMs(),
            "sourceSegmentIndexes", point.sourceSegmentIndexes()
        );
    }

    private Map<String, Object> matchFilter(Map<String, Object> matches) {
        List<Map<String, Object>> must = matches.entrySet().stream()
            .map(entry -> Map.of("key", entry.getKey(), "match", Map.of("value", entry.getValue())))
            .map(Map::copyOf)
            .toList();
        return Map.of("must", must);
    }

    private int payloadInt(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private long payloadLong(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private String payloadString(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value == null ? "" : value.toString();
    }

    private Integer httpStatus(RestClientException exception) {
        return exception instanceof RestClientResponseException responseException
            ? responseException.getStatusCode().value()
            : null;
    }

    private record SearchResponse(List<ScoredPoint> result) {
    }

    private record ScoredPoint(
        float score,
        @JsonProperty("payload") Map<String, Object> payload
    ) {
    }
}
