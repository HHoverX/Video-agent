package com.videoagent.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.auth.repository.AppUserRepository;
import com.videoagent.storage.StorageProperties;
import com.videoagent.testsupport.TestAuthClient;
import com.videoagent.testsupport.TestAuthClient.Session;
import com.videoagent.upload.dto.CompleteUploadResponse;
import com.videoagent.upload.dto.CreateUploadSessionRequest;
import com.videoagent.upload.dto.UploadPartResponse;
import com.videoagent.upload.dto.UploadPartUrlResponse;
import com.videoagent.upload.dto.UploadSessionResponse;
import com.videoagent.upload.entity.VideoUploadSessionEntity;
import com.videoagent.upload.repository.VideoUploadPartRepository;
import com.videoagent.upload.repository.VideoUploadSessionRepository;
import com.videoagent.upload.service.UploadPartBitmapStore;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.security.MessageDigest;
import java.net.URI;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Real MySQL + MinIO coverage for the resumable upload protocol. This test uses
 * the presigned URL returned by the API, so the part bytes do not pass through
 * Spring MVC.
 */
@EnabledIfEnvironmentVariable(named = "VIDEOAGENT_UPLOAD_INFRA_TEST", matches = "true")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "videoagent.security.jwt.secret=" + TestAuthClient.JWT_SECRET,
        "videoagent.analysis.consumer-group=videoagent-upload-infra-${random.uuid}",
        "videoagent.ai.asr.provider=mock",
        "videoagent.ai.llm.provider=mock"
    }
)
class ResumableUploadInfrastructureIntegrationTest {

    private static final int CHUNK_SIZE = 5 * 1024 * 1024;
    private static final byte[] MP4_HEADER = {
        0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm',
        0, 0, 0, 0, 'i', 's', 'o', 'm', 'm', 'p', '4', '2'
    };

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private VideoUploadSessionRepository sessionRepository;

    @Autowired
    private VideoUploadPartRepository partRepository;

    @Autowired
    private UploadPartBitmapStore bitmapStore;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private AnalysisTaskRepository taskRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private StorageProperties storageProperties;

    private Session owner;
    private Session stranger;
    private String uploadId;
    private String secondaryUploadId;
    private String objectKey;
    private String secondaryObjectKey;
    private Long videoId;
    private Long secondaryVideoId;

    @AfterEach
    void cleanUp() throws Exception {
        if (objectKey != null) {
            minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(storageProperties.bucket())
                .object(objectKey)
                .build());
        }
        if (secondaryObjectKey != null) {
            minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(storageProperties.bucket())
                .object(secondaryObjectKey)
                .build());
        }
        if (uploadId != null) {
            bitmapStore.delete(uploadId);
            sessionRepository.deleteById(uploadId);
        }
        if (secondaryUploadId != null) {
            bitmapStore.delete(secondaryUploadId);
            sessionRepository.deleteById(secondaryUploadId);
        }
        if (videoId != null) {
            videoRepository.deleteById(videoId);
        }
        if (secondaryVideoId != null) {
            videoRepository.deleteById(secondaryVideoId);
        }
        if (stranger != null) {
            userRepository.deleteById(stranger.userId());
        }
        if (owner != null) {
            userRepository.deleteById(owner.userId());
        }
    }

    @Test
    void shouldResumeRetryAndCompleteExactlyOnceWithDirectMinioParts() throws Exception {
        owner = TestAuthClient.registerAndLogin(
            restTemplate, baseUrl(""), "upload-owner-" + System.nanoTime()
        );
        stranger = TestAuthClient.registerAndLogin(
            restTemplate, baseUrl(""), "upload-stranger-" + System.nanoTime()
        );

        byte[] partOne = new byte[CHUNK_SIZE];
        System.arraycopy(MP4_HEADER, 0, partOne, 0, MP4_HEADER.length);
        byte[] partTwo = "tail-of-resumable-infrastructure-video".getBytes();
        byte[] completeFile = new byte[partOne.length + partTwo.length];
        System.arraycopy(partOne, 0, completeFile, 0, partOne.length);
        System.arraycopy(partTwo, 0, completeFile, partOne.length, partTwo.length);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(completeFile));

        ResponseEntity<UploadSessionResponse> created = restTemplate.exchange(
            baseUrl("/api/uploads"),
            HttpMethod.POST,
            jsonEntity(owner, new CreateUploadSessionRequest(
                "resumable.mp4", "Resumable infrastructure video",
                (long) completeFile.length, "video/mp4", (long) CHUNK_SIZE, sha256
            )),
            UploadSessionResponse.class
        );
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        uploadId = created.getBody().uploadId();
        assertThat(created.getBody().totalParts()).isEqualTo(2);
        assertThat(created.getBody().partNumberBase()).isZero();
        VideoUploadSessionEntity persistedSession = sessionRepository.selectById(uploadId);
        objectKey = persistedSession.getObjectKey();
        assertThat(objectKey).startsWith("videos/").doesNotContain(uploadId);
        assertThat(persistedSession.getTempPrefix()).isEqualTo("uploads/" + uploadId + "/parts");

        ResponseEntity<String> crossUserRead = restTemplate.exchange(
            baseUrl("/api/uploads/" + uploadId),
            HttpMethod.GET,
            new HttpEntity<>(stranger.headers()),
            String.class
        );
        assertThat(crossUserRead.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        UploadPartResponse firstConfirmation = uploadAndConfirm(owner, uploadId, 0, partOne);
        assertThat(bitmapStore.exists(uploadId)).isTrue();
        assertThat(bitmapStore.count(uploadId)).isEqualTo(1);
        long firstTtl = redisTemplate.getExpire(bitmapKey(uploadId), TimeUnit.MILLISECONDS);
        long expectedTtl = java.time.Duration.between(LocalDateTime.now(), persistedSession.getExpiresAt()).toMillis();
        assertThat(firstTtl).isPositive().isBetween(expectedTtl - 5_000, expectedTtl + 1_000);
        ResponseEntity<UploadPartResponse> duplicateConfirmation = restTemplate.exchange(
            baseUrl("/api/uploads/" + uploadId + "/parts/0/complete"),
            HttpMethod.POST,
            jsonEntity(owner, Map.of()),
            UploadPartResponse.class
        );
        assertThat(duplicateConfirmation.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(duplicateConfirmation.getBody()).isNotNull();
        assertThat(duplicateConfirmation.getBody().etag()).isEqualTo(firstConfirmation.etag());
        long secondTtl = redisTemplate.getExpire(bitmapKey(uploadId), TimeUnit.MILLISECONDS);
        assertThat(secondTtl).isLessThanOrEqualTo(firstTtl);

        bitmapStore.delete(uploadId);
        assertThat(bitmapStore.exists(uploadId)).isFalse();

        ResponseEntity<UploadSessionResponse> resumed = restTemplate.exchange(
            baseUrl("/api/uploads/" + uploadId),
            HttpMethod.GET,
            new HttpEntity<>(owner.headers()),
            UploadSessionResponse.class
        );
        assertThat(resumed.getBody()).isNotNull();
        assertThat(resumed.getBody().completedParts()).extracting(UploadPartResponse::partNumber)
            .containsExactly(0);
        assertThat(resumed.getBody().uploadedBytes()).isEqualTo(partOne.length);
        assertThat(bitmapStore.exists(uploadId)).isTrue();
        assertThat(bitmapStore.count(uploadId)).isEqualTo(1);
        long rebuiltTtl = redisTemplate.getExpire(bitmapKey(uploadId), TimeUnit.MILLISECONDS);
        long remainingSessionTtl = java.time.Duration.between(
            LocalDateTime.now(), persistedSession.getExpiresAt()
        ).toMillis();
        assertThat(rebuiltTtl).isPositive().isBetween(remainingSessionTtl - 5_000, remainingSessionTtl + 1_000);
        assertThat(partRepository.findByUploadId(uploadId)).isEmpty();

        ResponseEntity<String> missingPartCompletion = restTemplate.exchange(
            baseUrl("/api/uploads/" + uploadId + "/complete"),
            HttpMethod.POST,
            new HttpEntity<>(owner.headers()),
            String.class
        );
        assertThat(missingPartCompletion.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(sessionRepository.selectById(uploadId).getStatus()).isEqualTo("FAILED");

        uploadAndConfirm(owner, uploadId, 1, partTwo);

        CompleteUploadResponse completedResponse = complete(owner, uploadId);
        videoId = completedResponse.videoId();

        ResponseEntity<CompleteUploadResponse> repeated = restTemplate.exchange(
            baseUrl("/api/uploads/" + uploadId + "/complete"),
            HttpMethod.POST,
            new HttpEntity<>(owner.headers()),
            CompleteUploadResponse.class
        );
        assertThat(repeated.getBody()).isEqualTo(completedResponse);

        VideoEntity video = videoRepository.selectById(videoId);
        VideoUploadSessionEntity completed = sessionRepository.selectById(uploadId);
        assertThat(video.getObjectKey()).isEqualTo(objectKey);
        assertThat(video.getFileHash()).isEqualTo(sha256);
        assertThat(taskRepository.selectList(null))
            .noneMatch(task -> task.getVideoId().equals(videoId));
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThat(completed.getVideoId()).isEqualTo(videoId);
        assertThat(completed.getAnalysisTaskId()).isNull();
        assertThat(bitmapStore.exists(uploadId)).isFalse();
        assertThat(partRepository.findByUploadId(uploadId)).isEmpty();
        assertThat(minioClient.statObject(StatObjectArgs.builder()
            .bucket(storageProperties.bucket())
            .object(objectKey)
            .build()).size()).isEqualTo(completeFile.length);
    }

    @Test
    void shouldConfirmConcurrentDistinctAndDuplicatePartsWithoutDeadlock() throws Exception {
        owner = TestAuthClient.registerAndLogin(
            restTemplate, baseUrl(""), "upload-confirm-owner-" + System.nanoTime()
        );
        byte[] partOne = new byte[CHUNK_SIZE];
        System.arraycopy(MP4_HEADER, 0, partOne, 0, MP4_HEADER.length);
        byte[] partTwo = "tail-of-concurrent-confirm-video".getBytes();
        String primaryHash = sha256(partOne, partTwo);

        ResponseEntity<UploadSessionResponse> created = restTemplate.exchange(
            baseUrl("/api/uploads"),
            HttpMethod.POST,
            jsonEntity(owner, new CreateUploadSessionRequest(
                "concurrent-confirm.mp4", "Concurrent confirm",
                (long) partOne.length + partTwo.length, "video/mp4", (long) CHUNK_SIZE, primaryHash
            )),
            UploadSessionResponse.class
        );
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        uploadId = created.getBody().uploadId();

        uploadPartWithoutConfirmation(owner, uploadId, 0, partOne);
        uploadPartWithoutConfirmation(owner, uploadId, 1, partTwo);

        ResponseEntity<UploadSessionResponse> secondaryCreated = restTemplate.exchange(
            baseUrl("/api/uploads"),
            HttpMethod.POST,
            jsonEntity(owner, new CreateUploadSessionRequest(
                "separate-session.mp4", "Separate session",
                (long) partOne.length, "video/mp4", (long) CHUNK_SIZE, sha256(partOne)
            )),
            UploadSessionResponse.class
        );
        assertThat(secondaryCreated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(secondaryCreated.getBody()).isNotNull();
        secondaryUploadId = secondaryCreated.getBody().uploadId();
        uploadPartWithoutConfirmation(owner, secondaryUploadId, 0, partOne);

        List<UploadPartResponse> confirmations = confirmConcurrently(owner, List.of(
            new PartConfirmation(uploadId, 0),
            new PartConfirmation(uploadId, 0),
            new PartConfirmation(uploadId, 1),
            new PartConfirmation(secondaryUploadId, 0)
        ));

        assertThat(confirmations).extracting(UploadPartResponse::partNumber)
            .containsExactlyInAnyOrder(0, 0, 0, 1);
        assertThat(bitmapStore.readCompleted(uploadId, 0, 2)).containsExactly(0, 1);
        assertThat(bitmapStore.readCompleted(secondaryUploadId, 0, 1)).containsExactly(0);
        assertThat(partRepository.findByUploadId(uploadId)).isEmpty();
        assertThat(partRepository.findByUploadId(secondaryUploadId)).isEmpty();
        VideoUploadSessionEntity session = sessionRepository.selectById(uploadId);
        assertThat(session.getStatus()).isEqualTo("UPLOADING");
        assertThat(session.getVideoId()).isNull();
        assertThat(sessionRepository.selectById(secondaryUploadId).getStatus()).isEqualTo("UPLOADING");
    }

    @Test
    void shouldApplyBitmapCommandsAtomicallyWithAbsoluteExpiryAndMonotonicRebuild() throws Exception {
        uploadId = "bitmap-" + System.nanoTime();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(10);

        assertThat(bitmapStore.exists(uploadId)).isFalse();
        assertThat(bitmapStore.markCompleted(uploadId, 0, expiresAt)).isFalse();
        assertThat(bitmapStore.markCompleted(uploadId, 0, expiresAt.plusHours(1))).isTrue();
        assertThat(bitmapStore.markCompleted(uploadId, 4, expiresAt.plusHours(1))).isFalse();
        assertThat(bitmapStore.exists(uploadId)).isTrue();
        assertThat(bitmapStore.count(uploadId)).isEqualTo(2);
        assertThat(bitmapStore.readCompleted(uploadId, 0, 6)).containsExactly(0, 4);

        long ttlBeforeRebuild = redisTemplate.getExpire(bitmapKey(uploadId), TimeUnit.MILLISECONDS);
        bitmapStore.rebuild(uploadId, List.of(0, 2), expiresAt.plusHours(2));
        long ttlAfterRebuild = redisTemplate.getExpire(bitmapKey(uploadId), TimeUnit.MILLISECONDS);
        assertThat(ttlAfterRebuild).isLessThanOrEqualTo(ttlBeforeRebuild);
        assertThat(bitmapStore.readCompleted(uploadId, 0, 6)).containsExactly(0, 2, 4);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> confirm = executor.submit(() -> {
                ready.countDown();
                await(start);
                bitmapStore.markCompleted(uploadId, 3, expiresAt);
            });
            Future<?> rebuild = executor.submit(() -> {
                ready.countDown();
                await(start);
                bitmapStore.rebuild(uploadId, List.of(0, 2), expiresAt);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            confirm.get(5, TimeUnit.SECONDS);
            rebuild.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(bitmapStore.readCompleted(uploadId, 0, 6)).containsExactly(0, 2, 3, 4);

        assertThat(redisTemplate.persist(bitmapKey(uploadId))).isTrue();
        assertThat(redisTemplate.getExpire(bitmapKey(uploadId), TimeUnit.MILLISECONDS)).isEqualTo(-1);
        assertThat(bitmapStore.markCompleted(uploadId, 5, expiresAt)).isFalse();
        assertThat(redisTemplate.getExpire(bitmapKey(uploadId), TimeUnit.MILLISECONDS)).isPositive();

        bitmapStore.delete(uploadId);
        assertThat(bitmapStore.exists(uploadId)).isFalse();
        bitmapStore.delete(uploadId);
    }

    @Test
    void shouldReuseOneCanonicalVideoForConcurrentSameUserCompletionsWithClientHash() throws Exception {
        owner = TestAuthClient.registerAndLogin(
            restTemplate, baseUrl(""), "upload-dedup-owner-" + System.nanoTime()
        );
        byte[] content = mp4Part();
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        uploadId = createSession(owner, "same-content-a.mp4", content);
        secondaryUploadId = createSession(owner, "same-content-b.mp4", content);
        objectKey = sessionRepository.selectById(uploadId).getObjectKey();
        secondaryObjectKey = sessionRepository.selectById(secondaryUploadId).getObjectKey();
        uploadAndConfirm(owner, uploadId, 0, content);
        uploadAndConfirm(owner, secondaryUploadId, 0, content);

        List<CompleteUploadResponse> responses = completeSessionsConcurrently(owner, uploadId, secondaryUploadId);

        assertThat(responses).extracting(CompleteUploadResponse::videoId).containsOnly(responses.getFirst().videoId());
        assertThat(responses).extracting(CompleteUploadResponse::reusedExistingVideo)
            .containsExactlyInAnyOrder(false, true);
        videoId = responses.getFirst().videoId();
        List<VideoEntity> canonicalVideos = videoRepository.selectList(null).stream()
            .filter(video -> Long.valueOf(owner.userId()).equals(video.getUserId()))
            .filter(video -> sha256.equals(video.getFileHash()))
            .toList();
        assertThat(canonicalVideos).hasSize(1);
        assertThat(canonicalVideos.getFirst().getId()).isEqualTo(videoId);
        assertThat(canonicalVideos.getFirst().getFileHash()).hasSize(64).isEqualTo(sha256);
        assertThat(sessionRepository.selectById(uploadId).getVideoId()).isEqualTo(videoId);
        assertThat(sessionRepository.selectById(secondaryUploadId).getVideoId()).isEqualTo(videoId);
        assertThat(taskRepository.selectList(null)).noneMatch(task -> task.getVideoId().equals(videoId));
        assertThat(minioClient.statObject(StatObjectArgs.builder()
            .bucket(storageProperties.bucket())
            .object(canonicalVideos.getFirst().getObjectKey())
            .build()).size()).isEqualTo(content.length);
        String loserObjectKey = canonicalVideos.getFirst().getObjectKey().equals(objectKey)
            ? secondaryObjectKey
            : objectKey;
        String loserUploadId = canonicalVideos.getFirst().getObjectKey().equals(objectKey)
            ? secondaryUploadId
            : uploadId;
        assertThatThrownBy(() -> minioClient.statObject(StatObjectArgs.builder()
            .bucket(storageProperties.bucket())
            .object(loserObjectKey)
            .build())).isInstanceOf(Exception.class);

        ResponseEntity<CompleteUploadResponse> repeated = restTemplate.exchange(
            baseUrl("/api/uploads/" + loserUploadId + "/complete"),
            HttpMethod.POST,
            new HttpEntity<>(owner.headers()),
            CompleteUploadResponse.class
        );
        assertThat(repeated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repeated.getBody()).isNotNull();
        assertThat(repeated.getBody().videoId()).isEqualTo(videoId);
        assertThat(repeated.getBody().reusedExistingVideo()).isTrue();
    }

    @Test
    void shouldReuseCanonicalVideoForSequentialSameUserCompletions() throws Exception {
        owner = TestAuthClient.registerAndLogin(
            restTemplate, baseUrl(""), "upload-dedup-sequential-" + System.nanoTime()
        );
        byte[] content = mp4Part();
        uploadId = createSession(owner, "same-content-first.mp4", content);
        secondaryUploadId = createSession(owner, "same-content-second.mp4", content);
        uploadAndConfirm(owner, uploadId, 0, content);
        uploadAndConfirm(owner, secondaryUploadId, 0, content);

        CompleteUploadResponse first = complete(owner, uploadId);
        CompleteUploadResponse second = complete(owner, secondaryUploadId);

        assertThat(first.reusedExistingVideo()).isFalse();
        assertThat(second.reusedExistingVideo()).isTrue();
        assertThat(second.videoId()).isEqualTo(first.videoId());
        assertThat(videoRepository.selectList(null))
            .filteredOn(video -> Long.valueOf(owner.userId()).equals(video.getUserId()))
            .hasSize(1);
    }

    @Test
    void shouldKeepSameContentIndependentForDifferentUsers() throws Exception {
        owner = TestAuthClient.registerAndLogin(
            restTemplate, baseUrl(""), "upload-dedup-first-" + System.nanoTime()
        );
        stranger = TestAuthClient.registerAndLogin(
            restTemplate, baseUrl(""), "upload-dedup-second-" + System.nanoTime()
        );
        byte[] content = mp4Part();
        uploadId = createSession(owner, "same-content-owner.mp4", content);
        secondaryUploadId = createSession(stranger, "same-content-stranger.mp4", content);
        objectKey = sessionRepository.selectById(uploadId).getObjectKey();
        secondaryObjectKey = sessionRepository.selectById(secondaryUploadId).getObjectKey();
        uploadAndConfirm(owner, uploadId, 0, content);
        uploadAndConfirm(stranger, secondaryUploadId, 0, content);

        CompleteUploadResponse first = complete(owner, uploadId);
        CompleteUploadResponse second = complete(stranger, secondaryUploadId);

        assertThat(first.reusedExistingVideo()).isFalse();
        assertThat(second.reusedExistingVideo()).isFalse();
        assertThat(first.videoId()).isNotEqualTo(second.videoId());
        videoId = first.videoId();
        secondaryVideoId = second.videoId();
    }

    @Test
    void shouldCreateNewCanonicalVideoAfterOriginalIsDeleted() throws Exception {
        owner = TestAuthClient.registerAndLogin(
            restTemplate, baseUrl(""), "upload-dedup-delete-" + System.nanoTime()
        );
        byte[] content = mp4Part();
        uploadId = createSession(owner, "deleted-source.mp4", content);
        objectKey = sessionRepository.selectById(uploadId).getObjectKey();
        uploadAndConfirm(owner, uploadId, 0, content);
        CompleteUploadResponse first = complete(owner, uploadId);
        videoId = first.videoId();

        ResponseEntity<Void> deleted = restTemplate.exchange(
            baseUrl("/api/videos/" + videoId),
            HttpMethod.DELETE,
            new HttpEntity<>(owner.headers()),
            Void.class
        );
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        secondaryUploadId = createSession(owner, "reuploaded-source.mp4", content);
        secondaryObjectKey = sessionRepository.selectById(secondaryUploadId).getObjectKey();
        uploadAndConfirm(owner, secondaryUploadId, 0, content);
        CompleteUploadResponse reuploaded = complete(owner, secondaryUploadId);

        assertThat(reuploaded.reusedExistingVideo()).isFalse();
        assertThat(reuploaded.videoId()).isNotEqualTo(videoId);
        secondaryVideoId = reuploaded.videoId();
    }

    private UploadPartResponse uploadAndConfirm(Session session, String id, int partNumber, byte[] bytes) {
        uploadPartWithoutConfirmation(session, id, partNumber, bytes);

        ResponseEntity<UploadPartResponse> confirmation = restTemplate.exchange(
            baseUrl("/api/uploads/" + id + "/parts/" + partNumber + "/complete"),
            HttpMethod.POST,
            jsonEntity(session, Map.of()),
            UploadPartResponse.class
        );
        assertThat(confirmation.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmation.getBody()).isNotNull();
        return confirmation.getBody();
    }

    private void uploadPartWithoutConfirmation(Session session, String id, int partNumber, byte[] bytes) {
        ResponseEntity<UploadPartUrlResponse> urlResponse = restTemplate.exchange(
            baseUrl("/api/uploads/" + id + "/parts/" + partNumber + "/url"),
            HttpMethod.POST,
            new HttpEntity<>(session.headers()),
            UploadPartUrlResponse.class
        );
        assertThat(urlResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(urlResponse.getBody()).isNotNull();
        assertThat(urlResponse.getBody().expectedSize()).isEqualTo(bytes.length);

        HttpHeaders directHeaders = new HttpHeaders();
        directHeaders.setContentType(MediaType.valueOf("video/mp4"));
        directHeaders.setContentLength(bytes.length);
        ResponseEntity<String> directPut = restTemplate.exchange(
            URI.create(urlResponse.getBody().uploadUrl()),
            HttpMethod.PUT,
            new HttpEntity<>(bytes, directHeaders),
            String.class
        );
        assertThat(directPut.getStatusCode().is2xxSuccessful())
            .as("direct MinIO PUT status=%s body=%s", directPut.getStatusCode(), directPut.getBody())
            .isTrue();
    }

    private List<UploadPartResponse> confirmConcurrently(Session session, List<PartConfirmation> confirmations) {
        ExecutorService executor = Executors.newFixedThreadPool(confirmations.size());
        CountDownLatch ready = new CountDownLatch(confirmations.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<UploadPartResponse>> futures = confirmations.stream()
                .map(confirmation -> executor.submit(() -> confirmAfterBarrier(
                    session, confirmation.uploadId(), confirmation.partNumber(), ready, start
                )))
                .toList();
            ready.await();
            start.countDown();
            return futures.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new IllegalStateException("并发确认分片失败", exception);
                }
            }).toList();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发确认分片被中断", exception);
        } finally {
            executor.shutdownNow();
        }
    }

    private record PartConfirmation(String uploadId, int partNumber) {
    }

    private UploadPartResponse confirmAfterBarrier(
        Session session,
        String id,
        int partNumber,
        CountDownLatch ready,
        CountDownLatch start
    ) throws Exception {
        ready.countDown();
        start.await();
        ResponseEntity<UploadPartResponse> response = restTemplate.exchange(
            baseUrl("/api/uploads/" + id + "/parts/" + partNumber + "/complete"),
            HttpMethod.POST,
            jsonEntity(session, Map.of()),
            UploadPartResponse.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private List<CompleteUploadResponse> completeSessionsConcurrently(
        Session session,
        String firstUploadId,
        String secondUploadId
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<CompleteUploadResponse> first = executor.submit(
                () -> completeAfterBarrier(session, firstUploadId, ready, start)
            );
            Future<CompleteUploadResponse> second = executor.submit(
                () -> completeAfterBarrier(session, secondUploadId, ready, start)
            );
            ready.await();
            start.countDown();
            return Arrays.asList(first.get(), second.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private CompleteUploadResponse complete(Session session, String id) {
        ResponseEntity<CompleteUploadResponse> response = restTemplate.exchange(
            baseUrl("/api/uploads/" + id + "/complete"),
            HttpMethod.POST,
            new HttpEntity<>(session.headers()),
            CompleteUploadResponse.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private CompleteUploadResponse completeAfterBarrier(
        Session session,
        String id,
        CountDownLatch ready,
        CountDownLatch start
    ) throws Exception {
        ready.countDown();
        start.await();
        ResponseEntity<CompleteUploadResponse> response = restTemplate.exchange(
            baseUrl("/api/uploads/" + id + "/complete"),
            HttpMethod.POST,
            new HttpEntity<>(session.headers()),
            CompleteUploadResponse.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private <T> HttpEntity<T> jsonEntity(Session session, T body) {
        HttpHeaders headers = session.headers();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private String baseUrl(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private String bitmapKey(String id) {
        return "videoagent:upload:parts:" + id;
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private String createSession(Session session, String fileName, byte[] content) throws Exception {
        ResponseEntity<UploadSessionResponse> created = restTemplate.exchange(
            baseUrl("/api/uploads"),
            HttpMethod.POST,
            jsonEntity(session, new CreateUploadSessionRequest(
                fileName, fileName, (long) content.length, "video/mp4", (long) CHUNK_SIZE, sha256(content)
            )),
            UploadSessionResponse.class
        );
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        return created.getBody().uploadId();
    }

    private String sha256(byte[]... parts) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (byte[] part : parts) {
            digest.update(part);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private byte[] mp4Part() {
        byte[] content = new byte[CHUNK_SIZE];
        System.arraycopy(MP4_HEADER, 0, content, 0, MP4_HEADER.length);
        return content;
    }
}
