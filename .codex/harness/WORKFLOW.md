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
