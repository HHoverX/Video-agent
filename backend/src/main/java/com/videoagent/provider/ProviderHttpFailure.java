package com.videoagent.provider;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

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
        return forStatus(status, null, providerName, operation, transientCode, rejectedCode);
    }

    public static VideoAgentException forStatus(
        int status,
        String retryAfterHeader,
        String providerName,
        String operation,
        ErrorCode transientCode,
        ErrorCode rejectedCode
    ) {
        if (isRetryableStatus(status)) {
            return new VideoAgentException(
                transientCode,
                providerName + " " + operation + " 返回 HTTP " + status,
                null,
                parseRetryAfter(retryAfterHeader)
            );
        }
        return new VideoAgentException(
            rejectedCode,
            providerName + " " + operation + " 拒绝了请求 (HTTP " + status + ")"
        );
    }

    public static boolean isRetryableStatus(int status) {
        return status == 408 || status == 425 || status == 429 || status >= 500;
    }

    public static Duration parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value.strip());
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException ignored) {
            try {
                ZonedDateTime deadline = ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME);
                Duration duration = Duration.between(ZonedDateTime.now(deadline.getZone()), deadline);
                return duration.isNegative() ? Duration.ZERO : duration;
            } catch (RuntimeException invalidDate) {
                return null;
            }
        }
    }
}
