package com.ainexus;

import com.ainexus.common.api.ApiResponse;
import com.ainexus.common.api.ErrorCode;
import com.ainexus.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 统一响应体与错误码规范测试。
 */
class ApiResponseTest {

    @Test
    void shouldWrapSuccess() {
        ApiResponse<String> resp = ApiResponse.ok("data");
        assertEquals(ErrorCode.SUCCESS.getCode(), resp.getCode());
        assertEquals("data", resp.getData());
    }

    @Test
    void shouldWrapBizError() {
        ApiResponse<Void> resp = ApiResponse.fail(ErrorCode.RATE_LIMIT_EXCEEDED);
        assertEquals("D0001", resp.getCode());
        assertEquals("请求过于频繁，请稍后再试", resp.getMessage());
    }

    @Test
    void bizExceptionShouldCarryErrorCode() {
        BizException e = new BizException(ErrorCode.MODEL_TIMEOUT, "10s 超时");
        assertEquals(ErrorCode.MODEL_TIMEOUT, e.getErrorCode());
        assertEquals("10s 超时", e.getMessage());
    }
}
