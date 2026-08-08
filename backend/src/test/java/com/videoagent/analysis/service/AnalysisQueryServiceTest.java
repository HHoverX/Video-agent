package com.videoagent.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.videoagent.analysis.dto.AnalysisProgressSnapshot;
import com.videoagent.analysis.dto.AnalysisTaskResponse;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.progress.AnalysisProgressStore;
import com.videoagent.analysis.repository.AnalysisTaskRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalysisQueryServiceTest {

    private final AnalysisTaskRepository repository = mock(AnalysisTaskRepository.class);
    private final AnalysisProgressStore progressStore = mock(AnalysisProgressStore.class);
    private AnalysisQueryService service;

    @BeforeEach
    void setUp() {
        service = new AnalysisQueryService(repository, progressStore);
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

        AnalysisTaskResponse response = service.getTask(101L);

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

        AnalysisTaskResponse response = service.getTask(101L);

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

        AnalysisTaskResponse response = service.getTask(101L);

        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.stage()).isEqualTo("DONE");
        assertThat(response.progress()).isEqualTo(100);
        assertThat(response.message()).isEqualTo("分析完成");
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
