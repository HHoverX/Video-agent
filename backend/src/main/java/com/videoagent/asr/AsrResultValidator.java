package com.videoagent.asr;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import org.springframework.stereotype.Component;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.util.List;

@Component
public class AsrResultValidator {

    private static final long AUDIO_DURATION_TOLERANCE_MS = 1_500;

    public TranscriptionResult validate(AudioSource audioSource, List<TranscriptSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            throw invalid("ASR 未返回字幕片段");
        }

        long audioDurationMs = audioDurationMs(audioSource);
        long previousStart = -1;
        long previousEnd = -1;
        for (TranscriptSegment segment : segments) {
            if (segment == null
                || segment.text().isBlank()
                || segment.startMs() < 0
                || segment.endMs() <= segment.startMs()
                || segment.startMs() < previousStart
                || segment.endMs() < previousEnd
                || segment.endMs() > audioDurationMs + AUDIO_DURATION_TOLERANCE_MS) {
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

    private VideoAgentException invalid(String message) {
        return new VideoAgentException(ErrorCode.ASR_RESPONSE_INVALID, message);
    }
}
