package com.videoagent.summary.controller;

import com.videoagent.summary.dto.VideoChapterResponse;
import com.videoagent.summary.dto.VideoKeyPointResponse;
import com.videoagent.summary.dto.VideoSummaryResponse;
import com.videoagent.summary.service.VideoSummaryService;
import com.videoagent.security.CurrentUserAccessor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/videos/{videoId}")
public class VideoSummaryController {

    private final VideoSummaryService summaryService;
    private final CurrentUserAccessor currentUser;

    public VideoSummaryController(
        VideoSummaryService summaryService,
        CurrentUserAccessor currentUser
    ) {
        this.summaryService = summaryService;
        this.currentUser = currentUser;
    }

    @GetMapping("/summary")
    public ResponseEntity<VideoSummaryResponse> getSummary(@PathVariable long videoId) {
        return summaryService.getSummary(videoId, currentUser.userId())
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/chapters")
    public List<VideoChapterResponse> getChapters(@PathVariable long videoId) {
        return summaryService.getChapters(videoId, currentUser.userId());
    }

    @GetMapping("/key-points")
    public List<VideoKeyPointResponse> getKeyPoints(@PathVariable long videoId) {
        return summaryService.getKeyPoints(videoId, currentUser.userId());
    }
}
