# AI-Nexus Web · 企业级 AI 能力平台管理前端

基于 **Vue 3.4 + Vite 5 + TypeScript + Element Plus 2.8** 构建的管理控制台，与 [ai-nexus 后端](../ai-nexus)（Spring Boot 4.0 + Spring AI 2.0，端口 8080）**接口一一对应、开箱联调**。视觉体系为「灵动玻璃流体科技风」：玻璃拟态材质、流体渐变蓝主色、微光高光、全链路克制动效，深浅色双主题。

---

## 一、快速启动

```bash
# 1. 安装依赖（Node ≥ 18）
npm install

# 2. 启动开发服务器（http://localhost:5173）
npm run dev

# 3. 生产构建 / 预览构建产物
npm run build        # 产物输出至 dist/
npm run preview
```

### 无后端环境预览

内置 **Mock 引擎**（`src/mock/`），无后端也可预览并操作全部页面（登录任意账号密码即可）：

- 默认 `.env.development` 中 `VITE_USE_MOCK=auto`：**自动探测后端**，`/health` 可达时走真实接口，不可达时自动降级为演示数据（顶栏出现「演示数据」徽标）；
- 也可在顶栏「偏好设置」中手动切换 Mock 模式：`自动 / 真实后端 / 演示数据`（持久化到本地）；
- Mock 的 CRUD 变更持久化到 `localStorage`，可真实交互。

### 连接真实后端

1. 启动 ai-nexus 后端（默认 `http://localhost:8080`，无需额外 CORS 配置）；
2. 开发环境无需任何改动 —— Vite 代理已将 `/api`、`/actuator`、`/health` 转发到 `http://localhost:8080`（可用 `VITE_PROXY_TARGET` 修改目标）；
3. 顶栏「偏好设置」将 Mock 模式切为「真实后端」，或 `.env.development` 设置 `VITE_USE_MOCK=off`；
4. 使用后端 `/api/v1/auth/register` 注册的账号登录（开启 `ainexus.auth.enabled` 时鉴权生效）。

---

## 二、环境变量说明

| 变量 | 文件 | 说明 |
|---|---|---|
| `VITE_PROXY_TARGET` | `.env.development` | 开发代理目标，默认 `http://localhost:8080` |
| `VITE_USE_MOCK` | `.env.development` / `.env.production` | `auto`=自动探测（推荐）/ `on`=强制 Mock / `off`=强制真实后端 |
| `VITE_API_BASE_URL` | `.env.production` | 生产后端地址；默认同源部署（Nginx 反代 `/api` 到后端），跨域部署时填写如 `https://api.example.com` |

---

## 三、目录结构

```
src/
├── api/                    # 接口层：统一封装 + 按业务模块分文件
│   ├── request.ts          #   Axios 实例：拦截器 / 令牌刷新 / 错误码 / Mock 兜底
│   └── modules/            #   auth / chat / rag(SSE) / agent / knowledge / config
│                           #   stats / tenant / eval / monitor / model
├── assets/styles/          # 设计体系：variables(令牌) / glass(材质) / animations(动效)
│                           #   element(EP 覆盖) / markdown / base
├── components/             # 通用组件：StatCard / MarkdownRenderer / CitationPanel
│                           #   StepTimeline(思维链) / HealthPanel / GlobalSearch / EmptyState
├── composables/            # useChart(ECharts 封装) / useCountUp(数字滚动)
├── layouts/MainLayout.vue  # 可折叠侧边栏 + 顶栏 + 主内容区（响应式抽屉）
├── mock/                   # Mock 引擎与演示数据（离线预览）
├── router/                 # 路由 + 登录守卫 + 多租户菜单探测
├── stores/                 # Pinia：user(令牌/用户) / app(主题/动效/Mock/租户开关)
├── types/api.ts            # 后端契约 TypeScript 类型（与后端 DTO 一一对应）
├── utils/                  # jwt / format / download / trace / auth-storage / chain(思维链解析)
└── views/                  # 10 个页面视图
```

---

## 四、与后端的接口对应关系

统一约定：响应包装 `{ code, message, data, timestamp }`，`code === 'A0000'` 为成功；后端所有业务错误均为 HTTP 200 + 业务码，前端据此统一弹窗提示。

| 页面 | 前端调用 | 后端接口 | 状态 |
|---|---|---|---|
| 登录 / 注册 | `authApi.login / register / refresh` | `POST /api/v1/auth/login` `register` `refresh` | ✅ 真实 |
| 智能问答（流式） | `createRagStream()`（fetch 流式读取 SSE） | `POST /api/v1/rag/stream`（`data:` 为纯文本增量，流关闭即完成） | ✅ 真实 |
| 智能问答（同步/引用） | `ragApi.ask` | `POST /api/v1/rag/ask`（含 references / 相似度分 / fallback 标记） | ✅ 真实 |
| 云端会话恢复 | `chatApi.replay` | `GET /api/v1/chat/{sessionId}/replay` | ✅ 真实 |
| 智能体 | `agentApi.planExecute / invoke / vision` | `POST /api/v1/agent/plan-execute` `invoke` `vision` | ✅ 真实 |
| 知识库 | `knowledgeApi.*`（上传带进度条；分片查看解析 `/backup` JSON） | `/api/v1/kb` 系列 CRUD + `documents` 上传 + `backup` 导出 + `import` | ✅ 真实 |
| 系统配置 | `configApi.*`（在线修改实时生效 + 变更历史） | `/api/v1/config/keys` `effective` `GET/PUT /config` | ✅ 真实 |
| 工作台统计 | `statsApi.overview` | `GET /api/v1/stats/overview`（bySceneAndDate / byModel） | ✅ 真实 |
| 健康面板 | `monitorApi.actuatorHealth / appHealth` | `GET /actuator/health`、`GET /health` | ✅ 真实 |
| 指标看板 | `monitorApi.metrics / metric / prometheus` | `GET /actuator/metrics[/{name}]`、`/actuator/prometheus` | ✅ 真实 |
| 日志级别调整 | `monitorApi.loggers / setLoggerLevel` | `GET/POST /actuator/loggers[/{name}]` | ✅ 真实 |
| 租户管理 | `tenantApi.*`（含启停二次确认、配额、专属路由） | `/api/v1/tenants` 系列 | ✅ 真实 |
| 模型节点池 / 场景路由 | `modelApi.nodes / routes / saveRoutes` | `/model-pool/nodes`、`/model-route/config` | 🧩 后端规划中，Mock 驱动（页面真实部分为模型调用统计表） |
| 可用工具清单 | `modelApi.tools` | `/agent/tool-list` | 🧩 后端规划中，Mock 驱动 |
| 链路追踪详情 | `monitorApi.trace` | `/api/v1/trace/{id}` | 🧩 后端规划中，Mock 驱动（真实能力：响应头 `X-Trace-Id` 自动捕获、最近链路快速回查） |
| 审计日志 | `monitorApi.auditLogs` | `/api/v1/audit/logs` | 🧩 后端规划中，Mock 驱动 |

> 🧩 预留模块在请求返回 `A0003/A0001` 或网络失败时**自动回退 Mock**（`mockFallback`），后端接口就绪后无需改前端即可切换为真实数据。另对接 `POST /api/v1/chat/extract|keywords`（结构化抽取/要点）、`POST /api/v1/rag/eval`（RAG 评估）等能力已封装于 API 层。

### 认证与多租户

- 请求自动携带 `Authorization: Bearer {accessToken}`；HTTP 401 或错误码 `E0001/E0002` 时**单飞刷新令牌**（`/auth/refresh`）并重试一次，失败跳转登录页；
- `refreshToken` 7 天有效，`accessToken` 默认 30 分钟（`expiresIn` 秒）；
- 登录后从 JWT `tid` claim 解析租户 ID 并注入 `X-Tenant-Id` 请求头（后端亦从 JWT 识别，双兼容）；
- 多租户菜单通过探测 `/api/v1/tenants` 可达性动态显示/隐藏（仅 401/403 时隐藏；Mock 模式恒显示），亦可在「偏好设置」手动开关。

---

## 五、设计体系与动效

- **色彩**：主色流体渐变蓝 `#165DFF → #4080FF`；辅助色柔化处理 `#00B42A / #FF7D00 / #F53F3F`；浅色=极浅灰底+微弱径向光斑，深色=深灰底+顶部淡蓝微光（不使用纯黑纯白）；
- **玻璃拟态**：`backdrop-filter: blur(12px)` + 0.6~0.8 透明度 + `0 4px 20px` 柔投影 + 1px 半透明描边与顶部微光高光；层级以「模糊度+透明度+阴影浓度」区分（弹窗 > 侧边栏 > 卡片 > 背景）；
- **动效**：统一缓动 `cubic-bezier(0.4, 0, 0.2, 1)`，基础 200ms / 长过渡 350ms。页面切换上移淡入、列表瀑布式入场（每项延迟 50ms）、卡片 hover 上浮 4px、按钮按压缩放 0.97 + 微光扫过加载态、输入聚焦光晕、弹窗缩放淡入、SSE 光标呼吸闪烁、思考三点波浪、思维链节点呼吸光环、消息气泡弹性滑入、指标数字滚动；
- **动效分级 `animation-level`**：顶栏「偏好设置」可切换 高 / 中（默认）/ 低 / 关（全部动效停用），并尊重系统 `prefers-reduced-motion`；
- **主题**：浅 / 深双主题一键切换，ECharts 图表随主题重建，Element Plus 通过 CSS 变量深度定制（未更换组件库、未侵入组件逻辑）。

---

## 六、功能亮点

- **智能问答**：类 ChatGPT 双栏布局；SSE 流式逐字渲染 + 可中断（停止按钮）+ 失败重发；Markdown/代码高亮/一键复制；同步模式展示引用片段（来源文档、相似度分、片段偏移）折叠面板；会话本地持久化刷新不丢失，支持按 sessionId 恢复服务端会话；
- **智能体**：思维链解析为「思考→调用工具→观察→总结」竖向时间线，工具名/入参高亮；降级兜底告警、执行步数与耗时展示；附多模态图片理解；
- **知识库**：卡片列表、文档上传（进度条、类型/大小校验、解析状态轮询）、分片查看（解析备份 JSON）、导出/导入备份、删除二次确认（提示级联清理向量）；
- **全局搜索**：`Ctrl/Cmd + K` 快速跳转菜单与知识库；
- **响应式**：≤900px 侧边栏变抽屉，栅格自适应。

## 七、已知边界（与后端现状对齐）

1. RAG **流式接口不返回引用**（后端 SSE 仅为文本增量），引用溯源请使用「同步问答」模式或等待后端在 SSE 中扩展 citations 事件；
2. RAG 流式会话服务端不落库（仅 `/chat` 落库），故会话历史以本地持久化为主、`/chat/{id}/replay` 恢复为辅；
3. 「RAG 命中率」指标需接入 `POST /api/v1/rag/eval` 评估任务，当前真实模式下显示 `—`，演示模式显示参考值；
4. 模型节点池 / 场景路由 / 审计日志 / 链路详情为前端预留页面，接口就绪后自动切换真实数据。
