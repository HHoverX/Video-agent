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
        ownershipService.requireOwned(videoId, userId);
        List<VideoTranscriptSegmentEntity> segments =
            strategyResolver.requireNonEmpty(segmentRepository.findLatestSuccessfulByVideoId(videoId));
        QaContextMode mode = strategyResolver.resolveMode(segments);

        if (mode == QaContextMode.DIRECT_CONTEXT) {
            return answerDirect(videoId, userId, question, segments);
        }
        return answerRag(videoId, userId, question);
    }

    private QaResponse answerDirect(
        long videoId,
        long userId,
        String question,
        List<VideoTranscriptSegmentEntity> segments
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
        VideoQaResult result = qaProvider.answer(new VideoQaRequest(videoId, question, context));

        Map<Integer, VideoQaRequest.ContextItem> byIndex = new LinkedHashMap<>();
        for (VideoQaRequest.ContextItem item : context) {
            byIndex.put(item.index(), item);
        }
        List<QaCitation> citations = resolveCitations(result.citationIndexes(), byIndex);

        log.info("[userId={}][videoId={}][contextMode=DIRECT_CONTEXT][transcriptChars={}][segmentCount={}] qa answered",
            userId, videoId, strategyResolver.transcriptChars(segments), segments.size());
        return new QaResponse(QaContextMode.DIRECT_CONTEXT.name(), result.answer(), citations);
    }

    private QaResponse answerRag(long videoId, long userId, String question) {
        VideoRagIndexEntity index = ragIndexService.requireReady(videoId, userId);
        List<RetrievedChunk> chunks = retriever.retrieve(userId, videoId, question);

        List<VideoQaRequest.ContextItem> context = new ArrayList<>(chunks.size());
        for (RetrievedChunk chunk : chunks) {
            context.add(new VideoQaRequest.ContextItem(
                chunk.chunkIndex(),
                chunk.text(),
                chunk.startMs(),
                chunk.endMs()
            ));
        }
        VideoQaResult result = qaProvider.answer(new VideoQaRequest(videoId, question, context));

        Map<Integer, VideoQaRequest.ContextItem> byIndex = new LinkedHashMap<>();
        for (VideoQaRequest.ContextItem item : context) {
            byIndex.put(item.index(), item);
        }
        List<QaCitation> citations = resolveCitations(result.citationIndexes(), byIndex);

        List<Integer> retrievedIndexes = chunks.stream().map(RetrievedChunk::chunkIndex).toList();
        log.info("[userId={}][videoId={}][analysisTaskId={}][ragIndexId={}][contextMode=RAG][retrievalTopK={}][retrievedChunkIndexes={}] qa answered",
            userId, videoId, index.getAnalysisTaskId(), index.getId(),
            chunks.size(), retrievedIndexes);
        return new QaResponse(QaContextMode.RAG.name(), result.answer(), citations);
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
