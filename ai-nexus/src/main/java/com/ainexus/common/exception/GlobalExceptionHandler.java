package com.ainexus.common.exception;

import com.ainexus.common.api.ApiResponse;
import com.ainexus.common.api.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器：所有异常统一收敛为 {@link ApiResponse}，避免堆栈泄露给前端。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：按错误码返回，仅打 WARN 级日志。 */
    @ExceptionHandler(BizException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleBiz(BizException e) {
        log.warn("[biz-error] code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        return ApiResponse.fail(e.getErrorCode().getCode(), e.getMessage());
    }

    /** @Valid 请求体校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleValid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("[param-error] {}", detail);
        return ApiResponse.fail(ErrorCode.PARAM_ERROR.getCode(), detail);
    }

    /** 约束校验失败（非请求体）。 */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleConstraint(ConstraintViolationException e) {
        log.warn("[param-error] {}", e.getMessage());
        return ApiResponse.fail(ErrorCode.PARAM_ERROR);
    }

    /** 上传超限。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleUpload(MaxUploadSizeExceededException e) {
        return ApiResponse.fail(ErrorCode.PARAM_ERROR.getCode(), "上传文件过大");
    }

    /** 404 静态/路由未命中。 */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleNotFound(NoResourceFoundException e) {
        return ApiResponse.fail(ErrorCode.NOT_FOUND);
    }

    /** 兜底：未预期异常，打全量堆栈。 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleUnknown(Exception e) {
        log.error("[system-error] unexpected exception", e);
        return ApiResponse.fail(ErrorCode.SYSTEM_ERROR);
    }
}
