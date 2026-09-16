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
- **可替换 NLP Runtime**：内置能力可按配置切换到外置 ONNX Runtime；模型版本、SHA-256、固定 tensor 合同、有界并发、超时、健康检查和错误脱敏由服务统一管理。
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
  java -jar xnlp-server/target/xnlp-server-0.4.0.jar

# PostgreSQL
mvn -pl xnlp-server -am package -DskipTests && \
SPRING_PROFILES_ACTIVE=postgres DB_USERNAME=postgres DB_PASSWORD=postgres \
  java -jar xnlp-server/target/xnlp-server-0.4.0.jar

# 本地 H2 文件库（无需安装数据库）
mvn -pl xnlp-server -am package -DskipTests && \
SPRING_PROFILES_ACTIVE=h2 java -jar xnlp-server/target/xnlp-server-0.4.0.jar
```

### Release 0.4 H2 + Spring AI RAG 快速开始

以下示例使用 H2 文件库和 OpenAI-compatible provider，同时启用 `ChatModel` 与 `EmbeddingModel`。也可以将 provider 环境变量替换为 Ollama；未配置 embedding provider 时，知识库导入和检索会返回明确的 `provider_unconfigured`，不会生成伪向量。

```bash
mvn -pl xnlp-server -am package -DskipTests

SPRING_PROFILES_ACTIVE=h2 \
SPRING_AI_MODEL_CHAT=openai \
SPRING_AI_MODEL_EMBEDDING=openai \
OPENAI_API_KEY="$OPENAI_API_KEY" \
OPENAI_BASE_URL="${OPENAI_BASE_URL:-https://api.openai.com}" \
OPENAI_CHAT_MODEL="${OPENAI_CHAT_MODEL:-gpt-4o-mini}" \
OPENAI_EMBEDDING_MODEL="${OPENAI_EMBEDDING_MODEL:-text-embedding-3-small}" \
java -jar xnlp-server/target/xnlp-server-0.4.0.jar
```

创建知识库并导入文本。导入是异步的，文档 `indexStatus` 变为 `INDEXED` 后即可检索：

```bash
BASE_URL=http://localhost:8760
TENANT_ID=default

KB_ID="$(curl -fsS -X POST "$BASE_URL/api/v1/knowledge-bases" \
  -H "X-Tenant-ID: $TENANT_ID" \
  -H 'Content-Type: application/json' \
  -d '{
    "name":"Release 0.4 quickstart",
    "description":"Portable Spring AI RAG knowledge base",
    "embeddingModel":"text-embedding-3-small",
    "chunkPolicy":{"maxCharacters":1000,"overlapCharacters":100,"separatorMode":"PARAGRAPH"}
  }' | jq -r '.id')"

DOC_ID="$(curl -fsS -X POST "$BASE_URL/api/v1/knowledge-bases/$KB_ID/documents" \
  -H "X-Tenant-ID: $TENANT_ID" \
  -H 'Content-Type: application/json' \
  -d '{
    "title":"Database portability",
    "content":"X-NLP uses Spring Boot DataSource and JdbcTemplate so H2, MySQL and PostgreSQL share the same repository implementation.",
    "sourceType":"TEXT",
    "externalId":"quickstart-database-portability",
    "metadata":{"topic":"database"}
  }' | jq -r '.id')"

until [[ "$(curl -fsS \
  -H "X-Tenant-ID: $TENANT_ID" \
  "$BASE_URL/api/v1/knowledge-bases/$KB_ID/documents/$DOC_ID" | jq -r '.indexStatus')" == "INDEXED" ]]; do
  sleep 1
done
```

执行语义检索和带可核验引用的 RAG 对话：

```bash
curl -fsS -X POST "$BASE_URL/api/v1/knowledge-bases/$KB_ID/search" \
  -H "X-Tenant-ID: $TENANT_ID" \
  -H 'Content-Type: application/json' \
  -d '{"query":"如何切换数据库？","topK":5,"minScore":0.1,"rerank":false}' | jq

curl -fsS -X POST "$BASE_URL/api/v1/knowledge-bases/$KB_ID/rag" \
  -H "X-Tenant-ID: $TENANT_ID" \
  -H 'Content-Type: application/json' \
  -d '{
    "message":"X-NLP 的数据层如何切换数据库？",
    "topK":5,
    "maxContextChunks":3,
    "insufficientContextPolicy":"REJECT",
    "timeoutMs":30000
  }' | jq
```

升级数据库、provider/embedding 模型切换和回滚步骤见 `docs/UPGRADE-0.4.md`。

### Release 0.4 外部 E2E、性能门禁与数据库矩阵

H2 基线会构建服务端、创建隔离的临时数据库并启动确定性的 OpenAI-compatible mock provider；随后以 Benchmark P95 上限作为稳定性能烟测，并验证健康检查、Provider 诊断、模型配置/激活/预测、Benchmark、Dataset CRUD、稳定错误合同与测试数据清理：

```bash
./tests/e2e/run-h2.sh
```

脚本默认使用 `127.0.0.1:18760` 和 `127.0.0.1:18880`，可通过 `XNLP_E2E_API_PORT`、`XNLP_E2E_PROVIDER_PORT` 覆盖。已有构建产物时可设置 `XNLP_E2E_SKIP_BUILD=true`；性能烟测默认要求 P95 不超过 5000ms，可通过 `XNLP_E2E_MAX_BENCHMARK_P95_MS` 收紧或按受控 CI 环境调整。GitHub Actions 的 `verify` job 会将版本一致性、H2 外部 E2E/性能门禁以及 MySQL/PostgreSQL 数据库矩阵作为必跑步骤；`security` job 使用 Trivy 阻断高危/严重依赖漏洞和仓库 secret。

MySQL/PostgreSQL 使用临时容器执行同一套合同测试，不依赖开发数据库，也不会持久化测试卷：

```bash
./tests/e2e/run-db-matrix.sh mysql
./tests/e2e/run-db-matrix.sh postgres
# 或顺序执行两种数据库
./tests/e2e/run-db-matrix.sh all
```

需要 Docker Compose。数据库名称、测试账号、密码和映射端口可分别通过 `XNLP_E2E_DB_NAME`、`XNLP_E2E_DB_USERNAME`、`XNLP_E2E_DB_PASSWORD`、`XNLP_E2E_MYSQL_PORT`、`XNLP_E2E_POSTGRES_PORT` 覆盖；不要在共享或生产数据库上运行 E2E。

### Spring AI Embedding 语义检索

在配置 embedding provider 后，工作台的 `STS` 能力会优先使用 Spring AI `EmbeddingModel`，并可直接对评测数据集执行按租户隔离的 Top-K 语义搜索：

```bash
SPRING_AI_MODEL_EMBEDDING=ollama OLLAMA_EMBEDDING_MODEL=nomic-embed-text \
  SPRING_PROFILES_ACTIVE=h2 java -jar xnlp-server/target/xnlp-server-0.4.0.jar

curl -X POST http://localhost:8760/api/v1/nlp/semantic-similarity \
  -H 'Content-Type: application/json' \
  -d '{"text":"自然语言处理","textPair":"NLP 技术"}'

curl -X POST http://localhost:8760/api/v1/datasets/<dataset-id>/semantic-search \
  -H 'Content-Type: application/json' \
  -d '{"query":"检索排序","topK":5}'
```

未配置 embedding provider 时，STS 仍回退到内置 demo runtime；检索接口会返回明确的 provider 配置错误，不会伪造向量结果。

### 配置外置 ONNX 情感 Runtime

默认仍使用内置词典 demo。要为 `SENTIMENT` 启用严格 ONNX 模式，先构建服务并挂载符合合同的模型：

```bash
export XNLP_ONNX_ENABLED=true
export XNLP_SENTIMENT_RUNTIME_MODE=ONNX
export XNLP_ONNX_MODEL_PATH=/opt/xnlp/models/sentiment.onnx
export XNLP_ONNX_MODEL_VERSION=v1
export XNLP_ONNX_MODEL_SHA256=<64-char-sha256>
export XNLP_ONNX_INPUT_NAME=x
export XNLP_ONNX_OUTPUT_NAME=y

SPRING_PROFILES_ACTIVE=h2 java -jar xnlp-server/target/xnlp-server-0.4.0.jar
```

`XNLP_SENTIMENT_RUNTIME_MODE` 支持：

- `BUILTIN`：仅使用内置 demo；
- `AUTO`：ONNX ready 时执行真实 runtime，否则回退并在响应 metadata 中给出稳定原因；
- `ONNX`：严格使用 ONNX，未配置、加载失败或执行失败时返回稳定错误，不静默回退。

当前最小模型合同为：一个固定正维度的 `FLOAT` input、一个至少包含一个值的 `FLOAT` output；output 第一个值必须是 `[0,1]` 的正向情感概率。服务使用确定性 hashed unigram/bigram featurizer 填充 input，因此生产模型必须按相同合同训练和导出。典型 Transformer 的多 `INT64` tokenizer input 模型不能直接加载到该适配器。模型不会打包进服务镜像，部署时应只读挂载并配置精确 SHA-256。

ONNX Runtime 健康状态位于 Actuator health details；执行结果 metadata 包含实际 mode、runtime、provider、模型版本/checksum 和耗时。Java 25 启动参数需要 `--enable-native-access=ALL-UNNAMED`，仓库 Dockerfile 已默认配置。

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
java -jar xnlp-cli/target/xnlp-cli-0.4.0.jar \
  --server http://localhost:8760 \
  --api-key "$XNLP_API_KEY" \
  --tenant "$XNLP_TENANT_ID" health
java -jar xnlp-cli/target/xnlp-cli-0.4.0.jar models
java -jar xnlp-cli/target/xnlp-cli-0.4.0.jar provider status
java -jar xnlp-cli/target/xnlp-cli-0.4.0.jar provider probe
java -jar xnlp-cli/target/xnlp-cli-0.4.0.jar benchmark \
  --model ollama-default --requests 100 --concurrency 8 \
  --text "Explain retrieval-augmented generation."
java -jar xnlp-cli/target/xnlp-cli-0.4.0.jar dataset-list
java -jar xnlp-cli/target/xnlp-cli-0.4.0.jar evaluation-status
```

非 2xx 响应会转换为带 HTTP 状态码、稳定错误码、`requestId` 和 `traceId` 的 SDK 异常；CLI 输出相同诊断字段并以退出码 `2` 结束，不打印服务端堆栈。

浏览器回归使用 Playwright，并通过 API mock 覆盖 Playground 成功、Provider 失败和无模型空状态，不依赖真实外部 Provider：

```bash
cd xnlp-frontend
npx playwright install chromium
npm run test:e2e
```

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
  java -jar xnlp-server/target/xnlp-server-0.4.0.jar

# OpenAI-compatible provider
SPRING_AI_MODEL_CHAT=openai OPENAI_API_KEY=*** \
  OPENAI_BASE_URL=https://api.openai.com OPENAI_CHAT_MODEL=gpt-4o-mini \
  SPRING_PROFILES_ACTIVE=h2 java -jar xnlp-server/target/xnlp-server-0.4.0.jar
```

### API Key / JWT 身份保护

服务默认保持本地开发兼容，`XNLP_SECURITY_MODE` 为空且 `XNLP_SECURITY_ENABLED=false` 时不要求鉴权。共享环境可选择 `API_KEY`、`JWT` 或迁移期 `HYBRID`；旧 `XNLP_SECURITY_ENABLED=true` 继续等价于 API Key 模式：

```bash
XNLP_SECURITY_MODE=API_KEY \
XNLP_SECURITY_API_KEYS='replace-with-a-long-random-key,another-key' \
SPRING_PROFILES_ACTIVE=h2 \
java -jar xnlp-server/target/xnlp-server-0.4.0.jar
```

JWT 模式使用 Spring Security OAuth2 Resource Server，必须配置 issuer 与 audience，可选显式 JWK Set 地址；服务会校验签名、issuer、audience、有效期和租户 claim：

```bash
XNLP_SECURITY_MODE=JWT \
XNLP_SECURITY_JWT_ISSUER_URI='https://id.example.com/realms/xnlp' \
XNLP_SECURITY_JWT_JWK_SET_URI='https://id.example.com/realms/xnlp/protocol/openid-connect/certs' \
XNLP_SECURITY_JWT_AUDIENCE='xnlp-api' \
SPRING_PROFILES_ACTIVE=postgres \
java -jar xnlp-server/target/xnlp-server-0.4.0.jar
```

默认 claim 为 `tenant_id` 和 `roles`，角色支持 `ADMIN`、`DEVELOPER`、`VIEWER`。VIEWER 只读；DEVELOPER 可创建、修改和运行资源但不能删除；ADMIN 还可删除资源和管理租户成员。持久化 membership 可覆盖 JWT 角色并用于降权。开启认证后，探针和 Swagger/OpenAPI 资源仍公开；其他接口需要 API Key 或 Bearer JWT。前端静态 API Key 模式可设置 `VITE_XNLP_API_KEY` 与 `VITE_XNLP_TENANT_ID`。

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
