package com.videoagent.analysis.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videoagent.analysis.dto.AnalysisTaskResponse;
import com.videoagent.analysis.dto.StartAnalysisResponse;
import com.videoagent.analysis.service.AnalysisCommandService;
import com.videoagent.analysis.service.AnalysisQueryService;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.GlobalExceptionHandler;
import com.videoagent.common.exception.VideoAgentException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;

class AnalysisControllerTest {

    private final AnalysisCommandService commandService = mock(AnalysisCommandService.class);
    private final AnalysisQueryService queryService = mock(AnalysisQueryService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AnalysisCommandController(commandService),
                new AnalysisQueryController(queryService)
            )
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void shouldAcceptAnalysisWithoutWaitingForConsumer() throws Exception {
        when(commandService.start(7L)).thenReturn(new StartAnalysisResponse(101L, 7L, "PENDING"));

        mockMvc.perform(post("/api/videos/7/analysis"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.taskId").value(101))
            .andExpect(jsonPath("$.videoId").value(7))
            .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void shouldReturnTaskProgress() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 8, 8, 20, 0);
        when(queryService.getTask(101L)).thenReturn(new AnalysisTaskResponse(
            101L, 7L, "PROCESSING", "ANALYZING", 40, "正在模拟分析",
            null, null, now, now, null
        ));

        mockMvc.perform(get("/api/analysis/101"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.taskId").value(101))
            .andExpect(jsonPath("$.progress").value(40))
            .andExpect(jsonPath("$.stage").value("ANALYZING"));
    }

    @Test
    void shouldReturnRequiredBusinessErrors() throws Exception {
        when(commandService.start(999L)).thenThrow(new VideoAgentException(ErrorCode.VIDEO_NOT_FOUND));
        when(commandService.start(7L)).thenThrow(new VideoAgentException(ErrorCode.ANALYSIS_ALREADY_RUNNING));
        when(queryService.getTask(999L)).thenThrow(new VideoAgentException(ErrorCode.ANALYSIS_NOT_FOUND));

        mockMvc.perform(post("/api/videos/999/analysis"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("VIDEO_NOT_FOUND"));
        mockMvc.perform(post("/api/videos/7/analysis"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ANALYSIS_ALREADY_RUNNING"));
        mockMvc.perform(get("/api/analysis/999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
    }
}
