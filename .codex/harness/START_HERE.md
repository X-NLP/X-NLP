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
