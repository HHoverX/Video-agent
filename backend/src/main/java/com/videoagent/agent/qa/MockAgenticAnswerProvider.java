package com.videoagent.agent.qa;

import com.videoagent.agent.evidence.EvidenceItem;
import com.videoagent.agent.evidence.EvidenceSourceType;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic mock synthesizer for tests/local dev. Picks the evidence item
 * with the highest word-overlap against the question and cites it; if nothing
 * overlaps it returns the grounded "cannot determine" answer with no citation.
 */
public class MockAgenticAnswerProvider implements AgenticAnswerProvider {

    private static final String CANNOT_DETERMINE = "根据当前视频内容无法确定。";

    @Override
    public AgenticQaResult synthesize(String question, List<EvidenceItem> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return new AgenticQaResult(CANNOT_DETERMINE, List.of());
        }
        // Time-lookup evidence is already selected by the time window; ground on
        // it regardless of lexical overlap.
        EvidenceItem timeEvidence = evidence.stream()
            .filter(e -> e.sourceType() == EvidenceSourceType.TRANSCRIPT_TIME)
            .findFirst()
            .orElse(null);
        if (timeEvidence != null) {
            return new AgenticQaResult(
                "根据视频内容，" + timeEvidence.text().strip(),
                List.of(timeEvidence.evidenceId())
            );
        }
        // Single-tool flows (summary) ground on their one piece of evidence.
        if (evidence.size() == 1) {
            return new AgenticQaResult("根据视频内容，" + evidence.getFirst().text().strip(),
                List.of(evidence.getFirst().evidenceId()));
        }
        // Multi-search picks the highest-overlap item.
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        EvidenceItem best = evidence.stream()
            .max(Comparator
                .comparingInt((EvidenceItem item) -> overlapScore(q, item.text()))
                .thenComparing(EvidenceItem::evidenceId, Comparator.naturalOrder()))
            .orElse(null);
        if (best == null || overlapScore(q, best.text()) == 0) {
            return new AgenticQaResult(CANNOT_DETERMINE, List.of());
        }
        return new AgenticQaResult("根据视频内容，" + best.text().strip(), List.of(best.evidenceId()));
    }

    private int overlapScore(String question, String text) {
        if (text == null) {
            return 0;
        }
        int score = 0;
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!word.isBlank() && question.contains(word)) {
                score++;
            }
        }
        return score;
    }
}
