package com.videoagent.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.analysis.service.AnalysisCommandService;
import com.videoagent.auth.repository.AppUserRepository;
import com.videoagent.outbox.repository.AnalysisOutboxEventRepository;
import com.videoagent.testsupport.TestAuthClient;
import com.videoagent.testsupport.TestAuthClient.Session;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Real-MySQL transactional-atomicity tests for CRITICAL #1 (task + initial
 * dispatch outbox event in one transaction). The outbox INSERT failure is
 * injected at the mapper boundary inside a real Spring transaction; because
 * AnalysisCommandService.start is @Transactional, the task INSERT must roll
 * back with it.
 */
@EnabledIfEnvironmentVariable(named = "VIDEOAGENT_M7_INFRA_TEST", matches = "true")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "videoagent.security.jwt.secret=" + TestAuthClient.JWT_SECRET,
        "videoagent.analysis.consumer-group=videoagent-m7-atomic-${random.uuid}",
        "videoagent.ai.asr.provider=mock",
        "videoagent.ai.llm.provider=mock"
    }
)
class M7TransactionalAtomicityIntegrationTest {

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
    private AnalysisCommandService commandService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AnalysisOutboxEventRepository outboxEventRepository;

    private Session authSession;
    private Long videoId;

    @BeforeEach
    void setUp() {
        authSession = TestAuthClient.registerAndLogin(
            restTemplate,
            baseUrl(""),
            "m7-atomic-" + System.nanoTime()
        );
        LocalDateTime now = LocalDateTime.now();
        VideoEntity video = new VideoEntity();
        video.setUserId(authSession.userId());
        video.setTitle("M7 atomic video");
        video.setOriginalFilename("m7-atomic.mp4");
        video.setObjectKey("tests/m7-atomic/" + UUID.randomUUID() + ".mp4");
        video.setFileSize(40L);
        video.setMimeType("video/mp4");
        video.setFileHash("b".repeat(64));
        video.setStatus("UPLOADED");
        video.setCreatedAt(now);
        video.setUpdatedAt(now);
        assertThat(videoRepository.insert(video)).isEqualTo(1);
        videoId = video.getId();
    }

    @AfterEach
    void cleanUp() {
        if (videoId != null) {
            videoRepository.deleteById(videoId);
        }
        if (authSession != null) {
            userRepository.deleteById(authSession.userId());
            authSession = null;
        }
    }

    @Test
    void shouldRollBackTaskWhenInitialOutboxInsertFails() {
        // The outbox mapper fails to insert, which must roll back the task too.
        doThrow(new IllegalStateException("simulated outbox insert failure"))
            .when(outboxEventRepository).insertPendingIfAbsent(anyString(), anyString(), anyLong(), anyLong(), anyString(), any(), any());

        assertThatThrownBy(() -> commandService.start(videoId, authSession.userId()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("simulated outbox insert failure");

        // CRITICAL #1: both inserts must have rolled back.
        assertThat(taskRepository.findByBusinessKey(
            videoId, "STRUCTURED_SUMMARY", "m5-langchain4j-structured-v1"
        )).isNull();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM analysis_outbox_event WHERE task_id IN (SELECT id FROM analysis_task WHERE video_id = ?)",
            Long.class,
            videoId
        )).isZero();
    }

    private String baseUrl(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
