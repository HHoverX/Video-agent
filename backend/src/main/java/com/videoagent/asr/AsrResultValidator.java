package com.videoagent.asr;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.util.List;

@Component
public class AsrResultValidator {

    private static final long AUDIO_DURATION_TOLERANCE_MS = 1_500;
    private static final Logger log = LoggerFactory.getLogger(AsrResultValidator.class);

    public TranscriptionResult validate(AudioSource audioSource, List<TranscriptSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            throw invalid("ASR 未返回字幕片段");
        }

        long audioDurationMs = audioDurationMs(audioSource);
        long previousStart = -1;
        long previousEnd = -1;
        for (int index = 0; index < segments.size(); index++) {
            TranscriptSegment segment = segments.get(index);
            ValidationReason reason = validationReason(segment, previousStart, previousEnd, audioDurationMs);
            if (reason != null) {
                log.debug("ASR segment validation rejected segmentIndex={} startMs={} endMs={} textLength={} previousStartMs={} previousEndMs={} audioDurationMs={} toleranceMs={} totalSegments={} validationReason={}",
                    index,
                    segment == null ? null : segment.startMs(),
                    segment == null ? null : segment.endMs(),
                    segment == null ? null : segment.text().length(),
                    previousStart,
                    previousEnd,
                    audioDurationMs,
                    AUDIO_DURATION_TOLERANCE_MS,
                    segments.size(),
                    reason);
                throw invalid("ASR 字幕片段时间或文本无效");
            }
            previousStart = segment.startMs();
            previousEnd = segment.endMs();
        }
        return new TranscriptionResult(segments);
    }

    private long audioDurationMs(AudioSource audioSource) {
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(audioSource.file().toFile())) {
            float frameRate = stream.getFormat().getFrameRate();
            long frameLength = stream.getFrameLength();
            if (!Float.isFinite(frameRate) || frameRate <= 0 || frameLength <= 0) {
                throw invalid("无法确定 ASR 输入音频时长");
            }
            return Math.round(frameLength * 1_000.0 / frameRate);
        } catch (UnsupportedAudioFileException | IOException exception) {
            throw invalid("无法验证 ASR 输入音频时长");
        }
    }

    private ValidationReason validationReason(
        TranscriptSegment segment,
        long previousStart,
        long previousEnd,
        long audioDurationMs
    ) {
        if (segment == null) {
            return ValidationReason.NULL_SEGMENT;
        }
        if (segment.text().isBlank()) {
            return ValidationReason.BLANK_TEXT;
        }
        if (segment.startMs() < 0) {
            return ValidationReason.NEGATIVE_START;
        }
        if (segment.endMs() <= segment.startMs()) {
            return ValidationReason.NON_POSITIVE_DURATION;
        }
        if (segment.startMs() < previousStart) {
            return ValidationReason.START_REGRESSION;
        }
        if (segment.endMs() < previousEnd) {
            return ValidationReason.END_REGRESSION;
        }
        if (segment.endMs() > audioDurationMs + AUDIO_DURATION_TOLERANCE_MS) {
            return ValidationReason.EXCEEDS_AUDIO_DURATION;
        }
        return null;
    }

    private VideoAgentException invalid(String message) {
        return new VideoAgentException(ErrorCode.ASR_RESPONSE_INVALID, message);
    }

    private enum ValidationReason {
        NULL_SEGMENT,
        BLANK_TEXT,
        NEGATIVE_START,
        NON_POSITIVE_DURATION,
        START_REGRESSION,
        END_REGRESSION,
        EXCEEDS_AUDIO_DURATION
    }
}
