package com.ainexus.ai.model;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
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
     * @return 命中返回上次结果；未命中返回 null 并占位（执行完成后调用 write 回写）
     */
    public String getAndLock(String promptHash) {
        if (dedupeTtlSeconds <= 0) {
            return null;
        }
        RBucket<String> bucket = redissonClient.getBucket("ainexus:model:prompt:" + promptHash);
        String cached = bucket.get();
        if (cached != null) {
            log.info("[prompt-cache] hit, hash={}", promptHash);
        } else {
            // 占位防止同 Prompt 并发重复推理（短锁，完成后由 write 覆盖）
            bucket.set("", Duration.ofSeconds(Math.min(dedupeTtlSeconds, 5)));
        }
        return cached;
    }

    public void write(String promptHash, String answer) {
        if (dedupeTtlSeconds <= 0 || answer == null || answer.isBlank()) {
            return;
        }
        redissonClient.getBucket("ainexus:model:prompt:" + promptHash)
                .set(answer, Duration.ofSeconds(dedupeTtlSeconds));
    }
}
