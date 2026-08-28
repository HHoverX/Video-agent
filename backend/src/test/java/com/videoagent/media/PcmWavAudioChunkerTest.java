package com.videoagent.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videoagent.asr.AudioSource;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

class PcmWavAudioChunkerTest {

    @TempDir
    Path tempDirectory;

    private final PcmWavAudioChunker chunker = new PcmWavAudioChunker();

    @Test
    void shouldReuseShortWavWithoutCreatingOwnedChunkFiles() throws Exception {
        Path audio = createAnalysisWav("short.wav", 125);
        long sourceSize = Files.size(audio);

        try (AudioChunkSet chunks = chunker.chunk(new AudioSource(audio), sourceSize)) {
            assertThat(chunks.chunks()).containsExactly(new AudioChunk(audio, 0, 0, 125));
            assertThat(chunks.generatedDirectory()).isEmpty();
            assertThat(chunks.startMs(chunks.chunks().getFirst())).isZero();
        }

        assertThat(audio).exists();
        assertThat(tempDirectory.resolve("asr-chunks-")).doesNotExist();
        try (var paths = Files.list(tempDirectory)) {
            assertThat(paths.toList()).containsExactly(audio);
        }
    }

    @Test
    void shouldSplitLongWavIntoReadableFrameCompleteChunksWithExactRemainder() throws Exception {
        Path audio = createAnalysisWav("long.wav", 125);
        long maxWavBytes = 100;
        List<AudioChunk> recordedChunks;
        Path generatedDirectory;

        try (AudioChunkSet chunks = chunker.chunk(new AudioSource(audio), maxWavBytes)) {
            recordedChunks = chunks.chunks();
            generatedDirectory = chunks.generatedDirectory().orElseThrow();
            ByteArrayOutputStream reconstructed = new ByteArrayOutputStream();

            assertThat(recordedChunks).hasSize(8);
            assertThat(recordedChunks.getFirst().startFrame()).isZero();
            assertThat(recordedChunks.getLast().frameCount()).isEqualTo(13);
            assertThat(chunks.startMs(recordedChunks.get(1))).isEqualTo(1L);

            long coveredFrames = 0;
            for (int index = 0; index < recordedChunks.size(); index++) {
                AudioChunk chunk = recordedChunks.get(index);
                assertThat(chunk.index()).isEqualTo(index);
                assertThat(chunk.startFrame()).isEqualTo(coveredFrames);
                assertThat(Files.size(chunk.file())).isLessThanOrEqualTo(maxWavBytes);
                assertThat(frameLength(chunk.file())).isEqualTo(chunk.frameCount());
                reconstructed.writeBytes(pcmBytes(chunk.file()));
                coveredFrames += chunk.frameCount();
            }
            assertThat(coveredFrames).isEqualTo(125L);
            assertThat(reconstructed.toByteArray()).isEqualTo(pcmBytes(audio));
            assertThat(generatedDirectory).exists();
        }

        assertThat(generatedDirectory).doesNotExist();
        assertThat(audio).exists();
    }

    @Test
    void shouldCleanGeneratedChunksWhenCallerFailsAfterChunking() throws Exception {
        Path audio = createAnalysisWav("exception.wav", 125);
        AtomicReference<Path> generatedDirectory = new AtomicReference<>();

        assertThatThrownBy(() -> {
            try (AudioChunkSet chunks = chunker.chunk(new AudioSource(audio), 100)) {
                generatedDirectory.set(chunks.generatedDirectory().orElseThrow());
                assertThat(generatedDirectory.get()).exists();
                throw new IllegalStateException("simulated caller failure");
            }
        }).isInstanceOf(IllegalStateException.class);

        assertThat(generatedDirectory.get()).doesNotExist();
        try (var paths = Files.list(tempDirectory)) {
            assertThat(paths.toList()).containsExactly(audio);
        }
    }

    @Test
    void shouldRejectUnsupportedWavFormatAndInsufficientBudget() throws Exception {
        Path unsupported = createWav(
            "unsupported.wav",
            new AudioFormat(8_000, 8, 1, false, false),
            100
        );
        Path analysis = createAnalysisWav("budget.wav", 125);

        assertThatThrownBy(() -> chunker.chunk(new AudioSource(unsupported), 100))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.MEDIA_TEMP_FILE_ERROR)
            ).hasMessageContaining("仅支持");
        assertThatThrownBy(() -> chunker.chunk(new AudioSource(analysis), 44))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.MEDIA_TEMP_FILE_ERROR)
            ).hasMessageContaining("字节预算不足");
    }

    @Test
    void shouldRemainInsideWorkspaceForFinalRecursiveCleanup() throws Exception {
        Path root = tempDirectory.resolve("workspace-root");
        TemporaryMediaWorkspace workspaceFactory = new TemporaryMediaWorkspace(new MediaProperties(
            "ffmpeg", "ffprobe", Duration.ofSeconds(30), root, 4_000
        ));
        Path chunkDirectory;

        try (MediaWorkspace workspace = workspaceFactory.create(42L)) {
            createAnalysisWav(workspace.audioFile(), 125);
            AudioChunkSet chunks = chunker.chunk(new AudioSource(workspace.audioFile()), 100);
            chunkDirectory = chunks.generatedDirectory().orElseThrow();
            assertThat(chunkDirectory).startsWith(workspace.directory());
            // Intentionally do not close the set: workspace cleanup is the final safety net.
        }

        assertThat(chunkDirectory).doesNotExist();
        try (var remaining = Files.list(root)) {
            assertThat(remaining).isEmpty();
        }
    }

    private Path createAnalysisWav(String filename, long frames) throws Exception {
        return createWav(tempDirectory.resolve(filename), 16_000, 16, 1, true, false, frames);
    }

    private Path createWav(String filename, AudioFormat format, long frames) throws Exception {
        return createWav(tempDirectory.resolve(filename), format.getSampleRate(), format.getSampleSizeInBits(),
            format.getChannels(), format.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED), format.isBigEndian(), frames);
    }

    private void createAnalysisWav(Path path, long frames) throws Exception {
        createWav(path, 16_000, 16, 1, true, false, frames);
    }

    private Path createWav(
        Path path,
        float sampleRate,
        int sampleSizeInBits,
        int channels,
        boolean signed,
        boolean bigEndian,
        long frames
    ) throws Exception {
        AudioFormat format = new AudioFormat(sampleRate, sampleSizeInBits, channels, signed, bigEndian);
        byte[] data = new byte[Math.toIntExact(frames * format.getFrameSize())];
        for (int index = 0; index < data.length; index++) {
            data[index] = (byte) index;
        }
        try (AudioInputStream stream = new AudioInputStream(
            new ByteArrayInputStream(data), format, frames
        )) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile());
        }
        return path;
    }

    private long frameLength(Path file) throws Exception {
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(file.toFile())) {
            return stream.getFrameLength();
        }
    }

    private byte[] pcmBytes(Path file) throws Exception {
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(file.toFile())) {
            return stream.readAllBytes();
        }
    }
}
