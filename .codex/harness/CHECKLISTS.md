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
