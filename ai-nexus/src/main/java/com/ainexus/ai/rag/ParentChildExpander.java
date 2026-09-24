package com.ainexus.ai.rag;

import com.ainexus.domain.entity.KnowledgeChunk;
import com.ainexus.domain.repository.KnowledgeChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 父子分块召回扩展器：召回命中的是子块（小粒度、语义聚焦），回答需要父块（大粒度、上下文完整）。
 * 按子块元数据 (doc_id, chunk_index=父块序号) 从备份表取回父块原文替换命中文档文本，
 * 保留子块召回分；备份缺失（历史数据）时回退子块原文，保证向后兼容。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParentChildExpander {

    private final KnowledgeChunkRepository chunkRepository;
    private final RagProperties properties;

    /** 是否启用扩展：父子分块策略下生效 */
    public boolean enabled() {
        return "parent_child".equalsIgnoreCase(properties.getChunkStrategy());
    }

    /** 扩展命中文档：携带 is_child 标记的文档文本替换为其父块原文 */
    public List<Document> expand(List<Document> hits) {
        if (!enabled() || hits.isEmpty()) {
            return hits;
        }
        List<Document> expanded = new java.util.ArrayList<>(hits.size());
        int expandedCount = 0;
        for (Document doc : hits) {
            Object isChild = doc.getMetadata().get("is_child");
            if (Boolean.TRUE.equals(isChild)) {
                String docId = String.valueOf(doc.getMetadata().get("doc_id"));
                int parentIdx = doc.getMetadata().get("chunk_index") instanceof Number n ? n.intValue() : -1;
                String parentText = chunkRepository.findByDocUuidAndChunkIdx(docId, parentIdx)
                        .map(KnowledgeChunk::getContent).orElse(null);
                if (parentText != null) {
                    Map<String, Object> meta = new HashMap<>(doc.getMetadata());
                    meta.put("parent_expanded", true);
                    // score 保留子块召回分（引用溯源仍指向子块位置）
                    expanded.add(new Document(doc.getId(), parentText, meta));
                    expandedCount++;
                    continue;
                }
                log.warn("[parent-child] parent not found, doc={}, idx={}, fallback to child text", docId, parentIdx);
            }
            expanded.add(doc);
        }
        log.info("[parent-child] expanded {}/{} hits to parent context", expandedCount, hits.size());
        return expanded;
    }
}
