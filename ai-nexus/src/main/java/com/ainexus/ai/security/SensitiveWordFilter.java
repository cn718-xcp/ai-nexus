package com.ainexus.ai.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 敏感词过滤器：命中词替换为等长掩码（***）。
 * 词库来源：yml 自定义列表（ainexus.security.sensitive-words），
 * security.enabled=false（dev 默认）时原样返回。
 */
@Slf4j
@Component
public class SensitiveWordFilter {

    private final List<String> words;
    private final boolean enabled;

    public SensitiveWordFilter(
            @Value("${ainexus.security.sensitive-words:}") List<String> words,
            @Value("${ainexus.security.enabled:false}") boolean enabled) {
        this.words = words == null ? List.of() : words.stream().filter(w -> w != null && !w.isBlank()).toList();
        this.enabled = enabled;
    }

    /** 输入检测：命中任一敏感词返回 true */
    public boolean containsSensitive(String text) {
        if (!enabled || text == null || text.isBlank()) {
            return false;
        }
        return words.stream().anyMatch(text::contains);
    }

    /** 输出掩码：命中词替换为等长 ***，保持文本长度与可读性 */
    public String mask(String text) {
        if (!enabled || text == null || text.isBlank()) {
            return text;
        }
        String masked = text;
        for (String word : words) {
            if (masked.contains(word)) {
                masked = masked.replace(word, "*".repeat(word.length()));
            }
        }
        return masked;
    }
}
