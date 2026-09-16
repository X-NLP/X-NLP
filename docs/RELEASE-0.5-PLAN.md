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

### T-01 实施记录（2026-09-16）

- 新增 Spring Security OAuth2 Resource Server，支持 `DISABLED`、`API_KEY`、`JWT`、`HYBRID` 四种模式；旧 `enabled=true` 配置继续映射到 API Key 模式；
- JWT 强制 issuer、audience、timestamp 和 tenant claim 校验，clock skew 限制在五分钟内；角色从 token claim 读取，并允许 JDBC membership 覆盖；
- 新增 `XnlpPrincipal`、`TenantRole`、租户授权 service 和 `/api/v1/auth/me`、租户成员管理 API；租户上下文只从已验证 principal 绑定，不信任 JWT/API Key 模式下的客户端租户 Header；
- V8 追加 `tenants`、`tenant_memberships` 表及 subject 索引，提供 JDBC/memory repository，保持 H2/MySQL/PostgreSQL repository 合同；
- API Key 兼容身份默认映射 ADMIN/DEVELOPER/VIEWER；JWT 测试覆盖 viewer 读权限、admin 管理边界、跨租户拒绝、错误 audience 和过期 token；
- T-01 当前交付 Resource Server 与 RBAC API 基线；浏览器 OIDC Authorization Code + PKCE 登录属于后续前端身份接入，不在本任务内伪造 IdP 流程；
- 定向 15 条安全/迁移测试和完整 `mvn verify` 均通过。

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
