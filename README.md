# VirbiusLLM

License: [MIT](LICENSE) · 中文：[README.zh.md](README.zh.md)

VirbiusLLM is a security platform for large language model applications. It supports real-time and near-line policy rollout, rule authoring with agent assistance, and layered defenses against prompt jailbreaks, sensitive instructions, and unsafe model output.

The architecture follows an **edge–gateway–cloud** model with a **unified control plane** and **layered enforcement**:

| Component | Role |
|-----------|------|
| **`virbius-control`** | Registry API, admin console, single HTTP surface; source of truth for rules |
| **`virbius-engine`** | Cloud-side Prompt / Groovy evaluation and final merge (no OPA/Rego) |
| **`virbius-compiler`** | Compiles bundles into edge manifests, gateway artifacts, and engine inputs |
| **`virbius-core`** | Edge L0 SDK (Rust + C ABI): local keywords, blacklist, DLP desensitization |
| **`virbius-gateway`** | APISIX/Kong plugins + shared Lua core |
| **`virbius-gateway-agent`** | Gateway sidecar that calls `virbius-engine` |

Rules are versioned in **`rule_history` / `rule_revision`**. Publishing flows through **Compiler + PublishOrchestrator** to CDN (edge), etcd/Kong (gateway), and **Registry DB → RuleCache** (engine).

**MVP scope (frozen):** edge L0 + **APISIX gateway (required)** + engine (L1 Prompt + Groovy); rollout modes `dry_run` / `canary` / `full`.

## Documentation

| Topic | Link |
|-------|------|
| System design | [docs/DESIGN.md](docs/DESIGN.md) |
| User guide (EN) | [docs/user-guide.en.md](docs/user-guide.en.md) |
| User guide (中文) | [docs/user-guide.md](docs/user-guide.md) |
| PoC seed & admin API | [docs/POC-SEED-API.md](docs/POC-SEED-API.md) |
| Repo layout | [docs/POC-REPO.md](docs/POC-REPO.md) |

## Requirements

- **JDK 17**, **Maven 3.9+**
- **Rust** (for `virbius-core` and `virbius-gateway-agent`)
- Optional: **Redis**, **Ollama/vLLM** (Prompt 1B rules on engine)

See [docs/POC-REPO.md](docs/POC-REPO.md) for environment details.

## Quick start

Start control, engine, and gateway-agent locally:

```bash
bash scripts/run-local.sh
```

- Control / admin UI: http://127.0.0.1:8080/ui  
- Engine: http://127.0.0.1:8082  
- Gateway agent: http://127.0.0.1:9070  

Smoke test:

```bash
bash scripts/smoke-test.sh
```

## Edge SDK (`virbius-core`)

Rust clients can depend on the crate via path or git:

```toml
[dependencies]
virbius-core = { path = "../VirbiusLLM/virbius-core" }
```

**Offline demo** (fixture manifest with DLP rules):

```bash
cd virbius-core
cargo run --example rust_client_demo
```

**Control sync (Scheme B+)** — after `bash scripts/run-local.sh` and publishing edge rules:

```bash
export VIRBIUS_CONTROL_BASE_URL=http://127.0.0.1:8080
export VIRBIUS_TENANT_ID=default
export VIRBIUS_APP_ID=beta
export VIRBIUS_EDGE_CACHE_DIR=./cache/beta
# optional when VIRBIUS_API_KEY_AUTH_ENABLED=true on control:
export VIRBIUS_EDGE_API_KEY=vrb_tk_dev_viewer_default
cargo run --example rust_client_demo
```

Production apps should use `VirbiusEdge::init(EdgeInitConfig { ... })` from app config, not env vars. See [docs/user-guide.en.md](docs/user-guide.en.md) §3.

Gateway HTTP example (requires APISIX PoC):

```bash
export VIRBIUS_GATEWAY_URL=http://127.0.0.1:9080/v1/chat/completions
cargo run --example gateway_http_client
```

More detail: [virbius-core/README.md](virbius-core/README.md) · [docs/user-guide.en.md](docs/user-guide.en.md).

## Gateway (APISIX)

PoC route/service samples: [examples/gateway/poc-default/0.1.0/](examples/gateway/poc-default/0.1.0/).

Binding order: **Global → Service (tenant) → Route (scene)**. The `virbius-guard` plugin runs local access lists, then calls gateway-agent → engine.

## License

[MIT License](LICENSE) — Copyright (c) 2026 i1see1you.
