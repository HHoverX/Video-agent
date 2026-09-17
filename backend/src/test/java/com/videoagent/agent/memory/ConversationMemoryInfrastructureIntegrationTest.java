package com.videoagent.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.agent.config.ConversationMemoryProperties;
import com.videoagent.agent.memory.ConversationRedisStore.CommitResult;
import com.videoagent.agent.memory.ConversationRedisStore.CompactionSnapshot;
import com.videoagent.agent.memory.summary.ConversationSummaryProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest(properties = {
    "videoagent.ai.llm.provider=mock",
    "videoagent.agent.memory.ttl=5m",
    "videoagent.agent.memory.recent-turns=2",
    "videoagent.agent.memory.compact-trigger-turns=4",
    "videoagent.agent.memory.compact-batch-turns=2",
    "videoagent.agent.memory.max-summary-chars=2000",
    "videoagent.agent.memory.max-context-chars=6000"
})
@EnabledIfEnvironmentVariable(named = "VIDEOAGENT_MEMORY_INFRA_TEST", matches = "true")
class ConversationMemoryInfrastructureIntegrationTest {

    private static final DefaultRedisScript<Long> REPLACE_STATE = new DefaultRedisScript<>("""
        redis.call('SET', KEYS[1], ARGV[1])
        redis.call('DEL', KEYS[2])
        redis.call('RPUSH', KEYS[2], ARGV[2])
        return 1
        """, Long.class);

    @Autowired
    private RedisConversationMemory memory;

    @Autowired
    private ConversationRedisStore redisStore;

    @Autowired
    private ConversationCompactionService compactionService;

    @Autowired
    private ConversationMemoryProperties memoryProperties;

    @Autowired
    private ConversationMemoryMetrics metrics;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private final List<Long> conversationIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : conversationIds) {
            redisTemplate.delete(List.of(
                RedisConversationMemory.recentKey(id, id),
                RedisConversationMemory.summaryKey(id, id),
                RedisConversationMemory.lockKey(id, id)
            ));
        }
        conversationIds.clear();
    }

    @Test
    void shouldReturnEmptyContextOnRedisMiss() {
        long id = conversationId();

        assertThat(memory.load(id, id, "request-1")).isEqualTo(ConversationHistory.empty());
    }

    @Test
    void shouldAppendLoadAndRefreshRecentAndSummaryTtl() {
        long id = conversationId();
        ConversationTurn turn = new ConversationTurn("q1", "a1");
        redisTemplate.opsForValue().set(
            RedisConversationMemory.summaryKey(id, id),
            "old-summary",
            Duration.ofSeconds(30)
        );

        memory.appendTurn(id, id, turn, "request-1");
        ConversationHistory history = memory.load(id, id, "request-2");

        assertThat(history.summary()).isEqualTo("old-summary");
        assertThat(history.recentTurns()).containsExactly(turn);
        assertThat(redisTemplate.getExpire(RedisConversationMemory.recentKey(id, id)))
            .isGreaterThan(240L)
            .isLessThanOrEqualTo(300L);
        assertThat(redisTemplate.getExpire(RedisConversationMemory.summaryKey(id, id)))
            .isGreaterThan(240L)
            .isLessThanOrEqualTo(300L);
    }

    @Test
    void shouldCompactOldestBatchAndKeepOriginalTurnsUntilCommitSucceeds() throws Exception {
        long id = conversationId();
        append(id, 1, 4);

        compactionService.compact(id, id);

        ConversationHistory history = memory.load(id, id, "request-1");
        assertThat(history.summary()).isNotBlank();
        assertThat(history.recentTurns()).extracting(ConversationTurn::question)
            .containsExactly("q3", "q4");
        assertThat(redisTemplate.getExpire(RedisConversationMemory.summaryKey(id, id)))
            .isPositive()
            .isLessThanOrEqualTo(300L);
        assertThat(redisTemplate.getExpire(RedisConversationMemory.recentKey(id, id)))
            .isPositive()
            .isLessThanOrEqualTo(300L);
    }

    @Test
    void shouldNotDeleteTurnsTwiceWhenCompactionIsRepeated() throws Exception {
        long id = conversationId();
        append(id, 1, 4);

        compactionService.compact(id, id);
        String firstSummary = redisTemplate.opsForValue().get(
            RedisConversationMemory.summaryKey(id, id));
        compactionService.compact(id, id);

        assertThat(redisTemplate.opsForValue().get(RedisConversationMemory.summaryKey(id, id)))
            .isEqualTo(firstSummary);
        assertThat(memory.load(id, id, "request-1").recentTurns())
            .extracting(ConversationTurn::question)
            .containsExactly("q3", "q4");
    }

    @Test
    void shouldAllowOnlyOneEffectiveConcurrentCompaction() throws Exception {
        long id = conversationId();
        append(id, 1, 4);
        AtomicInteger summaryCalls = new AtomicInteger();
        CountDownLatch firstSummaryStarted = new CountDownLatch(1);
        CountDownLatch allowFirstSummaryToFinish = new CountDownLatch(1);
        ConversationSummaryProvider blockingProvider = (oldSummary, turns, maxChars) -> {
            summaryCalls.incrementAndGet();
            firstSummaryStarted.countDown();
            try {
                if (!allowFirstSummaryToFinish.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to finish test summary");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting to finish test summary", exception);
            }
            return "concurrent-summary";
        };
        ConversationCompactionService concurrentService = new ConversationCompactionService(
            redisStore, blockingProvider, memoryProperties, metrics);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> concurrentService.compact(id, id));
            assertThat(firstSummaryStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> second = executor.submit(() -> concurrentService.compact(id, id));
            second.get(5, TimeUnit.SECONDS);
            allowFirstSummaryToFinish.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            allowFirstSummaryToFinish.countDown();
            executor.shutdownNow();
        }

        assertThat(summaryCalls).hasValue(1);
        assertThat(memory.load(id, id, "request-1").recentTurns())
            .extracting(ConversationTurn::question)
            .containsExactly("q3", "q4");
        assertThat(redisTemplate.opsForValue().get(RedisConversationMemory.summaryKey(id, id)))
            .isEqualTo("concurrent-summary");
    }

    @Test
    void shouldPreserveTurnsAppendedWhileSnapshotIsBeingSummarized() throws Exception {
        long id = conversationId();
        append(id, 1, 4);
        String token = UUID.randomUUID().toString();
        assertThat(redisStore.tryAcquireLock(id, id, token)).isTrue();
        CompactionSnapshot snapshot = redisStore.snapshot(id, id).orElseThrow();

        append(id, 5, 6);
        CommitResult result = redisStore.commit(id, id, token, snapshot, "summary-1-2");

        assertThat(result).isEqualTo(CommitResult.SUCCESS);
        assertThat(memory.load(id, id, "request-1").recentTurns())
            .extracting(ConversationTurn::question)
            .containsExactly("q3", "q4", "q5", "q6");
    }

    @Test
    void shouldRejectStaleTokenWithoutDeletingTurns() throws Exception {
        long id = conversationId();
        append(id, 1, 4);
        String staleToken = UUID.randomUUID().toString();
        assertThat(redisStore.tryAcquireLock(id, id, staleToken)).isTrue();
        CompactionSnapshot snapshot = redisStore.snapshot(id, id).orElseThrow();
        redisTemplate.opsForValue().set(RedisConversationMemory.lockKey(id, id), "new-owner");

        CommitResult result = redisStore.commit(id, id, staleToken, snapshot, "must-not-commit");

        assertThat(result).isEqualTo(CommitResult.LOCK_LOST);
        assertThat(redisTemplate.opsForList().size(RedisConversationMemory.recentKey(id, id)))
            .isEqualTo(4L);
        assertThat(redisTemplate.opsForValue().get(RedisConversationMemory.summaryKey(id, id)))
            .isNull();
    }

    @Test
    void shouldNotReleaseLockOwnedByAnotherCompactor() {
        long id = conversationId();
        String staleToken = UUID.randomUUID().toString();
        assertThat(redisStore.tryAcquireLock(id, id, staleToken)).isTrue();
        redisTemplate.opsForValue().set(RedisConversationMemory.lockKey(id, id), "new-owner");

        redisStore.releaseLock(id, id, staleToken);

        assertThat(redisTemplate.opsForValue().get(RedisConversationMemory.lockKey(id, id)))
            .isEqualTo("new-owner");
    }

    @Test
    void shouldRejectChangedPrefixWithoutDeletingTurns() throws Exception {
        long id = conversationId();
        append(id, 1, 4);
        String token = UUID.randomUUID().toString();
        assertThat(redisStore.tryAcquireLock(id, id, token)).isTrue();
        CompactionSnapshot snapshot = redisStore.snapshot(id, id).orElseThrow();
        redisTemplate.opsForList().set(
            RedisConversationMemory.recentKey(id, id),
            0,
            objectMapper.writeValueAsString(new ConversationTurn("changed", "changed"))
        );

        CommitResult result = redisStore.commit(id, id, token, snapshot, "must-not-commit");

        assertThat(result).isEqualTo(CommitResult.PREFIX_CHANGED);
        assertThat(redisTemplate.opsForList().size(RedisConversationMemory.recentKey(id, id)))
            .isEqualTo(4L);
        assertThat(redisTemplate.opsForValue().get(RedisConversationMemory.summaryKey(id, id)))
            .isNull();
    }

    @Test
    void shouldLoadSummaryAndRecentTurnsFromOneAtomicSnapshot() throws Exception {
        long id = conversationId();
        String summaryKey = RedisConversationMemory.summaryKey(id, id);
        String recentKey = RedisConversationMemory.recentKey(id, id);
        String encodedA = objectMapper.writeValueAsString(new ConversationTurn("q-a", "a-a"));
        String encodedB = objectMapper.writeValueAsString(new ConversationTurn("q-b", "a-b"));
        replaceState(summaryKey, recentKey, "summary-a", encodedA);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> writer = executor.submit(() -> {
                start.await();
                for (int index = 0; index < 500; index++) {
                    if ((index & 1) == 0) {
                        replaceState(summaryKey, recentKey, "summary-b", encodedB);
                    } else {
                        replaceState(summaryKey, recentKey, "summary-a", encodedA);
                    }
                }
                return null;
            });
            Future<?> reader = executor.submit(() -> {
                start.await();
                for (int index = 0; index < 500; index++) {
                    ConversationHistory history = redisStore.load(id, id);
                    List<String> questions = history.recentTurns().stream()
                        .map(ConversationTurn::question)
                        .toList();
                    boolean stateA = history.summary().equals("summary-a")
                        && questions.equals(List.of("q-a"));
                    boolean stateB = history.summary().equals("summary-b")
                        && questions.equals(List.of("q-b"));
                    assertThat(stateA || stateB).isTrue();
                }
                return null;
            });
            start.countDown();
            writer.get(10, TimeUnit.SECONDS);
            reader.get(10, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private void append(long id, int first, int last) throws Exception {
        for (int index = first; index <= last; index++) {
            redisStore.append(id, id, new ConversationTurn("q" + index, "a" + index));
        }
    }

    private void replaceState(
        String summaryKey,
        String recentKey,
        String summary,
        String encodedTurn
    ) {
        Long result = redisTemplate.execute(
            REPLACE_STATE,
            List.of(summaryKey, recentKey),
            summary,
            encodedTurn
        );
        if (result == null || result != 1L) {
            throw new IllegalStateException("Redis test state replacement failed");
        }
    }

    private long conversationId() {
        long id = Math.abs(System.nanoTime());
        conversationIds.add(id);
        return id;
    }
}
