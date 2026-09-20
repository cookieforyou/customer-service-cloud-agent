package com.enterprise.cs.commons.api.dto;

/**
 * 统一响应信封（对齐知识服务 ApiResponse 形态：《02》§7）。
 * code：成功恒为 "200"；业务失败为字符串错误码（{@link com.enterprise.cs.commons.constant.ErrorCodes}，只增不改）。
 */
public record ApiResponse<T>(String code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("200", "success", data);
    }

    public static ApiResponse<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> error(String errorCode, String message) {
        return new ApiResponse<>(errorCode, message, null);
    }
}
