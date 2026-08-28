package com.videoagent.upload.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.upload.dto.CompleteUploadResponse;

import org.junit.jupiter.api.Test;

class UploadCompletionServiceTest {

    private final UploadCompletionTransaction transaction = mock(UploadCompletionTransaction.class);
    private final UploadFailureRecorder failureRecorder = mock(UploadFailureRecorder.class);
    private final UploadCompletionService service = new UploadCompletionService(transaction, failureRecorder);

    @Test
    void shouldRecordFailureAndAllowSafeRetry() {
        VideoAgentException storageFailure = new VideoAgentException(ErrorCode.STORAGE_ERROR, "compose unavailable");
        CompleteUploadResponse completed = new CompleteUploadResponse("u1", 42L, "COMPLETED", false);
        when(transaction.complete(7L, "u1")).thenThrow(storageFailure).thenReturn(completed);

        assertThatThrownBy(() -> service.complete(7L, "u1")).isSameAs(storageFailure);
        verify(failureRecorder).recordRetryableCompletionFailure(7L, "u1", "compose unavailable");

        assertThat(service.complete(7L, "u1")).isEqualTo(completed);
    }
}
