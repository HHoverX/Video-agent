package com.videoagent.asr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class TranscriptEvidenceSegmenterTest {

    private final TranscriptEvidenceSegmenter segmenter = new TranscriptEvidenceSegmenter();

    @Test
    void shouldCalculateDurationAwarePolicy() {
        assertThat(segmenter.durationPolicy(49))
            .isEqualTo(new TranscriptEvidenceSegmenter.DurationPolicy(5_000, 10_000, 15_000));
        assertThat(segmenter.durationPolicy(180))
            .isEqualTo(new TranscriptEvidenceSegmenter.DurationPolicy(5_000, 10_000, 15_000));
        assertThat(segmenter.durationPolicy(900))
            .isEqualTo(new TranscriptEvidenceSegmenter.DurationPolicy(15_000, 30_000, 45_000));
        assertThat(segmenter.durationPolicy(3_600))
            .isEqualTo(new TranscriptEvidenceSegmenter.DurationPolicy(22_500, 45_000, 60_000));
    }

    @Test
    void shouldRefineCoarseSentenceUsingOnlyWordTimings() {
        List<TranscriptEvidenceSegmenter.TimedWord> words = List.of(
            word("甲", 240, 5_000, "，"), word("乙", 5_000, 10_000, "。"),
            word("丙", 10_000, 15_000, "，"), word("丁", 15_000, 20_000, "。"),
            word("戊", 20_000, 25_000, "，"), word("己", 25_000, 30_000, "。"),
            word("庚", 30_000, 35_000, "，"), word("辛", 35_000, 40_000, "。"),
            word("壬", 40_000, 45_000, "，"), word("癸", 45_000, 49_132, "。")
        );
        TranscriptSegment sentence = sentence(words);

        List<TranscriptSegment> result = segmenter.refine(sentence, words, 49);

        assertThat(result).extracting(TranscriptSegment::text)
            .containsExactly("甲，乙。", "丙，丁。", "戊，己。", "庚，辛。", "壬，癸。");
        assertThat(result).extracting(TranscriptSegment::startMs)
            .containsExactly(240L, 10_000L, 20_000L, 30_000L, 40_000L);
        assertThat(result).extracting(TranscriptSegment::endMs)
            .containsExactly(10_000L, 20_000L, 30_000L, 40_000L, 49_132L);
    }

    @Test
    void shouldPreferStrongThenWeakThenRealWordBoundaryNearTarget() {
        List<TranscriptEvidenceSegmenter.TimedWord> strongWords = List.of(
            word("a", 0, 6_000, ","), word("b", 6_000, 9_000, "."),
            word("c", 9_000, 12_000, "."), word("d", 12_000, 18_000, ".")
        );
        List<TranscriptEvidenceSegmenter.TimedWord> weakWords = List.of(
            word("a", 0, 6_000, ","), word("b", 6_000, 9_000, ";"),
            word("c", 9_000, 12_000, ":"), word("d", 12_000, 18_000, ",")
        );
        List<TranscriptEvidenceSegmenter.TimedWord> plainWords = List.of(
            word("a", 0, 6_000, ""), word("b", 6_000, 9_000, ""),
            word("c", 9_000, 12_000, ""), word("d", 12_000, 18_000, "")
        );

        assertThat(segmenter.refine(sentence(strongWords), strongWords, 49).getFirst().endMs()).isEqualTo(12_000L);
        assertThat(segmenter.refine(sentence(weakWords), weakWords, 49).getFirst().endMs()).isEqualTo(9_000L);
        assertThat(segmenter.refine(sentence(plainWords), plainWords, 49).getFirst().endMs()).isEqualTo(9_000L);
    }

    @Test
    void shouldMergeShortTailOnlyWhenMergedDurationFitsMax() {
        List<TranscriptEvidenceSegmenter.TimedWord> mergeable = List.of(
            word("a", 0, 5_000, ""), word("b", 5_000, 10_000, "."), word("c", 10_000, 14_000, "")
        );
        List<TranscriptEvidenceSegmenter.TimedWord> nonMergeable = List.of(
            word("a", 0, 7_000, ""), word("b", 7_000, 14_000, "."), word("c", 14_000, 18_000, "")
        );

        assertThat(segmenter.refine(sentence(mergeable), mergeable, 49)).hasSize(1);
        assertThat(segmenter.refine(sentence(nonMergeable), nonMergeable, 49))
            .extracting(TranscriptSegment::endMs).containsExactly(14_000L, 18_000L);
    }

    @Test
    void shouldPreserveChineseAndProviderSpacingExactly() {
        List<TranscriptEvidenceSegmenter.TimedWord> chinese = List.of(
            word("你好", 0, 5_000, "，"), word("世界", 5_000, 10_000, "。"),
            word("再见", 10_000, 16_000, "。")
        );
        List<TranscriptEvidenceSegmenter.TimedWord> english = List.of(
            word("Hello", 0, 5_000, ","), word(" World", 5_000, 10_000, "!"),
            word(" Again", 10_000, 16_000, ".")
        );

        assertThat(segmenter.refine(sentence(chinese), chinese, 49))
            .extracting(TranscriptSegment::text).containsExactly("你好，世界。", "再见。");
        assertThat(segmenter.refine(sentence(english), english, 49))
            .extracting(TranscriptSegment::text).containsExactly("Hello, World!", "Again.");
    }

    @Test
    void shouldPreserveOriginalWhenDurationOrEvidenceIsNotTrustworthy() {
        List<TranscriptEvidenceSegmenter.TimedWord> valid = List.of(
            word("a", 0, 8_000, " "), word("b", 8_000, 16_000, ""), word("c", 16_000, 24_000, "")
        );
        TranscriptSegment sentence = sentence(valid);
        List<TranscriptEvidenceSegmenter.TimedWord> invalidTime = List.of(
            word("a", 0, 8_000, " "), word("b", 7_000, 16_000, ""), word("c", 16_000, 24_000, "")
        );
        List<TranscriptEvidenceSegmenter.TimedWord> mismatch = List.of(
            word("x", 0, 8_000, " "), word("b", 8_000, 16_000, ""), word("c", 16_000, 24_000, "")
        );
        List<TranscriptEvidenceSegmenter.TimedWord> partial = List.of(
            word("a", 0, 8_000, " "), new TranscriptEvidenceSegmenter.TimedWord(null, "", 8_000, 16_000),
            word("c", 16_000, 24_000, "")
        );

        assertThat(segmenter.refine(sentence, valid, null)).containsExactly(sentence);
        assertThat(segmenter.refine(sentence, List.of(), 49)).containsExactly(sentence);
        assertThat(segmenter.refine(sentence, invalidTime, 49)).containsExactly(sentence);
        assertThat(segmenter.refine(sentence, mismatch, 49)).containsExactly(sentence);
        assertThat(segmenter.refine(sentence, partial, 49)).containsExactly(sentence);
        assertThat(segmenter.refine(sentence, valid, 49)).isEqualTo(segmenter.refine(sentence, valid, 49));
    }

    @Test
    void shouldKeepNormalFinalSentenceUnchanged() {
        List<TranscriptEvidenceSegmenter.TimedWord> words = List.of(
            word("a", 0, 4_000, "."), word("b", 4_000, 8_000, ".")
        );
        TranscriptSegment sentence = sentence(words);

        assertThat(segmenter.refine(sentence, words, 49)).containsExactly(sentence);
    }

    @Test
    void shouldReportDeterministicFallbackReasonsWithoutTextDiagnostics() {
        List<TranscriptEvidenceSegmenter.TimedWord> valid = List.of(
            word("a", 0, 8_000, ""), word("b", 8_000, 16_000, ""), word("c", 16_000, 24_000, "")
        );
        TranscriptSegment sentence = sentence(valid);
        List<TranscriptEvidenceSegmenter.TimedWord> invalidTime = List.of(
            word("a", 0, 8_000, ""), word("b", 7_000, 16_000, ""), word("c", 16_000, 24_000, "")
        );
        List<TranscriptEvidenceSegmenter.TimedWord> mismatch = List.of(
            word("x", 0, 8_000, ""), word("b", 8_000, 16_000, ""), word("c", 16_000, 24_000, "")
        );
        List<TranscriptEvidenceSegmenter.TimedWord> malformed = List.of(
            new TranscriptEvidenceSegmenter.TimedWord(null, "", 0, 8_000), word("b", 8_000, 16_000, "")
        );

        assertThat(segmenter.refineWithDiagnostics(sentence, valid, null).fallbackReason())
            .isEqualTo(TranscriptEvidenceSegmenter.FallbackReason.MISSING_DURATION);
        assertThat(segmenter.refineWithDiagnostics(sentence, malformed, 49).fallbackReason())
            .isEqualTo(TranscriptEvidenceSegmenter.FallbackReason.INVALID_WORD_FIELD);
        assertThat(segmenter.refineWithDiagnostics(sentence, invalidTime, 49).fallbackReason())
            .isEqualTo(TranscriptEvidenceSegmenter.FallbackReason.INVALID_WORD_TIMING);
        TranscriptEvidenceSegmenter.RefinementResult mismatchResult = segmenter.refineWithDiagnostics(sentence, mismatch, 49);
        assertThat(mismatchResult.fallbackReason()).isEqualTo(TranscriptEvidenceSegmenter.FallbackReason.TEXT_MISMATCH);
        assertThat(mismatchResult.validation().reconstructedLength()).isEqualTo(3);
        assertThat(mismatchResult.validation().sentenceTextLength()).isEqualTo(3);
    }

    @Test
    void shouldReportSuccessfulRefinementMetadataWithoutTranscriptContent() {
        List<TranscriptEvidenceSegmenter.TimedWord> words = List.of(
            word("a", 0, 8_000, ""), word("b", 8_000, 16_000, ""), word("c", 16_000, 24_000, "")
        );

        TranscriptEvidenceSegmenter.RefinementResult result = segmenter.refineWithDiagnostics(sentence(words), words, 49);

        assertThat(result.refined()).isTrue();
        assertThat(result.inputTimedWords()).isEqualTo(3);
        assertThat(result.segments()).hasSize(3);
        assertThat(result.policy()).isEqualTo(new TranscriptEvidenceSegmenter.DurationPolicy(5_000, 10_000, 15_000));
        assertThat(Arrays.stream(TranscriptEvidenceSegmenter.EvidenceValidation.class.getRecordComponents())
            .map(component -> component.getType().getName()))
            .doesNotContain(String.class.getName());
    }

    @Test
    void shouldAcceptPunctuationOnlyTimedEvidenceAndPreserveItsPunctuation() {
        List<TranscriptEvidenceSegmenter.TimedWord> words = List.of(
            word("甲", 0, 5_000, ""), word("", 5_000, 6_000, "。"),
            word("乙", 6_000, 11_000, ""), word("", 11_000, 12_000, "。"),
            word("丙", 12_000, 18_000, "。")
        );
        TranscriptSegment sentence = sentence(words);

        TranscriptEvidenceSegmenter.RefinementResult result = segmenter.refineWithDiagnostics(sentence, words, 49);

        assertThat(result.refined()).isTrue();
        assertThat(result.segments()).extracting(TranscriptSegment::text)
            .containsExactly("甲。乙。", "丙。");
        assertThat(result.segments()).extracting(TranscriptSegment::startMs).containsExactly(0L, 12_000L);
        assertThat(result.segments()).extracting(TranscriptSegment::endMs).containsExactly(12_000L, 18_000L);
    }

    @Test
    void shouldRejectOnlyFullyEmptyTimedWord() {
        List<TranscriptEvidenceSegmenter.TimedWord> words = List.of(
            word("甲", 0, 8_000, ""), word("", 8_000, 16_000, "")
        );

        assertThat(segmenter.refineWithDiagnostics(new TranscriptSegment(0, 16_000, "甲"), words, 49).fallbackReason())
            .isEqualTo(TranscriptEvidenceSegmenter.FallbackReason.INVALID_WORD_FIELD);
    }

    private TranscriptEvidenceSegmenter.TimedWord word(String text, long beginMs, long endMs, String punctuation) {
        return new TranscriptEvidenceSegmenter.TimedWord(text, punctuation, beginMs, endMs);
    }

    private TranscriptSegment sentence(List<TranscriptEvidenceSegmenter.TimedWord> words) {
        String text = words.stream().map(word -> word.text() + word.punctuation()).reduce("", String::concat);
        return new TranscriptSegment(words.getFirst().beginMs(), words.getLast().endMs(), text);
    }
}
