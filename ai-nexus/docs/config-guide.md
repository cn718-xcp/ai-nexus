# 配置管理手册

## 一、配置分层与优先级

```
租户专属配置（sys_config.tenant_id 非空）
  > 灰度配置（scope=gray 且当前租户在 gray_tenants 中）
    > 全局配置（sys_config.tenant_id IS NULL）
      > 本地 application.yml 默认值
```

- 开关：`ainexus.dynamic.enabled`（dev 默认 false，仅读 yml；开启后 DB 配置生效）
- 读取路径零 DB 开销：内存缓存 + 写时失效（配置修改即时生效，无需重启）

## 二、可管控配置键

| 配置键 | 说明 | 默认值 |
|---|---|---|
| `resilience.circuit-enabled` | 模型调用熔断开关 | false |
| `resilience.circuit-window` | 熔断滚动窗口样本数 | 10 |
| `resilience.circuit-failure-rate` | 熔断失败率阈值 | 0.6 |
| `resilience.circuit-open-seconds` | 熔断冷却时长（秒） | 30 |
| `concurrency.model-max` | 模型调用并发上限（0=不启用） | 0 |
| `concurrency.queue-timeout-seconds` | 排队等待超时（秒） | 15 |
| `rag.similarity-threshold` | 检索相似度阈值 | 0.5 |
| `rag.mode` | 检索模式：vector / hybrid | vector |
| `rag.rerank-enabled` | 二阶段精排开关 | false |
| `rag.cache-enabled` | 检索结果缓存开关 | false |

## 三、操作示例

```bash
# 查询当前生效值（含优先级解析结果）
curl http://localhost:8080/api/v1/config/effective
curl "http://localhost:8080/api/v1/config/effective?tenantId=7"   # 租户视角

# 查询已落库的配置记录
curl http://localhost:8080/api/v1/config

# 修改全局配置（实时生效 + 变更审计落库）
curl -X PUT http://localhost:8080/api/v1/config -H 'Content-Type: application/json' \
  -d '{"key":"rag.cache-enabled","value":"true"}'

# 灰度发布：仅租户 7、9 生效，验证后放开为全局
curl -X PUT http://localhost:8080/api/v1/config -H 'Content-Type: application/json' \
  -d '{"key":"resilience.circuit-enabled","value":"true","scope":"gray","grayTenants":"7,9"}'

# 租户专属配置（仅租户 7 生效）
curl -X PUT http://localhost:8080/api/v1/config -H 'Content-Type: application/json' \
  -d '{"key":"concurrency.model-max","value":"8","tenantId":7}'
```

## 四、变更审计

所有配置修改自动写入 `audit_log`：`action=CONFIG_UPDATE`，`target=配置键`，
`detail=变更前后值（old -> new）`，并记录操作人与客户端 IP。

## 五、日志动态分级

无需重启即可调整日志级别（Spring Boot Actuator 原生能力，已暴露 `loggers` 端点）：

```bash
# 开启 DEBUG
curl -X POST http://localhost:8080/actuator/loggers/com.ainexus.service \
  -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}'

# 恢复 INFO
curl -X POST http://localhost:8080/actuator/loggers/com.ainexus.service \
  -H 'Content-Type: application/json' -d '{"configuredLevel":"INFO"}'

# 查询当前级别
curl http://localhost:8080/actuator/loggers/com.ainexus.service
```

## 六、数据备份与迁移

```bash
# 导出：知识库元数据 + 全部片段原文（JSON）
curl http://localhost:8080/api/v1/kb/{kbId}/backup -o backup.json

# 导入：新建知识库并按片段原文重新向量化（新 docUuid 全量重建）
curl -X POST http://localhost:8080/api/v1/kb/import -F 'file=@backup.json'
```

> 导入会生成新知识库（名称追加 `-restored`），原向量数据不受影响；
> 配合「租户+用户」隔离可在租户间完成受控的数据迁移。
