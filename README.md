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
- **Spring AI 2.0.0**：服务端通过 Spring AI 的 `ChatModel` 抽象接入 Ollama 与 OpenAI-compatible provider，业务层不直接依赖厂商 SDK；对外提供 `GET /api/v1/ai/status` 与 `POST /api/v1/ai/chat`。
- **工程能力**：统一 prompt 约束、请求观测、模型注册、数据集、评测、指标与对比链路；Pipeline Canvas 已可调用后端 pipeline trace，记录每个节点的输入、输出、状态和耗时。评测支持异步队列、逐条进度持久化、取消和模型/数据集/状态过滤。
- **数据库可切换**：使用 Spring Boot 的 `spring.datasource` profile 配置，默认 MySQL，也提供 PostgreSQL 和 H2 文件数据库配置；新库表结构由可移植的 `schema.sql` 初始化，数据访问层继续保持 repository 抽象。

### 启动前端

```bash
npm install --prefix xnlp-frontend
npm run dev --prefix xnlp-frontend
```

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

### 异步评测 API

评测提交不会阻塞 HTTP 请求，接口立即返回 `202 Accepted` 和 `Location`：

```bash
curl -i -X POST http://localhost:8760/api/v1/evaluations \
  -H 'Content-Type: application/json' \
  -d '{"modelName":"ollama-default","datasetId":"<dataset-id>","taskType":"SENTIMENT_ANALYSIS"}'

# 轮询状态和进度
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
