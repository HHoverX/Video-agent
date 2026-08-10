package com.videoagent.video.controller;

import com.videoagent.security.CurrentUserAccessor;
import com.videoagent.video.dto.VideoPageResponse;
import com.videoagent.video.dto.VideoResponse;
import com.videoagent.video.dto.VideoTitleUpdateRequest;
import com.videoagent.video.dto.VideoUploadResponse;
import com.videoagent.video.service.VideoService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.util.List;

@RestController
@Validated
@RequestMapping("/api/videos")
public class VideoController {

    private final VideoService videoService;
    private final CurrentUserAccessor currentUser;

    public VideoController(VideoService videoService, CurrentUserAccessor currentUser) {
        this.videoService = videoService;
        this.currentUser = currentUser;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VideoUploadResponse> upload(
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "title", required = false) String title
    ) {
        VideoUploadResponse response = videoService.upload(currentUser.userId(), file, title);
        return ResponseEntity
            .created(URI.create("/api/videos/" + response.videoId()))
            .body(response);
    }

    @GetMapping
    public VideoPageResponse list(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size,
        @RequestParam(required = false) String keyword
    ) {
        return videoService.listVideos(currentUser.userId(), page, size, keyword);
    }

    @GetMapping("/{videoId}")
    public VideoResponse detail(@PathVariable long videoId) {
        return videoService.getVideo(videoId, currentUser.userId());
    }

    @PatchMapping("/{videoId}")
    public VideoResponse updateTitle(
        @PathVariable long videoId,
        @Valid @RequestBody VideoTitleUpdateRequest request
    ) {
        return videoService.updateTitle(videoId, currentUser.userId(), request.title());
    }

    @DeleteMapping("/{videoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long videoId) {
        videoService.deleteVideo(videoId, currentUser.userId());
    }
}
