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

Admin API 前缀：`/api/v1/admin/tenants/{tenantId}/...`（响应包装为 `{ "code": 0, "data": ... }`）。  
兼容旧脚本仍可使用 `/api/v1/tenants/{tenantId}/...`（裸 JSON，无 `code` 包装）。

## 数据库（JDBC）

持久化通过 **`JdbcRegistryRepository` / `JdbcAccessListRepository`** + Spring `JdbcTemplate` 完成。

| 变量 | 说明 |
|------|------|
| `VIRBIUS_JDBC_URL` | 如 `jdbc:sqlite:./data/virbius-control.db` |
| `VIRBIUS_JDBC_DRIVER` | 如 `org.sqlite.JDBC`、`org.postgresql.Driver` |
| `spring.datasource.username` / `password` | 按需 |

```bash
mvn -q -pl virbius-control spring-boot:run
# 日常：运营台「策略上线」对单条规则 publish / evaluate / apply
# Legacy 整包：POST .../bundles/.../publish（OpenAPI [Legacy]；运营台无 Bundle 状态页）
```
