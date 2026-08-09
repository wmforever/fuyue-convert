package com.fuyue.formatconverter.web;

import com.fuyue.formatconverter.task.TaskNotFoundException;
import com.fuyue.formatconverter.task.TaskQueueFullException;
import com.fuyue.formatconverter.task.InsufficientStorageException;
import org.springframework.http.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(TaskNotFoundException.class)
    ResponseEntity<ApiError> notFound(TaskNotFoundException e) { return error(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", e.getMessage()); }

    @ExceptionHandler(TaskQueueFullException.class)
    ResponseEntity<ApiError> queueFull(TaskQueueFullException e) { return error(HttpStatus.TOO_MANY_REQUESTS, "TASK_QUEUE_FULL", e.getMessage()); }

    @ExceptionHandler(InsufficientStorageException.class)
    ResponseEntity<ApiError> insufficientStorage(InsufficientStorageException e) {
        return error(HttpStatus.INSUFFICIENT_STORAGE, "INSUFFICIENT_STORAGE", e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<ApiError> badRequest(RuntimeException e) { return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage()); }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> uploadTooLarge(MaxUploadSizeExceededException e) { return error(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_TOO_LARGE", "上传文件超过服务器限制"); }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> internal(Exception e) { return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务器处理失败"); }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message, Instant.now()));
    }
}
