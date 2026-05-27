# VirbiusLLM PoC 仓库布局

| 属性 | 值 |
|------|-----|
| 对齐 | [DESIGN.md v2.16](./DESIGN.md)、[MVP-OPENSPEC MVP-1.8](./openspec/MVP-OPENSPEC.md) |
| 目标 | 最小可编译骨架：`control` + `engine` + `gateway-agent` + APISIX 插件壳 |

## 目录

```text
VirbiusLLM/
├── pom.xml                      # Maven 父工程
├── virbius-control/             # Java：Registry API + Admin（:8080）
├── virbius-engine/              # Java：gRPC Evaluate + admin HTTP (:50051 / :8082)
├── virbius-gateway-agent/       # Rust：sidecar HTTP → engine（F-14）
├── virbius-gateway/
│   ├── core/                    # 共用 Lua（rules_runner、agent_client）
│   └── plugins/apisix/          # virbius-guard APISIX 插件
├── virbius-core/                # Rust 端 L0（后续）
├── virbius-compiler/            # 编译 CLI（后续）
├── docs/                        # DESIGN + openspec
└── docker-compose.poc.yml       # 本地联调（可选）
```

## 组件与端口（PoC）

| 组件 | 语言 | 端口 / 地址 |
|------|------|-------------|
| virbius-control | **Java 17** | `http://localhost:8080` |
| virbius-engine gRPC | **Java 17** | `localhost:50051` |
| virbius-engine admin | **Java 17** | `http://localhost:8082` |
| virbius-gateway-agent | **Rust** | `http://127.0.0.1:9070` |
| APISIX + virbius-guard | Lua | 业务 Route 转发 |

## 数据库与规则种子（JDBC，默认 SQLite 文件）

```bash
make init-db   # 或 ./scripts/init-databases.sh
# control 启动时执行 schema + seed.sql（黑名单等规则在 SQL 中）
```

- 表结构：[db/README.md](../db/README.md)
- 种子与 API：[POC-SEED-API.md](./POC-SEED-API.md)

## 环境要求

| 工具 | 版本 |
|------|------|
| **JDK** | **17**（与根 `pom.xml` 中 `java.version` 一致） |
| Maven | 3.9+ |
| Rust | 1.70+（`virbius-gateway-agent`、`virbius-core`） |
| SQLite CLI | 可选（`make init-db`，仅本地 `.db`；PG/MySQL 靠 `sql.init`） |

```bash
java -version   # 应显示 17.x
```

## 快速开始

```bash
# Java 控制面 + 引擎
mvn -q -pl virbius-control,virbius-engine package
java -jar virbius-control/target/virbius-control-0.1.0-SNAPSHOT.jar
java -jar virbius-engine/target/virbius-engine-0.1.0-SNAPSHOT.jar

# Rust gateway-agent
cd virbius-gateway-agent && cargo run --release

# 健康检查
curl -s http://localhost:8080/actuator/health
curl -s http://127.0.0.1:9070/health
```

## 实现顺序（建议）

1. **virbius-engine**：gRPC `Evaluate` 桩 + `/admin/policy-version`
2. **virbius-gateway-agent**：HTTP `/v1/evaluate` → gRPC 转发
3. **virbius-guard**（Lua）：trace 解析、调 agent socket
4. **virbius-control**：`rule_history` CRUD + 发布状态机桩
5. **virbius-core** / **compiler**：W3+ 里程碑

## 契约

- OpenAPI / proto / Schema：[openspec/](./openspec/)
- 规则真源：**`rule_history`**（非 `rule_runtime`）
