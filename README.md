# AI Nexus · 企业级 AI 能力平台

单仓（monorepo）管理的前后端一体项目：Spring Boot 4 + Spring AI 2 构建的后端 AI 服务，配套 Vue 3 管理控制台。

| 目录 | 说明 | 技术栈 | 默认端口 |
|---|---|---|---|
| [`ai-nexus/`](ai-nexus) | 后端服务：知识库管理 / RAG 检索增强问答 / 多模型路由 / Function Calling Agent / 多模态图片理解 / 调用统计，并含限流、幂等、重试、降级、可观测等生产级规范 | JDK 17 · Spring Boot 4.0.0 · Spring AI 2.0.1 GA · MySQL · Milvus · Redis · Ollama | 8080 |
| [`ai-nexus-web/`](ai-nexus-web) | 管理控制台：与后端接口一一对应，玻璃拟态视觉体系，内置 Mock 引擎可脱离后端预览全部页面 | Vue 3.4 · Vite 5 · TypeScript · Element Plus 2.8 · ECharts | 5173 |

完整说明（技术栈细节、接口示例、全部配置项、FAQ）见 [ai-nexus/README.md](ai-nexus/README.md) 与 [ai-nexus-web/README.md](ai-nexus-web/README.md)。

## 快速开始

### 1. 后端

```bash
cd ai-nexus

# 前置依赖：MySQL 8.0 / Redis / Milvus（standalone） / Ollama
#   ollama pull qwen2.5:7b && ollama pull nomic-embed-text
# 数据库连接改 src/main/resources/application-dev.yml

mvn clean package -DskipTests
mvn spring-boot:run          # 开发态启动，http://localhost:8080

# 容器化一键部署（应用 + MySQL + Redis + Milvus 全链路）
cp deploy/.env.example .env  # 按需修改端口与密码，然后：
docker compose up -d --build
```

启动后接口文档：<http://localhost:8080/swagger-ui.html>（容器化部署时为 `${APP_PORT:-8081}`）。

### 2. 前端

```bash
cd ai-nexus-web
npm install
npm run dev                  # http://localhost:5173
```

Vite 已把 `/api`、`/actuator`、`/health` 代理到 `http://localhost:8080`，后端就绪即可开箱联调；
后端未启动时 `VITE_USE_MOCK=auto` 会自动降级为演示数据，可独立预览与操作全部页面。

## 仓库结构

```
.
├── ai-nexus/                 # 后端（Spring Boot 4 + Spring AI 2）
│   ├── src/main/java/        # Controller / Service / AI 编排 / 领域模型
│   ├── src/main/resources/   # application.yml + dev/prod profile + schema.sql
│   ├── src/test/java/        # 单元测试
│   ├── sql/schema.sql        # 建表脚本（幂等）
│   ├── docs/                 # 配置手册 / 运维手册 / 性能压测报告
│   └── deploy/               # 容器编排部署包（含 .env 模板与部署说明）
└── ai-nexus-web/             # 前端（Vue 3 + Vite 5 + Element Plus）
    └── src/                  # views / components / api / stores / mock
```

## 构建与测试

```bash
# 后端：编译 + 单元测试
cd ai-nexus && mvn test

# 前端：类型检查 + 生产构建
cd ai-nexus-web && npm run build:check
```

## 约定

- **换行符**：仓库内文本统一以 LF 存储，策略见 [.gitattributes](.gitattributes)；Windows 检出时工作区形态由 `core.autocrlf` 决定。
- **敏感配置不入库**：密码、密钥通过环境变量或本地 `.env` 注入，模板见 `ai-nexus/deploy/.env.example`。
- **数据库表结构**由 `sql/schema.sql` 统一管理，JPA `ddl-auto` 已关闭。
