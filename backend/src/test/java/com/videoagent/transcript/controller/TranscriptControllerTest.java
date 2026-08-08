package com.videoagent.transcript.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.GlobalExceptionHandler;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.transcript.dto.TranscriptSegmentResponse;
import com.videoagent.transcript.service.TranscriptService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

class TranscriptControllerTest {

    private final TranscriptService transcriptService = mock(TranscriptService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TranscriptController(transcriptService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void shouldReturnOrderedTranscriptSegments() throws Exception {
        when(transcriptService.getVideoTranscript(7L)).thenReturn(List.of(
            new TranscriptSegmentResponse(0, 2_000, "first"),
            new TranscriptSegmentResponse(2_000, 4_000, "second")
        ));

        mockMvc.perform(get("/api/videos/7/transcript"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].startMs").value(0))
            .andExpect(jsonPath("$[0].endMs").value(2_000))
            .andExpect(jsonPath("$[0].text").value("first"))
            .andExpect(jsonPath("$[1].startMs").value(2_000));
    }

    @Test
    void shouldReturnEmptyArrayBeforeTranscriptExistsAnd404ForMissingVideo() throws Exception {
        when(transcriptService.getVideoTranscript(7L)).thenReturn(List.of());
        when(transcriptService.getVideoTranscript(999L))
            .thenThrow(new VideoAgentException(ErrorCode.VIDEO_NOT_FOUND));

        mockMvc.perform(get("/api/videos/7/transcript"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get("/api/videos/999/transcript"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("VIDEO_NOT_FOUND"));
    }
}
