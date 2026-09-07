package com.videoagent.upload.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.videoagent.auth.repository.AppUserRepository;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.storage.ObjectStorageService;
import com.videoagent.storage.StoredObject;
import com.videoagent.testsupport.TestAuthClient;
import com.videoagent.testsupport.TestAuthClient.Session;
import com.videoagent.upload.dto.CompleteUploadRequest;
import com.videoagent.upload.dto.CompleteUploadResponse;
import com.videoagent.upload.dto.CreateUploadSessionRequest;
import com.videoagent.upload.entity.VideoUploadSessionEntity;
import com.videoagent.upload.repository.VideoUploadSessionRepository;
import com.videoagent.video.repository.VideoRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@EnabledIfEnvironmentVariable(named = "VIDEOAGENT_UPLOAD_INFRA_TEST", matches = "true")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "videoagent.security.jwt.secret=" + TestAuthClient.JWT_SECRET,
        "videoagent.analysis.consumer-group=videoagent-upload-boundary-${random.uuid}",
        "videoagent.ai.asr.provider=mock",
        "videoagent.ai.llm.provider=mock",
        "videoagent.upload.completion-timeout=1s",
        "videoagent.upload.completion-recovery-interval-ms=3600000",
        "videoagent.upload.cleanup-interval-ms=3600000"
    }
)
class UploadCompletionTransactionBoundaryInfrastructureIntegrationTest {

    private static final byte[] MP4_HEADER = {
        0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'
    };

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UploadSessionService uploadSessionService;

    @Autowired
    private UploadCompletionService completionService;

    @Autowired
    private UploadCompletionTransaction completionTransaction;

    @Autowired
    private UploadCompletionRecoveryJob recoveryJob;

    @Autowired
    private UploadFailureRecorder failureRecorder;

    @Autowired
    private VideoUploadSessionRepository sessionRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ObjectStorageService storage;

    @MockitoSpyBean
    private UploadPartBitmapStore bitmapStore;

    private Session owner;
    private VideoUploadSessionEntity session;

    @BeforeEach
    void setUp() {
        assertBitmapCallsAreOutsideTransactions();
        owner = TestAuthClient.registerAndLogin(
            restTemplate, "http://127.0.0.1:" + port, "upload-boundary-" + System.nanoTime()
        );
        String hash = "a".repeat(64);
        String uploadId = uploadSessionService.create(owner.userId(), new CreateUploadSessionRequest(
            "boundary.mp4", "boundary", 24L, "video/mp4", null, hash
        )).uploadId();
        session = sessionRepository.selectById(uploadId);
    }

    @AfterEach
    void cleanUp() {
        if (session != null) {
            bitmapStore.delete(session.getId());
            VideoUploadSessionEntity current = sessionRepository.selectById(session.getId());
            if (current != null && current.getVideoId() != null) {
                videoRepository.deleteById(current.getVideoId());
            }
            sessionRepository.deleteById(session.getId());
        }
        if (owner != null) {
            userRepository.deleteById(owner.userId());
        }
    }

    @Test
    void shouldCommitCompletingBeforeStorageAndRejectConcurrentCompleteWithoutFailingOwner() throws Exception {
        CountDownLatch composeEntered = new CountDownLatch(1);
        CountDownLatch releaseCompose = new CountDownLatch(1);
        assertStorageCallsAreOutsideTransactions();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            composeEntered.countDown();
            assertThat(releaseCompose.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(storage).composeObject(any(), any(), any());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<CompleteUploadResponse> first = executor.submit(
            () -> completionService.complete(owner.userId(), session.getId(), request())
        );
        try {
            assertThat(composeEntered.await(10, TimeUnit.SECONDS)).isTrue();
            Map<String, Object> visible = jdbcTemplate.queryForMap(
                "SELECT status, completion_token FROM video_upload_session WHERE id = ?",
                session.getId()
            );
            assertThat(visible.get("status")).isEqualTo("COMPLETING");
            assertThat(visible.get("completion_token")).isNotNull();

            HttpHeaders headers = owner.headers();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> concurrent = restTemplate.exchange(
                "http://127.0.0.1:" + port + "/api/uploads/" + session.getId() + "/complete",
                HttpMethod.POST,
                new HttpEntity<>(request(), headers),
                String.class
            );
            assertThat(concurrent.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(sessionRepository.selectById(session.getId()).getStatus()).isEqualTo("COMPLETING");
            verify(storage).composeObject(any(), any(), any());
        } finally {
            releaseCompose.countDown();
            executor.shutdown();
        }

        CompleteUploadResponse response = first.get(10, TimeUnit.SECONDS);
        assertThat(response.videoId()).isNotNull();
        assertThat(sessionRepository.selectById(session.getId()).getStatus()).isEqualTo("COMPLETED");

        clearInvocations(storage);
        CompleteUploadResponse repeated = completionService.complete(owner.userId(), session.getId(), request());
        assertThat(repeated.videoId()).isEqualTo(response.videoId());
        verifyNoInteractions(storage);
    }

    @Test
    void shouldRecoverAfterComposeWithoutComposingAgain() {
        markAsTimedOut("attempt-a");
        when(storage.statObjectIfExists(session.getObjectKey()))
            .thenReturn(new StoredObject(session.getObjectKey(), 24L, "final", "video/mp4"));
        when(storage.readObjectRange(session.getObjectKey(), 0, 12)).thenReturn(MP4_HEADER);

        recoveryJob.recoverTimedOutCompletions();

        VideoUploadSessionEntity failed = sessionRepository.selectById(session.getId());
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getCompletingAt()).isNull();
        assertThat(failed.getCompletionToken()).isNull();
        assertThat(failed.getLastError()).isEqualTo("COMPLETING_TIMEOUT");

        CompleteUploadResponse response = completionService.complete(owner.userId(), session.getId(), request());

        assertThat(response.videoId()).isNotNull();
        verify(storage, never()).composeObject(any(), any(), any());
        assertThat(sessionRepository.selectById(session.getId()).getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void shouldPersistFailedWhenChunkValidationFailsAfterBeginCommit() {
        when(storage.statObjectIfExists(session.getObjectKey())).thenReturn(null);
        when(storage.statObjectIfExists(UploadKeyPolicy.partObjectKey(session.getTempPrefix(), 0)))
            .thenThrow(new VideoAgentException(ErrorCode.STORAGE_ERROR, "chunk stat failed"));

        assertThatThrownBy(() -> completionService.complete(owner.userId(), session.getId(), request()))
            .isInstanceOf(VideoAgentException.class)
            .hasMessage("chunk stat failed");

        assertFailedAndUnowned("chunk stat failed");
        verify(storage, never()).composeObject(any(), any(), any());
    }

    @Test
    void shouldPersistFailedWhenComposeFailsAfterBeginCommit() {
        when(storage.statObjectIfExists(session.getObjectKey())).thenReturn(null);
        when(storage.statObjectIfExists(UploadKeyPolicy.partObjectKey(session.getTempPrefix(), 0)))
            .thenReturn(new StoredObject("part", 24L, "etag-1", "application/octet-stream"));
        doThrow(new VideoAgentException(ErrorCode.STORAGE_ERROR, "compose failed"))
            .when(storage).composeObject(any(), any(), any());

        assertThatThrownBy(() -> completionService.complete(owner.userId(), session.getId(), request()))
            .isInstanceOf(VideoAgentException.class)
            .hasMessage("compose failed");

        assertFailedAndUnowned("compose failed");
    }

    @Test
    void shouldFenceRecoveredOldAttemptFromNewAttempt() {
        markAsTimedOut("attempt-a");
        recoveryJob.recoverTimedOutCompletions();
        BeginCompletionResult beginB = completionTransaction.beginCompletion(owner.userId(), session.getId(), request());
        UploadCompletionAttempt attemptA = new UploadCompletionAttempt(
            session.getId(), owner.userId(), session.getObjectKey(), session.getTempPrefix(), session.getContentType(),
            session.getFileSize(), session.getChunkSize(), session.getTotalParts(), session.getExpectedSha256(),
            "attempt-a", LocalDateTime.now().minusMinutes(2), session.getExpiresAt()
        );

        assertThatThrownBy(() -> completionTransaction.finalizeCompletion(attemptA))
            .isInstanceOfSatisfying(VideoAgentException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.UPLOAD_SESSION_STATE_CONFLICT));
        failureRecorder.recordRetryableCompletionFailure(
            owner.userId(), session.getId(), "attempt-a", "late attempt"
        );

        VideoUploadSessionEntity current = sessionRepository.selectById(session.getId());
        assertThat(current.getStatus()).isEqualTo("COMPLETING");
        assertThat(current.getCompletionToken()).isEqualTo(beginB.attempt().completionToken());
    }

    private void assertStorageCallsAreOutsideTransactions() {
        when(storage.statObjectIfExists(session.getObjectKey())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        });
        when(storage.statObjectIfExists(UploadKeyPolicy.partObjectKey(session.getTempPrefix(), 0))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new StoredObject(invocation.getArgument(0), 24L, "etag-1", "application/octet-stream");
        });
        when(storage.statObject(session.getObjectKey())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new StoredObject(session.getObjectKey(), 24L, "final", "video/mp4");
        });
        when(storage.readObjectRange(session.getObjectKey(), 0, 12)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return MP4_HEADER;
        });
    }

    private void assertBitmapCallsAreOutsideTransactions() {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(bitmapStore).exists(any());
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(bitmapStore).count(any());
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(bitmapStore).rebuild(any(), anyList(), any(LocalDateTime.class));
    }

    private void markAsTimedOut(String token) {
        VideoUploadSessionEntity current = sessionRepository.selectById(session.getId());
        current.setStatus("COMPLETING");
        current.setCompletionToken(token);
        current.setCompletingAt(LocalDateTime.now().minusMinutes(2));
        current.setUpdatedAt(LocalDateTime.now());
        assertThat(sessionRepository.updateById(current)).isEqualTo(1);
    }

    private void assertFailedAndUnowned(String error) {
        VideoUploadSessionEntity failed = sessionRepository.selectById(session.getId());
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getCompletingAt()).isNull();
        assertThat(failed.getCompletionToken()).isNull();
        assertThat(failed.getLastError()).isEqualTo(error);
    }

    private CompleteUploadRequest request() {
        return new CompleteUploadRequest("a".repeat(64));
    }
}
