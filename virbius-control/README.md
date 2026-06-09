# virbius-control

单 JVM：**Registry Admin API** + 统一运营台（MVP PoC）。

- **JDK 17**（继承父 `pom.xml`）
- 端口：`8080`
- 契约：[docs/openspec/registry.openapi.yaml](../docs/openspec/registry.openapi.yaml)
- 规则真源：**`tb_rule_history`**（见 [DESIGN §8.5.2](../docs/DESIGN.md)、[rule-rollout.md](../docs/openspec/rule-rollout.md) 放量、`rule-level-enforce.md` 执行面）
- PoC 种子：**`src/main/resources/db/seed.sql`**
- 操作说明：[docs/POC-SEED-API.md](../docs/POC-SEED-API.md)

## 运营入口（唯一）

| 用途 | URL |
|------|-----|
| **运营台**（名单 · 累计 · 请求映射 · 规则 · **策略上线**） | http://127.0.0.1:8080/ui |

旧路径（`/ui/access-lists`、`/ui/policies`、`access-lists.html` 等）均重定向到上述页面。

## HTTP 面

| 前缀 | 响应格式 | 说明 |
|------|----------|------|
| `/api/v1/admin/tenants/{tenantId}/...` | `{ "code": 0, "data": ... }` | Admin / 运营 API |
| `/api/v1/edge/tenants/{tenantId}/apps/{appId}/...` | **裸 JSON** | Edge SDK 拉取（方案 B+） |
| `/api/v1/tenants/{tenantId}/...` | 裸 JSON | Legacy 兼容 |

Edge 拉取契约：[MVP-OPENSPEC §4.8](../docs/openspec/MVP-OPENSPEC.md)、[DESIGN §8.10.2.5a](../docs/DESIGN.md)。

## 配置

### Edge manifest 拉取鉴权（方案 B+）

`src/main/resources/application.yml`：

```yaml
virbius:
  edge:
    delivery:
      auth:
        enabled: ${VIRBIUS_EDGE_AUTH_ENABLED:false}
```

| 变量 / 配置 | 默认 | 说明 |
|-------------|------|------|
| `virbius.edge.delivery.auth.enabled` | `false` | 等同环境变量 `VIRBIUS_EDGE_AUTH_ENABLED` |
| `VIRBIUS_EDGE_AUTH_ENABLED` | `false` | `true` 时 Edge API 须带有效 Bearer |
| PoC dev key | seed | `vrb_edge_dev_default_poc_only`（`tenant=default`） |

**凭证 Admin API**（响应带 `code` 包装）：

```bash
# 列出（不含明文 key）
curl -s "http://127.0.0.1:8080/api/v1/admin/tenants/default/edge-credentials"
# 签发（data.api_key 仅返回一次）
curl -s -X POST "http://127.0.0.1:8080/api/v1/admin/tenants/default/edge-credentials"
# 吊销
curl -s -X POST "http://127.0.0.1:8080/api/v1/admin/tenants/default/edge-credentials/{credentialId}/revoke"
```

凭证存 **`tb_edge_tenant_credential`**（仅 `tenant_id`，无 `app_id`）。详见 [db/README.md](../db/README.md)。

### 数据目录与 JDBC

| 变量 | 说明 |
|------|------|
| `VIRBIUS_DATA_DIR` | 默认 `./data`；edge manifest、gateway JSON、SQLite 等 |
| `VIRBIUS_JDBC_URL` | 如 `jdbc:sqlite:./data/virbius-control.db` |
| `VIRBIUS_JDBC_DRIVER` | 如 `org.sqlite.JDBC`、`org.postgresql.Driver` |
| `spring.datasource.username` / `password` | 按需 |

Edge 产物路径（`ArtifactService` 写入）：

```text
{data_dir}/edge/{tenant_id}/{app_id}/edge-manifest.json
```

## 数据库（JDBC）

持久化通过 **`JdbcRegistryRepository` / `JdbcAccessListRepository`** + Spring `JdbcTemplate` 完成。

```bash
mvn -q -pl virbius-control spring-boot:run
# 日常：运营台「策略上线」对单条规则 publish / evaluate / apply
# Legacy 整包：POST .../bundles/.../publish（OpenAPI [Legacy]；运营台无 Bundle 状态页）
```
