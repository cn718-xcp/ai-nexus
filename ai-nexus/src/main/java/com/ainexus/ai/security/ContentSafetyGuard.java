package com.ainexus.ai.security;

import com.ainexus.common.api.ErrorCode;
import com.ainexus.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 内容安全统一入口：所有进入模型或回流给用户的 AI 文本都经此组件，避免各服务各自复制检测逻辑
 * 导致新入口漏接（Agent / 结构化输出 / 多模态曾绕过 ChatService 的检测，形成安全绕过面）。
 * 开关：ainexus.security.enabled=false（dev 默认）时注入检测与敏感词均直通。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentSafetyGuard {

    private final PromptInjectionDetector injectionDetector;
    private final SensitiveWordFilter sensitiveWordFilter;

    /** 输入检查：命中提示词注入或敏感词即拦截（security.enabled=false 直通） */
    public void checkInput(String text) {
        if (injectionDetector.detect(text).injected()) {
            throw new BizException(ErrorCode.SECURITY_BLOCKED, "输入包含不允许的内容");
        }
        if (sensitiveWordFilter.containsSensitive(text)) {
            throw new BizException(ErrorCode.SECURITY_BLOCKED, "输入包含敏感词");
        }
    }

    /** 输出掩码：敏感词替换为等长掩码（security.enabled=false 原样返回） */
    public String maskOutput(String text) {
        return sensitiveWordFilter.mask(text);
    }
}
