package com.videoagent.common.exception;

import java.time.Duration;

public class VideoAgentException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Duration retryAfter;

    public VideoAgentException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public VideoAgentException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.retryAfter = null;
    }

    public VideoAgentException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryAfter = null;
    }

    public VideoAgentException(
        ErrorCode errorCode,
        String message,
        Throwable cause,
        Duration retryAfter
    ) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryAfter = retryAfter;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
