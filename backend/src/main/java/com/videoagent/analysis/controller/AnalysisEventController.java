package com.videoagent.analysis.controller;

import com.videoagent.analysis.service.AnalysisEventService;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisEventController {

    private final AnalysisEventService eventService;

    public AnalysisEventController(AnalysisEventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping(path = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable long taskId) {
        return eventService.subscribe(taskId);
    }
}
