package com.videoagent.analysis.controller;

import com.videoagent.analysis.dto.AnalysisTaskResponse;
import com.videoagent.analysis.service.AnalysisQueryService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisQueryController {

    private final AnalysisQueryService analysisQueryService;

    public AnalysisQueryController(AnalysisQueryService analysisQueryService) {
        this.analysisQueryService = analysisQueryService;
    }

    @GetMapping("/{taskId}")
    public AnalysisTaskResponse detail(@PathVariable long taskId) {
        return analysisQueryService.getTask(taskId);
    }
}
