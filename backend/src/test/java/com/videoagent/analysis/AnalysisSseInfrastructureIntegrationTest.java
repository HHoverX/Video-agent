package com.videoagent.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.analysis.dto.AnalysisProgressEventResponse;
import com.videoagent.analysis.dto.AnalysisTaskResponse;
import com.videoagent.analysis.dto.StartAnalysisResponse;
import com.videoagent.analysis.progress.RedisAnalysisProgressStore;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.auth.repository.AppUserRepository;
import com.videoagent.storage.StorageProperties;
import com.videoagent.summary.dto.VideoChapterResponse;
import com.videoagent.summary.dto.VideoKeyPointResponse;
import com.videoagent.summary.dto.VideoSummaryResponse;
import com.videoagent.transcript.dto.TranscriptSegmentResponse;
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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@EnabledIfEnvironmentVariable(named = "VIDEOAGENT_M6_INFRA_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "videoagent.analysis.consumer-group=videoagent-m6-infra-${random.uuid}",
        "videoagent.analysis.analysis-type=STRUCTURED_SUMMARY",
        "videoagent.analysis.model-version=m5-langchain4j-structured-v1",
        "videoagent.analysis.sse.timeout=45s",
        "videoagent.ai.llm.provider=mock",
        "videoagent.media.ffmpeg-timeout=30s"
    }
)
class AnalysisSseInfrastructureIntegrationTest {

    private static final Path MEDIA_ROOT = Path.of(
        "target", "m6-integration-media-" + UUID.randomUUID()
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
    private ObjectMapper objectMapper;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private AnalysisTaskRepository taskRepository;

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
    void shouldStreamRealPipelineAndFallBackToMysqlAfterRedisLoss() throws Exception {
        long videoId = uploadVideo(generateValidMp4());
        ResponseEntity<StartAnalysisResponse> started = restTemplate.exchange(
            baseUrl("/api/videos/" + videoId + "/analysis"),
            HttpMethod.POST,
            new HttpEntity<>(authSession.headers()),
            StartAnalysisResponse.class
        );
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(started.getBody()).isNotNull();
        long taskId = started.getBody().taskId();
        taskIds.add(taskId);

        SseCapture capture = collectEvents(taskId);

        assertThat(capture.contentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(capture.events()).isNotEmpty();
        assertThat(capture.events()).extracting(AnalysisProgressEventResponse::taskId)
            .containsOnly(taskId);
        assertThat(capture.events()).extracting(AnalysisProgressEventResponse::stage)
            .contains(
                "PREPARING",
                "EXTRACTING_AUDIO",
                "TRANSCRIBING",
                "SUMMARIZING",
                "SAVING",
                "DONE"
            );
        assertThat(capture.events().getLast().status()).isEqualTo("SUCCESS");
        assertThat(capture.events().getLast().progress()).isEqualTo(100);

        AnalysisTaskResponse queried = restTemplate.exchange(
            baseUrl("/api/analysis/" + taskId),
            HttpMethod.GET,
            new HttpEntity<>(authSession.headers()),
            AnalysisTaskResponse.class
        ).getBody();
        assertThat(queried).isNotNull();
        assertThat(queried.status()).isEqualTo("SUCCESS");
        assertThat(restTemplate.exchange(
            baseUrl("/api/videos/" + videoId + "/transcript"),
            HttpMethod.GET,
            new HttpEntity<>(authSession.headers()),
            TranscriptSegmentResponse[].class
        ).getBody()).isNotEmpty();
        assertThat(restTemplate.exchange(
            baseUrl("/api/videos/" + videoId + "/summary"),
            HttpMethod.GET,
            new HttpEntity<>(authSession.headers()),
            VideoSummaryResponse.class
        ).getBody()).isNotNull();
        assertThat(restTemplate.exchange(
            baseUrl("/api/videos/" + videoId + "/chapters"),
            HttpMethod.GET,
            new HttpEntity<>(authSession.headers()),
            VideoChapterResponse[].class
        ).getBody()).isNotEmpty();
        assertThat(restTemplate.exchange(
            baseUrl("/api/videos/" + videoId + "/key-points"),
            HttpMethod.GET,
            new HttpEntity<>(authSession.headers()),
            VideoKeyPointResponse[].class
        ).getBody()).isNotEmpty();

        redisTemplate.delete(RedisAnalysisProgressStore.key(taskId));
        SseCapture mysqlFallback = collectEvents(taskId);
        assertThat(mysqlFallback.events()).hasSize(1);
        assertThat(mysqlFallback.events().getFirst().status()).isEqualTo("SUCCESS");
        assertThat(mysqlFallback.events().getFirst().stage()).isEqualTo("DONE");
    }

    private SseCapture collectEvents(long taskId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl("/api/analysis/" + taskId + "/events")))
            .timeout(Duration.ofSeconds(45))
            .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + authSession.token())
            .GET()
            .build();
        HttpResponse<java.io.InputStream> response = HttpClient.newHttpClient().send(
            request,
            HttpResponse.BodyHandlers.ofInputStream()
        );
        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());

        List<AnalysisProgressEventResponse> events = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            response.body(), StandardCharsets.UTF_8
        ))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    events.add(objectMapper.readValue(
                        line.substring("data:".length()),
                        AnalysisProgressEventResponse.class
                    ));
                }
            }
        }
        return new SseCapture(
            response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(""),
            events
        );
    }

    private long uploadVideo(byte[] bytes) {
        if (authSession == null) {
            authSession = TestAuthClient.registerAndLogin(
                restTemplate,
                baseUrl(""),
                "m6-infra-" + System.nanoTime()
            );
        }
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.valueOf("video/mp4"));
        ByteArrayResource file = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "m6-sse-pipeline.mp4";
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(file, fileHeaders));
        body.add("title", "M6 SSE pipeline");
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

    private byte[] generateValidMp4() throws Exception {
        Path fixture = Files.createTempFile("videoagent-m6-", ".mp4");
        try {
            String executable = System.getenv().getOrDefault("FFMPEG_PATH", "ffmpeg");
            Process process = new ProcessBuilder(
                executable,
                "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "color=c=teal:s=320x180:r=15",
                "-f", "lavfi", "-i", "sine=frequency=640:sample_rate=16000",
                "-t", "20",
                "-c:v", "mpeg4",
                "-c:a", "aac",
                "-shortest",
                fixture.toString()
            ).redirectErrorStream(true).start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            String diagnostics = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(finished).as("FFmpeg fixture generation timed out").isTrue();
            assertThat(process.exitValue()).as(diagnostics).isZero();
            return Files.readAllBytes(fixture);
        } finally {
            Files.deleteIfExists(fixture);
        }
    }

    private String baseUrl(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private record SseCapture(String contentType, List<AnalysisProgressEventResponse> events) {
    }
}
