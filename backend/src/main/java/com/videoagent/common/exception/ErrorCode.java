package com.videoagent.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "请求参数不合法"),
    VIDEO_NOT_FOUND(HttpStatus.NOT_FOUND, "视频不存在"),
    VIDEO_FORMAT_NOT_SUPPORTED(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "仅支持 MP4 视频"),
    VIDEO_FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "视频文件超过大小限制"),
    VIDEO_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "视频上传失败"),
    STORAGE_ERROR(HttpStatus.BAD_GATEWAY, "对象存储操作失败"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
