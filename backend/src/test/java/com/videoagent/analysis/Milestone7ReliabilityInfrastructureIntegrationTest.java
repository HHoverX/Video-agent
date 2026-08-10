package com.videoagent.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.videoagent.analysis.consumer.AnalysisTaskProcessor;
import com.videoagent.analysis.dto.AnalysisMessage;
import com.videoagent.analysis.dto.AnalysisTaskResponse;
import com.videoagent.analysis.entity.AnalysisStatus;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.progress.RedisAnalysisProgressStore;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.analysis.service.AnalysisRecoveryJob;
import com.videoagent.asr.AsrProvider;
import com.videoagent.asr.TranscriptSegment;
import com.videoagent.asr.TranscriptionResult;
import com.videoagent.auth.repository.AppUserRepository;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.media.AudioExtractResult;
import com.videoagent.media.MediaProcessor;
import com.videoagent.storage.ObjectStorageService;
import com.videoagent.summary.provider.SummaryChapter;
import com.videoagent.summary.provider.SummaryKeyPoint;
import com.videoagent.summary.provider.VideoSummaryProvider;
import com.videoagent.summary.provider.VideoSummaryResult;
import com.videoagent.summary.repository.VideoChapterRepository;
import com.videoagent.summary.repository.VideoKeyPointRepository;
import com.videoagent.summary.repository.VideoSummaryRepository;
import com.videoagent.transcript.repository.VideoTranscriptSegmentRepository;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@EnabledIfEnvironmentVariable(named = "VIDEOAGENT_M7_INFRA_TEST", matches = "true")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "videoagent.analysis.consumer-group=videoagent-m7-infra-${random.uuid}",
        "videoagent.analysis.analysis-type=STRUCTURED_SUMMARY",
        "videoagent.analysis.model-version=m5-langchain4j-structured-v1",
        "videoagent.ai.asr.provider=mock",
        "videoagent.ai.llm.provider=mock",
        "videoagent.analysis.reliability.max-attempts=3",
        "videoagent.media.ffmpeg-timeout=30s"
    }
)
class Milestone7ReliabilityInfrastructureIntegrationTest {

    private static final Path MEDIA_ROOT = Path.of(
        "target", "m7-integration-media-" + UUID.randomUUID()
    ).toAbsolutePath().normalize();

    @DynamicPropertySource
    static void mediaProperties(DynamicPropertyRegistry registry) {
        registry.add("videoagent.media.temp-root", () -> MEDIA_ROOT.toString());
        registry.add("videoagent.security.jwt.secret", () -> TestAuthClient.JWT_SECRET);
        registry.add("videoagent.media.ffmpeg-path", () ->
            System.getenv().getOrDefault("FFMPEG_PATH", "ffmpeg")
        );
    }

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
    private VideoTranscriptSegmentRepository transcriptRepository;

    @Autowired
    private VideoSummaryRepository summaryRepository;

    @Autowired
    private VideoChapterRepository chapterRepository;

    @Autowired
    private VideoKeyPointRepository keyPointRepository;

    @Autowired
    private AnalysisTaskProcessor processor;

    @Autowired
    private AnalysisRecoveryJob recoveryJob;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private ObjectStorageService storageService;

    @MockitoBean
    private MediaProcessor mediaProcessor;

    @MockitoBean
    private AsrProvider asrProvider;

    @MockitoBean
    private VideoSummaryProvider summaryProvider;

    private final List<Long> videoIds = new ArrayList<>();
    private final List<Long> taskIds = new ArrayList<>();
    private Session authSession;

    @BeforeEach
    void createUser() {
        authSession = TestAuthClient.registerAndLogin(
            restTemplate,
            baseUrl(""),
            "m7-infra-" + System.nanoTime()
        );
    }

    @AfterEach
    void cleanUp() {
        for (Long taskId : taskIds.reversed()) {
            redisTemplate.delete(RedisAnalysisProgressStore.key(taskId));
            taskRepository.deleteById(taskId);
        }
        for (Long videoId : videoIds) {
            videoRepository.deleteById(videoId);
        }
        if (authSession != null) {
            userRepository.deleteById(authSession.userId());
            authSession = null;
        }
        taskIds.clear();
        videoIds.clear();
    }

    @Test
    void shouldRetryThenSucceedAndNotDuplicateOnDuplicateMessage() throws Exception {
        AtomicInteger asrCalls = new AtomicInteger();
        long videoId = insertVideo();
        long taskId = insertPendingTask(videoId);
        stubBasePipeline(asrCalls, 1);
        stubSummaryAlwaysOk();

        // Attempt 1: ASR transient failure -> RETRY_WAITING (backoff armed).
        processor.process(new AnalysisMessage(taskId, videoId));
        AnalysisTaskEntity retryWaiting = taskRepository.selectById(taskId);
        assertThat(retryWaiting.getStatus()).isEqualTo("RETRY_WAITING");
        assertThat(retryWaiting.getRetryCount()).isEqualTo(1);
        assertThat(retryWaiting.getRetryNotBefore()).isAfter(LocalDateTime.now());

        // A delayed duplicate arriving before retry_not_before must NOT start
        // the next attempt (HIGH #4).
        processor.process(new AnalysisMessage(taskId, videoId));
        assertThat(taskRepository.selectById(taskId).getStatus()).isEqualTo("RETRY_WAITING");

        // Once the backoff elapses, the next delivery is claimable and succeeds.
        clearRetryBackoff(taskId);
        processor.process(new AnalysisMessage(taskId, videoId));
        AnalysisTaskEntity completed = taskRepository.selectById(taskId);
        assertThat(completed.getStatus()).isEqualTo("SUCCESS");
        assertThat(asrCalls.get()).isEqualTo(2);

        // Duplicate MQ message: no-op, no duplicate results.
        processor.process(new AnalysisMessage(taskId, videoId));
        AnalysisTaskEntity afterDuplicate = taskRepository.selectById(taskId);
        assertThat(afterDuplicate.getStatus()).isEqualTo("SUCCESS");
        assertThat(transcriptRepository.findByTaskId(taskId)).hasSize(2);
        assertThat(summaryRepository.countByTaskId(taskId)).isEqualTo(1);
        assertThat(chapterRepository.selectCount(
            com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<com.videoagent.summary.entity.VideoChapterEntity>lambdaQuery()
                .eq(com.videoagent.summary.entity.VideoChapterEntity::getTaskId, taskId)
        )).isEqualTo(1);
        assertThat(keyPointRepository.selectCount(
            com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<com.videoagent.summary.entity.VideoKeyPointEntity>lambdaQuery()
                .eq(com.videoagent.summary.entity.VideoKeyPointEntity::getTaskId, taskId)
        )).isEqualTo(1);
    }

    @Test
    void shouldResumeFromSavedTranscriptWithoutRepeatingAsr() throws Exception {
        AtomicInteger asrCalls = new AtomicInteger();
        AtomicInteger summaryCalls = new AtomicInteger();
        long videoId = insertVideo();
        long taskId = insertPendingTask(videoId);
        stubBasePipeline(asrCalls, 0);
        stubSummaryFailsOnceThenOk(summaryCalls);

        // Attempt 1: ASR works, transcript saved, summary fails once.
        processor.process(new AnalysisMessage(taskId, videoId));
        assertThat(taskRepository.selectById(taskId).getStatus()).isEqualTo("RETRY_WAITING");
        assertThat(asrCalls.get()).isEqualTo(1);
        assertThat(transcriptRepository.findByTaskId(taskId)).hasSize(2);

        // Attempt 2: transcript resume -> ASR not invoked again.
        clearRetryBackoff(taskId);
        processor.process(new AnalysisMessage(taskId, videoId));
        assertThat(asrCalls.get()).isEqualTo(1);
        assertThat(summaryCalls.get()).isEqualTo(2);
        assertThat(taskRepository.selectById(taskId).getStatus()).isEqualTo("SUCCESS");
        assertThat(summaryRepository.countByTaskId(taskId)).isEqualTo(1);
    }

    @Test
    void shouldRecoverStaleProcessingWithNewGenerationAndFenceOldWorker() throws Exception {
        AtomicInteger asrCalls = new AtomicInteger();
        long videoId = insertVideo();
        long taskId = insertPendingTask(videoId);
        stubBasePipeline(asrCalls, 0);
        stubSummaryAlwaysOk();

        // Simulate a crashed worker that claimed 30 minutes ago (stale).
        LocalDateTime crashedAt = LocalDateTime.now().minusMinutes(30);
        assertThat(taskRepository.claimPending(taskId, "TRANSCRIBING", 70, crashedAt)).isEqualTo(1);
        AnalysisTaskEntity processing = taskRepository.selectById(taskId);
        assertThat(processing.getStatus()).isEqualTo("PROCESSING");
        assertThat(processing.getProcessingGeneration()).isEqualTo(1);

        // The old worker's writes must be fenced out (0 affected rows).
        int fencedWrite = taskRepository.updateProcessingProgress(
            taskId, "TRANSCRIBING", 70, processing.getProcessingGeneration(), crashedAt
        );
        // This succeeds because the old generation still matches. The fencing
        // happens only AFTER the recovery bumps the generation.
        assertThat(fencedWrite).isEqualTo(1);

        // Recovery reclaims the stale task to RETRY_WAITING with a new generation.
        recoveryJob.recoverStaleProcessingTasks();
        AnalysisTaskEntity recovered = taskRepository.selectById(taskId);
        assertThat(recovered.getStatus()).isEqualTo("RETRY_WAITING");
        assertThat(recovered.getProcessingGeneration()).isEqualTo(2);
        assertThat(recovered.getRetryCount()).isEqualTo(1);

        // The old worker now writes with generation 1 -> affectedRows 0.
        int fencedAfter = taskRepository.updateProcessingProgress(
            taskId, "TRANSCRIBING", 70, 1, crashedAt
        );
        assertThat(fencedAfter).isZero();

        // New worker (generation 2) completes the task.
        clearRetryBackoff(taskId);
        processor.process(new AnalysisMessage(taskId, videoId));
        AnalysisTaskEntity finalState = taskRepository.selectById(taskId);
        assertThat(finalState.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void shouldFailStaleProcessingWhenMaxAttemptsReached() throws Exception {
        long videoId = insertVideo();
        long taskId = insertPendingTask(videoId);
        // Set the task so that it has already used its full retry budget:
        // retry_count=2 with maxAttempts=3 means no budget left for a 3rd.
        AnalysisTaskEntity task = taskRepository.selectById(taskId);
        task.setRetryCount(2);
        task.setProcessingGeneration(1);
        task.setStatus("PROCESSING");
        task.setStage("TRANSCRIBING");
        task.setUpdatedAt(LocalDateTime.now().minusHours(1));
        assertThat(taskRepository.updateById(task)).isEqualTo(1);

        recoveryJob.recoverStaleProcessingTasks();

        AnalysisTaskEntity failed = taskRepository.selectById(taskId);
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getRetryCount()).isEqualTo(3);
    }

    @Test
    void shouldNotRetryProviderRejectionAndFailImmediately() throws Exception {
        long videoId = insertVideo();
        long taskId = insertPendingTask(videoId);
        stubBasePipeline(new AtomicInteger(), 0);
        when(summaryProvider.summarize(any())).thenThrow(
            new VideoAgentException(ErrorCode.LLM_PROVIDER_REJECTED, "invalid api key")
        );

        processor.process(new AnalysisMessage(taskId, videoId));

        AnalysisTaskEntity failed = taskRepository.selectById(taskId);
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getErrorCode()).isEqualTo("LLM_PROVIDER_REJECTED");
        assertThat(failed.getRetryCount()).isZero();
    }

    @Test
    void shouldMarkFailedWhenMaxAnalysisAttemptsReached() throws Exception {
        AtomicInteger asrCalls = new AtomicInteger();
        long videoId = insertVideo();
        long taskId = insertPendingTask(videoId);
        stubBasePipeline(asrCalls, 0);
        stubSummaryAlwaysOk();

        doAnswer(invocation -> {
            throw new VideoAgentException(ErrorCode.ASR_TIMEOUT, "always timing out");
        }).when(asrProvider).transcribe(any());

        processor.process(new AnalysisMessage(taskId, videoId));
        assertThat(taskRepository.selectById(taskId).getStatus()).isEqualTo("RETRY_WAITING");

        clearRetryBackoff(taskId);
        processor.process(new AnalysisMessage(taskId, videoId));
        assertThat(taskRepository.selectById(taskId).getStatus()).isEqualTo("RETRY_WAITING");

        clearRetryBackoff(taskId);
        processor.process(new AnalysisMessage(taskId, videoId));
        AnalysisTaskEntity finalState = taskRepository.selectById(taskId);
        assertThat(finalState.getStatus()).isEqualTo("FAILED");
        assertThat(finalState.getErrorCode()).isEqualTo("ASR_TIMEOUT");
    }

    @Test
    void shouldExposeTerminalStateOverGet() throws Exception {
        long videoId = insertVideo();
        long taskId = insertPendingTask(videoId);
        stubBasePipeline(new AtomicInteger(), 0);
        when(summaryProvider.summarize(any())).thenThrow(
            new VideoAgentException(ErrorCode.ASR_INPUT_TOO_LARGE, "audio too large")
        );

        processor.process(new AnalysisMessage(taskId, videoId));

        ResponseEntity<AnalysisTaskResponse> response = restTemplate.exchange(
            baseUrl("/api/analysis/" + taskId),
            HttpMethod.GET,
            new HttpEntity<>(authSession.headers()),
            AnalysisTaskResponse.class
        );
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("FAILED");
        assertThat(response.getBody().errorCode()).isEqualTo("ASR_INPUT_TOO_LARGE");
    }

    /**
     * Directly clears the retry_not_before backoff so a test can immediately
     * exercise the next delivery without waiting out the real backoff.
     */
    private void clearRetryBackoff(long taskId) {
        AnalysisTaskEntity task = taskRepository.selectById(taskId);
        task.setRetryNotBefore(LocalDateTime.now().minusSeconds(1));
        task.setUpdatedAt(LocalDateTime.now());
        assertThat(taskRepository.updateById(task)).isEqualTo(1);
    }

    private void stubBasePipeline(AtomicInteger asrCalls, int asrFailuresBeforeSuccess) throws Exception {
        doAnswer(invocation -> {
            Path destination = invocation.getArgument(1);
            Files.write(destination, new byte[] {1, 2, 3});
            return null;
        }).when(storageService).downloadObject(any(String.class), any(Path.class));
        when(mediaProcessor.extractAudio(any(Path.class), any(Path.class))).thenAnswer(invocation -> {
            Path audio = invocation.getArgument(1);
            Files.write(audio, new byte[] {4, 5, 6});
            return new AudioExtractResult(audio, 3L);
        });
        when(asrProvider.transcribe(any())).thenAnswer(invocation -> {
            int call = asrCalls.incrementAndGet();
            if (call <= asrFailuresBeforeSuccess) {
                throw new VideoAgentException(ErrorCode.ASR_TIMEOUT, "ASR temporarily unavailable");
            }
            return new TranscriptionResult(List.of(
                new TranscriptSegment(0, 2_000, "segment one"),
                new TranscriptSegment(2_000, 4_000, "segment two")
            ));
        });
    }

    private void stubSummaryAlwaysOk() {
        when(summaryProvider.summarize(any())).thenReturn(summaryResult());
    }

    private void stubSummaryFailsOnceThenOk(AtomicInteger summaryCalls) {
        when(summaryProvider.summarize(any())).thenAnswer(invocation -> {
            int call = summaryCalls.incrementAndGet();
            if (call == 1) {
                throw new VideoAgentException(ErrorCode.LLM_SUMMARY_FAILED, "LLM temporarily unavailable");
            }
            return summaryResult();
        });
    }

    private VideoSummaryResult summaryResult() {
        return new VideoSummaryResult(
            "overview",
            List.of(new SummaryChapter("chapter", "chapter summary", 0, 4_000)),
            List.of(new SummaryKeyPoint("point", 0, 2_000))
        );
    }

    private long insertVideo() {
        LocalDateTime now = LocalDateTime.now();
        VideoEntity video = new VideoEntity();
        video.setUserId(authSession.userId());
        video.setTitle("M7 reliability video");
        video.setOriginalFilename("m7-reliability.mp4");
        video.setObjectKey("tests/m7/" + UUID.randomUUID() + ".mp4");
        video.setFileSize(40L);
        video.setMimeType("video/mp4");
        video.setFileHash("c".repeat(64));
        video.setStatus("UPLOADED");
        video.setCreatedAt(now);
        video.setUpdatedAt(now);
        assertThat(videoRepository.insert(video)).isEqualTo(1);
        videoIds.add(video.getId());
        return video.getId();
    }

    private long insertPendingTask(long videoId) {
        LocalDateTime now = LocalDateTime.now();
        AnalysisTaskEntity task = new AnalysisTaskEntity();
        task.setVideoId(videoId);
        task.setAnalysisType("STRUCTURED_SUMMARY");
        task.setModelVersion("m5-langchain4j-structured-v1");
        task.setStatus(AnalysisStatus.PENDING.name());
        task.setStage("QUEUED");
        task.setProgress(0);
        task.setRetryCount(0);
        task.setProcessingGeneration(0);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        assertThat(taskRepository.insert(task)).isEqualTo(1);
        taskIds.add(task.getId());
        return task.getId();
    }

    private String baseUrl(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
