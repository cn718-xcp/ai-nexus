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
    DUPLICATE_REQUEST("D0002", "请勿重复提交请求"),

    /** 未认证：缺少或无效令牌 */
    UNAUTHORIZED("E0001", "未认证，请先登录"),
    /** 令牌过期 */
    TOKEN_EXPIRED("E0002", "令牌已过期，请刷新"),

    /** 租户不存在 */
    TENANT_NOT_FOUND("F0001", "租户不存在"),
    /** 租户已禁用 */
    TENANT_DISABLED("F0002", "租户已禁用，请联系管理员"),
    /** 知识库数量超出租户配额 */
    TENANT_KB_QUOTA_EXCEEDED("F0003", "知识库数量已达租户配额上限"),
    /** 每日模型调用限额用尽 */
    TENANT_DAILY_QUOTA_EXCEEDED("F0004", "今日模型调用额度已用尽，请明日再试"),
    /** 租户 QPS 超限 */
    TENANT_QPS_EXCEEDED("F0005", "租户调用频率超限，请稍后再试"),

    /** 配置项不存在 */
    CONFIG_NOT_FOUND("G0001", "配置项不存在"),
    /** 配置值不合法 */
    CONFIG_INVALID("G0002", "配置值不合法"),

    /** 模型节点不可用 */
    MODEL_NODE_UNAVAILABLE("H0001", "模型节点暂不可用，请稍后再试");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }
}
