package com.enterprise.cs.commons.exception;

import com.enterprise.cs.commons.constant.ErrorCodes;

/**
 * 业务异常：code 为平台字符串错误码（《02》§7，只增不改）。
 */
public class BusinessException extends RuntimeException {

    private final String code;

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static BusinessException of(String code, String message) {
        return new BusinessException(code, message);
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(ErrorCodes.SESSION_NOT_FOUND, message);
    }

    public String getCode() {
        return code;
    }
}
