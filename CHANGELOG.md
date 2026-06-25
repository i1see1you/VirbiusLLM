# Changelog

All notable changes to this project will be documented in this file.

## [0.1.0] — 2026-06

### Added
- **Edge L0 SDK** (`virbius-core`): Rust-native local scan engine with keyword/blacklist detection, DLP desensitization (phone, ID card, email, bank card), and C ABI for non-Rust languages.
- **Gateway** (`virbius-gateway` + `virbius-gateway-agent`): Real-time Lua firewall on APISIX/Kong with access lists, scene registry, and sidecar-based engine dispatch. OpenResty stretch support via compiler-flattened artifacts.
- **Cloud engine** (`virbius-engine`): Prompt L1 semantic detection (1B model), Groovy L3 policy scripting, and ActionMerge for multi-rule decision consolidation.
- **Control plane** (`virbius-control`): Spring Boot admin service with unified rule registry (`tb_rule_history`), tenant management, access list CRUD, artifact publishing, audit ingest (Redis Stream / Kafka), and promotion gates.
- **Compiler** (`virbius-compiler`): Transforms registry rules into layer-specific artifacts (edge manifest, gateway JSON, engine input).
- **Admin UI** (`ops.html`): Single-page operations console with rule management, strategy rollout (draft → dry_run → canary → full), access lists, cumulative counters, request mapping, monitor dashboard, and audit center.
- **Rollout states**: `draft` / `disabled` / `dry_run` / `canary` / `full` with canary ladder, promotion gates, and lifecycle enforcement (body change → force to draft).
- **Edge manifest sync** (Scheme B+): Control-direct pull with tenant-level Bearer auth (`tb_tenant_api_credential`).
- **Audit**: Redis Stream / Kafka audit ingest into `tb_audit_events`, JSONL allow logs, trace_id-based end-to-end troubleshooting.
- **Multi-tenant**: Tenant isolation across all layers with per-tenant fail policy (fail-open / fail-close).
- **Local PoC**: `scripts/run-local.sh` for one-command local stack (control + engine + gateway-agent + Redis + H2/SQLite).
