package com.videoagent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.analysis.dto.AnalysisProgressEventResponse;
import com.videoagent.analysis.dto.AnalysisTaskResponse;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.analysis.service.AnalysisEventService;
import com.videoagent.analysis.service.AnalysisQueryService;
import com.videoagent.auth.entity.AppUserEntity;
import com.videoagent.auth.repository.AppUserRepository;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.outbox.repository.AnalysisOutboxEventRepository;
import com.videoagent.rag.repository.VideoRagIndexRepository;
import com.videoagent.summary.repository.VideoChapterRepository;
import com.videoagent.summary.repository.VideoKeyPointRepository;
import com.videoagent.summary.repository.VideoSummaryRepository;
import com.videoagent.storage.ObjectStorageService;
import com.videoagent.transcript.repository.VideoTranscriptSegmentRepository;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;
import com.videoagent.upload.repository.VideoUploadSessionRepository;
import com.videoagent.upload.repository.VideoUploadPartRepository;
import org.apache.rocketmq.spring.core.RocketMQTemplate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@SpringBootTest(properties = {
    "videoagent.security.jwt.secret=unit-test-jwt-secret-with-more-than-32-bytes",
    "videoagent.security.jwt.expiration=2h",
    "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
        + "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration,"
        + "org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration"
})
@AutoConfigureMockMvc
class SecurityEndpointIntegrationTest {

    @MockitoBean
    private AppUserRepository userRepository;

    @MockitoBean
    private VideoRepository videoRepository;

    @MockitoBean
    private VideoUploadSessionRepository uploadSessionRepository;

    @MockitoBean
    private VideoUploadPartRepository uploadPartRepository;

    @MockitoBean
    private AnalysisTaskRepository taskRepository;

    @MockitoBean
    private AnalysisOutboxEventRepository outboxEventRepository;

    @MockitoBean
    private VideoRagIndexRepository ragIndexRepository;

    @MockitoBean
    private VideoTranscriptSegmentRepository transcriptRepository;

    @MockitoBean
    private VideoSummaryRepository summaryRepository;

    @MockitoBean
    private VideoChapterRepository chapterRepository;

    @MockitoBean
    private VideoKeyPointRepository keyPointRepository;

    @MockitoBean
    private RocketMQTemplate rocketMQTemplate;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private AnalysisEventService eventService;

    @MockitoBean
    private AnalysisQueryService queryService;

    @MockitoBean
    private ObjectStorageService storageService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final AtomicReference<AppUserEntity> storedUser = new AtomicReference<>();

    @BeforeEach
    void setUpUserRepository() {
        storedUser.set(null);
        when(userRepository.findByUsername(anyString())).thenAnswer(invocation -> {
            AppUserEntity current = storedUser.get();
            return current != null && current.getUsername().equals(invocation.getArgument(0))
                ? current
                : null;
        });
        when(userRepository.insert(any(AppUserEntity.class))).thenAnswer(invocation -> {
            AppUserEntity user = invocation.getArgument(0);
            user.setId(7L);
            storedUser.set(user);
            return 1;
        });
    }

    @Test
    void shouldRegisterOnceAndStoreOnlyBcryptHash() throws Exception {
        String request = """
            {"username":"alice","password":"safe-password"}
            """;

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(7))
            .andExpect(jsonPath("$.username").value("alice"))
            .andExpect(jsonPath("$.password").doesNotExist())
            .andExpect(jsonPath("$.passwordHash").doesNotExist());

        assertThat(storedUser.get().getPasswordHash()).startsWith("$2");
        assertThat(passwordEncoder.matches("safe-password", storedUser.get().getPasswordHash()))
            .isTrue();

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("USERNAME_ALREADY_EXISTS"));
    }

    @Test
    void shouldLoginAndResolveCurrentUserFromSecurityContext() throws Exception {
        storeUser("alice", "safe-password");

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"alice","password":"safe-password"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(jsonPath("$.expiresIn").value(7_200))
            .andReturn();
        String token = objectMapper.readTree(login.getResponse().getContentAsString())
            .path("token")
            .asText();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(7))
            .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void shouldReturn401ForMissingTokenAndInvalidPassword() throws Exception {
        storeUser("alice", "safe-password");

        mockMvc.perform(get("/api/auth/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"alice","password":"wrong-password"}
                    """))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void shouldAuthenticateSseAndKeepPollingFallbackAvailable() throws Exception {
        String token = loginToken();
        SseEmitter emitter = new SseEmitter();
        emitter.send(SseEmitter.event()
            .name("progress")
            .data(new AnalysisProgressEventResponse(
                101L, 9L, "SUCCESS", "DONE", 100, "分析完成", null, null
            ), MediaType.APPLICATION_JSON));
        emitter.complete();
        when(eventService.subscribe(101L, 7L)).thenReturn(emitter);
        LocalDateTime now = LocalDateTime.now();
        when(queryService.getTask(101L, 7L)).thenReturn(new AnalysisTaskResponse(
            101L, 9L, "SUCCESS", "DONE", 100, "分析完成",
            null, null, now, now, now
        ));

        MvcResult pending = mockMvc.perform(get("/api/analysis/101/events")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andReturn();
        mockMvc.perform(asyncDispatch(pending))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/analysis/101")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void shouldHideAnotherUsersSseTaskAsNotFound() throws Exception {
        String token = loginToken();
        when(eventService.subscribe(202L, 7L)).thenThrow(
            new VideoAgentException(ErrorCode.ANALYSIS_NOT_FOUND)
        );

        mockMvc.perform(get("/api/analysis/202/events")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
    }

    @Test
    void shouldSecureCurrentAnalysisTaskQuery() throws Exception {
        mockMvc.perform(get("/api/videos/9/analysis"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        String token = loginToken();
        LocalDateTime now = LocalDateTime.now();
        when(queryService.getCurrentTask(9L, 7L)).thenReturn(Optional.of(new AnalysisTaskResponse(
            101L, 9L, "PROCESSING", "ANALYZING", 40, "正在分析",
            null, null, now, now, null
        )));
        when(queryService.getCurrentTask(10L, 7L)).thenReturn(Optional.empty());
        when(queryService.getCurrentTask(11L, 7L)).thenThrow(
            new VideoAgentException(ErrorCode.VIDEO_NOT_FOUND)
        );
        when(queryService.getCurrentTask(12L, 7L)).thenThrow(
            new VideoAgentException(ErrorCode.VIDEO_NOT_FOUND)
        );

        mockMvc.perform(get("/api/videos/9/analysis").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.taskId").value(101))
            .andExpect(jsonPath("$.status").value("PROCESSING"));
        mockMvc.perform(get("/api/videos/10/analysis").header("Authorization", "Bearer " + token))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/videos/11/analysis").header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("VIDEO_NOT_FOUND"));
        mockMvc.perform(get("/api/videos/12/analysis").header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("VIDEO_NOT_FOUND"));
    }

    @Test
    void shouldSecurePlaybackUrlAndReturnOnlyPlaybackFields() throws Exception {
        mockMvc.perform(get("/api/videos/42/playback-url"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        String token = loginToken();
        VideoEntity owned = new VideoEntity();
        owned.setId(42L);
        owned.setUserId(7L);
        owned.setObjectKey("videos/owned.mp4");
        when(videoRepository.selectOne(any())).thenReturn(owned);
        when(storageService.presignGetObject(anyString(), any()))
            .thenReturn("https://media.example.com/signed-get");

        mockMvc.perform(get("/api/videos/42/playback-url")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.url").value("https://media.example.com/signed-get"))
            .andExpect(jsonPath("$.expiresAt").isNotEmpty())
            .andExpect(jsonPath("$.objectKey").doesNotExist())
            .andExpect(jsonPath("$.bucket").doesNotExist())
            .andExpect(jsonPath("$.accessKey").doesNotExist())
            .andExpect(jsonPath("$.secretKey").doesNotExist());

        when(videoRepository.selectOne(any())).thenReturn(null);
        mockMvc.perform(get("/api/videos/43/playback-url")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("VIDEO_NOT_FOUND"));
        mockMvc.perform(get("/api/videos/44/playback-url")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("VIDEO_NOT_FOUND"));
    }

    private String loginToken() throws Exception {
        storeUser("alice", "safe-password");
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"alice","password":"safe-password"}
                    """))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return response.path("token").asText();
    }

    private void storeUser(String username, String password) {
        AppUserEntity user = new AppUserEntity();
        user.setId(7L);
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        storedUser.set(user);
    }
}
