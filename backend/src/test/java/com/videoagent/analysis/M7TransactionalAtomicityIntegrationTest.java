package com.videoagent.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.analysis.service.AnalysisCommandService;
import com.videoagent.analysis.service.AnalysisRetryCoordinator;
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
    private AnalysisRetryCoordinator retryCoordinator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AnalysisOutboxEventRepository outboxEventRepository;

    private Session authSession;
    private Long videoId;
    private Long insertedTaskId;

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
        if (insertedTaskId != null) {
            taskRepository.deleteById(insertedTaskId);
            insertedTaskId = null;
        }
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

    @Test
    void shouldRollBackRetryStateWhenRetryOutboxInsertFails() {
        // MEDIUM #8: the RETRY_WAITING transition and the retry outbox INSERT
        // must be one transaction. Insert a PROCESSING task directly, then run
        // the retry coordinator with the outbox mapper forced to fail.
        LocalDateTime now = LocalDateTime.now();
        AnalysisTaskEntity task = new AnalysisTaskEntity();
        task.setVideoId(videoId);
        task.setAnalysisType("STRUCTURED_SUMMARY");
        task.setModelVersion("m5-langchain4j-structured-v1");
        task.setStatus("PROCESSING");
        task.setStage("SUMMARIZING");
        task.setProgress(85);
        task.setRetryCount(1);
        task.setProcessingGeneration(2);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        assertThat(taskRepository.insert(task)).isEqualTo(1);
        Long taskId = task.getId();
        insertedTaskId = taskId;

        doThrow(new IllegalStateException("simulated retry outbox insert failure"))
            .when(outboxEventRepository).insertPendingIfAbsent(anyString(), anyString(), anyLong(), anyLong(), anyString(), any(), any());

        assertThatThrownBy(() -> retryCoordinator.handleRetryableFailure(
            task, "SUMMARIZING", "LLM_SUMMARY_FAILED", "provider unavailable"
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("simulated retry outbox insert failure");

        // The whole transaction must roll back: the task is still PROCESSING at
        // the original generation (not RETRY_WAITING) and no retry event exists.
        AnalysisTaskEntity after = taskRepository.selectById(taskId);
        assertThat(after.getStatus()).isEqualTo("PROCESSING");
        assertThat(after.getStage()).isEqualTo("SUMMARIZING");
        assertThat(after.getProcessingGeneration()).isEqualTo(2);
        assertThat(after.getRetryCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM analysis_outbox_event WHERE task_id = ?",
            Long.class,
            taskId
        )).isZero();
    }

    @Test
    void shouldRollBackUserRetryWhenRetryOutboxInsertFails() {
        LocalDateTime now = LocalDateTime.now();
        AnalysisTaskEntity task = new AnalysisTaskEntity();
        task.setVideoId(videoId);
        task.setAnalysisType("STRUCTURED_SUMMARY");
        task.setModelVersion("m5-langchain4j-structured-v1");
        task.setStatus("FAILED");
        task.setStage("FAILED");
        task.setProgress(85);
        task.setRetryCount(3);
        task.setProcessingGeneration(4);
        task.setErrorCode("LLM_SUMMARY_FAILED");
        task.setErrorMessage("provider unavailable");
        task.setLastErrorCode("LLM_SUMMARY_FAILED");
        task.setLastErrorMessage("provider unavailable");
        task.setLastFailureStage("SUMMARIZING");
        task.setStartedAt(now.minusMinutes(2));
        task.setFinishedAt(now.minusMinutes(1));
        task.setCreatedAt(now.minusMinutes(3));
        task.setUpdatedAt(now.minusMinutes(1));
        assertThat(taskRepository.insert(task)).isEqualTo(1);
        insertedTaskId = task.getId();

        doThrow(new IllegalStateException("simulated user retry outbox insert failure"))
            .when(outboxEventRepository).insertPendingIfAbsent(anyString(), anyString(), anyLong(), anyLong(), anyString(), any(), any());

        assertThatThrownBy(() -> commandService.start(videoId, authSession.userId()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("simulated user retry outbox insert failure");

        AnalysisTaskEntity after = taskRepository.selectById(task.getId());
        assertThat(after.getStatus()).isEqualTo("FAILED");
        assertThat(after.getStage()).isEqualTo("FAILED");
        assertThat(after.getProcessingGeneration()).isEqualTo(4);
        assertThat(after.getRetryCount()).isEqualTo(3);
        assertThat(after.getErrorCode()).isEqualTo("LLM_SUMMARY_FAILED");
        assertThat(after.getFinishedAt()).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM analysis_outbox_event WHERE task_id = ?",
            Long.class,
            task.getId()
        )).isZero();
    }

    private String baseUrl(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
