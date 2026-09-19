package com.ainexus.common.exception;

import com.ainexus.common.api.ErrorCode;
import lombok.Getter;

/**
 * 业务异常：携带统一错误码，由全局异常处理器转换为统一响应体。
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
