package com.smartexam.exception;

import com.smartexam.common.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 可预期的业务异常。业务层后续通过该异常向接口层传递 HTTP 状态和错误码。
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final ErrorCode errorCode;

    public BusinessException(HttpStatus httpStatus, ErrorCode errorCode, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
