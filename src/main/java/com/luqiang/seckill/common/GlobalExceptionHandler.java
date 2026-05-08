package com.luqiang.seckill.common;

import jakarta.validation.ConstraintViolationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ConstraintViolationException.class)
    public ApiResponse<Void> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getMessage();
        if (message != null && message.contains(":")) {
            message = message.substring(message.lastIndexOf(':') + 1).trim();
        }
        return ApiResponse.fail(-400, message == null || message.isBlank() ? "请求参数不合法" : message);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldError() == null
                ? "请求参数不合法"
                : ex.getBindingResult().getFieldError().getDefaultMessage();
        return ApiResponse.fail(-400, message);
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception ex) {
        return ApiResponse.fail(-500, "服务器内部错误: " + ex.getClass().getSimpleName());
    }
}
