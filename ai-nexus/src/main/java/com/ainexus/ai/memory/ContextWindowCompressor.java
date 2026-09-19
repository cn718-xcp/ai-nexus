package com.ainexus.ai.memory;

import com.ainexus.domain.entity.ChatMessageRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * 超长上下文压缩器：滑窗消息总长度超出预算时，从最早的历史开始裁剪，
 * 保证总长（字符估算，1 token ≈ 1.5 中文字符/4 英文字符，取保守混合系数）不超窗口。
 * 裁剪在组装提示词前执行，与滑窗条数限制正交（双重保障）。
 */
public final class ContextWindowCompressor {

    private ContextWindowCompressor() {
    }

    /**
     * @param messages      时间正序历史消息
     * @param maxTotalChars 上下文字符预算（含全部历史）
     * @return 裁剪后的消息（保留最近的；预算内原样返回）
     */
    public static List<ChatMessageRecord> compress(List<ChatMessageRecord> messages, int maxTotalChars) {
        if (messages == null || totalChars(messages) <= maxTotalChars) {
            return messages;
        }
        // 从最早的一条开始丢弃，直到满足预算（最近上下文完整保留）
        List<ChatMessageRecord> kept = new ArrayList<>(messages);
        while (!kept.isEmpty() && totalChars(kept) > maxTotalChars) {
            kept.remove(0);
        }
        return kept;
    }

    public static int totalChars(List<ChatMessageRecord> messages) {
        return messages.stream().mapToInt(m -> m.getContent() == null ? 0 : m.getContent().length()).sum();
    }
}
