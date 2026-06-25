# VirbiusLLM 数据库脚本

MVP 默认用 **SQLite 文件库**（`./data/*.db`），但 **`schema.sql` / `seed.sql` 按 PostgreSQL、MySQL、SQLite 三方可执行 JDBC 方言编写**，换库只需改 JDBC URL 与驱动。物理表名统一 **`tb_` 前缀**（见 DESIGN §8.2）。

## 跨方言约定

| 项 | 写法 |
|----|------|
| 时间 | `TIMESTAMP` + `DEFAULT CURRENT_TIMESTAMP` |
| 短文本 / 主键 | `VARCHAR(n)` |
| JSON | `TEXT` |
| surrogate 主键 | 避免方言自增；审计/日志用自然键组合 `PRIMARY KEY (...)` |
| 幂等插入 | `INSERT … SELECT … FROM (SELECT 1) AS _one WHERE NOT EXISTS (…)` |
| 方言特化 | 三种方言各维护一个 `MetricsRollupJob` SQL 分支 + `INSERT OR IGNORE` / `INSERT IGNORE` / `ON CONFLICT` 按需选择 |

不使用：`PRAGMA`、`TEXT` 作主键、`AUTOINCREMENT`。

## 库文件位置（SQLite 默认）

目录：`./data/`（`VIRBIUS_DATA_DIR` 可覆盖）

| 组件 | 数据库文件 | 初始化脚本 |
|------|------------|------------|
| virbius-control | `virbius-control.db` | `virbius-control/src/main/resources/db/schema.sql` + `seed.sql` |
| virbius-engine | `virbius-engine.db` | Engine 无 JDBC 库，不通过 schema.sql 建表 |

`virbius-compiler`、`virbius-core`、`virbius-gateway-agent` **无 JDBC 本地库**（compiler CLI；core/agent 用文件或 Redis）。

## 表名对照（逻辑 → 物理）

| 逻辑名 | 物理表名 | 组件 |
|--------|----------|------|
| tenants | `tb_tenants` | control |
| bundles | `tb_bundles` | control |
| rules_current | `tb_rules_current` | control |
| rule_history | `tb_rule_history` | control |
| access_list | `tb_access_list` | control |
| edge_artifact_meta | `tb_edge_artifact_meta` | control（manifest revision / sha256） |
| tenant_api_credential | `tb_tenant_api_credential` | control（API Bearer） |
| gateway_artifact_meta | `tb_gateway_artifact_meta` | control（网关产物 revision / sha256） |
| audit_events | `tb_audit_events` | control |
| audit_ingest_checkpoint | `tb_audit_ingest_checkpoint` | control |
| rule_metrics_1m | `tb_rule_metrics_1m` | control（每分钟聚合） |
| tenant_request_stats_1h | `tb_tenant_request_stats_1h` | control（每小时请求统计） |
| deploy_state | `tb_deploy_state` | control |
| deploy_rollout | `tb_deploy_rollout` | control |

## 一键初始化（仅 SQLite CLI）

```bash
chmod +x scripts/init-databases.sh
./scripts/init-databases.sh
```

## 应用启动时自动初始化

`virbius-control` 与 `virbius-engine` 在启动时通过 Spring Boot `spring.sql.init` 执行 `classpath:db/schema.sql` 与 `seed.sql`（`CREATE IF NOT EXISTS` + `WHERE NOT EXISTS` 插入，可重复执行）。**任意 JDBC 目标库**均可使用同一套脚本。

### 让应用重建（推荐）

删除本地库文件后由 Spring 建表并灌种子（会顺带触发 control 写 gateway/edge 名单 JSON）：

```bash
VIRBIUS_REBUILD_DB=1 bash scripts/run-local.sh
```

等价手动步骤：`rm -f ./data/*.db` 后执行 `run-local.sh`。

> **注意**：若已有旧库（无前缀表名或旧 SQLite 方言表），必须用上述方式删库重建，不能只靠 `sql.init` 覆盖旧表结构。

## 规则种子与 API

PoC 规则（含黑名单关键字）见 **`virbius-control/src/main/resources/db/seed.sql`**，维护说明见 **[docs/seed-api.md](../docs/seed-api.md)**。

写入 Registry 后需 **publish** 才会进入 engine RuleCache。

## 核心表（control）

- `tb_rule_history` — 规则版本真源（追加写）
- `tb_rules_current` — 当前 revision 指针
- `tb_bundles` — 发布批次元数据
- `tb_access_list` — 名单（keyword 全层；user/device/ip 管侧）
- `tb_edge_artifact_meta` — Edge manifest 元数据（`artifact_revision`、`content_sha256`）
- `tb_tenant_api_credential` — API Bearer（hash 存储；`tenant_id` + `role`）
- `tb_audit_events` — 审计事件（所有 action 均入库）
- `tb_audit_ingest_checkpoint` — Redis Stream 消费位点
- `tb_rule_metrics_1m` — 每分钟规则聚合（`MetricsRollupJob` 写入）
- `tb_tenant_request_stats_1h` — 每小时租户请求统计
- `tb_deploy_rollout` — 放量指针和状态

## 核心表（engine）

Engine 无 JDBC 库，RuleCache 为内存/Redis 结构，无本地持久化表。

### 审计事件流

各执行面（engine / gateway-agent / edge SDK）将审计事件 **publish 到 Redis Stream**（默认 key `virbius:audit:events`），由 **virbius-control AuditIngestService** 消费入库 `tb_audit_events`。

| 类型 | Redis Stream | `tb_audit_events` | JSONL 备档 |
|------|--------------|-------------------|------------|
| review / block / captcha / degraded / allow 等 | ✅ publish | ✅ 全部入库 | 仅 allow 写 `*-audit-allow.jsonl` |

Allow 事件除入库外，还会额外通过 logback logger `virbius.audit.allow` 写入 **`*-audit-allow.jsonl`**（滚动文件，仅作留底备查）。

运营台 **「审计中心」** 仅查询 DB（`tb_audit_events`），不读 JSONL。按 `trace_id` 检索审计记录，见 [seed-api.md](../docs/seed-api.md)、[用户使用手册 §5](../docs/user-guide.md)。
