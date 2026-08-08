package com.videoagent.common.exception;

public class VideoAgentException extends RuntimeException {

    private final ErrorCode errorCode;

    public VideoAgentException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public VideoAgentException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public VideoAgentException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
