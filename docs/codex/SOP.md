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
