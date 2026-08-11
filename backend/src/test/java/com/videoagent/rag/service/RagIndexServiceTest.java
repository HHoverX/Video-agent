package com.videoagent.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.rag.chunk.TranscriptChunker;
import com.videoagent.rag.config.EmbeddingProperties;
import com.videoagent.rag.config.RagProperties;
import com.videoagent.rag.context.ContextStrategyResolver;
import com.videoagent.rag.embedding.EmbeddingProvider;
import com.videoagent.rag.entity.RagIndexStatus;
import com.videoagent.rag.entity.VideoRagIndexEntity;
import com.videoagent.rag.repository.VideoRagIndexRepository;
import com.videoagent.rag.vector.QdrantVectorStore;
import com.videoagent.rag.vector.VectorPoint;
import com.videoagent.transcript.entity.VideoTranscriptSegmentEntity;
import com.videoagent.transcript.repository.VideoTranscriptSegmentRepository;
import com.videoagent.video.service.VideoOwnershipService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

class RagIndexServiceTest {

    private final VideoRagIndexRepository indexRepository = mock(VideoRagIndexRepository.class);
    private final VideoTranscriptSegmentRepository segmentRepository = mock(VideoTranscriptSegmentRepository.class);
    private final VideoOwnershipService ownershipService = mock(VideoOwnershipService.class);
    private final EmbeddingProvider embeddingProvider = mock(EmbeddingProvider.class);
    private final QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final RagProperties ragProperties = new RagProperties(1000, 200, 1, 5);
    private final EmbeddingProperties embeddingProperties = new EmbeddingProperties("mock", "", "", "", 384, java.time.Duration.ofSeconds(30));
    private RagIndexService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
            .thenReturn(new SimpleTransactionStatus());
        service = new RagIndexService(
            indexRepository,
            segmentRepository,
            ownershipService,
            new ContextStrategyResolver(ragProperties),
            new TranscriptChunker(ragProperties),
            embeddingProvider,
            vectorStore,
            ragProperties,
            embeddingProperties,
            java.util.Optional.of(transactionManager)
        );
    }

    @Test
    void shouldNotBuildIndexForShortTranscript() {
        long videoId = 7L;
        when(segmentRepository.findLatestSuccessfulByVideoId(videoId)).thenReturn(segments(2));
        when(indexRepository.findByVideoId(videoId)).thenReturn(null);

        VideoRagIndexEntity result = service.buildIndex(videoId, 1L);

        assertThat(result.getStatus()).isEqualTo(RagIndexStatus.NOT_REQUIRED.name());
        assertThat(result.getContextMode()).isEqualTo("DIRECT_CONTEXT");
        verify(embeddingProvider, never()).embedDocuments(any());
        verify(vectorStore, never()).upsertPoints(anyLong(), anyLong(), anyLong(), any());
        verify(vectorStore, never()).ensureCollection(anyInt());
    }

    @Test
    void shouldBuildIndexForLongTranscript() {
        long videoId = 7L;
        when(segmentRepository.findLatestSuccessfulByVideoId(videoId)).thenReturn(segments(20));
        VideoRagIndexEntity index = indexEntity(videoId, 3L, RagIndexStatus.NOT_BUILT.name());
        VideoRagIndexEntity ready = indexEntity(videoId, 3L, RagIndexStatus.READY.name());
        ready.setChunkCount(2);
        when(indexRepository.findByVideoId(videoId)).thenReturn(index);
        when(indexRepository.claimBuilding(eq(99L), any(LocalDateTime.class))).thenReturn(1);
        when(embeddingProvider.embedDocuments(any())).thenReturn(java.util.stream.IntStream.range(0, 20).mapToObj(i -> new float[384]).toList());
        when(embeddingProvider.providerName()).thenReturn("mock");
        when(indexRepository.markReady(eq(99L), anyInt(), any(LocalDateTime.class))).thenReturn(1);
        when(indexRepository.selectById(99L)).thenReturn(ready);

        VideoRagIndexEntity result = service.buildIndex(videoId, 1L);

        assertThat(result.getStatus()).isEqualTo(RagIndexStatus.READY.name());
        verify(vectorStore).ensureCollection(384);
        verify(vectorStore).deleteByVideoStrict(1L, videoId);
        verify(vectorStore).upsertPoints(eq(1L), eq(7L), eq(3L), any());

        InOrder order = inOrder(indexRepository, transactionManager, embeddingProvider);
        order.verify(indexRepository).claimBuilding(eq(99L), any(LocalDateTime.class));
        order.verify(transactionManager).commit(any());
        order.verify(embeddingProvider).embedDocuments(any());
    }

    @Test
    void shouldUseDeterministicPointIds() {
        assertThat(QdrantVectorStore.pointId(7L, 3L, 0)).isEqualTo(QdrantVectorStore.pointId(7L, 3L, 0));
        assertThat(QdrantVectorStore.pointId(7L, 3L, 5)).isNotEqualTo(QdrantVectorStore.pointId(7L, 3L, 0));
        assertThat(QdrantVectorStore.pointId(7L, 3L, 0)).isEqualTo(QdrantVectorStore.pointId(7L, 3L, 0));
        assertThat(QdrantVectorStore.pointId(7L, 3L, 0)).isNotEqualTo(QdrantVectorStore.pointId(8L, 3L, 0));
        assertThat(QdrantVectorStore.pointId(7L, 3L, 0)).isGreaterThanOrEqualTo(0);
    }

    @Test
    void shouldCarryMetadataOnUpsert() {
        long videoId = 7L;
        when(segmentRepository.findLatestSuccessfulByVideoId(videoId)).thenReturn(segments(20));
        VideoRagIndexEntity index = indexEntity(videoId, 3L, RagIndexStatus.NOT_BUILT.name());
        when(indexRepository.findByVideoId(videoId)).thenReturn(index);
        when(indexRepository.claimBuilding(anyLong(), any(LocalDateTime.class))).thenReturn(1);
        when(embeddingProvider.embedDocuments(any())).thenReturn(java.util.stream.IntStream.range(0, 20).mapToObj(i -> new float[384]).toList());
        when(indexRepository.markReady(anyLong(), anyInt(), any(LocalDateTime.class))).thenReturn(1);
        when(indexRepository.selectById(anyLong())).thenReturn(index);

        service.buildIndex(videoId, 1L);

        ArgumentCaptor<List<VectorPoint>> pointsCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).upsertPoints(eq(1L), eq(7L), eq(3L), pointsCaptor.capture());
        VectorPoint point = pointsCaptor.getValue().getFirst();
        assertThat(point.chunkIndex()).isZero();
        assertThat(point.startMs()).isZero();
        assertThat(point.sourceSegmentIndexes()).isNotEmpty();
    }

    @Test
    void shouldMarkFailedWhenBuildFails() {
        long videoId = 7L;
        when(segmentRepository.findLatestSuccessfulByVideoId(videoId)).thenReturn(segments(20));
        VideoRagIndexEntity index = indexEntity(videoId, 3L, RagIndexStatus.NOT_BUILT.name());
        VideoRagIndexEntity failed = indexEntity(videoId, 3L, RagIndexStatus.FAILED.name());
        when(indexRepository.findByVideoId(videoId)).thenReturn(index);
        when(indexRepository.claimBuilding(anyLong(), any(LocalDateTime.class))).thenReturn(1);
        when(embeddingProvider.embedDocuments(any())).thenThrow(new IllegalStateException("embedding down"));
        when(indexRepository.markFailed(anyLong(), anyString(), anyString(), any(LocalDateTime.class))).thenReturn(1);
        when(indexRepository.selectById(99L)).thenReturn(failed);

        VideoRagIndexEntity result = service.buildIndex(videoId, 1L);

        assertThat(result.getStatus()).isEqualTo(RagIndexStatus.FAILED.name());
        verify(vectorStore, never()).upsertPoints(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void shouldRebuildWithoutDuplicates() {
        // READY index can be rebuilt: old vectors deleted first, then upsert.
        long videoId = 7L;
        when(segmentRepository.findLatestSuccessfulByVideoId(videoId)).thenReturn(segments(20));
        VideoRagIndexEntity index = indexEntity(videoId, 3L, RagIndexStatus.READY.name());
        when(indexRepository.findByVideoId(videoId)).thenReturn(index);
        when(indexRepository.claimBuilding(anyLong(), any(LocalDateTime.class))).thenReturn(1);
        when(embeddingProvider.embedDocuments(any())).thenReturn(java.util.stream.IntStream.range(0, 20).mapToObj(i -> new float[384]).toList());
        when(indexRepository.markReady(anyLong(), anyInt(), any(LocalDateTime.class))).thenReturn(1);
        when(indexRepository.selectById(anyLong())).thenReturn(index);

        service.buildIndex(videoId, 1L);

        verify(vectorStore).deleteByVideoStrict(1L, videoId);
        verify(vectorStore).upsertPoints(eq(1L), eq(7L), eq(3L), any());
    }

    @Test
    void shouldMarkFailedAndStopWhenStrictRebuildDeleteFails() {
        long videoId = 7L;
        when(segmentRepository.findLatestSuccessfulByVideoId(videoId)).thenReturn(segments(20));
        VideoRagIndexEntity index = indexEntity(videoId, 3L, RagIndexStatus.READY.name());
        VideoRagIndexEntity failed = indexEntity(videoId, 3L, RagIndexStatus.FAILED.name());
        when(indexRepository.findByVideoId(videoId)).thenReturn(index);
        when(indexRepository.claimBuilding(anyLong(), any(LocalDateTime.class))).thenReturn(1);
        when(embeddingProvider.embedDocuments(any())).thenReturn(
            java.util.stream.IntStream.range(0, 20).mapToObj(i -> new float[384]).toList());
        org.mockito.Mockito.doThrow(new VideoAgentException(
            ErrorCode.RAG_INDEX_BUILD_FAILED, "delete failed"))
            .when(vectorStore).deleteByVideoStrict(1L, videoId);
        when(indexRepository.markFailed(anyLong(), anyString(), anyString(), any(LocalDateTime.class)))
            .thenReturn(1);
        when(indexRepository.selectById(99L)).thenReturn(failed);

        VideoRagIndexEntity result = service.buildIndex(videoId, 1L);

        assertThat(result.getStatus()).isEqualTo(RagIndexStatus.FAILED.name());
        verify(vectorStore, never()).upsertPoints(anyLong(), anyLong(), anyLong(), any());
        verify(indexRepository, never()).markReady(anyLong(), anyInt(), any(LocalDateTime.class));
    }

    @Test
    void shouldRejectQaWhenIndexNotReady() {
        when(ownershipService.requireOwned(7L, 1L)).thenReturn(null);
        when(indexRepository.findByVideoId(7L)).thenReturn(null);

        assertThatThrownBy(() -> service.requireReady(7L, 1L))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.RAG_INDEX_NOT_READY));
    }

    @Test
    void shouldReusePreloadedTranscriptWhenReadingStatus() {
        List<VideoTranscriptSegmentEntity> preloaded = segments(2);
        when(indexRepository.findByVideoId(7L)).thenReturn(null);

        VideoRagIndexEntity result = service.getStatus(7L, 1L, preloaded);

        assertThat(result.getStatus()).isEqualTo(RagIndexStatus.NOT_REQUIRED.name());
        assertThat(result.getAnalysisTaskId()).isEqualTo(3L);
        verify(segmentRepository, never()).findLatestSuccessfulByVideoId(anyLong());
    }

    @Test
    void shouldCheckOwnershipBeforeLoadingTranscriptForStatus() {
        VideoAgentException failure = new VideoAgentException(ErrorCode.VIDEO_NOT_FOUND);
        org.mockito.Mockito.doThrow(failure).when(ownershipService).requireOwned(7L, 1L);

        assertThatThrownBy(() -> service.getStatus(7L, 1L)).isSameAs(failure);

        verify(segmentRepository, never()).findLatestSuccessfulByVideoId(anyLong());
        verify(indexRepository, never()).findByVideoId(anyLong());
    }

    private VideoRagIndexEntity indexEntity(long videoId, long taskId, String status) {
        VideoRagIndexEntity entity = new VideoRagIndexEntity();
        entity.setId(99L);
        entity.setVideoId(videoId);
        entity.setAnalysisTaskId(taskId);
        entity.setStatus(status);
        entity.setContextMode("RAG");
        entity.setTranscriptChars(5000);
        entity.setChunkCount(0);
        entity.setEmbeddingProvider("mock");
        entity.setEmbeddingDimension(384);
        return entity;
    }

    private List<VideoTranscriptSegmentEntity> segments(int count) {
        List<VideoTranscriptSegmentEntity> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            VideoTranscriptSegmentEntity entity = new VideoTranscriptSegmentEntity();
            entity.setSegmentIndex(i);
            entity.setTaskId(3L);
            entity.setStartMs(i * 400L);
            entity.setEndMs((i + 1) * 400L);
            entity.setText("segment " + i + " " + "word ".repeat(20));
            result.add(entity);
        }
        return result;
    }
}
