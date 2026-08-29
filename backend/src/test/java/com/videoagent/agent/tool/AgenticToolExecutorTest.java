package com.videoagent.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.agent.context.AgenticQaContext;
import com.videoagent.agent.config.AgentProperties;
import com.videoagent.agent.evidence.EvidenceItem;
import com.videoagent.agent.evidence.EvidenceSourceType;
import com.videoagent.agent.plan.RetrievalAction;
import com.videoagent.agent.plan.RetrievalTool;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.rag.context.QaContextMode;
import com.videoagent.rag.retrieval.RetrievedChunk;
import com.videoagent.rag.retrieval.TranscriptRetriever;
import com.videoagent.rag.service.RagIndexService;
import com.videoagent.telemetry.QaTelemetryContext;
import com.videoagent.telemetry.QaTelemetryRoute;
import com.videoagent.summary.dto.VideoChapterResponse;
import com.videoagent.summary.dto.VideoKeyPointResponse;
import com.videoagent.summary.dto.VideoSummaryResponse;
import com.videoagent.summary.service.VideoSummaryService;
import com.videoagent.transcript.entity.VideoTranscriptSegmentEntity;
import com.videoagent.transcript.repository.VideoTranscriptSegmentRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

class AgenticToolExecutorTest {

    private final VideoTranscriptSegmentRepository segmentRepository = mock(VideoTranscriptSegmentRepository.class);
    private final VideoSummaryService summaryService = mock(VideoSummaryService.class);
    private final TranscriptRetriever transcriptRetriever = mock(TranscriptRetriever.class);
    private final RagIndexService ragIndexService = mock(RagIndexService.class);
    private final AgentProperties properties =
        new AgentProperties("mock", 4, 15_000L, 120_000L, 12, 12_000, "");
    private AgenticToolExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new AgenticToolExecutor(
            segmentRepository, summaryService, transcriptRetriever, ragIndexService, properties);
    }

    private AgenticQaContext ragContext() {
        return new AgenticQaContext(1L, 7L, 3L, QaContextMode.RAG, true, true, "READY");
    }

    private AgenticQaContext directContext() {
        return new AgenticQaContext(1L, 7L, 3L, QaContextMode.DIRECT_CONTEXT, true, true, "NOT_REQUIRED");
    }

    private List<VideoTranscriptSegmentEntity> segments() {
        return List.of(
            segment(0, 0, 2000, "Redis 缓存进度"),
            segment(1, 2000, 4000, "RocketMQ 异步消息"),
            segment(2, 4000, 6000, "MySQL 保存状态")
        );
    }

    private VideoTranscriptSegmentEntity segment(int index, long start, long end, String text) {
        VideoTranscriptSegmentEntity s = new VideoTranscriptSegmentEntity();
        s.setSegmentIndex(index);
        s.setStartMs(start);
        s.setEndMs(end);
        s.setText(text);
        return s;
    }

    // ---- Summary tool ----

    @Test
    void shouldReadPersistedSummary() {
        when(segmentRepository.findLatestSuccessfulByVideoId(7L)).thenReturn(segments());
        when(summaryService.getSummary(7L, 1L)).thenReturn(java.util.Optional.of(
            new VideoSummaryResponse(3L, "视频概述", LocalDateTime.now(), LocalDateTime.now())));
        when(summaryService.getChapters(7L, 1L)).thenReturn(List.of(
            new VideoChapterResponse(0, "章节", "章节摘要", 0, 6000)));
        when(summaryService.getKeyPoints(7L, 1L)).thenReturn(List.of(
            new VideoKeyPointResponse(0, "要点内容", 0, 2000)));

        List<EvidenceItem> evidence = executor.execute(ragContext(),
            List.of(RetrievalAction.summary()));

        assertThat(evidence).hasSize(1);
        EvidenceItem item = evidence.getFirst();
        assertThat(item.sourceType()).isEqualTo(EvidenceSourceType.SUMMARY);
        assertThat(item.text()).contains("视频概述").contains("章节").contains("要点内容");
        assertThat(item.evidenceId()).isEqualTo("E1");
        verify(summaryService, never()).replaceTaskResult(any(), any(), any());
    }

    @Test
    void shouldReturnEmptyWhenSummaryMissing() {
        when(segmentRepository.findLatestSuccessfulByVideoId(7L)).thenReturn(segments());
        when(summaryService.getSummary(7L, 1L)).thenReturn(java.util.Optional.empty());

        List<EvidenceItem> evidence = executor.execute(ragContext(),
            List.of(RetrievalAction.summary()));

        assertThat(evidence).isEmpty();
    }

    @Test
    void shouldPropagateSummaryLookupFailureInsteadOfReturningEmpty() {
        IllegalStateException failure = new IllegalStateException("database down");
        when(summaryService.getSummary(7L, 1L)).thenThrow(failure);

        assertThatThrownBy(() -> executor.execute(ragContext(), List.of(RetrievalAction.summary())))
            .isSameAs(failure);
    }

    @Test
    void shouldPropagateChapterMappingFailureInsteadOfReturningPartialSummary() {
        when(summaryService.getSummary(7L, 1L)).thenReturn(java.util.Optional.of(
            new VideoSummaryResponse(3L, "视频概述", LocalDateTime.now(), LocalDateTime.now())));
        IllegalStateException failure = new IllegalStateException("mapping failed");
        when(summaryService.getChapters(7L, 1L)).thenThrow(failure);

        assertThatThrownBy(() -> executor.execute(ragContext(), List.of(RetrievalAction.summary())))
            .isSameAs(failure);
    }

    // ---- Time tool ----

    @Test
    void shouldReturnSegmentsInTimeWindow() {
        when(segmentRepository.findOverlappingByTaskIdAndVideoId(3L, 7L, 2500L, 3500L))
            .thenReturn(List.of(segments().get(1)));

        // Window [2500, 3500] covers only segment 1 [2000, 4000].
        List<EvidenceItem> evidence = executor.execute(ragContext(),
            List.of(RetrievalAction.byTime(3000, 500)));

        assertThat(evidence).hasSize(1);
        assertThat(evidence.getFirst().sourceType()).isEqualTo(EvidenceSourceType.TRANSCRIPT_TIME);
        assertThat(evidence.getFirst().segmentIndex()).isEqualTo(1);
        assertThat(evidence.getFirst().startMs()).isEqualTo(2000L);
        assertThat(evidence.getFirst().endMs()).isEqualTo(4000L);
        verify(transcriptRetriever, never()).retrieve(anyLong(), anyLong(), anyString());
    }

    @Test
    void shouldReturnEmptyForOutOfRangeTime() {
        when(segmentRepository.findOverlappingByTaskIdAndVideoId(
            3L, 7L, 8_999_000L, 9_001_000L)).thenReturn(List.of());

        List<EvidenceItem> evidence = executor.execute(ragContext(),
            List.of(RetrievalAction.byTime(9_000_000, 1000)));

        assertThat(evidence).isEmpty();
        verify(transcriptRetriever, never()).retrieve(anyLong(), anyLong(), anyString());
    }

    @Test
    void shouldPreserveSegmentOrderingAndTimestamps() {
        when(segmentRepository.findOverlappingByTaskIdAndVideoId(3L, 7L, 0L, 7000L))
            .thenReturn(segments());

        List<EvidenceItem> evidence = executor.execute(ragContext(),
            List.of(RetrievalAction.byTime(3000, 4000)));

        // Window [-1000, 7000] covers all three segments in order.
        assertThat(evidence).hasSize(3);
        assertThat(evidence).extracting(EvidenceItem::segmentIndex).containsExactly(0, 1, 2);
        assertThat(evidence).extracting(EvidenceItem::startMs).containsExactly(0L, 2000L, 4000L);
        assertThat(evidence).extracting(EvidenceItem::endMs).containsExactly(2000L, 4000L, 6000L);
        verify(transcriptRetriever, never()).retrieve(anyLong(), anyLong(), anyString());
    }

    @Test
    void shouldUseConfiguredDefaultWindowAndDatabaseRangeQuery() {
        when(segmentRepository.findOverlappingByTaskIdAndVideoId(
            3L, 7L, 185_000L, 215_000L)).thenReturn(List.of());

        executor.execute(ragContext(), List.of(new RetrievalAction(
            RetrievalTool.GET_TRANSCRIPT_BY_TIME, null, 200_000L, null)));

        verify(segmentRepository).findOverlappingByTaskIdAndVideoId(
            3L, 7L, 185_000L, 215_000L);
        verify(segmentRepository, never()).findLatestSuccessfulByVideoId(7L);
    }

    // ---- Search tool ----

    @Test
    void shouldUseFullTranscriptInDirectModeWithoutEmbedding() {
        when(segmentRepository.findLatestSuccessfulByVideoId(7L)).thenReturn(segments());

        List<EvidenceItem> evidence = executor.execute(directContext(),
            List.of(RetrievalAction.search("Redis")));

        assertThat(evidence).hasSize(3);
        assertThat(evidence).allMatch(e -> e.sourceType() == EvidenceSourceType.TRANSCRIPT_SEARCH);
        assertThat(evidence).extracting(EvidenceItem::segmentIndex).containsExactly(0, 1, 2);
        verify(transcriptRetriever, never()).retrieve(anyLong(), anyLong(), anyString());
    }

    @Test
    void shouldUseTranscriptRetrieverInRagReadyMode() {
        when(transcriptRetriever.retrieve(1L, 7L, "Redis 作用")).thenReturn(List.of(
            new RetrievedChunk(0, "Redis 缓存进度", 0, 2000, List.of(0), 0.9f)
        ));

        List<EvidenceItem> evidence = executor.execute(ragContext(),
            List.of(RetrievalAction.search("Redis 作用")));

        assertThat(evidence).hasSize(1);
        assertThat(evidence.getFirst().sourceType()).isEqualTo(EvidenceSourceType.TRANSCRIPT_SEARCH);
        assertThat(evidence.getFirst().chunkIndex()).isEqualTo(0);
        assertThat(evidence.getFirst().score()).isEqualTo(0.9f);
        verify(ragIndexService).requireReady(7L, 1L);
        verify(transcriptRetriever).retrieve(1L, 7L, "Redis 作用");
        verify(segmentRepository, never()).findLatestSuccessfulByVideoId(7L);
    }

    @Test
    void shouldPropagateAgenticTelemetryToRagSearchOnly() {
        QaTelemetryContext telemetryContext = new QaTelemetryContext("request-1", 7L, 3L);
        when(transcriptRetriever.retrieve(
            1L, 7L, "Redis 作用", telemetryContext, QaTelemetryRoute.AGENTIC
        )).thenReturn(List.of());

        executor.execute(ragContext(), List.of(RetrievalAction.search("Redis 作用")), telemetryContext);

        verify(transcriptRetriever).retrieve(
            1L, 7L, "Redis 作用", telemetryContext, QaTelemetryRoute.AGENTIC
        );
    }

    @Test
    void shouldKeepDirectSearchLocalWhenTelemetryIsPresent() {
        QaTelemetryContext telemetryContext = new QaTelemetryContext("request-1", 7L, 3L);
        when(segmentRepository.findLatestSuccessfulByVideoId(7L)).thenReturn(segments());

        executor.execute(directContext(), List.of(RetrievalAction.search("Redis")), telemetryContext);

        verify(transcriptRetriever, never()).retrieve(
            anyLong(), anyLong(), anyString(), any(QaTelemetryContext.class), any(QaTelemetryRoute.class)
        );
    }

    @Test
    void shouldRejectSearchWhenReadySnapshotBecomesBuilding() {
        when(ragIndexService.requireReady(7L, 1L))
            .thenThrow(new VideoAgentException(ErrorCode.RAG_INDEX_NOT_READY));

        assertThatThrownBy(() -> executor.execute(ragContext(),
            List.of(RetrievalAction.search("Redis"))))
            .isInstanceOfSatisfying(VideoAgentException.class, e ->
                assertThat(e.errorCode()).isEqualTo(ErrorCode.RAG_INDEX_NOT_READY));
        verify(ragIndexService).requireReady(7L, 1L);
        verify(transcriptRetriever, never()).retrieve(anyLong(), anyLong(), anyString());
    }

    @Test
    void shouldKeepUserIdAndVideoIdBoundInRetrieval() {
        when(transcriptRetriever.retrieve(1L, 7L, "query")).thenReturn(List.of());

        executor.execute(ragContext(), List.of(RetrievalAction.search("query")));

        // The retriever must be called with the server-bound user/video only.
        verify(transcriptRetriever).retrieve(1L, 7L, "query");
    }

    @Test
    void shouldDeduplicateIdenticalSearchActionsBeforeExternalCalls() {
        when(transcriptRetriever.retrieve(1L, 7L, "query")).thenReturn(List.of());

        executor.execute(ragContext(), List.of(
            RetrievalAction.search("query"),
            RetrievalAction.search("query")
        ));

        verify(ragIndexService, times(1)).requireReady(7L, 1L);
        verify(transcriptRetriever, times(1)).retrieve(1L, 7L, "query");
    }

    @Test
    void shouldKeepTelemetryAwareDistinctSearchesSeparate() {
        QaTelemetryContext telemetryContext = new QaTelemetryContext("request-1", 7L, 3L);
        when(transcriptRetriever.retrieve(
            eq(1L), eq(7L), anyString(), eq(telemetryContext), eq(QaTelemetryRoute.AGENTIC)
        )).thenReturn(List.of());

        executor.execute(ragContext(), List.of(
            RetrievalAction.search("query-a"),
            RetrievalAction.search("query-b")
        ), telemetryContext);

        verify(transcriptRetriever).retrieve(1L, 7L, "query-a", telemetryContext, QaTelemetryRoute.AGENTIC);
        verify(transcriptRetriever).retrieve(1L, 7L, "query-b", telemetryContext, QaTelemetryRoute.AGENTIC);
    }

    @Test
    void shouldNotDeduplicateDifferentSearchQueries() {
        when(transcriptRetriever.retrieve(anyLong(), anyLong(), anyString())).thenReturn(List.of());

        executor.execute(ragContext(), List.of(
            RetrievalAction.search("query-a"),
            RetrievalAction.search("query-b")
        ));

        verify(ragIndexService, times(2)).requireReady(7L, 1L);
        verify(transcriptRetriever).retrieve(1L, 7L, "query-a");
        verify(transcriptRetriever).retrieve(1L, 7L, "query-b");
    }
}
