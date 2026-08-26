package com.videoagent.analysis.controller;

import com.videoagent.analysis.dto.StartAnalysisResponse;
import com.videoagent.analysis.dto.AnalysisTaskResponse;
import com.videoagent.analysis.service.AnalysisCommandService;
import com.videoagent.analysis.service.AnalysisQueryService;
import com.videoagent.security.CurrentUserAccessor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/videos/{videoId}/analysis")
public class AnalysisCommandController {

    private final AnalysisCommandService analysisCommandService;
    private final AnalysisQueryService analysisQueryService;
    private final CurrentUserAccessor currentUser;

    public AnalysisCommandController(
        AnalysisCommandService analysisCommandService,
        AnalysisQueryService analysisQueryService,
        CurrentUserAccessor currentUser
    ) {
        this.analysisCommandService = analysisCommandService;
        this.analysisQueryService = analysisQueryService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ResponseEntity<AnalysisTaskResponse> current(@PathVariable long videoId) {
        return analysisQueryService.getCurrentTask(videoId, currentUser.userId())
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping
    public ResponseEntity<StartAnalysisResponse> start(@PathVariable long videoId) {
        return ResponseEntity.accepted().body(
            analysisCommandService.start(videoId, currentUser.userId())
        );
    }
}
