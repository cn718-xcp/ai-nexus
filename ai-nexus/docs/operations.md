# 运维监控手册

## 一、健康检查

```bash
# 聚合健康（含各组件明细：DB/Redis/Milvus/Ollama）
curl -s http://localhost:8080/actuator/health | jq .
```

| 组件 | 检查方式 | DOWN 常见原因 |
|---|---|---|
| db | Hikari 连接池探活 | MySQL 未启动/密码错误 |
| redis | Redisson ping | Redis 未启动/密码错误 |
| milvus | gRPC getVersion RPC | Milvus 未启动/端口不通 |
| ollama | HTTP /api/tags | Ollama 未启动/模型未拉取 |

## 二、监控指标（/actuator/prometheus）

| 指标 | 类型 | 说明 |
|---|---|---|
| `ainexus_kb_count` | Gauge | 知识库总量（建库/删库实时更新） |
| `ainexus_chat_requests_total` | Counter | 对话请求总数（配合 rate() 计算日活趋势） |
| `ainexus_rag_requests_total` | Counter | RAG 请求总数 |
| `ainexus_rag_hits_total` | Counter | RAG 检索命中（召回非空）次数；`hits/requests` = 命中率 |
| `ainexus_tool_calls_total` | Counter | Agent 工具调用总数 |
| `ainexus_tool_failures_total` | Counter | 工具调用失败数；`failures/calls` = 失败率 |
| `spring_ai_*` | - | Spring AI 内置模型观测（耗时/token） |

Prometheus 抓取配置示例：

```yaml
scrape_configs:
  - job_name: ai-nexus
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["ai-nexus-host:8080"]
```

## 三、日志排查指南

日志格式：`时间 [线程] 级别 [traceId] logger - 消息`

1. **按 traceId 串联全链路**：响应头 `X-Trace-Id` 返回本次请求追踪号，
   `grep <traceId> app.log` 即可串联入口→模型调用→向量库→DB 全链路日志。
2. **模型调用失败**：搜索 `[resilience]`（重试/熔断）、`[model-gate]`（排队超时）。
3. **熔断状态迁移**：搜索 `[circuit-breaker]`，观察 CLOSED→OPEN→HALF_OPEN 迁移与原因。
4. **向量入库失败**：搜索 `[kb] document ingested` 确认入库；InsertRequest 异常多为维度不符。
5. **审计日志**：`audit_log` 表记录 KB_CREATE/KB_DELETE/DOC_UPLOAD/LOGIN/REGISTER
   （操作人/时间/IP/结果），与业务表交叉核对可定位误操作。

## 四、常见故障处理

| 故障 | 定位 | 处理 |
|---|---|---|
| 全部请求 401（开启鉴权后） | JWT 密钥不一致 | 各实例 `JWT_SECRET` 保持一致 |
| 模型调用全部走降级 | 熔断 OPEN 或模型服务宕机 | `grep circuit-breaker`，确认 Ollama 状态后等冷却恢复或重启应用 |
| RAG 答案总是"未检索到" | embedding 模型缺失/维度不符 | `ollama pull nomic-embed-text`；核对 `embedding-dimension: 768` |
| 并发上传卡住 | 分布式锁被长事务持有 | `grep "ainexus:lock"`，必要时删除 Redis 锁 Key |
| 检索结果不更新 | 检索缓存未失效 | 上传/删除会自动 bump 版本；异常时可 `DEL ainexus:chat:context:ragver:<kb>` |
| 统计报表无数据 | 异步落库线程池打满 | `ai-call-log-` 前缀线程日志；确认 queueCapacity 与写入速率 |
