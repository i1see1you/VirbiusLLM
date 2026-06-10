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

不使用：`PRAGMA`、`datetime('now')`、`INSERT OR IGNORE`、`TEXT` 作主键、`AUTOINCREMENT`。

## 库文件位置（SQLite 默认）

目录：`./data/`（`VIRBIUS_DATA_DIR` 可覆盖）

| 组件 | 数据库文件 | 初始化脚本 |
|------|------------|------------|
| virbius-control | `virbius-control.db` | `virbius-control/src/main/resources/db/schema.sql` + `seed.sql` |
| virbius-engine | `virbius-engine.db` | `virbius-engine/src/main/resources/db/schema.sql` + `seed.sql` |

`virbius-compiler`、`virbius-core`、`virbius-gateway-agent` **无 JDBC 本地库**（compiler CLI；core/agent 用文件或 Redis）。

## 表名对照（逻辑 → 物理）

| 逻辑名 | 物理表名 | 组件 |
|--------|----------|------|
| tenants | `tb_tenants` | control |
| bundles | `tb_bundles` | control |
| rules_current | `tb_rules_current` | control |
| rule_history | `tb_rule_history` | control |
| access_list | `tb_access_list` | control |
| edge_artifact_meta | `tb_edge_artifact_meta` | control（方案 B+ manifest revision / sha256） |
| tenant_api_credential | `tb_tenant_api_credential` | control（API Bearer：Admin / Edge / tenants API，含 role） |
| gateway_artifact_meta | `tb_gateway_artifact_meta` | control（网关产物 revision / sha256；Redis/OSS 指针索引） |
| audit_events | `tb_audit_events` | control |
| cache_meta | `tb_cache_meta` | engine |
| rule_cache_entry | `tb_rule_cache_entry` | engine |

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

PoC 规则（含黑名单关键字）见 **`virbius-control/src/main/resources/db/seed.sql`**，维护说明见 **[docs/POC-SEED-API.md](../docs/POC-SEED-API.md)**。

写入 Registry 后需 **publish** 才会进入 engine RuleCache。

## 核心表（control）

- `tb_rule_history` — 规则版本真源（追加写）
- `tb_rules_current` — 当前 revision 指针
- `tb_bundles` — 发布批次元数据（`metadata_json`：`scope`、`gateway.routes` 含 uri/methods 等）
- `tb_access_list` — 名单（keyword 全层；user/device/ip 管侧）
- `tb_edge_artifact_meta` — Edge manifest 元数据（`artifact_revision`、`content_sha256`）
- `tb_tenant_api_credential` — API Bearer（hash 存储；`tenant_id` + `role`：`tenant_viewer` / `tenant_admin` / `platform_admin`）

## 核心表（engine）

- `tb_rule_cache_entry` — RuleCache 物化快照

- `tb_audit_ingest_checkpoint` — audit Stream 消费位点（control ingest）

审计事件由各执行面写入 Redis Stream / 本地 jsonl，由 **virbius-control AuditIngest** 入库 **`tb_audit_events`**（**不含 `effective_action=allow`**，见下）。

### 审计存储分工（PoC）

| 类型 | `tb_audit_events` | Redis Stream | JSONL 备档 |
|------|-------------------|--------------|------------|
| review / block / captcha / degraded 等 | ✅ | ✅ publish | `*-audit.jsonl`（非 allow） |
| **allow** | ❌ | ❌ 不 publish | `*-audit-allow.jsonl` |

默认 allow 日志路径（可配置，见 control `application.yml` 中 `virbius.audit.*`）：

| 来源 | 默认路径 |
|------|----------|
| gateway-agent | `{VIRBIUS_DATA_DIR}/gateway-audit-allow.jsonl` |
| virbius-engine | `/tmp/virbius/engine-audit-allow.jsonl` |
| edge / HTTP ingest | `{VIRBIUS_DATA_DIR}/audit-allow.jsonl` |

运营台 **「审计中心」** 按 `trace_id` 合并查询 DB 事件与上述 allow 日志，见 [rule-rollout.md §5.8](../docs/openspec/rule-rollout.md)。
