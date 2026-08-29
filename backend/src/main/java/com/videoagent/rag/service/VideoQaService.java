package com.videoagent.rag.service;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.rag.context.ContextStrategyResolver;
import com.videoagent.rag.context.QaContextMode;
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
import com.videoagent.telemetry.QaTelemetryContext;
import com.videoagent.telemetry.QaTelemetryRoute;
import com.videoagent.video.service.VideoOwnershipService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates grounded QA across both modes:
 *
 *  - DIRECT_CONTEXT: the full transcript segments become the LLM context; the
 *    model cites segment indexes and the backend resolves them to real
 *    timestamps from the database.
 *  - RAG: the index must be READY; the question is embedded, top-K chunks are
 *    retrieved with userId+videoId filtering, the model cites chunk indexes and
 *    the backend resolves them to real chunk metadata.
 *
 * Every citation is validated against the context actually provided to the
 * model. Fabricated indexes are dropped and never become timestamps.
 */
@Service
public class VideoQaService {

    private static final Logger log = LoggerFactory.getLogger(VideoQaService.class);
    private static final String INSUFFICIENT_EVIDENCE = "根据当前视频内容无法确定。";

    private final VideoOwnershipService ownershipService;
    private final VideoTranscriptSegmentRepository segmentRepository;
    private final ContextStrategyResolver strategyResolver;
    private final VideoQaProvider qaProvider;
    private final TranscriptRetriever retriever;
    private final RagIndexService ragIndexService;

    public VideoQaService(
        VideoOwnershipService ownershipService,
        VideoTranscriptSegmentRepository segmentRepository,
        ContextStrategyResolver strategyResolver,
        VideoQaProvider qaProvider,
        TranscriptRetriever retriever,
        RagIndexService ragIndexService
    ) {
        this.ownershipService = ownershipService;
        this.segmentRepository = segmentRepository;
        this.strategyResolver = strategyResolver;
        this.qaProvider = qaProvider;
        this.retriever = retriever;
        this.ragIndexService = ragIndexService;
    }

    public QaResponse answer(long videoId, long userId, String question) {
        return answerInternal(videoId, userId, question, QaTelemetryContext.newRequest(videoId), null);
    }

    /**
     * Application-internal entry point for a future Agentic fallback. The
     * caller owns the request correlation and this method never replaces it.
     */
    public QaResponse answerWithContext(
        long videoId,
        long userId,
        String question,
        QaTelemetryContext telemetryContext,
        QaTelemetryRoute telemetryRoute
    ) {
        return answerInternal(
            videoId,
            userId,
            question,
            Objects.requireNonNull(telemetryContext, "telemetryContext"),
            Objects.requireNonNull(telemetryRoute, "telemetryRoute")
        );
    }

    private QaResponse answerInternal(
        long videoId,
        long userId,
        String question,
        QaTelemetryContext telemetryContext,
        QaTelemetryRoute routeOverride
    ) {
        long startedAtNanos = System.nanoTime();
        QaTelemetryContext effectiveContext = telemetryContext;
        QaTelemetryRoute effectiveRoute = routeOverride;
        String outcome = "failure";
        String errorCategory = ErrorCode.INTERNAL_ERROR.name();
        try {
            ownershipService.requireOwned(videoId, userId);
            List<VideoTranscriptSegmentEntity> segments =
                strategyResolver.requireNonEmpty(segmentRepository.findLatestSuccessfulByVideoId(videoId));
            effectiveContext = effectiveContext.withAnalysisTaskId(segments.getFirst().getTaskId());
            QaContextMode mode = strategyResolver.resolveMode(segments);

            if (mode == QaContextMode.DIRECT_CONTEXT) {
                effectiveRoute = routeOverride == null ? QaTelemetryRoute.BASIC_DIRECT : routeOverride;
                QaResponse response = answerDirect(
                    videoId, userId, question, segments, effectiveContext, effectiveRoute
                );
                outcome = "success";
                errorCategory = "none";
                return response;
            }

            effectiveRoute = routeOverride == null ? QaTelemetryRoute.BASIC_RAG : routeOverride;
            VideoRagIndexEntity index = ragIndexService.requireReady(videoId, userId);
            if (index.getAnalysisTaskId() != null) {
                effectiveContext = effectiveContext.withAnalysisTaskId(index.getAnalysisTaskId());
            }
            QaResponse response = answerRag(
                videoId, userId, question, index, effectiveContext, effectiveRoute
            );
            outcome = "success";
            errorCategory = "none";
            return response;
        } catch (VideoAgentException exception) {
            errorCategory = exception.errorCode().name();
            throw exception;
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
            String route = effectiveRoute == null ? "none" : effectiveRoute.value();
            boolean fallback = effectiveRoute == QaTelemetryRoute.AGENTIC_FALLBACK_BASIC;
            log.info("event=ai.qa_request requestId={} videoId={} analysisTaskId={} route={} totalDurationMs={} outcome={} errorCategory={} fallback={}",
                effectiveContext.requestId(), effectiveContext.videoId(), effectiveContext.analysisTaskId(), route,
                durationMs, outcome, errorCategory, fallback);
        }
    }

    private QaResponse answerDirect(
        long videoId,
        long userId,
        String question,
        List<VideoTranscriptSegmentEntity> segments,
        QaTelemetryContext telemetryContext,
        QaTelemetryRoute telemetryRoute
    ) {
        List<VideoQaRequest.ContextItem> context = new ArrayList<>(segments.size());
        for (VideoTranscriptSegmentEntity segment : segments) {
            int index = segment.getSegmentIndex() == null ? context.size() : segment.getSegmentIndex();
            context.add(new VideoQaRequest.ContextItem(
                index,
                segment.getText() == null ? "" : segment.getText(),
                segment.getStartMs() == null ? 0L : segment.getStartMs(),
                segment.getEndMs() == null ? 0L : segment.getEndMs()
            ));
        }
        VideoQaResult result = qaProvider.answer(
            new VideoQaRequest(videoId, question, context), telemetryContext, telemetryRoute
        );

        Map<Integer, VideoQaRequest.ContextItem> byIndex = new LinkedHashMap<>();
        for (VideoQaRequest.ContextItem item : context) {
            byIndex.put(item.index(), item);
        }
        List<QaCitation> citations = resolveCitations(result.citationIndexes(), byIndex);
        String answer = citations.isEmpty() ? INSUFFICIENT_EVIDENCE : result.answer();

        log.info("[userId={}][videoId={}][contextMode=DIRECT_CONTEXT][transcriptChars={}][segmentCount={}] qa answered",
            userId, videoId, strategyResolver.transcriptChars(segments), segments.size());
        return new QaResponse(QaContextMode.DIRECT_CONTEXT.name(), answer, citations);
    }

    private QaResponse answerRag(
        long videoId,
        long userId,
        String question,
        VideoRagIndexEntity index,
        QaTelemetryContext telemetryContext,
        QaTelemetryRoute telemetryRoute
    ) {
        List<RetrievedChunk> chunks = retriever.retrieve(
            userId, videoId, question, telemetryContext, telemetryRoute
        );
        if (chunks.isEmpty()) {
            log.info("[userId={}][videoId={}][contextMode=RAG] no evidence above minimum score", userId, videoId);
            return new QaResponse(QaContextMode.RAG.name(), INSUFFICIENT_EVIDENCE, List.of());
        }

        List<VideoQaRequest.ContextItem> context = new ArrayList<>(chunks.size());
        for (RetrievedChunk chunk : chunks) {
            context.add(new VideoQaRequest.ContextItem(
                chunk.chunkIndex(),
                chunk.text(),
                chunk.startMs(),
                chunk.endMs()
            ));
        }
        VideoQaResult result = qaProvider.answer(
            new VideoQaRequest(videoId, question, context), telemetryContext, telemetryRoute
        );

        Map<Integer, VideoQaRequest.ContextItem> byIndex = new LinkedHashMap<>();
        for (VideoQaRequest.ContextItem item : context) {
            byIndex.put(item.index(), item);
        }
        List<QaCitation> citations = resolveCitations(result.citationIndexes(), byIndex);
        String answer = citations.isEmpty() ? INSUFFICIENT_EVIDENCE : result.answer();

        List<Integer> retrievedIndexes = chunks.stream().map(RetrievedChunk::chunkIndex).toList();
        log.info("[userId={}][videoId={}][analysisTaskId={}][ragIndexId={}][contextMode=RAG][retrievalTopK={}][retrievedChunkIndexes={}] qa answered",
            userId, videoId, index.getAnalysisTaskId(), index.getId(),
            chunks.size(), retrievedIndexes);
        return new QaResponse(QaContextMode.RAG.name(), answer, citations);
    }

    /**
     * Resolves LLM-provided citation indexes only against the context that was
     * actually given to the model. Indexes that do not match are dropped.
     */
    private List<QaCitation> resolveCitations(
        List<Integer> citationIndexes,
        Map<Integer, VideoQaRequest.ContextItem> byIndex
    ) {
        Set<Integer> dedup = new LinkedHashSet<>();
        List<QaCitation> citations = new ArrayList<>();
        if (citationIndexes == null) {
            return citations;
        }
        for (Integer index : citationIndexes) {
            if (index == null || !dedup.add(index)) {
                continue;
            }
            VideoQaRequest.ContextItem item = byIndex.get(index);
            if (item != null) {
                citations.add(new QaCitation(item.startMs(), item.endMs(), item.text()));
            }
        }
        return citations;
    }
}
