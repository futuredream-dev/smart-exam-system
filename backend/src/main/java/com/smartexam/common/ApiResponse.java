package com.smartexam.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 全项目统一 API 响应结构。
 *
 * @param <T> data 字段承载的数据类型
 */
public final class ApiResponse<T> {

    private final boolean success;
    private final String message;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String errorCode;

    private final T data;

    private ApiResponse(boolean success, String message, String errorCode, T data) {
        this.success = success;
        this.message = message;
        this.errorCode = errorCode;
        this.data = data;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "操作成功", null, data);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, message, null, data);
    }

    public static <T> ApiResponse<T> failure(String message, String errorCode) {
        return new ApiResponse<>(false, message, errorCode, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public T getData() {
        return data;
    }
}
