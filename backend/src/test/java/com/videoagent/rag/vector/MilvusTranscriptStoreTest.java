package com.videoagent.rag.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.rag.config.MilvusProperties;
import com.videoagent.rag.retrieval.ChunkIdentity;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.SearchResp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;
import java.util.Map;

class MilvusTranscriptStoreTest {

    private final MilvusClientV2 client = mock(MilvusClientV2.class);
    private final ObjectProvider<MilvusClientV2> provider = mock(ObjectProvider.class);
    private final MilvusTranscriptStore store = new MilvusTranscriptStore(
        new MilvusProperties("http://localhost:19530", "", "test_chunks", Duration.ofSeconds(2)),
        provider
    );

    @BeforeEach
    void setUp() {
        when(provider.getObject()).thenReturn(client);
    }

    @Test
    void shouldCreateDenseAndBm25SchemaWithTechnicalTextAnalyzer() {
        when(client.hasCollection(any(HasCollectionReq.class))).thenReturn(false, true);
        when(client.describeCollection(any())).thenReturn(describeSchema(384));

        store.ensureCollection(384);

        ArgumentCaptor<CreateCollectionReq> request = ArgumentCaptor.forClass(CreateCollectionReq.class);
        verify(client).createCollection(request.capture());
        CreateCollectionReq.CollectionSchema schema = request.getValue().getCollectionSchema();
        assertThat(schema.getField(MilvusTranscriptStore.DENSE_VECTOR).getDimension()).isEqualTo(384);
        assertThat(schema.getField(MilvusTranscriptStore.TEXT).getAnalyzerParams())
            .isEqualTo(Map.of(
                "tokenizer", "jieba",
                "filter", List.of("lowercase", "cnalphanumonly")
            ));
        assertThat(schema.getFunctionList()).singleElement()
            .satisfies(function -> {
                assertThat(function.getInputFieldNames()).containsExactly(MilvusTranscriptStore.TEXT);
                assertThat(function.getOutputFieldNames()).containsExactly(MilvusTranscriptStore.SPARSE_VECTOR);
            });
        assertThat(request.getValue().getIndexParams())
            .extracting(IndexParam::getMetricType)
            .containsExactly(IndexParam.MetricType.COSINE, IndexParam.MetricType.BM25);
    }

    @Test
    void shouldWriteMetadataAndApplyUserVideoFilterToBothSearchesAndDelete() {
        VectorPoint point = new VectorPoint(
            3, "Redis 与 RocketMQ", 1000, 2000, List.of(4, 5), new float[] {1.0f, 0.0f}, 0.0f
        );
        store.upsertPoints(7L, 9L, 11L, List.of(point));

        ArgumentCaptor<UpsertReq> upsert = ArgumentCaptor.forClass(UpsertReq.class);
        verify(client).upsert(upsert.capture());
        assertThat(upsert.getValue().getData()).singleElement().satisfies(row -> {
            assertThat(row.get("chunkId").getAsString()).isEqualTo(ChunkIdentity.of(9L, 11L, 3));
            assertThat(row.get("userId").getAsLong()).isEqualTo(7L);
            assertThat(row.get("videoId").getAsLong()).isEqualTo(9L);
            assertThat(row.get("text").getAsString()).isEqualTo("Redis 与 RocketMQ");
        });

        when(client.search(any())).thenReturn(emptySearch());
        store.searchDense(7L, 9L, new float[] {1.0f, 0.0f}, 15);
        store.searchLexical(7L, 9L, "SHA-256 uploadId", 15);
        store.deleteByVideoStrict(7L, 9L);

        ArgumentCaptor<SearchReq> searches = ArgumentCaptor.forClass(SearchReq.class);
        verify(client, org.mockito.Mockito.times(2)).search(searches.capture());
        assertThat(searches.getAllValues())
            .extracting(SearchReq::getFilter)
            .containsOnly("userId == 7 && videoId == 9");
        assertThat(searches.getAllValues())
            .extracting(SearchReq::getMetricType)
            .containsExactly(IndexParam.MetricType.COSINE, IndexParam.MetricType.BM25);

        ArgumentCaptor<DeleteReq> delete = ArgumentCaptor.forClass(DeleteReq.class);
        verify(client).delete(delete.capture());
        assertThat(delete.getValue().getFilter()).isEqualTo("userId == 7 && videoId == 9");
    }

    private DescribeCollectionResp describeSchema(int dimension) {
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();
        schema.addField(AddFieldReq.builder().fieldName(MilvusTranscriptStore.TEXT)
            .dataType(DataType.VarChar).maxLength(65_535).build());
        schema.addField(AddFieldReq.builder().fieldName(MilvusTranscriptStore.DENSE_VECTOR)
            .dataType(DataType.FloatVector).dimension(dimension).build());
        schema.addField(AddFieldReq.builder().fieldName(MilvusTranscriptStore.SPARSE_VECTOR)
            .dataType(DataType.SparseFloatVector).build());
        schema.addFunction(CreateCollectionReq.Function.builder()
            .name("transcript_bm25")
            .functionType(io.milvus.common.clientenum.FunctionType.BM25)
            .inputFieldNames(List.of(MilvusTranscriptStore.TEXT))
            .outputFieldNames(List.of(MilvusTranscriptStore.SPARSE_VECTOR))
            .build());
        return DescribeCollectionResp.builder().collectionSchema(schema).build();
    }

    private SearchResp emptySearch() {
        return SearchResp.builder().searchResults(List.of(List.of())).build();
    }
}
