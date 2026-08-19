package com.videoagent.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.rag.config.RagProperties;
import com.videoagent.rag.context.ContextStrategyResolver;
import com.videoagent.rag.dto.QaCitation;
import com.videoagent.rag.dto.QaResponse;
import com.videoagent.rag.entity.RagIndexStatus;
import com.videoagent.rag.entity.VideoRagIndexEntity;
import com.videoagent.rag.qa.VideoQaProvider;
import com.videoagent.rag.qa.VideoQaRequest;
import com.videoagent.rag.qa.VideoQaResult;
import com.videoagent.rag.retrieval.RetrievedChunk;
import com.videoagent.rag.retrieval.TranscriptRetriever;
import com.videoagent.transcript.entity.VideoTranscriptSegmentEntity;
import com.videoagent.transcript.repository.VideoTranscriptSegmentRepository;
import com.videoagent.video.service.VideoOwnershipService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

class VideoQaServiceTest {

    private final VideoOwnershipService ownershipService = mock(VideoOwnershipService.class);
    private final VideoTranscriptSegmentRepository segmentRepository = mock(VideoTranscriptSegmentRepository.class);
    private final VideoQaProvider qaProvider = mock(VideoQaProvider.class);
    private final TranscriptRetriever retriever = mock(TranscriptRetriever.class);
    private final RagIndexService ragIndexService = mock(RagIndexService.class);
    private final RagProperties properties = new RagProperties(100, 200, 1, 5, 0.0f);
    private VideoQaService service;

    @BeforeEach
    void setUp() {
        service = new VideoQaService(
            ownershipService,
            segmentRepository,
            new ContextStrategyResolver(properties),
            qaProvider,
            retriever,
            ragIndexService
        );
    }

    // ---------- DIRECT_CONTEXT ----------

    @Test
    void shouldAnswerDirectWithoutEmbeddingOrRetrieval() {
        when(segmentRepository.findLatestSuccessfulByVideoId(7L)).thenReturn(segments(2));
        when(qaProvider.answer(any(VideoQaRequest.class))).thenReturn(new VideoQaResult("answer", List.of(0)));

        QaResponse response = service.answer(7L, 1L, "问题？");

        assertThat(response.mode()).isEqualTo("DIRECT_CONTEXT");
        assertThat(response.answer()).isEqualTo("answer");
        assertThat(response.citations()).hasSize(1);
        QaCitation citation = response.citations().getFirst();
        assertThat(citation.startMs()).isZero();
        assertThat(citation.endMs()).isEqualTo(1000L);
        assertThat(citation.text()).isEqualTo("first");
        verify(ownershipService).requireOwned(7L, 1L);
        verify(retriever, never()).retrieve(anyLong(), anyLong(), any());
        verify(ragIndexService, never()).requireReady(anyLong(), anyLong());
    }

    @Test
    void shouldPassFullTranscriptInSegmentOrderToProvider() {
        when(segmentRepository.findLatestSuccessfulByVideoId(7L)).thenReturn(segments(3));
        when(qaProvider.answer(any(VideoQaRequest.class))).thenReturn(new VideoQaResult("ok", List.of(2)));

        service.answer(7L, 1L, "问题？");

        var captor = org.mockito.ArgumentCaptor.forClass(VideoQaRequest.class);
        verify(qaProvider).answer(captor.capture());
        List<VideoQaRequest.ContextItem> context = captor.getValue().context();
        assertThat(context).extracting(VideoQaRequest.ContextItem::index).containsExactly(0, 1, 2);
        assertThat(context).extracting(VideoQaRequest.ContextItem::text)
            .containsExactly("first", "second", "third");
    }

    @Test
    void shouldDropHallucinatedSegmentCitation() {
        when(segmentRepository.findLatestSuccessfulByVideoId(7L)).thenReturn(segments(2));
        // Model cites segment 99 which does not exist -> must be dropped.
        when(qaProvider.answer(any(VideoQaRequest.class))).thenReturn(new VideoQaResult("ok", List.of(0, 99)));

        QaResponse response = service.answer(7L, 1L, "问题？");

        assertThat(response.citations()).hasSize(1);
        assertThat(response.citations().getFirst().text()).isEqualTo("first");
    }

    @Test
    void shouldFallBackWhenContextInsufficient() {
        when(segmentRepository.findLatestSuccessfulByVideoId(7L)).thenReturn(segments(2));
        when(qaProvider.answer(any(VideoQaRequest.class)))
            .thenReturn(new VideoQaResult("根据当前视频内容无法确定。", List.of()));

        QaResponse response = service.answer(7L, 1L, "问题？");

        assertThat(response.answer()).isEqualTo("根据当前视频内容无法确定。");
        assertThat(response.citations()).isEmpty();
    }

    @Test
    void shouldRejectEmptyTranscript() {
        when(segmentRepository.findLatestSuccessfulByVideoId(7L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.answer(7L, 1L, "问题？"))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.TRANSCRIPTION_FAILED));
    }

    // ---------- RAG ----------

    @Test
    void shouldRejectQaWhenIndexNotReady() {
        when(segmentRepository.findLatestSuccessfulByVideoId(7L)).thenReturn(segments(20));
        when(ragIndexService.requireReady(7L, 1L)).thenThrow(new VideoAgentException(ErrorCode.RAG_INDEX_NOT_READY));

        assertThatThrownBy(() -> service.answer(7L, 1L, "问题？"))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.RAG_INDEX_NOT_READY));
    }

    @Test
    void shouldRetrieveAndAnswerInRagMode() {
        when(segmentRepository.findLatestSuccessfulByVideoId(7L)).thenReturn(segments(20));
        VideoRagIndexEntity index = new VideoRagIndexEntity();
        index.setId(1L);
        index.setVideoId(7L);
        index.setAnalysisTaskId(3L);
        index.setStatus(RagIndexStatus.READY.name());
        when(ragIndexService.requireReady(7L, 1L)).thenReturn(index);
        when(retriever.retrieve(eq(1L), eq(7L), eq("问题？"))).thenReturn(List.of(
            new RetrievedChunk(2, "chunk-two", 4000, 6000, List.of(2), 0.9f),
            new RetrievedChunk(0, "chunk-zero", 0, 2000, List.of(0), 0.7f)
        ));
        when(qaProvider.answer(any(VideoQaRequest.class))).thenReturn(new VideoQaResult("rag-answer", List.of(2)));

        QaResponse response = service.answer(7L, 1L, "问题？");

        assertThat(response.mode()).isEqualTo("RAG");
        assertThat(response.answer()).isEqualTo("rag-answer");
        assertThat(response.citations()).hasSize(1);
        assertThat(response.citations().getFirst().text()).isEqualTo("chunk-two");
        assertThat(response.citations().getFirst().startMs()).isEqualTo(4000L);
    }

    @Test
    void shouldRejectCitationOutsideRetrievedChunks() {
        when(segmentRepository.findLatestSuccessfulByVideoId(7L)).thenReturn(segments(20));
        VideoRagIndexEntity index = new VideoRagIndexEntity();
        index.setStatus(RagIndexStatus.READY.name());
        when(ragIndexService.requireReady(7L, 1L)).thenReturn(index);
        when(retriever.retrieve(eq(1L), eq(7L), eq("问题？"))).thenReturn(List.of(
            new RetrievedChunk(1, "chunk-one", 2000, 4000, List.of(1), 0.8f)
        ));
        // Model cites chunk 5 which was never retrieved -> dropped.
        when(qaProvider.answer(any(VideoQaRequest.class))).thenReturn(new VideoQaResult("answer", List.of(5)));

        QaResponse response = service.answer(7L, 1L, "问题？");

        assertThat(response.citations()).isEmpty();
    }

    @Test
    void shouldFallBackWhenNoRelevantContextInRag() {
        when(segmentRepository.findLatestSuccessfulByVideoId(7L)).thenReturn(segments(20));
        VideoRagIndexEntity index = new VideoRagIndexEntity();
        index.setStatus(RagIndexStatus.READY.name());
        when(ragIndexService.requireReady(7L, 1L)).thenReturn(index);
        when(retriever.retrieve(anyLong(), anyLong(), any())).thenReturn(List.of());
        when(qaProvider.answer(any(VideoQaRequest.class)))
            .thenReturn(new VideoQaResult("根据当前视频内容无法确定。", List.of()));

        QaResponse response = service.answer(7L, 1L, "问题？");

        assertThat(response.citations()).isEmpty();
        assertThat(response.answer()).isEqualTo("根据当前视频内容无法确定。");
    }

    private List<VideoTranscriptSegmentEntity> segments(int count) {
        List<VideoTranscriptSegmentEntity> result = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            VideoTranscriptSegmentEntity entity = new VideoTranscriptSegmentEntity();
            entity.setSegmentIndex(i);
            entity.setStartMs(i * 1000L);
            entity.setEndMs((i + 1) * 1000L);
            entity.setText(i == 0 ? "first" : i == 1 ? "second" : "third");
            result.add(entity);
        }
        return result;
    }
}
