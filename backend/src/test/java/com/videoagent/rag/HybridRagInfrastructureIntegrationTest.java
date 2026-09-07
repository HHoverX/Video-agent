package com.videoagent.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.auth.entity.AppUserEntity;
import com.videoagent.auth.repository.AppUserRepository;
import com.videoagent.rag.chunk.TranscriptChunk;
import com.videoagent.rag.config.RagProperties;
import com.videoagent.rag.embedding.EmbeddingProvider;
import com.videoagent.rag.entity.VideoRagChunkEntity;
import com.videoagent.rag.repository.VideoRagChunkRepository;
import com.videoagent.rag.rerank.HttpTranscriptReranker;
import com.videoagent.rag.retrieval.LexicalChunk;
import com.videoagent.rag.retrieval.LexicalTranscriptStore;
import com.videoagent.rag.retrieval.ReciprocalRankFusion;
import com.videoagent.rag.retrieval.RetrievedChunk;
import com.videoagent.rag.retrieval.TranscriptRetriever;
import com.videoagent.rag.vector.QdrantVectorStore;
import com.videoagent.rag.vector.VectorPoint;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@EnabledIfEnvironmentVariable(named = "VIDEOAGENT_HYBRID_RAG_INFRA_TEST", matches = "true")
@SpringBootTest(properties = {
    "videoagent.ai.asr.provider=mock",
    "videoagent.ai.llm.provider=mock",
    "videoagent.rag.embedding.provider=mock",
    "videoagent.rag.embedding.dimension=384"
})
class HybridRagInfrastructureIntegrationTest {

    @Autowired private AppUserRepository userRepository;
    @Autowired private VideoRepository videoRepository;
    @Autowired private AnalysisTaskRepository taskRepository;
    @Autowired private VideoRagChunkRepository chunkRepository;
    @Autowired private LexicalTranscriptStore lexicalStore;
    @Autowired private QdrantVectorStore vectorStore;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    private final List<Long> users = new ArrayList<>();
    private final List<Long> videos = new ArrayList<>();
    private final List<Long> tasks = new ArrayList<>();
    private final List<long[]> qdrantIndexes = new ArrayList<>();
    private HttpServer rerankServer;

    @AfterEach
    void cleanUp() {
        if (rerankServer != null) {
            rerankServer.stop(0);
            rerankServer = null;
        }
        qdrantIndexes.forEach(index -> vectorStore.deleteByVideo(index[0], index[1]));
        videos.forEach(videoId -> chunkRepository.deleteByUserAndVideo(ownerOf(videoId), videoId));
        for (Long taskId : tasks.reversed()) taskRepository.deleteById(taskId);
        for (Long videoId : videos.reversed()) videoRepository.deleteById(videoId);
        for (Long userId : users.reversed()) userRepository.deleteById(userId);
    }

    @Test
    void shouldUseNgramFulltextForCjkPreciseTermsAndSqlIsolation() {
        long userA = insertUser("hybrid-a");
        long userB = insertUser("hybrid-b");
        long videoX = insertVideo(userA, "X");
        long videoY = insertVideo(userA, "Y");
        long taskX = insertTask(videoX, "v1");
        long taskY = insertTask(videoY, "v1");

        List<TranscriptChunk> chunks = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            String text = "普通字幕片段 " + index + "，描述系统运行状态。";
            if (index == 3) text = "事务回滚发生在数据库写入失败之后";
            if (index == 7) text = "捕获 NullPointerException 后记录错误类别";
            if (index == 11) text = "UploadCompletionTransaction 保证完成阶段原子性";
            chunks.add(chunk(index, text));
        }
        lexicalStore.replace(userA, videoX, taskX, chunks);
        lexicalStore.replace(userA, videoY, taskY, List.of(chunk(0, "事务回滚只属于另一个视频")));

        VideoRagChunkEntity foreignUser = entity(userB, videoX, taskX, 99, "事务回滚只属于另一个用户");
        assertThat(chunkRepository.insert(foreignUser)).isEqualTo(1);

        assertThat(lexicalStore.search(userA, videoX, "事务回滚", 15))
            .extracting(LexicalChunk::text)
            .contains("事务回滚发生在数据库写入失败之后")
            .doesNotContain("事务回滚只属于另一个视频", "事务回滚只属于另一个用户");
        assertThat(lexicalStore.search(userA, videoX, "NullPointerException", 15))
            .extracting(LexicalChunk::text).contains("捕获 NullPointerException 后记录错误类别");
        assertThat(lexicalStore.search(userA, videoX, "UploadCompletionTransaction", 15))
            .extracting(LexicalChunk::text).contains("UploadCompletionTransaction 保证完成阶段原子性");

        Map<String, Object> explain = jdbcTemplate.queryForMap("""
            EXPLAIN SELECT id FROM video_rag_chunk
            WHERE user_id = ? AND video_id = ?
              AND MATCH(text) AGAINST(? IN NATURAL LANGUAGE MODE)
            ORDER BY MATCH(text) AGAINST(? IN NATURAL LANGUAGE MODE) DESC
            LIMIT 15
            """, userA, videoX, "事务回滚", "事务回滚");
        assertThat(String.valueOf(explain.get("key"))).isEqualTo("ft_rag_chunk_text");
    }

    @Test
    void shouldReplaceStaleTaskChunksAndRemainIdempotent() {
        long user = insertUser("hybrid-lifecycle");
        long video = insertVideo(user, "lifecycle");
        long taskV1 = insertTask(video, "v1");
        long taskV2 = insertTask(video, "v2");
        lexicalStore.replace(user, video, taskV1, List.of(chunk(0, "v1 obsolete marker")));

        List<TranscriptChunk> current = List.of(chunk(0, "v2 current marker"), chunk(1, "v2 current second"));
        lexicalStore.replace(user, video, taskV2, current);
        lexicalStore.replace(user, video, taskV2, current);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM video_rag_chunk WHERE user_id=? AND video_id=?", Integer.class, user, video))
            .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM video_rag_chunk WHERE user_id=? AND video_id=? AND analysis_task_id=?",
            Integer.class, user, video, taskV1)).isZero();
    }

    @Test
    void shouldRunDenseLexicalRrfAndHttpRerankAsOnePipeline() throws Exception {
        long user = insertUser("hybrid-pipeline");
        long video = insertVideo(user, "pipeline");
        long task = insertTask(video, "v1");
        List<TranscriptChunk> chunks = List.of(
            chunk(0, "A semantic database recovery candidate"),
            chunk(1, "B semantic compensation candidate"),
            chunk(2, "C NullPointerException precise marker"),
            chunk(3, "D UploadCompletionTransaction precise marker")
        );
        lexicalStore.replace(user, video, task, chunks);

        vectorStore.ensureCollection(384);
        vectorStore.deleteByVideoStrict(user, video);
        vectorStore.upsertPoints(user, video, task, List.of(
            point(chunks.get(0), vector(1.0f, 0.0f)),
            point(chunks.get(1), vector(0.9f, 0.1f)),
            point(chunks.get(2), vector(0.0f, 1.0f)),
            point(chunks.get(3), vector(0.0f, 1.0f))
        ));
        qdrantIndexes.add(new long[] {user, video});

        AtomicInteger rerankCalls = new AtomicInteger();
        startReranker(rerankCalls);
        RagProperties properties = new RagProperties(8000, 2000, 1, 2, 0.0f, 2, 60, 15, 3,
            new RagProperties.Reranker(true, rerankBaseUrl(), "integration-secret", "fake-reranker",
                Duration.ofSeconds(2)));
        TranscriptRetriever retriever = new TranscriptRetriever(
            fixedEmbeddingProvider(), vectorStore, lexicalStore, new ReciprocalRankFusion(),
            new HttpTranscriptReranker(properties), properties);

        List<RetrievedChunk> result = retriever.retrieve(
            user, video, "NullPointerException UploadCompletionTransaction");

        assertThat(rerankCalls).hasValue(1);
        assertThat(result).hasSize(3);
        assertThat(result).extracting(RetrievedChunk::text).containsExactly(
            "C NullPointerException precise marker",
            "A semantic database recovery candidate",
            "D UploadCompletionTransaction precise marker"
        );
    }

    private void startReranker(AtomicInteger calls) throws Exception {
        rerankServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        rerankServer.createContext("/rerank", exchange -> {
            calls.incrementAndGet();
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            List<Map<String, Object>> results = new ArrayList<>();
            for (int index = 0; index < request.path("documents").size(); index++) {
                String document = request.path("documents").get(index).asText();
                double score = document.startsWith("C ") ? 0.95
                    : document.startsWith("A ") ? 0.70
                    : document.startsWith("D ") ? 0.40 : 0.10;
                results.add(Map.of("index", index, "relevance_score", score));
            }
            byte[] response = objectMapper.writeValueAsBytes(Map.of("results", results));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        rerankServer.start();
    }

    private String rerankBaseUrl() {
        return "http://127.0.0.1:" + rerankServer.getAddress().getPort();
    }

    private EmbeddingProvider fixedEmbeddingProvider() {
        return new EmbeddingProvider() {
            public String providerName() { return "fixed-test"; }
            public int dimension() { return 384; }
            public List<float[]> embedDocuments(List<String> texts) { throw new UnsupportedOperationException(); }
            public float[] embedQuery(String text) { return vector(1.0f, 0.0f); }
        };
    }

    private float[] vector(float first, float second) {
        float[] vector = new float[384];
        vector[0] = first;
        vector[1] = second;
        return vector;
    }

    private VectorPoint point(TranscriptChunk chunk, float[] vector) {
        return new VectorPoint(chunk.chunkIndex(), chunk.text(), chunk.startMs(), chunk.endMs(),
            chunk.sourceSegmentIndexes(), vector, 0.0f);
    }

    private TranscriptChunk chunk(int index, String text) {
        return new TranscriptChunk(index, text, index * 1000L, (index + 1) * 1000L, List.of(index));
    }

    private VideoRagChunkEntity entity(long user, long video, long task, int index, String text) {
        VideoRagChunkEntity entity = new VideoRagChunkEntity();
        entity.setChunkId(video + ":" + task + ":" + index);
        entity.setUserId(user);
        entity.setVideoId(video);
        entity.setAnalysisTaskId(task);
        entity.setChunkIndex(index);
        entity.setText(text);
        entity.setStartMs(index * 1000L);
        entity.setEndMs((index + 1) * 1000L);
        entity.setSourceSegmentIndexes("[" + index + "]");
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

    private long insertUser(String prefix) {
        LocalDateTime now = LocalDateTime.now();
        AppUserEntity user = new AppUserEntity();
        user.setUsername(prefix + "-" + UUID.randomUUID().toString().substring(0, 12));
        user.setPasswordHash("x".repeat(60));
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        assertThat(userRepository.insert(user)).isEqualTo(1);
        users.add(user.getId());
        return user.getId();
    }

    private long insertVideo(long userId, String title) {
        LocalDateTime now = LocalDateTime.now();
        VideoEntity video = new VideoEntity();
        video.setUserId(userId);
        video.setTitle(title);
        video.setOriginalFilename("hybrid.mp4");
        video.setObjectKey("tests/hybrid/" + UUID.randomUUID() + ".mp4");
        video.setFileSize(1L);
        video.setMimeType("video/mp4");
        video.setStatus("UPLOADED");
        video.setCreatedAt(now);
        video.setUpdatedAt(now);
        assertThat(videoRepository.insert(video)).isEqualTo(1);
        videos.add(video.getId());
        return video.getId();
    }

    private long insertTask(long videoId, String modelVersion) {
        LocalDateTime now = LocalDateTime.now();
        AnalysisTaskEntity task = new AnalysisTaskEntity();
        task.setVideoId(videoId);
        task.setAnalysisType("STRUCTURED_SUMMARY");
        task.setModelVersion("hybrid-" + modelVersion);
        task.setStatus("SUCCESS");
        task.setStage("DONE");
        task.setProgress(100);
        task.setRetryCount(0);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        assertThat(taskRepository.insert(task)).isEqualTo(1);
        tasks.add(task.getId());
        return task.getId();
    }

    private long ownerOf(long videoId) {
        return videoRepository.selectById(videoId).getUserId();
    }
}
