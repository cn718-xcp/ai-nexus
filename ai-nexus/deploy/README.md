# 容器化部署说明

## 一、前置条件

| 组件 | 要求 |
|---|---|
| Docker | 24+（推荐 27+） |
| Docker Compose | v2.24+ |
| 内存 | 建议 8GB+ 可用（Milvus standalone 约占 2GB，MySQL/Redis 轻量） |
| 模型服务 | 宿主机已运行 Ollama 并拉取模型；**必须监听 0.0.0.0**：设置环境变量 `OLLAMA_HOST=0.0.0.0` 后重启 Ollama，否则容器无法访问宿主机模型服务 |

```bash
# 宿主机模型准备（RAG 必需 embedding，多模态需要 llava）
ollama pull qwen2.5:7b
ollama pull nomic-embed-text
ollama pull llava:7b
```

## 二、一键启动

```bash
# 1. 准备环境变量
cp deploy/.env.example .env

# 2. 构建并启动全链路（首次构建需下载 Maven 依赖，约 5-15 分钟）
docker compose up -d --build

# 3. 查看启动状态（app healthy 即就绪）
docker compose ps
docker compose logs -f app

# 4. 验证
curl http://localhost:${APP_PORT:-8081}/health
```

> Windows/macOS 的 Docker Desktop 默认支持 `host.docker.internal`；Linux 需在 app 服务下追加
> `extra_hosts: ["host.docker.internal:host-gateway"]`。

## 三、端口与资源配置

| 服务 | 容器端口 | 宿主机默认端口 | 用途 | 资源建议 |
|---|---|---|---|---|
| app | 8080 | 8081 | 业务接口/Swagger | 堆上限取容器内存 75%（自动） |
| mysql | 3306 | 33061 | 元数据/历史/流水 | 数据卷 `mysql-data` 持久化 |
| redis | 6379 | 16379 | 限流/幂等/缓存/锁 | AOF 持久化开启 |
| milvus | 19530 | 19531 | 向量存储（内嵌 etcd + 本地存储，单容器轻量部署） | 约 2GB 内存 |
| milvus 指标 | 9091 | 19091 | 健康检查/Metrics | - |

> Milvus 采用官方 standalone 内嵌 etcd + 本地存储模式，单容器即可运行，无需独立 etcd/minio；
> 生产大规模部署可切换为集群模式（独立 etcd + minio + 多 milvus 节点）。

所有端口通过 `.env` 调整；数据卷命名空间隔离，`docker compose down` 不丢数据，
`docker compose down -v` 才会清空。

## 四、常用运维命令

```bash
docker compose ps                      # 状态与健康检查
docker compose logs -f app             # 应用日志
docker compose restart app             # 重启应用
docker compose up -d --build app       # 仅重建应用（基础设施不受影响）
docker compose down                    # 停止（保留数据卷）
docker compose down -v                 # 停止并清空全部数据
```

## 五、模型服务说明

- 默认：容器内应用通过 `OLLAMA_BASE_URL` 复用**宿主机** Ollama，模型已在本机，免重复下载。
- 可选：若希望 Ollama 一并入容器，取消 compose 中 ollama 服务注释并执行
  `docker compose --profile models up -d`，再 `docker compose exec ollama ollama pull qwen2.5:7b`。
- 模型体积大（qwen2.5:7b 约 4.7GB），不建议纳入常规 CI 镜像。
