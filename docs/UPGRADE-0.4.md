# X-NLP 0.4.0 升级与回滚指南

> 适用范围：从 X-NLP `0.3.0` 升级到 `0.4.0`。升级会增加知识库、文档、chunk、embedding、导入任务和检索评测持久化结构。

## 1. 升级前检查

1. 记录当前 server/frontend 镜像 digest、Helm values、Spring profile 和 provider 配置。
2. 等待或取消正在运行的评测与导入任务，停止外部写流量。
3. 对数据库执行一致性备份：H2 复制已关闭实例的文件；MySQL 使用事务一致性备份；PostgreSQL 使用快照或 `pg_dump`。
4. 确认 Java 25、Node.js 22（前端构建）和目标数据库版本满足现有部署要求。
5. 在与生产同类型的临时数据库上运行迁移和 E2E，保留输出作为发布证据。

## 2. 版本与构建门禁

仓库发布元数据必须全部为 `0.4.0`：

```bash
python3 scripts/release/verify-version-alignment.py 0.4.0
mvn -B -ntp verify
npm ci --prefix xnlp-frontend
npm run build --prefix xnlp-frontend
npm run test:e2e --prefix xnlp-frontend
helm lint deploy/helm/xnlp
```

有 Docker 时，再执行数据库矩阵和镜像构建：

```bash
XNLP_E2E_SKIP_BUILD=true tests/e2e/run-h2.sh
tests/e2e/run-db-matrix.sh all
docker build --build-arg XNLP_VERSION=0.4.0 -t xnlp-server:0.4.0 .
docker build --build-arg XNLP_VERSION=0.4.0 -t xnlp-frontend:0.4.0 xnlp-frontend
```

GitHub Actions 还会运行高危/严重依赖漏洞和 secret 扫描。扫描失败时不得通过忽略整个目录、降低级别或提交白名单绕过；应升级依赖、移除 secret，或以独立安全评审记录精确例外及到期时间。

## 3. 数据库迁移

服务启动时继续由 `DatabaseMigrationRunner` 使用 Spring Boot `DataSource` 执行版本化迁移：

- `V5__rag-storage.sql`：知识库、文档、chunk、embedding 和导入任务；
- `V6 ingestion-control-and-rag-constraints`：由 `DatabaseMigrationRunner` 执行，补充导入取消字段以及知识库名称、文档外部标识的租户级唯一约束；
- `V7__retrieval-evaluation.sql`：检索评测 run/sample、查询索引和级联外键。

迁移代码按 JDBC product name 适配 H2、MySQL 和 PostgreSQL 的大文本类型，并在 schema history 中校验 checksum。不要手工修改已执行迁移；需要修正时新增后续迁移。

## 4. Provider 与索引注意事项

- RAG 需要同时配置 Spring AI chat provider 和 embedding provider。
- 知识库保存 embedding 模型标识和向量维度。切换 embedding 模型后必须显式 reindex，不能混用不同向量空间。
- 真实 ONNX runtime 的模型文件仍应外置只读挂载，并固定版本与 SHA-256；Java 25 需要 `--enable-native-access=ALL-UNNAMED`。
- 先在单个租户和小知识库上验证导入、search、RAG 引用与检索评测，再逐步恢复写流量。

## 5. Helm 滚动升级

```bash
helm upgrade --install xnlp deploy/helm/xnlp \
  --set server.image.tag=0.4.0 \
  --set frontend.image.tag=0.4.0 \
  --reuse-values

kubectl rollout status deployment/xnlp-server
kubectl rollout status deployment/xnlp-frontend
curl -fsS https://<host>/readyz
```

不要只更新应用镜像而遗漏 Helm Chart/appVersion 或 frontend 镜像。发布后执行一次知识库导入、search 和 RAG smoke，并检查 `/actuator/health`、错误率和 P95 延迟。

## 6. 回滚

数据库迁移没有自动 down 脚本。可靠回滚单位是“应用镜像 + Helm values + 升级前数据库快照”：

1. 再次停止写流量，保存失败现场日志、traceId、迁移历史和数据库快照。
2. 将 server/frontend 回滚到升级前的镜像 digest 或 `0.3.0` 标签。
3. 恢复升级前数据库快照。仅回滚应用而保留升级后的写入数据不属于受支持路径。
4. 恢复原 provider、ONNX 挂载和环境变量，等待 readiness 通过。
5. 运行 Release 0.3 API smoke，确认模型、数据集、评测和租户隔离可用后再恢复流量。

Helm 示例：

```bash
helm history xnlp
helm rollback xnlp <pre-upgrade-revision> --wait
# 随后按数据库平台流程恢复升级前快照。
```

## 7. 发布证据清单

- `python3 scripts/release/verify-version-alignment.py 0.4.0` 输出；
- Maven、前端 build、Playwright、H2 E2E/性能门禁、MySQL/PostgreSQL 矩阵和 Helm lint 结果；
- Trivy 高危/严重漏洞与 secret 扫描结果；
- server/frontend 镜像 digest、Helm revision 和数据库备份标识；
- 升级后知识库导入、search、RAG 引用和检索评测 smoke；
- 回滚演练时间、负责人、恢复点和验证结果。
