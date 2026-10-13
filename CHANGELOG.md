# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added
- **QA 代答（安全知识代答，M1a）**：
  - `virbius-gateway-agent`：新增 `qa.rs`（归一化 + exact/contains/regex 匹配、pointer+blob 快照加载与热更新、ack 上报、消费决策）；`/v1/evaluate` 支持 `last_user_message`，响应新增 `reply_text`/`qa_entry_id`；新增 `reply` 动作（仅 `allow && !degraded` 消费，其余压制）；审计事件 `QA_REPLY`/`QA_SUPPRESSED`/`QA_DRY_RUN_CANDIDATE`。
  - 网关（APISIX / OpenResty）：抽取 `last_user_message`，新增 `reply` 分支合成 OpenAI 兼容响应（JSON 与 SSE，`virbius-gateway/lib/reply.lua`）。
  - `virbius-engine`：新增离线预检端点 `POST /v1/precheck`（单次聚合推理、无审计/计数副作用，用于发布期答案送检）。
  - `virbius-control`：新增 `tb_qa_meta`/`tb_qa_entry`/`tb_qa_history`/`tb_qa_publish_job` 四表与 QA 管理 API；异步发布 job（答案送检 → Redis 快照 → 多实例 ack 状态机）；发布权限分离（`/qa/.../publish` → `PLATFORM_ADMIN`）。
  - 运营台（ops console）：新增「QA 代答」面板（库 CRUD、元信息、条目编辑器含前端校验、发布 + job 时间线 + 多实例 ack、脏表单守卫）；Vue3 SPA（`virbius-control/frontend/`，产物 `static/ui/`）（zh+en）。
  - 文档：新增 `docs/openspec/qa-reply.md`；`registry`/`gateway-agent`/`engine-admin` OpenAPI 与用户手册同步。
  - 测试：`scripts/test-qa-reply.sh`（agent 直连 E2E，26 断言：匹配/归一化/仲裁/rollout 消费门/审计/发布链+ack）；`scripts/test-openresty-fullchain.sh`（OpenResty POC 真·全链路 E2E，15 断言：透传 / QA 代答 JSON+SSE / deny 压制 / 审计 trace 传播 / 接入契约）。

### Changed
- **运营台（ops console）全量迁移至 Vue3 + Element Plus 单页应用**（`virbius-control/frontend/`，构建产物 `static/ui/`）：
  - 12 个面板全部重写（QA、名单、累计、租户、请求因子、扩展因子、场景注册、网关路由、规则、策略上线、监控、审计）；hash 路由 + 深链、切租户/层强制重挂载、全链路脏表单守卫、统一 api client（`field()` 归一化 + 401/403 处理）、zh/en i18n、全局错误边界。
  - 构建链：`frontend-maven-plugin`（`mvn package` 产出 UI）；CI 新增 `frontend` job（`vue-tsc` + Playwright 冒烟）。
  - 删除旧控制台 `ops.html` / `static/js/ops-*.js` / `static/js/i18n.js` / `static/vendor/`；`/ui`（及 `/ops`）指向新 SPA；`scripts/test-ops-qa-ui.sh` 下线（断言迁至 Playwright 冒烟）。
  - 测试：mock 冒烟（CI `frontend` job，含策略上线专项 `frontend/e2e/rollout.spec.ts`：Tab 深链/pending 下钻/流程条-force 校验/trace 下钻/版本弹窗）+ 真实后端集成 `scripts/test-ops-console-e2e.sh` / `frontend/e2e/integration/`（CI `ops-console-e2e` job），覆盖 QA 全流程、规则/QA 脏表单守卫、租户隔离、策略上线；规则面板补齐脏守卫。
  - 侧栏菜单规整（对齐 VirbiusAgent）：按 值守 / 策略 / 资产 / 平台 4 组分区渲染分组标题 + 图标；「规则」为可展开子菜单。
  - 顶栏凭证入口弱化：API Key 改为「设置（齿轮）」入口，仅显示"已配置/未配置"状态，输入遮蔽、不回显明文（对齐 Agent；本期无会话轨道故以设置承载 API key 轨道）。
  - 规则配置页对齐 VirbiusAgent 交互：层切换改为侧栏可展开菜单子项（`stores/rules.ts`），新建/编辑改为右侧抽屉（`el-drawer`）；引入 `ScriptEditor`（CodeMirror 高亮/补全/lint）、`DiffConfirm`（保存前 diff）、`RuleSimulate`、`AsyncPreview`、`DlpRegexTest` 组件。
  - 策略上线页对齐 VirbiusAgent 交互：`?tab=` 深链；部署状态卡（pending 可下钻 modal）；规则流程条 + canary 阶梯条 + 6 项 KPI + 24h 折线图（chart.js）+ 审计样本 trace 下钻 modal；机器灰度 prepare 版本确认 modal（含 diff）、生命周期分组、节点分布、事件表；5s 自动刷新开关。
  - 设计见 [docs/ops-redesign.md](docs/ops-redesign.md)。

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
