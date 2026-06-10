# virbius-core

License: [MIT](../LICENSE) · English: [README.md](README.md)

端侧 L0 Native SDK（Rust + C ABI）。集成见 [user-guide.md](../docs/user-guide.md)、Edge 同步见 §3.2。

**用户使用手册**：[docs/user-guide.md](../docs/user-guide.md) · [English](../docs/user-guide.en.md)（端 SDK、Init 配置、网关 Header、DLP、排障）

## Rust 快速开始（推荐）

```toml
[dependencies]
virbius-core = { path = "../VirbiusLLM/virbius-core" }
```

**离线 fixture**（不连 Control）：

```rust
use std::path::PathBuf;
use virbius_core::{EffectiveAction, EdgeInitConfig, ScanContext, VirbiusEdge};

fn main() -> Result<(), virbius_core::VirbiusError> {
    let edge = VirbiusEdge::init(EdgeInitConfig {
        offline_manifest_path: Some(
            "./virbius-core/examples/fixtures/demo-edge-manifest.json".into(),
        ),
        cache_dir: PathBuf::from("./cache"),
        ..Default::default()
    })?;
    let outcome = edge.scan_with(
        ScanContext {
            scene: Some("chat".into()),
            ..Default::default()
        },
        "用户输入的 prompt",
    )?;

    match outcome.action {
        EffectiveAction::Allow => { /* 继续调 LLM；outcome.trace_id → X-Virbius-Trace-Id */ }
        EffectiveAction::Block => { /* 本地拦截 */ }
        EffectiveAction::Review => { /* dry_run 命中，可放行并上报 */ }
        EffectiveAction::Captcha => { /* 触发验证码 */ }
    }
    Ok(())
}
```

**生产**（Control 方案 B+ 同步，见 [user-guide §3.2](../docs/user-guide.md)）：

```rust
let edge = VirbiusEdge::init(EdgeInitConfig {
    control_base_url: Some("https://control.example.com".into()),
    tenant_id: "default".into(),
    app_id: "beta".into(),
    edge_api_key: Some("vrb_tk_...".into()), // Control 开启 VIRBIUS_API_KEY_AUTH_ENABLED 时
    cache_dir: PathBuf::from(app_sandbox_dir),
    offline_manifest_path: None,
})?;
```

运行仓库内完整示例：

```bash
cd virbius-core
cargo run --example rust_client_demo
```

经网关调用 LLM（需本地 APISIX PoC）：

```bash
export VIRBIUS_GATEWAY_URL=http://127.0.0.1:9080/v1/chat/completions
cargo run --example gateway_http_client
```

- `trace_id`：**无需传入**，SDK 自动生成 UUID v4（`trace_id_source=sdk`）。
- 显式传入时须为 **UUID v4**（36 字符）。

## C ABI（跨语言）

见 [include/virbius.h](include/virbius.h)：**生产**用 `virbius_init_config_json`；遗留 `virbius_init`（URL 或离线路径）；`virbius_scan` / `virbius_reload` / `virbius_free_string`。

`virbius_scan` 会在 `virbius_scan_result.trace_id` 返回最终 trace。调用方须对 SDK 分配的字符串调用 `virbius_free_string` 释放。

## Manifest 与同步（方案 B+）

Control 按 `app_id` 写入：

```text
{data_dir}/edge/{tenant_id}/{app_id}/edge-manifest.json
```

SDK 在 `init` / `reload` 时：`policy-version` → 条件 `manifest` → sha256 校验 → 写入 `cache_dir`。

| 配置方式 | 说明 |
|----------|------|
| **`EdgeInitConfig`（生产）** | 宿主 App 配置；见 [user-guide](../docs/user-guide.md) |
| **`from_env()` / 示例** | 仅 CI；变量见 user-guide §3.2.2 |

**示例 / CI 环境变量**（不要在生产 App 中使用）：

```bash
export VIRBIUS_CONTROL_BASE_URL=http://127.0.0.1:8080
export VIRBIUS_TENANT_ID=default
export VIRBIUS_APP_ID=beta
export VIRBIUS_EDGE_CACHE_DIR=./cache/beta
export VIRBIUS_EDGE_API_KEY=vrb_tk_dev_viewer_default   # auth 开启时
# 或离线：
export VIRBIUS_EDGE_MANIFEST_PATH=./fixtures/demo-edge-manifest.json
```

## 与 virbius-client 的分工

| Crate | 层级 |
|-------|------|
| `virbius-core` | 端 L0，本地关键词 + DLP，同步 |
| `virbius-client`（规划中） | 管+云 HTTP Evaluate |
