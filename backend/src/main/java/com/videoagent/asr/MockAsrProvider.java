package com.videoagent.asr;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public class MockAsrProvider implements AsrProvider {

    private static final List<TranscriptSegment> DETERMINISTIC_SEGMENTS = List.of(
        new TranscriptSegment(0, 2_000, "欢迎使用 VideoAgent。"),
        new TranscriptSegment(2_000, 4_000, "音频已经通过 FFmpeg 提取。"),
        new TranscriptSegment(4_000, 6_000, "这是 Mock ASR 生成的带时间戳字幕。")
    );

    @Override
    public TranscriptionResult transcribe(AudioSource audioSource) {
        try {
            if (!Files.isRegularFile(audioSource.file())
                || Files.isSymbolicLink(audioSource.file())
                || Files.size(audioSource.file()) == 0) {
                throw new VideoAgentException(ErrorCode.TRANSCRIPTION_FAILED, "Mock ASR 输入音频无效");
            }
            return new TranscriptionResult(DETERMINISTIC_SEGMENTS);
        } catch (IOException exception) {
            throw new VideoAgentException(
                ErrorCode.TRANSCRIPTION_FAILED,
                "Mock ASR 无法读取输入音频",
                exception
            );
        }
    }
}
