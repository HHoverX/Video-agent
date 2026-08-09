package com.videoagent.media;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class FfmpegMediaProcessor implements MediaProcessor {

    private final MediaProperties properties;

    public FfmpegMediaProcessor(MediaProperties properties) {
        this.properties = properties;
    }

    @Override
    public AudioExtractResult extractAudio(Path videoFile, Path audioFile) {
        Path input = safeRegularInput(videoFile);
        Path output = safeOutput(input, audioFile);
        Path stderrFile = null;
        Process process = null;
        try {
            Files.deleteIfExists(output);
            stderrFile = Files.createTempFile(output.getParent(), "ffmpeg-", ".stderr");
            List<String> command = List.of(
                properties.ffmpegPath(),
                "-nostdin",
                "-hide_banner",
                "-y",
                "-i", input.toString(),
                "-map", "0:a:0",
                "-vn",
                "-ac", "1",
                "-ar", "16000",
                "-c:a", "pcm_s16le",
                output.toString()
            );
            process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(stderrFile.toFile())
                .start();

            boolean finished = process.waitFor(
                properties.ffmpegTimeout().toMillis(),
                TimeUnit.MILLISECONDS
            );
            if (!finished) {
                terminate(process);
                throw new VideoAgentException(
                    ErrorCode.FFMPEG_TIMEOUT,
                    "FFmpeg 提取音频超时；stderr=" + readStderr(stderrFile)
                );
            }

            String stderr = readStderr(stderrFile);
            if (process.exitValue() != 0) {
                if (isMissingAudioStream(stderr)) {
                    throw new VideoAgentException(
                        ErrorCode.VIDEO_AUDIO_STREAM_NOT_FOUND,
                        "该视频不包含可用于语音转写的音轨"
                    );
                }
                throw new VideoAgentException(
                    ErrorCode.FFMPEG_EXECUTION_FAILED,
                    "FFmpeg 退出码=" + process.exitValue() + "；stderr=" + stderr
                );
            }
            if (!Files.isRegularFile(output) || Files.size(output) == 0) {
                throw new VideoAgentException(
                    ErrorCode.FFMPEG_OUTPUT_MISSING,
                    "FFmpeg 未生成有效音频文件；stderr=" + stderr
                );
            }
            return new AudioExtractResult(output, Files.size(output));
        } catch (InterruptedException exception) {
            if (process != null) {
                terminate(process);
            }
            Thread.currentThread().interrupt();
            throw new VideoAgentException(
                ErrorCode.FFMPEG_EXECUTION_FAILED,
                "FFmpeg 执行线程被中断",
                exception
            );
        } catch (IOException exception) {
            throw new VideoAgentException(
                ErrorCode.FFMPEG_EXECUTION_FAILED,
                "FFmpeg 启动或文件操作失败",
                exception
            );
        } finally {
            if (stderrFile != null) {
                try {
                    Files.deleteIfExists(stderrFile);
                } catch (IOException ignored) {
                    // The enclosing task workspace performs a final recursive cleanup.
                }
            }
        }
    }

    private Path safeRegularInput(Path input) {
        Path normalized = input.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized) || Files.isSymbolicLink(normalized)) {
            throw new VideoAgentException(ErrorCode.FFMPEG_EXECUTION_FAILED, "FFmpeg 输入视频无效");
        }
        return normalized;
    }

    private Path safeOutput(Path input, Path output) {
        Path normalized = output.toAbsolutePath().normalize();
        if (normalized.equals(input)
            || normalized.getParent() == null
            || !normalized.getParent().equals(input.getParent())
            || Files.isSymbolicLink(normalized)) {
            throw new VideoAgentException(ErrorCode.FFMPEG_EXECUTION_FAILED, "FFmpeg 输出路径不安全");
        }
        return normalized;
    }

    private String readStderr(Path stderrFile) {
        try {
            String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8).strip();
            if (stderr.length() <= properties.stderrMaxChars()) {
                return stderr;
            }
            return stderr.substring(stderr.length() - properties.stderrMaxChars());
        } catch (IOException exception) {
            return "<stderr unreadable>";
        }
    }

    private boolean isMissingAudioStream(String stderr) {
        String normalized = stderr.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("matches no streams")
            || normalized.contains("does not contain any stream")
            || normalized.contains("contains no audio");
    }

    private void terminate(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
