package com.ainexus.service;

import com.ainexus.ai.route.ModelRouteContext;
import com.ainexus.ai.route.ModelScene;
import com.ainexus.ai.stats.AiCallEvent;
import com.ainexus.ai.stats.AiCallEventPublisher;
import com.ainexus.common.api.ErrorCode;
import com.ainexus.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.multipart.MultipartFile;
/**
 * 多模态服务：图片理解（图像 + 文本混合输入）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultimodalService {

    private final ModelRouteContext routeContext;
    private final AiCallEventPublisher eventPublisher;
    private final com.ainexus.ai.security.ContentSafetyGuard contentSafetyGuard;

    /**
     * 图片理解：路由到多模态模型（如 llava:7b / qwen2.5vl），以 Media 形式混合输入。
     */
    public String understandImage(String question, MultipartFile image) {
        validateImage(image);
        // 内容安全：随图文本同样进入模型提示词，必须与对话链路共用同一入口守卫
        contentSafetyGuard.checkInput(question);
        try {
            byte[] bytes = image.getBytes();
            String contentType = image.getContentType() == null ? "image/png" : image.getContentType();
            ChatModel chatModel = routeContext.route(ModelScene.MULTIMODAL);

            long start = System.currentTimeMillis();
            // 多模态消息：文本部分 + 图片 Media 部分
            UserMessage userMessage = UserMessage.builder()
                    .text(question == null || question.isBlank() ? "请详细描述这张图片的内容。" : question)
                    .media(Media.builder()
                            .mimeType(MimeTypeUtils.parseMimeType(contentType))
                            .data(new org.springframework.core.io.ByteArrayResource(bytes))
                            .build())
                    .build();
            ChatResponse response = chatModel.call(new Prompt(userMessage));
            String answer = response.getResult().getOutput().getText();

            eventPublisher.publish(AiCallEvent.builder()
                    .scene(ModelScene.MULTIMODAL.name())
                    .modelName(chatModel.getClass().getSimpleName())
                    .success(true)
                    .costMs(System.currentTimeMillis() - start)
                    .build());
            return answer;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[multimodal] image understanding failed", e);
            throw new BizException(ErrorCode.MODEL_CALL_ERROR, "图片理解失败: " + e.getMessage());
        }
    }

    private void validateImage(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new BizException(ErrorCode.MULTIMODAL_INPUT_ERROR, "图片不能为空");
        }
        String type = image.getContentType();
        // 前置判空后 requireNonNull 冗余，直接使用 type
        if (type == null || !type.startsWith("image/")) {
            throw new BizException(ErrorCode.MULTIMODAL_INPUT_ERROR, "仅支持图片类型文件");
        }
    }
}
