package com.videoagent.common.exception;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(VideoAgentException.class)
    public ResponseEntity<ApiErrorResponse> handleVideoAgentException(
        VideoAgentException exception,
        HttpServletRequest request
    ) {
        ErrorCode errorCode = exception.errorCode();
        return response(errorCode.httpStatus(), errorCode, exception.getMessage(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleMaxUploadSize(
        MaxUploadSizeExceededException exception,
        HttpServletRequest request
    ) {
        return response(
            ErrorCode.VIDEO_FILE_TOO_LARGE.httpStatus(),
            ErrorCode.VIDEO_FILE_TOO_LARGE,
            ErrorCode.VIDEO_FILE_TOO_LARGE.defaultMessage(),
            request
        );
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ResponseEntity<ApiErrorResponse> handleInvalidMultipart(
        Exception exception,
        HttpServletRequest request
    ) {
        return response(
            HttpStatus.BAD_REQUEST,
            ErrorCode.INVALID_REQUEST,
            "请提供 multipart 视频文件字段 file",
            request
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleArgumentTypeMismatch(
        MethodArgumentTypeMismatchException exception,
        HttpServletRequest request
    ) {
        return response(
            HttpStatus.BAD_REQUEST,
            ErrorCode.INVALID_REQUEST,
            "路径参数格式不正确",
            request
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
        Exception exception,
        HttpServletRequest request
    ) {
        log.error("Unhandled request failure: method={}, path={}", request.getMethod(), request.getRequestURI(), exception);
        return response(
            ErrorCode.INTERNAL_ERROR.httpStatus(),
            ErrorCode.INTERNAL_ERROR,
            ErrorCode.INTERNAL_ERROR.defaultMessage(),
            request
        );
    }

    private ResponseEntity<ApiErrorResponse> response(
        HttpStatus status,
        ErrorCode errorCode,
        String message,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(
            Instant.now(),
            status.value(),
            errorCode.name(),
            message,
            request.getRequestURI()
        ));
    }
}
