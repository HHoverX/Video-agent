package com.videoagent.asr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

class AsrResultValidatorTest {

    @TempDir
    Path tempDirectory;

    private final AsrResultValidator validator = new AsrResultValidator();
    private final Logger logger = (Logger) LoggerFactory.getLogger(AsrResultValidator.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Level originalLevel = logger.getLevel();

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        logger.setLevel(originalLevel);
    }

    @Test
    void shouldKeepValidSegmentsUnchanged() throws Exception {
        List<TranscriptSegment> segments = List.of(
            new TranscriptSegment(0, 500, "first"),
            new TranscriptSegment(500, 1_000, "second")
        );

        TranscriptionResult result = validator.validate(new AudioSource(createWav("valid.wav", 1)), segments);

        assertThat(result.segments()).isEqualTo(segments);
    }

    @Test
    void shouldLogStructuralReasonForReachableValidationFailuresWithoutTranscriptContent() throws Exception {
        assertRejection(
            Arrays.asList((TranscriptSegment) null),
            "NULL_SEGMENT",
            "segmentIndex=0",
            "textLength=null"
        );
        assertRejection(
            List.of(new TranscriptSegment(1_000, 2_000, "private transcript"),
                new TranscriptSegment(500, 2_500, "private transcript")),
            "START_REGRESSION",
            "segmentIndex=1",
            "previousStartMs=1000"
        );
        assertRejection(
            List.of(new TranscriptSegment(1_000, 2_000, "private transcript"),
                new TranscriptSegment(1_500, 1_900, "private transcript")),
            "END_REGRESSION",
            "segmentIndex=1",
            "previousEndMs=2000"
        );
        assertRejection(
            List.of(new TranscriptSegment(0, 3_000, "private transcript")),
            "EXCEEDS_AUDIO_DURATION",
            "segmentIndex=0",
            "audioDurationMs=1000"
        );
    }

    private void assertRejection(
        List<TranscriptSegment> segments,
        String reason,
        String... expectedFields
    ) throws Exception {
        appender.list.clear();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        appender.start();

        assertThatThrownBy(() -> validator.validate(new AudioSource(createWav("invalid.wav", 1)), segments))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.ASR_RESPONSE_INVALID)
            ).hasMessage("ASR 字幕片段时间或文本无效");

        String message = appender.list.getLast().getFormattedMessage();
        assertThat(message).contains("validationReason=" + reason).doesNotContain("private transcript");
        for (String expectedField : expectedFields) {
            assertThat(message).contains(expectedField);
        }
    }

    private Path createWav(String filename, int seconds) throws Exception {
        int sampleRate = 16_000;
        byte[] pcm = new byte[sampleRate * seconds * 2];
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        Path output = tempDirectory.resolve(filename);
        try (AudioInputStream stream = new AudioInputStream(
            new ByteArrayInputStream(pcm), format, sampleRate * seconds
        )) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, output.toFile());
        }
        return output;
    }
}
