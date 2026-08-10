package com.videoagent.analysis.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videoagent.analysis.dto.AnalysisProgressEventResponse;
import com.videoagent.analysis.service.AnalysisEventService;
import com.videoagent.security.CurrentUserAccessor;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AnalysisEventControllerTest {

    @Test
    void shouldExposeProgressAsEventStream() throws Exception {
        AnalysisEventService eventService = mock(AnalysisEventService.class);
        CurrentUserAccessor currentUser = mock(CurrentUserAccessor.class);
        SseEmitter emitter = new SseEmitter();
        emitter.send(SseEmitter.event()
            .name("progress")
            .data(new AnalysisProgressEventResponse(
                101L, 7L, "SUCCESS", "DONE", 100, "分析完成", null, null
            ), MediaType.APPLICATION_JSON));
        emitter.complete();
        when(currentUser.userId()).thenReturn(5L);
        when(eventService.subscribe(101L, 5L)).thenReturn(emitter);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
            new AnalysisEventController(eventService, currentUser)
        ).build();

        MvcResult pending = mockMvc.perform(get("/api/analysis/101/events"))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andReturn();
        MvcResult completed = mockMvc.perform(asyncDispatch(pending))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(completed.getResponse().getContentType()).startsWith("text/event-stream");
        assertThat(completed.getResponse().getContentAsString())
            .contains("event:progress", "\"taskId\":101", "\"status\":\"SUCCESS\"");
    }
}
