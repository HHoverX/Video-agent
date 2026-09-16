package com.videoagent.rag.service;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.rag.chunk.TranscriptChunk;
import com.videoagent.rag.chunk.TranscriptChunker;
import com.videoagent.rag.config.EmbeddingProperties;
import com.videoagent.rag.embedding.EmbeddingProvider;
import com.videoagent.rag.entity.RagIndexStatus;
import com.videoagent.rag.entity.VideoRagIndexEntity;
import com.videoagent.rag.repository.VideoRagIndexRepository;
import com.videoagent.rag.vector.MilvusTranscriptStore;
import com.videoagent.rag.vector.VectorPoint;
import com.videoagent.transcript.entity.VideoTranscriptSegmentEntity;
import com.videoagent.transcript.repository.VideoTranscriptSegmentRepository;
import com.videoagent.video.service.VideoOwnershipService;
import com.videoagent.telemetry.AiUsageMetrics;
import com.videoagent.telemetry.AnalysisTelemetryContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Builds and tracks the RAG index lifecycle in MySQL (the source of truth),
 * while Milvus holds the derived dense and BM25 data. Every valid transcript is
 * indexed. A build deletes old vectors first, then upserts deterministic point
 * ids so rebuilds replace rather than accumulate.
 */
@Service
public class RagIndexService {

    private static final Logger log = LoggerFactory.getLogger(RagIndexService.class);
    private static final Duration BUILD_LEASE = Duration.ofMinutes(15);

    private final VideoRagIndexRepository indexRepository;
    private final VideoTranscriptSegmentRepository segmentRepository;
    private final VideoOwnershipService ownershipService;
    private final TranscriptChunker chunker;
    private final EmbeddingProvider embeddingProvider;
    private final MilvusTranscriptStore transcriptStore;
    private final EmbeddingProperties embeddingProperties;
    private final TransactionTemplate transactionTemplate;
    private final AiUsageMetrics usageMetrics;

    @Autowired
    public RagIndexService(
        VideoRagIndexRepository indexRepository,
        VideoTranscriptSegmentRepository segmentRepository,
        VideoOwnershipService ownershipService,
        TranscriptChunker chunker,
        EmbeddingProvider embeddingProvider,
        MilvusTranscriptStore transcriptStore,
        EmbeddingProperties embeddingProperties,
        Optional<PlatformTransactionManager> transactionManager,
        AiUsageMetrics usageMetrics
    ) {
        this.indexRepository = indexRepository;
        this.segmentRepository = segmentRepository;
        this.ownershipService = ownershipService;
        this.chunker = chunker;
        this.embeddingProvider = embeddingProvider;
        this.transcriptStore = transcriptStore;
        this.embeddingProperties = embeddingProperties;
        this.transactionTemplate = transactionManager.map(TransactionTemplate::new).orElse(null);
        this.usageMetrics = usageMetrics == null ? AiUsageMetrics.noop() : usageMetrics;
    }

    RagIndexService(
        VideoRagIndexRepository indexRepository,
        VideoTranscriptSegmentRepository segmentRepository,
        VideoOwnershipService ownershipService,
        TranscriptChunker chunker,
        EmbeddingProvider embeddingProvider,
        MilvusTranscriptStore transcriptStore,
        EmbeddingProperties embeddingProperties,
        Optional<PlatformTransactionManager> transactionManager
    ) {
        this(indexRepository, segmentRepository, ownershipService, chunker, embeddingProvider,
            transcriptStore, embeddingProperties, transactionManager, AiUsageMetrics.noop());
    }

    @Transactional(readOnly = true)
    public VideoRagIndexEntity getStatus(long videoId, long userId) {
        ownershipService.requireOwned(videoId, userId);
        return resolveStatus(videoId, loadTranscript(videoId));
    }

    @Transactional(readOnly = true)
    public VideoRagIndexEntity getStatus(
        long videoId,
        long userId,
        List<VideoTranscriptSegmentEntity> segments
    ) {
        ownershipService.requireOwned(videoId, userId);
        return resolveStatus(videoId, segments);
    }

    private VideoRagIndexEntity resolveStatus(
        long videoId,
        List<VideoTranscriptSegmentEntity> segments
    ) {
        Long taskId = latestTaskId(segments);

        VideoRagIndexEntity existing = indexRepository.findByVideoId(videoId);
        if (existing == null) {
            return notBuilt(videoId, taskId);
        }
        return existing;
    }

    public VideoRagIndexEntity buildIndex(long videoId, long userId) {
        ownershipService.requireOwned(videoId, userId);
        List<VideoTranscriptSegmentEntity> segments = requireNonEmpty(loadTranscript(videoId));

        String buildToken = UUID.randomUUID().toString();
        VideoRagIndexEntity index = transactions().execute(status -> {
            VideoRagIndexEntity candidate = upsertIndex(videoId, segments);
            int claimed = claimBuild(candidate.getId(), buildToken);
            if (claimed != 1) {
                throw new VideoAgentException(ErrorCode.RAG_INDEX_BUILD_FAILED,
                    "问答索引正在构建或状态不允许重建");
            }
            return candidate;
        });
        return build(index, userId, videoId, segments, buildToken, false, AnalysisTelemetryContext.unavailable());
    }

    /**
     * Analysis-worker entry point. It reads transcript rows by the currently
     * running task id (the task is intentionally not SUCCESS yet), and treats a
     * READY row for the same task as an embedding checkpoint so retries do not
     * pay for embeddings twice.
     */
    public VideoRagIndexEntity ensureAnalysisIndex(AnalysisTaskEntity task, long userId) {
        long videoId = task.getVideoId();
        ownershipService.requireOwned(videoId, userId);
        List<VideoTranscriptSegmentEntity> segments = requireNonEmpty(
            segmentRepository.findByTaskId(task.getId())
        );
        VideoRagIndexEntity existing = indexRepository.findByVideoId(videoId);
        if (existing != null
            && task.getId().equals(existing.getAnalysisTaskId())
            && RagIndexStatus.READY.name().equals(existing.getStatus())) {
            return existing;
        }
        String buildToken = UUID.randomUUID().toString();
        VideoRagIndexEntity index = transactions().execute(status -> {
            VideoRagIndexEntity candidate = upsertIndex(videoId, segments);
            int claimed = claimBuild(candidate.getId(), buildToken);
            if (claimed != 1) {
                throw new VideoAgentException(ErrorCode.RAG_INDEX_BUILD_FAILED,
                    "问答索引正在由其他 Worker 构建");
            }
            return candidate;
        });
        AnalysisTelemetryContext telemetryContext = new AnalysisTelemetryContext(
            task.getId(), task.getVideoId(), task.getProcessingGeneration(), task.getRetryCount()
        );
        return build(index, userId, videoId, segments, buildToken, true, telemetryContext);
    }

    private VideoRagIndexEntity build(
        VideoRagIndexEntity index,
        long userId,
        long videoId,
        List<VideoTranscriptSegmentEntity> segments,
        String buildToken,
        boolean propagateFailure,
        AnalysisTelemetryContext telemetryContext
    ) {
        try {
            List<TranscriptChunk> chunks = chunker.chunk(segments);
            List<String> texts = chunks.stream().map(TranscriptChunk::text).toList();
            List<float[]> vectors = embedDocuments(texts, telemetryContext);
            transcriptStore.ensureCollection(embeddingProvider.dimension());

            List<VectorPoint> points = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                TranscriptChunk chunk = chunks.get(i);
                points.add(new VectorPoint(
                    chunk.chunkIndex(),
                    chunk.text(),
                    chunk.startMs(),
                    chunk.endMs(),
                    chunk.sourceSegmentIndexes(),
                    vectors.get(i),
                    0.0f
                ));
            }
            // Idempotent rebuild: clear old vectors for this video, then write.
            transcriptStore.deleteByVideoStrict(userId, videoId);
            transcriptStore.upsertPoints(userId, videoId, index.getAnalysisTaskId(), points);

            int ready = transactions().execute(status ->
                indexRepository.markReady(index.getId(), buildToken, chunks.size(), LocalDateTime.now()));
            if (ready != 1) {
                throw new VideoAgentException(ErrorCode.RAG_INDEX_BUILD_FAILED,
                    "问答索引状态更新失败");
            }
            log.info("[userId={}][videoId={}][analysisTaskId={}][ragIndexId={}][chunkCount={}][embeddingProvider={}] index ready",
                userId, videoId, index.getAnalysisTaskId(), index.getId(), chunks.size(),
                embeddingProvider.providerName());
            return indexRepository.selectById(index.getId());
        } catch (RuntimeException exception) {
            transactions().executeWithoutResult(status -> indexRepository.markFailed(
                index.getId(),
                buildToken,
                ErrorCode.RAG_INDEX_BUILD_FAILED.name(),
                safeMessage(exception),
                LocalDateTime.now()
            ));
            log.warn("[userId={}][videoId={}][ragIndexId={}] index build failed: {}",
                userId, videoId, index.getId(), safeMessage(exception));
            if (propagateFailure) {
                throw exception;
            }
            return indexRepository.selectById(index.getId());
        }
    }

    private List<float[]> embedDocuments(List<String> texts, AnalysisTelemetryContext telemetryContext) {
        if (telemetryContext.taskId() == null) {
            return embeddingProvider.embedDocuments(texts);
        }
        long startedAtNanos = System.nanoTime();
        String outcome = "failure";
        String errorCategory = ErrorCode.RAG_INDEX_BUILD_FAILED.name();
        try {
            List<float[]> vectors = embeddingProvider.embedDocuments(texts, telemetryContext);
            outcome = "success";
            errorCategory = "none";
            return vectors;
        } catch (RuntimeException exception) {
            if (exception instanceof VideoAgentException providerFailure) {
                errorCategory = providerFailure.errorCode().name();
            } else {
                errorCategory = ErrorCode.RAG_INDEX_BUILD_FAILED.name();
            }
            throw exception;
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
            usageMetrics.recordLogicalCall("embedding_document", embeddingProvider.providerName(),
                embeddingProperties.model(), "document", outcome, errorCategory, durationMs);
            log.info("event=ai.logical_call scope=analysis stage=embedding_document provider={} model={} taskId={} videoId={} generation={} retryCount={} documentCount={} durationMs={} outcome={} errorCategory={}",
                embeddingProvider.providerName(), embeddingProperties.model(), telemetryContext.taskId(),
                telemetryContext.videoId(), telemetryContext.generation(), telemetryContext.retryCount(), texts.size(),
                durationMs, outcome, errorCategory);
        }
    }

    private int claimBuild(long indexId, String buildToken) {
        LocalDateTime now = LocalDateTime.now();
        return indexRepository.claimBuilding(indexId, buildToken, now.minus(BUILD_LEASE), now);
    }

    private VideoRagIndexEntity upsertIndex(
        long videoId,
        List<VideoTranscriptSegmentEntity> segments
    ) {
        VideoRagIndexEntity existing = indexRepository.findByVideoId(videoId);
        Long taskId = latestTaskId(segments);
        if (existing == null) {
            VideoRagIndexEntity created = new VideoRagIndexEntity();
            created.setVideoId(videoId);
            created.setAnalysisTaskId(taskId);
            created.setStatus(RagIndexStatus.NOT_BUILT.name());
            created.setChunkCount(0);
            created.setEmbeddingProvider(embeddingProvider.providerName());
            created.setEmbeddingModel(embeddingProperties.model());
            created.setEmbeddingDimension(embeddingProperties.dimension());
            created.setCreatedAt(LocalDateTime.now());
            created.setUpdatedAt(LocalDateTime.now());
            indexRepository.insert(created);
            return created;
        }
        existing.setAnalysisTaskId(taskId);
        if (RagIndexStatus.READY.name().equals(existing.getStatus())
            || RagIndexStatus.NOT_BUILT.name().equals(existing.getStatus())) {
            existing.setStatus(RagIndexStatus.NOT_BUILT.name());
        }
        existing.setEmbeddingProvider(embeddingProvider.providerName());
        existing.setEmbeddingModel(embeddingProperties.model());
        existing.setEmbeddingDimension(embeddingProperties.dimension());
        indexRepository.updateById(existing);
        return existing;
    }

    private VideoRagIndexEntity notBuilt(long videoId, Long taskId) {
        VideoRagIndexEntity entity = new VideoRagIndexEntity();
        entity.setVideoId(videoId);
        entity.setAnalysisTaskId(taskId);
        entity.setStatus(RagIndexStatus.NOT_BUILT.name());
        entity.setChunkCount(0);
        entity.setEmbeddingProvider(embeddingProvider.providerName());
        entity.setEmbeddingModel(embeddingProperties.model());
        entity.setEmbeddingDimension(embeddingProperties.dimension());
        return entity;
    }

    private List<VideoTranscriptSegmentEntity> loadTranscript(long videoId) {
        return segmentRepository.findLatestSuccessfulByVideoId(videoId);
    }

    private List<VideoTranscriptSegmentEntity> requireNonEmpty(List<VideoTranscriptSegmentEntity> segments) {
        if (segments == null || segments.isEmpty()) {
            throw new VideoAgentException(ErrorCode.TRANSCRIPTION_FAILED, "该视频暂无可用字幕，无法建立问答索引");
        }
        return segments;
    }

    private Long latestTaskId(List<VideoTranscriptSegmentEntity> segments) {
        return segments.stream()
            .map(VideoTranscriptSegmentEntity::getTaskId)
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
            ? ErrorCode.RAG_INDEX_BUILD_FAILED.defaultMessage()
            : (message.length() <= 1000 ? message : message.substring(0, 1000));
    }

    private TransactionTemplate transactions() {
        if (transactionTemplate == null) {
            throw new IllegalStateException("RAG index build requires a PlatformTransactionManager");
        }
        return transactionTemplate;
    }

    public VideoRagIndexEntity requireReady(long videoId, long userId) {
        ownershipService.requireOwned(videoId, userId);
        VideoRagIndexEntity index = indexRepository.findByVideoId(videoId);
        if (index == null || !RagIndexStatus.READY.name().equals(index.getStatus())) {
            throw new VideoAgentException(ErrorCode.RAG_INDEX_NOT_READY);
        }
        return index;
    }
}
