package com.videoagent.agent.evidence;

import com.videoagent.agent.config.AgentProperties;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Normalizes and bounds the evidence list before it reaches the synthesizer.
 * Duplicate chunks/segments across tools are dropped by stable source identity
 * (first occurrence wins), and the total is capped by AGENT_MAX_EVIDENCE_ITEMS
 * and AGENT_MAX_EVIDENCE_CHARS as engineering safety limits.
 */
@Component
public class EvidenceNormalizer {

    private final AgentProperties properties;

    public EvidenceNormalizer(AgentProperties properties) {
        this.properties = properties;
    }

    public List<EvidenceItem> dedupeAndLimit(List<EvidenceItem> raw) {
        List<EvidenceItem> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int totalChars = 0;
        for (EvidenceItem item : raw) {
            if (item == null || item.text() == null || item.text().isBlank()) {
                continue;
            }
            if (!seen.add(item.dedupKey())) {
                continue;
            }
            if (result.size() >= properties.maxEvidenceItems()) {
                break;
            }
            int chars = item.text().length();
            if (totalChars + chars > properties.maxEvidenceChars()) {
                continue;
            }
            result.add(item);
            totalChars += chars;
        }
        return result;
    }
}
