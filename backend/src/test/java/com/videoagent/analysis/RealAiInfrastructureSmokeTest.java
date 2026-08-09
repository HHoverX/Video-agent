package com.videoagent.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.videoagent.analysis.dto.AnalysisTaskResponse;
import com.videoagent.analysis.dto.StartAnalysisResponse;
import com.videoagent.analysis.progress.RedisAnalysisProgressStore;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.storage.StorageProperties;
import com.videoagent.summary.dto.VideoChapterResponse;
import com.videoagent.summary.dto.VideoKeyPointResponse;
import com.videoagent.summary.dto.VideoSummaryResponse;
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
import org.junit.jupiter.api.condition.EnabledIf;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@EnabledIf("realAiEnvironmentReady")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealAiInfrastructureSmokeTest {

    private static final Path MEDIA_ROOT = Path.of(
        "target",
        "m6-5-real-ai-media-" + UUID.randomUUID()
    ).toAbsolutePath().normalize();

    @DynamicPropertySource
    static void realAiProperties(DynamicPropertyRegistry registry) {
        registry.add("videoagent.analysis.consumer-group", () ->
            "videoagent-m6-5-real-ai-" + UUID.randomUUID()
        );
        registry.add("videoagent.analysis.analysis-type", () -> "STRUCTURED_SUMMARY");
        registry.add("videoagent.analysis.model-version", () -> "m6.5-real-ai-v1");
        registry.add("videoagent.media.temp-root", () -> MEDIA_ROOT.toString());
        registry.add("videoagent.media.ffmpeg-path", () -> envOrDefault("FFMPEG_PATH", "ffmpeg"));
        registry.add("videoagent.media.ffmpeg-timeout", () -> "60s");

        registry.add("videoagent.ai.asr.provider", () -> "dashscope");
        registry.add("videoagent.ai.asr.api-key", () -> System.getenv("ASR_API_KEY"));
        registry.add("videoagent.ai.asr.model", () ->
            envOrDefault("ASR_MODEL", "fun-asr-flash-2026-06-15")
        );
        registry.add("videoagent.ai.asr.base-url", () ->
            envOrDefault(
                "ASR_BASE_URL",
                "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
            )
        );
        registry.add("videoagent.ai.asr.timeout", () -> envOrDefault("ASR_TIMEOUT", "60s"));

        registry.add("videoagent.ai.llm.provider", () -> "openai");
        registry.add("videoagent.ai.llm.api-key", () -> System.getenv("LLM_API_KEY"));
        registry.add("videoagent.ai.llm.model", () ->
            envOrDefault("LLM_MODEL", "deepseek-v4-flash")
        );
        registry.add("videoagent.ai.llm.base-url", () ->
            envOrDefault("LLM_BASE_URL", "https://api.deepseek.com")
        );
        registry.add("videoagent.ai.llm.timeout", () -> envOrDefault("LLM_TIMEOUT", "60s"));
        registry.add("videoagent.ai.llm.max-retries", () -> "0");
        registry.add("videoagent.ai.llm.structured-output-mode", () -> "json_object");
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
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private StorageProperties storageProperties;

    private final List<Long> videoIds = new ArrayList<>();
    private final List<Long> taskIds = new ArrayList<>();
    private final List<String> objectKeys = new ArrayList<>();

    static boolean realAiEnvironmentReady() {
        if (!"true".equalsIgnoreCase(System.getenv("VIDEOAGENT_REAL_AI_TEST"))) {
            return false;
        }
        String videoPath = System.getenv("VIDEOAGENT_REAL_AI_VIDEO");
        try {
            return hasText(System.getenv("ASR_API_KEY"))
                && hasText(System.getenv("LLM_API_KEY"))
                && hasText(System.getenv("VIDEOAGENT_REAL_AI_EXPECTED_TEXT"))
                && hasText(videoPath)
                && Files.isRegularFile(Path.of(videoPath).toAbsolutePath().normalize());
        } catch (RuntimeException exception) {
            return false;
        }
    }

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
        for (Long videoId : videoIds.reversed()) {
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
    void shouldRunUploadedVideoThroughDashScopeAndDeepSeek() throws Exception {
        Path videoPath = Path.of(System.getenv("VIDEOAGENT_REAL_AI_VIDEO"))
            .toAbsolutePath()
            .normalize();
        long videoId = uploadVideo(videoPath);

        ResponseEntity<StartAnalysisResponse> started = restTemplate.postForEntity(
            baseUrl("/api/videos/" + videoId + "/analysis"),
            null,
            StartAnalysisResponse.class
        );
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(started.getBody()).isNotNull();
        long taskId = started.getBody().taskId();
        taskIds.add(taskId);

        AnalysisTaskResponse completed = awaitTerminalTask(taskId);
        assertThat(completed).isNotNull();
        assertThat(completed.status())
            .withFailMessage(
                "Real AI task failed: code=%s, message=%s",
                completed.errorCode(),
                completed.errorMessage()
            )
            .isEqualTo("SUCCESS");
        assertThat(completed.stage()).isEqualTo("DONE");
        assertThat(completed.progress()).isEqualTo(100);

        assertThat(transcriptRepository.findLatestSuccessfulByVideoId(videoId)).isNotEmpty();
        ResponseEntity<TranscriptSegmentResponse[]> transcript = restTemplate.getForEntity(
            baseUrl("/api/videos/" + videoId + "/transcript"),
            TranscriptSegmentResponse[].class
        );
        assertThat(transcript.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(transcript.getBody()).isNotEmpty();
        String transcriptText = String.join(
            "",
            java.util.Arrays.stream(transcript.getBody())
                .map(TranscriptSegmentResponse::text)
                .toList()
        );
        assertThat(normalizeSpeech(transcriptText)).contains(
            normalizeSpeech(System.getenv("VIDEOAGENT_REAL_AI_EXPECTED_TEXT"))
        );

        ResponseEntity<VideoSummaryResponse> summary = restTemplate.getForEntity(
            baseUrl("/api/videos/" + videoId + "/summary"),
            VideoSummaryResponse.class
        );
        ResponseEntity<VideoChapterResponse[]> chapters = restTemplate.getForEntity(
            baseUrl("/api/videos/" + videoId + "/chapters"),
            VideoChapterResponse[].class
        );
        ResponseEntity<VideoKeyPointResponse[]> keyPoints = restTemplate.getForEntity(
            baseUrl("/api/videos/" + videoId + "/key-points"),
            VideoKeyPointResponse[].class
        );
        assertThat(summary.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(summary.getBody()).isNotNull();
        assertThat(summary.getBody().overview()).isNotBlank();
        assertThat(chapters.getBody()).isNotEmpty();
        assertThat(keyPoints.getBody()).isNotEmpty();
        assertThat(redisTemplate.opsForValue().get(RedisAnalysisProgressStore.key(taskId)))
            .contains("\"status\":\"SUCCESS\"", "\"progress\":100");
        assertMediaRootHasNoTaskDirectories();
    }

    private long uploadVideo(Path videoPath) throws Exception {
        byte[] bytes = Files.readAllBytes(videoPath);
        assertThat(bytes).isNotEmpty();
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.valueOf("video/mp4"));
        ByteArrayResource file = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return videoPath.getFileName().toString();
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(file, fileHeaders));
        body.add("title", "M6.5 real AI smoke test");
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

    private AnalysisTaskResponse awaitTerminalTask(long taskId) throws Exception {
        AnalysisTaskResponse task = null;
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (System.nanoTime() < deadline) {
            task = restTemplate.getForObject(
                baseUrl("/api/analysis/" + taskId),
                AnalysisTaskResponse.class
            );
            if (task != null && ("SUCCESS".equals(task.status()) || "FAILED".equals(task.status()))) {
                return task;
            }
            Thread.sleep(250);
        }
        return task;
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

    private static String normalizeSpeech(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return hasText(value) ? value.strip() : fallback;
    }
}
