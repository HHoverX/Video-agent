package com.videoagent.rag.controller;

import com.videoagent.rag.dto.RagIndexStatusResponse;
import com.videoagent.rag.entity.VideoRagIndexEntity;
import com.videoagent.rag.service.RagIndexService;
import com.videoagent.security.CurrentUserAccessor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/videos/{videoId}/rag")
public class RagIndexController {

    private final RagIndexService ragIndexService;
    private final CurrentUserAccessor currentUser;

    public RagIndexController(RagIndexService ragIndexService, CurrentUserAccessor currentUser) {
        this.ragIndexService = ragIndexService;
        this.currentUser = currentUser;
    }

    @GetMapping("/status")
    public RagIndexStatusResponse status(@PathVariable long videoId) {
        VideoRagIndexEntity index = ragIndexService.getStatus(videoId, currentUser.userId());
        return new RagIndexStatusResponse(
            index.getContextMode(),
            index.getStatus(),
            index.getChunkCount(),
            index.getEmbeddingModel(),
            index.getTranscriptChars(),
            index.getLastErrorCode(),
            index.getLastErrorMessage()
        );
    }

    @PostMapping("/index")
    public RagIndexStatusResponse build(@PathVariable long videoId) {
        VideoRagIndexEntity index = ragIndexService.buildIndex(videoId, currentUser.userId());
        return new RagIndexStatusResponse(
            index.getContextMode(),
            index.getStatus(),
            index.getChunkCount(),
            index.getEmbeddingModel(),
            index.getTranscriptChars(),
            index.getLastErrorCode(),
            index.getLastErrorMessage()
        );
    }
}
