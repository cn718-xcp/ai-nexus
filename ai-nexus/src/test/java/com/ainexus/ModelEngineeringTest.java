package com.ainexus;

import com.ainexus.ai.memory.ContextWindowCompressor;
import com.ainexus.ai.model.ModelInvoker;
import com.ainexus.ai.model.ModelRegistry;
import com.ainexus.ai.model.PromptCacheService;
import com.ainexus.domain.entity.ChatMessageRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 模型服务工程化单元测试：模型键解析、自动降级、灰度分流、上下文压缩、前缀缓存。
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ModelEngineeringTest {

    // ---------------- 模型键解析 ----------------

    @Test
    void shouldNormalizeModelKeys() {
        assertEquals("ollama:qwen2.5:7b", ModelRegistry.normalize("qwen2.5:7b"));
        assertEquals("openai:gpt-4o-mini", ModelRegistry.normalize("openai:gpt-4o-mini"));
        assertEquals("ollama", ModelRegistry.provider("ollama:qwen2.5:7b"));
        assertEquals("qwen2.5:7b", ModelRegistry.modelName("ollama:qwen2.5:7b"));
    }

    // ---------------- 自动降级与灰度 ----------------

    @Test
    void shouldFallbackToBackupModelOnInfrastructureFailure() {
        org.springframework.ai.chat.model.ChatModel failing = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        org.springframework.ai.chat.model.ChatModel backup = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        org.mockito.Mockito.when(failing.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("Connection refused: localhost:11434"));
        org.mockito.Mockito.when(backup.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new org.springframework.ai.chat.model.Generation(
                                new org.springframework.ai.chat.messages.AssistantMessage("from-backup")))));

        ModelInvoker invoker = new ModelInvoker(new ModelRegistry(null, "http://unused", "key") {
            @Override
            public org.springframework.ai.chat.model.ChatModel resolve(String modelKey) {
                return modelKey.equals("ollama:primary") ? failing : backup;
            }
        });

        String result = invoker.invoke("ollama:primary", List.of("ollama:backup"), null, 0,
                cm -> cm.call(new org.springframework.ai.chat.prompt.Prompt("t")).getResult().getOutput().getText());
        assertEquals("from-backup", result, "主模型连接失败应自动降级备用模型");
    }

    @Test
    void shouldNotFallbackOnBusinessFailure() {
        org.springframework.ai.chat.model.ChatModel failing = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        org.mockito.Mockito.when(failing.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new IllegalStateException("bad request params"));
        ModelInvoker invoker = new ModelInvoker(new ModelRegistry(null, "http://unused", "key") {
            @Override
            public org.springframework.ai.chat.model.ChatModel resolve(String modelKey) {
                return failing;
            }
        });
        // 业务类异常不触发模型切换（直接抛出）
        assertThrows(IllegalStateException.class, () -> invoker.invoke(
                "ollama:primary", List.of("ollama:backup"), null, 0,
                cm -> cm.call(new org.springframework.ai.chat.prompt.Prompt("t")).getResult().getOutput().getText()));
    }

    @Test
    void shouldRouteGrayTraffic() {
        org.springframework.ai.chat.model.ChatModel gray = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        org.mockito.Mockito.when(gray.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new org.springframework.ai.chat.model.Generation(
                                new org.springframework.ai.chat.messages.AssistantMessage("gray-answer")))));
        ModelInvoker invoker = new ModelInvoker(new ModelRegistry(null, "http://unused", "key") {
            @Override
            public org.springframework.ai.chat.model.ChatModel resolve(String modelKey) {
                return gray;
            }
        });
        // 灰度命中标志 true 时走灰度模型
        String result = invoker.invoke("ollama:primary", List.of(), "openai:gpt-4o-mini", true, null,
                cm -> cm.call(new org.springframework.ai.chat.prompt.Prompt("t")).getResult().getOutput().getText());
        assertEquals("gray-answer", result);
    }

    // ---------------- 上下文压缩 ----------------

    @Test
    void shouldCompressContextWithinBudget() {
        List<ChatMessageRecord> history = List.of(
                ChatMessageRecord.builder().role("user").content("a".repeat(300)).build(),
                ChatMessageRecord.builder().role("assistant").content("b".repeat(300)).build(),
                ChatMessageRecord.builder().role("user").content("c".repeat(300)).build());
        // 预算 700：应裁剪最早的 1~2 条保留最近消息
        List<ChatMessageRecord> compressed = ContextWindowCompressor.compress(history, 700);
        assertTrue(ContextWindowCompressor.totalChars(compressed) <= 700);
        // 预算充足时原样返回
        assertEquals(history, ContextWindowCompressor.compress(history, 5000));
    }

    // ---------------- 前缀缓存 ----------------

    @Mock
    private RedissonClient redissonClient;

    @Test
    void shouldPassThroughWhenDedupeDisabled(@Mock RBucket<String> bucket) {
        PromptCacheService cache = new PromptCacheService(redissonClient);
        try {
            var f = PromptCacheService.class.getDeclaredField("dedupeTtlSeconds");
            f.setAccessible(true);
            f.set(cache, 0);
        } catch (Exception ignored) {
        }
        org.mockito.Mockito.when(redissonClient.<String>getBucket(anyString())).thenReturn(bucket);
        org.mockito.Mockito.verifyNoInteractions(bucket);
        assertEquals(null, cache.getAndLock("hash"));
        // 关闭态不写缓存
        cache.write("hash", "answer");
        org.mockito.Mockito.verifyNoInteractions(bucket);
    }
}
