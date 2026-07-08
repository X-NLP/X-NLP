# X-NLP 验证说明

## 标准验证套件

跨模块改动运行：

这些脚本由 harness 脚本任务提供；如果仓库中尚未存在，请手动运行下方列出的命令。

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
- 覆盖桌面 1440px 和移动 375px 视口示例。

## 测试数据清理

先 dry-run：

```bash
scripts/codex/cleanup-test-data.sh
```

确认只匹配测试数据后执行：

```bash
scripts/codex/cleanup-test-data.sh --apply
```
