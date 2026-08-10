package com.videoagent.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import org.junit.jupiter.api.Test;

class FailureClassTest {

    @Test
    void shouldClassifyTransientProviderAndNetworkErrorsAsRetryable() {
        assertThat(FailureClass.of(new VideoAgentException(ErrorCode.ASR_TIMEOUT))).isEqualTo(FailureClass.RETRYABLE);
        assertThat(FailureClass.of(new VideoAgentException(ErrorCode.ASR_REQUEST_FAILED)))
            .isEqualTo(FailureClass.RETRYABLE);
        assertThat(FailureClass.of(new VideoAgentException(ErrorCode.LLM_SUMMARY_FAILED)))
            .isEqualTo(FailureClass.RETRYABLE);
        assertThat(FailureClass.of(new VideoAgentException(ErrorCode.STORAGE_ERROR)))
            .isEqualTo(FailureClass.RETRYABLE);
        assertThat(FailureClass.of(new VideoAgentException(ErrorCode.FFMPEG_TIMEOUT)))
            .isEqualTo(FailureClass.RETRYABLE);
        assertThat(FailureClass.of(new VideoAgentException(ErrorCode.TRANSCRIPTION_FAILED)))
            .isEqualTo(FailureClass.RETRYABLE);
        assertThat(FailureClass.of(new VideoAgentException(ErrorCode.ANALYSIS_DISPATCH_FAILED)))
            .isEqualTo(FailureClass.RETRYABLE);
        assertThat(FailureClass.of(new VideoAgentException(ErrorCode.SUMMARY_PERSISTENCE_FAILED)))
            .isEqualTo(FailureClass.RETRYABLE);
        assertThat(FailureClass.of(new VideoAgentException(ErrorCode.MEDIA_TEMP_FILE_ERROR)))
            .isEqualTo(FailureClass.RETRYABLE);
    }

    @Test
    void shouldClassifyDeterministicFailuresAsNonRetryable() {
        assertThat(FailureClass.of(new VideoAgentException(ErrorCode.VIDEO_AUDIO_STREAM_NOT_FOUND)))
            .isEqualTo(FailureClass.NON_RETRYABLE);
        assertThat(FailureClass.of(new VideoAgentException(ErrorCode.ASR_PROVIDER_REJECTED)))
            .isEqualTo(FailureClass.NON_RETRYABLE);
        assertThat(FailureClass.of(new VideoAgentException(ErrorCode.ASR_INPUT_TOO_LARGE)))
            .isEqualTo(FailureClass.NON_RETRYABLE);
        assertThat(FailureClass.of(new VideoAgentException(ErrorCode.ASR_RESPONSE_INVALID)))
            .isEqualTo(FailureClass.NON_RETRYABLE);
        assertThat(FailureClass.of(new VideoAgentException(ErrorCode.LLM_PROVIDER_REJECTED)))
            .isEqualTo(FailureClass.NON_RETRYABLE);
        assertThat(FailureClass.of(new VideoAgentException(ErrorCode.LLM_SUMMARY_INVALID)))
            .isEqualTo(FailureClass.NON_RETRYABLE);
        assertThat(FailureClass.of(new VideoAgentException(ErrorCode.FFMPEG_EXECUTION_FAILED)))
            .isEqualTo(FailureClass.NON_RETRYABLE);
        assertThat(FailureClass.of(new VideoAgentException(ErrorCode.FFMPEG_OUTPUT_MISSING)))
            .isEqualTo(FailureClass.NON_RETRYABLE);
        assertThat(FailureClass.of(new VideoAgentException(ErrorCode.INTERNAL_ANALYSIS_ERROR)))
            .isEqualTo(FailureClass.NON_RETRYABLE);
        assertThat(FailureClass.of(new VideoAgentException(ErrorCode.ANALYSIS_DISPATCH_EXHAUSTED)))
            .isEqualTo(FailureClass.NON_RETRYABLE);
        assertThat(FailureClass.of(new VideoAgentException(ErrorCode.ANALYSIS_RETRY_EXHAUSTED)))
            .isEqualTo(FailureClass.NON_RETRYABLE);
    }

    @Test
    void shouldTreatUnknownCodesAndProgrammingErrorsAsNonRetryable() {
        assertThat(FailureClass.of("UNKNOWN_ERROR_CODE")).isEqualTo(FailureClass.NON_RETRYABLE);
        assertThat(FailureClass.of((String) null)).isEqualTo(FailureClass.NON_RETRYABLE);
        assertThat(FailureClass.of("")).isEqualTo(FailureClass.NON_RETRYABLE);
    }
}
