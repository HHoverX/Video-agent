package com.videoagent.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

class VideoFileValidatorTest {

    private static final byte[] MP4_BYTES = {
        0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm',
        0, 0, 0, 0, 'i', 's', 'o', 'm', 'm', 'p', '4', '2'
    };

    @Test
    void shouldAcceptMp4AndDeriveTitle() {
        VideoFileValidator validator = new VideoFileValidator(
            uploadProperties(DataSize.ofMegabytes(1))
        );
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "demo.mp4",
            "video/mp4",
            MP4_BYTES
        );

        ValidatedVideoFile result = validator.validate(file, null);

        assertThat(result.title()).isEqualTo("demo");
        assertThat(result.originalFilename()).isEqualTo("demo.mp4");
        assertThat(result.contentType()).isEqualTo("video/mp4");
        assertThat(result.size()).isEqualTo(MP4_BYTES.length);
    }

    @Test
    void shouldRejectUnsupportedContent() {
        VideoFileValidator validator = new VideoFileValidator(
            uploadProperties(DataSize.ofMegabytes(1))
        );
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "demo.mp4",
            "video/mp4",
            "not-an-mp4".getBytes()
        );

        assertThatThrownBy(() -> validator.validate(file, null))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.VIDEO_FORMAT_NOT_SUPPORTED)
            );
    }

    @Test
    void shouldRejectFileOverConfiguredLimit() {
        VideoFileValidator validator = new VideoFileValidator(
            uploadProperties(DataSize.ofBytes(10))
        );
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "demo.mp4",
            "video/mp4",
            MP4_BYTES
        );

        assertThatThrownBy(() -> validator.validate(file, null))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.VIDEO_FILE_TOO_LARGE)
            );
    }

    private VideoUploadProperties uploadProperties(DataSize maxFileSize) {
        return new VideoUploadProperties(
            maxFileSize,
            DataSize.ofMegabytes(16),
            DataSize.ofMegabytes(5),
            DataSize.ofMegabytes(128),
            10_000,
            java.time.Duration.ofHours(24),
            java.time.Duration.ofMinutes(15),
            3
        );
    }
}
