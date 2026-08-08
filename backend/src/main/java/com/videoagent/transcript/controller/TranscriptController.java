package com.videoagent.transcript.controller;

import com.videoagent.transcript.dto.TranscriptSegmentResponse;
import com.videoagent.transcript.service.TranscriptService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/videos/{videoId}/transcript")
public class TranscriptController {

    private final TranscriptService transcriptService;

    public TranscriptController(TranscriptService transcriptService) {
        this.transcriptService = transcriptService;
    }

    @GetMapping
    public List<TranscriptSegmentResponse> getTranscript(@PathVariable long videoId) {
        return transcriptService.getVideoTranscript(videoId);
    }
}
