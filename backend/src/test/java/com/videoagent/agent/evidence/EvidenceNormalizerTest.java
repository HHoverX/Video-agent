package com.videoagent.agent.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.videoagent.agent.config.AgentProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class EvidenceNormalizerTest {

    private EvidenceNormalizer normalizer;

    @BeforeEach
    void setUp() {
        // maxEvidenceItems=3, maxEvidenceChars=100.
        AgentProperties properties = new AgentProperties("mock", 4, 15_000L, 120_000L, 3, 100, "");
        normalizer = new EvidenceNormalizer(properties);
    }

    @Test
    void shouldDeduplicateByStableSourceIdentity() {
        List<EvidenceItem> raw = List.of(
            item("E1", "chunk:7", "chunk seven", null, null, 7, null),
            item("E2", "chunk:7", "chunk seven duplicate", null, null, 7, null),
            item("E3", "segment:2", "segment two", null, 2, null, null)
        );
        List<EvidenceItem> result = normalizer.dedupeAndLimit(raw);
        assertThat(result).hasSize(2);
        // First occurrence (E1) wins.
        assertThat(result.getFirst().evidenceId()).isEqualTo("E1");
    }

    @Test
    void shouldLimitEvidenceItemCount() {
        List<EvidenceItem> raw = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            raw.add(item("E" + i, "key" + i, "text " + i, null, null, null, null));
        }
        List<EvidenceItem> result = normalizer.dedupeAndLimit(raw);
        assertThat(result).hasSize(3);
    }

    @Test
    void shouldLimitEvidenceCharCount() {
        List<EvidenceItem> raw = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            raw.add(item("E" + i, "k" + i, "x".repeat(40), null, null, null, null));
        }
        List<EvidenceItem> result = normalizer.dedupeAndLimit(raw);
        assertThat(result).hasSize(2); // 40+40=80, third would be 120 > 100.
    }

    @Test
    void shouldSkipBlankEvidence() {
        List<EvidenceItem> raw = List.of(
            item("E1", "k1", "   ", null, null, null, null),
            item("E2", "k2", "real", null, null, null, null)
        );
        List<EvidenceItem> result = normalizer.dedupeAndLimit(raw);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().evidenceId()).isEqualTo("E2");
    }

    private EvidenceItem item(String id, String key, String text, Long start, Integer segmentIndex, Integer chunkIndex, Float score) {
        return new EvidenceItem(id, EvidenceSourceType.TRANSCRIPT_SEARCH, text, start, null, segmentIndex, chunkIndex, List.of(), score);
    }
}
