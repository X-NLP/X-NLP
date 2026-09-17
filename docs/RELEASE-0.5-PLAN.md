# X-NLP Release 0.5 实施计划：企业身份、治理与可恢复执行

> 规划日期：2026-09-16
> 版本主线：`codex/release-0.5`
> 基线：Release 0.4.1（Release 0.4 RAG/NLP 闭环 + PostgreSQL/安全门禁修复）
> 目标版本：`0.5.0`

## 1. 版本目标

Release 0.5 将 X-NLP 从“具备工程能力的单体工作台”升级为可在企业多租户环境安全运行、治理和审计的平台。完成后应具备：

1. OIDC/OAuth2 登录和 JWT API 鉴权，同时保留可迁移的 API Key 兼容路径；
2. `ADMIN`、`DEVELOPER`、`VIEWER` 三类租户级角色和明确的资源授权边界；
3. API Key 创建、哈希存储、过期、撤销、轮换、最后使用时间和审计；
4. 多租户请求、并发、模型调用和知识导入配额，并返回稳定限流合同；
5. Dataset 样本 CRUD、乐观版本、导入校验报告和版本快照；
6. 评测样本级结果、失败分类、重跑和服务重启后的安全恢复；
7. Pipeline DAG 校验、节点超时/重试、运行记录、事件流和可下载 trace；
8. Secret 引用、备份恢复、对象存储和 SBOM/镜像签名发布基线。

## 2. 架构约束

- Java 25、Spring Boot 4.1.x、Spring AI 2.0.x；依赖升级只接受补丁/安全修复，跨 minor 升级单独评审。
- 继续使用 Spring Security，不引入自建密码协议。JWT 必须校验 issuer、audience、签名、过期时间和时钟偏差。
- 租户身份来自受信任认证主体；启用 OIDC/JWT 后不得仅凭客户端 `X-Tenant-ID` 获得其他租户权限。
- API Key 只保存不可逆哈希和短前缀，不记录明文；日志、错误、指标和审计事件禁止出现 token、Authorization、数据库密码或 provider key。
- 数据层继续使用 Spring Boot `DataSource` + `JdbcTemplate`，H2/MySQL/PostgreSQL 使用同一 repository 合同和迁移历史。
- 所有新增异步任务必须显式持久化 tenant、actor、状态、checkpoint、cancel flag，并保证重启恢复幂等。
- Controller 只处理 HTTP 合同；授权、配额、审计和业务编排分别进入独立 service/interceptor，不散落在 controller。
- 默认测试使用本地 JWT fixture、stub provider 和内存对象存储，不依赖外部 IdP、云存储或在线模型。

## 3. 安全与身份模型

### 3.1 认证模式

| 模式 | 用途 | 身份来源 | 租户来源 |
|---|---|---|---|
| `api-key` | 兼容服务调用 | API Key repository | key 绑定的 tenant |
| `jwt` | API/机器身份 | OAuth2 Resource Server JWT | `tenant_id` claim + membership 校验 |
| `oidc` | Web 用户登录 | OIDC Authorization Code + PKCE | session principal 的 active tenant |
| `hybrid` | 迁移期默认 | JWT 优先，API Key fallback | 由已验证 credential 决定 |

生产 profile 禁止 `anonymous`。开发/H2 profile 可显式开启匿名默认租户，但必须显示安全告警且 CI 证明生产配置无法误开。

### 3.2 角色与权限

| 能力 | ADMIN | DEVELOPER | VIEWER |
|---|---:|---:|---:|
| 查看模型、数据集、知识库、评测、trace | ✓ | ✓ | ✓ |
| 运行预测、检索、RAG、评测、pipeline | ✓ | ✓ | — |
| 创建/修改模型、数据集、知识库、pipeline | ✓ | ✓ | — |
| 删除资源、管理成员、配额和 API Key | ✓ | — | — |
| 查看安全审计与导出 | ✓ | — | — |

授权按 `tenant + resource + action` 判定。资源不存在与无权访问默认都返回不会泄露跨租户存在性的稳定响应。

## 4. 主要接口合同

所有接口位于 `/api/v1`，沿用 `ApiErrorResponse`、requestId 和 traceId。

### 4.1 当前身份与租户

| 方法 | 路径 | 入参 | 成功出参 | 主要错误码 |
|---|---|---|---|---|
| `GET` | `/auth/me` | 当前 credential | principal、tenants、activeTenant、roles | `authentication_required` |
| `POST` | `/auth/active-tenant` | `tenantId` | active tenant | `tenant_access_denied` |
| `GET` | `/tenants/{id}/members` | 分页 | member page | `forbidden` |
| `PUT` | `/tenants/{id}/members/{subject}` | roles | member | `role_invalid`、`forbidden` |
| `DELETE` | `/tenants/{id}/members/{subject}` | — | `204` | `last_admin_required` |

### 4.2 API Key 生命周期

| 方法 | 路径 | 入参 | 成功出参 | 主要错误码 |
|---|---|---|---|---|
| `POST` | `/api-keys` | name、roles、expiresAt | `201`，仅本次返回 secret | `api_key_limit_exceeded` |
| `GET` | `/api-keys` | page/size | 不含 secret 的 key page | `forbidden` |
| `POST` | `/api-keys/{id}/rotate` | gracePeriodSeconds | 新 secret + 旧 key 截止时间 | `api_key_not_found` |
| `DELETE` | `/api-keys/{id}` | reason | `204` | `api_key_not_found` |

### 4.3 配额与审计

| 方法 | 路径 | 入参 | 成功出参 | 主要错误码 |
|---|---|---|---|---|
| `GET` | `/tenants/{id}/quota` | — | limits + current usage | `forbidden` |
| `PUT` | `/tenants/{id}/quota` | limits | quota | `quota_invalid` |
| `GET` | `/audit-events` | actor/action/resource/time/page | audit page | `forbidden` |
| `GET` | `/audit-events/export` | 同上 | NDJSON stream | `export_too_large` |

限流响应使用 HTTP `429`、`Retry-After`，错误码区分 `rate_limit_exceeded`、`concurrency_limit_exceeded` 和 `quota_exceeded`。

### 4.4 Dataset 与评测增强

| 方法 | 路径 | 入参 | 成功出参 | 主要错误码 |
|---|---|---|---|---|
| `POST` | `/datasets/{id}/entries` | entry + expectedVersion | entry | `dataset_version_conflict` |
| `PUT` | `/datasets/{id}/entries/{entryId}` | entry + expectedVersion | entry | `dataset_entry_not_found` |
| `DELETE` | `/datasets/{id}/entries/{entryId}` | expectedVersion | `204` | `dataset_version_conflict` |
| `POST` | `/datasets/{id}/imports` | JSON/JSONL file | `202` import job | `dataset_import_invalid` |
| `GET` | `/datasets/{id}/versions` | page | version page | `dataset_not_found` |
| `POST` | `/evaluations/{id}/retry` | failedOnly | new run | `evaluation_not_retryable` |
| `GET` | `/evaluations/{id}/samples` | status/page | sample result page | `evaluation_not_found` |

### 4.5 Pipeline DAG

| 方法 | 路径 | 入参 | 成功出参 | 主要错误码 |
|---|---|---|---|---|
| `POST` | `/pipelines` | nodes、edges、defaults | pipeline | `pipeline_cycle`、`pipeline_invalid` |
| `PUT` | `/pipelines/{id}` | version + DAG | pipeline | `pipeline_version_conflict` |
| `POST` | `/pipelines/{id}/runs` | input + overrides | `202` run | `pipeline_quota_exceeded` |
| `GET` | `/pipeline-runs/{runId}` | — | run + node states | `pipeline_run_not_found` |
| `POST` | `/pipeline-runs/{runId}/cancel` | — | cancelling state | `pipeline_run_terminal` |
| `GET` | `/pipeline-runs/{runId}/events` | `Last-Event-ID` | SSE | `pipeline_run_not_found` |
| `GET` | `/pipeline-runs/{runId}/trace` | format=json/ndjson | trace download | `trace_not_ready` |

## 5. 数据变更

新增迁移只能追加，禁止修改已发布 V1/V5/V7 的 checksum：

- V8：`tenants`、`tenant_memberships`、`api_keys`、租户安全配置；
- V9：`audit_events`，使用时间 + tenant + action 索引和保留策略字段；
- V10：`tenant_quotas`、`quota_usage_windows`、并发 lease；
- V11：Dataset `version`、导入任务、导入错误、版本快照；
- V12：评测样本结果、checkpoint、retry lineage 与恢复 lease；
- V13：pipeline definition/version、DAG node/edge、run/node-run/event；
- V14：secret reference、backup job、object metadata 和 retention state。

每次迁移必须通过 H2/MySQL/PostgreSQL 空库与 0.4 数据升级合同，并验证索引、外键、唯一约束和 checksum。

## 6. WBS 与分支规划

版本主分支：`codex/release-0.5`。每个任务使用独立分支并通过 PR 合入版本分支；版本 PR 合入 `main` 后删除远端和本地任务分支。

| 任务 | 分支 | 输入 | 输出 | 依赖 | 验收标准 |
|---|---|---|---|---|---|
| T-00 规划与威胁模型 | `codex/release-0.5-t-00-enterprise-plan` | 0.4 架构/CI 证据 | 本计划、权限矩阵、接口/迁移/WBS | 0.4.1 | 方案可直接拆给 Coding Agent；安全边界与错误合同明确 |
| T-01 身份与 RBAC | `codex/release-0.5-t-01-identity-rbac` | Spring Security、tenant context | JWT Resource Server/API Key hybrid、membership、授权 service | T-00 | issuer/audience/expiry、跨租户、三角色与配置 fail-closed 测试通过 |
| T-02 API Key 与审计 | `codex/release-0.5-t-02-api-key-audit` | T-01 principal | 哈希 key 生命周期、audit writer/query/export | T-01 | 明文只返回一次；轮换/撤销并发安全；敏感信息不入库/日志 |
| T-03 配额与流控 | `codex/release-0.5-t-03-quota-rate-limit` | principal、audit | tenant quota、rate/concurrency guard、429 contract | T-01～T-02 | 多实例共享用量；失败释放 lease；Retry-After 和指标正确 |
| T-04 Dataset 版本化 | `codex/release-0.5-t-04-dataset-versioning` | 现有 Dataset API | 样本 CRUD、乐观锁、导入报告、快照 | T-02 | 并发冲突稳定；非法行可定位；三数据库一致 |
| T-05 可恢复评测 | `codex/release-0.5-t-05-resumable-evaluation` | Dataset version、现有评测 | 样本结果、checkpoint、retry/recovery | T-03～T-04 | 重启不重复计分；失败样本可重跑；取消与 lease 安全 |
| T-06 Pipeline DAG | `codex/release-0.5-t-06-pipeline-dag` | capability/runtime、quota | DAG 定义、拓扑执行、node retry/timeout | T-03 | cycle 拒绝；fan-out/fan-in 确定；节点错误可追踪 |
| T-07 Pipeline 运行审计 UI | `codex/release-0.5-t-07-pipeline-observability-ui` | T-06 run/event | 运行列表、DAG 状态、SSE、trace 下载 | T-06 | Playwright 覆盖成功、失败、取消、重连和窄屏 |
| T-08 Secret/备份/对象存储 | `codex/release-0.5-t-08-platform-adapters` | audit、quota | secret reference SPI、backup job、object store SPI | T-02～T-03 | 默认 fixture 无云依赖；恢复演练；禁止路径穿越和 secret 泄漏 |
| T-09 供应链与发布门禁 | `codex/release-0.5-t-09-supply-chain` | 全部实现 | 0.5 版本、SBOM、签名、升级/回滚/DoD | T-01～T-08 | Maven/前端/三 DB/Playwright/Helm/images/Trivy/SBOM 全绿 |

### T-07 实施记录（2026-09-17）

- 新增 Pipeline 运行审计工作台与导航，提供状态过滤、刷新、空态/错误态和响应式运行详情。
- DAG 详情展示节点状态、attempt 时间/错误、运行事件时间线；活动运行通过 SSE 使用 `Last-Event-ID` 自动重连并去重。
- 支持取消活动运行和下载终态 JSON trace，成功、失败、取消状态使用稳定且可访问的视觉语义。
- 后端新增 tenant-scoped `GET /pipeline-runs` 分页/状态/pipeline 过滤和 `GET /pipelines/{id}`，JDBC 与 memory repository 使用一致稳定排序。
- Playwright 覆盖成功、失败、取消、SSE 重连去重和 390px 窄屏无横向溢出。

### T-06 实施记录（2026-09-16）

- 新增 V13 Pipeline DAG 持久化：definition/version、nodes/edges、run、node attempt 和可重放 event，统一提供 `JdbcTemplate`/内存 repository 合同并按 tenant 隔离。
- 新增 DAG 结构校验、稳定拓扑批次和确定性 fan-in 合并，拒绝缺失端点、重复边和直接/间接 cycle。
- 新增 pipeline 创建/乐观更新、异步 run、状态查询、取消、SSE event replay 与 JSON trace 下载接口；保留原有 `/pipelines/execute` 兼容接口。
- 节点执行支持 timeout、retry/backoff、attempt 级输入/输出/错误和时间持久化；运行显式携带 tenant/actor/version，并在启动时发现可恢复运行。
- repository CAS 与 cancel guard 阻止迟到 worker 覆盖终态；HTTP 合同覆盖跨租户不可见、版本冲突、cycle、运行/取消、event 与 trace。
- `mvn -B -ntp clean verify` 全模块通过；server 238 项测试全绿。

### T-01 实施记录（2026-09-16）

- 新增 Spring Security OAuth2 Resource Server，支持 `DISABLED`、`API_KEY`、`JWT`、`HYBRID` 四种模式；旧 `enabled=true` 配置继续映射到 API Key 模式；
- JWT 强制 issuer、audience、timestamp 和 tenant claim 校验，clock skew 限制在五分钟内；角色从 token claim 读取，并允许 JDBC membership 覆盖；
- 新增 `XnlpPrincipal`、`TenantRole`、租户授权 service 和 `/api/v1/auth/me`、租户成员管理 API；租户上下文只从已验证 principal 绑定，不信任 JWT/API Key 模式下的客户端租户 Header；
- V8 追加 `tenants`、`tenant_memberships` 表及 subject 索引，提供 JDBC/memory repository，保持 H2/MySQL/PostgreSQL repository 合同；
- API Key 兼容身份默认映射 ADMIN/DEVELOPER/VIEWER；HTTP 权限矩阵落实 VIEWER 只读、DEVELOPER 可执行/写入但不可删除、ADMIN 可管理成员和删除资源；
- JWT 测试覆盖三角色允许/拒绝路径、跨租户拒绝、错误 audience 和过期 token；membership 错误提供 `membership_not_found`、`last_admin_required`、`role_invalid` 稳定合同；
- `.env.example`、README 和 Helm values/deployment 已暴露 JWT/HYBRID 配置；issuer、audience 等元数据保留在 Deployment，API Key 继续存放于 Secret；
- T-01 当前交付 Resource Server 与 RBAC API 基线；浏览器 OIDC Authorization Code + PKCE 登录属于后续前端身份接入，不在本任务内伪造 IdP 流程；
- 定向安全、迁移和 JDBC repository 测试及完整 `mvn verify` 均通过。

### T-02 实施记录（2026-09-16）

- V9 新增 `api_keys` 与 `audit_events`，API Key 只保存 SHA-256 哈希和短前缀，提供租户/时间/action 索引与 retention 字段；
- 新增 JDBC/memory repository 和 lifecycle service，支持一次性明文创建、过期、撤销、带 grace period 轮换、最后使用时间与认证审计；
- 新增 `/api/v1/api-keys` 创建/列表/轮换/撤销 API，以及租户隔离的审计分页查询和 NDJSON 导出；列表与审计不返回 hash 或 secret；
- 动态 API Key 已接入 Spring Security 认证过滤器，旧环境变量 API Key 保持兼容；
- repository/service/HTTP/迁移测试覆盖明文不落库、过期/撤销、轮换 grace、租户隔离、脱敏和审计过滤；完整 `mvn clean verify` 通过。


### T-03 实施记录（2026-09-16）

- V10 新增租户配额、固定窗口用量和并发 lease 表，提供 JDBC/memory repository；条件更新确保多实例共享窗口原子领取，lease 支持释放和过期回收；
- 新增 `GET/PUT /api/v1/tenants/{tenantId}/quota`，只允许同租户 ADMIN 查看和更新请求、模型调用、知识导入及并发额度；
- 请求 guard 已接入安全过滤链：按 UTC 分钟限制请求、用 TTL lease 限制并发，拒绝响应使用 HTTP 429、`Retry-After` 及稳定错误码；未配置配额的既有租户保持兼容不受限；
- 定向 repository/guard/HTTP/迁移测试覆盖原子竞争、租户隔离、lease 释放/过期、跨租户拒绝和非法配置。

### T-04 实施记录（2026-09-16）

- V11 新增租户级 Dataset 元数据、样本、不可变版本快照、导入任务和逐行错误表，继续使用 `DataSource` + `JdbcTemplate`，并提供 memory/JDBC 同合同实现；
- 新增样本创建、替换、删除、版本分页、JSON 导入和导入报告 API；所有写操作使用 `expectedVersion` CAS，冲突返回 `dataset_version_conflict` 与当前版本；
- 现有 Dataset 创建流程会原子引导版本 0 及全量初始样本，重复引导幂等，不覆盖已有版本数据；
- 导入任务记录 actor、状态、计数、结果版本和错误行，非法记录返回 `dataset_import_invalid` 定位信息；
- repository、迁移和 HTTP 测试覆盖 CAS 回滚、快照不可变、导入终态、分页、租户隔离及跨租户存在性隐藏。


### T-05 实施记录（2026-09-16）

- V12 新增可恢复评测 run、逐样本结果、checkpoint 与 recovery lease，所有记录显式携带 tenant，lease 使用单调 fencing token 阻止过期 worker 写入；
- 评测启动时固定不可变 Dataset version/snapshot，后台任务显式传播 tenant，不再读取运行期间可能变化的 Dataset；
- 新增逐样本结果分页/状态过滤及 terminal run 重试 API；failed-only retry 保留 root/parent lineage 与原 Dataset version；
- worker 对每个样本持久化成功或失败结果，checkpoint 单调推进；应用启动后自动发现非终态 run 并按 lease 恢复，取消和终态转换使用 fenced CAS；
- memory/JDBC repository 合同、H2 MySQL/PostgreSQL 兼容模式、并发 lease/fencing、迁移及 HTTP 租户隔离测试覆盖关键恢复路径。


## 7. 跨任务 Quality Gate

- `mvn -B -ntp verify`，新增 core/server/client/CLI 合同测试；
- `npm run build --prefix xnlp-frontend` 与 Playwright；
- H2 外部 E2E、MySQL/PostgreSQL 同合同矩阵；
- 授权测试必须同时覆盖允许、拒绝、跨租户存在性隐藏和 audit；
- 并发测试覆盖 key rotation、quota lease、dataset optimistic lock、evaluation recovery 和 DAG cancel；
- Trivy HIGH/CRITICAL、secret scan、CycloneDX SBOM、镜像签名验证进入 CI；
- 日志和错误执行 credential/provider secret canary 扫描；
- 迁移测试覆盖 0.4 数据库升级和新安装，禁止改写历史 migration。

## 8. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| Header tenant 可伪造 | 跨租户越权 | tenant 只能来自 verified principal/key binding；Header 仅作 active tenant 请求 |
| JWT 配置错误 | 接受错误 issuer/token | 启动时校验 issuer/audience/JWK；生产 fail-closed；fixture 测试 |
| API Key 明文泄漏 | 长期凭据失陷 | 高熵 secret、哈希存储、一次性展示、前缀定位、轮换 grace window |
| 应用内限流多实例失真 | 配额绕过 | JDBC 原子 window/lease 为默认实现；本地缓存只做优化 |
| 异步恢复重复执行 | 重复调用和错误计费 | checkpoint + fencing token + idempotency key + terminal CAS |
| DAG 爆炸或循环 | 资源耗尽 | 静态 cycle/size/depth 校验，运行时节点/并发/输出预算 |
| 审计表无限增长 | 数据库膨胀 | tenant/time 分页索引、retention、归档 adapter、导出上限 |
| 对象路径/URL 注入 | 任意文件读写或 SSRF | opaque object key、allowlist endpoint、大小/类型/checksum 校验 |

## 9. Release 0.5 完成定义

只有以下证据全部具备才标记 Release 0.5 完成：

- 三种认证模式和三角色权限矩阵的自动化测试通过，生产配置 fail-closed；
- API Key、审计、配额在并发和跨租户场景下通过；
- Dataset/评测可追溯、可重跑、可从进程重启恢复；
- Pipeline DAG 成功/失败/取消/恢复均有持久化 node trace 和 UI；
- H2、MySQL、PostgreSQL 对空库与 0.4 升级执行同一合同套件；
- 前端 build/Playwright、Helm、镜像、Trivy、SBOM 和签名校验全部通过；
- 升级、备份恢复、密钥轮换和回滚演练均有可复现记录。
