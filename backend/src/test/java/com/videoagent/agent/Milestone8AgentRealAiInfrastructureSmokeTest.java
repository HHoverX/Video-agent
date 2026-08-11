package com.videoagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.videoagent.agent.dto.AgenticQaResponse;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.auth.repository.AppUserRepository;
import com.videoagent.testsupport.TestAuthClient;
import com.videoagent.testsupport.TestAuthClient.Session;
import com.videoagent.transcript.entity.VideoTranscriptSegmentEntity;
import com.videoagent.transcript.repository.VideoTranscriptSegmentRepository;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;

import org.junit.jupiter.api.AfterEach;
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
 * Real-AI agentic retrieval acceptance. Gated by
 * VIDEOAGENT_M8_AGENT_REAL_AI_TEST=true (default OFF). Uses the real DeepSeek
 * planner + real embedding + Qdrant + real DeepSeek synthesizer. Never runs as
 * part of the default test suite.
 */
@EnabledIfEnvironmentVariable(named = "VIDEOAGENT_M8_AGENT_REAL_AI_TEST", matches = "true")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "videoagent.security.jwt.secret=" + TestAuthClient.JWT_SECRET,
        "videoagent.analysis.consumer-group=videoagent-m8-agent-real-${random.uuid}",
        "videoagent.agent.planner-provider=llm",
        "videoagent.rag.embedding.provider=${EMBEDDING_PROVIDER}",
        "videoagent.rag.embedding.api-key=${EMBEDDING_API_KEY}",
        "videoagent.rag.embedding.base-url=${EMBEDDING_BASE_URL}",
        "videoagent.rag.embedding.model=${EMBEDDING_MODEL}",
        "videoagent.rag.embedding.dimension=${EMBEDDING_DIMENSION}"
    }
)
class Milestone8AgentRealAiInfrastructureSmokeTest {

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

    private final List<Long> videoIds = new ArrayList<>();
    private final List<Long> taskIds = new ArrayList<>();
    private Session session;

    @AfterEach
    void cleanUp() {
        for (Long taskId : taskIds.reversed()) {
            taskRepository.deleteById(taskId);
        }
        for (Long videoId : videoIds) {
            videoRepository.deleteById(videoId);
        }
        if (session != null) {
            userRepository.deleteById(session.userId());
            session = null;
        }
        taskIds.clear();
        videoIds.clear();
    }

    @Test
    void shouldRunAgenticRetrievalWithRealProviders() {
        session = TestAuthClient.registerAndLogin(restTemplate, baseUrl(""), "m8-agent-real-" + System.nanoTime());
        long videoId = insertVideo(session, "Real Agent RAG");
        insertTranscript(videoId, session.userId(), longSegments(300));

        // Build the real embedding + Qdrant index.
        ResponseEntity<String> built = restTemplate.exchange(
            baseUrl("/api/videos/" + videoId + "/rag/index"),
            HttpMethod.POST,
            new HttpEntity<>(session.headers()),
            String.class
        );
        assertThat(built.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Semantic question: the answer must be grounded and cited.
        AgenticQaResponse semantic = askAgentic(videoId, session,
            "为什么选择 Redis 作为任务进度缓存？");
        System.out.println("=== M8.2 REAL AI SEMANTIC ===");
        System.out.println("strategy=" + semantic.strategy() + " tools=" + semantic.toolsUsed());
        System.out.println("answer=" + semantic.answer());
        for (var c : semantic.citations()) {
            System.out.println("citation=[" + c.startMs() + "," + c.endMs() + "] " + c.text());
        }
        assertThat(semantic.citations()).isNotEmpty();

        // Time question: must not use search; citation covers a real time range.
        AgenticQaResponse time = askAgentic(videoId, session, "3分20秒附近讲了什么？");
        System.out.println("=== M8.2 REAL AI TIME ===");
        System.out.println("strategy=" + time.strategy() + " tools=" + time.toolsUsed());
        System.out.println("answer=" + time.answer());
        for (var c : time.citations()) {
            System.out.println("citation=[" + c.startMs() + "," + c.endMs() + "]");
        }
        // Human verification required: tool choice reasonable, query
        // decomposition sensible, answer grounded, citations valid, no
        // cross-video/user content, no fabricated timestamp.
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
        video.setOriginalFilename("m8-agent-real.mp4");
        video.setObjectKey("tests/m8-agent-real/" + UUID.randomUUID() + ".mp4");
        video.setFileSize(40L);
        video.setMimeType("video/mp4");
        video.setFileHash("f".repeat(64));
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

    private List<String> longSegments(int count) {
        List<String> lines = new ArrayList<>();
        String filler = "详细说明。".repeat(80);
        for (int i = 0; i < count; i++) {
            if (i == 190) {
                // Redis fact inside the time window [185,215] so the time
                // lookup evidence (top-12 segments) includes it.
                lines.add("选择 Redis 作为任务进度缓存，是因为 Redis 读写延迟低、支持过期时间，"
                    + "并且能够在多实例间共享实时进度。这是本视频的核心结论。");
            } else if (i == 300) {
                lines.add("RocketMQ 用于异步任务的消息投递与解耦。");
            } else {
                lines.add("VideoAgent 视频分析系统知识点第 " + i + " 段。" + filler);
            }
        }
        return lines;
    }

    private String baseUrl(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
