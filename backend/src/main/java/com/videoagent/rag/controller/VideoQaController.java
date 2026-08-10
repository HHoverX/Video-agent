package com.videoagent.rag.controller;

import com.videoagent.rag.dto.QaRequest;
import com.videoagent.rag.dto.QaResponse;
import com.videoagent.rag.service.VideoQaService;
import com.videoagent.security.CurrentUserAccessor;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/videos/{videoId}/qa")
public class VideoQaController {

    private final VideoQaService qaService;
    private final CurrentUserAccessor currentUser;

    public VideoQaController(VideoQaService qaService, CurrentUserAccessor currentUser) {
        this.qaService = qaService;
        this.currentUser = currentUser;
    }

    @PostMapping
    public QaResponse ask(@PathVariable long videoId, @Valid @RequestBody QaRequest request) {
        return qaService.answer(videoId, currentUser.userId(), request.question());
    }
}
