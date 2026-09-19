# AI Nexus — 基于 Spring Boot 4.0.0 + Spring AI 2.0.1 GA 的生产级 AI 应用平台

> 面向大厂简历的生产级 Java 后端项目：知识库管理、RAG 检索增强问答、多模型路由、Function Calling 工具调用、多模态图片理解、结构化输出、调用统计，以及限流 / 幂等 / 重试 / 降级 / 可观测等完整生产级规范。

## 一、技术栈

| 层次 | 技术 |
|---|---|
| 基础框架 | JDK 17 · Spring Boot 4.0.0 · Maven |
| AI 框架 | Spring AI 2.0.1 GA（ChatClient / Ollama / Milvus / Tool Calling / Structured Output / Observation） |
| 模型 | Ollama：`qwen2.5:7b`（对话/RAG/Agent）、`nomic-embed-text`（Embedding）、`llava:7b`（多模态） |
| 存储 | MySQL 8.0（元数据/对话历史/调用流水，JPA）· Milvus（向量存储）· Redis（Redisson：限流/幂等） |
| 工程化 | SpringDoc OpenAPI 3 · Lombok · Hutool · Validation · AOP · Actuator + Micrometer（Prometheus） |

## 二、架构说明

```
                          ┌────────────────────────────────────────────┐
                          │              Controller 层                 │
                          │  限流(RRateLimiter) / 幂等(SET NX) / 校验   │
                          └───────────────┬────────────────────────────┘
                                          │
        ┌─────────────────────────────────┼──────────────────────────────────┐
        │                     Service 层（AI 核心编排）                        │
        │  ┌──────────────┐ ┌───────────────────────┐ ┌────────────────────┐ │
        │  │ ChatService  │ │    RagChatService     │ │   AgentService     │ │
        │  │ 多轮上下文    │ │  模板方法 RAG 流水线   │ │ Function Calling   │ │
        │  └──────┬───────┘ └──────────┬────────────┘ └─────────┬──────────┘ │
        │         │        ┌───────────▼──────────┐             │            │
        │         │        │ AbstractRagPipeline  │             │            │
        │         │        │ 预处理→检索→组装→后置 │             │            │
        │         │        └───────────┬──────────┘             │            │
        │  ┌──────▼────────────────────▼────────────────────────▼──────────┐ │
        │  │     ModelRouteContext（策略模式多模型路由 + 工厂缓存）          │ │
        │  └──────────────────────────┬────────────────────────────────────┘ │
        │  ┌──────────────────────────▼────────────────────────────────────┐ │
        │  │  ModelCallResilienceTemplate：重试(指数退避) / 超时 / 降级      │ │
        │  └───────────────────────────────────────────────────────────────┘ │
        └─────────────────────────────────┬──────────────────────────────────┘
                                          │
   ┌──────────────┬──────────────┬────────┴───────┬──────────────────┐
   │   Milvus     │    MySQL     │     Redis      │  Ollama(模型)    │
   │  向量存储     │ 元数据/历史   │ 限流/幂等       │ qwen2.5/llava    │
   └──────────────┴──────────────┴────────────────┴──────────────────┘
        异步旁路：AiCallEvent → ai_call_log 落库 → /stats 统计；Micrometer → Prometheus
```

### 设计模式落点
- **策略模式**（多模型路由）：`ModelRouteStrategy` 接口 + `SceneModelRouteStrategy` 场景路由实现 + `ModelRouteContext` 上下文选择，路由规则由 `application.yml` 配置驱动。
- **模板方法**（RAG 流水线）：`AbstractRagPipeline#run` 固定骨架（预处理→向量检索→提示词组装→后置处理），子类可覆写各阶段。
- **工厂模式**（向量/模型客户端）：`SceneModelRouteStrategy` 按"同名模型复用"惰性构建并缓存 ChatModel；文档入库以确定性 ID 生成向量 Chunk。

### Spring AI 2.0.1 GA 特性体现
- **结构化输出**：`ChatClient.call().entity(Class)` / `ListOutputConverter`（`StructuredOutputService`）。
- **工具调用（Agent）**：`@Tool` 注解式工具 + `MethodToolCallbackProvider`，框架内置工具循环。
- **模型观测**：Micrometer Observation 贯穿模型调用，`/actuator/prometheus` 暴露指标，日志带 traceId/spanId。

### 生产级规范
- 统一返回体 `ApiResponse` + 错误码枚举 `ErrorCode`（A/B/C/D 段位）+ 全局异常处理 + JSR-303 参数校验。
- 接口限流：Redisson 令牌桶（`@RateLimit`，SpEL 维度 + IP 兜底）。
- 幂等控制：Redis SET NX 请求摘要抢占（`@Idempotent`）。
- 模型调用弹性：重试（指数退避）→ 降级（返回知识片段兜底）→ 全链路埋点。
- 可观测：Actuator 健康检查、Prometheus 指标、trace 日志格式。
- 多环境：dev / prod Profile，敏感配置环境变量注入。

## 三、启动步骤

### 1. 前置依赖
```bash
# Ollama 模型（embedding 模型为 RAG 必需）
ollama pull qwen2.5:7b
ollama pull nomic-embed-text
ollama pull llava:7b          # 多模态功能需要

# Milvus（standalone，host: localhost, port: 19530, root/milvus）
# Redis
# MySQL 8.0（schema.sql 幂等，启动时自动建库建表）
```

### 2. 修改数据库连接
编辑 `src/main/resources/application-dev.yml` 中的 MySQL 账号密码。

### 3. 构建 & 启动
```bash
mvn clean package -DskipTests
java -jar target/ai-nexus.jar --spring.profiles.active=dev
# 开发态：mvn spring-boot:run
```

启动后访问 Swagger：http://localhost:8080/swagger-ui.html

## 四、接口示例

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/v1/kb | 创建知识库 |
| POST | /api/v1/kb/{kbId}/documents | 上传文档（txt/md/json/csv）切分入库 |
| GET | /api/v1/kb | 知识库列表 |
| POST | /api/v1/rag/ask | RAG 问答（含引用片段） |
| POST | /api/v1/rag/stream | RAG 流式问答（SSE，检索后首 token 直出） |
| POST | /api/v1/chat | 多轮对话（限流+幂等） |
| POST | /api/v1/chat/extract | 结构化输出：信息抽取 |
| POST | /api/v1/agent/invoke | Agent 工具调用 |
| POST | /api/v1/agent/vision | 多模态图片理解 |
| GET | /api/v1/stats/overview | 调用统计总览 |

```bash
# 创建知识库
curl -X POST http://localhost:8080/api/v1/kb \
  -H 'Content-Type: application/json' -d '{"name":"产品手册","description":"产品使用文档"}'

# 上传文档（kbId=1）
curl -X POST http://localhost:8080/api/v1/kb/1/documents -F 'file=@./manual.txt'

# RAG 问答
curl -X POST http://localhost:8080/api/v1/rag/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"产品如何初始化？","knowledgeBaseName":"产品手册","topK":5}'

# Agent 工具调用（模型自主调用 getWeather / getCurrentTime）
curl -X POST http://localhost:8080/api/v1/agent/invoke \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"北京今天天气怎么样？","sessionId":"s-001"}'

# 多模态
curl -X POST http://localhost:8080/api/v1/agent/vision -F 'image=@./cat.png' -F 'question=图里是什么动物？'

# 统计
curl http://localhost:8080/api/v1/stats/overview
```

## 五、环境变量（生产 profile）

| 变量 | 说明 | 默认值 |
|---|---|---|
| `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DB` | MySQL 连接 | localhost / 3306 / ai_nexus |
| `MYSQL_USER` / `MYSQL_PASSWORD` | MySQL 凭据 | root / 空 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | Redis 连接 | localhost / 6379 / 空 |
| `OLLAMA_BASE_URL` | Ollama 服务地址 | http://ollama:11434 |
| `MILVUS_HOST` / `MILVUS_PORT` / `MILVUS_USER` / `MILVUS_PASSWORD` | Milvus 连接 | localhost / 19530 / root / milvus |

> dev 环境密码可在 `application-dev.yml` 中直接修改，或用 `MYSQL_PASSWORD` 环境变量覆盖，避免提交个人密码到仓库。

## 六、部署与运维

### 容器化一键部署（推荐）
```bash
cp deploy/.env.example .env
docker compose up -d --build        # 应用+MySQL+Redis+Milvus 全链路
curl http://localhost:${APP_PORT:-8081}/health
```
详见 [deploy/README.md](deploy/README.md)。

### 认证与权限
- `ainexus.auth.enabled=true` 开启 JWT 鉴权（dev 默认关闭保持原行为）
- `POST /api/v1/auth/register` 注册 / `login` 登录（返回双令牌）/ `refresh` 刷新
- 鉴权开启后：知识库/对话历史/调用统计按用户隔离，越权返回 404
- `@RateLimit`/`@Idempotent` 自动升级为用户维度（登录态），匿名回退 IP/摘要

### 高可用开关（application.yml）
| 配置 | 默认 | 说明 |
|---|---|---|
| ainexus.resilience.circuit-enabled | false | 模型调用熔断（滚动窗口失败率→熔断→半开试探恢复） |
| ainexus.concurrency.model-max | 0 | 模型并发上限（Redisson 信号量排队，超时降级） |
| ainexus.rag.cache-enabled | false | 检索结果缓存（TTL+版本号主动失效） |
| ainexus.rag.mode / rerank-enabled | vector / false | 混合检索 RRF 融合 / Embedding 精排 |

### 监控与运维
- 全链路 traceId：日志带 traceId 字段，响应头 X-Trace-Id 回传
- 业务指标：/actuator/prometheus（知识库总量/日活对话/RAG 命中率/工具调用成功率）
- 组件健康：/actuator/health 聚合 MySQL/Redis/Milvus/Ollama 状态
- 业务审计：audit_log 表记录 KB 增删/文档上传/登录注册（操作人/IP/结果）
- 详见 [docs/operations.md](docs/operations.md)

## 七、进阶能力（SaaS 治理与运维）

### 多租户（`ainexus.tenant.enabled`，dev 默认关闭）
- 租户 CRUD：`/api/v1/tenants`（创建含配额：知识库数/并发数/日调用量；启停；专属模型路由）
- 二级隔离：`tenant_id + user_id` 双维度，越权统一 404；JWT access 令牌携带 tid
- 配额管控：建库数量配额、每日模型调用限额（Redis 原子计数）、租户/用户双维 QPS

### 模型服务池（`ainexus.model-pool.enabled`）
- 多 Ollama 节点集群化，`round_robin / least_connections` 负载均衡（连接计数感知）
- 定时健康探测：故障节点自动摘除、恢复回流，全故障回退主节点
- 路由三层决策：租户专属路由 > 全局场景路由 > 默认模型

### 动态配置中心（`ainexus.dynamic.enabled`）
- 运行时调整熔断/并发/检索参数，无需重启；优先级：租户专属 > 灰度 > 全局 > yml
- 管理接口 `/api/v1/config`，变更审计自动落库；详见 [docs/config-guide.md](docs/config-guide.md)

### 性能压测
- JDK 原生压测工具 `perf.LoadRunner`（chat/rag/stream/agent/限流场景，TP50-99/QPS）
- 基线数据与容量评估见 [docs/performance.md](docs/performance.md)

## 八、单元测试

```bash
mvn test
```

覆盖（共 19 个用例）：

| 测试类 | 覆盖点 |
|---|---|
| `TextChunkerTest` | 切分长度上限、overlap 衔接、空白输入、非法配置 |
| `ModelRouteContextTest` | 策略选择优先级、无策略兜底异常 |
| `RagPipelineTest` | 检索 topK/过滤表达式、提示词组装、空检索兜底 |
| `ApiResponseTest` | 统一响应体、错误码、业务异常 |
| `KnowledgeBaseServiceTest` | Collection 名规范化（中文名安全）、扩展名拦截、Chunk ID 36 位确定性、级联删除 ID 重算 |
| `StatsServiceTest` | 聚合转换、null 防护（不抛 NPE） |
| `SpelKeyResolverTest` | 参数表达式、嵌套属性、null 归一化 |

## 九、常见问题排查（FAQ）

| 现象 | 原因与处理 |
|---|---|
| `model "nomic-embed-text" not found` | Embedding 模型未拉取：`ollama pull nomic-embed-text`（RAG 必需） |
| 多模态接口报模型不支持 | 多模态模型未拉取：`ollama pull llava:7b`，或修改 `ainexus.model-route.routes.MULTIMODAL` |
| 向量入库报维度不匹配 | Collection 已按旧维度创建，Milvus 不会自动重建：手动 drop 该 Collection 或更换 `spring.ai.vectorstore.milvus.collection-name`（维度由 `embedding-dimension` 决定，nomic-embed-text 为 768） |
| 中文知识库名 | 已在服务端规范化为 ASCII 安全的 Collection 名（kb_ + md5 前 8 位），无需处理 |
| 启动报 8080 端口占用 | 结束残留进程或改 `server.port` |
| Redis 连接失败 | 限流/幂等依赖 Redis，检查 `spring.data.redis.*` 配置 |

## 十、目录结构

```
ai-nexus
├── pom.xml
├── sql/schema.sql
├── src/main/java/com/ainexus
│   ├── AiNexusApplication.java
│   ├── common/        # 统一返回体、错误码、异常、限流、幂等、常量
│   ├── config/        # Redisson、OpenAPI、异步线程池
│   ├── ai/            # route(策略)/pipeline(模板方法)/tools/resilience/memory/chunk/stats
│   ├── domain/        # JPA 实体与仓库
│   ├── dto/           # 请求/响应对象
│   ├── service/       # 业务编排
│   └── controller/    # REST 接口
├── src/main/resources # application.yml（全量注释）+ dev/prod + sql
└── src/test/java      # 单元测试
```
