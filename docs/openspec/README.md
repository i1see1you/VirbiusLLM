# VirbiusLLM MVP OpenSpec

本目录为 **MVP（Phase 1）** 可实施接口与数据契约，与 [DESIGN.md v2.14](../DESIGN.md) 对齐，**§11.6.0 冻结清单** 为准。代码骨架见 [POC-REPO.md](../POC-REPO.md)。

| 文件 | 说明 |
|------|------|
| [MVP-OPENSPEC.md](./MVP-OPENSPEC.md) | 总览、术语、验收、非目标 |
| [registry.openapi.yaml](./registry.openapi.yaml) | **virbius-control** 统一 HTTP API（Registry + Admin） |
| [gateway-agent.openapi.yaml](./gateway-agent.openapi.yaml) | 本机 gateway-agent HTTP |
| [engine-admin.openapi.yaml](./engine-admin.openapi.yaml) | engine 运维与缓存同步 |
| [evaluate.proto](./evaluate.proto) | virbius-engine gRPC |
| [schemas/rule-bundle.schema.json](./schemas/rule-bundle.schema.json) | Bundle 逻辑结构校验 |
| [schemas/audit-event.schema.json](./schemas/audit-event.schema.json) | 审计 jsonl 单行事件 |
| [schemas/control-context.schema.json](./schemas/control-context.schema.json) | 端/管/云公用防控请求上下文 |
| [schemas/edge-manifest.schema.json](./schemas/edge-manifest.schema.json) | 端侧 CDN 清单 |
| [groovy-l3-contract.md](./groovy-l3-contract.md) | 云侧 Groovy L3 脚本与 `ctx` API |
| [cumulative-counter.md](./cumulative-counter.md) | 累计规则 `tb_cumulative`、CounterStore、Redis 桶与 Lua（**待实现**） |
| [list-match.md](./list-match.md) | 名单规则 `list_name`、ListStore、名单快照（**待实现**） |
| [value-resolution.md](./value-resolution.md) | 规则可选 `value_source`；`matchList` / `getCumulate` 的 value 解析 |
| [list-and-cumulative-rules.md](./list-and-cumulative-rules.md) | **名单 + 累计最新合并设计（推荐阅读）** |
| [rule-level-enforce.md](./rule-level-enforce.md) | **规则级 enforce / canary**（管侧真拦 + 云 PolicyMerge） |

**默认基址（PoC）**

| 服务 | 基址 |
|------|------|
| **virbius-control** | `http://virbius-control:8080`（`/api/v1/*` + `/ui/*`） |
| gateway-agent | `http://127.0.0.1:9070` 或 `unix:///var/run/virbius/agent.sock` |
| virbius-engine gRPC | `virbius-engine:50051` |
| virbius-engine admin | `http://virbius-engine:8082` |
