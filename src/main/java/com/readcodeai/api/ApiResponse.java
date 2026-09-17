package com.readcodeai.api;

/** 统一返回体：{@code code=0} 表示成功，非 0 表示失败（HTTP 状态码同时会体现）。 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
