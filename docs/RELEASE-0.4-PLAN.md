# X-NLP Release 0.4 实施计划：真实 NLP Runtime 与可持久化 RAG

> 规划日期：2026-09-15
> 版本主线：`codex/release-0.4`
> 基线：Release 0.3 的 API 工程化、Provider 诊断、Playground/Benchmark、H2 外部 E2E 和可切换 JDBC 数据层
> 目标版本：`0.4.0`

## 1. 版本目标

Release 0.4 将当前“运行时临时 embedding + 数据集内存 Top-K”升级为可持久化、可更新、可评测的知识检索与 RAG 闭环，并落地首个可替换的真实 NLP Runtime。

完成后，用户应能：

1. 创建知识库并导入文本或文档；
2. 对文档进行确定性切分、增量 embedding 和持久化；
3. 重启服务后继续检索，更新或删除文档后索引同步变化；
4. 执行 Top-K 检索、可选 rerank，并查看来源、分数和链路诊断；
5. 基于检索上下文进行 RAG 对话，答案包含可核验引用；
6. 在 Web 工作台完成知识库管理、语义搜索和 RAG 调试；
7. 使用真实 NLP Runtime 替换至少一种 demo capability；
8. 保持 H2、MySQL、PostgreSQL 数据源可切换，不让核心关系数据绑定某一种数据库。

## 2. 架构约束

### 2.1 保持的技术基线

- Java 25、Spring Boot 4.1.0、Spring AI 2.0.1；依赖升级另开独立任务，不与 RAG 业务变更混合。
- Controller 只处理 HTTP 合同，业务编排进入 Service，持久化通过 Repository/SPI。
- Constructor injection；公共合同进入 `xnlp-core`，Spring Boot 适配器进入 `xnlp-server`。
- 默认测试不得依赖真实 OpenAI、Ollama、外部向量数据库或在线模型下载。
- 所有知识数据、向量、检索和 RAG 接口沿用租户隔离、API Key、稳定错误码、requestId/traceId 和 Micrometer 观测约定。

### 2.2 数据库可切换原则

- 关系数据继续使用 Spring Boot `DataSource` + `JdbcTemplate`，由 `h2/mysql/postgres` profile 选择驱动和连接参数。
- 定义项目级 `KnowledgeVectorStore` SPI，业务层不直接依赖 pgvector、MySQL Vector 或厂商 SDK。
- 默认 `JdbcKnowledgeVectorStore` 将 embedding 以可移植 JSON/TEXT 保存，并在应用层执行有边界的 cosine Top-K；该实现用于开发、小规模部署和三数据库一致性验证。
- PostgreSQL pgvector 作为后续可选高性能适配器，不替换默认可移植实现，也不改变上层接口。
- embedding 记录必须包含模型标识、维度、内容 checksum 和更新时间，防止模型切换后静默混用不同向量空间。

### 2.3 Spring AI 集成原则

- 使用 Spring AI `EmbeddingModel` 生成 query/document embedding，使用 `ChatModel`/`ChatClient` 生成 RAG 答案。
- 项目 SPI 对 Spring AI provider 保持中立；OpenAI-compatible、Ollama 或未来 provider 均通过 Boot 配置切换。
- Rerank 先定义项目协议与无 rerank fallback，再实现 OpenAI-compatible/Cohere/Jina 风格适配器。
- 真实 provider 缺失时返回明确的 `provider_unconfigured`，不生成伪向量或伪答案。

## 3. 领域模型与组件边界

### 3.1 核心领域对象

- `KnowledgeBase`：知识库元数据、embedding 模型、切分策略、状态和统计。
- `KnowledgeDocument`：原始文档、来源、checksum、版本、索引状态和错误摘要。
- `KnowledgeChunk`：确定性 chunk 序号、文本、token/字符范围、metadata。
- `VectorRecord`：chunk 与 embedding 模型、维度、向量、checksum 的绑定。
- `RetrievalQuery` / `RetrievalMatch`：查询参数、过滤条件、原始分数、rerank 分数和来源。
- `RagAnswer` / `Citation`：答案、引用、provider/runtime 信息、token/耗时和 traceId。
- `NlpRuntime`：真实 NLP 模型加载、能力声明、执行、健康状态和释放的 SPI。

### 3.2 服务分层

```text
KnowledgeController / RetrievalController / RagController
                         |
KnowledgeService / IngestionService / RetrievalService / RagService
                         |
DocumentRepository + ChunkRepository + KnowledgeVectorStore + Reranker
                         |
Jdbc repositories / JdbcKnowledgeVectorStore / optional provider adapters
                         |
Spring Boot DataSource + Spring AI EmbeddingModel/ChatModel
```

## 4. REST 接口合同

所有接口位于 `/api/v1`，错误统一返回 Release 0.3 的 `ApiErrorResponse`。

### 4.1 知识库

| 方法 | 路径 | 入参 | 成功出参 | 主要错误码 |
|---|---|---|---|---|
| `POST` | `/knowledge-bases` | `name`、`description?`、`embeddingModel?`、`chunkPolicy?` | `201 KnowledgeBaseResponse` | `validation_failed`、`knowledge_base_conflict` |
| `GET` | `/knowledge-bases` | `page`、`size`、`query?` | `PageResponse<KnowledgeBaseSummary>` | `validation_failed` |
| `GET` | `/knowledge-bases/{id}` | path `id` | `KnowledgeBaseResponse` | `knowledge_base_not_found` |
| `PUT` | `/knowledge-bases/{id}` | 可修改字段；embedding 模型变化必须显式 `reindex=true` | `KnowledgeBaseResponse` | `knowledge_base_not_found`、`reindex_required` |
| `DELETE` | `/knowledge-bases/{id}` | `force=false` | `204` | `knowledge_base_not_found`、`knowledge_base_not_empty` |

### 4.2 文档与索引

| 方法 | 路径 | 入参 | 成功出参 | 主要错误码 |
|---|---|---|---|---|
| `POST` | `/knowledge-bases/{id}/documents` | `title`、`content`、`sourceType`、`sourceUri?`、`externalId?`、`metadata?` | `202 KnowledgeDocumentResponse` | `knowledge_base_not_found`、`document_conflict`、`content_too_large` |
| `GET` | `/knowledge-bases/{id}/documents` | `page`、`size`、`status?` | `PageResponse<KnowledgeDocumentSummary>` | `knowledge_base_not_found` |
| `GET` | `/knowledge-bases/{id}/documents/{documentId}` | path 参数 | 文档详情与 chunk/index 统计 | `document_not_found` |
| `PUT` | `/knowledge-bases/{id}/documents/{documentId}` | `title?`、`content?`、`metadata?`、`expectedVersion` | `202 KnowledgeDocumentResponse` | `document_not_found`、`document_version_conflict` |
| `DELETE` | `/knowledge-bases/{id}/documents/{documentId}` | 无 | `204` | `document_not_found` |
| `POST` | `/knowledge-bases/{id}/reindex` | `documentIds?`、`force=false` | `202 IngestionJobResponse` | `provider_unconfigured`、`reindex_in_progress` |
| `GET` | `/ingestion-jobs/{jobId}` | path `jobId` | 进度、成功/失败数量、错误摘要 | `ingestion_job_not_found` |

### 4.3 检索、Rerank 与 RAG

| 方法 | 路径 | 入参 | 成功出参 | 主要错误码 |
|---|---|---|---|---|
| `POST` | `/knowledge-bases/{id}/search` | `query`、`topK=10`、`minScore?`、`filter?`、`rerank=false`、`rerankTopN?` | `RetrievalResponse` | `provider_unconfigured`、`knowledge_base_not_found`、`vector_dimension_mismatch`、`reranker_unavailable` |
| `POST` | `/knowledge-bases/{id}/rag` | `message`、`topK?`、`minScore?`、`maxContextChunks?`、`conversationId?`、`systemPrompt?` | `RagAnswerResponse` | `provider_unconfigured`、`insufficient_context`、`model_timeout` |
| `POST` | `/knowledge-bases/{id}/retrieval-evaluations` | dataset/query set、`topK`、rerank 配置 | `202 RetrievalEvaluationResponse` | `dataset_not_found`、`evaluation_conflict` |

`RetrievalResponse` 至少包含：`query`、`matches[]`、`embeddingModel`、`reranker?`、`elapsedMs`、`traceId`。每个 match 至少包含：`documentId`、`chunkId`、`title`、`content`、`sourceUri?`、`score`、`rerankScore?`、`metadata`。

`RagAnswerResponse` 至少包含：`answer`、`citations[]`、`retrieval` 摘要、`model`、`provider`、`usage?`、`elapsedMs`、`traceId`。Citation 必须指向本次返回的真实 chunk，禁止生成无法对应来源的引用编号。

## 5. 数据变更

在现有 append-only migration 机制中新增可移植表，不修改 V1 checksum：

1. `knowledge_bases`
   - `id`、`tenant_id`、`name`、`description`、`embedding_model`、`chunk_policy_json`、`status`、`created_at`、`updated_at`；
   - 唯一约束：`tenant_id + name`。
2. `knowledge_documents`
   - `id`、`tenant_id`、`knowledge_base_id`、`external_id`、`title`、`source_type`、`source_uri`、`content_text`、`content_checksum`、`version`、`index_status`、`error_message`、时间字段。
3. `knowledge_chunks`
   - `id`、`tenant_id`、`knowledge_base_id`、`document_id`、`seq`、`content_text`、`content_checksum`、`start_offset`、`end_offset`、`metadata_json`。
4. `knowledge_embeddings`
   - `chunk_id`、`tenant_id`、`knowledge_base_id`、`embedding_model`、`dimensions`、`embedding_json`、`content_checksum`、`updated_at`。
5. `ingestion_jobs`
   - `id`、`tenant_id`、`knowledge_base_id`、`status`、计数器、`error_summary`、时间字段。
6. `retrieval_evaluation_runs` 与样本结果表在 T-08 增加，避免首批迁移过早锁定评测模型。

所有查询必须带 `tenant_id`；文档删除顺序为 embedding → chunk → document；知识库删除默认拒绝非空库，`force=true` 时在单事务中级联清理。

## 6. WBS 与分支规划

版本主线和任务分支统一采用扁平版本命名，避免 Git ref 冲突：

```text
codex/release-0.4
codex/release-0.4-t-01-rag-contracts
codex/release-0.4-t-02-vector-store-spi
codex/release-0.4-t-03-document-ingestion
codex/release-0.4-t-04-retrieval-rerank
codex/release-0.4-t-05-rag-chat
codex/release-0.4-t-06-onnx-runtime
codex/release-0.4-t-07-knowledge-ui
codex/release-0.4-t-08-retrieval-evaluation
codex/release-0.4-t-09-release-gates
```

| 任务 | 内容 | 输出 | 依赖 | 状态 |
|---|---|---|---|---|
| T-01 | RAG 领域合同与 API DTO | core 模型、SPI 合同、DTO 校验、OpenAPI/error contract 测试 | Release 0.3 | 已完成（2026-09-15） |
| T-02 | Vector Store SPI 与 JDBC 实现 | 可移植 schema、repository、cosine Top-K、租户隔离 | T-01 | 已完成（2026-09-15） |
| T-03 | 文档导入与增量索引 | chunker、checksum、embedding、异步 job、增删改同步 | T-02 | 已完成（2026-09-15） |
| T-04 | Retrieval 与 Rerank | 检索 API、过滤、reranker SPI/provider adapter、链路观测 | T-03 | 待实施 |
| T-05 | RAG Chat 与引用 | context assembler、ChatClient、citation 校验、错误与超时 | T-04 | 待实施 |
| T-06 | 首个真实 NLP Runtime | `NlpRuntime` SPI、ONNX Runtime Java 适配、模型版本/checksum/释放 | T-01 | 待实施 |
| T-07 | Knowledge UI | 知识库、文档、索引任务、语义搜索、RAG 调试页面 | T-03～T-05 | 待实施 |
| T-08 | 检索评测与 E2E | Recall@K/MRR/nDCG、样本结果、H2 E2E、数据库矩阵 | T-04、T-07 | 待实施 |
| T-09 | 发布门禁与版本对齐 | Maven/npm/Helm `0.4.0`、安全/性能门禁、升级文档 | T-01～T-08 | 待实施 |

## 7. 每个任务的验收标准

### T-01 RAG 领域合同与 API DTO

- `xnlp-core` 提供不可变或受控可变的知识库、文档、chunk、检索、引用和向量 SPI 合同；
- DTO 对空文本、非法 Top-K、非法版本、超长内容做 Bean Validation；
- 公共错误码进入统一异常映射；
- core 公共 API 与 server DTO 单元测试通过，不需要数据库和真实 provider。

### T-01 实施记录（2026-09-15）

- `xnlp-core` 已新增知识库、文档、chunk、向量、检索、rerank、RAG 引用和异步导入任务合同；
- `KnowledgeVectorStore` 与 `RetrievalReranker` 已形成 provider/数据库无关的项目级 SPI；
- embedding/query vector 已校验维度和有限数值，集合与数组使用防御性复制；
- server 已新增知识库、文档、检索、RAG、reindex 请求 DTO，覆盖默认值、长度、Top-K、版本和跨字段校验；
- `RagErrorCode` 保持 core 与 HTTP 解耦，并由统一异常处理映射到 400/404/409/422/503/504；
- citation 同时按 `documentId + chunkId` 校验，禁止引用检索结果之外的来源；
- `mvn verify` 于 2026-09-15 本地通过，core/server/client/cli 全部 `BUILD SUCCESS`。

### T-02 Vector Store SPI 与 JDBC 实现

- H2、MySQL、PostgreSQL 使用同一 Java repository 代码；
- 向量插入、按知识库删除、按文档删除、Top-K、minScore、metadata filter 有测试；
- 维度不一致明确失败，不静默截断；
- 所有 SQL 带租户条件；H2 集成测试证明租户隔离和服务重启后数据可读。

### T-02 实施记录（2026-09-15）

- 新增 `knowledge_bases`、`knowledge_documents`、`knowledge_chunks`、`knowledge_embeddings`、`ingestion_jobs` 五张 RAG 表；主键和关联查询均显式包含 `tenant_id`；
- 数据库迁移按 JDBC product name 将大文本列适配为 MySQL `LONGTEXT` 或 H2/PostgreSQL `TEXT`，索引通过 metadata 判重后创建，避免绑定单一数据库方言；
- 新增知识库、文档、chunk、导入任务 repository 合同，为 T-03 JDBC adapter 和异步索引编排提供稳定边界；
- `JdbcKnowledgeVectorStore` 使用 JSON embedding 持久化、应用侧有界 cosine Top-K、稳定同分排序、`minScore` 和合并后的 document/chunk/vector metadata filter；
- upsert 使用 update → insert → duplicate retry update，支持同一 `(tenant, chunk, model)` 幂等替换；搜索候选上限为 10,000，超限返回稳定错误码；
- tenant ID 在 upsert/search/delete/count 入口统一规范化；持久化向量的声明维度、实际长度和有限数值在计算前校验；
- H2 集成测试覆盖 Top-K、过滤、阈值、upsert 替换、删除、租户隔离、维度错误和数据库损坏防御；文件数据库测试覆盖关闭并重新打开后的向量可检索；
- 目标测试共 6 条通过；`mvn verify` 于 2026-09-15 本地通过，core/server/client/cli 全部 `BUILD SUCCESS`。

### T-03 文档导入与增量索引

- 相同内容 checksum 重复导入不重复 embedding；
- 内容变化只替换受影响文档的 chunks/vectors；
- 删除文档同步删除 chunks/vectors；
- chunk 边界可重复、最大尺寸可配置，并保留 source offset；
- mock `EmbeddingModel` 下覆盖成功、部分失败、重试和取消。

### T-03 实施记录（2026-09-15）

- 新增知识库、文档与导入任务 REST API，支持 checksum/externalId 幂等、乐观版本更新、按文档和整库重建索引；
- 新增确定性 `PARAGRAPH` / `SENTENCE` / `FIXED` chunker，保留精确 source offset、稳定 chunk ID 和 SHA-256 checksum；
- 使用 Spring AI `EmbeddingModel` 进行有界批处理，支持有限向量校验、重试、部分失败、持久化取消和 provider 错误脱敏；
- 新增有界 ingestion executor，并在异步任务中显式传播 tenant ID；文档、chunk、vector 的替换与删除由事务边界原子提交；
- JDBC repository 与 V6 迁移补齐知识库、文档、chunk、ingestion job 持久化、取消标志及租户级唯一约束，继续复用 Spring Boot `DataSource` / `JdbcTemplate` 支持 H2、MySQL、PostgreSQL；
- 文档版本 CAS 和索引状态 CAS 防止旧 worker 覆盖新版本或复活已删除文档；知识库状态会在并发更新产生的后继任务完成后恢复一致；
- 目标测试共 11 条通过；`mvn verify` 于 2026-09-15 本地通过，core/server/client/cli 全部 `BUILD SUCCESS`。

### T-04 Retrieval 与 Rerank

- 检索结果稳定按分数排序，同分有确定性次序；
- rerank 未配置时可选择 fallback 或严格失败；
- provider 错误不泄露 key、Authorization、数据库密码或堆栈；
- 指标包含 embedding、vector search、rerank 分阶段耗时。

### T-05 RAG Chat 与引用

- mock ChatModel 下可验证 prompt、上下文预算和引用映射；
- 无足够上下文时按请求策略拒答或明确标记低置信；
- 引用只能指向返回的 chunk；
- 支持请求级超时和取消，错误合同与 Release 0.3 一致。

### T-06 首个真实 NLP Runtime

- 模型文件外置，配置包含版本和 SHA-256；
- 加载、执行、超时、关闭、重复加载和 checksum 错误有测试；
- 默认 Maven 测试使用小型 fixture 或 fake runtime，不在线下载大模型；
- 至少一种现有 demo capability 可通过配置切换到真实 runtime。

### T-07 Knowledge UI

- 页面覆盖 loading/empty/success/error/partial failure；
- 文档索引状态、失败原因、检索分数、rerank 分数、引用可见；
- 沿用 Release 0.3 的设计系统、响应式布局和可访问交互；
- Playwright 覆盖创建知识库、导入、检索、RAG 引用和 provider 未配置状态。

### T-08 检索评测与 E2E

- 支持 Recall@K、MRR、nDCG 和 latency；
- 样本级结果持久化，可定位 miss、低分和 rerank 变化；
- H2 外部 E2E 使用确定性 mock embedding/chat/rerank；
- MySQL/PostgreSQL CI 执行同一合同套件。

### T-09 发布门禁与版本对齐

- `mvn verify`、前端 build、Playwright、H2 E2E、数据库矩阵、Helm lint 全部进入 CI；
- 依赖漏洞和 secret scanning 不出现高危未处理项；
- Maven/npm/Helm/镜像标签统一为 `0.4.0`；
- README 提供 H2 快速开始、真实 provider 配置、知识库导入、search 和 RAG curl 示例。

## 8. 推荐执行顺序

```text
T-01 → T-02 → T-03 → T-04 → T-05 → T-07 → T-08 → T-09
          └──────────── T-06 可在 T-01 后并行 ────────────┘
```

默认先实施 T-01/T-02/T-03，尽早形成“可持久化导入与检索”最小闭环；真实 ONNX Runtime 不阻塞 RAG 数据链路，但必须在 Release 0.4 发布前完成。

## 9. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|---|---|---|
| JSON 向量全表扫描性能有限 | 大知识库延迟升高 | 设置候选上限与容量告警；SPI 保留 pgvector 适配；明确默认实现适用规模 |
| embedding 模型切换导致空间不一致 | 检索结果错误 | 保存模型与维度；配置变更强制 reindex；查询时校验 |
| 文档更新时索引部分成功 | 原文和向量不一致 | 文档版本 + job 状态 + 文档级事务；失败保持可重试状态 |
| RAG 引用由模型自由生成 | 出现虚假引用 | 使用 chunk ID 占位符、生成后校验和过滤；答案返回真实 citation 对象 |
| 真实 ONNX 模型体积和许可证 | CI/镜像过大或分发风险 | 模型外置、checksum、许可证清单；CI 使用 fixture/fake runtime |
| 多租户异步任务丢失上下文 | 越权读写 | job 显式保存 tenantId，异步线程不只依赖 ThreadLocal |
| provider 输出或异常泄密 | 安全风险 | 统一脱敏、错误码映射、日志字段白名单和现有安全回归 |

## 10. Release 0.4 完成定义

Release 0.4 只有在以下证据齐全时才能标记完成：

- H2 本地和外部 E2E 证明导入、持久化、重启检索、更新、删除、RAG 引用和评测闭环；
- GitHub Actions 证明 MySQL/PostgreSQL 使用相同代码与合同测试通过；
- mock provider 测试稳定通过，真实 provider 验证有单独记录但不作为默认测试依赖；
- 至少一个真实 NLP Runtime 完成配置化加载和能力替换；
- Web Playwright 覆盖核心成功与失败路径；
- Maven、npm、Helm、镜像版本统一为 `0.4.0`，发布文档和回滚说明齐全。
