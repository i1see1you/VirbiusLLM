# Glossary

This document defines key terms used across VirbiusLLM documentation and codebase.

## Architecture

| Term | Definition |
|------|------------|
| **Edge (端)** | The local SDK layer (`virbius-core`) embedded in client applications. Runs L0 keyword/blacklist detection and DLP desensitization with sub-millisecond latency. |
| **Gateway (管)** | The on-path real-time firewall (APISIX/Kong + `virbius-guard` Lua plugin + `virbius-gateway-agent` Rust sidecar). Performs access list checks, rate limiting, and dispatches to cloud engine. |
| **Cloud (云)** | The backend decision engine (`virbius-engine`) offering Prompt L1 semantic detection, Groovy L3 policy scripting, and ActionMerge. |
| **Control plane** | The admin service (`virbius-control`) serving as the single source of truth for rules, rollout state, and artifact publishing. |
| **Compiler** | `virbius-compiler` — transforms registry rules into layer-specific artifacts (edge manifest, gateway JSON, engine input). |
| **Execution plane** | The layers (edge, gateway, cloud) where rules are actively enforced. Rules in `draft` or `disabled` state are not in the execution plane. |

## Rules & Rollout

| Term | Definition |
|------|------------|
| **Rule** | A security policy defined in `virbius-control` with a unique `rule_id`. Can be keyword, prompt, Groovy, access list, etc. |
| **rule_revision** | Integer revision number incremented on each rule content change. Stored in `tb_rule_history`. |
| **rollout_state** | Lifecycle state of a rule: `draft` → `dry_run` → `canary` → `full`. Controls whether and how a rule is enforced. |
| **draft** | Initial state; not in execution plane. |
| **disabled** | Permanently removed from execution plane; can be recovered to `draft`. |
| **dry_run** | Observational mode; hits produce `effective_action=review` without blocking. |
| **canary** | Partial enforcement; traffic is bucketed (e.g., by `device_id`) and only in-bucket requests are blocked/captcha'd. |
| **full** | Full enforcement; all hits are blocked or captcha'd. |
| **canary_percent** | Percentage of traffic in the canary bucket (0–100). When 100%, the next step is `full`. |
| **canary ladder** | A sequence of percentage steps (e.g., 1→5→10→25→50→100) defined per tenant. |
| **enforce_mode** | Legacy field (`dry_run` / `canary` / `full`); being replaced by `rollout_state`. Still present in PoC code. |
| **rule_status** | Legacy field (`draft` / `active` / `disabled`); being replaced by `rollout_state`. |
| **intent_action** | The action a rule intends to produce: `allow`, `deny`, `captcha`, `review`. |
| **effective_action** | The final action after multi-rule merge: `allow`, `block`, `captcha`, `review`. |
| **runtime** | Execution form of a rule: `lua-dsl` (edge), `lua` (gateway), `native`/`prompt`/`groovy`/`list_match`/`cumulative` (cloud). |
| **Skill** | Legacy term for a security rule; being replaced by "rule" in documentation. |

## Decision & Merge

| Term | Definition |
|------|------------|
| **ActionMerge** | The algorithm in `virbius-engine` that consolidates multiple rule decisions into a single `effective_action`. Priority: deny > captcha > review > allow. |
| **PolicyMerger** | Alternative name for ActionMerge. |
| **risk_score** | Numeric score (0–1000+) assigned per rule hit. `max_risk_score` is the maximum across all matching rules. |

## Manifest & Artifacts

| Term | Definition |
|------|------------|
| **Manifest** | A JSON artifact published by `virbius-control` consumed by a specific layer. |
| **edge-manifest.json** | Per-app manifest for edge SDK; contains `rules[]`, `dlp_rules[]`, `sdk_config`. |
| **access-lists.json** | Gateway runtime file containing `context_bindings`, `lists[]`, `cumulatives[]`, `script_rules[]`. |
| **scene-registry.json** | Gateway runtime file mapping `(app_id, uri, match)` → `scene_id`. |
| **Bundle** | A versioned collection of rules and metadata (`tb_bundles`). |
| **bundle_version** | Version string (e.g., `0.1.0`) identifying a bundle release. |
| **Artifact** | A compiled output from `virbius-compiler` or `virbius-control` deployed to a layer. |
| **Scheme B+** | Edge manifest delivery via Control-direct pull with tenant-level Bearer authentication. |
| **RuleCache** | In-memory cache in `virbius-engine` that hot-reloads rules from the registry DB. |

## Scene & Context

| Term | Definition |
|------|------------|
| **scene** | A named context (e.g., `chat`, `code_assistant`) that determines which rules apply. |
| **tenant_id** | Multi-tenant identifier isolating rules, data, and configuration. |
| **app_id** | Application identifier within a tenant; used for per-app edge manifests. |
| **context_bindings** | Bundle metadata mapping logical variable names (e.g., `app_id`) to HTTP sources (e.g., `X-App-Id` header). |
| **ScanContext** | Input struct for `virbius-core` edge scan: `user_id`, `device_id`, `scene`, `trace_id`. |

## Trace & Audit

| Term | Definition |
|------|------------|
| **trace_id** | UUID v4 identifier propagated across all layers for end-to-end request tracing. |
| **AuditIngest** | `virbius-control` service that consumes audit events from Redis Stream or Kafka into `tb_audit_events`. |
| **tb_audit_events** | Database table storing review/block/captcha and sampled allow events. |
| **JSONL allow log** | Append-only JSONL file for allow audit events not stored in DB. |

## L1 / L2 / L3 Detection

| Term | Definition |
|------|------------|
| **L0** | Edge-level local keyword/blacklist detection; synchronous, sub-millisecond. |
| **L1** | Lightweight classification (small model, regex, signature rules); target <50ms latency. |
| **L2** | Semantic detection and instruction restructuring; heavier, triggered on high-risk only. |
| **L3** | Cloud policy computation (Groovy scripting); multi-rule merge and disposition. |

## Deployment

| Term | Definition |
|------|------------|
| **APISIX** | API gateway used as the primary gateway backend in MVP. |
| **OpenResty** | Alternative gateway backend (stretch goal) using compiler-flattened artifacts. |
| **gateway-agent** | Rust sidecar (`virbius-gateway-agent`) that bridges the Lua gateway to `virbius-engine`. |
| **DLP** | Data Loss Prevention — masking sensitive data (PII, credentials) before sending to LLM. |
| **TokenVault** | In-process store holding plaintext ↔ placeholder mappings for DLP desensitization. |
