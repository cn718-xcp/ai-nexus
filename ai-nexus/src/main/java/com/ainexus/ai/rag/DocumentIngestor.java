package com.ainexus.ai.rag;

import com.ainexus.ai.chunk.ParentChildChunker;
import com.ainexus.ai.chunk.SemanticChunker;
import com.ainexus.ai.chunk.TextChunker;
import com.ainexus.domain.entity.KnowledgeChunk;
import com.ainexus.domain.repository.KnowledgeChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文档统一入库器：分块策略解析（fixed/parent_child/semantic/auto）+ 切分 +
 * 溯源元数据（字符起止偏移、章节标题）生成 + 向量化 + Chunk 备份落库。
 * 策略可配置；auto 模式按文档长度自动升级为父子分块。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentIngestor {

    private final RagProperties properties;
    private final EmbeddingModel embeddingModel;
    private final VectorStoreHolder vectorStore;
    private final KnowledgeChunkRepository chunkRepository;

    /** 向量仓库持有器：避免与 KnowledgeBaseService 循环依赖 */
    @Component
    public static class VectorStoreHolder {
        private final org.springframework.ai.vectorstore.VectorStore store;

        public VectorStoreHolder(org.springframework.ai.vectorstore.VectorStore store) {
            this.store = store;
        }

        public void add(List<Document> docs) {
            store.add(docs);
        }
    }

    public record IngestResult(String docUuid, int chunkCount, String resolvedStrategy) {
    }

    /** 分块结果：文本 + 溯源元数据 */
    record Chunk(String id, String text, Map<String, Object> metadata) {
    }

    public IngestResult ingest(com.ainexus.domain.entity.KnowledgeBase kb, String fileName, String text) {
        String strategy = resolveStrategy(text);
        String docUuid = UUID.randomUUID().toString();
        List<Chunk> chunks = switch (strategy) {
            case "parent_child" -> ingestParentChild(kb, fileName, text, docUuid);
            case "semantic" -> ingestSemantic(kb, fileName, text, docUuid);
            default -> ingestFixed(kb, fileName, text, docUuid);
        };
        vectorStore.add(toDocuments(chunks));
        log.info("[ingest] kb={}, strategy={}, chunks={}", kb.getName(), strategy, chunks.size());
        return new IngestResult(docUuid, chunks.size(), strategy);
    }

    /** 策略解析：auto 模式下超长文档自动升级为父子分块 */
    private String resolveStrategy(String text) {
        String strategy = properties.getChunkStrategy();
        if ("auto".equalsIgnoreCase(strategy) && text.length() > properties.getAutoParentChildChars()) {
            return "parent_child";
        }
        return strategy;
    }

    /** 固定长度分块（原默认行为，新增偏移与章节元数据） */
    private List<Chunk> ingestFixed(com.ainexus.domain.entity.KnowledgeBase kb, String fileName, String text, String docUuid) {
        List<String> chunks = new TextChunker(800, 100).split(text);
        List<int[]> offsets = locateOffsets(text, chunks);
        List<Chunk> result = new ArrayList<>(chunks.size());
        List<KnowledgeChunk> backups = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            result.add(new Chunk(chunkId(docUuid, i), chunks.get(i),
                    metadata(kb, docUuid, fileName, i, offsets.get(i)[0], offsets.get(i)[1], sectionOf(text, offsets.get(i)[0], offsets.get(i)[1]))));
            backups.add(backup(kb, docUuid, i, chunks.get(i)));
        }
        chunkRepository.saveAll(backups);
        return result;
    }

    /** 语义分块：句级相似度断句，其余同固定分块 */
    private List<Chunk> ingestSemantic(com.ainexus.domain.entity.KnowledgeBase kb, String fileName, String text, String docUuid) {
        List<String> chunks = new SemanticChunker(embeddingModel, properties.getSemanticThreshold(),
                properties.getSemanticMaxChars()).split(text);
        List<int[]> offsets = locateOffsets(text, chunks);
        List<Chunk> result = new ArrayList<>(chunks.size());
        List<KnowledgeChunk> backups = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            result.add(new Chunk(chunkId(docUuid, i), chunks.get(i),
                    metadata(kb, docUuid, fileName, i, offsets.get(i)[0], offsets.get(i)[1], sectionOf(text, offsets.get(i)[0], offsets.get(i)[1]))));
            backups.add(backup(kb, docUuid, i, chunks.get(i)));
        }
        chunkRepository.saveAll(backups);
        return result;
    }

    /**
     * 父子分块：子块（小粒度）向量化用于召回；父块原文写入备份表，
     * 召回命中子块后按 (docUuid, chunk_index) 取回父块拼入提示词。
     */
    private List<Chunk> ingestParentChild(com.ainexus.domain.entity.KnowledgeBase kb, String fileName, String text, String docUuid) {
        ParentChildChunker chunker = new ParentChildChunker(properties.getParentSize(), properties.getChildSize(), 50);
        List<ParentChildChunker.ParentChild> parents = chunker.split(text);
        List<Chunk> result = new ArrayList<>();
        List<KnowledgeChunk> parentBackups = new ArrayList<>(parents.size());
        // 父块在原文中的起始偏移（滑动定位）
        int parentCursor = 0;
        for (ParentChildChunker.ParentChild parent : parents) {
            int parentStart = text.indexOf(parent.getParentText(), Math.max(0, parentCursor - 50));
            if (parentStart < 0) {
                parentStart = parentCursor;
            }
            parentCursor = parentStart + parent.getParentText().length();
            // 父块原文备份（召回扩展数据源）
            parentBackups.add(backup(kb, docUuid, parent.getParentIdx(), parent.getParentText()));
            for (ParentChildChunker.ParentChild.Child child : parent.getChildren()) {
                Map<String, Object> meta = metadata(kb, docUuid, fileName, parent.getParentIdx(),
                        parentStart + child.offsetInParent(),
                        parentStart + child.offsetInParent() + child.text().length(),
                        sectionOf(text, parentStart, parentStart + parent.getParentText().length()));
                meta.put("child_index", child.childIdx());
                meta.put("is_child", true);
                result.add(new Chunk(childId(docUuid, parent.getParentIdx(), child.childIdx()),
                        child.text(), meta));
            }
        }
        chunkRepository.saveAll(parentBackups);
        return result;
    }

    /**
     * 溯源元数据：知识库名/doc_id/文件名/父块序号/字符起止/章节 + 归属（user_id/tenant_id）。
     * 归属元数据是向量层租户隔离的依据：检索侧据此过滤，防止"全库检索"跨租户读数；
     * 值为 null 时不写入（鉴权/多租户关闭态产生的向量不带归属，行为与历史版本一致）。
     */
    public Map<String, Object> metadata(com.ainexus.domain.entity.KnowledgeBase kb, String docUuid,
                                        String fileName, int chunkIdx, int start, int end, String section) {
        Map<String, Object> meta = new java.util.HashMap<>();
        meta.put("knowledge_base_name", kb.getName());
        meta.put("doc_id", docUuid);
        meta.put("file_name", fileName);
        meta.put("chunk_index", chunkIdx);
        meta.put("char_start", start);
        meta.put("char_end", end);
        if (section != null) {
            meta.put("section", section);
        }
        if (kb.getUserId() != null) {
            meta.put("user_id", kb.getUserId());
        }
        if (kb.getTenantId() != null) {
            meta.put("tenant_id", kb.getTenantId());
        }
        return meta;
    }

    private KnowledgeChunk backup(com.ainexus.domain.entity.KnowledgeBase kb, String docUuid, int idx, String content) {
        return KnowledgeChunk.builder().kbId(kb.getId()).docUuid(docUuid).chunkIdx(idx).content(content).build();
    }

    private List<Document> toDocuments(List<Chunk> chunks) {
        return chunks.stream().map(c -> new Document(c.id(), c.text(), c.metadata())).toList();
    }

    private String chunkId(String docUuid, int idx) {
        return UUID.nameUUIDFromBytes((docUuid + ":" + idx).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String childId(String docUuid, int parentIdx, int childIdx) {
        return UUID.nameUUIDFromBytes((docUuid + ":" + parentIdx + ":" + childIdx).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** 在原文中顺序定位各分块的字符起止偏移（带 overlap 时从游标回退查找） */
    List<int[]> locateOffsets(String text, List<String> chunks) {
        List<int[]> offsets = new ArrayList<>(chunks.size());
        int cursor = 0;
        for (String chunk : chunks) {
            int start = text.indexOf(chunk, Math.max(0, cursor - 100));
            if (start < 0) {
                start = cursor;
            }
            offsets.add(new int[]{start, start + chunk.length()});
            cursor = start + chunk.length();
        }
        return offsets;
    }

    /** 章节标题定位：片段结束位置之前最近的标题行（含片段自身携带的标题，Markdown/# 或中文序号） */
    String sectionOf(String text, int start, int end) {
        String before = text.substring(0, Math.min(end, text.length()));
        String section = null;
        for (String line : before.split("\n")) {
            String t = line.trim();
            if ((t.startsWith("#") || t.matches("第[一二三四五六七八九十0-9]+[章节部分].*"))
                    && t.length() > 1 && t.length() <= 80) {
                section = t;
            }
        }
        return section;
    }
}
