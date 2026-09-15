# X-NLP

X-NLP is an open-source NLP serving framework designed to simplify the deployment, management, and evaluation of mainstream NLP models.

Instead of maintaining multiple inference services, X-NLP provides a unified interface for different NLP architectures, allowing developers to deploy, switch, benchmark, and compare models with minimal effort.

## Features

- 🚀 Unified RESTful APIs
- 🔄 One-click model switching
- 🧠 Support for multiple NLP architectures
- ⚡ High-performance inference
- 📊 Built-in benchmarking
- 🔌 Easy integration
- 🐳 Docker & Kubernetes ready
- 📈 Production-ready deployment

Designed for both research and enterprise production environments.

## 重构后的工程工作流

当前版本围绕“探索 → 组合 → 评测 → 交付”重构：

- **现代 Web 工作台**：左侧工作区导航、响应式布局、NLP 工作台、数据集/评测/画布和 `AI Assistant` 页面。
- **Spring AI 2.0.1**：服务端通过 Spring AI 的 `ChatModel` 抽象接入 Ollama 与 OpenAI-compatible provider，业务层不直接依赖厂商 SDK；对外提供 `GET /api/v1/ai/status` 与 `POST /api/v1/ai/chat`。
- **工程能力**：统一 prompt 约束、请求观测、模型注册、数据集、评测、指标与对比链路；Pipeline Canvas 已可调用后端 pipeline trace，记录每个节点的输入、输出、状态和耗时。评测支持异步队列、逐条进度持久化、取消和模型/数据集/状态过滤。
- **数据库可切换**：使用 Spring Boot 的 `spring.datasource` profile 配置，默认 MySQL，也提供 PostgreSQL 和 H2 文件数据库配置；数据库通过版本化迁移（`db/migration/V*__*.sql`）创建和升级，HikariCP 连接池参数可由环境变量调优，数据访问层继续保持 repository 抽象。

### 启动前端

```bash
npm install --prefix xnlp-frontend
npm run dev --prefix xnlp-frontend
```

### 使用 Docker Compose 启动完整工作台

Compose 会启动 MySQL、Ollama、X-NLP Server 和 Nginx 前端。首次使用时先拉取一个 Ollama 模型：

```bash
docker compose up -d --build
docker compose exec ollama ollama pull llama3.1
# 浏览器访问 http://localhost:5173，API 访问 http://localhost:8760
```

可通过环境变量覆盖端口、数据库凭据和 Ollama 模型：

```bash
DB_USERNAME=xnlp DB_PASSWORD=change-me OLLAMA_CHAT_MODEL=qwen2.5:7b \
  XNLP_PORT=8760 FRONTEND_PORT=5173 docker compose up -d --build
```

停止服务但保留数据库和模型卷：

```bash
docker compose down
```



### Kubernetes / Helm 部署

仓库提供可直接渲染和安装的 Helm Chart：

```bash
helm lint deploy/helm/xnlp
helm upgrade --install xnlp deploy/helm/xnlp \
  --set server.database.url='jdbc:postgresql://postgres:5432/xnlp' \
  --set server.database.username=xnlp \
  --set server.database.password='change-me' \
  --set server.env.profile=postgres
```

Chart 默认部署 Spring Boot 服务和 React/Nginx 工作台，包含健康探针、H2/废弃物证据 PVC、可选 Ingress，以及通过 Secret 注入数据库、OpenAI 和 API Key 配置。生产环境建议使用外部 MySQL/PostgreSQL、Ingress TLS 和显式资源 requests/limits。

### 选择数据库

```bash
# 默认 MySQL
mvn -pl xnlp-server -am package -DskipTests && \
SPRING_PROFILES_ACTIVE=mysql DB_URL='jdbc:mysql://localhost:3306/xnlp' \
  java -jar xnlp-server/target/xnlp-server-0.1.0.jar

# PostgreSQL
mvn -pl xnlp-server -am package -DskipTests && \
SPRING_PROFILES_ACTIVE=postgres DB_USERNAME=postgres DB_PASSWORD=postgres \
  java -jar xnlp-server/target/xnlp-server-0.1.0.jar

# 本地 H2 文件库（无需安装数据库）
mvn -pl xnlp-server -am package -DskipTests && \
SPRING_PROFILES_ACTIVE=h2 java -jar xnlp-server/target/xnlp-server-0.1.0.jar
```

### Spring AI Embedding 语义检索

在配置 embedding provider 后，工作台的 `STS` 能力会优先使用 Spring AI `EmbeddingModel`，并可直接对评测数据集执行按租户隔离的 Top-K 语义搜索：

```bash
SPRING_AI_MODEL_EMBEDDING=ollama OLLAMA_EMBEDDING_MODEL=nomic-embed-text \
  SPRING_PROFILES_ACTIVE=h2 java -jar xnlp-server/target/xnlp-server-0.1.0.jar

curl -X POST http://localhost:8760/api/v1/nlp/semantic-similarity \
  -H 'Content-Type: application/json' \
  -d '{"text":"自然语言处理","textPair":"NLP 技术"}'

curl -X POST http://localhost:8760/api/v1/datasets/<dataset-id>/semantic-search \
  -H 'Content-Type: application/json' \
  -d '{"query":"检索排序","topK":5}'
```

未配置 embedding provider 时，STS 仍回退到内置 demo runtime；检索接口会返回明确的 provider 配置错误，不会伪造向量结果。

### Pipeline Trace API

Canvas 可对数据集样本执行一个有序的 NLP 能力链，并返回可审计的节点级 trace：

```bash
curl -X POST http://localhost:8760/api/v1/pipelines/execute \
  -H 'Content-Type: application/json' \
  -d '{"text":"这个产品很好用，我很满意。","language":"zh","nodes":[{"id":"tokens","capability":"TOK"},{"id":"sentiment","capability":"SENTIMENT"}]}'
```

能力目录：

```text
GET /api/v1/pipelines/capabilities
```

### Java SDK 与 CLI

`xnlp-client` 提供基于 JDK `HttpClient` 的类型化 Java SDK，支持 API Key、租户、超时、自定义 HTTP 客户端和 Jackson 配置；覆盖模型资产、运行时模型、批量推理、数据集、异步评测、评测对比、SSE 进度以及 NLP/Pipeline/AI 扩展接口。

```java
try (XNLPClient client = XNLPClient.builder("http://localhost:8760")
        .apiKey(System.getenv("XNLP_API_KEY"))
        .tenantId(System.getenv().getOrDefault("XNLP_TENANT_ID", "default"))
        .build()) {
    System.out.println(client.health());
    client.listModels().forEach(model -> System.out.println(model.getName()));
    System.out.println(client.providerDiagnostics());
    System.out.println(client.benchmark("ollama-default",
            new BenchmarkRequest(100, 8, "Explain retrieval-augmented generation.")));
}
```

CLI 由同一个 SDK 驱动，避免命令行和 REST 行为分叉：

```bash
java -jar xnlp-cli/target/xnlp-cli-0.1.0.jar \
  --server http://localhost:8760 \
  --api-key "$XNLP_API_KEY" \
  --tenant "$XNLP_TENANT_ID" health
java -jar xnlp-cli/target/xnlp-cli-0.1.0.jar models
java -jar xnlp-cli/target/xnlp-cli-0.1.0.jar provider status
java -jar xnlp-cli/target/xnlp-cli-0.1.0.jar provider probe
java -jar xnlp-cli/target/xnlp-cli-0.1.0.jar benchmark \
  --model ollama-default --requests 100 --concurrency 8 \
  --text "Explain retrieval-augmented generation."
java -jar xnlp-cli/target/xnlp-cli-0.1.0.jar dataset-list
java -jar xnlp-cli/target/xnlp-cli-0.1.0.jar evaluation-status
```

非 2xx 响应会转换为带 HTTP 状态码、稳定错误码、`requestId` 和 `traceId` 的 SDK 异常；CLI 输出相同诊断字段并以退出码 `2` 结束，不打印服务端堆栈。

### 异步评测 API

评测提交不会阻塞 HTTP 请求，接口立即返回 `202 Accepted` 和 `Location`：

```bash
curl -i -X POST http://localhost:8760/api/v1/evaluations \
  -H 'Content-Type: application/json' \
  -d '{"modelName":"ollama-default","datasetId":"<dataset-id>","taskType":"SENTIMENT_ANALYSIS"}'

# 订阅实时状态和进度（SSE；-N 禁止 curl 缓冲）
curl -N \
  -H 'Accept: text/event-stream' \
  http://localhost:8760/api/v1/evaluations/<run-id>/events

# 轮询状态和进度（SSE 不可用时的降级方式）
curl http://localhost:8760/api/v1/evaluations/<run-id>

# 请求取消（worker 在样本边界安全停止）
curl -X POST http://localhost:8760/api/v1/evaluations/<run-id>/cancel

# 按模型、数据集名称或状态过滤历史任务
curl 'http://localhost:8760/api/v1/evaluations?status=running&modelName=ollama-default'
```

`EvaluationRun` 的状态为 `queued`、`running`、`cancelling`、`completed`、`failed` 或 `cancelled`，并返回 `totalEntries`、`processedEntries`、`progressPercent`。

### 配置 Spring AI provider

先构建包含当前 `xnlp-core` 依赖的服务包，再启动服务：

```bash
mvn -pl xnlp-server -am package -DskipTests

# Ollama（默认 provider）
SPRING_AI_MODEL_CHAT=ollama OLLAMA_BASE_URL=http://localhost:11434 \
  OLLAMA_CHAT_MODEL=llama3.1 SPRING_PROFILES_ACTIVE=h2 \
  java -jar xnlp-server/target/xnlp-server-0.1.0.jar

# OpenAI-compatible provider
SPRING_AI_MODEL_CHAT=openai OPENAI_API_KEY=*** \
  OPENAI_BASE_URL=https://api.openai.com OPENAI_CHAT_MODEL=gpt-4o-mini \
  SPRING_PROFILES_ACTIVE=h2 java -jar xnlp-server/target/xnlp-server-0.1.0.jar
```

### 可选 API Key 保护

服务默认保持本地开发兼容，`XNLP_SECURITY_ENABLED=false` 时不要求鉴权。部署到共享环境时建议开启 Spring Security API Key 模式：

```bash
XNLP_SECURITY_ENABLED=true \
XNLP_SECURITY_API_KEYS='replace-with-a-long-random-key,another-key' \
SPRING_PROFILES_ACTIVE=h2 \
java -jar xnlp-server/target/xnlp-server-0.1.0.jar
```

开启后，以下运维入口仍可供探针和文档访问：`/health`、`/livez`、`/readyz`、`/startupz`、`/ok`、`/actuator/health`、Swagger/OpenAPI 资源；其余接口需要携带 `X-API-Key`，也兼容 `Authorization: Bearer <key>`。前端构建时可设置 `VITE_XNLP_API_KEY` 与 `VITE_XNLP_TENANT_ID`，工作台会自动为普通 API、SSE 和废弃物上传请求附加认证及租户 Header。

### 多租户数据隔离

开启 API Key 安全后，可以为每个租户配置独立的 key。认证主体会绑定租户，客户端提交的 `X-Tenant-ID` 不会覆盖认证租户：

```yaml
xnlp:
  security:
    enabled: true
    api-key-tenants:
      tenant-a: ${TENANT_A_API_KEY}
      tenant-b: ${TENANT_B_API_KEY}
```

未开启安全时，`X-Tenant-ID` 仅用于本地开发切换租户；前端可通过 `VITE_XNLP_TENANT_ID` 设置，未携带时使用 `default`。模型配置、数据集、评测运行和废弃物清运业务数据均按租户过滤，JDBC、file、memory 三种存储实现保持相同隔离语义。已有数据库由 V4 迁移自动补充 `tenant_id` 并将历史数据归入 `default`。
