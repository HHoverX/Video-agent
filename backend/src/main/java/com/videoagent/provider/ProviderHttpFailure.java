package com.videoagent.provider;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

/**
 * Maps an HTTP status code from an ASR/LLM provider to the correct business
 * error code so the retry classifier treats transient statuses (408/429/5xx)
 * as retryable and deterministic rejections (400/401/403/404) as non-retryable.
 */
public final class ProviderHttpFailure {

    private ProviderHttpFailure() {
    }

    /**
     * Returns a VideoAgentException whose ErrorCode is:
     * - {@code transientCode} for timeout-like transient statuses (408, 429, 5xx)
     * - {@code rejectedCode} for deterministic rejections (400, 401, 403, 404)
     * - {@code transientCode} for any other unexpected status.
     */
    public static VideoAgentException forStatus(
        int status,
        String providerName,
        String operation,
        ErrorCode transientCode,
        ErrorCode rejectedCode
    ) {
        if (isRetryableStatus(status)) {
            return new VideoAgentException(
                transientCode,
                providerName + " " + operation + " 返回 HTTP " + status
            );
        }
        return new VideoAgentException(
            rejectedCode,
            providerName + " " + operation + " 拒绝了请求 (HTTP " + status + ")"
        );
    }

    public static boolean isRetryableStatus(int status) {
        return status == 408 || status == 429 || status >= 500;
    }
}
