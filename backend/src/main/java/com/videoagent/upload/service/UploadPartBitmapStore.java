package com.videoagent.upload.service;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Component
public class UploadPartBitmapStore {

    private static final String KEY_PREFIX = "videoagent:upload:parts:";
    private static final DefaultRedisScript<Long> MARK_COMPLETED = new DefaultRedisScript<>("""
        local old = redis.call('SETBIT', KEYS[1], ARGV[1], 1)
        if redis.call('PTTL', KEYS[1]) < 0 then
            redis.call('PEXPIREAT', KEYS[1], ARGV[2])
        end
        return old
        """, Long.class);
    private static final DefaultRedisScript<Long> REBUILD = new DefaultRedisScript<>("""
        if redis.call('EXISTS', KEYS[1]) == 0 then
            redis.call('SETBIT', KEYS[1], 0, 0)
        end
        for index = 2, #ARGV do
            redis.call('SETBIT', KEYS[1], ARGV[index], 1)
        end
        if redis.call('PTTL', KEYS[1]) < 0 then
            redis.call('PEXPIREAT', KEYS[1], ARGV[1])
        end
        return #ARGV - 1
        """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public UploadPartBitmapStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean exists(String uploadId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(uploadId)));
    }

    public boolean markCompleted(String uploadId, int partNumber, LocalDateTime expiresAt) {
        Long oldBit = redisTemplate.execute(
            MARK_COMPLETED,
            List.of(key(uploadId)),
            Integer.toString(partNumber),
            Long.toString(epochMillis(expiresAt))
        );
        return Long.valueOf(1L).equals(oldBit);
    }

    public void rebuild(String uploadId, List<Integer> completedParts, LocalDateTime expiresAt) {
        List<String> arguments = new ArrayList<>(completedParts.size() + 1);
        arguments.add(Long.toString(epochMillis(expiresAt)));
        completedParts.forEach(partNumber -> arguments.add(Integer.toString(partNumber)));
        redisTemplate.execute(REBUILD, List.of(key(uploadId)), arguments.toArray());
    }

    public long count(String uploadId) {
        byte[] redisKey = rawKey(uploadId);
        Long count = redisTemplate.execute(
            (RedisCallback<Long>) connection -> connection.stringCommands().bitCount(redisKey)
        );
        return count == null ? 0L : count;
    }

    public List<Integer> readCompleted(String uploadId, int firstPartNumber, int totalParts) {
        int lastPartNumber = firstPartNumber + totalParts - 1;
        byte[] value = redisTemplate.execute(
            (RedisCallback<byte[]>) connection -> connection.stringCommands().getRange(
                rawKey(uploadId), 0, lastPartNumber / Byte.SIZE
            )
        );
        if (value == null || value.length == 0) {
            return List.of();
        }
        List<Integer> completed = new ArrayList<>();
        for (int partNumber = firstPartNumber; partNumber <= lastPartNumber; partNumber++) {
            int byteIndex = partNumber / Byte.SIZE;
            int mask = 1 << (7 - partNumber % Byte.SIZE);
            if (byteIndex < value.length && (value[byteIndex] & mask) != 0) {
                completed.add(partNumber);
            }
        }
        return List.copyOf(completed);
    }

    public void delete(String uploadId) {
        redisTemplate.delete(key(uploadId));
    }

    static String key(String uploadId) {
        return KEY_PREFIX + uploadId;
    }

    private static byte[] rawKey(String uploadId) {
        return key(uploadId).getBytes(StandardCharsets.UTF_8);
    }

    private static long epochMillis(LocalDateTime expiresAt) {
        return expiresAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
