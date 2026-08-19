package com.videoagent.rag.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.rag.config.RagProperties;
import com.videoagent.transcript.entity.VideoTranscriptSegmentEntity;

import org.junit.jupiter.api.Test;

import java.util.List;

class ContextStrategyResolverTest {

    private final RagProperties properties = new RagProperties(1000, 200, 1, 5, 0.0f);
    private final ContextStrategyResolver resolver = new ContextStrategyResolver(properties);

    @Test
    void shouldChooseDirectContextBelowThreshold() {
        List<VideoTranscriptSegmentEntity> segments = List.of(
            segment("a".repeat(300)),
            segment("b".repeat(300))
        );
        assertThat(resolver.resolveMode(segments)).isEqualTo(QaContextMode.DIRECT_CONTEXT);
    }

    @Test
    void shouldChooseDirectContextAtExactThreshold() {
        List<VideoTranscriptSegmentEntity> segments = List.of(
            segment("x".repeat(1000))
        );
        assertThat(resolver.resolveMode(segments)).isEqualTo(QaContextMode.DIRECT_CONTEXT);
    }

    @Test
    void shouldChooseRagAboveThreshold() {
        List<VideoTranscriptSegmentEntity> segments = List.of(
            segment("a".repeat(600)),
            segment("b".repeat(600))
        );
        assertThat(resolver.resolveMode(segments)).isEqualTo(QaContextMode.RAG);
    }

    @Test
    void shouldJudgeByTranscriptSizeNotVideoDuration() {
        // A short, low-density 30-minute video has a tiny transcript -> DIRECT.
        List<VideoTranscriptSegmentEntity> tinyTranscript = List.of(segment("短"));
        assertThat(resolver.resolveMode(tinyTranscript)).isEqualTo(QaContextMode.DIRECT_CONTEXT);

        // A dense 5-minute course can have a huge transcript -> RAG.
        List<VideoTranscriptSegmentEntity> hugeTranscript = List.of(
            segment("密集".repeat(3000))
        );
        assertThat(resolver.resolveMode(hugeTranscript)).isEqualTo(QaContextMode.RAG);
    }

    @Test
    void shouldRejectEmptyTranscript() {
        assertThatThrownBy(() -> resolver.requireNonEmpty(List.of()))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.TRANSCRIPTION_FAILED));
    }

    @Test
    void shouldCountOnlyNonNullText() {
        VideoTranscriptSegmentEntity a = segment("hello");
        VideoTranscriptSegmentEntity b = segment(null);
        assertThat(resolver.transcriptChars(List.of(a, b))).isEqualTo(5);
    }

    private VideoTranscriptSegmentEntity segment(String text) {
        VideoTranscriptSegmentEntity entity = new VideoTranscriptSegmentEntity();
        entity.setSegmentIndex(0);
        entity.setStartMs(0L);
        entity.setEndMs(1000L);
        entity.setText(text);
        return entity;
    }
}
