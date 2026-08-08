package com.videoagent.analysis.consumer;

import com.videoagent.analysis.dto.AnalysisMessage;
import com.videoagent.analysis.dto.AnalysisProgressSnapshot;
import com.videoagent.analysis.entity.AnalysisStage;
import com.videoagent.analysis.entity.AnalysisStatus;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.progress.AnalysisProgressStore;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.analysis.service.AnalysisProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AnalysisTaskProcessor {

    private static final Logger log = LoggerFactory.getLogger(AnalysisTaskProcessor.class);

    private final AnalysisTaskRepository analysisTaskRepository;
    private final AnalysisProgressStore progressStore;
    private final AnalysisProperties properties;

    public AnalysisTaskProcessor(
        AnalysisTaskRepository analysisTaskRepository,
        AnalysisProgressStore progressStore,
        AnalysisProperties properties
    ) {
        this.analysisTaskRepository = analysisTaskRepository;
        this.progressStore = progressStore;
        this.properties = properties;
    }

    public void process(AnalysisMessage message) {
        if (message == null || message.taskId() == null || message.videoId() == null) {
            log.warn("[stage=CONSUME] ignoring malformed analysis message");
            return;
        }

        AnalysisTaskEntity task = analysisTaskRepository.selectById(message.taskId());
        if (task == null) {
            log.warn("[taskId={}][videoId={}][stage=CONSUME] task not found; message acknowledged",
                message.taskId(), message.videoId());
            return;
        }
        if (!message.videoId().equals(task.getVideoId())) {
            log.warn("[taskId={}][videoId={}][stage=CONSUME] message videoId does not match task",
                message.taskId(), message.videoId());
            return;
        }
        if (AnalysisStatus.SUCCESS.name().equals(task.getStatus())) {
            log.info("[taskId={}][videoId={}][stage=IDEMPOTENCY] task already SUCCESS; skipping duplicate message",
                task.getId(), task.getVideoId());
            return;
        }
        if (!AnalysisStatus.PENDING.name().equals(task.getStatus())) {
            log.info("[taskId={}][videoId={}][stage=IDEMPOTENCY] task status={} is not claimable; skipping",
                task.getId(), task.getVideoId(), task.getStatus());
            return;
        }

        int lastProgress = task.getProgress() == null ? 0 : task.getProgress();
        try {
            pause();
            int claimed = analysisTaskRepository.claimPending(
                task.getId(),
                AnalysisStage.PREPARING.name(),
                20,
                LocalDateTime.now()
            );
            if (claimed != 1) {
                log.info("[taskId={}][videoId={}][stage=IDEMPOTENCY] task was claimed by another consumer",
                    task.getId(), task.getVideoId());
                return;
            }

            lastProgress = 20;
            publish(task.getId(), AnalysisStage.PREPARING, lastProgress);
            pause();

            lastProgress = advance(task.getId(), AnalysisStage.ANALYZING, 40);
            pause();

            lastProgress = advance(task.getId(), AnalysisStage.PROCESSING, 70);
            pause();

            lastProgress = advance(task.getId(), AnalysisStage.SAVING, 90);
            pause();

            int completed = analysisTaskRepository.markSuccess(task.getId(), LocalDateTime.now());
            if (completed != 1) {
                throw new IllegalStateException("Task could not transition to SUCCESS");
            }
            publish(task.getId(), AnalysisStage.DONE, 100);
            log.info("[taskId={}][videoId={}][stage=DONE] simulated analysis completed",
                task.getId(), task.getVideoId());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail(task, lastProgress, "ANALYSIS_INTERRUPTED", "模拟分析线程被中断", exception);
        } catch (RuntimeException exception) {
            fail(task, lastProgress, "ANALYSIS_PROCESSING_FAILED", exception.getMessage(), exception);
        }
    }

    private int advance(long taskId, AnalysisStage stage, int progress) {
        int updated = analysisTaskRepository.updateProcessingProgress(
            taskId,
            stage.name(),
            progress,
            LocalDateTime.now()
        );
        if (updated != 1) {
            throw new IllegalStateException("Task progress update was rejected at stage " + stage.name());
        }
        publish(taskId, stage, progress);
        return progress;
    }

    private void publish(long taskId, AnalysisStage stage, int progress) {
        String status = stage == AnalysisStage.DONE
            ? AnalysisStatus.SUCCESS.name()
            : AnalysisStatus.PROCESSING.name();
        progressStore.save(taskId, new AnalysisProgressSnapshot(
            status,
            stage.name(),
            progress,
            stage.message()
        ));
    }

    private void fail(
        AnalysisTaskEntity task,
        int progress,
        String errorCode,
        String message,
        Exception exception
    ) {
        String safeMessage = message == null || message.isBlank() ? "模拟分析处理失败" : message;
        if (safeMessage.length() > 1000) {
            safeMessage = safeMessage.substring(0, 1000);
        }
        analysisTaskRepository.markFailed(task.getId(), errorCode, safeMessage, LocalDateTime.now());
        progressStore.save(task.getId(), new AnalysisProgressSnapshot(
            AnalysisStatus.FAILED.name(),
            AnalysisStage.FAILED.name(),
            progress,
            safeMessage
        ));
        log.error("[taskId={}][videoId={}][stage=FAILED] simulated analysis failed",
            task.getId(), task.getVideoId(), exception);
    }

    private void pause() throws InterruptedException {
        if (!properties.stepDelay().isZero() && !properties.stepDelay().isNegative()) {
            Thread.sleep(properties.stepDelay().toMillis());
        }
    }
}
