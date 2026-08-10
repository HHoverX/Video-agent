package com.videoagent.video;

import static org.assertj.core.api.Assertions.assertThat;

import com.videoagent.storage.StorageProperties;
import com.videoagent.auth.repository.AppUserRepository;
import com.videoagent.testsupport.TestAuthClient;
import com.videoagent.testsupport.TestAuthClient.Session;
import com.videoagent.video.dto.VideoPageResponse;
import com.videoagent.video.dto.VideoResponse;
import com.videoagent.video.dto.VideoUploadResponse;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Arrays;

@EnabledIfEnvironmentVariable(named = "VIDEOAGENT_M2_INFRA_TEST", matches = "true")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "videoagent.security.jwt.secret=" + TestAuthClient.JWT_SECRET
)
class VideoUploadInfrastructureIntegrationTest {

    private static final byte[] MP4_BYTES = {
        0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm',
        0, 0, 0, 0, 'i', 's', 'o', 'm', 'm', 'p', '4', '2'
    };

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private StorageProperties storageProperties;

    private Long createdVideoId;
    private String createdObjectKey;
    private Session authSession;

    @AfterEach
    void cleanUp() throws Exception {
        if (createdObjectKey != null) {
            minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(storageProperties.bucket())
                .object(createdObjectKey)
                .build());
        }
        if (createdVideoId != null) {
            videoRepository.deleteById(createdVideoId);
        }
        if (authSession != null) {
            userRepository.deleteById(authSession.userId());
        }
    }

    @Test
    void shouldUploadToMinioPersistInMysqlAndServeQueries() throws Exception {
        authSession = TestAuthClient.registerAndLogin(
            restTemplate,
            "http://127.0.0.1:" + port,
            "m2-infra-" + System.nanoTime()
        );
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.valueOf("video/mp4"));
        ByteArrayResource fileResource = new ByteArrayResource(MP4_BYTES) {
            @Override
            public String getFilename() {
                return "integration.mp4";
            }
        };

        MultiValueMap<String, Object> multipartBody = new LinkedMultiValueMap<>();
        multipartBody.add("file", new HttpEntity<>(fileResource, fileHeaders));
        multipartBody.add("title", "Infrastructure integration video");

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        requestHeaders.setBearerAuth(authSession.token());
        ResponseEntity<VideoUploadResponse> uploadResponse = restTemplate.exchange(
            baseUrl("/api/videos"),
            HttpMethod.POST,
            new HttpEntity<>(multipartBody, requestHeaders),
            VideoUploadResponse.class
        );

        assertThat(uploadResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(uploadResponse.getBody()).isNotNull();
        createdVideoId = uploadResponse.getBody().videoId();

        VideoEntity persisted = videoRepository.selectById(createdVideoId);
        assertThat(persisted).isNotNull();
        assertThat(persisted.getTitle()).isEqualTo("Infrastructure integration video");
        assertThat(persisted.getStatus()).isEqualTo("UPLOADED");
        assertThat(persisted.getUserId()).isEqualTo(authSession.userId());
        assertThat(persisted.getFileHash()).hasSize(64);
        createdObjectKey = persisted.getObjectKey();

        assertThat(minioClient.statObject(StatObjectArgs.builder()
            .bucket(storageProperties.bucket())
            .object(createdObjectKey)
            .build()).size()).isEqualTo(MP4_BYTES.length);

        ResponseEntity<VideoPageResponse> listResponse = restTemplate.exchange(
            baseUrl("/api/videos"),
            HttpMethod.GET,
            new HttpEntity<>(authSession.headers()),
            VideoPageResponse.class
        );
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).isNotNull();
        assertThat(listResponse.getBody().items().stream().map(VideoResponse::id))
            .contains(createdVideoId);

        ResponseEntity<VideoResponse> detailResponse = restTemplate.exchange(
            baseUrl("/api/videos/" + createdVideoId),
            HttpMethod.GET,
            new HttpEntity<>(authSession.headers()),
            VideoResponse.class
        );
        assertThat(detailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detailResponse.getBody()).isNotNull();
        assertThat(detailResponse.getBody().originalFilename()).isEqualTo("integration.mp4");
        assertThat(detailResponse.getBody().fileSize()).isEqualTo(MP4_BYTES.length);
    }

    private String baseUrl(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
