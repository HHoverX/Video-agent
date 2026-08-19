package com.videoagent.upload.controller;

import com.videoagent.security.CurrentUserAccessor;
import com.videoagent.upload.dto.CompleteUploadPartRequest;
import com.videoagent.upload.dto.CompleteUploadResponse;
import com.videoagent.upload.dto.CreateUploadSessionRequest;
import com.videoagent.upload.dto.UploadPartResponse;
import com.videoagent.upload.dto.UploadPartUrlResponse;
import com.videoagent.upload.dto.UploadSessionResponse;
import com.videoagent.upload.service.UploadCompletionService;
import com.videoagent.upload.service.UploadSessionService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

@RestController
@Validated
@RequestMapping("/api/uploads")
public class UploadSessionController {

    private final UploadSessionService uploadSessionService;
    private final UploadCompletionService uploadCompletionService;
    private final CurrentUserAccessor currentUser;

    public UploadSessionController(
        UploadSessionService uploadSessionService,
        UploadCompletionService uploadCompletionService,
        CurrentUserAccessor currentUser
    ) {
        this.uploadSessionService = uploadSessionService;
        this.uploadCompletionService = uploadCompletionService;
        this.currentUser = currentUser;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UploadSessionResponse create(@Valid @RequestBody CreateUploadSessionRequest request) {
        return uploadSessionService.create(currentUser.userId(), request);
    }

    @GetMapping("/{uploadId}")
    public UploadSessionResponse get(@PathVariable String uploadId) {
        return uploadSessionService.get(currentUser.userId(), uploadId);
    }

    @PostMapping("/{uploadId}/parts/{partNumber}/url")
    public UploadPartUrlResponse createPartUrl(
        @PathVariable String uploadId,
        @PathVariable @Min(1) @Max(10_000) int partNumber
    ) {
        return uploadSessionService.createPartUrl(currentUser.userId(), uploadId, partNumber);
    }

    @PostMapping("/{uploadId}/parts/{partNumber}/complete")
    public UploadPartResponse completePart(
        @PathVariable String uploadId,
        @PathVariable @Min(1) @Max(10_000) int partNumber,
        @Valid @RequestBody(required = false) CompleteUploadPartRequest request
    ) {
        return uploadSessionService.confirmPart(currentUser.userId(), uploadId, partNumber, request);
    }

    @PostMapping("/{uploadId}/complete")
    public CompleteUploadResponse complete(@PathVariable String uploadId) {
        return uploadCompletionService.complete(currentUser.userId(), uploadId);
    }

    @DeleteMapping("/{uploadId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable String uploadId) {
        uploadSessionService.cancel(currentUser.userId(), uploadId);
    }
}
