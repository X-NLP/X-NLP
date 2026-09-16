# X-NLP Release 0.3 实施计划

> 计划状态：执行中（默认 B+D 路线）
> 计划日期：2026-09-15
> 基线分支：`codex/release-0.3`

本文将 [`ROADMAP.md`](ROADMAP.md) 中建议的 Release 0.3 拆成可逐项验收的升级任务。现已按默认 B+D 路线开始实施，采用兼容迁移策略，不一次性破坏现有公共 API。

## 1. 版本目标

让新用户在 H2 环境中完成一条可解释的推理闭环：

```text
查看 Provider 状态 → 选择模型 → 执行预测 → 查看响应/耗时/错误 → 运行 Benchmark → 对比结果
```

同时建立后续真实 NLP、RAG 和企业安全都能复用的 REST 合同：参数校验、稳定错误码、requestId/traceId、分页结构和类型化 SDK。

## 2. 实施原则

1. 复用 Spring Boot 自动配置和 Spring AI 标准接口，不另造 Provider 生命周期；
2. API 先兼容迁移，再删除旧合同，避免一次性破坏前端、SDK 和 CLI；
3. 不向客户端返回 API Key、Authorization、数据库凭证或完整异常堆栈；
4. 测试默认使用 H2 与内存模型，不把外部网络作为单元/集成测试前提；
5. 明确区分“依赖已接入”“配置存在”“连接可达”“模型可调用”；
6. 每个任务独立提交、独立验证，验证通过后再进入下一项。

## 3. WBS 状态

| 编号 | 任务 | 状态 | 依赖 |
|---|---|---|---|
| T-01 | 统一 API 错误合同 | 已完成 | 无 |
| T-02 | 类型化请求、响应与校验 | 已完成 | T-01 |
| T-03 | Provider 诊断与连接测试 | 已完成 | T-01、T-02 |
| T-04 | Model Playground | 已完成 | T-02、T-03 |
| T-05 | Benchmark 产品闭环 | 已完成 | T-02、T-03 |
| T-06 | 模型详情与 Runtime 状态 | 已完成 | T-03 |
| T-07 | 外部 E2E 与数据库矩阵 | 实施中（H2 已通过，容器矩阵已接入 CI，待远端验证） | T-04、T-05、T-06 |
| T-08 | Java SDK / CLI 对齐 | 已完成 | T-01～T-06 |
| T-09 | Release 0.3 版本与发布元数据对齐 | 实现完成（Helm lint 待远端 CI） | T-01～T-08 |

## 4. 分支与提交命名

Release 0.3 使用版本主线分支：`codex/release-0.3`。由于 Git 不能同时保存
`codex/release-0.3` 与 `codex/release-0.3/...` 两级引用，任务分支采用扁平版本命名：

```text
codex/release-0.3-t-<编号>-<短名称>
```

例如：`codex/release-0.3-t-08-sdk-cli`。任务完成后合并回
`codex/release-0.3`，提交信息继续遵循 Conventional Commits。版本主线只接收
通过测试和验收门禁的任务提交。后续版本沿用 `codex/release-0.4`、
`codex/release-0.5`、`codex/release-0.6` 及对应扁平任务分支。

## 5. 可执行任务

### T-01 统一 API 错误合同

**输入**

- 当前 `GlobalExceptionHandler`；
- `XNLPException` 异常体系；
- Controller 中的 `IllegalArgumentException`、`NoSuchElementException` 和裸 `RuntimeException`；
- Micrometer tracing 上下文。

**输出**

- 类型化 `ApiErrorResponse`；
- 稳定错误码目录；
- 业务异常、校验异常、资源不存在、Provider 和数据库异常映射；
- `requestId` / `traceId` 响应字段；
- Controller 合同测试。

**依赖**

- 无。

**验收标准**

- 400、404、409、502/503、500 场景返回相同字段结构；
- 校验失败包含字段级错误，不返回 Java 类名或堆栈；
- 响应与日志均不泄露密钥；
- `DatasetController` 不再抛裸 `RuntimeException`；
- Maven 增量与全量测试通过。

### T-02 类型化请求、响应与校验

**输入**

- `BenchmarkController`、`DatasetController`、`NLPTaskController`、`EvaluationController`、AI 接口；
- 现有前端 API client、Java SDK 和 CLI 调用格式。

**输出**

- Benchmark、NLP、Dataset、Evaluation 核心请求/响应 DTO；
- 统一分页响应；
- 数量、并发、分页、topK、文本长度等约束；
- OpenAPI 可见的字段和校验信息；
- 兼容迁移说明。

**依赖**

- T-01。

**验收标准**

- 必填项缺失和越界值不会进入 service；
- 核心 REST 响应不要求 SDK 通过 `Map<String,Object>` 解析；
- 现有前端、SDK、CLI 调用全部通过编译或兼容测试；
- OpenAPI schema 能表达字段类型和限制。

### T-03 Provider 诊断与连接测试

**输入**

- Spring AI `ChatModel` / `EmbeddingModel` Bean；
- OpenAI-compatible 和 Ollama 自动配置；
- 模型配置档案和现有模型测试能力。

**输出**

- Chat、Embedding、Rerank 三类诊断结果；
- `configured`、`reachable`、`usable` 分层状态；
- provider、model、脱敏 endpoint、检查耗时、失败原因和建议动作；
- 有超时限制的主动连接测试；
- Dashboard/Models/Assistant 可消费的诊断接口。

**依赖**

- T-01、T-02。

**验收标准**

- 无 API Key、无 Ollama、模型不存在、网络失败分别有明确提示；
- 诊断不输出 secret，不无限等待；
- 未配置的 Rerank 明确为 unsupported/unconfigured，而不是伪装可用；
- 自动化测试不依赖外部 Provider。

### T-04 Model Playground

**输入**

- 模型列表、Runtime 列表、预测接口和 Provider 诊断接口；
- 当前 React/Vite/Tailwind 工作台组件风格。

**输出**

- 模型选择、输入编辑、推理参数和单次预测页面；
- 响应、耗时、模型、Provider、错误详情和 request/trace ID 展示；
- Provider 不可用时的修复引导；
- loading、空、成功、失败、重试状态。

**依赖**

- T-02、T-03。

**验收标准**

- 用户可从页面完成一次非流式预测；
- 页面明确区分 demo runtime 与真实 Provider；
- 不可用状态不会显示为普通推理失败；
- TypeScript 和 Vite build 通过；
- 至少有一条前端自动化回归覆盖成功和失败状态。

### T-05 Benchmark 产品闭环

**输入**

- 当前 Benchmark service/controller；
- 类型化模型和 Provider 状态；
- 现有图表与工作台样式。

**输出**

- 模型、请求数、并发度和测试文本配置；
- 运行状态与失败状态；
- 平均延迟、P50/P95/P99、吞吐量、成功率和错误数；
- 历史结果基础对比；
- 类型化 SDK/CLI 可消费结果。

**依赖**

- T-02、T-03。

**验收标准**

- 参数在前后端均受边界限制；
- Benchmark 失败保留稳定错误码和诊断信息；
- 页面可完成发起、等待、结果展示和重试；
- 结果计算有确定性测试。

### T-06 模型详情与 Runtime 状态

**输入**

- 模型配置档案、Runtime 列表、activate/unload/test API；
- Provider 诊断。

**输出**

- 配置档案与运行时实例分区展示；
- Provider、协议、模型版本、runtime 类型、加载状态与最近测试结果；
- activate、unload、test 操作反馈。

**依赖**

- T-03。

**验收标准**

- 用户不会把“已保存配置”误认为“模型已加载”；
- 操作成功后状态刷新，失败时显示稳定错误码和建议；
- 页面刷新后状态来自服务端，不依赖前端临时状态。

### T-07 外部 E2E 与数据库矩阵

**输入**

- Release 0.3 的 REST 与前端闭环；
- H2/MySQL/PostgreSQL profiles 和 Compose。

**输出**

- 健康、Provider、模型、预测、Benchmark、数据集和错误场景脚本；
- H2 必跑基线；
- MySQL/PostgreSQL Compose 回归；
- 测试数据清理和运行说明。

**依赖**

- T-04、T-05、T-06。

**验收标准**

- 测试不依赖开发机已有 H2 文件；
- 重复运行不会积累脏数据；
- H2 在 CI 必跑；
- MySQL/PostgreSQL 至少各有一次真实容器验证记录，未通过时记录具体阻塞而不是宣称支持已验证。

### T-08 Java SDK / CLI 对齐

**输入**

- T-01～T-06 最终 REST 合同；
- 当前 `XNLPClient` 和 Picocli 命令结构。

**输出**

- 类型化错误、Provider 状态、预测参数和 Benchmark 结果；
- CLI `provider status`、`benchmark` 等命令；
- 兼容旧命令的迁移说明。

**依赖**

- T-01～T-06。

**验收标准**

- SDK 核心新增能力不返回裸 Map；
- CLI 对非 2xx 响应输出错误码和 request/trace ID；
- SDK/CLI 单元测试及 Maven 全量验证通过；
- README 包含可复制运行示例。

### T-09 Release 0.3 版本与发布元数据对齐

**输入**

- 已完成的 Release 0.3 功能、测试与部署配置；
- Maven 多模块、前端 npm 包和 Helm Chart 当前版本元数据。

**输出**

- Maven 父工程与全部模块统一为 `0.3.0`；
- 前端 package、Helm Chart/appVersion 和默认镜像标签统一为 `0.3.0`；
- README、SOP 和辅助脚本中的可复制命令同步到 `0.3.0`。

**依赖**

- T-01～T-08。

**验收标准**

- Maven reactor 构建产物版本均为 `0.3.0`；
- npm package 与 lockfile 版本一致；
- `helm lint` 通过且 Chart/appVersion/默认镜像标签一致；
- 仓库当前有效文档和脚本不再引用 `0.1.0` 产物；
- Maven 全量验证、前端构建和浏览器回归通过。

## 6. 推荐执行顺序

```text
T-01 → T-02 → T-03 → T-04/T-05/T-06 → T-07/T-08 → T-09
```

T-04、T-05、T-06 可在合同稳定后并行；T-07 与 T-08 在主要 API 稳定后并行补齐，T-09 在版本发布前统一产物和部署元数据。

## 6. 需要共同确认的五个决策

1. 是否选择 Release 0.3 的 **B+D** 路线；
2. Provider 诊断是否从首版就展示 Rerank 的未配置状态；
3. 核心 API 是否允许从裸 Map 兼容迁移到稳定 DTO；
4. Playground 首版是否先交付非流式预测，把流式输出放到增量版本；
5. MySQL/PostgreSQL 是本轮 CI 必跑，还是先提供可重复的手动 Compose 验证。

**当前默认值**：采用 B+D；诊断包含 Rerank 状态；DTO 采用兼容迁移；Playground 首版非流式；H2 CI 必跑，MySQL/PostgreSQL 先做可重复 Compose 验证。

## 7.1 当前实施记录（2026-09-15）

- T-01 已完成：统一 API 错误合同，覆盖字段级校验、稳定错误码、requestId/traceId 与敏感信息脱敏。
- T-02 已完成：Dataset、Evaluation、NLP 与 Benchmark 核心请求已迁移到类型化 DTO，并保留现有前端字段兼容。
- T-03 已完成：新增被动诊断与主动 Provider Probe，区分 configured/reachable/usable，覆盖 API Key 缺失、HTTP 错误、超时和 Rerank 未配置。
- T-04 已完成：`/playground` 支持聊天模型选择、非流式预测、Provider 诊断、参数编辑、响应与错误元数据展示；Playwright 覆盖成功、稳定错误合同和无模型空状态，并已接入 GitHub Actions。
- T-05 已完成：`/benchmark` 已支持参数校验、并发压测、P50/P95/P99、吞吐量、成功率、失败诊断、session 历史和结果对比；后端有确定性服务测试。
- T-06 已完成：`/models` 已将配置档案和 `/models/runtime` 返回的可调用实例分区展示，显示 provider、协议、模型版本、runtime 类型、加载时间，并提供 activate/unload/test 的反馈；刷新时状态重新从服务端读取，部分接口失败不会阻断其余信息展示。
- T-07 实施中：已新增独立 H2 runner、确定性 OpenAI-compatible mock provider、Release 0.3 REST E2E、MySQL/PostgreSQL Compose 与矩阵 runner；H2 全链路已通过，数据库矩阵已接入 GitHub Actions 必跑门禁。当前开发机未安装 Docker（`docker: command not found`），因此本地尚无 MySQL/PostgreSQL 真实运行证据，待远端 CI 执行后记录结果；本地矩阵脚本在缺少 Docker 时会以退出码 3 明确报告环境阻塞。
- T-08 已完成：Java SDK 已提供类型化 Provider diagnostics、Benchmark 请求和统一错误合同；CLI 已增加 `provider status`、`provider probe`、`benchmark`，并对非 2xx 响应输出错误码和 request/trace ID。
- T-09 实现完成：Maven reactor、前端 package、Helm Chart/appVersion/镜像标签以及当前运行文档已统一到 `0.3.0`；Maven 全量验证、前端生产构建和 Playwright Chromium 回归均已通过。本地环境未安装 Helm，Chart 元数据静态检查通过，`helm lint` 待远端 CI 验证。

验证记录：`xnlp-frontend/npm run build` 与 `xnlp-frontend/npm run test:e2e` 已通过；`mvn -s ~/.m2/settings-aliyun.xml -Dmaven.repo.local=/tmp/m2 verify` 已通过全部模块；`XNLP_E2E_SKIP_BUILD=true tests/e2e/run-h2.sh` 已在隔离 H2 数据库和本地 mock provider 下通过健康、Provider、模型、预测、Benchmark、Dataset CRUD、错误合同与清理验证。

## 8. Release 0.3 完成门禁

只有以下证据全部具备，Release 0.3 才能标记完成：

- Maven 全量测试通过；
- 前端 TypeScript 与生产构建通过；
- API 合同测试覆盖校验、错误码和 request/trace ID；
- 内存模型覆盖成功路径，测试不依赖外部网络；
- Provider 缺失和不可达场景有自动化测试；
- H2 外部 E2E 通过；
- MySQL/PostgreSQL 有真实容器验证证据或明确阻塞记录；
- Playground、Benchmark 可从空状态到达成功或失败终态；
- SDK/CLI 与 REST 合同一致；
- 文档明确区分 demo runtime、配置存在、连接可达与生产可用。
- Maven、前端、Helm 与运行文档的发布版本统一为 `0.3.0`。
