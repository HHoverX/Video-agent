package com.videoagent.analysis.event;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.videoagent.analysis.dto.AnalysisProgressEventResponse;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

class AnalysisEventBroadcasterTest {

    private final AnalysisEventBroadcaster broadcaster = new AnalysisEventBroadcaster();

    @Test
    void shouldSendProcessingAndCompleteAfterSuccess() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        broadcaster.register(101L, emitter);

        broadcaster.publish(event("PROCESSING", "SUMMARIZING", 85));
        broadcaster.publish(event("SUCCESS", "DONE", 100));

        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        org.assertj.core.api.Assertions.assertThat(broadcaster.subscriberCount(101L)).isZero();
    }

    @Test
    void shouldSendFailedEventAndComplete() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        broadcaster.register(101L, emitter);

        broadcaster.publish(new AnalysisProgressEventResponse(
            101L, 7L, "FAILED", "FAILED", 35, "ffmpeg failed", "FFMPEG_FAILED", "ffmpeg failed"
        ));

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        org.assertj.core.api.Assertions.assertThat(broadcaster.subscriberCount(101L)).isZero();
    }

    @Test
    void shouldRemoveClientOnCompletionTimeoutAndError() {
        SseEmitter completed = mock(SseEmitter.class);
        broadcaster.register(101L, completed);
        ArgumentCaptor<Runnable> completion = ArgumentCaptor.forClass(Runnable.class);
        verify(completed).onCompletion(completion.capture());
        completion.getValue().run();
        org.assertj.core.api.Assertions.assertThat(broadcaster.subscriberCount(101L)).isZero();

        SseEmitter timedOut = mock(SseEmitter.class);
        broadcaster.register(102L, timedOut);
        ArgumentCaptor<Runnable> timeout = ArgumentCaptor.forClass(Runnable.class);
        verify(timedOut).onTimeout(timeout.capture());
        timeout.getValue().run();
        verify(timedOut).complete();
        org.assertj.core.api.Assertions.assertThat(broadcaster.subscriberCount(102L)).isZero();

        SseEmitter errored = mock(SseEmitter.class);
        broadcaster.register(103L, errored);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.function.Consumer<Throwable>> error =
            ArgumentCaptor.forClass(java.util.function.Consumer.class);
        verify(errored).onError(error.capture());
        error.getValue().accept(new IOException("client closed"));
        org.assertj.core.api.Assertions.assertThat(broadcaster.subscriberCount(103L)).isZero();
    }

    @Test
    void shouldIsolateDisconnectedClientFromOtherSubscribersAndTask() throws Exception {
        SseEmitter disconnected = mock(SseEmitter.class);
        SseEmitter connected = mock(SseEmitter.class);
        doThrow(new IOException("broken pipe"))
            .when(disconnected).send(any(SseEmitter.SseEventBuilder.class));
        broadcaster.register(101L, disconnected);
        broadcaster.register(101L, connected);

        assertThatCode(() -> broadcaster.publish(event("PROCESSING", "TRANSCRIBING", 70)))
            .doesNotThrowAnyException();

        verify(connected).send(any(SseEmitter.SseEventBuilder.class));
        org.assertj.core.api.Assertions.assertThat(broadcaster.subscriberCount(101L)).isEqualTo(1);
    }

    private AnalysisProgressEventResponse event(String status, String stage, int progress) {
        return new AnalysisProgressEventResponse(
            101L, 7L, status, stage, progress, "message", null, null
        );
    }
}
