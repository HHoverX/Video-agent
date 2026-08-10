package com.videoagent.analysis.service;

import com.videoagent.analysis.dto.AnalysisProgressEventResponse;
import com.videoagent.analysis.dto.AnalysisTaskResponse;
import com.videoagent.analysis.event.AnalysisEventBroadcaster;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AnalysisEventService {

    private final AnalysisQueryService queryService;
    private final AnalysisEventBroadcaster broadcaster;
    private final AnalysisEventProperties properties;

    public AnalysisEventService(
        AnalysisQueryService queryService,
        AnalysisEventBroadcaster broadcaster,
        AnalysisEventProperties properties
    ) {
        this.queryService = queryService;
        this.broadcaster = broadcaster;
        this.properties = properties;
    }

    public SseEmitter subscribe(long taskId, long userId) {
        AnalysisTaskResponse beforeRegistration = queryService.getTask(taskId, userId);
        AnalysisProgressEventResponse initialEvent = AnalysisProgressEventResponse.from(beforeRegistration);
        SseEmitter emitter = new SseEmitter(properties.timeout().toMillis());
        broadcaster.register(taskId, emitter);
        try {
            broadcaster.send(taskId, initialEvent);
            if (!terminal(beforeRegistration.status())) {
                AnalysisTaskResponse afterRegistration = queryService.getTask(taskId, userId);
                AnalysisProgressEventResponse currentEvent = AnalysisProgressEventResponse.from(afterRegistration);
                if (!currentEvent.equals(initialEvent)) {
                    broadcaster.send(taskId, currentEvent);
                }
            }
            return emitter;
        } catch (RuntimeException exception) {
            broadcaster.disconnect(taskId, emitter);
            throw exception;
        }
    }

    private boolean terminal(String status) {
        return "SUCCESS".equals(status) || "FAILED".equals(status);
    }
}
