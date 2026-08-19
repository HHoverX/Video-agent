package com.videoagent.upload;

import static org.assertj.core.api.Assertions.assertThat;

import com.videoagent.analysis.entity.AnalysisTaskEntity;
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
import com.videoagent.upload.repository.VideoUploadSessionRepository;
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

import java.security.MessageDigest;
import java.net.URI;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
    private String objectKey;
    private Long videoId;
    private Long analysisTaskId;

    @AfterEach
    void cleanUp() throws Exception {
        if (objectKey != null) {
            minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(storageProperties.bucket())
                .object(objectKey)
                .build());
        }
        if (uploadId != null) {
            sessionRepository.deleteById(uploadId);
        }
        if (analysisTaskId != null) {
            taskRepository.deleteById(analysisTaskId);
        }
        if (videoId != null) {
            videoRepository.deleteById(videoId);
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
        VideoUploadSessionEntity persistedSession = sessionRepository.selectById(uploadId);
        objectKey = persistedSession.getObjectKey();
        assertThat(objectKey).startsWith("videos/").doesNotContain(uploadId);

        ResponseEntity<String> crossUserRead = restTemplate.exchange(
            baseUrl("/api/uploads/" + uploadId),
            HttpMethod.GET,
            new HttpEntity<>(stranger.headers()),
            String.class
        );
        assertThat(crossUserRead.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        UploadPartResponse firstConfirmation = uploadAndConfirm(owner, uploadId, 1, partOne);
        ResponseEntity<UploadPartResponse> duplicateConfirmation = restTemplate.exchange(
            baseUrl("/api/uploads/" + uploadId + "/parts/1/complete"),
            HttpMethod.POST,
            jsonEntity(owner, Map.of()),
            UploadPartResponse.class
        );
        assertThat(duplicateConfirmation.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(duplicateConfirmation.getBody()).isNotNull();
        assertThat(duplicateConfirmation.getBody().etag()).isEqualTo(firstConfirmation.etag());

        ResponseEntity<UploadSessionResponse> resumed = restTemplate.exchange(
            baseUrl("/api/uploads/" + uploadId),
            HttpMethod.GET,
            new HttpEntity<>(owner.headers()),
            UploadSessionResponse.class
        );
        assertThat(resumed.getBody()).isNotNull();
        assertThat(resumed.getBody().completedParts()).extracting(UploadPartResponse::partNumber)
            .containsExactly(1);
        assertThat(resumed.getBody().uploadedBytes()).isEqualTo(partOne.length);

        ResponseEntity<String> missingPartCompletion = restTemplate.exchange(
            baseUrl("/api/uploads/" + uploadId + "/complete"),
            HttpMethod.POST,
            new HttpEntity<>(owner.headers()),
            String.class
        );
        assertThat(missingPartCompletion.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(sessionRepository.selectById(uploadId).getStatus()).isEqualTo("FAILED");

        uploadAndConfirm(owner, uploadId, 2, partTwo);

        List<CompleteUploadResponse> concurrent = completeConcurrently(owner, uploadId);
        assertThat(concurrent).hasSize(2);
        assertThat(concurrent).extracting(CompleteUploadResponse::videoId).doesNotContainNull().containsOnly(
            concurrent.getFirst().videoId()
        );
        assertThat(concurrent).extracting(CompleteUploadResponse::analysisTaskId).doesNotContainNull().containsOnly(
            concurrent.getFirst().analysisTaskId()
        );
        videoId = concurrent.getFirst().videoId();
        analysisTaskId = concurrent.getFirst().analysisTaskId();

        ResponseEntity<CompleteUploadResponse> repeated = restTemplate.exchange(
            baseUrl("/api/uploads/" + uploadId + "/complete"),
            HttpMethod.POST,
            new HttpEntity<>(owner.headers()),
            CompleteUploadResponse.class
        );
        assertThat(repeated.getBody()).isEqualTo(concurrent.getFirst());

        VideoEntity video = videoRepository.selectById(videoId);
        AnalysisTaskEntity task = taskRepository.selectById(analysisTaskId);
        VideoUploadSessionEntity completed = sessionRepository.selectById(uploadId);
        assertThat(video.getObjectKey()).isEqualTo(objectKey);
        assertThat(video.getFileHash()).isEqualTo(sha256);
        assertThat(task.getVideoId()).isEqualTo(videoId);
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThat(completed.getVideoId()).isEqualTo(videoId);
        assertThat(completed.getAnalysisTaskId()).isEqualTo(analysisTaskId);
        assertThat(minioClient.statObject(StatObjectArgs.builder()
            .bucket(storageProperties.bucket())
            .object(objectKey)
            .build()).size()).isEqualTo(completeFile.length);
    }

    private UploadPartResponse uploadAndConfirm(Session session, String id, int partNumber, byte[] bytes) {
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

    private List<CompleteUploadResponse> completeConcurrently(Session session, String id) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<CompleteUploadResponse> first = executor.submit(() -> completeAfterBarrier(session, id, ready, start));
            Future<CompleteUploadResponse> second = executor.submit(() -> completeAfterBarrier(session, id, ready, start));
            ready.await();
            start.countDown();
            return Arrays.asList(first.get(), second.get());
        } finally {
            executor.shutdownNow();
        }
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
}
