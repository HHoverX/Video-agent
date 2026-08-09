package com.videoagent.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@EnabledIfEnvironmentVariable(named = "VIDEOAGENT_FFMPEG_TEST", matches = "true")
class FfmpegMediaProcessorTest {

    @TempDir
    private Path tempDirectory;

    @Test
    void shouldExtractMonoWavAudioWithRealFfmpeg() throws Exception {
        Path video = generateVideo("valid.mp4", 2);
        Path audio = tempDirectory.resolve("audio.wav");
        FfmpegMediaProcessor processor = processor(Duration.ofSeconds(20));

        AudioExtractResult result = processor.extractAudio(video, audio);

        assertThat(result.audioFile()).isEqualTo(audio.toAbsolutePath().normalize());
        assertThat(result.size()).isPositive();
        assertThat(audio).exists();
        assertThat(Files.readAllBytes(audio)).startsWith("RIFF".getBytes());
    }

    @Test
    void shouldExposeNonZeroExitAsFfmpegFailure() throws Exception {
        Path invalidVideo = tempDirectory.resolve("invalid.mp4");
        Files.writeString(invalidVideo, "not a media file");

        assertThatThrownBy(() -> processor(Duration.ofSeconds(20)).extractAudio(
            invalidVideo,
            tempDirectory.resolve("invalid.wav")
        )).isInstanceOfSatisfying(VideoAgentException.class, exception -> {
            assertThat(exception.errorCode()).isEqualTo(ErrorCode.FFMPEG_EXECUTION_FAILED);
            assertThat(exception.getMessage()).contains("FFmpeg 退出码", "stderr=");
        });
    }

    @Test
    void shouldClassifyVideoWithoutAudioStream() throws Exception {
        Path video = generateVideoWithoutAudio("silent.mp4", 1);

        assertThatThrownBy(() -> processor(Duration.ofSeconds(20)).extractAudio(
            video,
            tempDirectory.resolve("silent.wav")
        )).isInstanceOfSatisfying(VideoAgentException.class, exception -> {
            assertThat(exception.errorCode()).isEqualTo(ErrorCode.VIDEO_AUDIO_STREAM_NOT_FOUND);
            assertThat(exception.getMessage()).isEqualTo("该视频不包含可用于语音转写的音轨");
        });
    }

    @Test
    void shouldTerminateFfmpegWhenTimeoutExpires() throws Exception {
        Path video = generateVideo("timeout.mp4", 5);

        assertThatThrownBy(() -> processor(Duration.ofNanos(1)).extractAudio(
            video,
            tempDirectory.resolve("timeout.wav")
        )).isInstanceOfSatisfying(VideoAgentException.class, exception ->
            assertThat(exception.errorCode()).isEqualTo(ErrorCode.FFMPEG_TIMEOUT)
        );
    }

    private FfmpegMediaProcessor processor(Duration timeout) {
        String executable = System.getenv().getOrDefault("FFMPEG_PATH", "ffmpeg");
        return new FfmpegMediaProcessor(new MediaProperties(
            executable,
            timeout,
            tempDirectory,
            4_000
        ));
    }

    private Path generateVideo(String filename, int durationSeconds) throws Exception {
        Path output = tempDirectory.resolve(filename);
        String executable = System.getenv().getOrDefault("FFMPEG_PATH", "ffmpeg");
        Process process = new ProcessBuilder(
            executable,
            "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "color=c=blue:s=160x120:r=10",
            "-f", "lavfi", "-i", "sine=frequency=1000:sample_rate=16000",
            "-t", Integer.toString(durationSeconds),
            "-c:v", "mpeg4",
            "-c:a", "aac",
            "-shortest",
            output.toString()
        ).redirectErrorStream(true).start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        String outputText = new String(process.getInputStream().readAllBytes());
        assertThat(finished).as("FFmpeg fixture generation timed out").isTrue();
        assertThat(process.exitValue()).as(outputText).isZero();
        assertThat(output).exists();
        return output;
    }

    private Path generateVideoWithoutAudio(String filename, int durationSeconds) throws Exception {
        Path output = tempDirectory.resolve(filename);
        String executable = System.getenv().getOrDefault("FFMPEG_PATH", "ffmpeg");
        Process process = new ProcessBuilder(
            executable,
            "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "color=c=black:s=160x120:r=10",
            "-t", Integer.toString(durationSeconds),
            "-c:v", "mpeg4",
            output.toString()
        ).redirectErrorStream(true).start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        String outputText = new String(process.getInputStream().readAllBytes());
        assertThat(finished).as("FFmpeg fixture generation timed out").isTrue();
        assertThat(process.exitValue()).as(outputText).isZero();
        assertThat(output).exists();
        return output;
    }
}
