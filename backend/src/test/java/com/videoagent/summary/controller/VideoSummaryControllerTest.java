package com.videoagent.summary.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.GlobalExceptionHandler;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.summary.dto.VideoChapterResponse;
import com.videoagent.summary.dto.VideoKeyPointResponse;
import com.videoagent.summary.dto.VideoSummaryResponse;
import com.videoagent.summary.service.VideoSummaryService;
import com.videoagent.security.CurrentUserAccessor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

class VideoSummaryControllerTest {

    private final VideoSummaryService service = mock(VideoSummaryService.class);
    private final CurrentUserAccessor currentUser = mock(CurrentUserAccessor.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(currentUser.userId()).thenReturn(5L);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new VideoSummaryController(service, currentUser)
            )
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void shouldReturnSummaryAndOrderedCollections() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 8, 8, 12, 0);
        when(service.getSummary(7L, 5L)).thenReturn(Optional.of(
            new VideoSummaryResponse(11L, "overview", now, now)
        ));
        when(service.getChapters(7L, 5L)).thenReturn(List.of(
            new VideoChapterResponse(0, "first", "summary", 0, 2_000),
            new VideoChapterResponse(1, "second", "summary", 2_000, 4_000)
        ));
        when(service.getKeyPoints(7L, 5L)).thenReturn(List.of(
            new VideoKeyPointResponse(0, "point", 0, 2_000)
        ));

        mockMvc.perform(get("/api/videos/7/summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.taskId").value(11))
            .andExpect(jsonPath("$.overview").value("overview"));
        mockMvc.perform(get("/api/videos/7/chapters"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].chapterIndex").value(0))
            .andExpect(jsonPath("$[1].startMs").value(2_000));
        mockMvc.perform(get("/api/videos/7/key-points"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].content").value("point"));
    }

    @Test
    void shouldReturnNoContentWhenSummaryIsNotReadyAnd404ForMissingVideo() throws Exception {
        when(service.getSummary(7L, 5L)).thenReturn(Optional.empty());
        when(service.getSummary(999L, 5L)).thenThrow(
            new VideoAgentException(ErrorCode.VIDEO_NOT_FOUND)
        );

        mockMvc.perform(get("/api/videos/7/summary"))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/videos/999/summary"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("VIDEO_NOT_FOUND"));
    }
}
