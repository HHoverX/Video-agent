package com.videoagent.analysis.event;

import com.videoagent.analysis.dto.AnalysisProgressEventResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class AnalysisEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(AnalysisEventBroadcaster.class);
    private static final String PROGRESS_EVENT = "progress";

    private final ConcurrentMap<Long, Set<Subscription>> subscriptions = new ConcurrentHashMap<>();

    public void register(long taskId, SseEmitter emitter) {
        Subscription subscription = new Subscription(taskId, emitter);
        subscriptions.computeIfAbsent(taskId, ignored -> ConcurrentHashMap.newKeySet())
            .add(subscription);
        emitter.onCompletion(() -> remove(subscription));
        emitter.onTimeout(() -> {
            remove(subscription);
            completeQuietly(emitter);
        });
        emitter.onError(error -> remove(subscription));
    }

    public void send(long taskId, AnalysisProgressEventResponse event) {
        Set<Subscription> current = subscriptions.get(taskId);
        if (current == null) {
            return;
        }
        for (Subscription subscription : current) {
            deliver(subscription, event);
        }
    }

    public void publish(AnalysisProgressEventResponse event) {
        send(event.taskId(), event);
    }

    public void disconnect(long taskId, SseEmitter emitter) {
        Set<Subscription> current = subscriptions.get(taskId);
        if (current == null) {
            return;
        }
        current.stream()
            .filter(subscription -> subscription.emitter == emitter)
            .findFirst()
            .ifPresent(subscription -> {
                remove(subscription);
                completeQuietly(emitter);
            });
    }

    int subscriberCount(long taskId) {
        Set<Subscription> current = subscriptions.get(taskId);
        return current == null ? 0 : current.size();
    }

    private void deliver(Subscription subscription, AnalysisProgressEventResponse event) {
        boolean terminal = event.terminal();
        synchronized (subscription) {
            if (subscription.closed || (!terminal && event.progress() < subscription.lastProgress)) {
                return;
            }
            try {
                subscription.emitter.send(SseEmitter.event()
                    .name(PROGRESS_EVENT)
                    .data(event, MediaType.APPLICATION_JSON));
                subscription.lastProgress = Math.max(subscription.lastProgress, event.progress());
                if (terminal) {
                    subscription.closed = true;
                }
            } catch (IOException | IllegalStateException exception) {
                subscription.closed = true;
                remove(subscription);
                log.debug("SSE client disconnected for analysis task {}", subscription.taskId, exception);
                return;
            }
        }

        if (terminal) {
            remove(subscription);
            completeQuietly(subscription.emitter);
        }
    }

    private void remove(Subscription subscription) {
        subscriptions.computeIfPresent(subscription.taskId, (ignored, current) -> {
            current.remove(subscription);
            return current.isEmpty() ? null : current;
        });
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException exception) {
            log.debug("SSE emitter was already complete", exception);
        }
    }

    private static final class Subscription {

        private final long taskId;
        private final SseEmitter emitter;
        private int lastProgress = -1;
        private boolean closed;

        private Subscription(long taskId, SseEmitter emitter) {
            this.taskId = taskId;
            this.emitter = emitter;
        }
    }
}
