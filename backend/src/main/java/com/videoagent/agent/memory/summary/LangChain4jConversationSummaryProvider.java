package com.videoagent.agent.memory.summary;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.agent.memory.ConversationTurn;

import java.util.List;

public class LangChain4jConversationSummaryProvider implements ConversationSummaryProvider {

    private final LangChain4jConversationSummaryAiService aiService;
    private final ObjectMapper objectMapper;

    public LangChain4jConversationSummaryProvider(
        LangChain4jConversationSummaryAiService aiService,
        ObjectMapper objectMapper
    ) {
        this.aiService = aiService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String summarize(
        String oldSummary,
        List<ConversationTurn> turnsToCompact,
        int maxSummaryChars
    ) {
        try {
            return aiService.summarize(objectMapper.writeValueAsString(new SummaryPrompt(
                oldSummary == null ? "" : oldSummary,
                turnsToCompact == null ? List.of() : turnsToCompact,
                maxSummaryChars
            )));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Conversation summary prompt serialization failed", exception);
        }
    }

    private record SummaryPrompt(
        String oldSummary,
        List<ConversationTurn> turnsToCompact,
        int maxSummaryChars
    ) {
    }
}
