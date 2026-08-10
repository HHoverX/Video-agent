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
import com.videoagent.storage.StorageProperties;
import com.videoagent.transcript.dto.TranscriptSegmentResponse;
import com.videoagent.transcript.entity.VideoTranscriptSegmentEntity;
import com.videoagent.transcript.repository.VideoTranscriptSegmentRepository;
import com.videoagent.testsupport.TestAuthClient;
import com.videoagent.testsupport.TestAuthClient.Session;
import com.videoagent.video.dto.VideoUploadResponse;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@EnabledIfEnvironmentVariable(named = "VIDEOAGENT_M4_INFRA_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "videoagent.analysis.consumer-group=videoagent-m4-infra-${random.uuid}",
        "videoagent.analysis.analysis-type=TRANSCRIPTION",
        "videoagent.analysis.model-version=m4-ffmpeg-mock-asr-v1",
        "videoagent.media.ffmpeg-timeout=30s"
    }
)
class MediaTranscriptionInfrastructureIntegrationTest {

    private static final Path MEDIA_ROOT = Path.of(
        "target",
        "m4-integration-media-" + UUID.randomUUID()
    ).toAbsolutePath().normalize();
    private static final byte[] INVALID_MP4 = {
        0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm',
        0, 0, 0, 0, 'i', 's', 'o', 'm', 'm', 'p', '4', '2'
    };

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
    private VideoTranscriptSegmentRepository segmentRepository;

    @Autowired
    private AnalysisTaskProcessor processor;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private StorageProperties storageProperties;

    private final List<Long> videoIds = new ArrayList<>();
    private final List<Long> taskIds = new ArrayList<>();
    private final List<String> objectKeys = new ArrayList<>();
    private Session authSession;

    @AfterEach
    void cleanDatabaseAndStorage() throws Exception {
        for (Long taskId : taskIds) {
            redisTemplate.delete(RedisAnalysisProgressStore.key(taskId));
            taskRepository.deleteById(taskId);
        }
        for (String objectKey : objectKeys) {
            minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(storageProperties.bucket())
                .object(objectKey)
                .build());
        }
        for (Long videoId : videoIds) {
            videoRepository.deleteById(videoId);
        }
        if (authSession != null) {
            userRepository.deleteById(authSession.userId());
            authSession = null;
        }
        taskIds.clear();
        objectKeys.clear();
        videoIds.clear();
    }

    @AfterAll
    void cleanMediaRoot() throws Exception {
        if (!Files.exists(MEDIA_ROOT)) {
            return;
        }
        try (var paths = Files.walk(MEDIA_ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void shouldRunRealMinioFfmpegMockAsrTranscriptPipeline() throws Exception {
        long videoId = uploadVideo(generateValidMp4(), "m4-real-pipeline.mp4", "M4 real pipeline");
        long historicalTaskId = insertHistoricalFrameworkTask(videoId);

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
        long taskId = startResponse.getBody().taskId();
        taskIds.add(taskId);

        AnalysisTaskResponse completed = awaitTerminalTask(taskId);
        assertThat(completed.status()).isEqualTo("SUCCESS");
        assertThat(completed.stage()).isEqualTo("DONE");
        assertThat(completed.progress()).isEqualTo(100);

        AnalysisTaskEntity persisted = taskRepository.selectById(taskId);
        assertThat(persisted.getAnalysisType()).isEqualTo("TRANSCRIPTION");
        assertThat(persisted.getModelVersion()).isEqualTo("m4-ffmpeg-mock-asr-v1");
        assertThat(persisted.getStartedAt()).isNotNull();
        assertThat(persisted.getFinishedAt()).isNotNull();
        AnalysisTaskEntity historical = taskRepository.selectById(historicalTaskId);
        assertThat(historical.getAnalysisType()).isEqualTo("FRAMEWORK");
        assertThat(historical.getModelVersion()).isEqualTo("m3-simulation-v1");
        assertThat(historical.getStatus()).isEqualTo("SUCCESS");

        List<VideoTranscriptSegmentEntity> rows =
            segmentRepository.findLatestSuccessfulByVideoId(videoId);
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(VideoTranscriptSegmentEntity::getSegmentIndex)
            .containsExactly(0, 1, 2);
        assertThat(rows).extracting(VideoTranscriptSegmentEntity::getStartMs)
            .containsExactly(0L, 2_000L, 4_000L);

        ResponseEntity<TranscriptSegmentResponse[]> transcriptResponse = restTemplate.exchange(
            baseUrl("/api/videos/" + videoId + "/transcript"),
            HttpMethod.GET,
            new HttpEntity<>(authSession.headers()),
            TranscriptSegmentResponse[].class
        );
        assertThat(transcriptResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(transcriptResponse.getBody()).isNotNull();
        assertThat(transcriptResponse.getBody()).extracting(TranscriptSegmentResponse::startMs)
            .containsExactly(0L, 2_000L, 4_000L);
        assertThat(transcriptResponse.getBody()).extracting(TranscriptSegmentResponse::text)
            .containsExactly(
                "欢迎使用 VideoAgent。",
                "音频已经通过 FFmpeg 提取。",
                "这是 Mock ASR 生成的带时间戳字幕。"
            );

        String redisKey = RedisAnalysisProgressStore.key(taskId);
        assertThat(redisTemplate.opsForValue().get(redisKey))
            .contains("\"status\":\"SUCCESS\"", "\"progress\":100");
        assertThat(redisTemplate.getExpire(redisKey, TimeUnit.SECONDS))
            .isPositive()
            .isLessThanOrEqualTo(Duration.ofHours(24).toSeconds());

        LocalDateTime updatedBeforeDuplicate = persisted.getUpdatedAt();
        processor.process(new AnalysisMessage(taskId, videoId));
        assertThat(taskRepository.selectById(taskId).getUpdatedAt()).isEqualTo(updatedBeforeDuplicate);
        assertThat(segmentRepository.findLatestSuccessfulByVideoId(videoId)).hasSize(3);

        redisTemplate.delete(redisKey);
        AnalysisTaskResponse mysqlFallback = restTemplate.exchange(
            baseUrl("/api/analysis/" + taskId),
            HttpMethod.GET,
            new HttpEntity<>(authSession.headers()),
            AnalysisTaskResponse.class
        ).getBody();
        assertThat(mysqlFallback).isNotNull();
        assertThat(mysqlFallback.status()).isEqualTo("SUCCESS");
        assertThat(mysqlFallback.progress()).isEqualTo(100);
        assertMediaRootHasNoTaskDirectories();
    }

    @Test
    void shouldMarkTaskFailedAndCleanWorkspaceWhenFfmpegRejectsVideo() throws Exception {
        long videoId = uploadVideo(INVALID_MP4, "m4-invalid.mp4", "M4 invalid FFmpeg input");
        ResponseEntity<StartAnalysisResponse> startResponse = restTemplate.exchange(
            baseUrl("/api/videos/" + videoId + "/analysis"),
            HttpMethod.POST,
            new HttpEntity<>(authSession.headers()),
            StartAnalysisResponse.class
        );
        assertThat(startResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(startResponse.getBody()).isNotNull();
        long taskId = startResponse.getBody().taskId();
        taskIds.add(taskId);

        AnalysisTaskResponse failed = awaitTerminalTask(taskId);

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.stage()).isEqualTo("FAILED");
        assertThat(failed.errorCode()).isEqualTo("FFMPEG_EXECUTION_FAILED");
        assertThat(failed.errorMessage()).contains("FFmpeg 退出码", "stderr=");
        assertThat(segmentRepository.findLatestSuccessfulByVideoId(videoId)).isEmpty();
        assertMediaRootHasNoTaskDirectories();
    }

    private long uploadVideo(byte[] bytes, String filename, String title) {
        if (authSession == null) {
            authSession = TestAuthClient.registerAndLogin(
                restTemplate,
                baseUrl(""),
                "m4-infra-" + System.nanoTime()
            );
        }
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.valueOf("video/mp4"));
        ByteArrayResource file = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(file, fileHeaders));
        body.add("title", title);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(authSession.token());

        ResponseEntity<VideoUploadResponse> response = restTemplate.exchange(
            baseUrl("/api/videos"),
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            VideoUploadResponse.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        long videoId = response.getBody().videoId();
        videoIds.add(videoId);
        VideoEntity video = videoRepository.selectById(videoId);
        assertThat(video).isNotNull();
        objectKeys.add(video.getObjectKey());
        return videoId;
    }

    private long insertHistoricalFrameworkTask(long videoId) {
        LocalDateTime now = LocalDateTime.now();
        AnalysisTaskEntity historical = new AnalysisTaskEntity();
        historical.setVideoId(videoId);
        historical.setAnalysisType("FRAMEWORK");
        historical.setModelVersion("m3-simulation-v1");
        historical.setStatus("SUCCESS");
        historical.setStage("DONE");
        historical.setProgress(100);
        historical.setRetryCount(0);
        historical.setStartedAt(now);
        historical.setFinishedAt(now);
        historical.setCreatedAt(now);
        historical.setUpdatedAt(now);
        assertThat(taskRepository.insert(historical)).isEqualTo(1);
        taskIds.add(historical.getId());
        return historical.getId();
    }

    private AnalysisTaskResponse awaitTerminalTask(long taskId) throws Exception {
        AnalysisTaskResponse task = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            task = restTemplate.exchange(
                baseUrl("/api/analysis/" + taskId),
                HttpMethod.GET,
                new HttpEntity<>(authSession.headers()),
                AnalysisTaskResponse.class
            ).getBody();
            if (task != null && ("SUCCESS".equals(task.status()) || "FAILED".equals(task.status()))) {
                return task;
            }
            Thread.sleep(100);
        }
        return task;
    }

    private byte[] generateValidMp4() throws Exception {
        Path fixture = Files.createTempFile("videoagent-m4-", ".mp4");
        try {
            String executable = System.getenv().getOrDefault("FFMPEG_PATH", "ffmpeg");
            Process process = new ProcessBuilder(
                executable,
                "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "color=c=blue:s=320x180:r=15",
                "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=16000",
                "-t", "6",
                "-c:v", "mpeg4",
                "-c:a", "aac",
                "-shortest",
                fixture.toString()
            ).redirectErrorStream(true).start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            String diagnostics = new String(process.getInputStream().readAllBytes());
            assertThat(finished).as("FFmpeg fixture generation timed out").isTrue();
            assertThat(process.exitValue()).as(diagnostics).isZero();
            return Files.readAllBytes(fixture);
        } finally {
            Files.deleteIfExists(fixture);
        }
    }

    private void assertMediaRootHasNoTaskDirectories() throws Exception {
        if (!Files.exists(MEDIA_ROOT)) {
            return;
        }
        try (var paths = Files.list(MEDIA_ROOT)) {
            assertThat(paths).isEmpty();
        }
    }

    private String baseUrl(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
