package com.videoagent.rag.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.rag.chunk.TranscriptChunk;
import com.videoagent.rag.chunk.TranscriptChunker;
import com.videoagent.rag.config.EmbeddingProperties;
import com.videoagent.rag.config.RagProperties;
import com.videoagent.rag.context.ContextStrategyResolver;
import com.videoagent.rag.context.QaContextMode;
import com.videoagent.rag.embedding.EmbeddingProvider;
import com.videoagent.rag.entity.RagIndexStatus;
import com.videoagent.rag.entity.VideoRagIndexEntity;
import com.videoagent.rag.repository.VideoRagIndexRepository;
import com.videoagent.rag.vector.QdrantVectorStore;
import com.videoagent.rag.vector.VectorPoint;
import com.videoagent.transcript.entity.VideoTranscriptSegmentEntity;
import com.videoagent.transcript.repository.VideoTranscriptSegmentRepository;
import com.videoagent.video.service.VideoOwnershipService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds and tracks the RAG index lifecycle in MySQL (the source of truth),
 * while Qdrant holds the derived vector data. A short transcript never creates
 * a vector index (NOT_REQUIRED). For RAG transcripts, the build deletes old
 * vectors first, then upserts with deterministic point ids so rebuilds replace
 * rather than accumulate.
 */
@Service
public class RagIndexService {

    private static final Logger log = LoggerFactory.getLogger(RagIndexService.class);

    private final VideoRagIndexRepository indexRepository;
    private final VideoTranscriptSegmentRepository segmentRepository;
    private final VideoOwnershipService ownershipService;
    private final ContextStrategyResolver strategyResolver;
    private final TranscriptChunker chunker;
    private final EmbeddingProvider embeddingProvider;
    private final QdrantVectorStore vectorStore;
    private final RagProperties ragProperties;
    private final EmbeddingProperties embeddingProperties;
    private final TransactionTemplate transactionTemplate;

    public RagIndexService(
        VideoRagIndexRepository indexRepository,
        VideoTranscriptSegmentRepository segmentRepository,
        VideoOwnershipService ownershipService,
        ContextStrategyResolver strategyResolver,
        TranscriptChunker chunker,
        EmbeddingProvider embeddingProvider,
        QdrantVectorStore vectorStore,
        RagProperties ragProperties,
        EmbeddingProperties embeddingProperties,
        Optional<PlatformTransactionManager> transactionManager
    ) {
        this.indexRepository = indexRepository;
        this.segmentRepository = segmentRepository;
        this.ownershipService = ownershipService;
        this.strategyResolver = strategyResolver;
        this.chunker = chunker;
        this.embeddingProvider = embeddingProvider;
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
        this.embeddingProperties = embeddingProperties;
        this.transactionTemplate = transactionManager.map(TransactionTemplate::new).orElse(null);
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
        long chars = strategyResolver.transcriptChars(segments);
        QaContextMode mode = strategyResolver.resolveMode(segments);
        Long taskId = latestTaskId(segments);

        VideoRagIndexEntity existing = indexRepository.findByVideoId(videoId);
        if (mode == QaContextMode.DIRECT_CONTEXT) {
            if (existing == null) {
                return notRequired(videoId, taskId, chars);
            }
            existing.setContextMode(QaContextMode.DIRECT_CONTEXT.name());
            existing.setStatus(RagIndexStatus.NOT_REQUIRED.name());
            return existing;
        }
        if (existing == null) {
            return notBuilt(videoId, taskId, chars);
        }
        existing.setContextMode(QaContextMode.RAG.name());
        return existing;
    }

    public VideoRagIndexEntity buildIndex(long videoId, long userId) {
        ownershipService.requireOwned(videoId, userId);
        List<VideoTranscriptSegmentEntity> segments = strategyResolver.requireNonEmpty(loadTranscript(videoId));
        long chars = strategyResolver.transcriptChars(segments);
        QaContextMode mode = strategyResolver.resolveMode(segments);
        if (mode == QaContextMode.DIRECT_CONTEXT) {
            VideoRagIndexEntity notRequired = transactions().execute(status ->
                upsertIndex(videoId, userId, segments, chars, mode));
            log.info("[userId={}][videoId={}][contextMode=DIRECT_CONTEXT][transcriptChars={}] index not required",
                userId, videoId, chars);
            return notRequired;
        }

        VideoRagIndexEntity index = transactions().execute(status -> {
            VideoRagIndexEntity candidate = upsertIndex(videoId, userId, segments, chars, mode);
            int claimed = indexRepository.claimBuilding(candidate.getId(), LocalDateTime.now());
            if (claimed != 1) {
                throw new VideoAgentException(ErrorCode.RAG_INDEX_BUILD_FAILED,
                    "问答索引正在构建或状态不允许重建");
            }
            return candidate;
        });
        return build(index, userId, videoId, segments);
    }

    private VideoRagIndexEntity build(
        VideoRagIndexEntity index,
        long userId,
        long videoId,
        List<VideoTranscriptSegmentEntity> segments
    ) {
        try {
            List<TranscriptChunk> chunks = chunker.chunk(segments);
            List<String> texts = chunks.stream().map(TranscriptChunk::text).toList();
            List<float[]> vectors = embeddingProvider.embedDocuments(texts);
            vectorStore.ensureCollection(embeddingProperties.dimension());

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
            vectorStore.deleteByVideoStrict(userId, videoId);
            vectorStore.upsertPoints(userId, videoId, index.getAnalysisTaskId(), points);

            int ready = transactions().execute(status ->
                indexRepository.markReady(index.getId(), chunks.size(), LocalDateTime.now()));
            if (ready != 1) {
                throw new VideoAgentException(ErrorCode.RAG_INDEX_BUILD_FAILED,
                    "问答索引状态更新失败");
            }
            log.info("[userId={}][videoId={}][analysisTaskId={}][ragIndexId={}][contextMode=RAG][chunkCount={}][embeddingProvider={}] index ready",
                userId, videoId, index.getAnalysisTaskId(), index.getId(), chunks.size(),
                embeddingProvider.providerName());
            return indexRepository.selectById(index.getId());
        } catch (RuntimeException exception) {
            transactions().executeWithoutResult(status -> indexRepository.markFailed(
                index.getId(),
                ErrorCode.RAG_INDEX_BUILD_FAILED.name(),
                safeMessage(exception),
                LocalDateTime.now()
            ));
            log.warn("[userId={}][videoId={}][ragIndexId={}] index build failed: {}",
                userId, videoId, index.getId(), safeMessage(exception));
            return indexRepository.selectById(index.getId());
        }
    }

    private VideoRagIndexEntity upsertIndex(
        long videoId,
        long userId,
        List<VideoTranscriptSegmentEntity> segments,
        long chars,
        QaContextMode mode
    ) {
        VideoRagIndexEntity existing = indexRepository.findByVideoId(videoId);
        Long taskId = latestTaskId(segments);
        if (existing == null) {
            VideoRagIndexEntity created = new VideoRagIndexEntity();
            created.setVideoId(videoId);
            created.setAnalysisTaskId(taskId);
            created.setStatus(mode == QaContextMode.DIRECT_CONTEXT
                ? RagIndexStatus.NOT_REQUIRED.name()
                : RagIndexStatus.NOT_BUILT.name());
            created.setContextMode(mode.name());
            created.setTranscriptChars((int) chars);
            created.setChunkCount(0);
            created.setEmbeddingProvider(mode == QaContextMode.RAG ? embeddingProvider.providerName() : "");
            created.setEmbeddingModel(embeddingProperties.model());
            created.setEmbeddingDimension(mode == QaContextMode.RAG ? embeddingProperties.dimension() : 0);
            created.setCreatedAt(LocalDateTime.now());
            created.setUpdatedAt(LocalDateTime.now());
            indexRepository.insert(created);
            return created;
        }
        existing.setTranscriptChars((int) chars);
        existing.setAnalysisTaskId(taskId);
        if (mode == QaContextMode.DIRECT_CONTEXT) {
            existing.setStatus(RagIndexStatus.NOT_REQUIRED.name());
            existing.setContextMode(QaContextMode.DIRECT_CONTEXT.name());
        } else {
            existing.setContextMode(QaContextMode.RAG.name());
            if (RagIndexStatus.NOT_REQUIRED.name().equals(existing.getStatus())
                || RagIndexStatus.READY.name().equals(existing.getStatus())
                || RagIndexStatus.NOT_BUILT.name().equals(existing.getStatus())) {
                existing.setStatus(RagIndexStatus.NOT_BUILT.name());
            }
            existing.setEmbeddingProvider(embeddingProvider.providerName());
            existing.setEmbeddingModel(embeddingProperties.model());
            existing.setEmbeddingDimension(embeddingProperties.dimension());
        }
        indexRepository.updateById(existing);
        return existing;
    }

    private VideoRagIndexEntity notRequired(long videoId, Long taskId, long chars) {
        VideoRagIndexEntity entity = new VideoRagIndexEntity();
        entity.setVideoId(videoId);
        entity.setAnalysisTaskId(taskId);
        entity.setStatus(RagIndexStatus.NOT_REQUIRED.name());
        entity.setContextMode(QaContextMode.DIRECT_CONTEXT.name());
        entity.setTranscriptChars((int) chars);
        entity.setChunkCount(0);
        return entity;
    }

    private VideoRagIndexEntity notBuilt(long videoId, Long taskId, long chars) {
        VideoRagIndexEntity entity = new VideoRagIndexEntity();
        entity.setVideoId(videoId);
        entity.setAnalysisTaskId(taskId);
        entity.setStatus(RagIndexStatus.NOT_BUILT.name());
        entity.setContextMode(QaContextMode.RAG.name());
        entity.setTranscriptChars((int) chars);
        entity.setChunkCount(0);
        entity.setEmbeddingProvider(embeddingProvider.providerName());
        entity.setEmbeddingModel(embeddingProperties.model());
        entity.setEmbeddingDimension(embeddingProperties.dimension());
        return entity;
    }

    private List<VideoTranscriptSegmentEntity> loadTranscript(long videoId) {
        return segmentRepository.findLatestSuccessfulByVideoId(videoId);
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
