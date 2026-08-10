package com.videoagent.analysis.service;

import com.videoagent.analysis.dto.AnalysisProgressSnapshot;
import com.videoagent.analysis.entity.AnalysisStage;
import com.videoagent.analysis.entity.AnalysisStatus;

import org.springframework.stereotype.Component;

/**
 * Single, minimal mechanism for publishing terminal progress (SUCCESS / FAILED)
 * to Redis + SSE. Callers invoke this ONLY after the database conditional
 * transition has committed, so the DB remains the source of truth and a Redis/
 * SSE failure can never change correctness (MEDIUM #6).
 */
@Component
public class TerminalNotifier {

    private final AnalysisProgressUpdateService progressUpdateService;

    public TerminalNotifier(AnalysisProgressUpdateService progressUpdateService) {
        this.progressUpdateService = progressUpdateService;
    }

    public void succeeded(long taskId, long videoId) {
        progressUpdateService.update(taskId, videoId, new AnalysisProgressSnapshot(
            AnalysisStatus.SUCCESS.name(),
            AnalysisStage.DONE.name(),
            100,
            AnalysisStage.DONE.message()
        ));
    }

    public void failed(long taskId, long videoId, int progress, String errorCode, String errorMessage) {
        String safeMessage = errorMessage == null || errorMessage.isBlank()
            ? "分析任务处理失败"
            : (errorMessage.length() <= 1000 ? errorMessage : errorMessage.substring(0, 1000));
        progressUpdateService.update(taskId, videoId, new AnalysisProgressSnapshot(
            AnalysisStatus.FAILED.name(),
            AnalysisStage.FAILED.name(),
            progress,
            safeMessage
        ), errorCode, safeMessage);
    }
}
