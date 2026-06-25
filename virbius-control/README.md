# virbius-control

单 JVM：**Registry Admin API** + 统一运营台（MVP PoC）。

- **JDK 17**（继承父 `pom.xml`）
- 端口：`8080`
- Admin / Edge API：[docs/seed-api.md](../docs/seed-api.md)
- 规则真源：**`tb_rule_history`**（见 [DESIGN §8.5](../docs/DESIGN.md)、[用户使用手册 §6](../docs/user-guide.md) 放量与执行面）
- PoC 种子：**`src/main/resources/db/seed.sql`**
- 操作说明：[docs/seed-api.md](../docs/seed-api.md)

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

Edge 拉取契约：[用户使用手册 §3.2](../docs/user-guide.md)、[DESIGN §8.10.2.5a](../docs/DESIGN.md)。

## 配置

### API Key 鉴权（Admin / Edge / Legacy tenants API）

`src/main/resources/application.yml`：

```yaml
virbius:
  security:
    api-key:
      enabled: ${VIRBIUS_API_KEY_AUTH_ENABLED:false}
```

| 变量 / 配置 | 默认 | 说明 |
|-------------|------|------|
| `virbius.security.api-key.enabled` | `false` | 等同环境变量 `VIRBIUS_API_KEY_AUTH_ENABLED` |
| `VIRBIUS_API_KEY_AUTH_ENABLED` | `false` | `true` 时 Admin / Edge / `/api/v1/tenants/**` 须带有效 Bearer |
| PoC dev keys | seed | 见下表 |

**角色**（`tb_tenant_api_credential.role`）：

| 角色 | 能力 |
|------|------|
| `tenant_viewer` | Edge manifest GET；Admin GET |
| `tenant_admin` | 写操作、放量、发布、租户级凭证管理 |
| `platform_admin` | 租户 CRUD；全租户；平台级凭证 |

**PoC seed keys**（`VIRBIUS_API_KEY_AUTH_ENABLED=true` 时使用）：

| Key | tenant | role |
|-----|--------|------|
| `vrb_tk_dev_viewer_default` | `default` | `tenant_viewer` |
| `vrb_tk_dev_admin_default` | `default` | `tenant_admin` |
| `vrb_tk_dev_platform` | `*` | `platform_admin` |

**凭证 Admin API**（响应带 `code` 包装）：

```bash
# 租户级：列出 / 签发 / 吊销
curl -s -H "Authorization: Bearer vrb_tk_dev_admin_default" \
  "http://127.0.0.1:8080/api/v1/admin/tenants/default/api-credentials"
curl -s -X POST -H "Authorization: Bearer vrb_tk_dev_admin_default" \
  -H "Content-Type: application/json" \
  -d '{"role":"tenant_viewer","label":"edge-sdk"}' \
  "http://127.0.0.1:8080/api/v1/admin/tenants/default/api-credentials"
curl -s -X POST -H "Authorization: Bearer vrb_tk_dev_admin_default" \
  "http://127.0.0.1:8080/api/v1/admin/tenants/default/api-credentials/{credentialId}/revoke"

# 平台级
curl -s -H "Authorization: Bearer vrb_tk_dev_platform" \
  "http://127.0.0.1:8080/api/v1/admin/platform/api-credentials"
```

凭证存 **`tb_tenant_api_credential`**（`tenant_id` + `role` + `key_hash`）。运营台「租户」页可 CRUD 租户与凭证。详见 [db/README.md](../db/README.md)。

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
```
