package com.videoagent.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.auth.entity.AppUserEntity;
import com.videoagent.auth.repository.AppUserRepository;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@EnabledIfEnvironmentVariable(named = "VIDEOAGENT_MEMORY_INFRA_TEST", matches = "true")
@SpringBootTest(properties = {
    "videoagent.analysis.consumer-group=videoagent-memory-${random.uuid}",
    "videoagent.ai.asr.provider=mock",
    "videoagent.ai.llm.provider=mock",
    "videoagent.agent.memory.max-turns=3",
    "videoagent.agent.memory.ttl=5m"
})
class ConversationMemoryInfrastructureIntegrationTest {

    @Autowired
    private PersistentConversationMemory memory;

    @Autowired
    private ConversationTurnStore turnStore;

    @Autowired
    private ConversationTurnRepository turnRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoSpyBean
    private RedisConversationMemory redisMemory;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> videoIds = new ArrayList<>();

    @BeforeEach
    void assertInfrastructureAndTransactionBoundary() {
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = 'conversation_turn' "
                + "AND index_name = 'idx_conversation_turn_recent'",
            Long.class
        )).isEqualTo(4L);

        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(redisMemory).load(anyLong(), anyLong());
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(redisMemory).replace(anyLong(), anyLong(), any(ConversationHistory.class));
    }

    @AfterEach
    void cleanUp() {
        for (Long userId : userIds) {
            for (Long videoId : videoIds) {
                redisTemplate.delete(RedisConversationMemory.key(userId, videoId));
            }
        }
        for (Long videoId : videoIds.reversed()) {
            videoRepository.deleteById(videoId);
        }
        for (Long userId : userIds.reversed()) {
            userRepository.deleteById(userId);
        }
        videoIds.clear();
        userIds.clear();
    }

    @Test
    void shouldKeepFullMysqlHistoryAndRefillOnlyRecentTurnsWithTtl() throws Exception {
        long userId = insertUser("memory-full");
        long videoId = insertVideo(userId, "Full history");
        for (int index = 1; index <= 5; index++) {
            memory.appendTurn(userId, videoId, new ConversationTurn("q" + index, "a" + index));
        }

        assertThat(turnRepository.countByUserIdAndVideoId(userId, videoId)).isEqualTo(5L);
        redisTemplate.delete(RedisConversationMemory.key(userId, videoId));

        ConversationHistory recovered = memory.load(userId, videoId);

        assertThat(recovered.turns()).extracting(ConversationTurn::question)
            .containsExactly("q3", "q4", "q5");
        String key = RedisConversationMemory.key(userId, videoId);
        assertThat(redisTemplate.opsForList().size(key)).isEqualTo(3L);
        Long ttlSeconds = redisTemplate.getExpire(key);
        assertThat(ttlSeconds).isPositive().isLessThanOrEqualTo(300L);
        assertThat(redisTemplate.opsForList().range(key, 0, -1))
            .extracting(this::questionFromJson)
            .containsExactly("q3", "q4", "q5");
    }

    @Test
    void shouldUseIdTieBreakerAndKeepUserVideoHistoriesIsolated() {
        long userA = insertUser("memory-a");
        long userB = insertUser("memory-b");
        long videoX = insertVideo(userA, "Video X");
        long videoY = insertVideo(userA, "Video Y");
        LocalDateTime sameMillisecond = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);

        insertTurn(userA, videoX, "a-x-1", sameMillisecond);
        insertTurn(userA, videoX, "a-x-2", sameMillisecond);
        insertTurn(userA, videoY, "a-y", sameMillisecond);
        insertTurn(userB, videoX, "b-x", sameMillisecond);

        assertThat(turnStore.loadRecent(userA, videoX, 3).turns())
            .extracting(ConversationTurn::question)
            .containsExactly("a-x-1", "a-x-2");
        assertThat(turnStore.loadRecent(userA, videoY, 3).turns())
            .extracting(ConversationTurn::question)
            .containsExactly("a-y");
        assertThat(turnStore.loadRecent(userB, videoX, 3).turns())
            .extracting(ConversationTurn::question)
            .containsExactly("b-x");
    }

    private long insertUser(String prefix) {
        LocalDateTime now = LocalDateTime.now();
        AppUserEntity user = new AppUserEntity();
        user.setUsername(prefix + "-" + System.nanoTime());
        user.setPasswordHash("test-password-hash");
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        assertThat(userRepository.insert(user)).isEqualTo(1);
        userIds.add(user.getId());
        return user.getId();
    }

    private long insertVideo(long userId, String title) {
        LocalDateTime now = LocalDateTime.now();
        VideoEntity video = new VideoEntity();
        video.setUserId(userId);
        video.setTitle(title);
        video.setOriginalFilename("memory.mp4");
        video.setObjectKey("videos/memory-" + System.nanoTime() + ".mp4");
        video.setFileSize(24L);
        video.setMimeType("video/mp4");
        video.setStatus("UPLOADED");
        video.setCreatedAt(now);
        video.setUpdatedAt(now);
        assertThat(videoRepository.insert(video)).isEqualTo(1);
        videoIds.add(video.getId());
        return video.getId();
    }

    private void insertTurn(long userId, long videoId, String question, LocalDateTime createdAt) {
        ConversationTurnEntity entity = new ConversationTurnEntity();
        entity.setUserId(userId);
        entity.setVideoId(videoId);
        entity.setQuestion(question);
        entity.setAnswer("answer-" + question);
        entity.setCreatedAt(createdAt);
        assertThat(turnRepository.insert(entity)).isEqualTo(1);
    }

    private String questionFromJson(String value) {
        try {
            return objectMapper.readValue(value, ConversationTurn.class).question();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
