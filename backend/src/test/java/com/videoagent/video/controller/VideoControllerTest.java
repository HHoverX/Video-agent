package com.videoagent.video.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.GlobalExceptionHandler;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.video.dto.VideoResponse;
import com.videoagent.video.dto.VideoUploadResponse;
import com.videoagent.video.service.VideoService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

class VideoControllerTest {

    private final VideoService videoService = org.mockito.Mockito.mock(VideoService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(new VideoController(videoService))
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
        when(videoService.upload(file, "Demo")).thenReturn(new VideoUploadResponse(42L));

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
        when(videoService.listVideos()).thenReturn(List.of(video));
        when(videoService.getVideo(42L)).thenReturn(video);

        mockMvc.perform(get("/api/videos"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(42))
            .andExpect(jsonPath("$[0].title").value("Demo"));

        mockMvc.perform(get("/api/videos/42"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.originalFilename").value("demo.mp4"))
            .andExpect(jsonPath("$.status").value("UPLOADED"));
    }

    @Test
    void shouldReturnStructuredNotFoundError() throws Exception {
        when(videoService.getVideo(999L)).thenThrow(new VideoAgentException(ErrorCode.VIDEO_NOT_FOUND));

        mockMvc.perform(get("/api/videos/999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("VIDEO_NOT_FOUND"))
            .andExpect(jsonPath("$.path").value("/api/videos/999"));
    }
}
