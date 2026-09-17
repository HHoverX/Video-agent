package com.videoagent.agent.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.agent.config.ConversationMemoryProperties;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
class ConversationRedisStore {

    private static final String KEY_PREFIX = "videoagent:agentic-qa:memory:v2:";

    private static final DefaultRedisScript<Long> APPEND = new DefaultRedisScript<>("""
        local length = redis.call('RPUSH', KEYS[1], ARGV[1])
        redis.call('PEXPIRE', KEYS[1], ARGV[2])
        if redis.call('EXISTS', KEYS[2]) == 1 then
            redis.call('PEXPIRE', KEYS[2], ARGV[2])
        end
        return length
        """, Long.class);

    private static final DefaultRedisScript<List> LOAD = new DefaultRedisScript<>("""
        local state = {}
        local summary = redis.call('GET', KEYS[1])
        table.insert(state, summary or '')
        local turns = redis.call('LRANGE', KEYS[2], 0, -1)
        for _, turn in ipairs(turns) do
            table.insert(state, turn)
        end
        return state
        """, List.class);

    private static final DefaultRedisScript<Long> COMMIT_COMPACTION = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) ~= ARGV[1] then
            return -1
        end
        local batch = tonumber(ARGV[2])
        for index = 1, batch do
            if redis.call('LINDEX', KEYS[2], index - 1) ~= ARGV[4 + index] then
                return -2
            end
        end
        redis.call('SET', KEYS[3], ARGV[4])
        redis.call('LTRIM', KEYS[2], batch, -1)
        if redis.call('EXISTS', KEYS[2]) == 1 then
            redis.call('PEXPIRE', KEYS[2], ARGV[3])
        end
        redis.call('PEXPIRE', KEYS[3], ARGV[3])
        return 1
        """, Long.class);

    private static final DefaultRedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[1] then
            return redis.call('DEL', KEYS[1])
        end
        return 0
        """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ConversationMemoryProperties properties;

    ConversationRedisStore(
        StringRedisTemplate redisTemplate,
        ObjectMapper objectMapper,
        ConversationMemoryProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    ConversationHistory load(long userId, long videoId) throws JsonProcessingException {
        List<?> state = redisTemplate.execute(
            LOAD,
            List.of(summaryKey(userId, videoId), recentKey(userId, videoId))
        );
        if (state == null || state.isEmpty()) {
            throw new IllegalStateException("Redis load script returned no result");
        }
        String summary = requireString(state.getFirst());
        List<String> values = new ArrayList<>(state.size() - 1);
        for (int index = 1; index < state.size(); index++) {
            values.add(requireString(state.get(index)));
        }
        List<ConversationTurn> turns = deserialize(values);
        return new ConversationHistory(summary, turns);
    }

    long append(long userId, long videoId, ConversationTurn turn) throws JsonProcessingException {
        String encoded = objectMapper.writeValueAsString(turn);
        Long length = redisTemplate.execute(
            APPEND,
            List.of(recentKey(userId, videoId), summaryKey(userId, videoId)),
            encoded,
            Long.toString(properties.ttl().toMillis())
        );
        if (length == null) {
            throw new IllegalStateException("Redis append script returned no result");
        }
        return length;
    }

    boolean tryAcquireLock(long userId, long videoId, String token) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
            lockKey(userId, videoId), token, properties.compactLockTtl()));
    }

    Optional<CompactionSnapshot> snapshot(long userId, long videoId) throws JsonProcessingException {
        Long size = redisTemplate.opsForList().size(recentKey(userId, videoId));
        if (size == null || size < properties.compactTriggerTurns()) {
            return Optional.empty();
        }
        List<String> values = redisTemplate.opsForList().range(
            recentKey(userId, videoId), 0, properties.compactBatchTurns() - 1L);
        if (values == null || values.size() != properties.compactBatchTurns()) {
            return Optional.empty();
        }
        String summary = redisTemplate.opsForValue().get(summaryKey(userId, videoId));
        return Optional.of(new CompactionSnapshot(
            summary == null ? "" : summary,
            deserialize(values),
            List.copyOf(values)
        ));
    }

    CommitResult commit(
        long userId,
        long videoId,
        String token,
        CompactionSnapshot snapshot,
        String newSummary
    ) {
        List<String> arguments = new ArrayList<>(4 + snapshot.encodedTurns().size());
        arguments.add(token);
        arguments.add(Integer.toString(snapshot.encodedTurns().size()));
        arguments.add(Long.toString(properties.ttl().toMillis()));
        arguments.add(newSummary);
        arguments.addAll(snapshot.encodedTurns());
        Long result = redisTemplate.execute(
            COMMIT_COMPACTION,
            List.of(lockKey(userId, videoId), recentKey(userId, videoId), summaryKey(userId, videoId)),
            arguments.toArray()
        );
        if (result == null) {
            throw new IllegalStateException("Redis compaction script returned no result");
        }
        if (result == 1L) {
            return CommitResult.SUCCESS;
        }
        return result == -1L ? CommitResult.LOCK_LOST : CommitResult.PREFIX_CHANGED;
    }

    void releaseLock(long userId, long videoId, String token) {
        redisTemplate.execute(RELEASE_LOCK, List.of(lockKey(userId, videoId)), token);
    }

    private List<ConversationTurn> deserialize(List<String> values) throws JsonProcessingException {
        List<ConversationTurn> turns = new ArrayList<>(values.size());
        for (String value : values) {
            ConversationTurn turn = objectMapper.readValue(value, ConversationTurn.class);
            if (turn == null) {
                throw new JsonProcessingException("Conversation turn was null") { };
            }
            turns.add(turn);
        }
        return turns;
    }

    private String requireString(Object value) {
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalStateException("Redis memory script returned a non-string value");
    }

    static String recentKey(long userId, long videoId) {
        return baseKey(userId, videoId) + ":recent";
    }

    static String summaryKey(long userId, long videoId) {
        return baseKey(userId, videoId) + ":summary";
    }

    static String lockKey(long userId, long videoId) {
        return baseKey(userId, videoId) + ":compact-lock";
    }

    private static String baseKey(long userId, long videoId) {
        return KEY_PREFIX + userId + ":" + videoId;
    }

    record CompactionSnapshot(
        String oldSummary,
        List<ConversationTurn> turns,
        List<String> encodedTurns
    ) {
    }

    enum CommitResult {
        SUCCESS,
        LOCK_LOST,
        PREFIX_CHANGED
    }
}
