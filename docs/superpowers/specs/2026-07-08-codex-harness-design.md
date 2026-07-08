# X-NLP 的 Codex Harness 设计

日期：2026-07-08

## 目的

X-NLP 后续会持续通过 Codex 完成开发任务。任务会同时涉及 Spring Boot 后端、React 前端、模型协议配置、评测数据和回归测试。Codex harness 的作用是为每次任务提供一套固定的工作方式，让 Codex 能从正确的上下文开始，选择正确的流程，运行正确的验证，清理测试数据，并在测试通过后把改动提交和推送到 GitHub。

这个 harness 放在仓库内部，并随项目一起版本化。它不是替代 `AGENTS.md`，而是补充更具体的任务入口、模板、脚本和 X-NLP 专项规则。

## 目标

- 给 Codex 开发任务提供一个统一入口。
- 规范功能开发、缺陷修复、UI 回归、模型接入等任务类型。
- 让前端、后端、API smoke、UI smoke 的验证方式可重复执行。
- 让临时测试数据有固定前缀，并且容易清理。
- 固化 X-NLP 的专项规则，包括模型类型、标准协议、评测数据和前端响应式要求。
- 保留项目规则：任务测试通过后提交并推送到 GitHub。

## 非目标

- 第一版不新增 CI 系统。
- 第一版不做复杂的 multi-agent 编排。
- 不替代 Maven、Vite 或现有项目构建工具。
- 不做生产部署自动化。
- 不依赖只能在浏览器里完成、无法用脚本或文档近似验证的自动化。

## 目录结构设计

```text
X-NLP/
├── .codex/
│   ├── harness/
│   │   ├── START_HERE.md
│   │   ├── WORKFLOW.md
│   │   ├── CHECKLISTS.md
│   │   └── XNLP_RULES.md
│   └── templates/
│       ├── feature-task.md
│       ├── bugfix-task.md
│       ├── ui-regression-task.md
│       ├── model-integration-task.md
│       └── handoff-summary.md
├── docs/
│   └── codex/
│       ├── SOP.md
│       └── VERIFICATION.md
└── scripts/
    └── codex/
        ├── check.sh
        ├── smoke-api.sh
        ├── cleanup-test-data.sh
        └── env-status.sh
```

## Harness 文件

### `.codex/harness/START_HERE.md`

这是 Codex 开始 X-NLP 开发任务时应该先读的入口文件。它要求 Codex：

- 先阅读 `AGENTS.md`、`SPEC.md` 和对应的任务模板。
- 修改文件前先运行 `git status --short`。
- 判断当前任务类型：功能开发、缺陷修复、UI 回归、模型接入，还是文档任务。
- 使用 `.codex/harness/CHECKLISTS.md` 中对应的检查清单。
- 最终交付前运行必须的验证命令。
- 测试通过后提交并推送，除非用户明确要求不要提交或不要推送。

### `.codex/harness/WORKFLOW.md`

定义常规任务生命周期：

1. 上下文：阅读项目说明，检查相关文件，理解当前运行状态。
2. 计划或规格：较大的任务先创建或更新 spec/plan，再实现。
3. 实现：改动范围只覆盖当前任务需要的文件。
4. 验证：根据改动范围运行对应命令。
5. 回归：检查受影响的 UI 或 API 路径。
6. 清理：删除 `ui-test-*`、`ui-reg-*`、`debug-*` 等临时测试数据。
7. 交付：总结改动、测试结果、残余风险、commit hash 和 push 结果。

### `.codex/harness/CHECKLISTS.md`

包含不同任务类型的检查清单：

- 功能开发清单
- 缺陷修复清单
- UI 回归清单
- 后端 API 清单
- 模型接入清单
- 发布和推送清单

每个清单要写清楚必须运行的命令、必须做的手工或浏览器检查，以及测试数据清理要求。

### `.codex/harness/XNLP_RULES.md`

记录 X-NLP 专项规则：

- 模型类型分为 `CHAT`、`EMBEDDING`、`RERANKING`；前端和后端行为都必须按类型区分。
- 模型供应商必须使用标准协议接口，例如 OpenAI-compatible chat、OpenAI embeddings、Ollama、Anthropic Messages、Gemini、Cohere rerank、Jina rerank。
- 除非用户明确批准，否则不接受非标准模型适配。
- 评测数据必须回归创建、列表、查看 entries、导出和删除。
- 前端页面必须保持响应式，不允许页面级横向溢出。
- 任何模型或数据集测试数据都必须使用清晰前缀，并在测试后清理。

## 模板

### 功能开发模板

用于让 Codex 记录用户目标、影响模块、API/UI 变更、数据模型变更、测试方式和上线注意事项。

### 缺陷修复模板

用于记录复现步骤、实际行为、期望行为、根因、修复范围、回归测试和清理动作。

### UI 回归模板

用于提醒 Codex 检查所有路由、浏览器 console 错误、页面级横向溢出、关键交互、表格滚动、表单、弹窗和清理动作。

### 模型接入模板

用于记录供应商、协议、模型类型、所需凭证、base URL、请求/响应语义、验证方式和不支持的行为。

### Handoff 总结模板

用于长任务或跨 Codex 会话交接，提供结构化总结。

## 脚本

### `scripts/codex/check.sh`

运行标准本地验证套件：

```bash
npm run build --prefix xnlp-frontend
mvn test -pl xnlp-core -Dmaven.repo.local=/tmp/m2
mvn test -pl xnlp-server -am -Dmaven.repo.local=/tmp/m2
```

脚本应该 fail fast，并在运行每条命令前先打印命令。

### `scripts/codex/smoke-api.sh`

检查本地后端 API。默认后端地址是 `http://127.0.0.1:8080`，也可以通过 `XNLP_API_BASE` 覆盖。

第一版 smoke 端点：

- `GET /health`
- `GET /api/v1/models`
- `GET /api/v1/models/capabilities`
- `GET /api/v1/datasets`
- `GET /api/v1/evaluations`

脚本应该打印 HTTP 状态码和简短响应预览。它不能依赖外部模型凭证。

### `scripts/codex/cleanup-test-data.sh`

清理 Codex 测试产生的数据：

- 名称匹配 `ui-test-*`、`ui-reg-*`、`codex-test-*`、`debug-*` 的模型配置。
- 名称匹配相同前缀的数据集。

脚本默认 dry-run，只展示将要删除的内容。传入 `--apply` 后才真正删除。

### `scripts/codex/env-status.sh`

输出本地开发环境状态：

- 当前分支和 `git status`。
- `5173` 和 `8080` 端口是否监听。
- 前端和后端启动命令提示。
- Java、Maven、Node、npm 版本。

## 验证策略

不同改动范围对应不同验证要求：

| 改动范围 | 必须验证 |
|---|---|
| 前端代码 | `npm run build --prefix xnlp-frontend`，并检查受影响页面 |
| 后端代码 | `mvn test -pl xnlp-server -am -Dmaven.repo.local=/tmp/m2`；接口行为变化时跑 API smoke |
| Core 代码 | `mvn test -pl xnlp-core -Dmaven.repo.local=/tmp/m2` |
| 模型管理 | API smoke、模型列表 UI、按模型类型检查行为 |
| 数据集/评测 | 数据集 create/list/view/export/delete smoke，评测列表 UI |
| 跨模块改动 | `scripts/codex/check.sh` |

前端浏览器检查必须包括：

- 页面加载没有 console error。
- 没有页面级横向溢出。
- 关键控件根据类型和状态正确启用或禁用。
- 表单有可理解的校验信息。

## Git 和交付策略

- 修改前和最终交付前都运行 `git status --short`。
- 不回滚和当前任务无关的用户改动。
- 测试通过后只暂存当前任务相关文件。
- commit 使用 Conventional Commits。
- 必要时使用现有 token 方式推送到当前 GitHub 仓库。
- 最终回复必须包含改动文件、验证命令、commit hash、push 状态和残余风险。

## MVP 验收标准

第一版 harness 完成的标准：

- 存在 `.codex/harness/START_HERE.md`、`WORKFLOW.md`、`CHECKLISTS.md`、`XNLP_RULES.md`。
- `.codex/templates/*.md` 包含上面列出的五类任务模板。
- `docs/codex/SOP.md` 和 `docs/codex/VERIFICATION.md` 说明如何使用 harness。
- 存在并可执行 `scripts/codex/check.sh`、`smoke-api.sh`、`cleanup-test-data.sh`、`env-status.sh`。
- `scripts/codex/check.sh` 能在当前 workspace 通过。
- 后端运行时，`scripts/codex/smoke-api.sh` 能正常工作。
- `scripts/codex/cleanup-test-data.sh` 支持 dry-run 和 `--apply`。

## 风险和规避

- 风险：Harness 文档逐渐过期。
  - 规避：文件保持短小、面向任务；工作流变化时同步更新 harness。
- 风险：清理脚本误删用户数据。
  - 规避：只匹配明确测试前缀，默认 dry-run。
- 风险：服务没有启动时 smoke 脚本失败。
  - 规避：打印清晰的启动提示，并给出可理解的错误信息。
- 风险：Codex 跳过 harness。
  - 规避：`START_HERE.md` 作为单一入口；如果用户批准，后续可以在 `AGENTS.md` 中显式指向它。

## 未决事项

MVP 没有未决设计问题。CI 集成、multi-agent worktree、部署自动化都暂缓，等本地开发 harness 使用稳定后再考虑。
