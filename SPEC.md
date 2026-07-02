## X-NLP MVP 功能列表 & 规格说明

> X-NLP 是一个 NLP 处理框架，提供模型管理、数据集管理、评测引擎与对比分析。
> 版本：MVP 0.1.0 | 更新：2026-07-02 (cross-verified)

---

## 一、架构概览

```
xnlp-core        共享领域模型、SPI、管线、模型注册中心
xnlp-server      Spring Boot 4.1.0 REST API 服务 (端口 8080)
xnlp-client       Java SDK (HTTP client)
xnlp-cli          Picocli 命令行工具
xnlp-frontend     React 18 + Vite 5 + Tailwind 3 前端 (端口 5173, 已代理到后端)
```

```
依赖图: xnlp-cli -> xnlp-client -> xnlp-core <- xnlp-server
```

**当前运行状态**：后端 Java (8080) + 前端 Vite (5173) 均已启动并可交互。

---

## 二、功能完成矩阵

### 2.1 后端 REST API

| # | 功能 | 端点 | 状态 | 说明 |
|---|------|------|------|------|
| 1 | 健康检查（聚合） | `GET /health` | ✅ | 包含探针状态汇总 |
| 2 | K8s Liveness 探针 | `GET /livez`, `GET /healthz` | ✅ | 进程存活探测 |
| 3 | K8s Readiness 探针 | `GET /readyz` | ✅ | 模型注册中心就绪检查 |
| 4 | K8s Startup 探针 | `GET /startupz` | ✅ | 慢启动探测 |
| 5 | 最小存活信号 | `GET /ok` | ✅ | 200 空 body |
| 6 | 列出模型 | `GET /api/v1/models` | ✅ | |
| 7 | 查看模型详情 | `GET /api/v1/models/{name}` | ✅ | |
| 8 | 加载模型 | `POST /api/v1/models` | ⚠️ | 需要 ChatModel 后端实例，当前无可用后端 |
| 9 | 删除模型档案 | `DELETE /api/v1/models/{name}` | ✅ | 删除配置档案并卸载运行时模型 |
| 10 | 单次推理 | `POST /api/v1/models/{name}/predict` | ⚠️ | 需要 ChatModel，当前无可用后端 |
| 11 | 基准测试 | `POST /api/v1/benchmark/{modelName}` | ⚠️ | 需要 ChatModel |
| 12 | 列出数据集 | `GET /api/v1/datasets` | ✅ | JSON 文件持久化 |
| 13 | 获取数据集 | `GET /api/v1/datasets/{id}` | ✅ | |
| 14 | 创建数据集 | `POST /api/v1/datasets` | ✅ | 支持 JSON body |
| 15 | 更新数据集 | `PUT /api/v1/datasets/{id}` | ✅ | |
| 16 | 删除数据集 | `DELETE /api/v1/datasets/{id}` | ✅ | |
| 17 | 查看条目(分页) | `GET /api/v1/datasets/{id}/entries` | ✅ | page + size 参数 |
| 18 | 导出数据集 | `GET /api/v1/datasets/{id}/export` | ✅ | JSON 导出 |
| 19 | 数据集计数 | `GET /api/v1/datasets/count` | ✅ | |
| 20 | 列出评测记录 | `GET /api/v1/evaluations` | ✅ | 内存存储 |
| 21 | 获取评测详情 | `GET /api/v1/evaluations/{id}` | ✅ | |
| 22 | 运行评测 | `POST /api/v1/evaluations` | ⚠️ | 需要运行中的模型进行推理 |
| 23 | 对比评测 | `GET /api/v1/evaluations/compare?ids=` | ✅ | 多跑对比 + 增量 + 最佳跑 |
| 24 | NLP 任务列表 | `GET /api/v1/nlp/tasks` | ✅ | 6 种内置任务 (NLPTaskService) |
| 25 | 文本分类 | `POST /api/v1/nlp/classify` | ⚠️ | 接口就绪; 需模型 |
| 26 | 情感分析 | `POST /api/v1/nlp/sentiment` | ⚠️ | 接口就绪; 需模型 |
| 27 | 文本摘要 | `POST /api/v1/nlp/summarize` | ⚠️ | 接口就绪; 需模型 |
| 28 | 命名实体识别 | `POST /api/v1/nlp/ner` | ⚠️ | 接口就绪; 需模型 |
| 29 | 问答 | `POST /api/v1/nlp/qa` | ⚠️ | 接口就绪; 需模型 |
| 30 | 翻译 | `POST /api/v1/nlp/translate` | ⚠️ | 接口就绪; 需模型 |
| 31 | Swagger UI | `/swagger-ui.html` | ✅ | springdoc-openapi |
| 32 | Actuator 指标 | `/actuator/prometheus` | ✅ | Micrometer + Prometheus |
| 33 | CORS | 全局 | ✅ | 允许 localhost:* |
| 34 | 全局异常处理 | 全局 | ✅ | GlobalExceptionHandler |
| 35 | 观测追踪 | 全局 | ✅ | Micrometer Tracing + OTel OTLP |
| 36 | 模型能力元数据 | `GET /api/v1/models/capabilities` | ✅ | 返回模型类型与标准协议白名单 |
| 37 | 运行时模型列表 | `GET /api/v1/models/runtime` | ✅ | 仅返回已加载到运行时的模型 |
| 38 | 激活模型 | `POST /api/v1/models/{name}/activate` | ⚠️ | 仅 CHAT 模型可激活到 ChatModel 运行时 |
| 39 | 卸载运行时模型 | `POST /api/v1/models/{name}/unload` | ✅ | 仅卸载运行时，不删除模型档案 |
| 40 | 测试模型 | `POST /api/v1/models/{name}/test` | ⚠️ | CHAT 走标准 ChatModel；向量/排序暂返回配置态说明 |
| 41 | 官方供应商预设 | `GET /api/v1/models/capabilities` | ✅ | OpenAI/Anthropic/Gemini/Ollama/DeepSeek/Qwen/Cohere/Jina/Custom |

### 2.2 前端页面

| # | 页面 | 路由 | 状态 | 功能 |
|---|------|------|------|------|
| 1 | Dashboard | `/` | ✅ | 模型/数据集/评测计数卡片，快捷导航 |
| 2 | 模型管理 | `/models` | ✅ | 大语言模型/向量模型/排序模型档案 CRUD、激活、测试 |
| 3 | 数据集管理 | `/datasets` | ✅ | 创建(JSON上传) / 列表 / 查看 / 导出 / 删除 |
| 4 | 评测管理 | `/evaluation` | ✅ | CHAT 模型+数据集选择 / 运行 / 历史 / 指标弹窗 |
| 5 | 对比分析 | `/compare` | ✅ | 多跑选择 / 指标表格+增量 / 柱状图 / 雷达图 |

### 2.2a 前端 API 客户端覆盖 (client.ts)

前端 `api/client.ts` 共导出 5 个 API 组、26 个方法。实际被页面使用的覆盖情况：

| API 组 | 方法 | 使用页面 | 状态 |
|--------|------|----------|------|
| `modelsApi.list` | GET | Dashboard, Evaluation | ✅ 已连线 |
| `modelsApi.get` | GET | — | ❌ 未使用 |
| `modelsApi.create` | POST | Models | ✅ 已连线 |
| `modelsApi.delete` | DELETE | Models | ✅ 已连线 |
| `modelsApi.activate` | POST | Models | ✅ 已连线 |
| `modelsApi.unload` | POST | — | ❌ 未使用 |
| `modelsApi.capabilities` | GET | Models | ✅ 已连线 |
| `modelsApi.test` | POST | Models | ✅ 已连线 |
| `modelsApi.predict` | POST | — | ❌ 未使用 (无 Predict 页面) |
| `modelsApi.benchmark` | POST | — | ❌ 未使用 |
| `datasetsApi.list` | GET | Dashboard, Datasets, Evaluation | ✅ 已连线 |
| `datasetsApi.get` | GET | — | ❌ 未使用 |
| `datasetsApi.create` | POST | Datasets | ✅ 已连线 |
| `datasetsApi.update` | PUT | — | ❌ 未使用 |
| `datasetsApi.delete` | DELETE | Datasets | ✅ 已连线 |
| `datasetsApi.entries` | GET | Datasets (模态框) | ✅ 已连线 |
| `datasetsApi.exportJson` | GET | Datasets (导出按钮) | ✅ 已连线 |
| `datasetsApi.count` | GET | — | ❌ 未使用 |
| `evaluationsApi.list` | GET | Dashboard, Evaluation, Compare | ✅ 已连线 |
| `evaluationsApi.get` | GET | — | ❌ 未使用 |
| `evaluationsApi.run` | POST | Evaluation | ✅ 已连线 |
| `evaluationsApi.compare` | GET | Compare | ✅ 已连线 |
| `nlpApi.tasks` | GET | — | ❌ 未使用 |
| `nlpApi.classify` | POST | — | ❌ 未使用 |
| `nlpApi.sentiment` | POST | — | ❌ 未使用 |
| `nlpApi.summarize` | POST | — | ❌ 未使用 |
| `nlpApi.ner` | POST | — | ❌ 未使用 |
| `nlpApi.qa` | POST | — | ❌ 未使用 |
| `nlpApi.translate` | POST | — | ❌ 未使用 |
| `healthApi.check` | GET | — | ❌ 未使用 |

**结论**：前端模型管理已使用 capabilities/create/delete/activate/test；6 个 NLP 任务 API + Predict/Benchmark/Health 仍已就绪但**无对应 UI**。

### 2.3 领域模型 (xnlp-core)

| # | 模块 | 类 | 状态 |
|---|------|-----|------|
| 1 | 评测模型 | `NLPTaskType` (enum) | ✅ | 6 种任务类型 |
| 2 | 评测模型 | `EvaluationEntry` | ✅ | input + expectedOutput |
| 3 | 评测模型 | `EvaluationDataset` | ✅ | 名称/描述/类型/条目列表 |
| 4 | 评测模型 | `EvaluationMetrics` | ✅ | 分类/NER/QA/ROUGE/BLEU 指标 |
| 5 | 评测模型 | `EvaluationRun` | ✅ | 运行元数据+指标+状态 |
| 6 | 评测模型 | `CompareResult` | ✅ | 多跑指标值+增量+最佳跑 |
| 7 | 推理模型 | `PredictRequest` / `PredictResponse` | ✅ | |
| 8 | 推理模型 | `ModelInfo` | ✅ | 名称/版本/后端/设备/状态 |
| 9 | 推理模型 | `BenchmarkResult` | ✅ | |
| 10 | 配置模型 | `AppConfig` / `ModelConfig` / `ModelType` / `ModelProtocol` / `ModelSource` / `ServerConfig` | ✅ | 标准模型类型、协议白名单与官方/自定义来源 |
| 11 | 管线 SPI | `ProcessingPipeline` | ✅ | |
| 12 | 管线 | `PipelineManager` | ✅ | 优先级排序/执行 |
| 13 | 管线 | `TextNormalizerPipeline` | ✅ | 默认文本规范化 |
| 14 | 注册中心 | `ModelRegistry` | ✅ | ChatModel 管理/推理/管线 |
| 15 | 错误 | 6 个自定义异常类 | ✅ | |

### 2.4 后端服务

| # | 服务 | 状态 | 说明 |
|---|------|------|------|
| 1 | `DatasetService` | ✅ | JSON 文件 CRUD (`data/datasets/`) |
| 2 | `MetricsCalculator` | ✅ | F1/ROUGE/BLEU/精确匹配/NER F1 |
| 3 | `EvaluationService` | ✅ | 评测编排 + 多跑对比 |
| 4 | `NLPTaskService` | ✅ | 6 种任务 LLM 提示词构造 |
| 5 | `ModelService` | ✅ | 模型注册中心包装 |
| 5a | `ModelCatalogService` | ✅ | 模型调用配置档案 JSON 持久化 (`data/models/`) |
| 6 | `InferenceService` | ✅ | 推理编排 |
| 7 | `BenchmarkService` | ✅ | 基准测试 |
| 8 | `MetricsService` | ✅ | Micrometer 自定义指标注册 |
| 9 | `ModelInitializer` | ✅ | 启动时自动加载模型 |

### 2.5 可观测性

| # | 能力 | 技术 | 状态 |
|---|------|------|------|
| 1 | 指标 | Micrometer -> Prometheus `/actuator/prometheus` | ✅ |
| 2 | 追踪 | Micrometer Tracing -> OTel OTLP | ✅ |
| 3 | 日志 | Logback + logstash-encoder | ✅ |

### 2.6 单元测试覆盖

| # | 测试类 | 模块 | 测试数 | 状态 |
|---|--------|------|--------|------|
| 1 | `XNLPExceptionTest` | xnlp-core | ~6 | ✅ 通过 |
| 2 | `PredictRequestTest` | xnlp-core | ~6 | ✅ 通过 |
| 3 | `PipelineManagerTest` | xnlp-core | 5 | ✅ 通过 |
| 4 | `ModelRegistryTest` | xnlp-core | ~5 | ⚠️ 依赖 Mock ChatModel |
| 5 | `XNLPApplicationSmokeTest` | xnlp-server | 8 | ❌ `@Disabled` — 需 ChatModel 后端 |

**测试运行命令**：
```bash
mvn test -Dmaven.repo.local=/tmp/m2              # 全部测试
mvn test -Dmaven.repo.local=/tmp/m2 -pl xnlp-core # 仅 core
```

---

## 三、已知缺口 & 待实现

### 3.1 阻塞性问题

| 问题 | 严重度 | 说明 |
|------|--------|------|
| **无可用模型后端** | 🔴 Critical | Ollama/OpenAI Starters 因与 Spring Boot 4.1 不兼容被移除。`ModelRegistry` 依赖 `ChatModel` 接口但上下文中无实例。所有 `/predict` 端点、评测运行、NLP 任务实际执行均无法工作 |
| **SmokeTest 被禁用** | 🟡 Medium | `XNLPApplicationSmokeTest` 整体 `@Disabled`，8 个集成测试全部跳过 |

### 3.2 后端待实现

| 功能 | 优先级 | 说明 |
|------|--------|------|
| 模型后端对接 | P0 | 对接 ChatModel 实现：Ollama / OpenAI API / Mock 后端 |
| 批量推理端点 | P1 | `POST /api/v1/models/{name}/batch-predict` — 当前缺失 |
| 异步评测 | P1 | 评测耗时长，需异步+进度+取消 |
| 评测持久化 | P1 | 当前评测结果仅在内存，重启丢失 |
| 模型热加载/卸载 | P1 | 从应用配置动态加载模型 |
| 用户认证/授权 | P2 | API Key 或 OAuth2 |
| SDK 完善 | P2 | `xnlp-client` Java SDK |
| CLI 完善 | P2 | `xnlp-cli` 子命令 |

### 3.3 前端待实现

| 功能 | 优先级 | 说明 |
|------|--------|------|
| NLP Playground 页面 | P1 | 新建 `/nlp` 路由，集成 6 种 NLP 任务交互 |
| 评测进度展示 | P1 | 实时进度条/日志 |
| 数据集条目分页 | P1 | 后端分页就绪; 前端 `Datasets.tsx` 硬编码 `page=0,size=50` |
| JSON 文件拖拽导入 | P1 | 拖拽上传 JSON 创建数据集 |
| 搜索/筛选 | P2 | 数据集和评测记录搜索 |
| 暗色主题 | P2 | |
| 国际化 | P3 | |

### 3.4 已知前端 Bugs

| # | 位置 | 问题 | 严重度 |
|---|------|------|--------|
| 1 | `Compare.tsx:161` | Radar 图: `radarData()` 生成 `accuracy/f1Macro/rouge1/bleu` 四个维度字段，但所有 `<Radar>` 硬编码 `dataKey="accuracy"`，每模型仅展示单维单点 | 🟡 Medium |
| 2 | `Datasets.tsx` | 条目查看模态框无分页控件，条目 > 50 时不可见 | 🟡 Medium |
| 3 | `Datasets.tsx` | JSON 校验 `Array.isArray` 后字符串判断 `'objects'` 有误(应为 `'object'`) | 🟢 Low |

### 3.5 基础设施

| 功能 | 优先级 | 说明 |
|------|--------|------|
| Docker Compose | P1 | 后端+前端+Ollama 一键启动 |
| K8s 部署清单 | P1 | `docker/` 目录已预留 |
| CI/CD | P2 | GitHub Actions |
| 集成测试 | P2 | `tests/` 目录已预留 |
| 数据库替代 JSON 文件 | P2 | PostgreSQL/H2 |
| Maven 离线构建 | P2 | 需 `-Dmaven.repo.local=/tmp/m2` 绕过沙箱网络限制 |

---

## 四、API 规格

### 4.1 健康探针

```yaml
GET /health
    响应:
      app: "xnlp-server"
      status: "UP"
      probes:
        liveness:   { status: "UP" }
        readiness:  { status: "READY", loaded_models: N }
        startup:    { status: "STARTED", startup_complete: true }

GET /livez  (alias: GET /healthz)
    200: { status: "UP", timestamp: "..." }

GET /readyz
    200: { status: "READY", loaded_models: N }
    503: { status: "NOT_READY", reason: "..." }

GET /startupz
    200: { status: "STARTED", startup_complete: true }
    503: { status: "STARTING", startup_complete: false }

GET /ok
    200: (空 body)
```

### 4.2 模型管理

```yaml
GET /api/v1/models
    响应: [ ModelInfo, ... ]  (优先返回配置档案; 无档案时返回运行时模型)
    ModelInfo: { name, type, protocol, provider, modelName, baseUrl, apiKeySet,
                 version, backend, device, status, loadedAt, metadata }

GET /api/v1/models/capabilities
    响应: { types: [CHAT, EMBEDDING, RERANKING], protocolsByType: {...}, requiredFields: {...}, providers: [...] }
    providers: [{ id, name, source: OFFICIAL|CUSTOM, baseUrl, models: [{ name, type, protocol, maxInputLength, maxOutputLength }] }]

GET /api/v1/models/runtime
    响应: [ ModelInfo, ... ]  (仅已加载到 ModelRegistry 的运行时模型)

GET /api/v1/models/{name}
    200: ModelInfo
    404: { error: "...", ... }

POST /api/v1/models
    请求: { name, type, source?, protocol, provider, modelName, baseUrl?, apiKey?,
            maxInputLength?, maxOutputLength?, options? }
    校验: protocol 必须属于 type 的标准协议白名单; 远程协议必须提供 baseUrl
    响应: ModelInfo (apiKey 不回显，仅返回 apiKeySet)

POST /api/v1/models/{name}/activate
    响应: ModelInfo
    说明: 仅 CHAT 模型可加载到 Spring AI ChatModel 运行时；EMBEDDING/RERANKING 先只管理标准档案

POST /api/v1/models/{name}/unload
    响应: 200
    说明: 仅卸载运行时模型，不删除档案

POST /api/v1/models/{name}/test
    请求: { input?, query?, documents?: [string] }
    响应: { type, protocol, model?, output?, elapsedSeconds?, status?, message? }

POST /api/v1/models/{name}/predict
    请求: { modelName?, text }
    响应: { text, model, elapsedSeconds }
    ⚠️ 需要标准 Spring AI ChatModel 运行时实例

DELETE /api/v1/models/{name}
    响应: 204
    说明: 删除模型档案并卸载运行时模型
```

支持的标准协议白名单：

| Type | Protocols |
|------|-----------|
| CHAT | SPRING_AI_CHAT, OPENAI_CHAT_COMPLETIONS, OLLAMA_CHAT, ANTHROPIC_MESSAGES, GOOGLE_GEMINI_GENERATE_CONTENT |
| EMBEDDING | SPRING_AI_EMBEDDING, OPENAI_EMBEDDINGS, OLLAMA_EMBEDDINGS, GOOGLE_GEMINI_EMBEDDING |
| RERANKING | COHERE_RERANK, JINA_RERANK |

内置官方供应商预设：OpenAI、Anthropic、Google Gemini、Ollama、DeepSeek、Alibaba Qwen、Cohere、Jina AI。另保留 Custom 入口用于自定义标准兼容 endpoint。

### 4.3 数据集管理

```yaml
GET /api/v1/datasets
    响应: [ DatasetSummary ]  (不含 entries 数组以节省带宽)

GET /api/v1/datasets/count
    响应: { count: number }

GET /api/v1/datasets/{id}
    响应: Dataset (含完整 entries 数组)

POST /api/v1/datasets
    请求: { name, description?, taskType?, entries: [{ input, expectedOutput }] }
    响应: Dataset
    存储: data/datasets/{id}.json  (JSON 文件持久化)

PUT /api/v1/datasets/{id}
    请求: { name, description?, taskType?, entries? }
    响应: Dataset

GET /api/v1/datasets/{id}/entries?page=0&size=50
    响应: { entries: [...], page: 0, size: 50, total: N }

GET /api/v1/datasets/{id}/export
    响应: JSON string (完整数据集，含所有字段)

DELETE /api/v1/datasets/{id}
    响应: 204
```

### 4.4 评测引擎

```yaml
POST /api/v1/evaluations
    请求: { modelName, datasetId, taskType? }
    响应: EvaluationRun
    状态流转: running -> completed | failed
    逻辑: 遍历 dataset.entries，逐条构建 prompt -> registry.predict() -> 对比 expectedOutput -> 计算指标

GET /api/v1/evaluations
    响应: [ EvaluationRun ]  (按 createdAt 倒序)

GET /api/v1/evaluations/{id}
    200: EvaluationRun (含 metrics 或 errorMessage)

GET /api/v1/evaluations/compare?ids=id1&ids=id2&ids=id3
    响应:
      runs: [ Run, ... ]
      metricValues: { accuracy: [0.85, 0.91, 0.89], f1Macro: [...], ... }
      deltas: { accuracy: [0, +0.06, +0.04], ... }   (相对第一跑的增量)
      bestRunId: "id2"
      summary: "Comparison of N evaluation runs."
    指标名: accuracy, f1Macro, precisionMacro, recallMacro, rouge1, rouge2, rougeL,
            bleu, exactMatch, f1Score, entityF1
```

### 4.5 NLP 任务

```yaml
GET /api/v1/nlp/tasks
    响应: [{ task: "CLASSIFY", description: "...", parameters: [...], ...] }, ...]
    内置 6 种: TEXT_CLASSIFICATION, SENTIMENT_ANALYSIS, SUMMARIZATION,
               NAMED_ENTITY_RECOGNITION, QUESTION_ANSWERING, TRANSLATION

POST /api/v1/nlp/classify
    请求: { modelName, text, categories: [string] }
    响应: { label: "...", model: "...", elapsed_seconds: 0.0 }

POST /api/v1/nlp/sentiment
    请求: { modelName, text }
    响应: { label: "positive"|"negative"|"neutral", ... }

POST /api/v1/nlp/summarize
    请求: { modelName, text, max_length? }
    响应: { summary, ... }

POST /api/v1/nlp/ner
    请求: { modelName, text }
    响应: { entities: [...], ... }

POST /api/v1/nlp/qa
    请求: { modelName, context, question }
    响应: { answer, ... }

POST /api/v1/nlp/translate
    请求: { modelName, text, source_language? }
    响应: { translation, ... }

⚠️ 以上 6 个 NLP POST 端点均调用 NLPTaskService，其内部构造 LLM prompt 然后调用
   registry.predict() — 因无 ChatModel，当前也返回 mock 响应。
```

### 4.6 基准测试

```yaml
POST /api/v1/benchmark/{modelName}
    请求: { requests: 5, concurrency: 2, text: "..." }
    响应: { model, totalRequests, successful, latencyAvgMs, throughputRps, ... }
```

---

## 五、评测指标说明

`MetricsCalculator` 位于 `xnlp-server/.../service/MetricsCalculator.java`，支持 per-class F1、LCS-based ROUGE 和 token-set BLEU。无外部依赖。

| 任务类型 | 指标 |
|----------|------|
| TEXT_CLASSIFICATION | accuracy, precision_macro, recall_macro, f1_macro |
| SENTIMENT_ANALYSIS | accuracy, f1_macro |
| SUMMARIZATION | rouge-1, rouge-2, rouge-L |
| TRANSLATION | bleu, rouge-1, rouge-2, rouge-L |
| QUESTION_ANSWERING | exact_match, f1_score |
| NAMED_ENTITY_RECOGNITION | entity_f1 (strict entity-level match) |

---

## 六、核心数据模型

### 6.1 评测相关

**EvaluationDataset**
```java
id: String (UUID)
name: String
description: String?
taskType: NLPTaskType?  (enum, 6 种任务)
entries: List<EvaluationEntry>
entryCount: int
createdAt: Instant
updatedAt: Instant
```

**EvaluationEntry**
```java
input: String
expectedOutput: String
```

**EvaluationRun**
```java
id: String (UUID)
modelName: String
datasetId: String
datasetName: String  (冗余字段，避免前端额外请求)
taskType: NLPTaskType
status: "running" | "completed" | "failed"
metrics: EvaluationMetrics?
errorMessage: String?
createdAt: Instant
completedAt: Instant?
elapsedSeconds: double
```

**EvaluationMetrics**
```java
accuracy: double
f1Macro, precisionMacro, recallMacro: double?
rouge1, rouge2, rougeL: double?
bleu: double?
exactMatch, f1Score: double?
entityF1: double? (+ truePositive, falsePositive, falseNegative)
totalEntries: int
correctEntries: int
perClassF1: Map<String, Double>
```

**CompareResult**
```java
runs: List<EvaluationRun>
metricValues: Map<String, List<Double>>   // key=指标名, value=每跑的指标值
deltas: Map<String, List<Double>>          // 相对第一跑的增量, 第一跑 delta=0
bestRunId: String
summary: String
```

### 6.2 其他关键模型

- `PredictRequest`: text + modelName + maxLength
- `PredictResponse`: text + model + elapsedSeconds
- `ModelConfig`: 模型调用档案（name/type/protocol/provider/modelName/baseUrl/apiKey/options）
- `ModelType`: CHAT / EMBEDDING / RERANKING
- `ModelProtocol`: 标准协议白名单，不允许任意自定义 HTTP 接入
- `ModelSource`: OFFICIAL / CUSTOM
- `ModelInfo`: 脱敏模型元数据（apiKey 不回显，仅 apiKeySet）
- `BenchmarkResult`: 基准测试统计

---

## 七、前端组件树

```
App.tsx
└── Layout.tsx (侧边导航 + Header)
    ├── Dashboard.tsx    /          概览卡片 + 快捷导航
    ├── Models.tsx       /models    模型档案 CRUD + 激活 + 测试
    ├── Datasets.tsx     /datasets  CRUD + JSON 上传
    ├── Evaluation.tsx   /evaluation 运行 + 历史 + 指标弹窗
    └── Compare.tsx      /compare   多选 + 指标表格 + 柱状图 + 雷达图

依赖:
  api/client.ts          API 客户端 (所有 REST 调用)
  lucide-react           图标库
  recharts               图表库
  react-router-dom       路由
  tailwindcss            样式
```

### 7.1 页面功能详解

**Dashboard** (`Dashboard.tsx`):
- 3 张统计卡片 (Loaded Models / Datasets / Evaluations 计数)
- 模型列表 (名称+后端+状态)
- 最近评测记录 (模型名+数据集名+状态+准确率)
- 空状态提示 (当无数据时)

**Models** (`Models.tsx`):
- 支持大语言模型、向量模型、排序模型三类档案
- Provider -> Model 两级选择，官方预设自动填充 type/protocol/baseUrl/maxInput/maxOutput
- 表单维护标准调用字段: type/source/protocol/provider/modelName/baseUrl/apiKey/maxInput/maxOutput
- API Key 只写入后端，列表仅显示是否已配置
- 列表标记 OFFICIAL/CUSTOM 来源
- 支持激活 CHAT 模型到 Spring AI ChatModel 运行时，以及统一测试入口
- EMBEDDING/RERANKING 当前先维护标准档案，运行时测试需后续接入 Spring AI 标准接口

**Datasets** (`Datasets.tsx`):
- 创建表单: JSON 文本区输入条目数组，支持选择任务类型
- 表格列表: 名称(可点击查看)/任务类型/条目数/创建时间/操作
- 操作: 导出 JSON (浏览器下载), 删除 (确认弹窗)
- 详情模态框: 展示每个条目的 input + expectedOutput

**Evaluation** (`Evaluation.tsx`):
- 运行面板: 下拉选择模型 + 数据集 (自动带出 taskType)
- 运行历史表格: 模型/数据集/任务/状态/Acc/F1/耗时/详情
- 指标详情弹窗: 展示全部指标字段

**Compare** (`Compare.tsx`):
- 选择面板: 已完成评测的标签式多选
- 指标对比表格: 每个指标行展示各跑数值 + 增量箭头
- 柱状图 (Recharts BarChart): 多模型多指标分组柱状图
- 雷达图 (Recharts RadarChart): ⚠️ 当前仅展示 accuracy 维度 (已知 Bug)

---

## 八、环境配置 & 构建

### 8.1 application.yml 核心配置

```yaml
server.port: ${XNLP_PORT:8760}      # ⚠️ 配置值为 8760，当前实际运行在 8080
spring.application.name: xnlp-server

management.endpoints.web.exposure.include: health,info,metrics,prometheus
management.tracing.sampling.probability: 1.0
management.otlp.tracing.endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT}

xnlp.server.max-workers: 4
xnlp.server.request-timeout-seconds: 60
xnlp.server.max-batch-size: 32

xnlp.models:
  - name: ollama-default
    version: latest
    model-path: llama3
    backend: ollama
    device: cpu
    max-input-length: 4096
    max-output-length: 2048

logging.file.path: ${LOG_PATH:/var/log/xnlp}
```

### 8.2 Maven 构建

```bash
# 由于沙箱网络限制，必须使用本地 Maven 仓库
mvn clean verify -Dmaven.repo.local=/tmp/m2 -DskipTests
mvn test -Dmaven.repo.local=/tmp/m2 -pl xnlp-core
mvn spring-boot:run -pl xnlp-server -Dmaven.repo.local=/tmp/m2
```

### 8.3 启动服务

```bash
# 后端 (需要 escalated sandbox)
cd /Users/haoxiaolong/data/codex/X-NLP
mvn spring-boot:run -pl xnlp-server -Dmaven.repo.local=/tmp/m2 -DskipTests

# 前端 (需要 escalated sandbox)
cd /Users/haoxiaolong/data/codex/X-NLP/xnlp-frontend
npm run dev
```

### 8.4 Vite 开发代理

```typescript
// vite.config.ts
proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } }
```

### 8.5 前端技术栈版本

| 依赖 | 版本 |
|------|------|
| React | ^18.3.1 |
| React Router | ^6.26.0 |
| Recharts | ^2.12.0 |
| Lucide React | ^0.400.0 |
| Tailwind CSS | ^3.4.6 |
| Vite | ^5.3.4 |
| TypeScript | ^5.5.3 |

---

## 九、下一步 Vibe Coding 路线图

### Phase 1 — 打通模型推理 (P0, 预计 1-2天)
> 目标：让 `/predict` 和评测运行真正能用

**Task 1.1** — 实现 Mock ChatModel
- 新建 `xnlp-server/.../backend/MockChatModel.java`
- 实现 `org.springframework.ai.chat.model.ChatModel` 接口
- `call()`: 回显 prompt 前 100 字符 + "[mock]"
- 注册为 `@Bean` 到 Spring 容器

**Task 1.2** — 修复 `XNLPConfiguration.java`
- 在 `modelRegistry()` 中注入 `ChatModel` bean
- `registry.registerChatModel(modelName, chatModel)` 完成模型注册
- 移除 `ModelInitializer` 中 ollama-default 的错误日志

**Task 1.3** — 验证链路
- 启动服务后 `GET /api/v1/models` 返回含 `mock-chat` 的列表
- `POST /api/v1/models/mock-chat/predict` 返回 mock 响应
- `POST /api/v1/evaluations` 使用 mock 模型完成评测
- 启用 `XNLPApplicationSmokeTest` 并修复测试预期

### Phase 2 — 评测闭环 (P1, 预计 2-3天)
> 目标：评测流程可重复、可追溯

**Task 2.1** — 评测结果持久化
- `EvaluationService` 将 runs 写入 `data/evaluations/` JSON 文件
- 启动时从磁盘加载历史

**Task 2.2** — 异步评测
- `POST /api/v1/evaluations` 返回 202 + runId，后端异步执行
- `GET /api/v1/evaluations/{id}` 查询进度
- 可选：SSE 流式推送进度 `GET /api/v1/evaluations/{id}/stream`

**Task 2.3** — 评测批量对比
- 前端 Compare 页面支持按模型/数据集筛选
- 后端支持过滤参数 `?modelName=...&datasetName=...`

**Task 2.4** — 前端评测进度
- Evaluation 页面添加 run status 轮询 (setInterval 2s)
- 显示进度条 (当前条目/总条目)

### Phase 3 — 前端交互增强 (P1, 预计 2-3天)
> 目标：完善用户体验，补齐 NLP Playground

**Task 3.1** — NLP Playground 页面 (`/nlp`)
- 新建 `pages/Playground.tsx`
- 左侧: 模型选择 + 任务类型选择 (6 种) + 参数输入
- 右侧: 结果展示
- 历史记录 (session 内)

**Task 3.2** — 数据集拖拽导入
- 使用原生 Drag & Drop API
- 支持 `.json` 文件拖拽或点击上传
- 自动解析并填充创建表单

**Task 3.3** — 数据集条目分页
- `Datasets.tsx` 查看模态框添加分页控件
- 使用已有的 `page`/`size` 参数

**Task 3.4** — 修复 Compare 雷达图
- 重构雷达图: 每模型用不同 `dataKey` 或改为单模型多指标模式

### Phase 4 — 基础设施 (P2, 预计 2-3天)
> 目标：生产就绪的基础设施

**Task 4.1** — Docker Compose
- `docker-compose.yml`: xnlp-server + xnlp-frontend (nginx serve) + ollama
- 健康检查依赖

**Task 4.2** — 前端搜索/筛选
- Dashboard 添加搜索框 (模型/数据集/评测)
- 数据集列表可按任务类型筛选

**Task 4.3** — 内置评测数据集模板
- 预置 2-3 个标准数据集 JSON (sentiment-test, classify-test)
- 启动时自动导入 (或提供一键导入按钮)

**Task 4.4** — CI/CD
- GitHub Actions: `mvn verify` + 前端 `npm run build`
- Docker 镜像构建并推送到 registry

### Phase 5 — 企业级 (P3)
> 目标：生产级安全和可扩展性

**Task 5.1** — 用户认证 (API Key / OAuth2 + Spring Security)
**Task 5.2** — PostgreSQL 替代 JSON 文件持久化
**Task 5.3** — K8s Helm Chart
**Task 5.4** — 多租户数据隔离

---

## 十、技术决策 & 注意事项

### 10.1 Spring AI + Spring Boot 4.1 兼容性

- **问题**: `spring-ai-starter-model-ollama` 和 `spring-ai-starter-model-openai` 的 auto-config 引用 `RestClientAutoConfiguration`，该配置类在 SB 4.x 中被重组/移除
- **当前状态**: 两个 starter 已从 `xnlp-server/pom.xml` 移除
- **xnlp-core** 保留 `spring-ai-model:1.0.0`（显式版本）— `ModelRegistry` 只需 `ChatModel` 接口

### 10.2 Maven 本地仓库

- 沙箱环境有出站网络限制，必须使用预置本地仓库 `/tmp/m2`
- 所有 mvn 命令需追加 `-Dmaven.repo.local=/tmp/m2`

### 10.3 端口差异

- `application.yml` 声明 `8760`，`XNLPProperties.java` 中也配置 `8760`
- 实际运行在 **8080**（沙箱开放端口），Vite proxy 也指向 8080
- 若部署到非沙箱环境，需对齐端口

### 10.4 模型配置

- `application.yml` 中 `xnlp.models` 数组保留了一条 `ollama-default` 配置
- `ModelInitializer` 启动时尝试加载该模型，因无 ChatModel 实例会记录 error 日志
- Phase 1 完成后可移除该配置或将其改为 mock 模型名
