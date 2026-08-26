package com.videoagent.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.videoagent.analysis.dto.AnalysisProgressSnapshot;
import com.videoagent.analysis.dto.AnalysisTaskResponse;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.progress.AnalysisProgressStore;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.video.service.VideoOwnershipService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisQueryServiceTest {

    private final AnalysisTaskRepository repository = mock(AnalysisTaskRepository.class);
    private final AnalysisProgressStore progressStore = mock(AnalysisProgressStore.class);
    private final VideoOwnershipService ownershipService = mock(VideoOwnershipService.class);
    private final AnalysisProperties properties = new AnalysisProperties(
        null, null, "STRUCTURED_SUMMARY", "m5-langchain4j-structured-v1", Duration.ofHours(24)
    );
    private AnalysisQueryService service;

    @BeforeEach
    void setUp() {
        service = new AnalysisQueryService(repository, progressStore, ownershipService, properties);
        when(ownershipService.isOwned(7L, 5L)).thenReturn(true);
    }

    @Test
    void shouldPreferRedisProgressOverPersistedProgress() {
        AnalysisTaskEntity task = successfulTask();
        task.setStatus("PROCESSING");
        task.setStage("PREPARING");
        task.setProgress(20);
        when(repository.selectById(101L)).thenReturn(task);
        when(progressStore.find(101L)).thenReturn(Optional.of(
            new AnalysisProgressSnapshot("PROCESSING", "ANALYZING", 40, "正在模拟分析")
        ));

        AnalysisTaskResponse response = service.getTask(101L, 5L);

        assertThat(response.status()).isEqualTo("PROCESSING");
        assertThat(response.stage()).isEqualTo("ANALYZING");
        assertThat(response.progress()).isEqualTo(40);
        assertThat(response.message()).isEqualTo("正在模拟分析");
    }

    @Test
    void shouldFallBackToMysqlWhenRedisProgressIsMissing() {
        AnalysisTaskEntity task = successfulTask();
        when(repository.selectById(101L)).thenReturn(task);
        when(progressStore.find(101L)).thenReturn(Optional.empty());

        AnalysisTaskResponse response = service.getTask(101L, 5L);

        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.stage()).isEqualTo("DONE");
        assertThat(response.progress()).isEqualTo(100);
        assertThat(response.message()).isEqualTo("分析完成");
        assertThat(response.finishedAt()).isNotNull();
    }

    @Test
    void shouldKeepMysqlTerminalStateWhenRedisSnapshotIsStale() {
        AnalysisTaskEntity task = successfulTask();
        when(repository.selectById(101L)).thenReturn(task);
        when(progressStore.find(101L)).thenReturn(Optional.of(
            new AnalysisProgressSnapshot("PROCESSING", "SAVING", 90, "正在保存结果")
        ));

        AnalysisTaskResponse response = service.getTask(101L, 5L);

        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.stage()).isEqualTo("DONE");
        assertThat(response.progress()).isEqualTo(100);
        assertThat(response.message()).isEqualTo("分析完成");
    }

    @Test
    void shouldIgnoreFailedRedisSnapshotAfterMysqlTaskIsRestarted() {
        AnalysisTaskEntity task = successfulTask();
        task.setStatus("RETRY_WAITING");
        task.setStage("RETRY_WAITING");
        task.setProgress(0);
        task.setFinishedAt(null);
        when(repository.selectById(101L)).thenReturn(task);
        when(progressStore.find(101L)).thenReturn(Optional.of(
            new AnalysisProgressSnapshot("FAILED", "FAILED", 80, "old terminal failure")
        ));

        AnalysisTaskResponse response = service.getTask(101L, 5L);

        assertThat(response.status()).isEqualTo("RETRY_WAITING");
        assertThat(response.stage()).isEqualTo("RETRY_WAITING");
        assertThat(response.progress()).isZero();
        assertThat(response.message()).isEqualTo("分析暂时失败，正在重试");
    }

    @Test
    void shouldHideAnotherUsersTask() {
        when(repository.selectById(101L)).thenReturn(successfulTask());
        when(ownershipService.isOwned(7L, 6L)).thenReturn(false);

        assertThatThrownBy(() -> service.getTask(101L, 6L))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.ANALYSIS_NOT_FOUND)
            );
    }

    @Test
    void shouldReturnCurrentConfiguredTaskAfterOwnershipCheck() {
        AnalysisTaskEntity task = successfulTask();
        task.setStatus("PROCESSING");
        task.setStage("ANALYZING");
        task.setProgress(45);
        when(repository.findByBusinessKey(7L, "STRUCTURED_SUMMARY", "m5-langchain4j-structured-v1"))
            .thenReturn(task);
        when(progressStore.find(101L)).thenReturn(Optional.of(
            new AnalysisProgressSnapshot("PROCESSING", "ANALYZING", 55, "正在分析")
        ));

        Optional<AnalysisTaskResponse> response = service.getCurrentTask(7L, 5L);

        assertThat(response).isPresent();
        assertThat(response.orElseThrow().progress()).isEqualTo(55);
        inOrder(ownershipService, repository).verify(ownershipService).requireOwned(7L, 5L);
        verify(repository).findByBusinessKey(7L, "STRUCTURED_SUMMARY", "m5-langchain4j-structured-v1");
    }

    @Test
    void shouldReturnEmptyWhenNoCurrentConfiguredTaskExists() {
        when(repository.findByBusinessKey(7L, "STRUCTURED_SUMMARY", "m5-langchain4j-structured-v1"))
            .thenReturn(null);

        assertThat(service.getCurrentTask(7L, 5L)).isEmpty();
        verify(repository).findByBusinessKey(7L, "STRUCTURED_SUMMARY", "m5-langchain4j-structured-v1");
        verifyNoInteractions(progressStore);
    }

    @Test
    void shouldKeepMysqlCurrentTaskWhenRedisSnapshotHasDifferentStatus() {
        AnalysisTaskEntity task = successfulTask();
        task.setStatus("RETRY_WAITING");
        task.setStage("RETRY_WAITING");
        task.setProgress(0);
        task.setFinishedAt(null);
        when(repository.findByBusinessKey(7L, "STRUCTURED_SUMMARY", "m5-langchain4j-structured-v1"))
            .thenReturn(task);
        when(progressStore.find(101L)).thenReturn(Optional.of(
            new AnalysisProgressSnapshot("FAILED", "FAILED", 80, "stale failure")
        ));

        AnalysisTaskResponse response = service.getCurrentTask(7L, 5L).orElseThrow();

        assertThat(response.status()).isEqualTo("RETRY_WAITING");
        assertThat(response.stage()).isEqualTo("RETRY_WAITING");
        assertThat(response.progress()).isZero();
    }

    @Test
    void shouldNotQueryCurrentTaskWhenOwnershipIsRejected() {
        when(ownershipService.requireOwned(7L, 6L)).thenThrow(new VideoAgentException(ErrorCode.VIDEO_NOT_FOUND));

        assertThatThrownBy(() -> service.getCurrentTask(7L, 6L))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.VIDEO_NOT_FOUND)
            );
        verifyNoInteractions(repository, progressStore);
    }

    private AnalysisTaskEntity successfulTask() {
        LocalDateTime now = LocalDateTime.now();
        AnalysisTaskEntity task = new AnalysisTaskEntity();
        task.setId(101L);
        task.setVideoId(7L);
        task.setStatus("SUCCESS");
        task.setStage("DONE");
        task.setProgress(100);
        task.setRetryCount(0);
        task.setCreatedAt(now.minusSeconds(5));
        task.setStartedAt(now.minusSeconds(4));
        task.setFinishedAt(now);
        return task;
    }
}
