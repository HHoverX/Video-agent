package com.videoagent.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.videoagent.common.exception.ErrorCode;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class ProviderHttpFailureTest {

    @Test
    void shouldClassifyTransientAndDeterministicStatuses() {
        assertThat(ProviderHttpFailure.isRetryableStatus(429)).isTrue();
        assertThat(ProviderHttpFailure.isRetryableStatus(503)).isTrue();
        assertThat(ProviderHttpFailure.isRetryableStatus(401)).isFalse();
        assertThat(ProviderHttpFailure.isRetryableStatus(400)).isFalse();

        var transientFailure = ProviderHttpFailure.forStatus(
            429, "17", "ASR", "transcribe", ErrorCode.ASR_REQUEST_FAILED, ErrorCode.ASR_PROVIDER_REJECTED
        );
        assertThat(transientFailure.errorCode()).isEqualTo(ErrorCode.ASR_REQUEST_FAILED);
        assertThat(transientFailure.retryAfter()).isEqualTo(Duration.ofSeconds(17));

        var authenticationFailure = ProviderHttpFailure.forStatus(
            401, "17", "ASR", "transcribe", ErrorCode.ASR_REQUEST_FAILED, ErrorCode.ASR_PROVIDER_REJECTED
        );
        assertThat(authenticationFailure.errorCode()).isEqualTo(ErrorCode.ASR_PROVIDER_REJECTED);
        assertThat(authenticationFailure.retryAfter()).isNull();
    }
}
