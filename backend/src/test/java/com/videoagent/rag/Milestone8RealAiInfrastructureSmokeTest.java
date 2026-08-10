package com.videoagent.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.videoagent.auth.repository.AppUserRepository;
import com.videoagent.rag.dto.QaResponse;
import com.videoagent.rag.dto.RagIndexStatusResponse;
import com.videoagent.testsupport.TestAuthClient;
import com.videoagent.testsupport.TestAuthClient.Session;
import com.videoagent.transcript.entity.VideoTranscriptSegmentEntity;
import com.videoagent.transcript.repository.VideoTranscriptSegmentRepository;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;
import com.videoagent.analysis.repository.AnalysisTaskRepository;

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
 * Real-AI RAG acceptance. Gated by VIDEOAGENT_M8_REAL_AI_TEST=true (default
 * OFF). Requires a real embedding provider and a real LLM configured via
 * environment variables. Never runs as part of the default test suite.
 */
@EnabledIfEnvironmentVariable(named = "VIDEOAGENT_M8_REAL_AI_TEST", matches = "true")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "videoagent.security.jwt.secret=" + TestAuthClient.JWT_SECRET,
        "videoagent.analysis.consumer-group=videoagent-m8-real-${random.uuid}",
        "videoagent.rag.embedding.provider=${EMBEDDING_PROVIDER}",
        "videoagent.rag.embedding.api-key=${EMBEDDING_API_KEY}",
        "videoagent.rag.embedding.base-url=${EMBEDDING_BASE_URL}",
        "videoagent.rag.embedding.model=${EMBEDDING_MODEL}",
        "videoagent.rag.embedding.dimension=${EMBEDDING_DIMENSION}"
    }
)
class Milestone8RealAiInfrastructureSmokeTest {

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
    void shouldAnswerWithRealEmbeddingAndLlmInRagMode() {
        session = TestAuthClient.registerAndLogin(restTemplate, baseUrl(""), "m8-real-" + System.nanoTime());
        long videoId = insertVideo(session, "Real RAG");
        insertTranscript(videoId, session.userId(), longSegments(60));

        ResponseEntity<RagIndexStatusResponse> built = restTemplate.exchange(
            baseUrl("/api/videos/" + videoId + "/rag/index"),
            HttpMethod.POST,
            new HttpEntity<>(session.headers()),
            RagIndexStatusResponse.class
        );
        assertThat(built.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(built.getBody().mode()).isEqualTo("RAG");
        assertThat(built.getBody().status()).isEqualTo("READY");

        ResponseEntity<QaResponse> qa = restTemplate.exchange(
            baseUrl("/api/videos/" + videoId + "/qa"),
            HttpMethod.POST,
            new HttpEntity<>("{\"question\":\"视频中介绍了哪些内容？\"}", jsonHeaders(session)),
            QaResponse.class
        );
        assertThat(qa.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(qa.getBody().mode()).isEqualTo("RAG");
        assertThat(qa.getBody().answer()).isNotBlank();
        assertThat(qa.getBody().citations()).isNotEmpty();
        // Human verification required: answer grounded in transcript, citations
        // fall within real time ranges, no cross-video data, no fabricated
        // citations.
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
        video.setOriginalFilename("m8-real.mp4");
        video.setObjectKey("tests/m8-real/" + UUID.randomUUID() + ".mp4");
        video.setFileSize(40L);
        video.setMimeType("video/mp4");
        video.setFileHash("d".repeat(64));
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
            lines.add("VideoAgent 视频分析系统知识点第 " + i + " 段。" + filler);
        }
        return lines;
    }

    private String baseUrl(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
