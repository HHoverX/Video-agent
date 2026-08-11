package com.videoagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.videoagent.agent.dto.AgenticCitation;
import com.videoagent.agent.dto.AgenticQaResponse;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.auth.repository.AppUserRepository;
import com.videoagent.rag.entity.VideoRagIndexEntity;
import com.videoagent.rag.repository.VideoRagIndexRepository;
import com.videoagent.rag.vector.QdrantVectorStore;
import com.videoagent.summary.entity.VideoChapterEntity;
import com.videoagent.summary.entity.VideoKeyPointEntity;
import com.videoagent.summary.entity.VideoSummaryEntity;
import com.videoagent.summary.repository.VideoChapterRepository;
import com.videoagent.summary.repository.VideoKeyPointRepository;
import com.videoagent.summary.repository.VideoSummaryRepository;
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
import java.util.List;
import java.util.UUID;

/**
 * M8.2 Agentic Retrieval infrastructure acceptance. Real MySQL/Redis/RocketMQ/
 * MinIO/FFmpeg/Qdrant with Mock ASR/Summary/Embedding/Planner/Answer.
 *
 * PATH A — summary question: GET_VIDEO_SUMMARY works even when RAG is NOT_BUILT
 *          and does not touch Qdrant/embedding.
 * PATH B — time question: GET_TRANSCRIPT_BY_TIME returns the real segment.
 * PATH C — semantic question (RAG READY): SEARCH_TRANSCRIPT returns evidence.
 * PATH D — multi-search: two SEARCH_TRANSCRIPT actions, both executed.
 * PATH E — cross-user isolation: user B cannot agentic-QA user A's video.
 */
@EnabledIfEnvironmentVariable(named = "VIDEOAGENT_M8_AGENT_INFRA_TEST", matches = "true")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "videoagent.security.jwt.secret=" + TestAuthClient.JWT_SECRET,
        "videoagent.analysis.consumer-group=videoagent-m8-agent-${random.uuid}",
        "videoagent.ai.asr.provider=mock",
        "videoagent.ai.llm.provider=mock",
        "videoagent.rag.embedding.provider=mock",
        "videoagent.rag.embedding.dimension=384",
        "videoagent.agent.planner-provider=mock",
        "videoagent.agent.max-tool-calls=4",
        "videoagent.rag.direct-context-max-chars=8000"
    }
)
class Milestone8AgentInfrastructureIntegrationTest {

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
    private VideoSummaryRepository summaryRepository;

    @Autowired
    private VideoChapterRepository chapterRepository;

    @Autowired
    private VideoKeyPointRepository keyPointRepository;

    @Autowired
    private VideoRagIndexRepository ragIndexRepository;

    @Autowired
    private QdrantVectorStore vectorStore;

    private final List<Long> videoIds = new ArrayList<>();
    private final List<Long> taskIds = new ArrayList<>();
    private Session userA;
    private Session userB;

    @BeforeEach
    void setUp() {
        userA = TestAuthClient.registerAndLogin(restTemplate, baseUrl(""), "m8a-" + System.nanoTime());
        userB = TestAuthClient.registerAndLogin(restTemplate, baseUrl(""), "m8b-" + System.nanoTime());
    }

    @AfterEach
    void cleanUp() {
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
    }

    @Test
    void pathASummaryQuestionWorksWithoutRagOrQdrant() {
        long videoId = insertVideo(userA, "Long A");
        insertTranscript(videoId, userA.userId(), longSegments(300, "Redis"));
        insertSummary(videoId, userA.userId());
        // RAG index is NOT_BUILT by default (long transcript, no build).

        AgenticQaResponse response = askAgentic(videoId, userA, "这个视频主要讲了什么？");

        assertThat(response.strategy()).isEqualTo("SUMMARY");
        assertThat(response.toolsUsed()).containsExactly("GET_VIDEO_SUMMARY");
        assertThat(response.answer()).contains("Redis");
        // Summary evidence has no fabricated timestamp: the citation carries
        // null startMs/endMs.
        assertThat(response.citations()).hasSize(1);
        assertThat(response.citations().getFirst().startMs()).isNull();
        assertThat(response.citations().getFirst().endMs()).isNull();
        // The summary path must not build a RAG index; the RAG status stays
        // NOT_BUILT (no Qdrant vectors were created for this video).
        VideoRagIndexEntity index = ragIndexRepository.findByVideoId(videoId);
        if (index != null) {
            assertThat(index.getStatus()).isEqualTo("NOT_BUILT");
        }
    }

    @Test
    void pathBTimeQuestionReturnsCorrectSegment() {
        long videoId = insertVideo(userA, "Long B");
        insertTranscript(videoId, userA.userId(), longSegments(300, "Redis"));

        AgenticQaResponse response = askAgentic(videoId, userA, "3分20秒在讲什么？");

        assertThat(response.strategy()).isEqualTo("TIME_LOOKUP");
        assertThat(response.toolsUsed()).containsExactly("GET_TRANSCRIPT_BY_TIME");
        // 3:20 = 200000ms; window 15000 => [185000, 215000] covers segment 200s.
        assertThat(response.citations()).isNotEmpty();
        AgenticCitation first = response.citations().getFirst();
        assertThat(first.startMs()).isLessThanOrEqualTo(215_000L);
        assertThat(first.endMs()).isGreaterThanOrEqualTo(185_000L);
    }

    @Test
    void pathCSemanticQuestionUsesSearchWhenRagReady() {
        long videoId = insertVideo(userA, "Long C");
        insertTranscript(videoId, userA.userId(), longSegments(300, "Redis"));
        // Build the RAG index so SEARCH_TRANSCRIPT can use Qdrant.
        buildRagIndex(videoId, userA);

        AgenticQaResponse response = askAgentic(videoId, userA, "为什么使用 Redis 保存进度？");

        assertThat(response.strategy()).isEqualTo("SEMANTIC_SEARCH");
        assertThat(response.toolsUsed()).containsExactly("SEARCH_TRANSCRIPT");
        assertThat(response.citations()).isNotEmpty();
    }

    @Test
    void pathDMultiSearchExecutesBothQueries() {
        long videoId = insertVideo(userA, "Long D");
        insertTranscript(videoId, userA.userId(), longSegments(300, "Redis"));
        buildRagIndex(videoId, userA);

        AgenticQaResponse response = askAgentic(videoId, userA, "比较视频中 Redis 和 RocketMQ 的作用。");

        assertThat(response.strategy()).isEqualTo("MULTI_SEARCH");
        assertThat(response.toolsUsed()).hasSize(2);
        assertThat(response.toolsUsed()).allMatch("SEARCH_TRANSCRIPT"::equals);
        // Both search actions must produce evidence (deduplicated); at least one
        // citation is returned for the best-matching evidence.
        assertThat(response.citations()).isNotEmpty();
    }

    @Test
    void pathECrossUserAgenticQaIs404() {
        long videoId = insertVideo(userA, "User A video");
        insertTranscript(videoId, userA.userId(), longSegments(300, "Redis"));

        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl("/api/videos/" + videoId + "/qa/agentic"),
            HttpMethod.POST,
            new HttpEntity<>("{\"question\":\"内容是什么？\"}", jsonHeaders(userB)),
            String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private AgenticQaResponse askAgentic(long videoId, Session session, String question) {
        ResponseEntity<AgenticQaResponse> response = restTemplate.exchange(
            baseUrl("/api/videos/" + videoId + "/qa/agentic"),
            HttpMethod.POST,
            new HttpEntity<>("{\"question\":\"" + question + "\"}", jsonHeaders(session)),
            AgenticQaResponse.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private void buildRagIndex(long videoId, Session session) {
        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl("/api/videos/" + videoId + "/rag/index"),
            HttpMethod.POST,
            new HttpEntity<>(session.headers()),
            String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
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
        video.setOriginalFilename("m8-agent.mp4");
        video.setObjectKey("tests/m8-agent/" + UUID.randomUUID() + ".mp4");
        video.setFileSize(40L);
        video.setMimeType("video/mp4");
        video.setFileHash("e".repeat(64));
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

    private void insertSummary(long videoId, long userId) {
        LocalDateTime now = LocalDateTime.now();
        Long taskId = taskIds.getFirst();
        VideoSummaryEntity summary = new VideoSummaryEntity();
        summary.setVideoId(videoId);
        summary.setTaskId(taskId);
        summary.setOverview("视频主要讲解 Redis 的作用。");
        summary.setCreatedAt(now);
        summary.setUpdatedAt(now);
        assertThat(summaryRepository.insert(summary)).isEqualTo(1);

        VideoChapterEntity chapter = new VideoChapterEntity();
        chapter.setVideoId(videoId);
        chapter.setTaskId(taskId);
        chapter.setChapterIndex(0);
        chapter.setTitle("Redis 章节");
        chapter.setSummary("介绍 Redis 缓存进度。");
        chapter.setStartMs(0L);
        chapter.setEndMs(10_000L);
        assertThat(chapterRepository.insert(chapter)).isEqualTo(1);

        VideoKeyPointEntity point = new VideoKeyPointEntity();
        point.setVideoId(videoId);
        point.setTaskId(taskId);
        point.setPointIndex(0);
        point.setContent("Redis 用于缓存任务进度。");
        point.setStartMs(0L);
        point.setEndMs(10_000L);
        assertThat(keyPointRepository.insert(point)).isEqualTo(1);
    }

    private List<String> longSegments(int count, String topic) {
        List<String> lines = new ArrayList<>();
        String filler = "详细说明。".repeat(60);
        for (int i = 0; i < count; i++) {
            lines.add(topic + " 相关知识点第 " + i + " 段。" + filler);
        }
        return lines;
    }

    private String baseUrl(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
