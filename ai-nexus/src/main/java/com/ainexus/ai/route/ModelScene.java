package com.ainexus.ai.route;

import lombok.Getter;

/**
 * 模型调用场景：多模型路由的维度。
 */
@Getter
public enum ModelScene {

    /** 普通对话 */
    CHAT,
    /** RAG 检索增强问答 */
    RAG,
    /** Agent 工具调用 */
    AGENT,
    /** 多模态（图片理解） */
    MULTIMODAL;

    public static ModelScene of(String value) {
        if (value == null || value.isBlank()) {
            return CHAT;
        }
        try {
            return ModelScene.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return CHAT;
        }
    }
}
