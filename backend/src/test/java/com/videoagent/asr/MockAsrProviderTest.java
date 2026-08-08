package com.videoagent.asr;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

class MockAsrProviderTest {

    @TempDir
    private Path tempDirectory;

    @Test
    void shouldReturnDeterministicOrderedTimestampSegments() throws Exception {
        Path audio = tempDirectory.resolve("audio.wav");
        Files.write(audio, new byte[] {1, 2, 3, 4});
        MockAsrProvider provider = new MockAsrProvider();

        TranscriptionResult first = provider.transcribe(new AudioSource(audio));
        TranscriptionResult second = provider.transcribe(new AudioSource(audio));

        assertThat(first).isEqualTo(second);
        assertThat(first.segments()).hasSize(3);
        assertThat(first.segments()).extracting(TranscriptSegment::startMs)
            .containsExactly(0L, 2_000L, 4_000L);
        assertThat(first.segments()).extracting(TranscriptSegment::endMs)
            .containsExactly(2_000L, 4_000L, 6_000L);
        assertThat(first.segments()).allSatisfy(segment ->
            assertThat(segment.text()).isNotBlank()
        );
    }
}
