# virbius-core

端侧 L0 Native SDK（Rust + C ABI）。契约：[MVP-OPENSPEC §4](../docs/openspec/MVP-OPENSPEC.md)。

## Rust 快速开始（推荐）

```toml
[dependencies]
virbius-core = { path = "../VirbiusLLM/virbius-core" }
```

```rust
use virbius_core::{EffectiveAction, ScanContext, VirbiusEdge};

fn main() -> Result<(), virbius_core::VirbiusError> {
    std::env::set_var("VIRBIUS_EDGE_LISTS_PATH", "./data/edge/default-content-lists.json");

    let edge = VirbiusEdge::new();
    let outcome = edge.scan_with(
        ScanContext {
            scene: Some("chat".into()),
            ..Default::default()
        },
        "用户输入的 prompt",
    )?;

    match outcome.action {
        EffectiveAction::Allow => { /* 继续调 LLM；outcome.trace_id 可写入 X-Virbius-Trace-Id */ }
        EffectiveAction::Block => { /* 本地拦截 */ }
        EffectiveAction::Review => { /* dry_run 命中，可放行并上报 */ }
        EffectiveAction::Captcha => { /* 触发验证码 */ }
    }
    Ok(())
}
```

- `trace_id`：**无需传入**，SDK 自动生成 UUID v4（`trace_id_source=sdk`）。
- 显式传入时须为 **UUID v4 或 ULID**。

## C ABI（跨语言）

见 [include/virbius.h](include/virbius.h)：`virbius_init` / `virbius_scan` / `virbius_reload` / `virbius_free_string`。

`virbius_scan` 会在 `virbius_scan_result.trace_id` 返回最终 trace（未传 ctx.trace_id 时由 SDK 生成 UUID v4）。调用方须对 `trace_id` 及 block 时的 `rule_id` / `reason_code` / `layer` 调用 `virbius_free_string` 释放。

## 规则文件

```bash
export VIRBIUS_EDGE_MANIFEST_PATH=./data/edge/default-edge-manifest.json
# 或 legacy 词表：
export VIRBIUS_EDGE_LISTS_PATH=./data/edge/default-content-lists.json
```

## 与 virbius-client 的分工

| Crate | 层级 |
|-------|------|
| `virbius-core` | 端 L0，本地关键词，同步 |
| `virbius-client`（规划中） | 管+云 HTTP Evaluate |
