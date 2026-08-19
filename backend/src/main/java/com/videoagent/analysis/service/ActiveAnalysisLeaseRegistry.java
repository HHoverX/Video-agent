package com.videoagent.analysis.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local registry of work this JVM actually owns. Heartbeats are sent
 * only for entries registered by the consumer thread after a successful DB
 * claim. If the JVM dies, the registry disappears; if a call hangs past the
 * execution limit, the entry expires and the DB lease can be recovered.
 */
@Component
public class ActiveAnalysisLeaseRegistry {

    public record ActiveLease(long taskId, long videoId, int generation, Instant startedAt) {
    }

    private final ConcurrentHashMap<Long, ActiveLease> active = new ConcurrentHashMap<>();

    public ActiveLease register(long taskId, long videoId, int generation) {
        ActiveLease lease = new ActiveLease(taskId, videoId, generation, Instant.now());
        active.put(taskId, lease);
        return lease;
    }

    public void unregister(long taskId, int generation) {
        active.computeIfPresent(taskId, (ignored, lease) ->
            lease.generation() == generation ? null : lease
        );
    }

    public List<ActiveLease> snapshot() {
        return List.copyOf(active.values());
    }
}
