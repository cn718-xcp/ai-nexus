package com.ainexus.common.api;

import lombok.Getter;

/**
 * 统一错误码枚举。
 * 规范：A-系统通用 / B-AI 模型 / C-知识库 / D-限流幂等。
 */
@Getter
public enum ErrorCode {

    /** 成功 */
    SUCCESS("A0000", "成功"),
    /** 系统内部错误 */
    SYSTEM_ERROR("A0001", "系统繁忙，请稍后再试"),
    /** 参数错误 */
    PARAM_ERROR("A0002", "请求参数不合法"),
    /** 资源不存在 */
    NOT_FOUND("A0003", "资源不存在"),
    /** 通用业务异常 */
    BIZ_ERROR("A0004", "业务处理失败"),

    /** 模型调用失败 */
    MODEL_CALL_ERROR("B0001", "模型调用失败"),
    /** 模型响应超时 */
    MODEL_TIMEOUT("B0002", "模型响应超时"),
    /** 模型不支持该能力 */
    MODEL_CAPABILITY_NOT_SUPPORTED("B0003", "当前模型不支持该能力"),
    /** 多模态输入不合法 */
    MULTIMODAL_INPUT_ERROR("B0004", "多模态输入不合法"),

    /** 知识库不存在 */
    KB_NOT_FOUND("C0001", "知识库不存在"),
    /** 知识库文档为空 */
    KB_EMPTY("C0002", "知识库暂无可用文档"),
    /** 文档解析失败 */
    DOC_PARSE_ERROR("C0003", "文档解析失败"),

    /** 触发限流 */
    RATE_LIMIT_EXCEEDED("D0001", "请求过于频繁，请稍后再试"),
    /** 重复请求（幂等命中） */
    DUPLICATE_REQUEST("D0002", "请勿重复提交请求");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }
}
