# virbius-gateway-agent

APISIX/Kong 同节点 sidecar（**Rust**，F-14）。与 `virbius-core` 同属 Rust 工具链，便于统一维护。

- 监听：`127.0.0.1:9070`（`VIRBIUS_AGENT_LISTEN`）
- 上游 engine：`VIRBIUS_ENGINE_URL`（默认 `http://127.0.0.1:8082`，MVP 走 HTTP `/v1/evaluate`；生产目标 gRPC `:50051`）
- 契约：[用户使用手册 §4](../docs/user-guide.en.md)、[DESIGN.md §11.5](../docs/DESIGN.md)
- **管侧名单**：读取 `{VIRBIUS_DATA_DIR}/gateway/{tenant}-access-lists.json`（由 control 同步写入）；`user_id` / `device_id` / `ip_cidr` / `keyword` 在 agent 内优先拦截
- 环境变量：`VIRBIUS_GATEWAY_LISTS_PATH`、`VIRBIUS_DATA_DIR`、`VIRBIUS_TENANT_ID`（默认 `default`）
- **QA 代答（M1a）**：`/v1/evaluate` 支持 `last_user_message`；命中 QA 库且最终 `allow` 且未降级（`degraded=false`）时返回 `effective_action=reply` + `reply_text`/`qa_entry_id`（见 [openspec/qa-reply.md §22](../docs/openspec/qa-reply.md)）
  - `VIRBIUS_QA_PATH`（文件兜底，默认 `{data}/gateway/{tenant}-qa.json`）、`VIRBIUS_QA_POINTER_PREFIX`（`virbius:config:qa`）、`VIRBIUS_QA_BLOB_PREFIX`（`virbius:artifacts:qa`）、`VIRBIUS_QA_ACK_PREFIX`（`virbius:ack:qa`）、`VIRBIUS_QA_SYNC_POLL_SEC`（默认 10）

```bash
cargo build --release
VIRBIUS_ENGINE_URL=http://127.0.0.1:8082 ./target/release/virbius-gateway-agent
curl -s http://127.0.0.1:9070/health
```
