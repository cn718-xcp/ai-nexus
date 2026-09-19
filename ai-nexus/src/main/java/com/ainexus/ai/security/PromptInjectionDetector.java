package com.ainexus.ai.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 提示词注入检测：规则库匹配越狱/诱导攻击特征。
 * 内置规则（中英文）+ 自定义规则（yml 正则追加），命中即拦截。
 * security.enabled=false（dev 默认）时直通。
 */
@Slf4j
@Component
public class PromptInjectionDetector {

    /** 内置注入特征规则（大小写不敏感） */
    private static final List<Pattern> BUILTIN_PATTERNS = List.of(
            Pattern.compile("(?i)ignore\\s+(all\\s+)?(previous|prior|above)\\s+instructions"),
            Pattern.compile("(?i)disregard\\s+(all\\s+)?(previous|above)\\s+instructions"),
            Pattern.compile("(?i)you\\s+are\\s+now\\s+a"),
            Pattern.compile("(?i)reveal\\s+(your\\s+)?(system\\s+)?prompt"),
            Pattern.compile("(?i)print\\s+(your\\s+)?(system\\s+)?prompt"),
            Pattern.compile("(?i)(忽略|无视)(之前|以上|上面)?(所有)?(系统)?(指令|提示词|设定)"),
            Pattern.compile("(?i)(你现在是|假装你是|扮演)"),
            Pattern.compile("(?i)(泄露|输出|打印)(你的)?(系统提示词|初始指令)"),
            Pattern.compile("(?i)DAN\\s+mode"),
            Pattern.compile("(?i)developer\\s+mode"));

    private final List<String> customPatterns;

    public PromptInjectionDetector(
            @Value("${ainexus.security.injection-patterns:}") List<String> customPatterns) {
        this.customPatterns = customPatterns;
    }

    /** 检测结果：是否注入 + 命中规则描述 */
    public record Detection(boolean injected, String rule) {
    }

    public Detection detect(String input) {
        if (input == null || input.isBlank()) {
            return new Detection(false, null);
        }
        for (Pattern pattern : BUILTIN_PATTERNS) {
            if (pattern.matcher(input).find()) {
                log.warn("[security] prompt injection detected, builtin rule={}", pattern.pattern());
                return new Detection(true, pattern.pattern());
            }
        }
        for (String rule : customPatterns == null ? List.<String>of() : customPatterns) {
            try {
                if (Pattern.compile(rule, Pattern.CASE_INSENSITIVE).matcher(input).find()) {
                    log.warn("[security] prompt injection detected, custom rule={}", rule);
                    return new Detection(true, rule);
                }
            } catch (Exception ignored) {
                // 自定义正则非法时跳过，不影响主链路
            }
        }
        return new Detection(false, null);
    }
}
