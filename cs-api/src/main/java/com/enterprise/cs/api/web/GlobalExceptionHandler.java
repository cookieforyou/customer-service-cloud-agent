package com.enterprise.cs.api.web;

import com.enterprise.cs.commons.api.dto.ApiResponse;
import com.enterprise.cs.commons.constant.ErrorCodes;
import com.enterprise.cs.commons.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常映射（《02》§7：错误码字符串只增不改；HTTP 映射对齐知识服务惯例）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final Map<String, HttpStatus> STATUS = Map.of(
            ErrorCodes.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS,
            ErrorCodes.SESSION_NOT_FOUND, HttpStatus.NOT_FOUND,
            ErrorCodes.FORBIDDEN, HttpStatus.FORBIDDEN,
            ErrorCodes.UNAUTHORIZED, HttpStatus.UNAUTHORIZED,
            ErrorCodes.IDENTITY_INCOMPLETE, HttpStatus.UNAUTHORIZED,
            ErrorCodes.APP_KEY_NOT_FOUND, HttpStatus.UNAUTHORIZED,
            ErrorCodes.INVALID_SIGN, HttpStatus.UNAUTHORIZED,
            ErrorCodes.SIGN_EXPIRED, HttpStatus.UNAUTHORIZED);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> business(BusinessException e) {
        HttpStatus status = STATUS.getOrDefault(e.getCode(), HttpStatus.BAD_REQUEST);
        return ResponseEntity.status(status).body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception e) {
        log.error("unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCodes.INTERNAL_ERROR, "服务内部错误"));
    }
}
