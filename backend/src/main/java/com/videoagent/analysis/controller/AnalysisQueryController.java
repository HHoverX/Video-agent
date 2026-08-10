package com.videoagent.analysis.controller;

import com.videoagent.analysis.dto.AnalysisTaskResponse;
import com.videoagent.analysis.service.AnalysisQueryService;
import com.videoagent.security.CurrentUserAccessor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisQueryController {

    private final AnalysisQueryService analysisQueryService;
    private final CurrentUserAccessor currentUser;

    public AnalysisQueryController(
        AnalysisQueryService analysisQueryService,
        CurrentUserAccessor currentUser
    ) {
        this.analysisQueryService = analysisQueryService;
        this.currentUser = currentUser;
    }

    @GetMapping("/{taskId}")
    public AnalysisTaskResponse detail(@PathVariable long taskId) {
        return analysisQueryService.getTask(taskId, currentUser.userId());
    }
}
