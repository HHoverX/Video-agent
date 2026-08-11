package com.videoagent.agent.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videoagent.agent.dto.AgenticCitation;
import com.videoagent.agent.dto.AgenticQaResponse;
import com.videoagent.agent.service.AgenticVideoQaService;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.GlobalExceptionHandler;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.security.CurrentUserAccessor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

class AgenticQaControllerTest {

    private final AgenticVideoQaService agenticQaService = mock(AgenticVideoQaService.class);
    private final CurrentUserAccessor currentUser = mock(CurrentUserAccessor.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(currentUser.userId()).thenReturn(5L);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AgenticQaController(agenticQaService, currentUser)
            )
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void shouldAnswerAgenticQuestion() throws Exception {
        when(agenticQaService.answerAgentic(7L, 5L, "Redis 作用？"))
            .thenReturn(new AgenticQaResponse(
                "因为延迟低",
                "SEMANTIC_SEARCH",
                "RAG",
                List.of("SEARCH_TRANSCRIPT"),
                List.of(new AgenticCitation("TRANSCRIPT_SEARCH", 0L, 2000L, "Redis 缓存"))
            ));

        mockMvc.perform(post("/api/videos/7/qa/agentic")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Redis 作用？\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.strategy").value("SEMANTIC_SEARCH"))
            .andExpect(jsonPath("$.toolsUsed[0]").value("SEARCH_TRANSCRIPT"))
            .andExpect(jsonPath("$.citations[0].startMs").value(0));
    }

    @Test
    void shouldRejectBlankQuestion() throws Exception {
        mockMvc.perform(post("/api/videos/7/qa/agentic")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"   \"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn404ForForeignVideo() throws Exception {
        when(agenticQaService.answerAgentic(anyLong(), anyLong(), anyString()))
            .thenThrow(new VideoAgentException(ErrorCode.VIDEO_NOT_FOUND));

        mockMvc.perform(post("/api/videos/7/qa/agentic")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"谁拍的？\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("VIDEO_NOT_FOUND"));
    }
}
