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

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisQueryServiceTest {

    private final AnalysisTaskRepository repository = mock(AnalysisTaskRepository.class);
    private final AnalysisProgressStore progressStore = mock(AnalysisProgressStore.class);
    private final VideoOwnershipService ownershipService = mock(VideoOwnershipService.class);
    private AnalysisQueryService service;

    @BeforeEach
    void setUp() {
        service = new AnalysisQueryService(repository, progressStore, ownershipService);
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
    void shouldHideAnotherUsersTask() {
        when(repository.selectById(101L)).thenReturn(successfulTask());
        when(ownershipService.isOwned(7L, 6L)).thenReturn(false);

        assertThatThrownBy(() -> service.getTask(101L, 6L))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.ANALYSIS_NOT_FOUND)
            );
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
