package com.videoagent.rag.vector;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.rag.config.MilvusProperties;
import com.videoagent.rag.retrieval.ChunkIdentity;
import com.videoagent.rag.retrieval.LexicalChunk;

import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Single derived transcript index in Milvus. Dense and BM25 searches always
 * bind the server-owned userId and videoId filter before issuing a query.
 */
@Component
public class MilvusTranscriptStore {

    static final String CHUNK_ID = "chunkId";
    static final String USER_ID = "userId";
    static final String VIDEO_ID = "videoId";
    static final String TASK_ID = "analysisTaskId";
    static final String CHUNK_INDEX = "chunkIndex";
    static final String TEXT = "text";
    static final String DENSE_VECTOR = "denseVector";
    static final String SPARSE_VECTOR = "sparseVector";
    static final String START_MS = "startMs";
    static final String END_MS = "endMs";
    static final String SOURCE_INDEXES = "sourceSegmentIndexes";

    private static final Logger log = LoggerFactory.getLogger(MilvusTranscriptStore.class);
    private static final int TEXT_MAX_LENGTH = 65_535;
    private static final int CHUNK_ID_MAX_LENGTH = 160;
    private static final int MAX_SOURCE_SEGMENTS = 4_096;
    private static final List<String> OUTPUT_FIELDS = List.of(
        CHUNK_ID, TASK_ID, CHUNK_INDEX, TEXT, START_MS, END_MS, SOURCE_INDEXES
    );
    private static final Map<String, Object> ANALYZER_PARAMS = Map.of(
        "tokenizer", "jieba",
        "filter", List.of("lowercase", "cnalphanumonly")
    );

    private final MilvusProperties properties;
    private final ObjectProvider<MilvusClientV2> clientProvider;

    public MilvusTranscriptStore(
        MilvusProperties properties,
        ObjectProvider<MilvusClientV2> clientProvider
    ) {
        this.properties = properties;
        this.clientProvider = clientProvider;
    }

    public synchronized void ensureCollection(int dimension) {
        MilvusClientV2 client = client();
        try {
            if (!hasCollection(client)) {
                try {
                    client.createCollection(createCollectionRequest(dimension));
                } catch (RuntimeException exception) {
                    if (!hasCollection(client)) {
                        throw exception;
                    }
                }
            }
            verifySchema(client, dimension);
            client.loadCollection(LoadCollectionReq.builder()
                .collectionName(properties.collection())
                .sync(true)
                .build());
        } catch (RuntimeException exception) {
            throw failure("无法初始化 Milvus 字幕集合", exception);
        }
    }

    public void upsertPoints(long userId, long videoId, long taskId, List<VectorPoint> points) {
        if (points.isEmpty()) {
            return;
        }
        List<JsonObject> rows = points.stream()
            .map(point -> row(userId, videoId, taskId, point))
            .toList();
        try {
            client().upsert(UpsertReq.builder()
                .collectionName(properties.collection())
                .data(rows)
                .build());
        } catch (RuntimeException exception) {
            log.warn("[userId={}][videoId={}][taskId={}][chunkCount={}] Milvus insert failed",
                userId, videoId, taskId, points.size());
            throw failure("Milvus 字幕索引写入失败", exception);
        }
    }

    public void deleteByVideo(long userId, long videoId) {
        try {
            deleteByVideoStrict(userId, videoId);
        } catch (VideoAgentException exception) {
            log.warn("[userId={}][videoId={}] Milvus delete best-effort failed", userId, videoId);
        }
    }

    public void deleteByVideoStrict(long userId, long videoId) {
        try {
            client().delete(DeleteReq.builder()
                .collectionName(properties.collection())
                .filter(scopeFilter(userId, videoId))
                .build());
        } catch (RuntimeException exception) {
            throw failure("Milvus 旧字幕索引删除失败", exception);
        }
    }

    public List<VectorPoint> searchDense(
        long userId,
        long videoId,
        float[] queryVector,
        int topK
    ) {
        SearchReq request = SearchReq.builder()
            .collectionName(properties.collection())
            .annsField(DENSE_VECTOR)
            .metricType(IndexParam.MetricType.COSINE)
            .data(List.of(new FloatVec(queryVector)))
            .filter(scopeFilter(userId, videoId))
            .outputFields(OUTPUT_FIELDS)
            .topK(topK)
            .consistencyLevel(ConsistencyLevel.STRONG)
            .build();
        return search(request, userId, videoId, "dense").stream()
            .map(hit -> {
                Map<String, Object> entity = hit.getEntity();
                return VectorPoint.retrieved(
                    number(entity, TASK_ID).longValue(),
                    number(entity, CHUNK_INDEX).intValue(),
                    string(entity, TEXT),
                    number(entity, START_MS).longValue(),
                    number(entity, END_MS).longValue(),
                    intList(entity, SOURCE_INDEXES),
                    hit.getScore() == null ? 0.0f : hit.getScore()
                );
            })
            .toList();
    }

    public List<LexicalChunk> searchLexical(
        long userId,
        long videoId,
        String query,
        int topK
    ) {
        SearchReq request = SearchReq.builder()
            .collectionName(properties.collection())
            .annsField(SPARSE_VECTOR)
            .metricType(IndexParam.MetricType.BM25)
            .data(List.of(new EmbeddedText(query)))
            .filter(scopeFilter(userId, videoId))
            .outputFields(OUTPUT_FIELDS)
            .topK(topK)
            .consistencyLevel(ConsistencyLevel.STRONG)
            .build();
        return search(request, userId, videoId, "bm25").stream()
            .map(hit -> {
                Map<String, Object> entity = hit.getEntity();
                return new LexicalChunk(
                    string(entity, CHUNK_ID),
                    number(entity, CHUNK_INDEX).intValue(),
                    string(entity, TEXT),
                    number(entity, START_MS).longValue(),
                    number(entity, END_MS).longValue(),
                    intList(entity, SOURCE_INDEXES),
                    hit.getScore() == null ? 0.0 : hit.getScore().doubleValue()
                );
            })
            .toList();
    }

    static Map<String, Object> analyzerParams() {
        return ANALYZER_PARAMS;
    }

    private List<SearchResp.SearchResult> search(
        SearchReq request,
        long userId,
        long videoId,
        String mode
    ) {
        try {
            SearchResp response = client().search(request);
            if (response == null || response.getSearchResults() == null
                || response.getSearchResults().isEmpty()) {
                return List.of();
            }
            return response.getSearchResults().getFirst();
        } catch (RuntimeException exception) {
            log.warn("[userId={}][videoId={}][mode={}] Milvus search failed", userId, videoId, mode);
            throw failure("Milvus 字幕检索失败", exception);
        }
    }

    private CreateCollectionReq createCollectionRequest(int dimension) {
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
            .enableDynamicField(false)
            .build();
        schema.addField(field(CHUNK_ID, DataType.VarChar).maxLength(CHUNK_ID_MAX_LENGTH)
            .isPrimaryKey(true).autoID(false).build());
        schema.addField(field(USER_ID, DataType.Int64).build());
        schema.addField(field(VIDEO_ID, DataType.Int64).build());
        schema.addField(field(TASK_ID, DataType.Int64).build());
        schema.addField(field(CHUNK_INDEX, DataType.Int32).build());
        schema.addField(field(TEXT, DataType.VarChar).maxLength(TEXT_MAX_LENGTH)
            .enableAnalyzer(true).enableMatch(true).analyzerParams(ANALYZER_PARAMS).build());
        schema.addField(field(DENSE_VECTOR, DataType.FloatVector).dimension(dimension).build());
        schema.addField(field(SPARSE_VECTOR, DataType.SparseFloatVector).build());
        schema.addField(field(START_MS, DataType.Int64).build());
        schema.addField(field(END_MS, DataType.Int64).build());
        schema.addField(field(SOURCE_INDEXES, DataType.Array)
            .elementType(DataType.Int32).maxCapacity(MAX_SOURCE_SEGMENTS).build());
        schema.addFunction(CreateCollectionReq.Function.builder()
            .name("transcript_bm25")
            .functionType(FunctionType.BM25)
            .inputFieldNames(List.of(TEXT))
            .outputFieldNames(List.of(SPARSE_VECTOR))
            .build());

        List<IndexParam> indexes = List.of(
            IndexParam.builder()
                .fieldName(DENSE_VECTOR)
                .indexName("dense_autoindex")
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE)
                .build(),
            IndexParam.builder()
                .fieldName(SPARSE_VECTOR)
                .indexName("bm25_sparse_index")
                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                .metricType(IndexParam.MetricType.BM25)
                .build()
        );
        return CreateCollectionReq.builder()
            .collectionName(properties.collection())
            .description("Derived transcript chunks for dense and BM25 retrieval")
            .collectionSchema(schema)
            .indexParams(indexes)
            .consistencyLevel(ConsistencyLevel.STRONG)
            .build();
    }

    private AddFieldReq.AddFieldReqBuilder<?> field(String name, DataType type) {
        return AddFieldReq.builder().fieldName(name).dataType(type);
    }

    private boolean hasCollection(MilvusClientV2 client) {
        return Boolean.TRUE.equals(client.hasCollection(HasCollectionReq.builder()
            .collectionName(properties.collection())
            .build()));
    }

    private void verifySchema(MilvusClientV2 client, int dimension) {
        CreateCollectionReq.CollectionSchema schema = client.describeCollection(
            DescribeCollectionReq.builder().collectionName(properties.collection()).build()
        ).getCollectionSchema();
        CreateCollectionReq.FieldSchema dense = schema == null ? null : schema.getField(DENSE_VECTOR);
        CreateCollectionReq.FieldSchema text = schema == null ? null : schema.getField(TEXT);
        CreateCollectionReq.FieldSchema sparse = schema == null ? null : schema.getField(SPARSE_VECTOR);
        boolean hasBm25 = schema != null && schema.getFunctionList() != null
            && schema.getFunctionList().stream().anyMatch(function ->
                function.getFunctionType() == FunctionType.BM25
                    && function.getOutputFieldNames().contains(SPARSE_VECTOR));
        if (dense == null || dense.getDimension() == null || dense.getDimension() != dimension
            || dense.getDataType() != DataType.FloatVector
            || text == null || text.getDataType() != DataType.VarChar
            || sparse == null || sparse.getDataType() != DataType.SparseFloatVector
            || !hasBm25) {
            throw new IllegalStateException(
                "Milvus collection schema does not match configured embedding dimension " + dimension
            );
        }
    }

    private JsonObject row(long userId, long videoId, long taskId, VectorPoint point) {
        JsonObject row = new JsonObject();
        row.addProperty(CHUNK_ID, ChunkIdentity.of(videoId, taskId, point.chunkIndex()));
        row.addProperty(USER_ID, userId);
        row.addProperty(VIDEO_ID, videoId);
        row.addProperty(TASK_ID, taskId);
        row.addProperty(CHUNK_INDEX, point.chunkIndex());
        row.addProperty(TEXT, point.text());
        row.addProperty(START_MS, point.startMs());
        row.addProperty(END_MS, point.endMs());
        JsonArray vector = new JsonArray(point.vector().length);
        for (float value : point.vector()) vector.add(value);
        row.add(DENSE_VECTOR, vector);
        JsonArray indexes = new JsonArray(point.sourceSegmentIndexes().size());
        point.sourceSegmentIndexes().forEach(indexes::add);
        row.add(SOURCE_INDEXES, indexes);
        return row;
    }

    private String scopeFilter(long userId, long videoId) {
        return USER_ID + " == " + userId + " && " + VIDEO_ID + " == " + videoId;
    }

    private Number number(Map<String, Object> entity, String name) {
        Object value = entity == null ? null : entity.get(name);
        if (value instanceof Number number) return number;
        if (value instanceof JsonElement element && element.isJsonPrimitive()) {
            return element.getAsNumber();
        }
        throw new IllegalStateException("Milvus result is missing numeric field " + name);
    }

    private String string(Map<String, Object> entity, String name) {
        Object value = entity == null ? null : entity.get(name);
        if (value instanceof JsonElement element) return element.getAsString();
        return value == null ? "" : value.toString();
    }

    private List<Integer> intList(Map<String, Object> entity, String name) {
        Object value = entity == null ? null : entity.get(name);
        if (value instanceof List<?> values) {
            return values.stream().map(item -> ((Number) item).intValue()).toList();
        }
        if (value instanceof JsonArray values) {
            List<Integer> result = new ArrayList<>(values.size());
            values.forEach(item -> result.add(item.getAsInt()));
            return List.copyOf(result);
        }
        return List.of();
    }

    private MilvusClientV2 client() {
        return clientProvider.getObject();
    }

    private VideoAgentException failure(String message, RuntimeException exception) {
        return new VideoAgentException(ErrorCode.RAG_INDEX_BUILD_FAILED, message, exception);
    }
}
