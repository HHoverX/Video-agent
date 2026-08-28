package com.videoagent.asr;

import java.util.ArrayList;
import java.util.List;

final class TranscriptEvidenceSegmenter {

    private static final long MINIMUM_SEGMENT_MS = 5_000L;
    private static final long MIN_TARGET_MS = 10_000L;
    private static final long MAX_TARGET_MS = 45_000L;
    private static final long MAX_SEGMENT_MS = 60_000L;
    private static final int MAX_TEXT_LENGTH = 2_000;

    List<TranscriptSegment> refine(
        TranscriptSegment finalSentence,
        List<TimedWord> words,
        Integer videoDurationSeconds
    ) {
        return refineWithDiagnostics(finalSentence, words, videoDurationSeconds).segments();
    }

    RefinementResult refineWithDiagnostics(
        TranscriptSegment finalSentence,
        List<TimedWord> words,
        Integer videoDurationSeconds
    ) {
        if (videoDurationSeconds == null || words == null || words.size() < 2) {
            return fallback(finalSentence, words, null,
                videoDurationSeconds == null ? FallbackReason.MISSING_DURATION : FallbackReason.INSUFFICIENT_TIMED_WORDS,
                null);
        }

        DurationPolicy policy = durationPolicy(videoDurationSeconds);
        if (finalSentence.endMs() - finalSentence.startMs() <= policy.maxMs()) {
            return fallback(finalSentence, words, policy, FallbackReason.SENTENCE_WITHIN_MAX, null);
        }
        EvidenceValidation validation = validateEvidence(finalSentence, words);
        if (!validation.valid()) {
            return fallback(finalSentence, words, policy, validation.reason(), validation);
        }

        List<WordRange> ranges = split(words, policy);
        mergeShortTail(ranges, words, policy);
        return new RefinementResult(toSegments(ranges, words), null, policy, words.size(), null);
    }

    DurationPolicy durationPolicy(int videoDurationSeconds) {
        long durationMs = Math.max(0L, videoDurationSeconds) * 1_000L;
        long targetMs = clamp(durationMs / 30L, MIN_TARGET_MS, MAX_TARGET_MS);
        long minMs = Math.max(MINIMUM_SEGMENT_MS, targetMs / 2L);
        long maxMs = Math.min(MAX_SEGMENT_MS, targetMs + targetMs / 2L);
        return new DurationPolicy(minMs, targetMs, maxMs);
    }

    private RefinementResult fallback(
        TranscriptSegment sentence,
        List<TimedWord> words,
        DurationPolicy policy,
        FallbackReason reason,
        EvidenceValidation validation
    ) {
        return new RefinementResult(List.of(sentence), reason, policy, words == null ? 0 : words.size(), validation);
    }

    private EvidenceValidation validateEvidence(TranscriptSegment sentence, List<TimedWord> words) {
        StringBuilder reconstructed = new StringBuilder();
        long previousBegin = -1L;
        long previousEnd = -1L;
        for (int index = 0; index < words.size(); index++) {
            TimedWord word = words.get(index);
            if (word == null || word.text() == null || word.punctuation() == null
                || word.text().isEmpty() && word.punctuation().isEmpty()) {
                return EvidenceValidation.field(index);
            }
            if (word.beginMs() < sentence.startMs() || word.endMs() > sentence.endMs()
                || word.endMs() <= word.beginMs() || word.beginMs() < previousBegin
                || word.beginMs() < previousEnd || word.endMs() < previousEnd) {
                return EvidenceValidation.timing(index, word, previousBegin, previousEnd, sentence);
            }
            reconstructed.append(word.text()).append(word.punctuation());
            previousBegin = word.beginMs();
            previousEnd = word.endMs();
        }
        if (!reconstructed.toString().strip().equals(sentence.text().strip())) {
            return EvidenceValidation.textMismatch(reconstructed.length(), sentence.text().length());
        }
        return EvidenceValidation.successful();
    }

    private List<WordRange> split(List<TimedWord> words, DurationPolicy policy) {
        List<WordRange> ranges = new ArrayList<>();
        int start = 0;
        while (start < words.size()) {
            int end = selectEnd(start, words, policy);
            ranges.add(new WordRange(start, end));
            start = end + 1;
        }
        return ranges;
    }

    private int selectEnd(int start, List<TimedWord> words, DurationPolicy policy) {
        long startMs = words.get(start).beginMs();
        int textLimitEnd = largestTextLimitEnd(start, words);
        int strongAfterTarget = -1;
        int bestStrong = -1;
        int bestWeak = -1;
        int bestOther = -1;
        int lastWithinMax = -1;

        for (int index = start; index <= textLimitEnd; index++) {
            long elapsed = words.get(index).endMs() - startMs;
            if (elapsed > policy.maxMs()) {
                break;
            }
            lastWithinMax = index;
            if (elapsed < policy.minMs()) {
                continue;
            }
            Boundary boundary = boundary(words.get(index).punctuation());
            if (boundary == Boundary.STRONG) {
                if (elapsed >= policy.targetMs()) {
                    strongAfterTarget = index;
                    break;
                }
                bestStrong = closerToTarget(bestStrong, index, startMs, words, policy.targetMs());
            } else if (boundary == Boundary.WEAK) {
                bestWeak = closerToTarget(bestWeak, index, startMs, words, policy.targetMs());
            } else {
                bestOther = closerToTarget(bestOther, index, startMs, words, policy.targetMs());
            }
        }
        if (strongAfterTarget >= 0) {
            return strongAfterTarget;
        }
        if (bestStrong >= 0) {
            return bestStrong;
        }
        if (bestWeak >= 0) {
            return bestWeak;
        }
        if (bestOther >= 0) {
            return bestOther;
        }
        return lastWithinMax >= start ? lastWithinMax : start;
    }

    private int largestTextLimitEnd(int start, List<TimedWord> words) {
        int length = 0;
        for (int index = start; index < words.size(); index++) {
            length += words.get(index).text().length() + words.get(index).punctuation().length();
            if (length > MAX_TEXT_LENGTH) {
                return index == start ? index : index - 1;
            }
        }
        return words.size() - 1;
    }

    private int closerToTarget(
        int current,
        int candidate,
        long startMs,
        List<TimedWord> words,
        long targetMs
    ) {
        if (current < 0) {
            return candidate;
        }
        long currentDistance = Math.abs((words.get(current).endMs() - startMs) - targetMs);
        long candidateDistance = Math.abs((words.get(candidate).endMs() - startMs) - targetMs);
        return candidateDistance < currentDistance ? candidate : current;
    }

    private void mergeShortTail(List<WordRange> ranges, List<TimedWord> words, DurationPolicy policy) {
        if (ranges.size() < 2) {
            return;
        }
        WordRange tail = ranges.getLast();
        long tailDuration = duration(tail, words);
        WordRange previous = ranges.get(ranges.size() - 2);
        long mergedDuration = words.get(tail.end()).endMs() - words.get(previous.start()).beginMs();
        if (tailDuration < policy.minMs() && mergedDuration <= policy.maxMs()) {
            ranges.set(ranges.size() - 2, new WordRange(previous.start(), tail.end()));
            ranges.removeLast();
        }
    }

    private List<TranscriptSegment> toSegments(List<WordRange> ranges, List<TimedWord> words) {
        List<TranscriptSegment> segments = new ArrayList<>(ranges.size());
        for (WordRange range : ranges) {
            StringBuilder text = new StringBuilder();
            for (int index = range.start(); index <= range.end(); index++) {
                TimedWord word = words.get(index);
                text.append(word.text()).append(word.punctuation());
            }
            segments.add(new TranscriptSegment(
                words.get(range.start()).beginMs(), words.get(range.end()).endMs(), text.toString()
            ));
        }
        return segments;
    }

    private long duration(WordRange range, List<TimedWord> words) {
        return words.get(range.end()).endMs() - words.get(range.start()).beginMs();
    }

    private Boundary boundary(String punctuation) {
        if (punctuation.chars().anyMatch(character -> "。！？!?.".indexOf(character) >= 0)) {
            return Boundary.STRONG;
        }
        if (punctuation.chars().anyMatch(character -> "，,、；;：:".indexOf(character) >= 0)) {
            return Boundary.WEAK;
        }
        return Boundary.OTHER;
    }

    private long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    record TimedWord(String text, String punctuation, long beginMs, long endMs) {
    }

    record DurationPolicy(long minMs, long targetMs, long maxMs) {
    }

    record RefinementResult(
        List<TranscriptSegment> segments,
        FallbackReason fallbackReason,
        DurationPolicy policy,
        int inputTimedWords,
        EvidenceValidation validation
    ) {
        boolean refined() {
            return fallbackReason == null;
        }
    }

    enum FallbackReason {
        MISSING_DURATION,
        SENTENCE_WITHIN_MAX,
        INSUFFICIENT_TIMED_WORDS,
        INVALID_WORD_FIELD,
        INVALID_WORD_TIMING,
        TEXT_MISMATCH
    }

    record EvidenceValidation(
        boolean valid,
        FallbackReason reason,
        Integer wordIndex,
        Long beginTime,
        Long endTime,
        Long previousBeginTime,
        Long previousEndTime,
        Integer reconstructedLength,
        Integer sentenceTextLength
    ) {
        static EvidenceValidation successful() {
            return new EvidenceValidation(true, null, null, null, null, null, null, null, null);
        }

        static EvidenceValidation field(int wordIndex) {
            return new EvidenceValidation(false, FallbackReason.INVALID_WORD_FIELD,
                wordIndex, null, null, null, null, null, null);
        }

        static EvidenceValidation timing(
            int wordIndex,
            TimedWord word,
            long previousBeginTime,
            long previousEndTime,
            TranscriptSegment sentence
        ) {
            return new EvidenceValidation(false, FallbackReason.INVALID_WORD_TIMING,
                wordIndex, word.beginMs(), word.endMs(), previousBeginTime, previousEndTime,
                null, null);
        }

        static EvidenceValidation textMismatch(int reconstructedLength, int sentenceTextLength) {
            return new EvidenceValidation(false, FallbackReason.TEXT_MISMATCH,
                null, null, null, null, null, reconstructedLength, sentenceTextLength);
        }
    }

    private record WordRange(int start, int end) {
    }

    private enum Boundary {
        STRONG, WEAK, OTHER
    }
}
