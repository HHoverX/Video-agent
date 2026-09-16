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
import com.videoagent.rag.dto.QaResponse;
import com.videoagent.rag.entity.RagIndexStatus;
import com.videoagent.rag.entity.VideoRagIndexEntity;
import com.videoagent.rag.qa.VideoQaProvider;
import com.videoagent.rag.qa.VideoQaRequest;
import com.videoagent.rag.qa.VideoQaResult;
import com.videoagent.rag.retrieval.RetrievedChunk;
import com.videoagent.rag.retrieval.TranscriptRetriever;
import com.videoagent.telemetry.QaTelemetryContext;
import com.videoagent.telemetry.QaTelemetryRoute;
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
    private VideoQaService service;

    @BeforeEach
    void setUp() {
        service = new VideoQaService(
            ownershipService, segmentRepository, qaProvider, retriever, ragIndexService);
    }

    @Test
    void shouldRejectEmptyTranscript() {
        when(segmentRepository.findLatestSuccessfulByVideoId(7L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.answer(7L, 1L, "问题？"))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.TRANSCRIPTION_FAILED));
        verify(ragIndexService, never()).requireReady(anyLong(), anyLong());
    }

    @Test
    void shouldRejectQaWhenIndexNotReady() {
        when(segmentRepository.findLatestSuccessfulByVideoId(7L)).thenReturn(segments());
        when(ragIndexService.requireReady(7L, 1L))
            .thenThrow(new VideoAgentException(ErrorCode.RAG_INDEX_NOT_READY));

        assertThatThrownBy(() -> service.answer(7L, 1L, "问题？"))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.RAG_INDEX_NOT_READY));
        verify(retriever, never()).retrieve(
            anyLong(), anyLong(), any(), any(QaTelemetryContext.class), any(QaTelemetryRoute.class));
    }

    @Test
    void shouldRetrieveAndAnswerWhenIndexReady() {
        when(segmentRepository.findLatestSuccessfulByVideoId(7L)).thenReturn(segments());
        when(ragIndexService.requireReady(7L, 1L)).thenReturn(readyIndex());
        when(retriever.retrieve(
            eq(1L), eq(7L), eq("问题？"), any(QaTelemetryContext.class), eq(QaTelemetryRoute.BASIC_RAG)
        )).thenReturn(List.of(
            new RetrievedChunk(2, "chunk-two", 4000, 6000, List.of(2), 0.9f),
            new RetrievedChunk(0, "chunk-zero", 0, 2000, List.of(0), 0.7f)
        ));
        when(qaProvider.answer(
            any(VideoQaRequest.class), any(QaTelemetryContext.class), eq(QaTelemetryRoute.BASIC_RAG)
        )).thenReturn(new VideoQaResult("rag-answer", List.of(2)));

        QaResponse response = service.answer(7L, 1L, "问题？");

        assertThat(response.answer()).isEqualTo("rag-answer");
        assertThat(response.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.text()).isEqualTo("chunk-two");
            assertThat(citation.startMs()).isEqualTo(4000L);
        });
    }

    @Test
    void shouldRejectCitationOutsideRetrievedChunks() {
        when(segmentRepository.findLatestSuccessfulByVideoId(7L)).thenReturn(segments());
        when(ragIndexService.requireReady(7L, 1L)).thenReturn(readyIndex());
        when(retriever.retrieve(
            eq(1L), eq(7L), eq("问题？"), any(QaTelemetryContext.class), eq(QaTelemetryRoute.BASIC_RAG)
        )).thenReturn(List.of(new RetrievedChunk(1, "chunk-one", 2000, 4000, List.of(1), 0.8f)));
        when(qaProvider.answer(
            any(VideoQaRequest.class), any(QaTelemetryContext.class), eq(QaTelemetryRoute.BASIC_RAG)
        )).thenReturn(new VideoQaResult("answer", List.of(5)));

        QaResponse response = service.answer(7L, 1L, "问题？");

        assertThat(response.answer()).isEqualTo("根据当前视频内容无法确定。");
        assertThat(response.citations()).isEmpty();
    }

    @Test
    void shouldNotCallProviderWhenRetrievalReturnsNoEvidence() {
        when(segmentRepository.findLatestSuccessfulByVideoId(7L)).thenReturn(segments());
        when(ragIndexService.requireReady(7L, 1L)).thenReturn(readyIndex());
        when(retriever.retrieve(
            anyLong(), anyLong(), any(), any(QaTelemetryContext.class), eq(QaTelemetryRoute.BASIC_RAG)
        )).thenReturn(List.of());

        QaResponse response = service.answer(7L, 1L, "问题？");

        assertThat(response.answer()).isEqualTo("根据当前视频内容无法确定。");
        assertThat(response.citations()).isEmpty();
        verify(qaProvider, never()).answer(
            any(VideoQaRequest.class), any(QaTelemetryContext.class), any(QaTelemetryRoute.class));
    }

    @Test
    void shouldShareRequestAndReadyTaskContextAcrossRetrievalAndAnswer() {
        when(segmentRepository.findLatestSuccessfulByVideoId(7L)).thenReturn(segments());
        when(ragIndexService.requireReady(7L, 1L)).thenReturn(readyIndex());
        when(retriever.retrieve(
            eq(1L), eq(7L), eq("问题？"), any(QaTelemetryContext.class), eq(QaTelemetryRoute.BASIC_RAG)
        )).thenReturn(List.of(new RetrievedChunk(0, "chunk", 0, 1000, List.of(0), 0.9f)));
        when(qaProvider.answer(
            any(VideoQaRequest.class), any(QaTelemetryContext.class), eq(QaTelemetryRoute.BASIC_RAG)
        )).thenReturn(new VideoQaResult("answer", List.of(0)));

        service.answer(7L, 1L, "问题？");

        var retrieverContext = org.mockito.ArgumentCaptor.forClass(QaTelemetryContext.class);
        var providerContext = org.mockito.ArgumentCaptor.forClass(QaTelemetryContext.class);
        verify(retriever).retrieve(
            eq(1L), eq(7L), eq("问题？"), retrieverContext.capture(), eq(QaTelemetryRoute.BASIC_RAG));
        verify(qaProvider).answer(
            any(VideoQaRequest.class), providerContext.capture(), eq(QaTelemetryRoute.BASIC_RAG));
        assertThat(retrieverContext.getValue().requestId())
            .isNotBlank()
            .isEqualTo(providerContext.getValue().requestId());
        assertThat(retrieverContext.getValue().analysisTaskId()).isEqualTo(33L);
    }

    private VideoRagIndexEntity readyIndex() {
        VideoRagIndexEntity index = new VideoRagIndexEntity();
        index.setId(1L);
        index.setVideoId(7L);
        index.setAnalysisTaskId(33L);
        index.setStatus(RagIndexStatus.READY.name());
        return index;
    }

    private List<VideoTranscriptSegmentEntity> segments() {
        VideoTranscriptSegmentEntity segment = new VideoTranscriptSegmentEntity();
        segment.setTaskId(11L);
        segment.setSegmentIndex(0);
        segment.setStartMs(0L);
        segment.setEndMs(1000L);
        segment.setText("first");
        return List.of(segment);
    }
}
