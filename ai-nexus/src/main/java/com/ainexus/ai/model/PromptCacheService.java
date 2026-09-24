package com.ainexus.ai.model;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 对话前缀缓存：相同提示词（system+history+user 全文）短 TTL 内直接回放上次结果，
 * 合并瞬时重复请求（重试风暴/前端重复提交），避免重复推理。
 * ainexus.model.dedupe-ttl-seconds=0（默认）时直通。
 */
@Slf4j
@Component
public class PromptCacheService {

    private final RedissonClient redissonClient;

    @Value("${ainexus.model.dedupe-ttl-seconds:0}")
    private int dedupeTtlSeconds;

    public PromptCacheService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * @return 命中返回上次结果；未命中返回 null 并写独立占位锁（执行完成后调用 write 回写）
     */
    public String getAndLock(String promptHash) {
        if (dedupeTtlSeconds <= 0) {
            return null;
        }
        String baseKey = "ainexus:model:prompt:" + promptHash;
        String cached = redissonClient.<String>getBucket(baseKey).get();
        if (cached != null && !cached.isBlank()) {
            log.info("[prompt-cache] hit, hash={}", promptHash);
            return cached;
        }
        // 占位锁写独立 Key：结果 Key 只在 write 后可见，避免空串占位被误判为命中返回空答案
        redissonClient.getBucket(baseKey + ":lock")
                .setIfAbsent("1", Duration.ofSeconds(Math.min(dedupeTtlSeconds, 5)));
        return null;
    }

    public void write(String promptHash, String answer) {
        if (dedupeTtlSeconds <= 0 || answer == null || answer.isBlank()) {
            return;
        }
        redissonClient.getBucket("ainexus:model:prompt:" + promptHash)
                .set(answer, Duration.ofSeconds(dedupeTtlSeconds));
    }
}
