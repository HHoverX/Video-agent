package com.videoagent.rag.qa;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic mock QA for unit/infra tests and local development. It selects
 * the context item with the highest word-overlap against the question (so the
 * "Redis" chunk wins for a "Redis" question), then returns a grounded answer
 * citing exactly that item. If nothing overlaps, it returns the grounded
 * "cannot determine" fallback with no citation.
 */
public class MockVideoQaProvider implements VideoQaProvider {

    private static final String CANNOT_DETERMINE = "根据当前视频内容无法确定。";

    @Override
    public VideoQaResult answer(VideoQaRequest request) {
        String question = request.question() == null ? "" : request.question().toLowerCase(Locale.ROOT);
        // Deterministic: max word-overlap with the question, ties broken by the
        // highest context index so results are stable.
        VideoQaRequest.ContextItem best = request.context().stream()
            .max(Comparator
                .comparingInt((VideoQaRequest.ContextItem item) -> overlapScore(question, item.text()))
                .thenComparing(Comparator.comparingInt(VideoQaRequest.ContextItem::index).reversed()))
            .orElse(null);
        if (best == null || overlapScore(question, best.text()) == 0) {
            return new VideoQaResult(CANNOT_DETERMINE, List.of());
        }
        String answer = "根据视频内容，" + best.text().strip();
        return new VideoQaResult(answer, List.of(best.index()));
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
