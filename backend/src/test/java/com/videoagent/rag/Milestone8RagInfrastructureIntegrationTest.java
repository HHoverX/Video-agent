package com.videoagent.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.auth.repository.AppUserRepository;
import com.videoagent.rag.dto.QaResponse;
import com.videoagent.rag.dto.RagIndexStatusResponse;
import com.videoagent.rag.entity.VideoRagIndexEntity;
import com.videoagent.rag.repository.VideoRagIndexRepository;
import com.videoagent.rag.vector.QdrantVectorStore;
import com.videoagent.testsupport.TestAuthClient;
import com.videoagent.testsupport.TestAuthClient.Session;
import com.videoagent.transcript.entity.VideoTranscriptSegmentEntity;
import com.videoagent.transcript.repository.VideoTranscriptSegmentRepository;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M8.1 RAG infrastructure acceptance. Uses real MySQL, Redis, RocketMQ, MinIO,
 * FFmpeg and Qdrant with Mock ASR / Mock Summary / Mock Embedding / Mock QA.
 *
 * PATH A (short transcript): DIRECT_CONTEXT, rag/status = NOT_REQUIRED, no
 * Qdrant vectors, QA answers from the full transcript with timestamp citations.
 *
 * PATH B (long transcript): RAG, NOT_BUILT -> build -> READY -> retrieval ->
 * grounded QA with chunk citations. Also verifies user isolation (A cannot
 * reach B's index / QA and Qdrant never returns B's chunks for A's query).
 */
@EnabledIfEnvironmentVariable(named = "VIDEOAGENT_M8_RAG_INFRA_TEST", matches = "true")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "videoagent.security.jwt.secret=" + TestAuthClient.JWT_SECRET,
        "videoagent.analysis.consumer-group=videoagent-m8-rag-${random.uuid}",
        "videoagent.ai.asr.provider=mock",
        "videoagent.ai.llm.provider=mock",
        "videoagent.rag.embedding.provider=mock",
        "videoagent.rag.embedding.dimension=384",
        "videoagent.rag.direct-context-max-chars=8000",
        "videoagent.rag.chunk-max-chars=4000"
    }
)
class Milestone8RagInfrastructureIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private AnalysisTaskRepository taskRepository;

    @Autowired
    private VideoTranscriptSegmentRepository segmentRepository;

    @Autowired
    private VideoRagIndexRepository ragIndexRepository;

    @Autowired
    private QdrantVectorStore vectorStore;

    private final List<Long> videoIds = new ArrayList<>();
    private final List<Long> taskIds = new ArrayList<>();
    private final Map<Long, Long> indexedVideoOwners = new LinkedHashMap<>();
    private Session userA;
    private Session userB;

    @BeforeEach
    void setUp() {
        userA = TestAuthClient.registerAndLogin(restTemplate, baseUrl(""), "m8-a-" + System.nanoTime());
        userB = TestAuthClient.registerAndLogin(restTemplate, baseUrl(""), "m8-b-" + System.nanoTime());
    }

    @AfterEach
    void cleanUp() {
        indexedVideoOwners.forEach((videoId, userId) -> vectorStore.deleteByVideo(userId, videoId));
        for (Long taskId : taskIds.reversed()) {
            taskRepository.deleteById(taskId);
        }
        for (Long videoId : videoIds) {
            videoRepository.deleteById(videoId);
        }
        if (userA != null) {
            userRepository.deleteById(userA.userId());
            userA = null;
        }
        if (userB != null) {
            userRepository.deleteById(userB.userId());
            userB = null;
        }
        taskIds.clear();
        videoIds.clear();
        indexedVideoOwners.clear();
    }

    @Test
    void pathAShortTranscriptUsesDirectContextWithoutVectors() {
        long videoId = insertVideo(userA, "Short video");
        insertTranscript(videoId, userA.userId(), shortSegments());

        RagIndexStatusResponse status = ragStatus(videoId, userA);
        assertThat(status.mode()).isEqualTo("DIRECT_CONTEXT");
        assertThat(status.status()).isEqualTo("NOT_REQUIRED");

        // Build must not create vectors for DIRECT_CONTEXT.
        RagIndexStatusResponse afterBuild = ragBuild(videoId, userA);
        assertThat(afterBuild.status()).isEqualTo("NOT_REQUIRED");
        VideoRagIndexEntity index = ragIndexRepository.findByVideoId(videoId);
        assertThat(index).isNotNull();
        assertThat(index.getChunkCount()).isZero();

        QaResponse qa = askQa(videoId, userA, "Redis 用于什么？");
        assertThat(qa.mode()).isEqualTo("DIRECT_CONTEXT");
        assertThat(qa.answer()).contains("Redis");
        assertThat(qa.citations()).isNotEmpty();
        assertThat(qa.citations().getFirst().startMs()).isGreaterThanOrEqualTo(0);
        assertThat(qa.citations().getFirst().endMs()).isGreaterThan(0);
    }

    @Test
    void pathBLongTranscriptBuildsRagIndexAndAnswersWithIsolation() {
        long videoA = insertVideo(userA, "Long A");
        insertTranscript(videoA, userA.userId(), longSegments(40, "USER_A_ONLY_REDIS"));
        long videoB = insertVideo(userB, "Long B");
        insertTranscript(videoB, userB.userId(), longSegments(40, "USER_B_ONLY_ROCKETMQ"));

        // B's video should be isolated from A's index operations. The status
        // endpoint for a foreign video must return 404 (resource hidden).
        ResponseEntity<String> foreign = restTemplate.exchange(
            baseUrl("/api/videos/" + videoA + "/rag/status"),
            HttpMethod.GET,
            new HttpEntity<>(userB.headers()),
            String.class
        );
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Build A's index.
        RagIndexStatusResponse built = ragBuild(videoA, userA);
        assertThat(built.mode()).isEqualTo("RAG");
        assertThat(built.status()).isEqualTo("READY");
        assertThat(built.chunkCount()).isGreaterThan(0);

        RagIndexStatusResponse builtB = ragBuild(videoB, userB);
        assertThat(builtB.status()).isEqualTo("READY");
        assertThat(builtB.chunkCount()).isGreaterThan(0);

        // QA in RAG mode.
        QaResponse qa = askQa(videoA, userA, "Redis 用来做什么？");
        assertThat(qa.mode()).isEqualTo("RAG");
        assertThat(qa.answer()).isNotBlank();
        assertThat(qa.citations()).isNotEmpty();

        // Cross-user QA must be 404.
        ResponseEntity<String> foreignQa = restTemplate.exchange(
            baseUrl("/api/videos/" + videoA + "/qa"),
            HttpMethod.POST,
            new HttpEntity<>("{\"question\":\"内容是什么？\"}", jsonHeaders(userB)),
            String.class
        );
        assertThat(foreignQa.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // A's Qdrant search must not return B's chunks (vector isolation).
        java.util.List<com.videoagent.rag.vector.VectorPoint> aHits = vectorStore.search(
            userA.userId(), videoA, new float[384], 5
        );
        // A's video has its own chunks; nothing from B should appear because the
        // filter is userId+videoId.
        assertThat(aHits).isNotEmpty();
        assertThat(aHits).allSatisfy(hit -> {
            assertThat(hit.text()).contains("USER_A_ONLY_REDIS");
            assertThat(hit.text()).doesNotContain("USER_B_ONLY_ROCKETMQ");
        });
    }

    private RagIndexStatusResponse ragStatus(long videoId, Session session) {
        ResponseEntity<RagIndexStatusResponse> response = restTemplate.exchange(
            baseUrl("/api/videos/" + videoId + "/rag/status"),
            HttpMethod.GET,
            new HttpEntity<>(session.headers()),
            RagIndexStatusResponse.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private RagIndexStatusResponse ragBuild(long videoId, Session session) {
        ResponseEntity<RagIndexStatusResponse> response = restTemplate.exchange(
            baseUrl("/api/videos/" + videoId + "/rag/index"),
            HttpMethod.POST,
            new HttpEntity<>(session.headers()),
            RagIndexStatusResponse.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        if (response.getBody() != null && "READY".equals(response.getBody().status())) {
            indexedVideoOwners.put(videoId, session.userId());
        }
        return response.getBody();
    }

    private QaResponse askQa(long videoId, Session session, String question) {
        ResponseEntity<QaResponse> response = restTemplate.exchange(
            baseUrl("/api/videos/" + videoId + "/qa"),
            HttpMethod.POST,
            new HttpEntity<>("{\"question\":\"" + question + "\"}", jsonHeaders(session)),
            QaResponse.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private HttpHeaders jsonHeaders(Session session) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(session.token());
        return headers;
    }

    private long insertVideo(Session owner, String title) {
        LocalDateTime now = LocalDateTime.now();
        VideoEntity video = new VideoEntity();
        video.setUserId(owner.userId());
        video.setTitle(title);
        video.setOriginalFilename("m8-" + UUID.randomUUID() + ".mp4");
        video.setObjectKey("tests/m8/" + UUID.randomUUID() + ".mp4");
        video.setFileSize(40L);
        video.setMimeType("video/mp4");
        video.setFileHash("a".repeat(64));
        video.setStatus("UPLOADED");
        video.setCreatedAt(now);
        video.setUpdatedAt(now);
        assertThat(videoRepository.insert(video)).isEqualTo(1);
        videoIds.add(video.getId());
        return video.getId();
    }

    private void insertTranscript(long videoId, long userId, List<String> lines) {
        LocalDateTime now = LocalDateTime.now();
        com.videoagent.analysis.entity.AnalysisTaskEntity task = new com.videoagent.analysis.entity.AnalysisTaskEntity();
        task.setVideoId(videoId);
        task.setAnalysisType("STRUCTURED_SUMMARY");
        task.setModelVersion("m5-langchain4j-structured-v1");
        task.setStatus("SUCCESS");
        task.setStage("DONE");
        task.setProgress(100);
        task.setRetryCount(0);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        assertThat(taskRepository.insert(task)).isEqualTo(1);
        taskIds.add(task.getId());

        for (int i = 0; i < lines.size(); i++) {
            VideoTranscriptSegmentEntity segment = new VideoTranscriptSegmentEntity();
            segment.setVideoId(videoId);
            segment.setTaskId(task.getId());
            segment.setSegmentIndex(i);
            segment.setStartMs(i * 1000L);
            segment.setEndMs((i + 1) * 1000L);
            segment.setText(lines.get(i));
            segment.setCreatedAt(now);
            assertThat(segmentRepository.insert(segment)).isEqualTo(1);
        }
    }

    private List<String> shortSegments() {
        return List.of(
            "Redis 用于缓存任务进度。",
            "RocketMQ 用于异步消息投递。",
            "MySQL 保存业务状态。"
        );
    }

    private List<String> longSegments(int count, String topic) {
        List<String> lines = new ArrayList<>();
        String filler = "详细说明。".repeat(60);
        for (int i = 0; i < count; i++) {
            lines.add(topic + " 相关知识点说明，第 " + i + " 段。" + filler);
        }
        return lines;
    }

    private String baseUrl(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
