package com.videoagent.common.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.auth.repository.AppUserRepository;
import com.videoagent.outbox.repository.AnalysisOutboxEventRepository;
import com.videoagent.rag.repository.VideoRagIndexRepository;
import com.videoagent.transcript.repository.VideoTranscriptSegmentRepository;
import com.videoagent.summary.repository.VideoChapterRepository;
import com.videoagent.summary.repository.VideoKeyPointRepository;
import com.videoagent.summary.repository.VideoSummaryRepository;
import com.videoagent.video.repository.VideoRepository;
import org.apache.rocketmq.spring.core.RocketMQTemplate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
            + "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration,"
            + "org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration"
    }
)
class HealthEndpointIntegrationTest {

    @MockitoBean
    private AppUserRepository appUserRepository;

    @MockitoBean
    private VideoRepository videoRepository;

    @MockitoBean
    private AnalysisTaskRepository analysisTaskRepository;

    @MockitoBean
    private AnalysisOutboxEventRepository analysisOutboxEventRepository;

    @MockitoBean
    private VideoRagIndexRepository ragIndexRepository;

    @MockitoBean
    private VideoTranscriptSegmentRepository transcriptSegmentRepository;

    @MockitoBean
    private VideoSummaryRepository videoSummaryRepository;

    @MockitoBean
    private VideoChapterRepository videoChapterRepository;

    @MockitoBean
    private VideoKeyPointRepository videoKeyPointRepository;

    @MockitoBean
    private RocketMQTemplate rocketMQTemplate;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void shouldServeHealthOverHttp() {
        ResponseEntity<HealthResponse> response = restTemplate.getForEntity(
            "http://127.0.0.1:" + port + "/api/health",
            HealthResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("UP");
        assertThat(response.getBody().application()).isEqualTo("videoagent-api");
        assertThat(response.getBody().timestamp()).isNotNull();
    }
}
