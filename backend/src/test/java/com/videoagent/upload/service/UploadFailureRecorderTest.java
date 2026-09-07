package com.videoagent.upload.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.upload.entity.VideoUploadSessionEntity;
import com.videoagent.upload.repository.VideoUploadSessionRepository;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

class UploadFailureRecorderTest {

    private final VideoUploadSessionRepository sessions = mock(VideoUploadSessionRepository.class);
    private final UploadFailureRecorder recorder = new UploadFailureRecorder(sessions);

    @Test
    void shouldFailOnlyTheMatchingOwnedAttempt() {
        VideoUploadSessionEntity session = completing("attempt-a", LocalDateTime.now());
        when(sessions.lockById("u1")).thenReturn(session);

        recorder.recordRetryableCompletionFailure(7L, "u1", "attempt-a", "compose failed");

        verify(sessions).markCompletionFailed(
            eq("u1"), eq("attempt-a"), eq("compose failed"), any(LocalDateTime.class)
        );
    }

    @Test
    void shouldNotLetAnOldAttemptFailANewerAttempt() {
        VideoUploadSessionEntity session = completing("attempt-b", LocalDateTime.now());
        when(sessions.lockById("u1")).thenReturn(session);

        recorder.recordRetryableCompletionFailure(7L, "u1", "attempt-a", "late failure");

        verify(sessions, never()).markCompletionFailed(any(), any(), any(), any(LocalDateTime.class));
    }

    @Test
    void shouldRecoverOnlyTheSameTimedOutAttempt() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);
        VideoUploadSessionEntity timedOut = completing("attempt-a", cutoff.minusSeconds(1));
        when(sessions.lockById("u1")).thenReturn(timedOut);

        recorder.recordTimedOutCompletion("u1", "attempt-a", cutoff);

        verify(sessions).markCompletionFailed(
            eq("u1"), eq("attempt-a"), eq("COMPLETING_TIMEOUT"), any(LocalDateTime.class)
        );

        VideoUploadSessionEntity newer = completing("attempt-b", cutoff.plusSeconds(1));
        when(sessions.lockById("u2")).thenReturn(newer);
        recorder.recordTimedOutCompletion("u2", "attempt-a", cutoff);
        verify(sessions, never()).markCompletionFailed(
            eq("u2"), eq("attempt-a"), eq("COMPLETING_TIMEOUT"), any(LocalDateTime.class)
        );
    }

    private VideoUploadSessionEntity completing(String token, LocalDateTime completingAt) {
        VideoUploadSessionEntity session = new VideoUploadSessionEntity();
        session.setId("u1");
        session.setUserId(7L);
        session.setStatus("COMPLETING");
        session.setCompletionToken(token);
        session.setCompletingAt(completingAt);
        return session;
    }
}
