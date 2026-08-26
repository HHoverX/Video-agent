package com.videoagent.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.analysis.dto.AnalysisProgressEventResponse;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.event.AnalysisEventBroadcaster;
import com.videoagent.analysis.progress.AnalysisProgressStore;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.video.service.VideoOwnershipService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

class AnalysisEventServiceTest {

    @Test
    void shouldSeedSseFromMysqlWhenRedisSnapshotIsMissing() {
        AnalysisTaskRepository repository = mock(AnalysisTaskRepository.class);
        AnalysisProgressStore progressStore = mock(AnalysisProgressStore.class);
        AnalysisEventBroadcaster broadcaster = mock(AnalysisEventBroadcaster.class);
        VideoOwnershipService ownershipService = mock(VideoOwnershipService.class);
        AnalysisTaskEntity task = processingTask();
        when(repository.selectById(101L)).thenReturn(task);
        when(progressStore.find(101L)).thenReturn(Optional.empty());
        when(ownershipService.isOwned(7L, 5L)).thenReturn(true);

        AnalysisEventService service = new AnalysisEventService(
            new AnalysisQueryService(
                repository,
                progressStore,
                ownershipService,
                new AnalysisProperties(null, null, null, null, Duration.ofHours(24))
            ),
            broadcaster,
            new AnalysisEventProperties(Duration.ofSeconds(10))
        );

        SseEmitter emitter = service.subscribe(101L, 5L);

        assertThat(emitter.getTimeout()).isEqualTo(10_000L);
        verify(broadcaster).register(101L, emitter);
        ArgumentCaptor<AnalysisProgressEventResponse> event =
            ArgumentCaptor.forClass(AnalysisProgressEventResponse.class);
        verify(broadcaster).send(org.mockito.ArgumentMatchers.eq(101L), event.capture());
        assertThat(event.getAllValues()).allSatisfy(value -> {
            assertThat(value.status()).isEqualTo("PROCESSING");
            assertThat(value.stage()).isEqualTo("TRANSCRIBING");
            assertThat(value.progress()).isEqualTo(70);
        });
    }

    private AnalysisTaskEntity processingTask() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 9, 12, 0);
        AnalysisTaskEntity task = new AnalysisTaskEntity();
        task.setId(101L);
        task.setVideoId(7L);
        task.setStatus("PROCESSING");
        task.setStage("TRANSCRIBING");
        task.setProgress(70);
        task.setCreatedAt(now.minusMinutes(1));
        task.setStartedAt(now);
        return task;
    }
}
