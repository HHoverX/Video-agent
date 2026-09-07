package com.videoagent.upload.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.upload.entity.VideoUploadSessionEntity;
import com.videoagent.upload.repository.VideoUploadSessionRepository;
import com.videoagent.video.service.VideoUploadProperties;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

class UploadCompletionRecoveryJobTest {

    @Test
    void shouldScanConfiguredTimeoutAndFenceEachCandidateByToken() {
        VideoUploadSessionRepository sessions = mock(VideoUploadSessionRepository.class);
        UploadFailureRecorder recorder = mock(UploadFailureRecorder.class);
        VideoUploadProperties properties = new VideoUploadProperties(
            DataSize.ofGigabytes(20), DataSize.ofMegabytes(16), DataSize.ofMegabytes(5),
            DataSize.ofMegabytes(128), 10_000, Duration.ofHours(24), Duration.ofMinutes(15), 3,
            Duration.ofMinutes(30)
        );
        VideoUploadSessionEntity candidate = new VideoUploadSessionEntity();
        candidate.setId("u1");
        candidate.setCompletionToken("attempt-a");
        when(sessions.findTimedOutCompletions(any(LocalDateTime.class), eq(100)))
            .thenReturn(List.of(candidate));
        UploadCompletionRecoveryJob job = new UploadCompletionRecoveryJob(sessions, recorder, properties);

        job.recoverTimedOutCompletions();

        var cutoff = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(sessions).findTimedOutCompletions(cutoff.capture(), eq(100));
        verify(recorder).recordTimedOutCompletion("u1", "attempt-a", cutoff.getValue());
    }
}
