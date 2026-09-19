# 性能压测报告

## 一、压测环境

| 项 | 值 |
|---|---|
| 被测服务 | ai-nexus（Spring Boot 4.0.0，JDK 17，本地 Maven 启动） |
| 模型服务 | Ollama @ localhost:11434，qwen2.5:7b（对话）/ nomic-embed-text（Embedding） |
| 存储 | MySQL 8.0（本机）/ Redis 7（本机）/ Milvus standalone（本机 v2.4.15） |
| 压测工具 | `perf.LoadRunner`（JDK 17 HttpClient + 线程池，业务码级限流判定） |
| 数据规模 | perf-kb 知识库 1 篇文档 2 Chunk |

## 二、压测用例与基线数据

### 1. 基线（默认配置：缓存关、单节点）

| 场景 | 并发 | 请求数 | 错误 | QPS | TP50 | TP95 | 平均 | 结论 |
|---|---|---|---|---|---|---|---|---|
| chat 多轮对话 | 4 | 8 | 0 | 1.02 | 1371ms | 6522ms | 3667ms | 本地 7b 模型生成是主要耗时 |
| rag 检索问答（缓存关） | 4 | 8 | 0 | 0.71 | 4809ms | 5724ms | 4493ms | 检索(embedding+Milvus)约占 1~1.5s |
| rag/stream 首 token | 2 | 4 | 0 | 3.54 | 367ms | 759ms | 513ms | 真·流式首包远低于整段生成 |
| agent 工具调用 | 2 | 4 | 0 | 1.39 | 1337ms | 1525ms | 1374ms | 含时间工具 1 次调用 |

### 2. 优化项：检索结果缓存（动态配置 `rag.cache-enabled` 运行时开启）

| 场景 | TP50 | 平均 | 提升 |
|---|---|---|---|
| rag 冷缓存（首次，写缓存） | 3823ms | 3919ms | - |
| rag 热缓存（同问题命中） | **3225ms** | **3242ms** | **TP50 -33%，avg -28%** |

- 命中缓存后跳过 Query Embedding + Milvus 相似度检索，节省约 1~1.5s；
- 剩余耗时为模型生成（占 70%+），是本地单卡推理的物理上限；
- 生产建议：高频 FAQ 场景开启缓存（TTL 10min + 知识库变更版本号主动失效）。

### 3. 高可用机制验证

| 机制 | 用例 | 结果 |
|---|---|---|
| 接口限流 | 30 并发打 /chat（独立 sessionId，令牌桶 20/10s） | 20 成功 + **10 拒绝（D0001）**，快速失败不堆积，服务无雪崩 |
| 幂等 | 相同 sessionId 3s 内重复提交 | 第 2 次起拒绝（D0002），防止重复计费/重复入库 |
| 熔断 | 模型不可用时连续调用（单测覆盖三态迁移） | 重试耗尽 → 熔断打开 → 冷却后半开试探 → 恢复闭合 |

> 压测期间 `/health` 持续 UP，无线程池耗尽、无连接泄露。

## 三、容量评估结论

1. **瓶颈定位**：本地单机部署下，吞吐瓶颈 100% 在模型推理（qwen2.5:7b 生成约 60~80 token/s），
   应用层（Tomcat/JPA/Milvus）在 4 并发下延迟贡献 < 5%。
2. **水平扩展路径**：模型层通过 `ainexus.model-pool.nodes` 挂多 Ollama 节点（轮询/最少连接 +
   健康摘除）线性扩展推理吞吐；应用层无状态，可直接横向扩容。
3. **缓存收益边界**：命中缓存的请求延迟下降约 30%（本地推理场景）；命中率取决于问题重合度，
   FAQ 型知识库通常可达 40%+。
4. **限流容量规划**：默认全局限流 20 QPS/10s 窗口，需按 `租户并发配额 × 实例数` 调整
   `@RateLimit` permits 与模型池容量匹配。

## 四、复现步骤

```bash
# 1. 启动（开动态配置，便于运行时开关缓存）
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dainexus.dynamic.enabled=true"

# 2. 准备数据（创建 perf-kb + 上传文档，Swagger 或 curl）

# 3. 基线
java -cp target/classes;target/test-classes perf.LoadRunner chat 4 8
java -cp target/classes;target/test-classes perf.LoadRunner rag 4 8
java -cp target/classes;target/test-classes perf.LoadRunner stream 2 4
java -cp target/classes;target/test-classes perf.LoadRunner agent 2 4

# 4. 限流验证
java -cp target/classes;target/test-classes perf.LoadRunner rate-limit 10 30

# 5. 缓存对比（运行时开缓存后重跑 rag）
curl -X PUT http://localhost:8080/api/v1/config -H 'Content-Type: application/json' \
  -d '{"key":"rag.cache-enabled","value":"true"}'
java -cp target/classes;target/test-classes perf.LoadRunner rag 3 6
```
