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

**默认基址（PoC）**

| 服务 | 基址 |
|------|------|
| **virbius-control** | `http://virbius-control:8080`（`/api/v1/*` + `/ui/*`） |
| gateway-agent | `http://127.0.0.1:9070` 或 `unix:///var/run/virbius/agent.sock` |
| virbius-engine gRPC | `virbius-engine:50051` |
| virbius-engine admin | `http://virbius-engine:8082` |
