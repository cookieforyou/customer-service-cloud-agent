package com.enterprise.cs.commons.constant;

/**
 * 平台字符串错误码（《02》§7：只增不改；HTTP 映射见 cs-api GlobalExceptionHandler，对齐知识服务映射惯例）。
 */
public final class ErrorCodes {

    public static final String BAD_REQUEST = "BAD_REQUEST";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String SESSION_NOT_FOUND = "SESSION_NOT_FOUND";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String TURN_IN_PROGRESS = "TURN_IN_PROGRESS";
    public static final String IDENTITY_INCOMPLETE = "IDENTITY_INCOMPLETE";
    public static final String APP_KEY_NOT_FOUND = "APP_KEY_NOT_FOUND";
    public static final String INVALID_SIGN = "INVALID_SIGN";
    public static final String SIGN_EXPIRED = "SIGN_EXPIRED";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private ErrorCodes() {
    }
}
