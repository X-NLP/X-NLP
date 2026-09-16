# X-NLP 产品路线图与实现审计

> 审计日期：2026-09-15
> 当前基线：Release 0.4 已合并到 `main`（PR #3，commit `620250d`）
> 审计基线：Release 0.4 完整实现及合并后 CI 结果（以本文审计日期的工作树、Git 历史和 GitHub Actions 为准）
> 本文只记录当前工作区中可以由源码、构建结果或测试结果证明的状态；“已实现”不等于“生产环境已配置真实 provider”。

## 1. 当前产品定位

X-NLP 的主线不是简单的聊天窗口，而是一个可组合、可评测、可观测的 NLP 工程工作台：

1. 管理模型资产与运行时状态；
2. 管理数据集、样本和期望输出；
3. 通过统一能力组件组成 NLP pipeline；
4. 对处理结果进行异步评测、追踪和对比；
5. 通过 Spring AI 接入 ChatModel / EmbeddingModel 等标准模型能力；
6. 通过 Spring Boot 的 DataSource 与 profile 切换存储实现。

## 2. 当前实现快照

### 2.1 已实现并已由源码确认的能力

| 领域 | 已实现内容 | 证据位置 |
|---|---|---|
| Web 工作台 | React 18 + Vite + Tailwind；Dashboard、Models、Datasets、Evaluation、Compare、Canvas、AI Assistant、NLP Workbench、WasteFlow | `xnlp-frontend/src/App.tsx`、`xnlp-frontend/src/pages/` |
| 模型资产 | 模型档案 CRUD、能力目录、激活、卸载、测试、运行时列表 | `xnlp-server/src/main/java/com/xnlp/server/controller/ModelController.java`、`ModelCatalogService.java` |
| Chat AI | Spring AI ChatModel；OpenAI-compatible 与 Ollama 配置切换 | `xnlp-server/src/main/resources/application.yml`、`SpringAIRuntimeBridge.java` |
| Embedding AI | 语义相似度、数据集 Top-K 语义搜索、provider 响应校验 | `SemanticSearchService.java`、`NLPTaskController.java`、`DatasetController.java` |
| 可持久化 RAG | 知识库/文档导入、确定性切分、增量 embedding、JDBC 向量存储、过滤检索、可选 rerank、可信引用 RAG Chat | `KnowledgeIngestionService.java`、`JdbcKnowledgeVectorStore.java`、`RetrievalService.java`、`RagChatService.java` |
| NLP 组件 | TOK、POS、NER、DEP、SDP、SRL、CON、AMR、KEYPHRASE、摘要、纠错、分类、情感、STS、TST 等能力目录与 demo runtime | `xnlp-server/src/main/java/com/xnlp/server/component/impl/`、`CapabilityRegistry.java` |
| Pipeline | 有序节点执行、节点状态、节点结果、失败后跳过、traceId 与耗时 | `PipelineTraceService.java`、`PipelineController.java`、`xnlp-core/src/main/java/com/xnlp/core/api/` |
| 评测 | 异步队列、进度持久化、取消、SSE、指标计算、历史过滤、评测对比 | `EvaluationService.java`、`EvaluationController.java`、`EvaluationRunEntity.java` |
| 数据层 | Spring JDBC repository；H2、MySQL、PostgreSQL profile；memory/file 适配器；版本迁移与 checksum 校验 | `xnlp-server/src/main/java/com/xnlp/server/repository/`、`application-*.yml`、`DatabaseMigrationRunner.java` |
| 工程能力 | API Key、租户隔离、健康探针、Actuator/Prometheus、Micrometer Tracing、结构化日志、Docker Compose、Helm、GitHub Actions | `config/`、`tenant/`、`docker-compose.yml`、`deploy/helm/`、`.github/workflows/ci.yml` |
| SDK / CLI | Java SDK 覆盖模型、推理、数据集、评测、SSE、NLP/Pipeline/AI；Picocli 覆盖主要模型/数据集/评测工作流 | `xnlp-client/src/main/java/`、`xnlp-cli/src/main/java/` |

### 2.2 当前验证结果

| 验证项 | 结果 | 说明 |
|---|---|---|
| Maven 全量构建与测试 | ✅ `BUILD SUCCESS` | `mvn -s ~/.m2/settings-aliyun.xml -Dmaven.repo.local=/tmp/m2 verify`；全部模块通过，0 failures，0 errors |
| 前端生产构建与浏览器回归 | ✅ 本地成功 / ⏳ CI 待验证 | TypeScript 与 Vite 构建通过；Playwright Chromium 3 条回归通过，并已接入 GitHub Actions |
| 真实 Chat provider | ⚠️ 未在本次审计中验证 | 需要有效的 OpenAI API Key 或可访问的 Ollama 服务 |
| 真实 Embedding provider | ⚠️ 未在本次审计中验证 | 需要配置 embedding provider；未配置时不能把 demo runtime 当成生产语义检索 |
| MySQL / PostgreSQL 容器矩阵 | ⏳ 待远端验证 | 已提供 Compose 与矩阵 runner，并接入 GitHub Actions 必跑门禁；当前开发机没有 `docker` 命令，需以远端 CI 结果形成真实容器证据 |
| H2 外部 E2E | ✅ 成功 | `tests/e2e/run-h2.sh` 使用隔离数据库和确定性 mock provider，完整合同回归已通过并接入 GitHub Actions |

> Maven 测试需要在允许嵌入式服务器绑定随机端口的环境运行。受限沙箱中出现的 `SocketException: Operation not permitted` 是环境限制；在允许本地端口的环境重新执行后通过。

## 3. 未实现或需要升级的内容

### P0：从“能跑”到“可用闭环”

1. **Provider 运行时诊断与配置向导（Release 0.3 已完成）**
   - 已提供 Chat、Embedding、Rerank 的被动诊断与主动 Probe，并区分 `configured / reachable / usable`。
   - 已覆盖 API Key 缺失、模型不可用、HTTP 错误、超时和未配置 Rerank；前端 Models/Playground 可展示修复线索。
   - 后续仍可升级：增加真正的配置向导、按 provider 的字段校验和可选的安全凭据管理。

2. **真实 NLP runtime 至少落地一个适配器（Release 0.4 已完成）**
   - 已落地 provider-neutral `NlpRuntime` SPI 与 ONNX Runtime Java 1.29.0 适配器，模型文件外置并校验版本与 SHA-256。
   - 已覆盖加载、执行、有界并发、超时、关闭、重复加载和 checksum 错误，情感能力可在 demo 与 ONNX runtime 间配置切换。
   - 后续可在同一 SPI 上增加 HanLP/DJL 或更多 ONNX 能力，不改变上层 API。

3. **统一 API 合同（Release 0.3 已完成核心范围）**
   - 已覆盖统一错误响应、字段级校验、稳定错误码、requestId/traceId、分页及 Benchmark/NLP/Dataset/Evaluation 核心 DTO。
   - 后续仍需把剩余 `Map<String,Object>` 响应迁移到公开 DTO，并同步 SDK、CLI 与 OpenAPI 生成客户端。

### P1：产品闭环与 RAG 能力

4. **Model Playground 与 Benchmark 页面（已完成核心闭环）**
   - Playground 已支持模型选择、非流式预测、Provider 诊断、参数编辑、响应与错误元数据。
   - Benchmark 已支持参数边界、并发执行、P50/P95/P99、吞吐量、成功率、失败诊断、历史记录和结果对比。
   - Playwright 已覆盖 Playground 成功、稳定错误合同、空状态和重复提交保护；首版流式输出/token 使用量仍待增强。

5. **模型详情与运行时操作（Release 0.3 已完成）**
   - Models 页面已分离配置档案与 `/models/runtime` 可调用实例，显示 provider、协议、模型版本、runtime 类型和加载时间，并支持 activate/unload/test 反馈。

6. **Dataset 编辑与样本级体验（Release 0.5 规划）**
   - 当前重点是创建、列表、分页、导出、删除；需要详情编辑、样本增删改、数据集版本和导入校验报告。

7. **评测样本级结果与可恢复执行（Release 0.5 规划）**
   - 当前已持久化运行状态和聚合指标；还需要逐条预测结果、错误分类、重跑、断点恢复和指标插件 SPI。

8. **持久化向量检索与 Rerank（Release 0.4 已完成）**
   - 已提供项目级 `KnowledgeVectorStore` SPI、可移植 JDBC 向量存储、确定性切分、增量 embedding、更新/删除同步和持久化 Top-K 检索。
   - 已提供 Cohere/Jina 风格 rerank 协议适配与 fallback/strict 策略，并完成 Retrieval、RAG、Knowledge UI 和 Recall@K/MRR/nDCG 评测闭环。
   - 后续优化方向是 pgvector 等高性能适配器、容量治理和更大规模性能基线，不改变默认跨数据库实现。

9. **Pipeline DAG 与可审计运行记录（Release 0.5 规划）**
   - 当前按请求顺序执行；需要依赖关系、分支/合并、节点级超时/重试、运行日志流、trace 持久化与下载。

### P2：企业生产化

10. OAuth2/OIDC/JWT 与 RBAC（管理员、开发者、只读用户）；
11. API Key 生命周期、过期、撤销、轮换和审计；
12. 多租户配额、限流、熔断、并发与请求大小限制；
13. Secret 管理、备份恢复、迁移回滚和数据保留；
14. 对象存储适配器替代 WasteFlow 的本地文件存储；
15. MySQL/PostgreSQL 真实容器矩阵、镜像漏洞扫描、SBOM、签名和 registry 发布。

### P2：开发者生态

16. 类型化 Java SDK 与 CLI 补齐 Provider diagnostics、benchmark、pipeline、semantic search、compare 等命令；
17. Python/TypeScript SDK、Webhook、OpenAPI 生成客户端；
18. runtime/组件插件模板、示例工程、快速开始脚本和贡献者文档；
19. 前端单元测试、组件测试、浏览器 E2E 和可访问性检查。

## 4. 建议的版本路线

### Release 0.3：可用推理闭环（已完成）

**目标**：让第一次启动的用户能看懂系统状态，并从页面完成一次可解释的模型调用与基准测试。

- R0.3-1：统一 API DTO、校验、错误码和 OpenAPI（核心范围已完成）；
- R0.3-2：Provider 状态诊断与连接测试（已完成）；
- R0.3-3：Model Playground（已完成，含 Playwright 成功/失败/空状态回归）；
- R0.3-4：Benchmark 页面与 SDK/CLI 对齐（已完成）；
- R0.3-5：模型详情、激活、卸载和运行时状态（已完成）；
- R0.3-6：外部 E2E 基础脚本（H2、MySQL、PostgreSQL runner 已接入 CI）。
- R0.3-7：Maven、前端和 Helm 发布元数据已统一为 `0.3.0`。

**完成标准**：新用户使用 H2 + Ollama 或 OpenAI-compatible provider，能够完成“配置/检查 provider → 选择模型 → 预测 → 查看错误/耗时 → benchmark”，并有自动化测试证明。

### Release 0.4：真实 NLP 与 RAG

- R0.4-1：落地首个真实 NLP runtime（已完成：ONNX Runtime Java 1.29.0）；
- R0.4-2：向量存储 SPI 与第一种持久化实现（已完成）；
- R0.4-3：文档切分、批量导入、增量更新（已完成）；
- R0.4-4：Rerank 协议适配与检索链路（已完成）；
- R0.4-5：RAG Chat 与可信引用（已完成）；
- R0.4-6：Knowledge UI 与检索评测（已完成）。

**完成标准**：数据集/文档导入后可重复检索，重启服务后向量仍可用，更新和删除能同步，检索结果可以被评测。

### Release 0.5：企业平台

- OAuth2/OIDC/JWT、RBAC、审计、配额、限流、熔断、任务恢复、Secret、备份和生产对象存储。

### Release 0.6：开发者生态

- 完整 SDK/CLI、Python/TypeScript SDK、Webhook、OpenAPI 客户端、插件模板和示例工程。

## 5. 当前执行决策

Release 0.3 的执行记录见 [`RELEASE-0.3-PLAN.md`](RELEASE-0.3-PLAN.md)；Release 0.4 的接口合同、数据变更、WBS 与验收门禁见 [`RELEASE-0.4-PLAN.md`](RELEASE-0.4-PLAN.md)。

Release 0.3 与 Release 0.4 已合并到 `main`。Release 0.4 的 T-01～T-09 已形成“导入 → 切分 → embedding → 持久化 → 过滤检索 → 可选 rerank → grounded RAG → canonical citation → Knowledge UI → 检索评测”的完整闭环，并落地 ONNX Runtime Java 1.29.0。

合并后 CI 暴露 PostgreSQL V1 baseline 的 `DOUBLE` 方言错误，以及 Netty、Tomcat、PostgreSQL JDBC 的 HIGH/CRITICAL 漏洞。当前先在 `codex/release-0.4.1-ci-gates` 完成发布基线修复；远端 H2/MySQL/PostgreSQL、Helm、镜像与 Trivy 门禁通过后再启动 Release 0.5。

Release 0.5 默认按以下顺序实施：

1. OAuth2/OIDC/JWT 与 RBAC 基线，保留现有 API Key 兼容路径；
2. API Key 生命周期与统一审计日志；
3. 多租户配额、限流、并发和请求大小治理；
4. Dataset 样本编辑、版本与导入报告；
5. 评测样本结果、重跑与断点恢复；
6. Pipeline DAG、节点重试/超时和持久化 trace；
7. Secret、备份恢复、对象存储和供应链发布能力。
