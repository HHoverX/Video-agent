package com.videoagent.analysis.controller;

import com.videoagent.analysis.dto.StartAnalysisResponse;
import com.videoagent.analysis.service.AnalysisCommandService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/videos/{videoId}/analysis")
public class AnalysisCommandController {

    private final AnalysisCommandService analysisCommandService;

    public AnalysisCommandController(AnalysisCommandService analysisCommandService) {
        this.analysisCommandService = analysisCommandService;
    }

    @PostMapping
    public ResponseEntity<StartAnalysisResponse> start(@PathVariable long videoId) {
        return ResponseEntity.accepted().body(analysisCommandService.start(videoId));
    }
}
