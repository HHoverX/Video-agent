package com.videoagent.agent.tool;

import com.videoagent.agent.context.AgenticQaContext;
import com.videoagent.agent.evidence.EvidenceItem;
import com.videoagent.agent.evidence.EvidenceSourceType;
import com.videoagent.agent.plan.RetrievalAction;
import com.videoagent.agent.plan.RetrievalTool;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.rag.context.QaContextMode;
import com.videoagent.rag.retrieval.RetrievedChunk;
import com.videoagent.rag.retrieval.TranscriptRetriever;
import com.videoagent.summary.dto.VideoChapterResponse;
import com.videoagent.summary.dto.VideoKeyPointResponse;
import com.videoagent.summary.dto.VideoSummaryResponse;
import com.videoagent.summary.service.VideoSummaryService;
import com.videoagent.transcript.entity.VideoTranscriptSegmentEntity;
import com.videoagent.transcript.repository.VideoTranscriptSegmentRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes retrieval actions strictly inside the server-bound context. The
 * LLM plan never carries a userId/videoId, so cross-user or cross-video access
 * is impossible at the schema level; this executor only ever reads the current
 * authenticated user's video.
 */
@Component
public class AgenticToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgenticToolExecutor.class);

    private final VideoTranscriptSegmentRepository segmentRepository;
    private final VideoSummaryService summaryService;
    private final TranscriptRetriever transcriptRetriever;

    public AgenticToolExecutor(
        VideoTranscriptSegmentRepository segmentRepository,
        VideoSummaryService summaryService,
        TranscriptRetriever transcriptRetriever
    ) {
        this.segmentRepository = segmentRepository;
        this.summaryService = summaryService;
        this.transcriptRetriever = transcriptRetriever;
    }

    /**
     * Executes the validated plan's actions in order, assigning stable
     * request-local evidence ids E1, E2, ...
     */
    public List<EvidenceItem> execute(AgenticQaContext context, List<RetrievalAction> actions) {
        AtomicInteger idCounter = new AtomicInteger(1);
        List<EvidenceItem> evidence = new ArrayList<>();
        for (RetrievalAction action : actions) {
            if (action == null || action.tool() == null) {
                continue;
            }
            if (action.tool() == RetrievalTool.GET_VIDEO_SUMMARY) {
                evidence.addAll(summaryEvidence(context, idCounter));
            } else if (action.tool() == RetrievalTool.GET_TRANSCRIPT_BY_TIME) {
                evidence.addAll(timeEvidence(context, action, idCounter));
            } else if (action.tool() == RetrievalTool.SEARCH_TRANSCRIPT) {
                evidence.addAll(searchEvidence(context, action.query(), idCounter));
            }
        }
        return evidence;
    }

    private List<EvidenceItem> summaryEvidence(AgenticQaContext context, AtomicInteger idCounter) {
        try {
            VideoSummaryResponse summary = summaryService.getSummary(context.videoId(), context.currentUserId())
                .orElse(null);
            if (summary == null) {
                return List.of();
            }
            List<VideoChapterResponse> chapters = summaryService.getChapters(
                context.videoId(), context.currentUserId());
            List<VideoKeyPointResponse> keyPoints = summaryService.getKeyPoints(
                context.videoId(), context.currentUserId());

            StringBuilder text = new StringBuilder("概述：" + (summary.overview() == null ? "" : summary.overview()));
            if (!chapters.isEmpty()) {
                text.append("\n章节：");
                for (VideoChapterResponse chapter : chapters) {
                    text.append("\n[")
                        .append(chapter.title())
                        .append("] ")
                        .append(chapter.summary());
                }
            }
            if (!keyPoints.isEmpty()) {
                text.append("\n要点：");
                for (VideoKeyPointResponse point : keyPoints) {
                    text.append("\n- ").append(point.content());
                }
            }
            List<EvidenceItem> items = new ArrayList<>();
            items.add(new EvidenceItem(
                evidenceId(idCounter),
                EvidenceSourceType.SUMMARY,
                text.toString(),
                null,
                null,
                null,
                null,
                List.of(),
                null
            ));
            log.info("[userId={}][videoId={}][tool=GET_VIDEO_SUMMARY][summaryAvailable=true]",
                context.currentUserId(), context.videoId());
            return items;
        } catch (RuntimeException exception) {
            log.info("[userId={}][videoId={}][tool=GET_VIDEO_SUMMARY][summaryAvailable=false]",
                context.currentUserId(), context.videoId());
            return List.of();
        }
    }

    private List<EvidenceItem> timeEvidence(
        AgenticQaContext context,
        RetrievalAction action,
        AtomicInteger idCounter
    ) {
        long timeMs = action.timeMs() == null ? 0 : action.timeMs();
        long windowMs = action.windowMs() == null ? 15_000 : action.windowMs();
        List<VideoTranscriptSegmentEntity> segments = loadSegments(context.videoId());
        long from = Math.max(0, timeMs - windowMs);
        long to = timeMs + windowMs;

        List<EvidenceItem> items = new ArrayList<>();
        for (VideoTranscriptSegmentEntity segment : segments) {
            long startMs = segment.getStartMs() == null ? 0L : segment.getStartMs();
            long endMs = segment.getEndMs() == null ? startMs : segment.getEndMs();
            if (overlaps(startMs, endMs, from, to)) {
                items.add(new EvidenceItem(
                    evidenceId(idCounter),
                    EvidenceSourceType.TRANSCRIPT_TIME,
                    segment.getText() == null ? "" : segment.getText(),
                    startMs,
                    endMs,
                    segment.getSegmentIndex(),
                    null,
                    List.of(),
                    null
                ));
            }
        }
        log.info("[userId={}][videoId={}][tool=GET_TRANSCRIPT_BY_TIME][requestedTimeMs={}][matchedSegmentIndexes={}]",
            context.currentUserId(), context.videoId(), timeMs,
            items.stream().map(e -> String.valueOf(e.segmentIndex())).toList());
        return items;
    }

    private List<EvidenceItem> searchEvidence(
        AgenticQaContext context,
        String query,
        AtomicInteger idCounter
    ) {
        List<VideoTranscriptSegmentEntity> segments = loadSegments(context.videoId());
        QaContextMode mode = context.contextMode();

        if (mode == QaContextMode.DIRECT_CONTEXT) {
            // Short transcript: the full transcript is the evidence. No
            // embedding, no Qdrant.
            List<EvidenceItem> items = new ArrayList<>();
            for (VideoTranscriptSegmentEntity segment : segments) {
                long startMs = segment.getStartMs() == null ? 0L : segment.getStartMs();
                long endMs = segment.getEndMs() == null ? startMs : segment.getEndMs();
                items.add(new EvidenceItem(
                    evidenceId(idCounter),
                    EvidenceSourceType.TRANSCRIPT_SEARCH,
                    segment.getText() == null ? "" : segment.getText(),
                    startMs,
                    endMs,
                    segment.getSegmentIndex(),
                    null,
                    List.of(),
                    null
                ));
            }
            return items;
        }

        if (!context.ragReady()) {
            throw new VideoAgentException(ErrorCode.RAG_INDEX_NOT_READY);
        }

        List<RetrievedChunk> chunks = transcriptRetriever.retrieve(
            context.currentUserId(),
            context.videoId(),
            query
        );
        List<EvidenceItem> items = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            items.add(new EvidenceItem(
                evidenceId(idCounter),
                EvidenceSourceType.TRANSCRIPT_SEARCH,
                chunk.text(),
                chunk.startMs(),
                chunk.endMs(),
                null,
                chunk.chunkIndex(),
                chunk.sourceSegmentIndexes(),
                chunk.score()
            ));
        }
        log.info("[userId={}][videoId={}][tool=SEARCH_TRANSCRIPT][retrievedChunkIndexes={}]",
            context.currentUserId(), context.videoId(),
            chunks.stream().map(RetrievedChunk::chunkIndex).toList());
        return items;
    }

    private List<VideoTranscriptSegmentEntity> loadSegments(long videoId) {
        return segmentRepository.findLatestSuccessfulByVideoId(videoId);
    }

    private boolean overlaps(long startMs, long endMs, long from, long to) {
        return startMs < to && endMs > from;
    }

    private String evidenceId(AtomicInteger counter) {
        return "E" + counter.getAndIncrement();
    }
}
