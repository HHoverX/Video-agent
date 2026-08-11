package com.videoagent.agent.qa;

import com.videoagent.agent.evidence.EvidenceItem;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import java.util.List;

/**
 * Real agentic synthesizer backed by the shared LangChain4j ChatModel. The
 * evidence is passed verbatim as untrusted data; the model can only cite the
 * evidence ids it was given.
 */
public class LangChain4jAgenticAnswerProvider implements AgenticAnswerProvider {

    private final LangChain4jAgenticAnswerAiService aiService;

    public LangChain4jAgenticAnswerProvider(LangChain4jAgenticAnswerAiService aiService) {
        this.aiService = aiService;
    }

    @Override
    public AgenticQaResult synthesize(String question, List<EvidenceItem> evidence) {
        try {
            AgenticQaAiResponse response = aiService.synthesize(prompt(question, evidence));
            if (response == null || response.answer() == null || response.answer().isBlank()) {
                throw new VideoAgentException(ErrorCode.LLM_SUMMARY_INVALID, "问答服务返回空回答");
            }
            return new AgenticQaResult(response.answer(), response.citationEvidenceIds());
        } catch (VideoAgentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new VideoAgentException(ErrorCode.LLM_SUMMARY_FAILED, "LLM 问答调用失败", exception);
        }
    }

    private String prompt(String question, List<EvidenceItem> evidence) {
        StringBuilder evidenceBlock = new StringBuilder();
        for (EvidenceItem item : evidence) {
            evidenceBlock.append("[EVIDENCE ")
                .append(item.evidenceId())
                .append("]\n");
            if (item.startMs() != null) {
                evidenceBlock.append("[startMs=")
                    .append(item.startMs())
                    .append(",endMs=")
                    .append(item.endMs())
                    .append("]\n");
            }
            evidenceBlock.append(item.text()).append('\n');
        }
        return """
            问题：%s

            <evidence>
            %s</evidence>
            """.formatted(question, evidenceBlock);
    }
}
