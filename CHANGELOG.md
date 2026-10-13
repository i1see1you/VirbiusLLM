# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added
- **QA 代答（安全知识代答，M1a）**：
  - `virbius-gateway-agent`：新增 `qa.rs`（归一化 + exact/contains/regex 匹配、pointer+blob 快照加载与热更新、ack 上报、消费决策）；`/v1/evaluate` 支持 `last_user_message`，响应新增 `reply_text`/`qa_entry_id`；新增 `reply` 动作（仅 `allow && !degraded` 消费，其余压制）；审计事件 `QA_REPLY`/`QA_SUPPRESSED`/`QA_DRY_RUN_CANDIDATE`。
  - 网关（APISIX / OpenResty）：抽取 `last_user_message`，新增 `reply` 分支合成 OpenAI 兼容响应（JSON 与 SSE，`virbius-gateway/lib/reply.lua`）。
  - `virbius-engine`：新增离线预检端点 `POST /v1/precheck`（单次聚合推理、无审计/计数副作用，用于发布期答案送检）。
  - `virbius-control`：新增 `tb_qa_meta`/`tb_qa_entry`/`tb_qa_history`/`tb_qa_publish_job` 四表与 QA 管理 API；异步发布 job（答案送检 → Redis 快照 → 多实例 ack 状态机）；发布权限分离（`/qa/.../publish` → `PLATFORM_ADMIN`）。
  - 运营台（ops console）：新增「QA 代答」面板（库 CRUD、元信息、条目编辑器含前端校验、发布 + job 时间线 + 多实例 ack、脏表单守卫）；`ops.html` / `js/ops-qa.js` / `js/i18n.js`（zh+en）。
  - 文档：新增 `docs/openspec/qa-reply.md`；`registry`/`gateway-agent`/`engine-admin` OpenAPI 与用户手册同步。
  - 测试：`scripts/test-qa-reply.sh`（agent 直连 E2E，26 断言：匹配/归一化/仲裁/rollout 消费门/审计/发布链+ack）；`scripts/test-openresty-fullchain.sh`（OpenResty POC 真·全链路 E2E，15 断言：透传 / QA 代答 JSON+SSE / deny 压制 / 审计 trace 传播 / 接入契约）；`scripts/test-ops-qa-ui.sh`（运营台 DOM 级 UI E2E，jsdom，12 断言）。

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
