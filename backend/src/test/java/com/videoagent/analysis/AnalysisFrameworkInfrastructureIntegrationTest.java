package com.videoagent.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.videoagent.analysis.consumer.AnalysisTaskProcessor;
import com.videoagent.analysis.dto.AnalysisMessage;
import com.videoagent.analysis.dto.AnalysisTaskResponse;
import com.videoagent.analysis.dto.StartAnalysisResponse;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.progress.RedisAnalysisProgressStore;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.auth.repository.AppUserRepository;
import com.videoagent.asr.AsrProvider;
import com.videoagent.asr.TranscriptSegment;
import com.videoagent.asr.TranscriptionResult;
import com.videoagent.media.AudioExtractResult;
import com.videoagent.media.MediaProcessor;
import com.videoagent.storage.ObjectStorageService;
import com.videoagent.transcript.service.TranscriptService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "VIDEOAGENT_M3_INFRA_TEST", matches = "true")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "videoagent.analysis.consumer-group=videoagent-m3-infra-${random.uuid}",
        "videoagent.analysis.analysis-type=FRAMEWORK",
        "videoagent.analysis.model-version=m3-simulation-v1",
        "videoagent.media.temp-root=target/m3-integration-media",
        "videoagent.security.jwt.secret=" + TestAuthClient.JWT_SECRET
    }
)
class AnalysisFrameworkInfrastructureIntegrationTest {

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
    private AnalysisTaskProcessor processor;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private ObjectStorageService storageService;

    @MockitoBean
    private MediaProcessor mediaProcessor;

    @MockitoBean
    private AsrProvider asrProvider;

    @MockitoBean
    private TranscriptService transcriptService;

    private Long videoId;
    private Long taskId;
    private Session authSession;

    @BeforeEach
    void createVideo() throws Exception {
        authSession = TestAuthClient.registerAndLogin(
            restTemplate,
            baseUrl(""),
            "m3-infra-" + System.nanoTime()
        );
        doAnswer(invocation -> {
            Path destination = invocation.getArgument(1);
            Files.write(destination, new byte[] {1, 2, 3});
            Thread.sleep(120);
            return null;
        }).when(storageService).downloadObject(any(String.class), any(Path.class));
        when(mediaProcessor.extractAudio(any(Path.class), any(Path.class))).thenAnswer(invocation -> {
            Path audio = invocation.getArgument(1);
            Files.write(audio, new byte[] {4, 5, 6});
            Thread.sleep(120);
            return new AudioExtractResult(audio, 3L);
        });
        when(asrProvider.transcribe(any())).thenAnswer(invocation -> {
            Thread.sleep(120);
            return new TranscriptionResult(List.of(
                new TranscriptSegment(0, 2_000, "M3 regression segment")
            ));
        });
        doAnswer(invocation -> {
            Thread.sleep(120);
            return null;
        }).when(transcriptService).replaceTaskSegments(any(), any());

        LocalDateTime now = LocalDateTime.now();
        VideoEntity video = new VideoEntity();
        video.setUserId(authSession.userId());
        video.setTitle("M3 integration video");
        video.setOriginalFilename("m3-integration.mp4");
        video.setObjectKey("tests/m3/" + UUID.randomUUID() + ".mp4");
        video.setFileSize(40L);
        video.setMimeType("video/mp4");
        video.setFileHash("0".repeat(64));
        video.setStatus("UPLOADED");
        video.setCreatedAt(now);
        video.setUpdatedAt(now);
        assertThat(videoRepository.insert(video)).isEqualTo(1);
        videoId = video.getId();
    }

    @AfterEach
    void cleanUp() {
        if (taskId != null) {
            redisTemplate.delete(RedisAnalysisProgressStore.key(taskId));
            taskRepository.deleteById(taskId);
        }
        if (videoId != null) {
            AnalysisTaskEntity task = taskRepository.findByBusinessKey(
                videoId, "FRAMEWORK", "m3-simulation-v1"
            );
            if (task != null) {
                redisTemplate.delete(RedisAnalysisProgressStore.key(task.getId()));
                taskRepository.deleteById(task.getId());
            }
            videoRepository.deleteById(videoId);
        }
        if (authSession != null) {
            userRepository.deleteById(authSession.userId());
        }
    }

    @Test
    void shouldDispatchConsumeTrackProgressAndFallBackToMysql() throws Exception {
        long startedNanos = System.nanoTime();
        ResponseEntity<StartAnalysisResponse> startResponse = restTemplate.exchange(
            baseUrl("/api/videos/" + videoId + "/analysis"),
            HttpMethod.POST,
            new HttpEntity<>(authSession.headers()),
            StartAnalysisResponse.class
        );
        Duration requestDuration = Duration.ofNanos(System.nanoTime() - startedNanos);

        assertThat(startResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(startResponse.getBody()).isNotNull();
        assertThat(startResponse.getBody().status()).isEqualTo("PENDING");
        assertThat(requestDuration).isLessThan(Duration.ofSeconds(3));
        taskId = startResponse.getBody().taskId();

        AnalysisTaskEntity pending = taskRepository.selectById(taskId);
        assertThat(pending).isNotNull();
        assertThat(pending.getStatus()).isIn("PENDING", "PROCESSING");
        assertThat(pending.getProgress()).isBetween(0, 90);

        ResponseEntity<String> duplicateResponse = restTemplate.exchange(
            baseUrl("/api/videos/" + videoId + "/analysis"),
            HttpMethod.POST,
            new HttpEntity<>(authSession.headers()),
            String.class
        );
        assertThat(duplicateResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicateResponse.getBody()).contains("ANALYSIS_ALREADY_RUNNING");

        Set<Integer> observedProgress = new HashSet<>();
        Set<String> observedRedisSnapshots = new HashSet<>();
        observedProgress.add(0);
        AnalysisTaskResponse current = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            current = restTemplate.exchange(
                baseUrl("/api/analysis/" + taskId),
                HttpMethod.GET,
                new HttpEntity<>(authSession.headers()),
                AnalysisTaskResponse.class
            ).getBody();
            assertThat(current).isNotNull();
            observedProgress.add(current.progress());
            String redisSnapshot = redisTemplate.opsForValue().get(
                RedisAnalysisProgressStore.key(taskId)
            );
            if (redisSnapshot != null) {
                observedRedisSnapshots.add(redisSnapshot);
            }
            if ("SUCCESS".equals(current.status())) {
                break;
            }
            Thread.sleep(50);
        }

        assertThat(current).isNotNull();
        assertThat(current.status()).isEqualTo("SUCCESS");
        assertThat(current.stage()).isEqualTo("DONE");
        assertThat(current.progress()).isEqualTo(100);
        assertThat(observedProgress).anyMatch(progress -> progress > 0 && progress < 100);
        assertThat(observedRedisSnapshots).anyMatch(snapshot ->
            snapshot.contains("\"status\":\"PROCESSING\"")
                && !snapshot.contains("\"progress\":100")
        );

        AnalysisTaskEntity persisted = taskRepository.selectById(taskId);
        assertThat(persisted.getStatus()).isEqualTo("SUCCESS");
        assertThat(persisted.getStage()).isEqualTo("DONE");
        assertThat(persisted.getProgress()).isEqualTo(100);
        assertThat(persisted.getStartedAt()).isNotNull();
        assertThat(persisted.getFinishedAt()).isNotNull();

        String redisKey = RedisAnalysisProgressStore.key(taskId);
        assertThat(redisTemplate.opsForValue().get(redisKey)).contains("\"status\":\"SUCCESS\"");
        Long ttlSeconds = redisTemplate.getExpire(redisKey, TimeUnit.SECONDS);
        assertThat(ttlSeconds).isPositive().isLessThanOrEqualTo(Duration.ofHours(24).toSeconds());

        LocalDateTime updatedBeforeDuplicate = persisted.getUpdatedAt();
        processor.process(new AnalysisMessage(taskId, videoId));
        AnalysisTaskEntity afterDuplicate = taskRepository.selectById(taskId);
        assertThat(afterDuplicate.getUpdatedAt()).isEqualTo(updatedBeforeDuplicate);
        assertThat(afterDuplicate.getStatus()).isEqualTo("SUCCESS");

        redisTemplate.delete(redisKey);
        AnalysisTaskResponse fallback = restTemplate.exchange(
            baseUrl("/api/analysis/" + taskId),
            HttpMethod.GET,
            new HttpEntity<>(authSession.headers()),
            AnalysisTaskResponse.class
        ).getBody();
        assertThat(fallback).isNotNull();
        assertThat(fallback.status()).isEqualTo("SUCCESS");
        assertThat(fallback.progress()).isEqualTo(100);
        assertThat(fallback.message()).isEqualTo("分析完成");
    }

    @Test
    void shouldRejectAnalysisForMissingVideo() {
        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl("/api/videos/999999999/analysis"),
            HttpMethod.POST,
            new HttpEntity<>(authSession.headers()),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("VIDEO_NOT_FOUND");
    }

    private String baseUrl(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
