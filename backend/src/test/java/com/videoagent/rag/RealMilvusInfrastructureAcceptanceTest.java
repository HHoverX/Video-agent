package com.videoagent.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.auth.entity.AppUserEntity;
import com.videoagent.auth.repository.AppUserRepository;
import com.videoagent.rag.embedding.EmbeddingProvider;
import com.videoagent.rag.entity.RagIndexStatus;
import com.videoagent.rag.entity.VideoRagIndexEntity;
import com.videoagent.rag.repository.VideoRagIndexRepository;
import com.videoagent.rag.retrieval.HybridCandidate;
import com.videoagent.rag.retrieval.LexicalChunk;
import com.videoagent.rag.retrieval.ReciprocalRankFusion;
import com.videoagent.rag.service.RagIndexService;
import com.videoagent.rag.vector.MilvusTranscriptStore;
import com.videoagent.rag.vector.VectorPoint;
import com.videoagent.transcript.entity.VideoTranscriptSegmentEntity;
import com.videoagent.transcript.repository.VideoTranscriptSegmentRepository;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@EnabledIfEnvironmentVariable(named = "VIDEOAGENT_REAL_MILVUS_ACCEPTANCE", matches = "true")
@SpringBootTest
class RealMilvusInfrastructureAcceptanceTest {

    @Autowired private MilvusTranscriptStore store;
    @Autowired private EmbeddingProvider embeddingProvider;
    @Autowired private ReciprocalRankFusion fusion;
    @Autowired private RagIndexService ragIndexService;
    @Autowired private AppUserRepository userRepository;
    @Autowired private VideoRepository videoRepository;
    @Autowired private AnalysisTaskRepository taskRepository;
    @Autowired private VideoTranscriptSegmentRepository segmentRepository;
    @Autowired private VideoRagIndexRepository ragIndexRepository;

    @Test
    void shouldUseRealDenseBm25RrfIsolationAndReplacement() {
        assertThat(embeddingProvider.providerName()).isNotEqualTo("mock");
        long seed = Math.abs(System.nanoTime());
        long userA = seed;
        long videoA = seed + 1;
        long taskA = seed + 2;
        long videoB = seed + 3;
        long userB = seed + 4;
        long videoC = seed + 5;

        List<String> texts = List.of(
            "Redis cache and distributed lock",
            "RocketMQ transactional message",
            "SHA-256 upload integrity",
            "NullPointerException diagnostic trace",
            "uploadId resumable session",
            "使用Redis实现分布式锁"
        );
        try {
            store.ensureCollection(embeddingProvider.dimension());
            put(userA, videoA, taskA, texts);
            put(userA, videoB, taskA + 1, List.of("OTHER_VIDEO_ONLY Redis"));
            put(userB, videoC, taskA + 2, List.of("OTHER_USER_ONLY Redis"));

            List<VectorPoint> dense = store.searchDense(
                userA, videoA, embeddingProvider.embedQuery("Redis distributed lock"), 10);
            List<LexicalChunk> bm25 = store.searchLexical(userA, videoA, "Redis", 10);
            List<HybridCandidate> hybrid = fusion.fuse(videoA, dense, bm25, 60);

            System.out.println("REAL_MILVUS_DENSE=" + dense.stream()
                .map(hit -> hit.chunkIndex() + ":" + hit.score() + ":" + hit.text()).toList());
            System.out.println("REAL_MILVUS_BM25=" + bm25.stream()
                .map(hit -> hit.chunkIndex() + ":" + hit.lexicalScore() + ":" + hit.text()).toList());
            System.out.println("REAL_MILVUS_RRF=" + hybrid.stream()
                .map(hit -> hit.chunkIndex() + ":" + hit.rrfScore() + ":" + hit.text()).toList());

            assertThat(dense).isNotEmpty().allSatisfy(hit ->
                assertThat(hit.text()).doesNotContain("OTHER_VIDEO_ONLY", "OTHER_USER_ONLY"));
            assertThat(dense).anySatisfy(hit -> assertThat(hit.text()).contains("Redis"));
            assertThat(bm25).extracting(LexicalChunk::text)
                .contains("Redis cache and distributed lock", "使用Redis实现分布式锁")
                .doesNotContain("OTHER_VIDEO_ONLY Redis", "OTHER_USER_ONLY Redis");
            assertThat(hybrid).isNotEmpty().allSatisfy(hit ->
                assertThat(hit.text()).doesNotContain("OTHER_VIDEO_ONLY", "OTHER_USER_ONLY"));

            for (String term : List.of(
                "Redis", "RocketMQ", "SHA-256", "NullPointerException", "uploadId", "使用Redis实现分布式锁")) {
                List<LexicalChunk> hits = store.searchLexical(userA, videoA, term, 10);
                System.out.println("REAL_MILVUS_TERM=" + term + " HITS="
                    + hits.stream().map(LexicalChunk::text).toList());
                assertThat(hits).isNotEmpty();
            }

            store.deleteByVideoStrict(userA, videoA);
            put(userA, videoA, taskA + 10, List.of("REBUILT_ONLY current chunk"));
            assertThat(store.searchLexical(userA, videoA, "RocketMQ", 10)).isEmpty();
            assertThat(store.searchLexical(userA, videoA, "REBUILT_ONLY", 10))
                .extracting(LexicalChunk::text).containsExactly("REBUILT_ONLY current chunk");
        } finally {
            store.deleteByVideo(userA, videoA);
            store.deleteByVideo(userA, videoB);
            store.deleteByVideo(userB, videoC);
        }
    }

    @Test
    void shouldPersistReadyOnlyAfterRealMilvusWrite() {
        assertThat(embeddingProvider.providerName()).isNotEqualTo("mock");
        AppUserEntity user = insertUser();
        VideoEntity video = insertVideo(user.getId());
        AnalysisTaskEntity task = insertTask(video.getId());
        insertLongTranscript(video.getId(), task.getId());
        try {
            VideoRagIndexEntity built = ragIndexService.buildIndex(video.getId(), user.getId());
            VideoRagIndexEntity persisted = ragIndexRepository.findByVideoId(video.getId());

            System.out.println("REAL_MILVUS_RAG_INDEX=status=" + persisted.getStatus()
                + ",chunks=" + persisted.getChunkCount() + ",taskId=" + persisted.getAnalysisTaskId());
            assertThat(built.getStatus()).isEqualTo(RagIndexStatus.READY.name());
            assertThat(persisted.getStatus()).isEqualTo(RagIndexStatus.READY.name());
            assertThat(persisted.getChunkCount()).isPositive();
            assertThat(store.searchLexical(user.getId(), video.getId(), "RocketMQ", 10)).isNotEmpty();
        } finally {
            store.deleteByVideo(user.getId(), video.getId());
            deleteFixture(user, video, task);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VIDEOAGENT_EXPECT_MILVUS_DOWN", matches = "true")
    void shouldPersistFailedInsteadOfReadyWhenRealMilvusIsUnavailable() {
        assertThat(embeddingProvider.providerName()).isNotEqualTo("mock");
        AppUserEntity user = insertUser();
        VideoEntity video = insertVideo(user.getId());
        AnalysisTaskEntity task = insertTask(video.getId());
        insertLongTranscript(video.getId(), task.getId());
        try {
            VideoRagIndexEntity result = ragIndexService.buildIndex(video.getId(), user.getId());
            VideoRagIndexEntity persisted = ragIndexRepository.findByVideoId(video.getId());

            System.out.println("REAL_MILVUS_FAILURE_RAG_INDEX=status=" + persisted.getStatus()
                + ",error=" + persisted.getLastErrorMessage());
            assertThat(result.getStatus()).isEqualTo(RagIndexStatus.FAILED.name());
            assertThat(persisted.getStatus()).isEqualTo(RagIndexStatus.FAILED.name());
            assertThat(persisted.getStatus()).isNotEqualTo(RagIndexStatus.READY.name());
        } finally {
            deleteFixture(user, video, task);
        }
    }

    private void deleteFixture(AppUserEntity user, VideoEntity video, AnalysisTaskEntity task) {
        segmentRepository.deleteByTaskId(task.getId());
        VideoRagIndexEntity index = ragIndexRepository.findByVideoId(video.getId());
        if (index != null) {
            ragIndexRepository.deleteById(index.getId());
        }
        taskRepository.deleteById(task.getId());
        videoRepository.deleteById(video.getId());
        userRepository.deleteById(user.getId());
    }

    private void put(long userId, long videoId, long taskId, List<String> texts) {
        List<float[]> vectors = embeddingProvider.embedDocuments(texts);
        List<VectorPoint> points = new ArrayList<>(texts.size());
        for (int index = 0; index < texts.size(); index++) {
            points.add(new VectorPoint(index, texts.get(index), index * 1000L, (index + 1) * 1000L,
                List.of(index), vectors.get(index), 0.0f));
        }
        store.upsertPoints(userId, videoId, taskId, points);
    }

    private AppUserEntity insertUser() {
        LocalDateTime now = LocalDateTime.now();
        AppUserEntity user = new AppUserEntity();
        user.setUsername("real-milvus-" + UUID.randomUUID().toString().substring(0, 12));
        user.setPasswordHash("x".repeat(60));
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        assertThat(userRepository.insert(user)).isEqualTo(1);
        return user;
    }

    private VideoEntity insertVideo(long userId) {
        LocalDateTime now = LocalDateTime.now();
        VideoEntity video = new VideoEntity();
        video.setUserId(userId);
        video.setTitle("Real Milvus acceptance");
        video.setOriginalFilename("real-milvus.mp4");
        video.setObjectKey("acceptance/" + UUID.randomUUID() + ".mp4");
        video.setFileSize(1L);
        video.setMimeType("video/mp4");
        video.setStatus("UPLOADED");
        video.setCreatedAt(now);
        video.setUpdatedAt(now);
        assertThat(videoRepository.insert(video)).isEqualTo(1);
        return video;
    }

    private AnalysisTaskEntity insertTask(long videoId) {
        LocalDateTime now = LocalDateTime.now();
        AnalysisTaskEntity task = new AnalysisTaskEntity();
        task.setVideoId(videoId);
        task.setAnalysisType("STRUCTURED_SUMMARY");
        task.setModelVersion("real-milvus-acceptance");
        task.setStatus("SUCCESS");
        task.setStage("DONE");
        task.setProgress(100);
        task.setRetryCount(0);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        assertThat(taskRepository.insert(task)).isEqualTo(1);
        return task;
    }

    private void insertLongTranscript(long videoId, long taskId) {
        LocalDateTime now = LocalDateTime.now();
        for (int index = 0; index < 24; index++) {
            VideoTranscriptSegmentEntity segment = new VideoTranscriptSegmentEntity();
            segment.setVideoId(videoId);
            segment.setTaskId(taskId);
            segment.setSegmentIndex(index);
            segment.setStartMs(index * 1000L);
            segment.setEndMs((index + 1) * 1000L);
            segment.setText(("segment " + index
                + " uses Redis RocketMQ SHA-256 NullPointerException uploadId distributed lock. ").repeat(8));
            segment.setCreatedAt(now);
            assertThat(segmentRepository.insert(segment)).isEqualTo(1);
        }
    }
}
