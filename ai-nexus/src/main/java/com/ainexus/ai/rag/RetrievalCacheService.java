package com.ainexus.ai.rag;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.ainexus.common.constant.RedisKeyConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 向量检索结果缓存：高频知识库的重复问题命中缓存后跳过向量库 IO。
 * - Key 携带知识库版本号：文档上传/删除时版本自增实现主动失效
 * - TTL 兜底：未被主动失效的缓存自然过期
 * `ainexus.rag.cache-enabled=false`（dev 默认）时直通，行为与优化前一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalCacheService {

    private static final Duration TTL = Duration.ofMinutes(10);

    private final RedissonClient redissonClient;
    private final com.ainexus.common.config.DynamicConfigService dynamicConfig;

    /** yml 静态默认值（动态配置优先级更高，可运行时开关用于压测对比/应急处置） */
    @Value("${ainexus.rag.cache-enabled:false}")
    private boolean cacheEnabled;

    /** 缓存是否生效：动态配置 rag.cache-enabled 优先，yml 默认兜底 */
    private boolean cacheEnabled() {
        return dynamicConfig.getBool("rag.cache-enabled", cacheEnabled, null);
    }

    /** 查询缓存：命中返回缓存文档，未命中返回 null */
    public List<Document> get(String kbName, String query, int topK, Long userId, Long tenantId) {
        if (!cacheEnabled()) {
            return null;
        }
        RBucket<String> bucket = redissonClient.getBucket(key(kbName, query, topK, userId, tenantId));
        String json = bucket.get();
        if (json == null) {
            return null;
        }
        log.info("[rag-cache] hit, kb={}, topK={}", kbName, topK);
        List<CachedDoc> docs;
        try {
            docs = JSONUtil.toList(json, CachedDoc.class);
        } catch (Exception e) {
            // 缓存数据损坏/结构不兼容：删除脏 Key 回源重建，绝不把缓存故障放大为检索主链路故障
            log.warn("[rag-cache] corrupt cache entry evicted, kb={}, err={}", kbName, e.getMessage());
            bucket.delete();
            return null;
        }
        // score 转存 metadata（Document 无带 score 的构造器），保证下游引用定位仍可读取
        return docs.stream().map(d -> {
            Map<String, Object> metadata = new java.util.HashMap<>(d.metadata() == null ? Map.of() : d.metadata());
            if (d.score() != null) {
                metadata.put("cached_score", d.score());
            }
            return new Document(d.id(), d.text(), metadata);
        }).toList();
    }

    /** 写缓存（失败不影响主链路） */
    public void put(String kbName, String query, int topK, List<Document> documents, Long userId, Long tenantId) {
        if (!cacheEnabled() || documents == null || documents.isEmpty()) {
            return;
        }
        try {
            List<CachedDoc> payload = documents.stream()
                    .map(d -> new CachedDoc(d.getId(), d.getText(), d.getMetadata(), d.getScore()))
                    .toList();
            redissonClient.getBucket(key(kbName, query, topK, userId, tenantId))
                    .set(JSONUtil.toJsonStr(payload), TTL);
        } catch (Exception e) {
            log.warn("[rag-cache] put failed: {}", e.getMessage());
        }
    }

    /**
     * 知识库更新（上传/删除文档）时版本自增，旧缓存 Key 全部失效。
     * 同时推进全局版本：kbName 为空的"全库检索"条目挂在全局版本下，
     * 任一知识库变更都必须使其失效，否则全库缓存最长滞后 TTL（10 分钟）。
     */
    public void bumpVersion(String kbName) {
        if (!cacheEnabled()) {
            return;
        }
        redissonClient.getBucket(RedisKeyConstants.CHAT_CONTEXT + "ragver:" + kbKeyPart(kbName))
                .set(String.valueOf(System.currentTimeMillis()));
        redissonClient.getBucket(RedisKeyConstants.CHAT_CONTEXT + "ragver:_global_")
                .set(String.valueOf(System.currentTimeMillis()));
    }

    /**
     * 缓存 Key 中的知识库维度：知识库名由用户创建（长度可控但可含分隔符/换行），
     * 直接拼接可构造出与其他缓存 Key 碰撞或逃逸命名空间的字符串，统一摘要化消除该风险。
     */
    private String kbKeyPart(String kbName) {
        return kbName == null || kbName.isBlank() ? "_all_" : DigestUtil.md5Hex(kbName);
    }

    /** 归属维度：全库检索条目必须按调用者隔离，否则 A 的全库缓存会回给 B（跨用户串数据） */
    private String ownerPart(Long userId, Long tenantId) {
        if (userId != null) {
            return "u" + userId;
        }
        if (tenantId != null) {
            return "t" + tenantId;
        }
        return "anon";
    }

    private String key(String kbName, String query, int topK, Long userId, Long tenantId) {
        String kbPart = kbKeyPart(kbName);
        // 版本号参与 Key：命名库按库版本失效；空 kbName 的全库条目挂全局版本（任一库变更即失效）
        String versionKey = RedisKeyConstants.CHAT_CONTEXT + "ragver:"
                + ("_all_".equals(kbPart) ? "_global_" : kbPart);
        String version = String.valueOf(redissonClient.getBucket(versionKey).get());
        // 语义归一化：去空白+小写，提升同义查询的缓存命中率
        String normalized = query == null ? "" : query.replaceAll("\\s+", "").toLowerCase();
        return RedisKeyConstants.CHAT_CONTEXT + "ragcache:" + version + ":"
                + kbPart + ":" + ownerPart(userId, tenantId) + ":" + topK + ":" + DigestUtil.md5Hex(normalized);
    }

    /** 缓存序列化载体 */
    record CachedDoc(String id, String text, Map<String, Object> metadata, Double score) {
    }
}
