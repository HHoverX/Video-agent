package com.videoagent.video.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.GlobalExceptionHandler;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.security.CurrentUserAccessor;
import com.videoagent.video.dto.VideoPageResponse;
import com.videoagent.video.dto.VideoResponse;
import com.videoagent.video.dto.VideoUploadResponse;
import com.videoagent.video.service.VideoService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

class VideoControllerTest {

    private final VideoService videoService = org.mockito.Mockito.mock(VideoService.class);
    private final CurrentUserAccessor currentUser = org.mockito.Mockito.mock(CurrentUserAccessor.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(currentUser.userId()).thenReturn(5L);
        mockMvc = MockMvcBuilders
            .standaloneSetup(new VideoController(videoService, currentUser))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void shouldUploadMultipartVideo() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "demo.mp4",
            "video/mp4",
            "content".getBytes()
        );
        when(videoService.upload(5L, file, "Demo")).thenReturn(new VideoUploadResponse(42L));

        mockMvc.perform(multipart("/api/videos").file(file).param("title", "Demo"))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/api/videos/42"))
            .andExpect(jsonPath("$.videoId").value(42));
    }

    @Test
    void shouldListVideosAndReturnDetail() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 8, 8, 12, 0);
        VideoResponse video = new VideoResponse(
            42L, "Demo", "demo.mp4", 128L, null, "video/mp4", "UPLOADED", now, now
        );
        when(videoService.listVideos(5L, 1, 10, null)).thenReturn(
            new VideoPageResponse(List.of(video), 1, 10, 1, 1)
        );
        when(videoService.getVideo(42L, 5L)).thenReturn(video);

        mockMvc.perform(get("/api/videos"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].id").value(42))
            .andExpect(jsonPath("$.items[0].title").value("Demo"))
            .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(get("/api/videos/42"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.originalFilename").value("demo.mp4"))
            .andExpect(jsonPath("$.status").value("UPLOADED"));
    }

    @Test
    void shouldReturnStructuredNotFoundError() throws Exception {
        when(videoService.getVideo(999L, 5L)).thenThrow(new VideoAgentException(ErrorCode.VIDEO_NOT_FOUND));

        mockMvc.perform(get("/api/videos/999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("VIDEO_NOT_FOUND"))
            .andExpect(jsonPath("$.path").value("/api/videos/999"));
    }

    @Test
    void shouldUpdateTitleAndDeleteOwnedVideo() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 8, 8, 12, 0);
        VideoResponse updated = new VideoResponse(
            42L, "New title", "demo.mp4", 128L, null, "video/mp4", "UPLOADED", now, now
        );
        when(videoService.updateTitle(42L, 5L, "New title")).thenReturn(updated);

        mockMvc.perform(patch("/api/videos/42")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"New title\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("New title"));
        mockMvc.perform(delete("/api/videos/42"))
            .andExpect(status().isNoContent());
    }

    @Test
    void shouldRejectBlankTitle() throws Exception {
        mockMvc.perform(patch("/api/videos/42")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"   \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
