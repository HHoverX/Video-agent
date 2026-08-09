package com.videoagent.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.videoagent.analysis.consumer.AnalysisTaskProcessor;
import com.videoagent.analysis.dto.AnalysisMessage;
import com.videoagent.analysis.dto.AnalysisTaskResponse;
import com.videoagent.analysis.dto.StartAnalysisResponse;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.progress.RedisAnalysisProgressStore;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.storage.StorageProperties;
import com.videoagent.summary.dto.VideoChapterResponse;
import com.videoagent.summary.dto.VideoKeyPointResponse;
import com.videoagent.summary.dto.VideoSummaryResponse;
import com.videoagent.summary.entity.VideoSummaryEntity;
import com.videoagent.summary.repository.VideoChapterRepository;
import com.videoagent.summary.repository.VideoKeyPointRepository;
import com.videoagent.summary.repository.VideoSummaryRepository;
import com.videoagent.transcript.dto.TranscriptSegmentResponse;
import com.videoagent.transcript.repository.VideoTranscriptSegmentRepository;
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

@EnabledIfEnvironmentVariable(named = "VIDEOAGENT_M5_INFRA_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "videoagent.analysis.consumer-group=videoagent-m5-infra-${random.uuid}",
        "videoagent.analysis.analysis-type=STRUCTURED_SUMMARY",
        "videoagent.analysis.model-version=m5-langchain4j-structured-v1",
        "videoagent.ai.llm.provider=mock",
        "videoagent.media.ffmpeg-timeout=30s"
    }
)
class StructuredSummaryInfrastructureIntegrationTest {

    private static final Path MEDIA_ROOT = Path.of(
        "target",
        "m5-integration-media-" + UUID.randomUUID()
    ).toAbsolutePath().normalize();

    @DynamicPropertySource
    static void mediaProperties(DynamicPropertyRegistry registry) {
        registry.add("videoagent.media.temp-root", () -> MEDIA_ROOT.toString());
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
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private StorageProperties storageProperties;

    private final List<Long> videoIds = new ArrayList<>();
    private final List<Long> taskIds = new ArrayList<>();
    private final List<String> objectKeys = new ArrayList<>();

    @AfterEach
    void cleanDatabaseAndStorage() throws Exception {
        for (Long taskId : taskIds.reversed()) {
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
    void shouldRunFullStructuredSummaryPipelineAndPreserveHistory() throws Exception {
        long videoId = uploadVideo(generateValidMp4());
        long m3TaskId = insertHistoricalTask(videoId, "FRAMEWORK", "m3-simulation-v1");
        long m4TaskId = insertHistoricalTask(videoId, "TRANSCRIPTION", "m4-ffmpeg-mock-asr-v1");

        long startedNanos = System.nanoTime();
        ResponseEntity<StartAnalysisResponse> started = restTemplate.postForEntity(
            baseUrl("/api/videos/" + videoId + "/analysis"),
            null,
            StartAnalysisResponse.class
        );
        Duration requestDuration = Duration.ofNanos(System.nanoTime() - startedNanos);

        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(started.getBody()).isNotNull();
        assertThat(started.getBody().status()).isEqualTo("PENDING");
        assertThat(requestDuration).isLessThan(Duration.ofSeconds(3));
        long taskId = started.getBody().taskId();
        taskIds.add(taskId);

        AnalysisTaskEntity pending = taskRepository.selectById(taskId);
        assertThat(pending).isNotNull();
        assertThat(pending.getAnalysisType()).isEqualTo("STRUCTURED_SUMMARY");
        assertThat(pending.getModelVersion()).isEqualTo("m5-langchain4j-structured-v1");

        AnalysisTaskResponse completed = awaitTerminalTask(taskId);
        assertThat(completed.status()).isEqualTo("SUCCESS");
        assertThat(completed.stage()).isEqualTo("DONE");
        assertThat(completed.progress()).isEqualTo(100);

        assertThat(taskRepository.selectById(m3TaskId).getModelVersion())
            .isEqualTo("m3-simulation-v1");
        assertThat(taskRepository.selectById(m4TaskId).getModelVersion())
            .isEqualTo("m4-ffmpeg-mock-asr-v1");

        assertThat(transcriptRepository.findLatestSuccessfulByVideoId(videoId))
            .hasSize(3);
        assertThat(chapterRepository.findLatestSuccessfulByVideoId(videoId))
            .extracting(entity -> entity.getChapterIndex())
            .containsExactly(0, 1);
        assertThat(keyPointRepository.findLatestSuccessfulByVideoId(videoId))
            .extracting(entity -> entity.getPointIndex())
            .containsExactly(0, 1, 2);

        ResponseEntity<TranscriptSegmentResponse[]> transcriptResponse = restTemplate.getForEntity(
            baseUrl("/api/videos/" + videoId + "/transcript"),
            TranscriptSegmentResponse[].class
        );
        ResponseEntity<VideoSummaryResponse> summaryResponse = restTemplate.getForEntity(
            baseUrl("/api/videos/" + videoId + "/summary"),
            VideoSummaryResponse.class
        );
        ResponseEntity<VideoChapterResponse[]> chaptersResponse = restTemplate.getForEntity(
            baseUrl("/api/videos/" + videoId + "/chapters"),
            VideoChapterResponse[].class
        );
        ResponseEntity<VideoKeyPointResponse[]> pointsResponse = restTemplate.getForEntity(
            baseUrl("/api/videos/" + videoId + "/key-points"),
            VideoKeyPointResponse[].class
        );

        assertThat(transcriptResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(transcriptResponse.getBody()).hasSize(3);
        assertThat(summaryResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(summaryResponse.getBody()).isNotNull();
        assertThat(summaryResponse.getBody().taskId()).isEqualTo(taskId);
        assertThat(summaryResponse.getBody().overview()).contains("欢迎使用 VideoAgent");
        assertThat(chaptersResponse.getBody()).extracting(VideoChapterResponse::chapterIndex)
            .containsExactly(0, 1);
        assertThat(chaptersResponse.getBody()).extracting(VideoChapterResponse::startMs)
            .containsExactly(0L, 4_000L);
        assertThat(pointsResponse.getBody()).extracting(VideoKeyPointResponse::pointIndex)
            .containsExactly(0, 1, 2);
        assertThat(pointsResponse.getBody()).extracting(VideoKeyPointResponse::startMs)
            .containsExactly(0L, 2_000L, 4_000L);

        String redisKey = RedisAnalysisProgressStore.key(taskId);
        assertThat(redisTemplate.opsForValue().get(redisKey))
            .contains("\"status\":\"SUCCESS\"", "\"progress\":100");
        assertThat(redisTemplate.getExpire(redisKey, TimeUnit.SECONDS))
            .isPositive()
            .isLessThanOrEqualTo(Duration.ofHours(24).toSeconds());

        AnalysisTaskEntity beforeDuplicate = taskRepository.selectById(taskId);
        VideoSummaryEntity summaryBeforeDuplicate =
            summaryRepository.findLatestSuccessfulByVideoId(videoId);
        processor.process(new AnalysisMessage(taskId, videoId));
        assertThat(taskRepository.selectById(taskId).getUpdatedAt())
            .isEqualTo(beforeDuplicate.getUpdatedAt());
        assertThat(summaryRepository.findLatestSuccessfulByVideoId(videoId).getId())
            .isEqualTo(summaryBeforeDuplicate.getId());

        redisTemplate.delete(redisKey);
        AnalysisTaskResponse mysqlFallback = restTemplate.getForObject(
            baseUrl("/api/analysis/" + taskId),
            AnalysisTaskResponse.class
        );
        assertThat(mysqlFallback).isNotNull();
        assertThat(mysqlFallback.status()).isEqualTo("SUCCESS");
        assertThat(mysqlFallback.progress()).isEqualTo(100);
        assertMediaRootHasNoTaskDirectories();
    }

    private long uploadVideo(byte[] bytes) {
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.valueOf("video/mp4"));
        ByteArrayResource file = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "m5-real-pipeline.mp4";
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(file, fileHeaders));
        body.add("title", "M5 structured summary pipeline");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

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

    private long insertHistoricalTask(long videoId, String type, String modelVersion) {
        LocalDateTime now = LocalDateTime.now();
        AnalysisTaskEntity historical = new AnalysisTaskEntity();
        historical.setVideoId(videoId);
        historical.setAnalysisType(type);
        historical.setModelVersion(modelVersion);
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
            task = restTemplate.getForObject(
                baseUrl("/api/analysis/" + taskId),
                AnalysisTaskResponse.class
            );
            if (task != null && ("SUCCESS".equals(task.status()) || "FAILED".equals(task.status()))) {
                return task;
            }
            Thread.sleep(100);
        }
        return task;
    }

    private byte[] generateValidMp4() throws Exception {
        Path fixture = Files.createTempFile("videoagent-m5-", ".mp4");
        try {
            String executable = System.getenv().getOrDefault("FFMPEG_PATH", "ffmpeg");
            Process process = new ProcessBuilder(
                executable,
                "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "color=c=purple:s=320x180:r=15",
                "-f", "lavfi", "-i", "sine=frequency=720:sample_rate=16000",
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
