package com.luqiang.seckill.common;

public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(1, message, data);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
