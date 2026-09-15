package com.videoagent.upload.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.upload.dto.CompleteUploadRequest;
import com.videoagent.upload.dto.CompleteUploadResponse;
import com.videoagent.upload.entity.VideoUploadSessionEntity;
import com.videoagent.upload.repository.VideoUploadSessionRepository;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

class UploadCompletionTransactionTest {

    private final VideoUploadSessionRepository sessions = mock(VideoUploadSessionRepository.class);
    private final VideoRepository videos = mock(VideoRepository.class);
    private final UploadTemporaryObjectCleaner cleaner = mock(UploadTemporaryObjectCleaner.class);
    private UploadCompletionTransaction transaction;

    @BeforeEach
    void setUp() {
        transaction = new UploadCompletionTransaction(sessions, videos, cleaner);
    }

    @Test
    void shouldCommitAnImmutableAttemptContextFromBegin() {
        VideoUploadSessionEntity session = session("UPLOADING");
        when(sessions.lockById("u1")).thenReturn(session);
        when(sessions.markCompletionStarted(any(), any(), any(), any(LocalDateTime.class), any())).thenReturn(1);

        BeginCompletionResult result = transaction.beginCompletion(7L, "u1", completeRequest());

        assertThat(result.completedResponse()).isNull();
        assertThat(result.attempt().completionToken()).isNotBlank();
        assertThat(result.attempt().completingAt()).isNotNull();
        assertThat(result.attempt().tempPrefix()).isEqualTo("upload-parts/u1");
        assertThat(session.getStatus()).isEqualTo("COMPLETING");
        assertThat(session.getCompletionToken()).isEqualTo(result.attempt().completionToken());
        assertThat(session.getCompletingAt()).isEqualTo(result.attempt().completingAt());
    }

    @Test
    void shouldReturnCompletedResultFromBeginWithoutStartingAnotherAttempt() {
        VideoUploadSessionEntity session = session("COMPLETED");
        session.setVideoId(42L);
        when(sessions.lockById("u1")).thenReturn(session);
        when(videos.selectById(42L)).thenReturn(video(42L, "videos/final.mp4"));

        BeginCompletionResult result = transaction.beginCompletion(7L, "u1", completeRequest());

        assertThat(result.completedResponse().videoId()).isEqualTo(42L);
        assertThat(result.attempt()).isNull();
        verify(sessions, never()).markCompletionStarted(any(), any(), any(), any(LocalDateTime.class), any());
    }

    @Test
    void shouldRejectConcurrentBeginWithoutTakingOwnership() {
        VideoUploadSessionEntity session = session("COMPLETING");
        session.setCompletionToken("active-token");
        when(sessions.lockById("u1")).thenReturn(session);

        assertThatThrownBy(() -> transaction.beginCompletion(7L, "u1", completeRequest()))
            .isInstanceOfSatisfying(VideoAgentException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.UPLOAD_SESSION_STATE_CONFLICT));
        verify(sessions, never()).markCompletionStarted(any(), any(), any(), any(LocalDateTime.class), any());
    }

    @Test
    void shouldAllowFailedSessionToStartANewAttempt() {
        VideoUploadSessionEntity session = session("FAILED");
        session.setCompletionToken(null);
        session.setCompletingAt(null);
        when(sessions.lockById("u1")).thenReturn(session);
        when(sessions.markCompletionStarted(any(), any(), any(), any(LocalDateTime.class), any())).thenReturn(1);

        UploadCompletionAttempt attempt = transaction.beginCompletion(7L, "u1", completeRequest()).attempt();

        assertThat(attempt.completionToken()).isNotBlank();
        assertThat(session.getStatus()).isEqualTo("COMPLETING");
    }

    @Test
    void shouldAcceptLegacyHashAndRejectMismatchedHash() {
        VideoUploadSessionEntity legacy = session("UPLOADING");
        legacy.setExpectedSha256(null);
        when(sessions.lockById("legacy")).thenReturn(legacy);
        when(sessions.markCompletionStarted(any(), any(), any(), any(LocalDateTime.class), any())).thenReturn(1);
        legacy.setId("legacy");

        UploadCompletionAttempt legacyAttempt = transaction.beginCompletion(7L, "legacy", completeRequest()).attempt();

        assertThat(legacy.getExpectedSha256()).isEqualTo(hash());
        assertThat(legacyAttempt.expectedSha256()).isEqualTo(hash());

        VideoUploadSessionEntity current = session("UPLOADING");
        when(sessions.lockById("u1")).thenReturn(current);
        assertThatThrownBy(() -> transaction.beginCompletion(
            7L, "u1", new CompleteUploadRequest("b".repeat(64))
        )).isInstanceOfSatisfying(VideoAgentException.class,
            error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.UPLOAD_PART_INVALID));
        assertThat(current.getExpectedSha256()).isEqualTo(hash());
    }

    @Test
    void shouldUseSessionHashWhenFinalizeRequestOmitsRepeatedDeclaration() {
        VideoUploadSessionEntity session = session("UPLOADING");
        when(sessions.lockById("u1")).thenReturn(session);
        when(sessions.markCompletionStarted(any(), any(), any(), any(LocalDateTime.class), any())).thenReturn(1);

        UploadCompletionAttempt attempt = transaction.beginCompletion(7L, "u1", null).attempt();

        assertThat(attempt.expectedSha256()).isEqualTo(hash());
    }

    @Test
    void shouldFinalizeOnlyTheMatchingAttemptAndUseExpectedHash() {
        VideoUploadSessionEntity session = session("COMPLETING");
        session.setCompletionToken("attempt-b");
        session.setCompletingAt(LocalDateTime.now());
        when(sessions.lockById("u1")).thenReturn(session);
        when(videos.findByUserIdAndFileHash(7L, hash())).thenReturn(video(42L, "videos/final.mp4"));
        when(sessions.markCompletionCompleted(any(), any(), anyLong(), any(LocalDateTime.class))).thenReturn(1);
        UploadCompletionAttempt attempt = attempt("attempt-b");

        CompleteUploadResponse response = transaction.finalizeCompletion(attempt);

        assertThat(response.videoId()).isEqualTo(42L);
        var captor = org.mockito.ArgumentCaptor.forClass(VideoEntity.class);
        verify(videos).insertOrReuseByUserAndFileHash(captor.capture());
        assertThat(captor.getValue().getFileHash()).isEqualTo(hash());
        verify(sessions).markCompletionCompleted("u1", "attempt-b", 42L, session.getCompletedAt());
        verify(cleaner).cleanupAfterCommit(session);
        assertThat(session.getCompletionToken()).isNull();
        assertThat(session.getCompletingAt()).isNull();
    }

    @Test
    void shouldFenceAnOldAttemptFromFinalizingANewerAttempt() {
        VideoUploadSessionEntity session = session("COMPLETING");
        session.setCompletionToken("attempt-b");
        when(sessions.lockById("u1")).thenReturn(session);

        assertThatThrownBy(() -> transaction.finalizeCompletion(attempt("attempt-a")))
            .isInstanceOfSatisfying(VideoAgentException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.UPLOAD_SESSION_STATE_CONFLICT));
        verify(videos, never()).insertOrReuseByUserAndFileHash(any(VideoEntity.class));
        verify(sessions, never()).markCompletionCompleted(any(), any(), anyLong(), any(LocalDateTime.class));
        assertThat(session.getCompletionToken()).isEqualTo("attempt-b");
    }

    @Test
    void shouldReturnExistingResultWhenFinalizeFindsCompleted() {
        VideoUploadSessionEntity session = session("COMPLETED");
        session.setVideoId(42L);
        when(sessions.lockById("u1")).thenReturn(session);
        when(videos.selectById(42L)).thenReturn(video(42L, "videos/final.mp4"));

        CompleteUploadResponse response = transaction.finalizeCompletion(attempt("old-attempt"));

        assertThat(response.videoId()).isEqualTo(42L);
        verify(videos, never()).insertOrReuseByUserAndFileHash(any(VideoEntity.class));
    }

    private VideoUploadSessionEntity session(String status) {
        VideoUploadSessionEntity session = new VideoUploadSessionEntity();
        session.setId("u1");
        session.setUserId(7L);
        session.setFileName("lesson.mp4");
        session.setTitle("lesson");
        session.setFileSize(24L);
        session.setContentType("video/mp4");
        session.setChunkSize(24L);
        session.setTotalParts(1);
        session.setTempPrefix("upload-parts/u1");
        session.setObjectKey("videos/final.mp4");
        session.setExpectedSha256(hash());
        session.setStatus(status);
        session.setExpiresAt(LocalDateTime.now().plusHours(1));
        return session;
    }

    private UploadCompletionAttempt attempt(String token) {
        return new UploadCompletionAttempt(
            "u1", 7L, "videos/final.mp4", "upload-parts/u1", "video/mp4", 24L, 24L, 1,
            hash(), token, LocalDateTime.now(), LocalDateTime.now().plusHours(1)
        );
    }

    private CompleteUploadRequest completeRequest() {
        return new CompleteUploadRequest(hash());
    }

    private String hash() {
        return "a".repeat(64);
    }

    private VideoEntity video(long id, String objectKey) {
        VideoEntity video = new VideoEntity();
        video.setId(id);
        video.setObjectKey(objectKey);
        return video;
    }
}
