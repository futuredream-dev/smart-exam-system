package com.smartexam.exception;

import com.smartexam.common.ApiResponse;
import com.smartexam.common.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将常见异常转换为统一 JSON，避免把堆栈或内部实现细节直接返回给前端。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDatabaseConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.failure("数据存在关联或约束冲突，请刷新后重试", ErrorCode.CONFLICT.name()));
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidPath() {
        return badRequest("请求参数格式不正确", ErrorCode.BAD_REQUEST);
    }

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        return ResponseEntity.status(exception.getHttpStatus())
                .body(ApiResponse.failure(exception.getMessage(), exception.getErrorCode().name()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception) {
        FieldError firstError = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .orElse(null);
        String message = firstError == null
                ? "请求参数校验失败"
                : firstError.getField() + ": " + firstError.getDefaultMessage();
        return badRequest(message, ErrorCode.VALIDATION_ERROR);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
            ConstraintViolationException exception) {
        return badRequest(exception.getMessage(), ErrorCode.VALIDATION_ERROR);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableMessage() {
        return badRequest("请求 JSON 格式不正确", ErrorCode.BAD_REQUEST);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.failure("无权执行该操作", ErrorCode.FORBIDDEN.name()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        log.error("未处理的服务器异常", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure("服务器内部错误", ErrorCode.INTERNAL_SERVER_ERROR.name()));
    }

    private ResponseEntity<ApiResponse<Void>> badRequest(String message, ErrorCode errorCode) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure(message, errorCode.name()));
    }
}
