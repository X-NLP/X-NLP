## X-NLP MVP 功能列表 & 规格说明

> X-NLP 是一个 NLP 处理框架，目标是快速引入、组合、评测自然语言处理技术，并把处理过程和评测变化可视化。
> 版本：MVP 0.2.0 | 更新：2026-09-14

---

## 产品定位更新

### 核心定位

X-NLP 不是单纯的大模型调用平台。当前阶段的核心是 NLP 处理框架：管理可用模型资产，管理评测数据，组合 NLP 处理节点，展示每个节点对数据造成的重要变化，并对不同处理方案做效果评测和对比。

### 页面职责

| 页面 | 职责 | 当前要求 |
|---|---|---|
| Dashboard | 系统整体看板 | 查看系统整体信息，包括模型资产数量、NLP 能力数量、数据集数量、评测数量、最近评测状态 |
| Models | 模型资产配置 | 配置系统可用模型，包括大语言模型、嵌入模型、排序模型、分词模型、词性标注模型、NER 模型、句法分析模型、语义角色模型、分类模型等 |
| Datasets | 评测数据管理 | 管理数据集、样本、期望输出和元数据 |
| Evaluation | 效果评测 | 选择模型/数据集/任务运行评测，沉淀指标 |
| Compare | 评测对比 | 对比多次评测的指标变化 |
| Canvas | 处理过程画布 | 查看数据在各处理节点下的重要变化；已接入后端 pipeline trace |

### NLP 能力参考

NLP 当前处理能力参考 HanLP 常见功能体系，优先覆盖以下能力：

| 能力 | 说明 | MVP 状态 |
|---|---|---|
| 分词 | 粗粒度/细粒度 tokenization | 内置 demo runtime，真实模型 SPI 已预留 |
| 词性标注 | POS tagging | 内置 demo runtime，真实模型 SPI 已预留 |
| 命名实体识别 | 人名、机构、地名、时间等实体抽取 | 内置 demo runtime，真实模型 SPI 已预留 |
| 依存句法分析 | 输出词之间的依存关系 | 内置 demo runtime，真实模型 SPI 已预留 |
| 语义角色标注 | 谓词和论元角色识别 | 内置 demo runtime，真实模型 SPI 已预留 |
| 文本分类 | 单标签/多标签分类 | 内置 demo runtime，评测类型已有 |
| 情感分析 | 情感极性分类 | 内置 demo runtime，评测类型已有 |
| 文本相似度/语义检索 | 依赖嵌入模型和向量检索 | 内置 demo runtime，真实向量检索链路待实现 |
| 排序 | query-document rerank | 模型资产类型已有，标准协议测试已有 |

### 大语言模型的定位

大语言模型当前只作为一种可配置模型资产存在。MVP 不把它作为 NLP 主流程的默认实现，也不把所有 NLP 任务都强制转成 prompt 调用。后续如果需要使用大模型，可以作为独立处理节点、评测 baseline、数据增强或人工标注辅助能力接入，但必须在规格中明确用途。

### 标准协议原则

所有外部模型必须通过标准协议或明确的本地 SPI 接入：

- 大语言模型：Spring AI Chat、OpenAI-compatible Chat Completions、Ollama Chat、Anthropic Messages、Gemini generateContent。
- 嵌入模型：Spring AI Embedding、OpenAI-compatible Embeddings、Ollama Embeddings、Gemini Embedding。
- 排序模型：Cohere Rerank、Jina Rerank。
- NLP 组件：HanLP 风格协议预留、本地 Java SPI 预留。

不接受临时拼接、不可复用、不可评测的非规范模型对接。

## 一、架构概览

```
xnlp-core        共享领域模型、SPI、管线、模型注册中心
xnlp-server      Spring Boot 4.1.0 REST API 服务 (端口 8760)
xnlp-client       Java SDK (HTTP client)
xnlp-cli          Picocli 命令行工具
xnlp-frontend     React 18 + Vite 5 + Tailwind 3 前端 (端口 5173, 已代理到后端)
```

```
依赖图: xnlp-cli -> xnlp-client -> xnlp-core <- xnlp-server
```

**当前运行状态**：后端 Java (8760) + 前端 Vite (5173) 均已启动并可交互。

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
| 8 | 加载模型 | `POST /api/v1/models` | ⚠️ | 需要配置 Spring AI ChatModel（OpenAI API Key 或 Ollama） |
| 9 | 删除模型档案 | `DELETE /api/v1/models/{name}` | ✅ | 删除配置档案并卸载运行时模型 |
| 10 | 单次推理 | `POST /api/v1/models/{name}/predict` | ⚠️ | 需要配置 Spring AI ChatModel（OpenAI API Key 或 Ollama） |
| 11 | 基准测试 | `POST /api/v1/benchmark/{modelName}` | ⚠️ | 需要 ChatModel |
| 12 | 列出数据集 | `GET /api/v1/datasets` | ✅ | Spring JDBC + 当前数据库 profile |
| 13 | 获取数据集 | `GET /api/v1/datasets/{id}` | ✅ | |
| 14 | 创建数据集 | `POST /api/v1/datasets` | ✅ | 支持 JSON body |
| 15 | 更新数据集 | `PUT /api/v1/datasets/{id}` | ✅ | |
| 16 | 删除数据集 | `DELETE /api/v1/datasets/{id}` | ✅ | |
| 17 | 查看条目(分页) | `GET /api/v1/datasets/{id}/entries` | ✅ | page + size 参数 |
| 18 | 导出数据集 | `GET /api/v1/datasets/{id}/export` | ✅ | JSON 导出 |
| 19 | 数据集计数 | `GET /api/v1/datasets/count` | ✅ | |
| 20 | 列出评测记录 | `GET /api/v1/evaluations` | ✅ | Spring JDBC 持久化（memory profile 除外） |
| 21 | 获取评测详情 | `GET /api/v1/evaluations/{id}` | ✅ | |
| 22 | 运行评测 | `POST /api/v1/evaluations` | ✅ | 返回 `202 Accepted`，异步队列执行并持久化进度；真实推理仍需 provider |
| 23 | 对比评测 | `GET /api/v1/evaluations/compare?ids=` | ✅ | 多跑对比 + 增量 + 最佳跑 |
| 24 | NLP 能力列表 | `GET /api/v1/nlp/tasks` | ✅ | 返回 HanLP 风格能力目录 |
| 25 | 文本分类 | `POST /api/v1/nlp/classify` | ⚠️ | 历史 prompt 实现；后续改为分类模型/流水线节点调用 |
| 26 | 情感分析 | `POST /api/v1/nlp/sentiment` | ⚠️ | 历史 prompt 实现；后续改为专用分类模型或规则节点 |
| 27 | 文本摘要 | `POST /api/v1/nlp/summarize` | ⚠️ | 历史 prompt 实现；当前不是主线 NLP 能力 |
| 28 | 命名实体识别 | `POST /api/v1/nlp/ner` | ⚠️ | 历史 prompt 实现；后续改为 NER 模型/流水线节点调用 |
| 29 | 问答 | `POST /api/v1/nlp/qa` | ⚠️ | 历史 prompt 实现；当前不是主线 NLP 能力 |
| 30 | 翻译 | `POST /api/v1/nlp/translate` | ⚠️ | 历史 prompt 实现；当前不是主线 NLP 能力 |
| 31 | Swagger UI | `/swagger-ui.html` | ✅ | springdoc-openapi |
| 32 | Actuator 指标 | `/actuator/prometheus` | ✅ | Micrometer + Prometheus |
| 33 | CORS | 全局 | ✅ | 允许 localhost:* |
| 34 | 全局异常处理 | 全局 | ✅ | GlobalExceptionHandler |
| 35 | 观测追踪 | 全局 | ✅ | Micrometer Tracing + OTel OTLP |
| 36 | 模型能力元数据 | `GET /api/v1/models/capabilities` | ✅ | 返回模型类型与标准协议白名单 |
| 37 | 运行时模型列表 | `GET /api/v1/models/runtime` | ✅ | 仅返回已加载到运行时的模型 |
| 38 | 激活模型 | `POST /api/v1/models/{name}/activate` | ⚠️ | 仅 CHAT 模型可激活到 ChatModel 运行时 |
| 39 | 卸载运行时模型 | `POST /api/v1/models/{name}/unload` | ✅ | 仅卸载运行时，不删除模型档案 |
| 40 | 测试模型 | `POST /api/v1/models/{name}/test` | ⚠️ | LLM/Embedding/Rerank 走标准协议；NLP 组件当前返回配置态说明 |
| 41 | 官方供应商预设 | `GET /api/v1/models/capabilities` | ✅ | OpenAI/Anthropic/Gemini/Ollama/DeepSeek/Qwen/Cohere/Jina/HanLP/Local/Custom |

### 2.2 前端页面

| # | 页面 | 路由 | 状态 | 功能 |
|---|------|------|------|------|
| 1 | Dashboard | `/` | ✅ | 系统整体看板：模型资产、NLP 能力、数据集、评测概览 |
| 2 | AI Assistant | `/assistant` | ✅ | 基于 Spring AI ChatModel 的工程 Copilot，对话上下文与 provider 状态 |
| 3 | 模型管理 | `/models` | ✅ | 大语言模型/嵌入/排序/分词/POS/NER/句法/SRL/分类模型资产 CRUD、激活、测试 |
| 4 | NLP Workbench | `/nlp` | ✅ | TOK/POS/NER/DEP/SDP/SRL/CON/AMR/关键词/抽取式摘要/生成式摘要/纠错/分类/情感/STS/TST 交互式分析 |
| 5 | 数据集管理 | `/datasets` | ✅ | 创建(JSON上传/拖拽) / 列表 / 查看 / 条目分页 / 导出 / 删除 |
| 6 | 评测管理 | `/evaluation` | ✅ | 模型+数据集选择 / 运行 / 历史 / 指标弹窗 |
| 7 | Pipeline Canvas | `/canvas` | ✅ | 以节点视图组合数据集、模型与评测步骤；支持执行后端 trace 并查看节点级输入/输出/耗时 |
| 8 | 对比分析 | `/compare` | ✅ | 多跑选择 / 指标表格+增量 / 柱状图 / 雷达图 |

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

**结论**：前端模型管理已使用 capabilities/create/delete/activate/test；NLP Workbench 已通过统一 `nlpApi.analyze` 覆盖 HanLP 风格任务目录；AI Assistant 已接入 `aiApi.status/chat`。Predict、Benchmark、历史兼容的 6 个 NLP API 与 Health API 仍保留为 SDK/后续页面的可用接口。

### 2.3 领域模型 (xnlp-core)

| # | 模块 | 类 | 状态 |
|---|------|-----|------|
| 1 | 评测模型 | `NLPTaskType` (enum) | ✅ | 保留评测任务类型；HanLP 风格运行能力由 `NlpComponent`/`CapabilityRegistry` 扩展 |
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
| 1 | `DatasetService` | ✅ | Spring JDBC CRUD，通过 MySQL/PostgreSQL/H2 profile 切换 |
| 2 | `MetricsCalculator` | ✅ | F1/ROUGE/BLEU/精确匹配/NER F1 |
| 3 | `EvaluationService` | ✅ | 评测编排 + 多跑对比 |
| 4 | `NLPTaskService` | ⚠️ | 历史 prompt 任务实现；后续应重构为 HanLP 风格 pipeline 能力服务 |
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
| 1 | `XNLPExceptionTest` | xnlp-core | 4 | ✅ 通过 |
| 2 | `PredictRequestTest` | xnlp-core | 4 | ✅ 通过 |
| 3 | `PipelineManagerTest` | xnlp-core | 5 | ✅ 通过 |
| 4 | `ModelRegistryTest` | xnlp-core | 9 | ✅ 通过 |
| 5 | `XNLPApplicationSmokeTest` | xnlp-server | 14 | ✅ H2 + 内存 ChatModel，覆盖健康、模型、Spring AI、NLP、评测 |

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
| **模型后端依赖运行时配置** | 🟡 Medium | Spring AI 2.0.1 已接入 OpenAI/Ollama starters；未设置 `OPENAI_API_KEY` 且未启动 Ollama 时，应用仍可启动，但需要先配置 provider 才能执行真实推理 |
| **真实 AI provider 依赖运行时配置** | 🟡 Medium | Smoke test 已使用内存 ChatModel，不依赖外部服务；生产环境仍需配置 `OPENAI_API_KEY` 或启动 Ollama 才能执行真实推理 |

### 3.2 后端待实现

| 功能 | 优先级 | 说明 |
|------|--------|------|
| 模型后端对接 | P0 | ✅ 统一接入 Spring AI `ChatModel`，支持 OpenAI / Ollama provider 切换；测试使用内存 stub |
| 批量推理端点 | P1 | ✅ `POST /api/v1/models/{name}/batch-predict`，有界批量、顺序执行、逐项错误返回 |
| 异步评测 | P1 | ✅ 有界线程池、进度持久化、取消接口和状态过滤已实现 |
| 评测持久化 | P1 | ✅ 默认使用 Spring JDBC 写入 evaluation_runs；`memory` profile 仅用于临时实验 |
| 模型热加载/卸载 | P1 | 从应用配置动态加载模型 |
| 用户认证/授权 | P2 | API Key 或 OAuth2 |
| SDK 完善 | P2 | `xnlp-client` Java SDK |
| CLI 完善 | P2 | `xnlp-cli` 子命令 |

### 3.3 前端待实现

| 功能 | 优先级 | 说明 |
|------|--------|------|
| NLP Playground 页面 | P1 | ✅ 已实现 `/nlp` 工作台与内置能力交互 |
| 评测进度展示 | P1 | ✅ 轮询进度条、状态过滤与取消按钮；SSE/运行日志后续增强 |
| 数据集条目分页 | P1 | ✅ 后端分页 + 前端分页控件，默认每页 20 条 |
| JSON 文件拖拽导入 | P1 | ✅ 支持 `.json` 文件选择与拖拽上传创建数据集 |
| 搜索/筛选 | P2 | ✅ 数据集支持关键词与任务类型筛选，评测记录支持模型/数据集/状态搜索 |
| 暗色主题 | P2 | ✅ 现代化深色工作台布局与响应式交互 |
| 国际化 | P3 | ✅ 中文/英文切换已接入 |

### 3.4 已知前端 Bugs

当前已知的 Compare 雷达图、数据集条目分页和 JSON 文件导入问题已在 MVP 0.2.0 修复。后续缺陷以自动化测试和运行态验证结果为准。

### 3.5 基础设施

| 功能 | 优先级 | 说明 |
|------|--------|------|
| Docker Compose | P1 | 后端+前端+Ollama 一键启动 |
| K8s 部署清单 | P1 | `docker/` 目录已预留 |
| CI/CD | P2 | GitHub Actions |
| 集成测试 | P2 | `tests/` 目录已预留 |
| 数据库 profile 切换 | P1 | ✅ Spring Boot datasource profiles: MySQL/PostgreSQL/H2 |
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
    响应: { types: [CHAT, EMBEDDING, RERANKING, TOKENIZATION, PART_OF_SPEECH,
                   NAMED_ENTITY_RECOGNITION, DEPENDENCY_PARSING,
                   SEMANTIC_ROLE_LABELING, TEXT_CLASSIFICATION],
            protocolsByType: {...}, requiredFields: {...}, providers: [...] }
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
    说明: 仅 CHAT 模型可加载到 Spring AI ChatModel 运行时；其他模型资产由各自 pipeline/runtime 使用

POST /api/v1/models/{name}/unload
    响应: 200
    说明: 仅卸载运行时模型，不删除档案

POST /api/v1/models/{name}/test
    请求: { input?, query?, documents?: [string] }
    响应: { status: "succeeded"|"failed"|"configured", type, protocol, provider, model,
            elapsedSeconds, httpStatus?, result?, message?, runtimeReady? }
    说明: 已加载的 SPRING_AI_CHAT 使用 ChatModel；远程标准协议直接发标准连通性请求，
          供应商认证/模型错误以结构化 failed 结果返回，不再作为前端 HTTP 异常抛出。
          HanLP/本地 NLP 组件可通过 /api/v1/pipelines/execute 进入内置能力 runtime 并返回节点级 trace。

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
| TOKENIZATION | HANLP_TOKENIZATION, LOCAL_JAVA_SPI |
| PART_OF_SPEECH | HANLP_POS |
| NAMED_ENTITY_RECOGNITION | HANLP_NER |
| DEPENDENCY_PARSING | HANLP_DEPENDENCY |
| SEMANTIC_ROLE_LABELING | HANLP_SRL |
| TEXT_CLASSIFICATION | HANLP_CLASSIFICATION, LOCAL_CLASSIFIER |

内置官方供应商预设：OpenAI、Anthropic、Google Gemini、Ollama、DeepSeek、Alibaba Qwen、Cohere、Jina AI、HanLP。另保留 Local 和 Custom 入口用于本地 SPI 或自定义标准兼容 endpoint。

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
    存储: datasets + dataset_entries 表（Spring JDBC + 当前数据库 profile）

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
    响应: 202 Accepted + EvaluationRun + Location: /api/v1/evaluations/{id}
    状态流转: queued -> running -> completed | failed | cancelled
    逻辑: 有界线程池异步遍历 dataset.entries，逐条构建 prompt -> registry.predict()
          -> 对比 expectedOutput -> 持久化 processedEntries/progressPercent -> 计算指标

POST /api/v1/evaluations/{id}/cancel
    响应: EvaluationRun
    说明: 将活动任务标记为 cancelling，worker 在当前样本完成后转为 cancelled

GET /api/v1/evaluations
    查询参数: modelName?, datasetName?, status?
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
    响应: [{ task: "TOK", description: "...", parameters: {...} }, ...]
    当前能力目录: TOK, POS, NER, DEP, SDP, SRL, CON, AMR, KEYPHRASE,
                 EXSUM, ABSUM, COR, CLASSIFICATION, SENTIMENT, STS, TST

POST /api/v1/nlp/analyze
    请求: { task?: string, capability?: string, text: string, language?: string, ... }
    `task` 为规范字段，`capability` 为兼容别名；两者都支持能力 ID 或常见长名称

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

⚠️ 以上 6 个 NLP POST 端点属于历史 MVP 接口，其内部仍构造 LLM prompt 然后调用
   registry.predict()。这不符合新的产品定位：后续应重构为基于分词、词性、NER、句法、SRL、分类等
   pipeline 节点的通用 NLP 能力接口。大模型只有在规格明确时才作为独立节点或 baseline 使用。
```

### 4.6 Pipeline 组合与 Trace

```yaml
GET /api/v1/pipelines/capabilities
    响应: [{ id, displayName, description, parameters }, ...]

POST /api/v1/pipelines/execute
    请求: {
      text: string,
      textPair?: string,
      language?: string,
      parameters?: object,
      nodes: [{ id: string, capability: string, name?: string, parameters?: object }]
    }
    响应: {
      traceId, status: completed|failed, inputText, outputText, language,
      startedAt, completedAt, durationMs,
      nodes: [{ id, capability, name, status, inputText, outputText,
                durationMs, result, errorMessage? }]
    }
    说明: 节点按请求顺序执行；文本型结果会作为下一节点输入；失败节点之后的节点标记为 skipped。
```

### 4.7 基准测试

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
taskType: NLPTaskType?  (当前 enum 仍是历史 6 种任务，后续扩展到 HanLP 风格能力)
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
- `ModelType`: CHAT / EMBEDDING / RERANKING / TOKENIZATION / PART_OF_SPEECH / NAMED_ENTITY_RECOGNITION / DEPENDENCY_PARSING / SEMANTIC_ROLE_LABELING / TEXT_CLASSIFICATION
- `ModelProtocol`: 标准协议白名单，不允许任意自定义 HTTP 接入
- `ModelSource`: OFFICIAL / CUSTOM
- `ModelInfo`: 脱敏模型元数据（apiKey 不回显，仅 apiKeySet）
- `BenchmarkResult`: 基准测试统计

---

## 七、前端组件树

```
App.tsx
└── Layout.tsx (侧边导航 + Header)
    ├── Dashboard.tsx    /          系统整体看板
    ├── Models.tsx       /models    模型档案 CRUD + 激活 + 测试
    ├── Datasets.tsx     /datasets  CRUD + JSON 上传
    ├── Evaluation.tsx   /evaluation 运行 + 历史 + 指标弹窗
    ├── Canvas.tsx       /canvas    处理节点变化画布
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
- 4 张统计卡片 (Model Assets / NLP Capabilities / Datasets / Evaluations 计数)
- 模型资产列表 (名称+后端+状态)
- NLP 能力列表 (能力名+说明)
- 最近评测记录 (模型名+数据集名+状态+准确率)
- 空状态提示 (当无数据时)

**Models** (`Models.tsx`):
- 支持大语言模型、嵌入模型、排序模型、分词模型、词性标注模型、NER 模型、句法分析模型、语义角色模型、分类模型资产
- Provider -> Model 两级选择，官方预设自动填充 type/protocol/baseUrl/maxInput/maxOutput
- 表单维护标准调用字段: type/source/protocol/provider/modelName/baseUrl/apiKey/maxInput/maxOutput
- API Key 只写入后端，列表仅显示是否已配置
- 列表标记 OFFICIAL/CUSTOM 来源
- 支持激活 CHAT 模型到 Spring AI ChatModel 运行时，以及统一测试入口
- EMBEDDING/RERANKING 当前走标准协议连通性测试；HanLP/本地 NLP 组件已通过 pipeline runtime 执行并返回节点级 trace

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
- 雷达图 (Recharts RadarChart): 按 accuracy/f1Macro/rouge1/bleu 等可用指标生成多维对比

---

## 八、环境配置 & 构建

### 8.1 application.yml 核心配置

```yaml
server.port: ${XNLP_PORT:8760}
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
# 完整 reactor 验证（网络受限环境可显式指定可写 Maven 仓库和 settings）
mvn -s /tmp/xnlp-central-settings.xml -Dmaven.repo.local=/tmp/m2 clean verify
mvn -s /tmp/xnlp-central-settings.xml -Dmaven.repo.local=/tmp/m2 test -pl xnlp-core
```

### 8.3 启动服务

```bash
# 后端：先通过 reactor 打包，避免直接启动时解析到旧的本地 xnlp-core artifact
cd /Users/haoxiaolong/data/codex/X-NLP
mvn -s /tmp/xnlp-central-settings.xml -Dmaven.repo.local=/tmp/m2 -pl xnlp-server -am package -DskipTests
SPRING_PROFILES_ACTIVE=h2 java -jar xnlp-server/target/xnlp-server-0.1.0.jar

# 前端 (需要 escalated sandbox)
cd /Users/haoxiaolong/data/codex/X-NLP/xnlp-frontend
npm run dev
```

### 8.4 Vite 开发代理

```typescript
// vite.config.ts
proxy: { '/api': { target: 'http://localhost:8760', changeOrigin: true } }
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

### Phase 1 — 打通模型推理 (P0)
> 状态：✅ 已完成（provider-neutral Spring AI runtime bridge）

- 使用 Spring AI `ChatModel` 作为统一推理 SPI，由 Spring Boot 配置选择 OpenAI 或 Ollama provider。
- `SpringAIRuntimeBridge` 将可用的 `ChatModel` 注册为 `spring-ai-default`，模型预测与异步评测复用同一模型注册中心。
- 测试环境通过 `XNLPApplicationSmokeTest.TestChatModelConfiguration` 提供内存 stub，验证 `/predict`、AI Chat 和评测链路；生产环境不自动伪造外部 provider。
- 当没有 `OPENAI_API_KEY` 或 Ollama 服务时，AI 状态会明确返回不可用，而不是伪造成功。

### Phase 2 — 评测闭环 (P1, 预计 2-3天)
> 目标：评测流程可重复、可追溯

**Task 2.1** — 评测结果持久化
- `EvaluationService` 将 runs 写入 `evaluation_runs` 表；默认使用 Spring JDBC
- 历史记录由当前 datasource profile 读取，数据库切换不改变服务层接口

**Task 2.2** — 异步评测
- ✅ `POST /api/v1/evaluations` 返回 `202 Accepted`、runId 和 `Location`，后端通过有界 `ThreadPoolTaskExecutor` 异步执行
- ✅ `GET /api/v1/evaluations/{id}` 查询 `queued/running/cancelling/completed/failed/cancelled` 状态和进度
- ✅ `POST /api/v1/evaluations/{id}/cancel` 请求取消，worker 在样本边界安全停止
- SSE 流式推送暂未实现，前端使用 2 秒轮询

**Task 2.3** — 评测批量对比
- ✅ 前端 Compare 页面支持按模型/数据集筛选
- ✅ 后端支持过滤参数 `?modelName=...&datasetName=...&status=...`

**Task 2.4** — 前端评测进度
- ✅ Evaluation 页面添加 run status 轮询 (setInterval 2s)
- ✅ 显示进度条 (当前条目/总条目) 和取消按钮

### Phase 3 — 前端交互增强 (P1)
> 状态：✅ 已完成（MVP 0.2.0）

- `/nlp` NLP Workbench 已提供任务选择、输入、结果与历史交互。
- Datasets 已支持 JSON 文件选择/拖拽导入及条目分页。
- Compare 已支持多指标雷达图与柱状图对比。

后续增强项：评测进度的 SSE/WebSocket 实时推送、运行日志流式推送、持久化 pipeline trace 和真实生产模型运行时接入。

### Phase 4 — 基础设施 (P2, 预计 2-3天)
> 目标：生产就绪的基础设施

**Task 4.1** — Docker Compose
- ✅ `docker-compose.yml`: MySQL + xnlp-server + xnlp-frontend (nginx serve) + Ollama
- ✅ 服务健康检查与 `depends_on` 启动依赖
- ✅ 服务端和前端均提供可复现的多阶段 Docker 构建

**Task 4.2** — 前端搜索/筛选
- ✅ 数据集列表支持名称、描述、任务类型关键词搜索
- ✅ 数据集列表支持按任务类型筛选
- ✅ 评测记录支持按模型、数据集名称和状态筛选

**Task 4.3** — 内置评测数据集模板
- ✅ 预置 `sentiment-test-v1` 与 `classify-test-v1` 标准 JSON 模板
- ✅ 应用启动后按稳定 ID 幂等导入，兼容 JDBC 与 file 数据存储 profile
- ✅ 模板存在时不覆盖用户修改，缺失时自动恢复

**Task 4.4** — CI/CD
- ✅ GitHub Actions: `mvn verify` + 前端 `npm run build`
- ✅ CI 构建服务端与前端 Docker 镜像，发布到 registry 仍需配置仓库凭据与发布策略

### Phase 5 — 企业级 (P3)
> 目标：生产级安全和可扩展性

**Task 5.1** — 用户认证 (API Key / OAuth2 + Spring Security)
**Task 5.2** — 多数据库增强：数据库迁移版本化与运行时连接池调优
**Task 5.3** — K8s Helm Chart
**Task 5.4** — 多租户数据隔离

---

## 十、技术决策 & 注意事项

### 10.1 Spring AI + Spring Boot 4.1

- **当前状态**: 使用 Spring AI `2.0.1` BOM，与 Spring Boot 4.1 的运行时基线保持一致。
- **Provider**: `spring-ai-starter-model-ollama` 与 `spring-ai-starter-model-openai` 由 Spring Boot 自动配置；通过 `SPRING_AI_MODEL_CHAT=ollama|openai` 选择当前 ChatModel，业务层通过 `ChatModel` 抽象调用，不绑定厂商 SDK。
- **应用入口**: `GET /api/v1/ai/status` 检查运行时，`POST /api/v1/ai/chat` 提供工程化 Copilot 能力。

### 10.2 数据库切换

- 默认 profile 为 `mysql`，可通过 `SPRING_PROFILES_ACTIVE=postgres` 或 `SPRING_PROFILES_ACTIVE=h2` 直接切换。
- 连接信息使用 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 环境变量覆盖；`schema.sql` 仅负责新数据库的便携式表结构初始化；生产环境已有旧表时，应使用对应数据库的迁移流程补齐新增评测进度字段。

### 10.3 Maven 本地仓库

- 沙箱环境有出站网络限制，必须使用预置本地仓库 `/tmp/m2`
- 所有 mvn 命令需追加 `-Dmaven.repo.local=/tmp/m2`

### 10.4 端口差异

- `application.yml` 声明 `8760`，`XNLPProperties.java` 中也配置 `8760`
- 默认运行在 **8760**，可通过 `XNLP_PORT` 覆盖
- Vite 开发代理使用 `8760`，部署时按环境变量对齐

### 10.5 模型配置

- `application.yml` 中 `xnlp.models` 数组保留一条 `ollama-default` 示例配置
- 配置 `OPENAI_API_KEY` 或启动 Ollama 后，`ModelInitializer` 会将 Spring AI ChatModel 注册到运行时
- 未配置 provider 时应用仍可启动，模型/AI 接口会返回可诊断的服务不可用信息
