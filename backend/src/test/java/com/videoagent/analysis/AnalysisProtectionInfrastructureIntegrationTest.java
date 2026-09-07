package com.videoagent.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videoagent.analysis.dto.StartAnalysisResponse;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.analysis.service.AnalysisCommandService;
import com.videoagent.analysis.service.AnalysisProtectionProperties;
import com.videoagent.analysis.service.AnalysisRateLimiter;
import com.videoagent.auth.repository.AppUserRepository;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@EnabledIfEnvironmentVariable(named = "VIDEOAGENT_ANALYSIS_PROTECTION_INFRA_TEST", matches = "true")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "videoagent.security.jwt.secret=" + TestAuthClient.JWT_SECRET,
        "videoagent.analysis.consumer-group=videoagent-analysis-protection-${random.uuid}",
        "videoagent.analysis.recovery-interval-ms=3600000",
        "videoagent.outbox.publish-interval-ms=3600000",
        "videoagent.ai.asr.provider=mock",
        "videoagent.ai.llm.provider=mock"
    }
)
class AnalysisProtectionInfrastructureIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private AnalysisTaskRepository taskRepository;

    @Autowired
    private AnalysisCommandService commandService;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Session firstUser;
    private Session secondUser;
    private final List<Long> videoIds = new ArrayList<>();
    private final List<Long> taskIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        firstUser = TestAuthClient.registerAndLogin(
            restTemplate, baseUrl(""), "analysis-protection-a-" + System.nanoTime()
        );
        secondUser = TestAuthClient.registerAndLogin(
            restTemplate, baseUrl(""), "analysis-protection-b-" + System.nanoTime()
        );
    }

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(rateKey(firstUser.userId()));
        redisTemplate.delete(rateKey(secondUser.userId()));
        if (!videoIds.isEmpty()) {
            jdbcTemplate.update(
                "DELETE FROM analysis_outbox_event WHERE video_id IN (" + placeholders(videoIds.size()) + ")",
                videoIds.toArray()
            );
        }
        taskIds.forEach(taskRepository::deleteById);
        videoIds.forEach(videoRepository::deleteById);
        userRepository.deleteById(firstUser.userId());
        userRepository.deleteById(secondUser.userId());
    }

    @Test
    void shouldRunRealRedisTokenBucketWithRefillTtlIsolationAndAtomicConcurrency() throws Exception {
        AnalysisRateLimiter limiter = limiter(3, 1, Duration.ofMillis(200));

        limiter.checkAllowed(firstUser.userId());
        limiter.checkAllowed(firstUser.userId());
        limiter.checkAllowed(firstUser.userId());
        assertRateLimited(limiter, firstUser.userId());
        assertThat(redisTemplate.getExpire(rateKey(firstUser.userId()), TimeUnit.MILLISECONDS))
            .isPositive().isLessThanOrEqualTo(600L);

        limiter.checkAllowed(secondUser.userId());
        Thread.sleep(240L);
        limiter.checkAllowed(firstUser.userId());
        assertRateLimited(limiter, firstUser.userId());

        redisTemplate.delete(rateKey(secondUser.userId()));
        AnalysisRateLimiter cappedRefillLimiter = limiter(3, 3, Duration.ofMillis(200));
        cappedRefillLimiter.checkAllowed(secondUser.userId());
        Thread.sleep(100L);
        cappedRefillLimiter.checkAllowed(secondUser.userId());
        cappedRefillLimiter.checkAllowed(secondUser.userId());
        cappedRefillLimiter.checkAllowed(secondUser.userId());
        assertRateLimited(cappedRefillLimiter, secondUser.userId());

        redisTemplate.delete(rateKey(firstUser.userId()));
        AnalysisRateLimiter concurrentLimiter = limiter(5, 1, Duration.ofSeconds(10));
        ExecutorService executor = Executors.newFixedThreadPool(12);
        CountDownLatch ready = new CountDownLatch(12);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    try {
                        concurrentLimiter.checkAllowed(firstUser.userId());
                        allowed.incrementAndGet();
                    } catch (VideoAgentException exception) {
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.ANALYSIS_RATE_LIMITED);
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(allowed).hasValue(5);
    }

    @Test
    void shouldCountOnlyOwnedActiveStatusesAndAllowDifferentBusinessVersions() {
        long firstVideo = createVideo(firstUser.userId());
        long secondVideo = createVideo(secondUser.userId());
        for (String status : List.of("PENDING", "PROCESSING", "RETRY_WAITING", "SUCCESS", "FAILED")) {
            insertTask(firstVideo, status, "model-" + status);
        }
        insertTask(secondVideo, "PROCESSING", "other-user-model");

        assertThat(taskRepository.countActiveByUserId(firstUser.userId())).isEqualTo(3L);
        assertThat(taskRepository.countActiveByUserId(secondUser.userId())).isEqualTo(1L);
        List<Map<String, Object>> plan = jdbcTemplate.queryForList("""
            EXPLAIN SELECT COUNT(*)
            FROM analysis_task task
            INNER JOIN video ON video.id = task.video_id
            WHERE video.user_id = ?
              AND task.status IN ('PENDING', 'PROCESSING', 'RETRY_WAITING')
            """, firstUser.userId());
        assertThat(plan).allSatisfy(row -> assertThat(row.get("key")).isNotNull());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM analysis_task WHERE video_id = ? AND analysis_type = 'STRUCTURED_SUMMARY'",
            Long.class,
            firstVideo
        )).isEqualTo(5L);
    }

    @Test
    void shouldConvergeConcurrentSameBusinessCreatesToOneTaskAndOneOutbox() throws Exception {
        long videoId = createVideo(firstUser.userId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<StartAnalysisResponse> first = executor.submit(
                () -> startAfterBarrier(videoId, firstUser.userId(), ready, start)
            );
            Future<StartAnalysisResponse> second = executor.submit(
                () -> startAfterBarrier(videoId, firstUser.userId(), ready, start)
            );
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            StartAnalysisResponse firstResponse = first.get(10, TimeUnit.SECONDS);
            StartAnalysisResponse secondResponse = second.get(10, TimeUnit.SECONDS);
            taskIds.add(firstResponse.taskId());

            assertThat(secondResponse.taskId()).isEqualTo(firstResponse.taskId());
            assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM analysis_task WHERE video_id = ? AND analysis_type = ? AND model_version = ?",
                Long.class,
                videoId,
                "STRUCTURED_SUMMARY",
                "m5-langchain4j-structured-v1"
            )).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM analysis_outbox_event WHERE task_id = ?",
                Long.class,
                firstResponse.taskId()
            )).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    private AnalysisRateLimiter limiter(int capacity, int refillTokens, Duration refillPeriod) {
        return new AnalysisRateLimiter(
            redisTemplate,
            new AnalysisProtectionProperties(
                new AnalysisProtectionProperties.RateLimit(capacity, refillTokens, refillPeriod, 1),
                3
            )
        );
    }

    private void assertRateLimited(AnalysisRateLimiter limiter, long userId) {
        assertThatThrownBy(() -> limiter.checkAllowed(userId))
            .isInstanceOfSatisfying(VideoAgentException.class,
                exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.ANALYSIS_RATE_LIMITED));
    }

    private long createVideo(long userId) {
        LocalDateTime now = LocalDateTime.now();
        VideoEntity video = new VideoEntity();
        video.setUserId(userId);
        video.setTitle("analysis protection");
        video.setOriginalFilename("analysis-protection.mp4");
        video.setObjectKey("tests/analysis-protection/" + UUID.randomUUID() + ".mp4");
        video.setFileSize(24L);
        video.setMimeType("video/mp4");
        video.setFileHash(UUID.randomUUID().toString().replace("-", "") + "a".repeat(32));
        video.setStatus("UPLOADED");
        video.setCreatedAt(now);
        video.setUpdatedAt(now);
        assertThat(videoRepository.insert(video)).isEqualTo(1);
        videoIds.add(video.getId());
        return video.getId();
    }

    private void insertTask(long videoId, String status, String modelVersion) {
        LocalDateTime now = LocalDateTime.now();
        AnalysisTaskEntity task = new AnalysisTaskEntity();
        task.setVideoId(videoId);
        task.setAnalysisType("STRUCTURED_SUMMARY");
        task.setModelVersion(modelVersion);
        task.setStatus(status);
        task.setStage(status);
        task.setProgress(0);
        task.setRetryCount(0);
        task.setProcessingGeneration(0);
        task.setRetryNotBefore(now);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        assertThat(taskRepository.insert(task)).isEqualTo(1);
        taskIds.add(task.getId());
    }

    private StartAnalysisResponse startAfterBarrier(
        long videoId,
        long userId,
        CountDownLatch ready,
        CountDownLatch start
    ) {
        ready.countDown();
        await(start);
        return commandService.start(videoId, userId);
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private String baseUrl(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private String rateKey(long userId) {
        return "videoagent:analysis:rate:" + userId;
    }

    private String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }
}
