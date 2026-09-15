package com.videoagent.upload.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.storage.ObjectStorageService;
import com.videoagent.storage.ComposeObjectSource;
import com.videoagent.storage.StoredObject;
import com.videoagent.upload.dto.CompleteUploadRequest;
import com.videoagent.upload.dto.CompleteUploadResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

class UploadCompletionServiceTest {

    private final UploadCompletionTransaction transaction = mock(UploadCompletionTransaction.class);
    private final UploadFailureRecorder failureRecorder = mock(UploadFailureRecorder.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final UploadPartStateService partState = mock(UploadPartStateService.class);
    private UploadCompletionService service;

    @BeforeEach
    void setUp() {
        service = new UploadCompletionService(transaction, failureRecorder, storage, partState);
    }

    @Test
    void shouldValidatePartsComposeAndFinalizeOutsideTransactionOrchestration() {
        UploadCompletionAttempt attempt = attempt();
        CompleteUploadResponse completed = completed();
        when(transaction.beginCompletion(7L, "u1", request())).thenReturn(BeginCompletionResult.started(attempt));
        when(storage.statObjectIfExists("videos/final.mp4")).thenReturn(null);
        org.mockito.Mockito.doReturn(new StoredObject("p1", 24, "e1", "application/octet-stream"))
            .when(storage).statObjectIfExists("upload-parts/u1/part-00001");
        when(storage.statObject("videos/final.mp4"))
            .thenReturn(new StoredObject("videos/final.mp4", 24, "final", "video/mp4"));
        when(storage.readObjectRange("videos/final.mp4", 0, 12)).thenReturn(mp4Header());
        when(transaction.finalizeCompletion(attempt)).thenReturn(completed);

        assertThat(service.complete(7L, "u1", request())).isEqualTo(completed);

        verify(storage).composeObject(eq("videos/final.mp4"), any(), eq("video/mp4"));
        verify(partState).requirePossiblyComplete(attempt);
        verify(transaction).finalizeCompletion(attempt);
        verify(failureRecorder, never()).recordRetryableCompletionFailure(anyLong(), any(), any(), any());
    }

    @Test
    void shouldSkipPartValidationAndComposeWhenFinalObjectAlreadyExists() {
        UploadCompletionAttempt attempt = attempt();
        when(transaction.beginCompletion(7L, "u1", request())).thenReturn(BeginCompletionResult.started(attempt));
        when(storage.statObjectIfExists("videos/final.mp4"))
            .thenReturn(new StoredObject("videos/final.mp4", 24, "final", "video/mp4"));
        when(storage.readObjectRange("videos/final.mp4", 0, 12)).thenReturn(mp4Header());
        when(transaction.finalizeCompletion(attempt)).thenReturn(completed());

        service.complete(7L, "u1", request());

        verify(storage, never()).composeObject(any(), any(), any());
        verify(storage, never()).statObjectIfExists("upload-parts/u1/part-00001");
        verify(transaction).finalizeCompletion(attempt);
    }

    @Test
    void shouldNotTreatComposedObjectEtagAsTrustedSha256() {
        UploadCompletionAttempt attempt = attempt();
        when(transaction.beginCompletion(7L, "u1", request())).thenReturn(BeginCompletionResult.started(attempt));
        when(storage.statObjectIfExists("videos/final.mp4"))
            .thenReturn(new StoredObject("videos/final.mp4", 24, "multipart-etag-3", "video/mp4"));
        when(storage.readObjectRange("videos/final.mp4", 0, 12)).thenReturn(mp4Header());
        when(transaction.finalizeCompletion(attempt)).thenReturn(completed());

        assertThat(service.complete(7L, "u1", request())).isEqualTo(completed());

        verify(transaction).finalizeCompletion(attempt);
    }

    @Test
    void shouldRecordOwnedAttemptFailureWhenChunkStatOrComposeFails() {
        UploadCompletionAttempt attempt = attempt();
        VideoAgentException statFailure = new VideoAgentException(ErrorCode.STORAGE_ERROR, "stat unavailable");
        when(transaction.beginCompletion(7L, "u1", request())).thenReturn(BeginCompletionResult.started(attempt));
        when(storage.statObjectIfExists("videos/final.mp4")).thenReturn(null);
        when(storage.statObjectIfExists("upload-parts/u1/part-00001")).thenThrow(statFailure);

        assertThatThrownBy(() -> service.complete(7L, "u1", request())).isSameAs(statFailure);
        verify(failureRecorder).recordRetryableCompletionFailure(7L, "u1", "attempt-a", "stat unavailable");

        VideoAgentException composeFailure = new VideoAgentException(ErrorCode.STORAGE_ERROR, "compose unavailable");
        org.mockito.Mockito.doReturn(new StoredObject("p1", 24, "e1", "application/octet-stream"))
            .when(storage).statObjectIfExists("upload-parts/u1/part-00001");
        org.mockito.Mockito.doThrow(composeFailure).when(storage)
            .composeObject(eq("videos/final.mp4"), any(), eq("video/mp4"));

        assertThatThrownBy(() -> service.complete(7L, "u1", request())).isSameAs(composeFailure);
        verify(failureRecorder).recordRetryableCompletionFailure(7L, "u1", "attempt-a", "compose unavailable");
    }

    @Test
    void shouldRejectFromBitmapBeforePartStatsOrCompose() {
        UploadCompletionAttempt attempt = zeroBasedAttempt(3, 40L, 16L);
        VideoAgentException incomplete = new VideoAgentException(ErrorCode.UPLOAD_PART_INVALID, "incomplete");
        when(transaction.beginCompletion(7L, "u1", request())).thenReturn(BeginCompletionResult.started(attempt));
        when(storage.statObjectIfExists("videos/final.mp4")).thenReturn(null);
        doThrow(incomplete).when(partState).requirePossiblyComplete(attempt);

        assertThatThrownBy(() -> service.complete(7L, "u1", request())).isSameAs(incomplete);

        verify(storage, never()).statObjectIfExists("uploads/u1/parts/0");
        verify(storage, never()).composeObject(any(), any(), any());
        verify(failureRecorder).recordRetryableCompletionFailure(7L, "u1", "attempt-a", "incomplete");
    }

    @Test
    void shouldStillValidateAllMinioPartsAndComposeInIntegerOrderAfterBitmapPasses() {
        UploadCompletionAttempt attempt = zeroBasedAttempt(3, 40L, 16L);
        when(transaction.beginCompletion(7L, "u1", request())).thenReturn(BeginCompletionResult.started(attempt));
        when(storage.statObjectIfExists("videos/final.mp4")).thenReturn(null);
        when(storage.statObjectIfExists("uploads/u1/parts/0"))
            .thenReturn(new StoredObject("p0", 16L, "e0", "video/mp4"));
        when(storage.statObjectIfExists("uploads/u1/parts/1"))
            .thenReturn(new StoredObject("p1", 16L, "e1", "video/mp4"));
        when(storage.statObjectIfExists("uploads/u1/parts/2"))
            .thenReturn(new StoredObject("p2", 8L, "e2", "video/mp4"));
        when(storage.statObject("videos/final.mp4"))
            .thenReturn(new StoredObject("videos/final.mp4", 40L, "final", "video/mp4"));
        when(storage.readObjectRange("videos/final.mp4", 0, 12)).thenReturn(mp4Header());
        when(transaction.finalizeCompletion(attempt)).thenReturn(completed());

        service.complete(7L, "u1", request());

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<ComposeObjectSource>> sources =
            org.mockito.ArgumentCaptor.forClass((Class<List<ComposeObjectSource>>) (Class<?>) List.class);
        verify(storage).composeObject(eq("videos/final.mp4"), sources.capture(), eq("video/mp4"));
        assertThat(sources.getValue()).extracting(ComposeObjectSource::objectKey)
            .containsExactly("uploads/u1/parts/0", "uploads/u1/parts/1", "uploads/u1/parts/2");
    }

    @Test
    void shouldFailWhenBitmapPassesButMinioPartIsMissingOrWrongSized() {
        UploadCompletionAttempt attempt = zeroBasedAttempt(2, 24L, 16L);
        when(transaction.beginCompletion(7L, "u1", request())).thenReturn(BeginCompletionResult.started(attempt));
        when(storage.statObjectIfExists("videos/final.mp4")).thenReturn(null);
        when(storage.statObjectIfExists("uploads/u1/parts/0"))
            .thenReturn(new StoredObject("p0", 16L, "e0", "video/mp4"));
        when(storage.statObjectIfExists("uploads/u1/parts/1")).thenReturn(null);

        assertThatThrownBy(() -> service.complete(7L, "u1", request()))
            .isInstanceOfSatisfying(VideoAgentException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.UPLOAD_PART_INVALID));
        verify(storage, never()).composeObject(any(), any(), any());

        when(storage.statObjectIfExists("uploads/u1/parts/1"))
            .thenReturn(new StoredObject("p1", 9L, "bad", "video/mp4"));
        assertThatThrownBy(() -> service.complete(7L, "u1", request()))
            .isInstanceOfSatisfying(VideoAgentException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.UPLOAD_PART_INVALID));
    }

    @Test
    void shouldNotRecordFailureWhenBeginDidNotGrantOwnership() {
        VideoAgentException conflict = new VideoAgentException(
            ErrorCode.UPLOAD_SESSION_STATE_CONFLICT, "上传正在完成中"
        );
        when(transaction.beginCompletion(7L, "u1", request())).thenThrow(conflict);

        assertThatThrownBy(() -> service.complete(7L, "u1", request())).isSameAs(conflict);

        verify(failureRecorder, never()).recordRetryableCompletionFailure(anyLong(), any(), any(), any());
        verify(storage, never()).statObjectIfExists(any());
    }

    @Test
    void shouldReturnCompletedBeginResultWithoutAnyStorageIo() {
        CompleteUploadResponse completed = completed();
        when(transaction.beginCompletion(7L, "u1", request())).thenReturn(BeginCompletionResult.completed(completed));

        assertThat(service.complete(7L, "u1", request())).isEqualTo(completed);

        verify(storage, never()).statObjectIfExists(any());
        verify(storage, never()).composeObject(any(), any(), any());
        verify(transaction, never()).finalizeCompletion(any());
    }

    @Test
    void shouldFailWithoutOverwriteWhenExistingFinalObjectIsInvalid() {
        UploadCompletionAttempt attempt = attempt();
        when(transaction.beginCompletion(7L, "u1", request())).thenReturn(BeginCompletionResult.started(attempt));
        when(storage.statObjectIfExists("videos/final.mp4"))
            .thenReturn(new StoredObject("videos/final.mp4", 23, "bad", "video/mp4"));

        assertThatThrownBy(() -> service.complete(7L, "u1", request()))
            .isInstanceOfSatisfying(VideoAgentException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.UPLOAD_PART_INVALID));

        verify(storage, never()).composeObject(any(), any(), any());
        verify(failureRecorder).recordRetryableCompletionFailure(
            eq(7L), eq("u1"), eq("attempt-a"), any()
        );
    }

    @Test
    void shouldRejectInvalidMp4HeaderAfterCompose() {
        UploadCompletionAttempt attempt = attempt();
        when(transaction.beginCompletion(7L, "u1", request())).thenReturn(BeginCompletionResult.started(attempt));
        when(storage.statObjectIfExists("videos/final.mp4")).thenReturn(null);
        when(storage.statObjectIfExists("upload-parts/u1/part-00001"))
            .thenReturn(new StoredObject("p1", 24, "e1", "application/octet-stream"));
        when(storage.statObject("videos/final.mp4"))
            .thenReturn(new StoredObject("videos/final.mp4", 24, "final", "video/mp4"));
        when(storage.readObjectRange("videos/final.mp4", 0, 12)).thenReturn(new byte[12]);

        assertThatThrownBy(() -> service.complete(7L, "u1", request()))
            .isInstanceOfSatisfying(VideoAgentException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.VIDEO_FORMAT_NOT_SUPPORTED));
        verify(transaction, never()).finalizeCompletion(any());
    }

    private UploadCompletionAttempt attempt() {
        return new UploadCompletionAttempt(
            "u1", 7L, "videos/final.mp4", "upload-parts/u1", "video/mp4", 24L, 24L, 1,
            "a".repeat(64), "attempt-a", LocalDateTime.now(), LocalDateTime.now().plusHours(1)
        );
    }

    private UploadCompletionAttempt zeroBasedAttempt(int totalParts, long fileSize, long chunkSize) {
        return new UploadCompletionAttempt(
            "u1", 7L, "videos/final.mp4", "uploads/u1/parts", "video/mp4", fileSize, chunkSize,
            totalParts, "a".repeat(64), "attempt-a", LocalDateTime.now(), LocalDateTime.now().plusHours(1)
        );
    }

    private CompleteUploadRequest request() {
        return new CompleteUploadRequest("a".repeat(64));
    }

    private CompleteUploadResponse completed() {
        return new CompleteUploadResponse("u1", 42L, "COMPLETED", false);
    }

    private byte[] mp4Header() {
        return new byte[] {0, 0, 0, 0, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
    }
}
