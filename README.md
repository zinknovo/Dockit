# Dockit — AI 文档智能协作平台

> 一个基于 Spring Cloud 微服务的文档智能处理系统，提供文档上传/版本管理、实时协作编辑、RAG 知识库问答与 Agent 自动化操作能力。

## ✨ 核心能力

| 能力 | 说明 |
|---|---|
| **Agent 智能执行** | 规划 → 执行 → 审批 → 反思的闭环：18 个 Agent Tool（文档读写、文件管理、RAG 检索、文本加工、PPT 生成），高风险步骤（文件恢复/版本切换）人工审批，失败自动重规划，任务快照 7 天可续接，进度经 WebSocket 实时广播 |
| **RAG 知识库** | Qdrant 向量库（向量 + 内容 payload 同存，命中即取）+ BM25 混合检索 + 跨编码器重排；检索/问答链统一收口（`KnowledgeQueryService`），分阶段耗时埋点接入 AIOps |
| **文档版本与协作** | 文档版本历史（MinIO 原始文件 + 版本元数据）、批注/评论、多人实时协作（WebSocket + 操作广播） |
| **多模型接入** | 统一 OpenAI 兼容协议：DeepSeek / 通义千问 / GLM / Kimi / MiniMax / 火山方舟，模型由配置驱动，切换即改配置 |
| **AIOps 监控** | 模型调用诊断：FaultDetector（错误率/P95 延迟阈值告警）、告警冷却、Prometheus/Grafana |
| **异步任务** | RabbitMQ 驱动异步化（文件操作、AI 请求），主链路不被阻塞 |

## 🏗 架构

微服务（Spring Cloud Alibaba + Next.js），服务经由 Nacos 注册发现，网关统一 JWT 鉴权：

| 服务 | 端口 | 职责 |
|---|---|---|
| gateway-service | 8080 | 路由 / JWT 鉴权 / CORS / 日志告警上报 |
| user-service | 8081 | 注册登录、refresh token |
| file-service | 8082 | 文件元数据、上传/下载/删除、bucket 权限校验 |
| ai-service | 8083 | 对话、RAG 知识库、Agent 执行、技能系统、AIOps 监控 |
| document-service | 8084 | 文档 CRUD、版本控制、批注/评论、实时协作 |
| web/ | 3000 | Next.js 前端（工作台、文档编辑、Agent 页） |

基础设施：**Nacos**（注册+配置）、**MySQL**、**Redis**、**Qdrant**（向量库）、**RabbitMQ**、**MinIO**、**Prometheus/Grafana**（monitoring profile）。

> 完整架构图与领域词汇见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 与 [CONTEXT.md](CONTEXT.md)，跨服务决策见 [docs/adr/](docs/adr/)。

## 🚀 快速开始

### 环境要求

- JDK **21** + Maven 3.8+
- Node.js **20.19+**（web 前端）
- Docker + Docker Compose（基础设施与部署）

### 1. 配置密钥

```bash
cp .env.example .env
# 编辑 .env：填入 OPENAI_API_KEY（智谱 GLM）/ DASHSCOPE_API_KEY 等外部模型密钥
```

### 2. 一键启动（基础设施 + 微服务）

```bash
./start.sh
```

脚本完成：Maven 打包 → 构建镜像 → `docker compose up`。

也可拆分部署：`docker-compose-infra.yml`（基础设施）/ `docker-compose-services.yml`（服务）/ `docker-compose-gate-user.yml` / `docker-compose-monitoring.yml`。

### 3. 本地开发（单模块调试）

```bash
# 先启动基础设施（Redis/Qdrant/MySQL 至少）
docker compose -f docker-compose-infra.yml up -d

# IDE 直接运行各服务主类（本地 profile 需 MYSQL_HOST=localhost MYSQL_PORT=3307）
# 或 Maven 启动
mvn spring-boot:run -pl ai-service
```

### 4. 前端开发

```bash
cd web
npm install
npm run dev     # :3000
```

## 🧪 测试

```bash
# 单测（全部 mock，无外部依赖）
mvn test

# CI 同款（排除依赖真实基础设施的上下文冒烟测试）
mvn test -Dtest='!*ApplicationTests,!AllModelsTest' -Dsurefire.failIfNoSpecifiedTests=false

# RAG 压测（可选，需 .env 密钥 + 本地 Qdrant）
mvn -pl ai-service test -Dtest=RagBenchmarkTest -Dbenchmark=true
```

> 注意：`*ApplicationTests` / `AllModelsTest` 会启动完整 Spring 上下文，需真实 MySQL/Redis/API Key（加载 `.env` 后运行）。压测报告见 [docs/rag-benchmark.md](docs/rag-benchmark.md)（实测：500/1000 篇规模下检索 P95 ≈ 717ms / 1.2s）。

## 📦 部署

- 一键：`./start.sh`
- 脚本式（原 Swarm 流程）：`./deploy.sh`
- CI：GitHub Actions（push main / PR 触发：后端单测 + 前端 lint/build）

## 📚 文档

| 文档 | 内容 |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 架构总览、服务拓扑、关键机制 |
| [CONTEXT.md](CONTEXT.md) | 领域词汇表（统一命名） |
| [docs/adr/0001-file-lifecycle-ownership.md](docs/adr/0001-file-lifecycle-ownership.md) | 架构决策记录 |
| [docs/rag-benchmark.md](docs/rag-benchmark.md) | RAG 索引/检索压测报告 |
| [docs/agents/](docs/agents/) | 面向 AI Agent 的领域/流程文档 |

## 🔒 安全说明

- 用户文件按 **bucket** 隔离（MinIO），权限校验经 `BucketPermissionChecker`（common 单一实现）
- 文档级 `Document Access` 访问控制
- 敏感操作（文件恢复、版本切换）需要**人工审批**（approval token 绑定操作 + 参数指纹）
- `.env` 不入库；生产密钥通过环境变量注入
