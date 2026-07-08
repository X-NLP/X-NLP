# Codex Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 X-NLP 仓库建立第一版 Codex 开发 harness，让后续功能开发、缺陷修复、UI 回归、模型接入和交接都有固定入口、模板、验证脚本和交付规则。

**Architecture:** Harness 以仓库内文档和脚本实现，不引入新的运行时服务。`.codex/harness` 放任务入口、流程、检查清单和 X-NLP 专项规则；`.codex/templates` 放可复用任务模板；`docs/codex` 放面向开发者的 SOP 和验证说明；`scripts/codex` 放可重复执行的本地检查、API smoke、测试数据清理和环境状态脚本。

**Tech Stack:** Markdown、Bash、Maven、Spring Boot、React/Vite、npm、curl、git、lsof、Java、Node.js。

---

## 文件职责

- Create: `.codex/harness/START_HERE.md`，Codex 开始 X-NLP 开发任务时的单一入口。
- Create: `.codex/harness/WORKFLOW.md`，定义上下文、计划、实现、验证、清理、提交推送的生命周期。
- Create: `.codex/harness/CHECKLISTS.md`，按任务类型列出必须检查和必须运行的命令。
- Create: `.codex/harness/XNLP_RULES.md`，固化模型类型、标准协议、评测数据、响应式 UI 和测试数据命名规则。
- Create: `.codex/templates/feature-task.md`，功能开发任务记录模板。
- Create: `.codex/templates/bugfix-task.md`，缺陷修复任务记录模板。
- Create: `.codex/templates/ui-regression-task.md`，UI 回归任务记录模板。
- Create: `.codex/templates/model-integration-task.md`，模型接入任务记录模板。
- Create: `.codex/templates/handoff-summary.md`，长任务或跨会话交接总结模板。
- Create: `docs/codex/SOP.md`，说明人和 Codex 如何使用 harness 完成一次开发任务。
- Create: `docs/codex/VERIFICATION.md`，说明不同改动范围对应的验证命令、启动方式和验收标准。
- Create: `scripts/codex/check.sh`，运行标准本地验证套件并 fail fast。
- Create: `scripts/codex/smoke-api.sh`，检查本地后端基础 API，不依赖外部模型凭证。
- Create: `scripts/codex/cleanup-test-data.sh`，默认 dry-run 清理测试前缀数据，`--apply` 才执行删除。
- Create: `scripts/codex/env-status.sh`，输出 git、端口、启动命令和本机工具版本状态。

## Task 1: 创建 harness 文档入口和工作流

**Files:**
- Create: `.codex/harness/START_HERE.md`
- Create: `.codex/harness/WORKFLOW.md`

- [ ] **Step 1: 创建目录**

Run:

```bash
mkdir -p .codex/harness
```

Expected: `.codex/harness` 目录存在。

- [ ] **Step 2: 写入 `.codex/harness/START_HERE.md`**

Create `.codex/harness/START_HERE.md` with exactly this content:

```markdown
# X-NLP Codex 任务入口

这是 Codex 处理 X-NLP 开发任务时的第一份项目内上下文。它补充 `AGENTS.md`，不替代 `AGENTS.md`。

## 开始前必须做

1. 阅读仓库根目录的 `AGENTS.md`。
2. 运行 `git status --short`，确认当前工作区状态。
3. 根据用户请求判断任务类型：功能开发、缺陷修复、UI 回归、模型接入、文档任务。
4. 阅读 `.codex/harness/XNLP_RULES.md`。
5. 阅读 `.codex/harness/CHECKLISTS.md` 中对应任务类型的清单。
6. 如果任务较大，先在 `docs/superpowers/specs/` 或 `docs/superpowers/plans/` 中补充设计或计划。

## 任务模板选择

- 功能开发：使用 `.codex/templates/feature-task.md`。
- 缺陷修复：使用 `.codex/templates/bugfix-task.md`。
- UI 回归：使用 `.codex/templates/ui-regression-task.md`。
- 模型接入：使用 `.codex/templates/model-integration-task.md`。
- 长任务交接：使用 `.codex/templates/handoff-summary.md`。

## 修改原则

- 只修改当前任务需要的文件。
- 不回滚用户或其他任务留下的无关改动。
- 模型管理、评测数据、前端响应式和 API 行为必须同时考虑。
- 临时测试数据必须使用 `ui-test-*`、`ui-reg-*`、`codex-test-*` 或 `debug-*` 前缀。

## 验证原则

- 前端改动至少运行 `npm run build --prefix xnlp-frontend`。
- 后端改动至少运行 `mvn test -pl xnlp-server -Dmaven.repo.local=/tmp/m2`。
- Core 改动至少运行 `mvn test -pl xnlp-core -Dmaven.repo.local=/tmp/m2`。
- 跨模块改动运行 `scripts/codex/check.sh`。
- API 行为变化时，在后端启动后运行 `scripts/codex/smoke-api.sh`。
- UI 变化必须用浏览器检查受影响页面，没有 console error，没有页面级横向溢出。

## 交付原则

- 最终交付前运行 `git status --short`。
- 测试通过后提交并推送到当前 GitHub 仓库，除非用户明确要求不要提交或不要推送。
- commit message 使用 Conventional Commits。
- 最终回复包含：改动摘要、验证命令、commit hash、push 状态、残余风险。
```

- [ ] **Step 3: 写入 `.codex/harness/WORKFLOW.md`**

Create `.codex/harness/WORKFLOW.md` with exactly this content:

```markdown
# X-NLP Codex 工作流

## 1. 上下文

- 阅读 `AGENTS.md` 和 `.codex/harness/START_HERE.md`。
- 运行 `git status --short`。
- 查找当前任务相关文件，优先使用 `rg` 和 `rg --files`。
- 如果本地服务已经运行，先用 `scripts/codex/env-status.sh` 或 `lsof -i :5173 -i :8080` 确认状态。

## 2. 计划或规格

- 小修复可以直接实现，但必须先理解根因。
- 多文件、多模块或新增能力任务要先写 spec 或 plan。
- 已有设计文档在 `docs/superpowers/specs/`。
- 可执行计划放在 `docs/superpowers/plans/`。

## 3. 实现

- 前端遵循现有 React、Vite、Tailwind 风格。
- 后端遵循 Spring Boot、构造器注入、集中配置和现有 controller/service 分层。
- 模型能力必须按 `CHAT`、`EMBEDDING`、`RERANKING` 区分。
- 不新增非标准模型协议适配，除非用户明确批准。
- 不做和当前任务无关的重构。

## 4. 验证

- 根据 `.codex/harness/CHECKLISTS.md` 选择验证命令。
- 修改范围跨前端和后端时运行 `scripts/codex/check.sh`。
- 后端本地 smoke 默认使用 `http://127.0.0.1:8080`。
- 前端本地地址默认使用 `http://localhost:5173/`。

## 5. 回归

- UI 任务检查所有受影响路由。
- API 任务检查正常路径和错误路径。
- 模型任务至少检查模型列表、模型详情、连接测试、active 状态和类型约束。
- 评测任务至少检查数据集列表、entries 查看、评测创建、结果展示和对比页面。

## 6. 清理

- 测试数据名称使用 `ui-test-*`、`ui-reg-*`、`codex-test-*` 或 `debug-*`。
- 先运行 `scripts/codex/cleanup-test-data.sh` 查看 dry-run。
- 确认只命中测试数据后再运行 `scripts/codex/cleanup-test-data.sh --apply`。

## 7. 提交和推送

- 运行 `git status --short` 确认只包含当前任务相关文件。
- 使用 Conventional Commits，例如 `feat: add model provider selector`。
- 测试通过后推送到当前 GitHub 仓库。
- 不把 `GITHUB_TOKEN` 写入磁盘、远端 URL 或日志。
```

- [ ] **Step 4: 检查文件内容**

Run:

```bash
sed -n '1,220p' .codex/harness/START_HERE.md
sed -n '1,220p' .codex/harness/WORKFLOW.md
```

Expected: 两个文件都是中文，且包含验证、清理、提交推送规则。

- [ ] **Step 5: Commit**

Run:

```bash
git add .codex/harness/START_HERE.md .codex/harness/WORKFLOW.md
git commit -m "docs: add codex harness workflow"
```

Expected: commit 成功。

## Task 2: 创建 X-NLP 专项规则和检查清单

**Files:**
- Create: `.codex/harness/XNLP_RULES.md`
- Create: `.codex/harness/CHECKLISTS.md`

- [ ] **Step 1: 写入 `.codex/harness/XNLP_RULES.md`**

Create `.codex/harness/XNLP_RULES.md` with exactly this content:

```markdown
# X-NLP 专项规则

## 模型类型

X-NLP 的模型类型固定为三类：

- `CHAT`：大语言模型，用于对话、生成、分类、抽取、摘要等文本生成任务。
- `EMBEDDING`：向量模型，用于文本向量化、相似度、检索召回。
- `RERANKING`：排序模型，用于候选结果重排。

前端表单、后端校验、active 行为、连接测试和评测选择都必须按模型类型区分。

## 标准协议

模型供应商必须通过标准协议接口对接。第一版认可的协议包括：

- OpenAI-compatible Chat Completions 或 Responses 语义。
- OpenAI-compatible Embeddings。
- Ollama 本地模型 API。
- Anthropic Messages API。
- Gemini 标准生成 API。
- Cohere Rerank API。
- Jina Rerank API。

除非用户明确批准，不接受只为单个私有模型硬编码的非标准适配。

## 模型配置

模型配置必须维护以下信息：

- 供应商名称。
- 模型名称。
- 模型类型。
- 协议类型。
- Base URL 或 endpoint。
- API key 名称或密钥引用。
- 是否官方模型。
- 是否启用。
- 连接测试状态或最近一次测试结果。

密钥不得提交到仓库。示例值必须使用占位格式，例如 `sk-...` 或 `${PROVIDER_API_KEY}`。

## 评测数据

- 数据集必须支持创建、列表、查看 entries、导出和删除。
- 评测运行必须可追踪使用的数据集、模型、任务类型、指标和时间。
- 对比页面必须展示评测变化，不得只展示单次结果。

## 前端响应式

- 所有页面必须支持桌面和移动宽度。
- 不允许页面级横向溢出。
- 表格或宽内容应该在自身容器内滚动。
- 表单按钮、导航、筛选器和弹窗在移动端必须可操作。

## 测试数据

Codex 产生的临时数据只能使用这些前缀：

- `ui-test-*`
- `ui-reg-*`
- `codex-test-*`
- `debug-*`

清理脚本只能删除匹配这些前缀的数据，默认必须 dry-run。
```

- [ ] **Step 2: 写入 `.codex/harness/CHECKLISTS.md`**

Create `.codex/harness/CHECKLISTS.md` with exactly this content:

```markdown
# X-NLP Codex 检查清单

## 功能开发

- [ ] 记录用户目标、影响模块和验收标准。
- [ ] 后端 API 变化时确认 controller、service、DTO 和错误响应一致。
- [ ] 前端页面变化时确认桌面和移动布局都可用。
- [ ] 使用 `codex-test-*` 前缀创建临时数据。
- [ ] 按改动范围运行验证命令。
- [ ] 清理临时测试数据。
- [ ] 测试通过后 commit 并 push。

## 缺陷修复

- [ ] 写清复现步骤、实际行为和期望行为。
- [ ] 定位根因，不只处理表面报错。
- [ ] 添加或更新能覆盖该问题的测试。
- [ ] 运行最小相关测试，再运行受影响模块测试。
- [ ] 检查错误提示是否对用户可理解。
- [ ] 测试通过后 commit 并 push。

## UI 回归

- [ ] 检查 `/` 首页。
- [ ] 检查 `/models` 模型管理页。
- [ ] 检查 `/datasets` 数据集页。
- [ ] 检查 `/evaluations` 评测页。
- [ ] 检查 `/compare` 对比页。
- [ ] 检查浏览器 console，没有新 error。
- [ ] 检查页面级横向溢出。
- [ ] 检查关键表单、弹窗、按钮、筛选和空状态。
- [ ] 使用 `ui-reg-*` 前缀创建临时数据并清理。

## 后端 API

- [ ] 运行 `mvn test -pl xnlp-server -Dmaven.repo.local=/tmp/m2`。
- [ ] 后端启动后运行 `scripts/codex/smoke-api.sh`。
- [ ] 检查成功响应和错误响应结构。
- [ ] 确认接口不依赖真实外部模型凭证才能完成基础 smoke。

## 模型接入

- [ ] 确认模型类型是 `CHAT`、`EMBEDDING` 或 `RERANKING`。
- [ ] 确认协议属于 `.codex/harness/XNLP_RULES.md` 认可的标准协议。
- [ ] 检查供应商、模型名称、Base URL、API key 引用、官方/自定义标记。
- [ ] 检查连接测试不会把非 CHAT 模型加载进 ChatModel runtime。
- [ ] 检查 active 行为按模型类型隔离。
- [ ] 检查前端模型选择器按类型过滤可选模型。

## 发布和推送

- [ ] 运行 `git status --short`。
- [ ] 只暂存当前任务相关文件。
- [ ] commit message 使用 Conventional Commits。
- [ ] 使用安全 token 方式 push，不把 token 写入磁盘。
- [ ] 最终回复说明 commit hash 和 push 状态。
```

- [ ] **Step 3: 检查规则覆盖设计要求**

Run:

```bash
rg -n "CHAT|EMBEDDING|RERANKING|OpenAI|Ollama|Anthropic|Gemini|Cohere|Jina|横向溢出|dry-run" .codex/harness
```

Expected: 输出覆盖模型类型、标准协议、响应式和清理 dry-run 规则。

- [ ] **Step 4: Commit**

Run:

```bash
git add .codex/harness/XNLP_RULES.md .codex/harness/CHECKLISTS.md
git commit -m "docs: add xnlp harness rules"
```

Expected: commit 成功。

## Task 3: 创建任务模板

**Files:**
- Create: `.codex/templates/feature-task.md`
- Create: `.codex/templates/bugfix-task.md`
- Create: `.codex/templates/ui-regression-task.md`
- Create: `.codex/templates/model-integration-task.md`
- Create: `.codex/templates/handoff-summary.md`

- [ ] **Step 1: 创建目录**

Run:

```bash
mkdir -p .codex/templates
```

Expected: `.codex/templates` 目录存在。

- [ ] **Step 2: 写入功能开发模板**

Create `.codex/templates/feature-task.md` with exactly this content:

```markdown
# 功能开发任务

## 用户目标

- 请求：
- 验收标准：
- 不做范围：

## 影响范围

- 前端页面：
- 后端 API：
- Core/domain：
- 数据文件或存储：

## 实现记录

- 关键设计：
- 主要文件：
- 兼容性考虑：

## 测试数据

- 前缀：`codex-test-`
- 创建的数据：
- 清理方式：`scripts/codex/cleanup-test-data.sh --apply`

## 验证

- 前端 build：
- 后端测试：
- API smoke：
- 浏览器检查：

## 交付

- Commit：
- Push：
- 残余风险：
```

- [ ] **Step 3: 写入缺陷修复模板**

Create `.codex/templates/bugfix-task.md` with exactly this content:

```markdown
# 缺陷修复任务

## 问题

- 复现步骤：
- 实际行为：
- 期望行为：
- 报错信息：

## 根因

- 触发条件：
- 根因文件：
- 为什么之前没有覆盖：

## 修复

- 修改范围：
- 行为变化：
- 错误提示变化：

## 回归

- 最小复现测试：
- 模块测试：
- UI/API 检查：
- 测试数据清理：

## 交付

- Commit：
- Push：
- 残余风险：
```

- [ ] **Step 4: 写入 UI 回归模板**

Create `.codex/templates/ui-regression-task.md` with exactly this content:

```markdown
# UI 回归任务

## 范围

- 路由：`/`、`/models`、`/datasets`、`/evaluations`、`/compare`
- 浏览器：Codex in-app browser
- 视口：桌面宽度、移动宽度

## 检查项

- 页面加载：
- Console error：
- 页面级横向溢出：
- 导航：
- 表格和列表：
- 表单和弹窗：
- 空状态和错误状态：

## 测试数据

- 前缀：`ui-reg-`
- 创建的数据：
- 清理结果：

## 问题记录

- 问题 1：
- 问题 2：
- 问题 3：

## 修复和验证

- 修复文件：
- `npm run build --prefix xnlp-frontend`：
- 浏览器回归：
```

- [ ] **Step 5: 写入模型接入模板**

Create `.codex/templates/model-integration-task.md` with exactly this content:

```markdown
# 模型接入任务

## 供应商和协议

- 供应商：
- 官方或自定义：
- 标准协议：
- Base URL：
- API key 环境变量或密钥引用：

## 模型

- 模型名称：
- 模型类型：`CHAT` / `EMBEDDING` / `RERANKING`
- 默认参数：
- 不支持能力：

## 后端行为

- 配置字段：
- 连接测试方式：
- active 行为：
- 错误响应：

## 前端行为

- 供应商选择：
- 模型选择：
- 官方/自定义标记：
- 按类型过滤：

## 验证

- 单元测试：
- API smoke：
- UI 检查：
- 不泄露密钥检查：
```

- [ ] **Step 6: 写入交接总结模板**

Create `.codex/templates/handoff-summary.md` with exactly this content:

```markdown
# Codex 交接总结

## 当前目标

- 用户请求：
- 当前状态：
- 下一步：

## 已完成

- 改动 1：
- 改动 2：
- 改动 3：

## 关键文件

- 文件 1：
- 文件 2：
- 文件 3：

## 验证结果

- 命令 1：
- 命令 2：
- 浏览器检查：

## Git 状态

- Branch：
- Commit：
- Push：
- 未提交文件：

## 注意事项

- 用户偏好：中文文档；测试通过后提交并推送。
- 不要回滚无关改动。
- 不要把 `GITHUB_TOKEN` 写入磁盘或远端 URL。
```

- [ ] **Step 7: 检查模板完整性**

Run:

```bash
find .codex/templates -maxdepth 1 -type f -name '*.md' | sort
rg -n "Commit|Push|验证|清理|模型类型|横向溢出" .codex/templates
```

Expected: 输出 5 个模板文件，并能看到提交、推送、验证、清理、模型和 UI 回归字段。

- [ ] **Step 8: Commit**

Run:

```bash
git add .codex/templates
git commit -m "docs: add codex task templates"
```

Expected: commit 成功。

## Task 4: 创建 Codex 使用说明文档

**Files:**
- Create: `docs/codex/SOP.md`
- Create: `docs/codex/VERIFICATION.md`

- [ ] **Step 1: 创建目录**

Run:

```bash
mkdir -p docs/codex
```

Expected: `docs/codex` 目录存在。

- [ ] **Step 2: 写入 `docs/codex/SOP.md`**

Create `docs/codex/SOP.md` with exactly this content:

```markdown
# X-NLP Codex SOP

## 适用范围

这份 SOP 用于 X-NLP 仓库内的 Codex 开发任务，包括前端、后端、模型管理、评测数据、UI 回归和文档维护。

## 标准流程

1. 阅读 `AGENTS.md`。
2. 阅读 `.codex/harness/START_HERE.md`。
3. 运行 `git status --short`。
4. 按任务类型选择 `.codex/templates/` 下的模板。
5. 阅读 `.codex/harness/CHECKLISTS.md` 中对应清单。
6. 实现前明确验证命令。
7. 实现后运行验证并清理测试数据。
8. 测试通过后 commit 并 push。
9. 最终回复说明改动、验证、commit、push 和风险。

## 本地启动

后端默认使用 8080 端口：

```bash
cd xnlp-server
mvn spring-boot:run -Dmaven.repo.local=/tmp/m2 -DskipTests -Dspring-boot.run.arguments=--server.port=8080
```

前端默认使用 5173 端口：

```bash
npm run dev --prefix xnlp-frontend
```

## GitHub 推送

用户要求任务测试通过后推送到当前 GitHub 仓库。推送时使用临时 credential helper，不把 token 写入磁盘：

```bash
git -c credential.helper='!f() { echo username=x-access-token; echo password=$GITHUB_TOKEN; }; f' push origin main
```

## 安全规则

- 不提交真实 API key。
- 不修改远端 URL 来嵌入 token。
- 不删除非测试前缀数据。
- 不回滚无关用户改动。
```

- [ ] **Step 3: 写入 `docs/codex/VERIFICATION.md`**

Create `docs/codex/VERIFICATION.md` with exactly this content:

```markdown
# X-NLP 验证说明

## 标准验证套件

跨模块改动运行：

```bash
scripts/codex/check.sh
```

这个脚本依次运行：

```bash
npm run build --prefix xnlp-frontend
mvn test -pl xnlp-core -Dmaven.repo.local=/tmp/m2
mvn test -pl xnlp-server -Dmaven.repo.local=/tmp/m2
```

## 按改动范围验证

| 改动范围 | 必须验证 |
|---|---|
| 前端代码 | `npm run build --prefix xnlp-frontend`，浏览器检查受影响页面 |
| 后端代码 | `mvn test -pl xnlp-server -Dmaven.repo.local=/tmp/m2` |
| Core 代码 | `mvn test -pl xnlp-core -Dmaven.repo.local=/tmp/m2` |
| 模型管理 | 后端测试、API smoke、模型列表 UI、模型 active 行为 |
| 数据集/评测 | API smoke、数据集 create/list/view/export/delete、评测列表和对比页 |
| 跨模块改动 | `scripts/codex/check.sh` |

## API smoke

后端启动后运行：

```bash
scripts/codex/smoke-api.sh
```

使用自定义后端地址：

```bash
XNLP_API_BASE=http://127.0.0.1:8080 scripts/codex/smoke-api.sh
```

第一版 smoke 检查：

- `GET /health`
- `GET /api/v1/models`
- `GET /api/v1/models/capabilities`
- `GET /api/v1/datasets`
- `GET /api/v1/evaluations`

## UI 检查

- 页面加载没有 console error。
- 没有页面级横向溢出。
- 关键控件根据状态正确启用或禁用。
- 表单校验信息可理解。
- 移动宽度下导航、表格、按钮和弹窗可操作。

## 测试数据清理

先 dry-run：

```bash
scripts/codex/cleanup-test-data.sh
```

确认只匹配测试数据后执行：

```bash
scripts/codex/cleanup-test-data.sh --apply
```
```

- [ ] **Step 4: 检查文档链接到 harness**

Run:

```bash
rg -n "START_HERE|CHECKLISTS|check.sh|smoke-api.sh|cleanup-test-data.sh|GITHUB_TOKEN" docs/codex
```

Expected: SOP 和 VERIFICATION 都能检索到 harness 或脚本引用。

- [ ] **Step 5: Commit**

Run:

```bash
git add docs/codex/SOP.md docs/codex/VERIFICATION.md
git commit -m "docs: add codex operating docs"
```

Expected: commit 成功。

## Task 5: 创建本地验证脚本

**Files:**
- Create: `scripts/codex/check.sh`
- Create: `scripts/codex/smoke-api.sh`
- Create: `scripts/codex/cleanup-test-data.sh`
- Create: `scripts/codex/env-status.sh`

- [ ] **Step 1: 创建目录**

Run:

```bash
mkdir -p scripts/codex
```

Expected: `scripts/codex` 目录存在。

- [ ] **Step 2: 写入 `scripts/codex/check.sh`**

Create `scripts/codex/check.sh` with exactly this content:

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

run() {
  echo "+ $*"
  "$@"
}

run npm run build --prefix xnlp-frontend
run mvn test -pl xnlp-core -Dmaven.repo.local=/tmp/m2
run mvn test -pl xnlp-server -Dmaven.repo.local=/tmp/m2
```

- [ ] **Step 3: 写入 `scripts/codex/smoke-api.sh`**

Create `scripts/codex/smoke-api.sh` with exactly this content:

```bash
#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${XNLP_API_BASE:-http://127.0.0.1:8080}"
ENDPOINTS=(
  "/health"
  "/api/v1/models"
  "/api/v1/models/capabilities"
  "/api/v1/datasets"
  "/api/v1/evaluations"
)

echo "X-NLP API smoke base: ${BASE_URL}"

for endpoint in "${ENDPOINTS[@]}"; do
  url="${BASE_URL}${endpoint}"
  tmp_body="$(mktemp)"
  status="$(curl -sS -o "$tmp_body" -w '%{http_code}' "$url" || true)"
  preview="$(tr '\n' ' ' < "$tmp_body" | cut -c 1-180)"
  rm -f "$tmp_body"

  echo "${status} ${endpoint} ${preview}"

  if [[ ! "$status" =~ ^2 ]]; then
    echo "Smoke failed for ${url}. Start backend with: cd xnlp-server && mvn spring-boot:run -Dmaven.repo.local=/tmp/m2 -DskipTests -Dspring-boot.run.arguments=--server.port=8080" >&2
    exit 1
  fi
done
```

- [ ] **Step 4: 写入 `scripts/codex/cleanup-test-data.sh`**

Create `scripts/codex/cleanup-test-data.sh` with exactly this content:

```bash
#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${XNLP_API_BASE:-http://127.0.0.1:8080}"
APPLY="false"
PREFIX_REGEX='^(ui-test-|ui-reg-|codex-test-|debug-)'

if [[ "${1:-}" == "--apply" ]]; then
  APPLY="true"
elif [[ "${1:-}" != "" ]]; then
  echo "Usage: $0 [--apply]" >&2
  exit 2
fi

need_tool() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 2
  fi
}

need_tool curl
need_tool jq

delete_matches() {
  local label="$1"
  local list_path="$2"
  local delete_path_prefix="$3"

  echo "Checking ${label} from ${BASE_URL}${list_path}"
  local payload
  payload="$(curl -sS "${BASE_URL}${list_path}")"

  echo "$payload" | jq -r '.[]? | [.id, (.name // .displayName // "")] | @tsv' | while IFS=$'\t' read -r id name; do
    if [[ "$name" =~ $PREFIX_REGEX ]]; then
      if [[ "$APPLY" == "true" ]]; then
        echo "DELETE ${label}: ${name} (${id})"
        curl -sS -X DELETE "${BASE_URL}${delete_path_prefix}/${id}" >/dev/null
      else
        echo "DRY-RUN ${label}: ${name} (${id})"
      fi
    fi
  done
}

if [[ "$APPLY" == "true" ]]; then
  echo "Apply mode: matching test data will be deleted. Base: ${BASE_URL}"
else
  echo "Dry-run mode: pass --apply to delete matching test data. Base: ${BASE_URL}"
fi

delete_matches "model" "/api/v1/models" "/api/v1/models"
delete_matches "dataset" "/api/v1/datasets" "/api/v1/datasets"
```

- [ ] **Step 5: 写入 `scripts/codex/env-status.sh`**

Create `scripts/codex/env-status.sh` with exactly this content:

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

echo "== Git =="
git branch --show-current || true
git status --short || true

echo
echo "== Ports =="
if command -v lsof >/dev/null 2>&1; then
  lsof -i :5173 -sTCP:LISTEN || echo "Port 5173 is not listening"
  lsof -i :8080 -sTCP:LISTEN || echo "Port 8080 is not listening"
else
  echo "lsof is not available"
fi

echo
echo "== Startup hints =="
echo "Frontend: npm run dev --prefix xnlp-frontend"
echo "Backend:  cd xnlp-server && mvn spring-boot:run -Dmaven.repo.local=/tmp/m2 -DskipTests -Dspring-boot.run.arguments=--server.port=8080"

echo
echo "== Tool versions =="
java -version 2>&1 | head -n 1 || true
mvn -version 2>/dev/null | head -n 1 || true
node --version || true
npm --version || true
```

- [ ] **Step 6: 设置脚本可执行**

Run:

```bash
chmod +x scripts/codex/check.sh scripts/codex/smoke-api.sh scripts/codex/cleanup-test-data.sh scripts/codex/env-status.sh
```

Expected: 四个脚本都可执行。

- [ ] **Step 7: Shell 语法检查**

Run:

```bash
bash -n scripts/codex/check.sh
bash -n scripts/codex/smoke-api.sh
bash -n scripts/codex/cleanup-test-data.sh
bash -n scripts/codex/env-status.sh
```

Expected: 四条命令均无输出且退出码为 0。

- [ ] **Step 8: 运行环境状态脚本**

Run:

```bash
scripts/codex/env-status.sh
```

Expected: 输出 git 状态、5173/8080 端口状态、前后端启动提示和 Java/Maven/Node/npm 版本。端口没有监听不算失败。

- [ ] **Step 9: Commit**

Run:

```bash
git add scripts/codex/check.sh scripts/codex/smoke-api.sh scripts/codex/cleanup-test-data.sh scripts/codex/env-status.sh
git commit -m "chore: add codex verification scripts"
```

Expected: commit 成功。

## Task 6: 验证 harness 并推送

**Files:**
- Verify: `.codex/harness/START_HERE.md`
- Verify: `.codex/harness/WORKFLOW.md`
- Verify: `.codex/harness/CHECKLISTS.md`
- Verify: `.codex/harness/XNLP_RULES.md`
- Verify: `.codex/templates/*.md`
- Verify: `docs/codex/SOP.md`
- Verify: `docs/codex/VERIFICATION.md`
- Verify: `scripts/codex/*.sh`

- [ ] **Step 1: 检查 MVP 文件全部存在**

Run:

```bash
for path in \
  .codex/harness/START_HERE.md \
  .codex/harness/WORKFLOW.md \
  .codex/harness/CHECKLISTS.md \
  .codex/harness/XNLP_RULES.md \
  .codex/templates/feature-task.md \
  .codex/templates/bugfix-task.md \
  .codex/templates/ui-regression-task.md \
  .codex/templates/model-integration-task.md \
  .codex/templates/handoff-summary.md \
  docs/codex/SOP.md \
  docs/codex/VERIFICATION.md \
  scripts/codex/check.sh \
  scripts/codex/smoke-api.sh \
  scripts/codex/cleanup-test-data.sh \
  scripts/codex/env-status.sh; do
  test -f "$path" && echo "OK $path"
done
```

Expected: 输出 15 行 `OK ...`。

- [ ] **Step 2: 检查脚本权限**

Run:

```bash
test -x scripts/codex/check.sh
test -x scripts/codex/smoke-api.sh
test -x scripts/codex/cleanup-test-data.sh
test -x scripts/codex/env-status.sh
```

Expected: 四条命令均退出码为 0。

- [ ] **Step 3: 运行标准验证套件**

Run:

```bash
scripts/codex/check.sh
```

Expected: 前端 build、xnlp-core 测试、xnlp-server 测试均通过。

- [ ] **Step 4: 后端运行时执行 API smoke**

If backend is not running, start it in a separate terminal:

```bash
cd xnlp-server
mvn spring-boot:run -Dmaven.repo.local=/tmp/m2 -DskipTests -Dspring-boot.run.arguments=--server.port=8080
```

Then run:

```bash
scripts/codex/smoke-api.sh
```

Expected: `/health`、`/api/v1/models`、`/api/v1/models/capabilities`、`/api/v1/datasets`、`/api/v1/evaluations` 都返回 2xx。

- [ ] **Step 5: 验证清理脚本 dry-run**

Run:

```bash
scripts/codex/cleanup-test-data.sh
```

Expected: 输出 `Dry-run mode`。没有匹配数据时不删除任何内容。后端未运行时记录该限制，不改用危险的本地文件删除。

- [ ] **Step 6: 检查 git 状态**

Run:

```bash
git status --short
```

Expected: 没有未提交文件；如果前面任务在同一次执行中还未提交，则只出现 harness、templates、docs/codex、scripts/codex 相关文件。

- [ ] **Step 7: 推送到 GitHub**

Run:

```bash
git -c credential.helper='!f() { echo username=x-access-token; echo password=$GITHUB_TOKEN; }; f' push origin main
```

Expected: push 成功，不修改远端 URL，不把 token 写入磁盘。

- [ ] **Step 8: 最终交付说明**

Final response must include:

```text
已完成 X-NLP Codex harness MVP。
验证：scripts/codex/check.sh 通过；scripts/codex/smoke-api.sh 通过或说明后端未运行限制；cleanup-test-data dry-run 已检查。
Git：列出最后一个 commit hash 和 push 状态。
残余风险：如有，说明具体风险；没有则写“未发现”。
```

## Plan 自检

- Spec coverage: 设计文档中的 harness 入口、工作流、检查清单、X-NLP 规则、五个模板、两份 docs、四个脚本、可执行权限、标准验证、API smoke、dry-run 清理和推送要求均有对应任务。
- Placeholder scan: 本计划没有未完成占位说明，也没有引用其他任务来省略细节的步骤。
- Path consistency: 所有路径与设计文档一致；本计划只新增 harness MVP 文件，不修改现有业务代码。
