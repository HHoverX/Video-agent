package com.videoagent.rag.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.GlobalExceptionHandler;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.rag.dto.QaRequest;
import com.videoagent.rag.dto.QaResponse;
import com.videoagent.rag.entity.RagIndexStatus;
import com.videoagent.rag.entity.VideoRagIndexEntity;
import com.videoagent.rag.service.RagIndexService;
import com.videoagent.rag.service.VideoQaService;
import com.videoagent.security.CurrentUserAccessor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

class RagControllerTest {

    private final RagIndexService ragIndexService = mock(RagIndexService.class);
    private final VideoQaService qaService = mock(VideoQaService.class);
    private final CurrentUserAccessor currentUser = mock(CurrentUserAccessor.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(currentUser.userId()).thenReturn(5L);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new RagIndexController(ragIndexService, currentUser),
                new VideoQaController(qaService, currentUser)
            )
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void shouldReturnRagStatus() throws Exception {
        VideoRagIndexEntity index = new VideoRagIndexEntity();
        index.setContextMode("DIRECT_CONTEXT");
        index.setStatus(RagIndexStatus.NOT_REQUIRED.name());
        index.setTranscriptChars(100);
        index.setChunkCount(0);
        when(ragIndexService.getStatus(7L, 5L)).thenReturn(index);

        mockMvc.perform(get("/api/videos/7/rag/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("DIRECT_CONTEXT"))
            .andExpect(jsonPath("$.status").value("NOT_REQUIRED"))
            .andExpect(jsonPath("$.chunkCount").value(0));
    }

    @Test
    void shouldBuildIndex() throws Exception {
        VideoRagIndexEntity index = new VideoRagIndexEntity();
        index.setContextMode("RAG");
        index.setStatus(RagIndexStatus.READY.name());
        index.setChunkCount(3);
        when(ragIndexService.buildIndex(7L, 5L)).thenReturn(index);

        mockMvc.perform(post("/api/videos/7/rag/index"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("RAG"))
            .andExpect(jsonPath("$.status").value("READY"));
    }

    @Test
    void shouldAnswerQa() throws Exception {
        when(qaService.answer(7L, 5L, "问题？"))
            .thenReturn(new QaResponse("DIRECT_CONTEXT", "答案", List.of()));

        mockMvc.perform(post("/api/videos/7/qa")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"问题？\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("DIRECT_CONTEXT"))
            .andExpect(jsonPath("$.answer").value("答案"));
    }

    @Test
    void shouldRejectBlankQuestion() throws Exception {
        mockMvc.perform(post("/api/videos/7/qa")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"   \"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn404ForForeignVideoStatus() throws Exception {
        when(ragIndexService.getStatus(7L, 5L))
            .thenThrow(new VideoAgentException(ErrorCode.VIDEO_NOT_FOUND));

        mockMvc.perform(get("/api/videos/7/rag/status"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("VIDEO_NOT_FOUND"));
    }

    @Test
    void shouldReturn404ForForeignVideoQa() throws Exception {
        when(qaService.answer(anyLong(), anyLong(), anyString()))
            .thenThrow(new VideoAgentException(ErrorCode.VIDEO_NOT_FOUND));

        mockMvc.perform(post("/api/videos/7/qa")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"谁拍的？\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("VIDEO_NOT_FOUND"));
    }

    @Test
    void shouldReturnConflictWhenRagIndexNotReady() throws Exception {
        when(qaService.answer(anyLong(), anyLong(), anyString()))
            .thenThrow(new VideoAgentException(ErrorCode.RAG_INDEX_NOT_READY));

        mockMvc.perform(post("/api/videos/7/qa")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"内容是什么？\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("RAG_INDEX_NOT_READY"));
    }
}
