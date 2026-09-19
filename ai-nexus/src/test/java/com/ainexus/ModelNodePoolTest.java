package com.ainexus;

import com.ainexus.ai.modelpool.ModelNode;
import com.ainexus.ai.modelpool.ModelNodePool;
import com.ainexus.ai.modelpool.ModelNodeProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型节点池单元测试：直通模式、轮询/最少连接调度、故障摘除与恢复回流。
 */
class ModelNodePoolTest {

    private static final Function<String, Boolean> ALWAYS_UP = b -> true;
    private static final Function<String, Boolean> ALWAYS_DOWN = b -> false;

    private ModelNodeProperties props(boolean enabled, String strategy, String... urls) {
        ModelNodeProperties p = new ModelNodeProperties();
        p.setEnabled(enabled);
        p.setStrategy(strategy);
        for (int i = 0; i < urls.length; i++) {
            ModelNodeProperties.Node n = new ModelNodeProperties.Node();
            n.setName("node" + i);
            n.setBaseUrl(urls[i]);
            p.getNodes().add(n);
        }
        return p;
    }

    @Test
    void shouldBeUnavailableWhenDisabledOrEmpty() {
        assertFalse(new ModelNodePool(props(false, "round_robin", "http://a:11434"), ALWAYS_UP).isAvailable());
        assertTrue(new ModelNodePool(props(true, "round_robin", "http://a:11434", "http://b:11434"), ALWAYS_UP).isAvailable());
    }

    @Test
    void shouldRoundRobinAcrossHealthyNodes() {
        ModelNodePool pool = new ModelNodePool(props(true, "round_robin", "http://a", "http://b", "http://c"), ALWAYS_UP);
        // 轮询依次覆盖 a/b/c 并循环
        assertEquals("http://a", pool.acquire().getBaseUrl());
        assertEquals("http://b", pool.acquire().getBaseUrl());
        assertEquals("http://c", pool.acquire().getBaseUrl());
        assertEquals("http://a", pool.acquire().getBaseUrl());
    }

    @Test
    void shouldPickLeastConnectionsNode() {
        ModelNodePool pool = new ModelNodePool(props(true, "least_connections", "http://a", "http://b"), ALWAYS_UP);
        ModelNode a = pool.acquire();
        pool.begin(a);
        pool.begin(a);
        // a 已有 2 个连接：下一次调度应选择空闲的 b
        ModelNode b = pool.acquire();
        assertEquals("http://b", b.getBaseUrl());
        pool.end(a);
        pool.end(a);
        assertEquals("http://a", pool.acquire().getBaseUrl());
    }

    @Test
    void shouldRemoveUnhealthyNodeAndRecover() {
        ModelNodeProperties p = props(true, "round_robin", "http://a", "http://b");
        // 探测函数：a 可达、b 不可达
        ModelNodePool pool = new ModelNodePool(p, b -> b.equals("http://a"));
        pool.healthCheck();
        // b 被摘除：轮询只会在 a 上（两次 acquire 均 a）
        assertEquals("http://a", pool.acquire().getBaseUrl());
        assertEquals("http://a", pool.acquire().getBaseUrl());

        // b 恢复：健康检查后回流
        ModelNodePool recoverPool = new ModelNodePool(p, b -> true);
        recoverPool.healthCheck();
        List<String> bases = recoverPool.allNodes().stream().map(ModelNode::getBaseUrl).toList();
        assertTrue(bases.contains("http://b"));
    }

    @Test
    void shouldReturnNullWhenAllNodesDown() {
        ModelNodePool pool = new ModelNodePool(props(true, "round_robin", "http://a"), ALWAYS_DOWN);
        pool.healthCheck();
        assertNull(pool.acquire(), "全部节点不健康应返回 null 由调用方回退主节点");
    }
}
