package com.videoagent.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videoagent.analysis.entity.AnalysisStatus;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.auth.repository.AppUserRepository;
import com.videoagent.common.exception.ApiErrorResponse;
import com.videoagent.storage.StorageProperties;
import com.videoagent.summary.entity.VideoChapterEntity;
import com.videoagent.summary.entity.VideoKeyPointEntity;
import com.videoagent.summary.entity.VideoSummaryEntity;
import com.videoagent.summary.repository.VideoChapterRepository;
import com.videoagent.summary.repository.VideoKeyPointRepository;
import com.videoagent.summary.repository.VideoSummaryRepository;
import com.videoagent.testsupport.TestAuthClient;
import com.videoagent.testsupport.TestAuthClient.Session;
import com.videoagent.transcript.entity.VideoTranscriptSegmentEntity;
import com.videoagent.transcript.repository.VideoTranscriptSegmentRepository;
import com.videoagent.video.dto.VideoPageResponse;
import com.videoagent.video.dto.VideoResponse;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;

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

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@EnabledIfEnvironmentVariable(named = "VIDEOAGENT_M66_INFRA_TEST", matches = "true")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "videoagent.security.jwt.secret=" + TestAuthClient.JWT_SECRET,
        "videoagent.analysis.consumer-group=videoagent-m66-infra-${random.uuid}",
        "videoagent.ai.asr.provider=mock",
        "videoagent.ai.llm.provider=mock"
    }
)
class Milestone66InfrastructureIntegrationTest {

    private static final byte[] STORED_VIDEO = {
        0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm',
        0, 0, 0, 0, 'i', 's', 'o', 'm', 'm', 'p', '4', '2'
    };

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private AnalysisTaskRepository taskRepository;

    @Autowired
    private VideoTranscriptSegmentRepository transcriptRepository;

    @Autowired
    private VideoSummaryRepository summaryRepository;

    @Autowired
    private VideoChapterRepository chapterRepository;

    @Autowired
    private VideoKeyPointRepository keyPointRepository;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private StorageProperties storageProperties;

    private final List<Long> videoIds = new ArrayList<>();
    private final List<String> objectKeys = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long videoId : videoIds) {
            taskRepository.delete(Wrappers.<AnalysisTaskEntity>lambdaQuery()
                .eq(AnalysisTaskEntity::getVideoId, videoId));
            videoRepository.deleteById(videoId);
        }
        for (String objectKey : objectKeys) {
            try {
                minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(storageProperties.bucket())
                    .object(objectKey)
                    .build());
            } catch (Exception ignored) {
                // Best-effort test cleanup mirrors the production consistency boundary.
            }
        }
        for (Long userId : userIds) {
            userRepository.deleteById(userId);
        }
    }

    @Test
    void shouldIsolateUsersManageVideosAndDeleteCompletedResults() throws Exception {
        String suffix = Long.toUnsignedString(System.nanoTime());
        Session alice = register("m66-alice-" + suffix);
        Session bob = register("m66-bob-" + suffix);
        LocalDateTime base = LocalDateTime.now().minusHours(1);

        VideoEntity aliceOld = insertVideo(alice.userId(), "Alice old", base.plusMinutes(1));
        VideoEntity aliceAgent = insertVideo(alice.userId(), "Agent 中文演示", base.plusMinutes(2));
        VideoEntity aliceNewest = insertVideo(alice.userId(), "Alice newest", base.plusMinutes(3));
        VideoEntity aliceActive = insertVideo(alice.userId(), "Alice active", base.plusMinutes(4));
        VideoEntity aliceDeletable = insertVideo(alice.userId(), "Alice deletable", base.plusMinutes(5));
        VideoEntity bobVideo = insertVideo(bob.userId(), "Bob private agent", base.plusMinutes(6));

        AnalysisTaskEntity bobTask = insertTask(bobVideo.getId(), AnalysisStatus.SUCCESS, "DONE", 100);
        AnalysisTaskEntity activeTask = insertTask(
            aliceActive.getId(), AnalysisStatus.PENDING, "QUEUED", 0
        );
        AnalysisTaskEntity completedTask = insertTask(
            aliceDeletable.getId(), AnalysisStatus.SUCCESS, "DONE", 100
        );
        insertCompletedResults(aliceDeletable.getId(), completedTask.getId());
        putStoredVideo(aliceDeletable.getObjectKey());

        ResponseEntity<VideoPageResponse> firstPage = exchange(
            "/api/videos?page=1&size=2", HttpMethod.GET, alice, null, VideoPageResponse.class
        );
        assertThat(firstPage.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstPage.getBody()).isNotNull();
        assertThat(firstPage.getBody().size()).isEqualTo(2);
        assertThat(firstPage.getBody().total()).isEqualTo(5);
        assertThat(firstPage.getBody().pages()).isEqualTo(3);
        assertThat(firstPage.getBody().items())
            .extracting(VideoResponse::id)
            .containsExactly(aliceDeletable.getId(), aliceActive.getId())
            .doesNotContain(bobVideo.getId());

        ResponseEntity<VideoPageResponse> search = exchange(
            "/api/videos?page=1&size=10&keyword=Agent",
            HttpMethod.GET,
            alice,
            null,
            VideoPageResponse.class
        );
        assertThat(search.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(search.getBody()).isNotNull();
        assertThat(search.getBody().total()).isEqualTo(1);
        assertThat(search.getBody().items())
            .extracting(VideoResponse::id)
            .containsExactly(aliceAgent.getId());

        ResponseEntity<VideoResponse> updated = exchange(
            "/api/videos/" + aliceOld.getId(),
            HttpMethod.PATCH,
            alice,
            Map.of("title", "Alice renamed"),
            VideoResponse.class
        );
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).isNotNull();
        assertThat(updated.getBody().title()).isEqualTo("Alice renamed");

        assertHiddenFrom(alice, bobVideo.getId(), bobTask.getId());

        ResponseEntity<ApiErrorResponse> blocked = exchange(
            "/api/videos/" + aliceActive.getId(),
            HttpMethod.DELETE,
            alice,
            null,
            ApiErrorResponse.class
        );
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blocked.getBody()).isNotNull();
        assertThat(blocked.getBody().code()).isEqualTo("VIDEO_ANALYSIS_IN_PROGRESS");
        assertThat(taskRepository.selectById(activeTask.getId())).isNotNull();

        ResponseEntity<Void> deleted = exchange(
            "/api/videos/" + aliceDeletable.getId(),
            HttpMethod.DELETE,
            alice,
            null,
            Void.class
        );
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(videoRepository.selectById(aliceDeletable.getId())).isNull();
        assertThat(taskRepository.selectById(completedTask.getId())).isNull();
        assertThat(transcriptRepository.selectCount(Wrappers.<VideoTranscriptSegmentEntity>lambdaQuery()
            .eq(VideoTranscriptSegmentEntity::getVideoId, aliceDeletable.getId()))).isZero();
        assertThat(summaryRepository.selectCount(Wrappers.<VideoSummaryEntity>lambdaQuery()
            .eq(VideoSummaryEntity::getVideoId, aliceDeletable.getId()))).isZero();
        assertThat(chapterRepository.selectCount(Wrappers.<VideoChapterEntity>lambdaQuery()
            .eq(VideoChapterEntity::getVideoId, aliceDeletable.getId()))).isZero();
        assertThat(keyPointRepository.selectCount(Wrappers.<VideoKeyPointEntity>lambdaQuery()
            .eq(VideoKeyPointEntity::getVideoId, aliceDeletable.getId()))).isZero();
        assertThatThrownBy(() -> minioClient.getObject(GetObjectArgs.builder()
            .bucket(storageProperties.bucket())
            .object(aliceDeletable.getObjectKey())
            .build()))
            .isInstanceOf(Exception.class);

        assertThat(aliceNewest.getId()).isNotNull();
    }

    private void assertHiddenFrom(Session alice, long bobVideoId, long bobTaskId) {
        assertThat(exchange(
            "/api/videos/" + bobVideoId,
            HttpMethod.GET,
            alice,
            null,
            ApiErrorResponse.class
        ).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(
            "/api/videos/" + bobVideoId,
            HttpMethod.PATCH,
            alice,
            Map.of("title", "stolen"),
            ApiErrorResponse.class
        ).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(
            "/api/videos/" + bobVideoId,
            HttpMethod.DELETE,
            alice,
            null,
            ApiErrorResponse.class
        ).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(
            "/api/videos/" + bobVideoId + "/analysis",
            HttpMethod.POST,
            alice,
            null,
            ApiErrorResponse.class
        ).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(
            "/api/videos/" + bobVideoId + "/transcript",
            HttpMethod.GET,
            alice,
            null,
            ApiErrorResponse.class
        ).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(
            "/api/videos/" + bobVideoId + "/summary",
            HttpMethod.GET,
            alice,
            null,
            ApiErrorResponse.class
        ).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(
            "/api/videos/" + bobVideoId + "/chapters",
            HttpMethod.GET,
            alice,
            null,
            ApiErrorResponse.class
        ).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(
            "/api/videos/" + bobVideoId + "/key-points",
            HttpMethod.GET,
            alice,
            null,
            ApiErrorResponse.class
        ).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(
            "/api/analysis/" + bobTaskId,
            HttpMethod.GET,
            alice,
            null,
            ApiErrorResponse.class
        ).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        HttpHeaders sseHeaders = alice.headers();
        sseHeaders.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
        ResponseEntity<String> sse = restTemplate.exchange(
            baseUrl("/api/analysis/" + bobTaskId + "/events"),
            HttpMethod.GET,
            new HttpEntity<>(sseHeaders),
            String.class
        );
        assertThat(sse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private Session register(String username) {
        Session session = TestAuthClient.registerAndLogin(restTemplate, baseUrl(""), username);
        userIds.add(session.userId());
        return session;
    }

    private VideoEntity insertVideo(long userId, String title, LocalDateTime createdAt) {
        VideoEntity video = new VideoEntity();
        video.setUserId(userId);
        video.setTitle(title);
        video.setOriginalFilename(title + ".mp4");
        video.setObjectKey("m66/" + UUID.randomUUID() + ".mp4");
        video.setFileSize((long) STORED_VIDEO.length);
        video.setMimeType("video/mp4");
        video.setStatus("UPLOADED");
        video.setCreatedAt(createdAt);
        video.setUpdatedAt(createdAt);
        assertThat(videoRepository.insert(video)).isOne();
        videoIds.add(video.getId());
        objectKeys.add(video.getObjectKey());
        return video;
    }

    private AnalysisTaskEntity insertTask(
        long videoId,
        AnalysisStatus status,
        String stage,
        int progress
    ) {
        LocalDateTime now = LocalDateTime.now();
        AnalysisTaskEntity task = new AnalysisTaskEntity();
        task.setVideoId(videoId);
        task.setAnalysisType("M66_ACCEPTANCE");
        task.setModelVersion("m66-" + UUID.randomUUID());
        task.setStatus(status.name());
        task.setStage(stage);
        task.setProgress(progress);
        task.setRetryCount(0);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        if (status == AnalysisStatus.SUCCESS) {
            task.setStartedAt(now.minusSeconds(1));
            task.setFinishedAt(now);
        }
        assertThat(taskRepository.insert(task)).isOne();
        return task;
    }

    private void insertCompletedResults(long videoId, long taskId) {
        LocalDateTime now = LocalDateTime.now();

        VideoTranscriptSegmentEntity segment = new VideoTranscriptSegmentEntity();
        segment.setVideoId(videoId);
        segment.setTaskId(taskId);
        segment.setSegmentIndex(0);
        segment.setStartMs(0L);
        segment.setEndMs(1000L);
        segment.setText("M6.6 删除级联测试");
        segment.setCreatedAt(now);
        assertThat(transcriptRepository.insert(segment)).isOne();

        VideoSummaryEntity summary = new VideoSummaryEntity();
        summary.setVideoId(videoId);
        summary.setTaskId(taskId);
        summary.setOverview("M6.6 删除级联测试摘要");
        summary.setCreatedAt(now);
        summary.setUpdatedAt(now);
        assertThat(summaryRepository.insert(summary)).isOne();

        VideoChapterEntity chapter = new VideoChapterEntity();
        chapter.setVideoId(videoId);
        chapter.setTaskId(taskId);
        chapter.setChapterIndex(0);
        chapter.setTitle("测试章节");
        chapter.setSummary("测试章节摘要");
        chapter.setStartMs(0L);
        chapter.setEndMs(1000L);
        assertThat(chapterRepository.insert(chapter)).isOne();

        VideoKeyPointEntity keyPoint = new VideoKeyPointEntity();
        keyPoint.setVideoId(videoId);
        keyPoint.setTaskId(taskId);
        keyPoint.setPointIndex(0);
        keyPoint.setContent("测试要点");
        keyPoint.setStartMs(0L);
        keyPoint.setEndMs(1000L);
        assertThat(keyPointRepository.insert(keyPoint)).isOne();
    }

    private void putStoredVideo(String objectKey) throws Exception {
        minioClient.putObject(PutObjectArgs.builder()
            .bucket(storageProperties.bucket())
            .object(objectKey)
            .stream(new ByteArrayInputStream(STORED_VIDEO), STORED_VIDEO.length, -1)
            .contentType("video/mp4")
            .build());
    }

    private <T> ResponseEntity<T> exchange(
        String path,
        HttpMethod method,
        Session session,
        Object body,
        Class<T> responseType
    ) {
        HttpHeaders headers = session.headers();
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return restTemplate.exchange(
            baseUrl(path),
            method,
            new HttpEntity<>(body, headers),
            responseType
        );
    }

    private String baseUrl(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
