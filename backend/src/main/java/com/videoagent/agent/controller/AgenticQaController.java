package com.videoagent.agent.controller;

import com.videoagent.agent.dto.AgenticQaResponse;
import com.videoagent.agent.service.AgenticVideoQaService;
import com.videoagent.rag.dto.QaRequest;
import com.videoagent.security.CurrentUserAccessor;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/videos/{videoId}/qa/agentic")
public class AgenticQaController {

    private final AgenticVideoQaService agenticQaService;
    private final CurrentUserAccessor currentUser;

    public AgenticQaController(
        AgenticVideoQaService agenticQaService,
        CurrentUserAccessor currentUser
    ) {
        this.agenticQaService = agenticQaService;
        this.currentUser = currentUser;
    }

    @PostMapping
    public AgenticQaResponse ask(@PathVariable long videoId, @Valid @RequestBody QaRequest request) {
        return agenticQaService.answerAgentic(videoId, currentUser.userId(), request.question());
    }
}
