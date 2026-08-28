package com.videoagent.media;

import com.videoagent.asr.AudioSource;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministically splits the PCM WAV emitted by the media pipeline. The
 * caller owns the byte budget; this class has no provider payload knowledge.
 */
public final class PcmWavAudioChunker {

    private static final float EXPECTED_FRAME_RATE = 16_000F;
    private static final int EXPECTED_SAMPLE_SIZE_BITS = 16;
    private static final int EXPECTED_CHANNELS = 1;

    public AudioChunkSet chunk(AudioSource source, long maxWavBytes) {
        Path input = safeInput(source);
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(input.toFile())) {
            AudioFormat format = requireAnalysisPcmFormat(stream.getFormat());
            long sourceFrames = stream.getFrameLength();
            if (sourceFrames <= 0) {
                throw invalid("ASR PCM WAV 不包含可分片的音频帧");
            }
            long sourceSize = Files.size(input);
            if (sourceSize <= maxWavBytes) {
                return new AudioChunkSet(
                    List.of(new AudioChunk(input, 0, 0, sourceFrames)),
                    (int) format.getFrameRate(),
                    null
                );
            }

            Path chunkDirectory = Files.createTempDirectory(input.getParent(), "asr-chunks-");
            try {
                long generatedHeaderBytes = generatedWavHeaderBytes(chunkDirectory, format);
                long framesPerChunk = chunkCapacityFrames(maxWavBytes, generatedHeaderBytes, format);
                return writeChunks(stream, format, sourceFrames, framesPerChunk, maxWavBytes, chunkDirectory);
            } catch (RuntimeException | IOException exception) {
                deleteDirectory(chunkDirectory);
                if (exception instanceof VideoAgentException videoAgentException) {
                    throw videoAgentException;
                }
                throw new VideoAgentException(
                    ErrorCode.MEDIA_TEMP_FILE_ERROR,
                    "ASR PCM WAV 分片失败",
                    exception
                );
            }
        } catch (UnsupportedAudioFileException exception) {
            throw invalid("ASR 音频分片仅支持 PCM WAV 输入");
        } catch (IOException exception) {
            throw new VideoAgentException(
                ErrorCode.MEDIA_TEMP_FILE_ERROR,
                "ASR PCM WAV 无法读取",
                exception
            );
        }
    }

    private AudioChunkSet writeChunks(
        AudioInputStream source,
        AudioFormat format,
        long sourceFrames,
        long framesPerChunk,
        long maxWavBytes,
        Path chunkDirectory
    ) throws IOException {
        List<AudioChunk> chunks = new ArrayList<>();
        long startFrame = 0;
        int index = 0;
        while (startFrame < sourceFrames) {
            long frameCount = Math.min(framesPerChunk, sourceFrames - startFrame);
            Path output = chunkDirectory.resolve(String.format(Locale.ROOT, "chunk-%04d.wav", index));
            AudioInputStream chunkStream = new AudioInputStream(source, format, frameCount);
            AudioSystem.write(chunkStream, AudioFileFormat.Type.WAVE, output.toFile());

            long outputSize = Files.size(output);
            if (outputSize > maxWavBytes) {
                throw invalid("ASR PCM WAV 分片超过调用方字节预算");
            }
            chunks.add(new AudioChunk(output, index, startFrame, frameCount));
            startFrame = Math.addExact(startFrame, frameCount);
            index++;
        }
        return new AudioChunkSet(chunks, (int) format.getFrameRate(), chunkDirectory);
    }

    private long generatedWavHeaderBytes(Path chunkDirectory, AudioFormat format) throws IOException {
        Path probe = chunkDirectory.resolve("header-probe.wav");
        byte[] oneFrame = new byte[format.getFrameSize()];
        try (AudioInputStream probeStream = new AudioInputStream(
            new ByteArrayInputStream(oneFrame), format, 1
        )) {
            AudioSystem.write(probeStream, AudioFileFormat.Type.WAVE, probe.toFile());
            long headerBytes = Files.size(probe) - format.getFrameSize();
            if (headerBytes < 0) {
                throw invalid("ASR PCM WAV 分片无法确定文件头大小");
            }
            return headerBytes;
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    private long chunkCapacityFrames(long maxWavBytes, long headerBytes, AudioFormat format) {
        long payloadBytes = maxWavBytes - headerBytes;
        if (maxWavBytes <= 0 || payloadBytes < format.getFrameSize()) {
            throw invalid("ASR PCM WAV 分片字节预算不足以容纳一个完整音频帧");
        }
        long capacity = payloadBytes / format.getFrameSize();
        int frameRate = (int) format.getFrameRate();
        if (frameRate % 1_000 == 0) {
            long framesPerMillisecond = frameRate / 1_000L;
            if (capacity >= framesPerMillisecond) {
                capacity -= capacity % framesPerMillisecond;
            }
        }
        if (capacity <= 0) {
            throw invalid("ASR PCM WAV 分片字节预算不足以容纳一个完整音频帧");
        }
        return capacity;
    }

    private AudioFormat requireAnalysisPcmFormat(AudioFormat format) {
        if (!AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding())
            || Float.compare(format.getSampleRate(), EXPECTED_FRAME_RATE) != 0
            || Float.compare(format.getFrameRate(), EXPECTED_FRAME_RATE) != 0
            || format.getSampleSizeInBits() != EXPECTED_SAMPLE_SIZE_BITS
            || format.getChannels() != EXPECTED_CHANNELS
            || format.getFrameSize() != 2
            || format.isBigEndian()) {
            throw invalid("ASR 音频分片仅支持 16kHz 单声道 16-bit little-endian PCM WAV");
        }
        return format;
    }

    private Path safeInput(AudioSource source) {
        if (source == null || source.file() == null) {
            throw invalid("ASR PCM WAV 输入为空");
        }
        Path input = source.file().toAbsolutePath().normalize();
        if (!Files.isRegularFile(input) || Files.isSymbolicLink(input) || input.getParent() == null) {
            throw invalid("ASR PCM WAV 输入无效");
        }
        return input;
    }

    private void deleteDirectory(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // The enclosing media workspace remains the final cleanup safety net.
        }
    }

    private VideoAgentException invalid(String message) {
        return new VideoAgentException(ErrorCode.MEDIA_TEMP_FILE_ERROR, message);
    }
}
