package com.videoagent.analysis.progress;

import com.videoagent.analysis.dto.AnalysisProgressSnapshot;

import java.util.Optional;

public interface AnalysisProgressStore {

    void save(long taskId, AnalysisProgressSnapshot progress);

    Optional<AnalysisProgressSnapshot> find(long taskId);
}
