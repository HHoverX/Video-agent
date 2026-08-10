package com.videoagent.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "请求参数不合法"),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "请求参数校验失败"),
    USERNAME_ALREADY_EXISTS(HttpStatus.CONFLICT, "用户名已存在"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "用户名或密码错误"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "请先登录"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    VIDEO_NOT_FOUND(HttpStatus.NOT_FOUND, "视频不存在"),
    VIDEO_FORMAT_NOT_SUPPORTED(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "仅支持 MP4 视频"),
    VIDEO_FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "视频文件超过大小限制"),
    VIDEO_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "视频上传失败"),
    VIDEO_ANALYSIS_IN_PROGRESS(HttpStatus.CONFLICT, "视频正在分析中，暂时无法删除"),
    ANALYSIS_ALREADY_RUNNING(HttpStatus.CONFLICT, "该视频当前分析版本已有任务"),
    ANALYSIS_NOT_FOUND(HttpStatus.NOT_FOUND, "分析任务不存在"),
    ANALYSIS_DISPATCH_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "分析任务投递失败"),
    MEDIA_TEMP_FILE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "临时媒体文件处理失败"),
    FFMPEG_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "FFmpeg 提取音频超时"),
    FFMPEG_EXECUTION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "FFmpeg 提取音频失败"),
    FFMPEG_OUTPUT_MISSING(HttpStatus.UNPROCESSABLE_ENTITY, "FFmpeg 未生成音频"),
    VIDEO_AUDIO_STREAM_NOT_FOUND(HttpStatus.UNPROCESSABLE_ENTITY, "该视频不包含可用于语音转写的音轨"),
    ASR_REQUEST_FAILED(HttpStatus.BAD_GATEWAY, "语音转写服务请求失败"),
    ASR_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "语音转写服务请求超时"),
    ASR_RESPONSE_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "语音转写服务返回无效结果"),
    ASR_INPUT_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "音频超过语音转写服务输入限制"),
    TRANSCRIPTION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "语音转录失败"),
    LLM_SUMMARY_FAILED(HttpStatus.BAD_GATEWAY, "LLM 结构化总结失败"),
    LLM_SUMMARY_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "LLM 总结结果不合法"),
    SUMMARY_PERSISTENCE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "视频总结保存失败"),
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
