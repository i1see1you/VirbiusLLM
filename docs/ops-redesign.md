# 运营台（ops console）重设计方案

> 状态：**方案稿 v1.4（Phase 0/1 已实施，Phase 2 进行中，见"实施进度"）**。**绿地项目、尚未上线，无向后兼容约束**。
> 决策已定：**全量迁移 Vue3 + Element Plus**；范围 **Phase 0–3**；认证 **双轨道设计、dev/test 关闭（后端运行时零改动，strict 兜底列为可选）**；切换 **快切**；UI 门禁 **vue-tsc + 轻量 Playwright 冒烟**；**后端约束：只修 BUG，其余不改**；8 项决策见"决策记录"。
> 参考实现：`VirbiusAgent` 的 `virbius-control/frontend`（Vue3 + Element Plus + Pinia + vue-router + Vite）。

## 决策记录（v1.4）

| # | 决策项 | 结论 | 依据 / 备注 |
|---|---|---|---|
| 1 | 列表分页 | 不做服务端分页：全量拉取 + 客户端过滤/分页（50/页） | 对齐 Agent |
| 2 | UI 测试门禁 | vue-tsc + **轻量 Playwright 冒烟** | v1.3 重开，绿地建基线成本最低 |
| 3 | 构建产物 | 不入库：`static/ui/` 进 gitignore，`frontend-maven-plugin` 在 `mvn package` 构建 | 对齐 Agent |
| 4 | 切换方式 | 快切：开发末期一个版本内切换并删旧控制台 | 无存量用户 |
| 5 | 会话轨道 | 双轨道设计、dev/test 关闭；**strict 启动校验列为可选加固，本期不改后端行为** | 对齐 Agent 结构，但按"后端只修 BUG"约束降级 |
| 6 | 策略上线 | 单 view + el-tabs（规则放量 / 机器灰度） | 对齐 Agent `RolloutView` |
| 7 | API 类型治理 | 手写 `field()` 归一化，**不做契约快照/代码生成** | 对齐 Agent（v1.4 砍掉 C13） |
| 8 | 双入口写语义冻结 | N/A | 快切 |

> v1.4 相对 v1.3：砍掉 CommandPalette、契约快照、角色权限矩阵、独立 /nodes 与 /settings view、全局 layer=ops 审计流——**全部对齐 Agent 的更简做法**。

## 实施进度

- **Phase 0/1 完成**：`virbius-control/frontend/` 工程、构建链（`frontend-maven-plugin` + CI `frontend` job）、壳层（DefaultLayout/router/stores/api client/i18n/错误边界）、12 view 骨架；`OpsUiController` `/ui` → 新 SPA（旧控制台仍在 `/ops.html`）。门禁：`vue-tsc` 零错误 + Playwright 冒烟全绿；`mvn package` 产物含 `static/ui/`。
- **Phase 2 完成**：**12/12 面板全部迁移**——QA、名单、累计、租户、请求因子、扩展因子、场景注册、网关路由、规则、策略上线（双 tab）、监控、审计。均已对真实 control 做端到端验证；`vue-tsc` 零错误、Playwright 冒烟全绿、`mvn package` 产物含 `static/ui/`。
- **快切完成**：已删除旧控制台（`ops.html` + `static/js/ops-*.js` + `i18n.js` + `static/vendor/`）；`/ui`（及 `/ops`）指向新 SPA；`OpsUiController` 仅保留 `/ui`、`/ui/`、`/ops` → `/ui/index.html`；下线 `scripts/test-ops-qa-ui.sh`；README / user-guide / DESIGN / CHANGELOG 已同步。验证：`/ops.html`、`/ui/rules` → 404（修正 `GlobalExceptionHandler` 把缺失资源当 500 的既有 bug）；后端 127 测试全绿。
- **测试**：mock 冒烟（CI `frontend`）+ **真实后端集成**（CI `ops-console-e2e`，`scripts/test-ops-console-e2e.sh`）覆盖 QA 全流程 / 规则+QA 脏守卫 / 租户隔离；规则面板补齐脏表单守卫。
- **规则页增强（对齐 VirbiusAgent 交互）**：层切换改为**侧栏可展开菜单子项**（`stores/rules.ts` 持有 `currentLayer`，`routeKey` 含 layer）；新建/编辑改为**右侧抽屉**（`el-drawer` rtl）填写；引入 **`ScriptEditor`（CodeMirror 高亮/补全/lint）**、**`DiffConfirm`（保存前 diff）**、**`RuleSimulate`（可折叠模拟面板 + fixture 预设）**、**`AsyncPreview`**、**`DlpRegexTest`** 组件。
- **策略上线页增强（对齐 VirbiusAgent 交互）**：`?tab=` 深链；部署状态卡（pending 规则下钻 modal）；规则**流程条** + **canary 阶梯条** + 6 项 KPI + **24h 折线图（chart.js）** + 审计样本 **trace 详情 modal**；机器灰度 **prepare 版本确认 modal（含 diff）**、生命周期分组、节点分布、事件表；5s 自动刷新开关。新增 `chart.js` + `vue-chartjs`（独立懒加载 chunk）。
- 说明：规则页已由纯 textarea 升级为 CodeMirror（`codemirror` 独立懒加载 chunk，主包不受影响）；策略上线引入 chart.js（独立 chunk）。

## 1. 背景与结论

现有运营台是单个 `ops.html` + 16 个全局脚本 + 约 20 个全局变量的无框架实现。`VirbiusAgent` 的运营台是 Vue3 SPA，二者都用 Spring Boot 托管前端。结论：**可作参照，但应突破其历史包袱（千行 view、全量分页、零 UI 测试）。**

| 维度 | VirbiusAgent | VirbiusLLM（原生） |
|---|---|---|
| 技术形态 | Vue3 SPA，产物进 `static/ui` | 单 HTML + 16 脚本，无构建 |
| 导航/深链 | 14 路由懒加载、query 同步、可分享 | 12 panel 靠 display，无路由 |
| 租户/层隔离 | `routeKey=path:tenant:layer` 强制重挂载 | 切租户 `reloadAll()`，常丢表单 |
| 编辑体验 | 列表→抽屉→数据驱动表单；simple/advanced 双模式 | 命令式 fill/read；8+ sync 函数 |
| 风险控制 | DiffConfirm + 门禁引导 + 执行面只读 | 危险操作一键无确认 |
| 配置即验证 | 内嵌模拟器/正则测试/预览 | 无 |
| 反馈 | feedback store + ElMessage；持久错误页内红字 | 覆盖式 `#log` |

**移植**：双模式编辑器、全链路脏表单守卫、diff 确认 + 风险分级确认、配置即验证、切换即重置、分级轮询 + 语义化空态。
**突破**：千行 view → 子组件化；零 UI 测试 → 加 Playwright 冒烟；后端英文报错当协议 → 结构化错误码（§6）。

## 2. 旧控制台现状（弃件，不修）

未上线，旧控制台无生产角色。**冻结不维护**，仅记录以免新方案重现。

| 位置 | 问题 | 新方案对策 |
|---|---|---|
| `ops-rollout.js:513` | `#btnGatewayArtifact` 空引用 | 不引用未定义元素 |
| `ops-deploy.js:434-443` | `window._onTabSwitch` 无调用方 → 轮询不启动 | store + 生命周期驱动轮询 |
| `ops-tenants.js:54` | `savedKey` 未定义 → key 无法恢复 | session store 统一持久化 |
| `ops-monitor.js:23` | `monFetch` 不带鉴权 | 全走 `api/client.ts` |

结构性问题（转为设计要求）：无路由/深链；切租户丢表单；覆盖式 `#log`；原生 confirm 遍地且确认不一致；表格无分页/搜索/排序；轮询各自为政；i18n 不彻底；工具函数重复。

## 3. 目标架构

```
virbius-control/
  frontend/                     # 新增：Vue3 SPA 工程
    package.json                # vue / element-plus / pinia / vue-router / vue-i18n / vite / chart.js / codemirror
    vite.config.ts              # base:'/ui/', outDir:'../src/main/resources/static/ui'
    playwright.config.ts        # 冒烟 E2E（决策 #2）
    src/
      main.ts  App.vue          # 注册 app.config.errorHandler（§5-10）
      layouts/DefaultLayout.vue # 侧栏(4组)+顶栏(租户/语言/反馈/Key)
      router/index.ts           # hash 路由，12 view 懒加载 + popstate/脏守卫钩子
      stores/{session,feedback,ui}.ts
      api/client.ts             # admin/adminRoot/rawJson + field() 归一化 + 401/403 统一处理
      styles/theme.css          # Element Plus 主题覆盖 + 工具类
      components/               # 统一基础组件（§5）
      views/                    # 12 个面板一一对应
      locales/{zh,en}.ts        # 由现有 i18n.js 扁平字典转换
  src/main/java/.../OpsUiController.java  # /ui → SPA 回退 index.html
  src/main/resources/static/ui/           # 构建产物（.gitignore，决策 #3）
```

- **构建链**：pom 引 `frontend-maven-plugin`，`mvn package` 产出 UI；CI 加 node 跑同一命令 + Playwright。
- **类型契约（决策 #7）**：不做 OpenAPI 快照/代码生成，仅 `field()` 归一化；可选 `api/types.ts` 作开发期提示，不设门禁。
- **切换**：开发期旧控制台为入口；12 面板就绪 + 冒烟通过后，一个版本内删除旧控制台并切换 `/ui`。

### 3.1 后端改动清单（约束：只修 BUG，其余不改）

| 类型 | 改动 | 必要性 | 说明 |
|---|---|---|---|
| 构建 | `virbius-control/pom.xml` 加 `frontend-maven-plugin` | 必要 | 构建工具，非业务逻辑（决策 #3） |
| 构建 | CI 加 node 环境 | 必要 | 同上 |
| 路由接入 | `OpsUiController`：`/ui` 指向新 SPA（现为 302 到 `/ops.html`） | 必要 | 行级改动；hash 路由下 Spring 静态服务 `static/ui/` 即可，无需回退逻辑 |

**后端运行时业务代码零改动**：分页、结构化错误码、ETag、角色门控、审计流均不做。唯一潜在运行时改动是 strict 启动校验（§7.3-2），按约束降级为可选加固，**本期不纳入**。

## 4. 信息架构与路由

顶栏：租户选择器（持久化 localStorage+URL）· 语言 · 反馈中心 · **设置（齿轮入口）**。凭证（API Key）**不在顶栏展示**：设置抽屉内仅显示"已配置 / 未配置"状态，输入遮蔽、不回显明文（对齐 Agent：人用会话登录、Key 在租户页管理；本期无会话轨道，故以设置入口弱化承载 API key 轨道）。

侧栏按 **4 组分区**（值守 / 策略 / 资产 / 平台）渲染**分组标题 + 图标**（对齐 Agent）；「规则」为可展开子菜单（cloud/gateway/edge 子项，`stores/rules.ts` 持有当前层）。

| 分组 | 路由 | view | 来源 |
|---|---|---|---|
| 值守 | `/monitor`、`/audit` | MonitorView / AuditCenterView | monitor、audit-center |
| 策略 | `/rules?layer=` | RulesView（子导航 cloud/gateway/edge） | rules |
| 策略 | `/rollout` | RolloutView（**tabs：规则放量 / 机器灰度**，决策 #6） | rollout + `ops-deploy.js` |
| 策略 | `/qa?qaId=` | QaView | qa |
| 资产 | `/lists`、`/cumulatives`、`/bindings`、`/ext-bindings`、`/scenes`、`/gateway-routes` | 各自 View | 对应 panel |
| 平台 | `/tenants` | TenantsView | tenants |

- **消歧（B6，对齐 Agent）**：机器灰度**编排**与**节点清单/健康**都归 `RolloutView`（canary tab，数据源 `/deploy-rollout/*`）；**不设独立 `/nodes`、`/settings` view**——Agent 即如此（节点分布内嵌 RolloutView）。
- **bundle 上下文（B8）**：`bundleId`/`bundleVer` 作为 `session store` 固定默认值（`poc-default`/`0.1.0`）注入 metadata 类请求，不做选择器（对齐 Agent `session.ts:21-22`）。
- **深链与浏览器行为**：`routeKey=path:tenant:layer`（layer 由 `stores/rules.ts` 持有，随侧栏菜单子项切换）；切租户强制重挂载；`?trace_id=`/`?qaId=`/`?ruleId=` 可分享。`router.beforeEach` 拦脏表单；hash 配 `popstate` 守卫；同一规则双标签并发编辑时用 `updated_at` 乐观锁。

## 5. 统一基础组件

收敛现有三种编辑语义为一套：

1. `DataTable`：客户端分页/过滤/排序（决策 #1）+ 行选中 + 空态语义（无数据/加载失败/无流量）。
2. `DrawerEditor`：右侧抽屉表单壳，内置脏守卫、保存中态、beforeunload、Esc、popstate 集成。
3. `SchemaForm`：字段配置驱动，simple/advanced 双模式（脚本字段切 CodeMirror，旧规则服务端 `parse-condition` 自动降级）。
4. `DiffConfirm`：保存前 LCS diff 弹窗。
5. `ConfirmDialog`：风险分级确认（普通/破坏性/需 reason）。
6. `PublishPanel`：发布 job 时间线 + 多实例 ack（复用 QA 后端语义）。
7. `InlineValidator`：内嵌验证器——正则测试、QA 命中模拟、放量命中预览、脚本 lint/补全。
8. `FeedbackCenter`：toast（瞬时）+ 面板内 banner（持久）；替换 `#log`。
9. `TenantSelect`。
10. **`AppErrorBoundary` + 全局 `errorHandler`**：捕获渲染/未处理异常，显示可恢复错误页而非白屏。**可选**：识别懒加载 chunk 失效并提示刷新（Agent 无处理，属廉价保险）。
11. **`Charts` / `KpiCard` / `EmptyState`**：统一图表封装（`chart.js` + `vue-chartjs`，独立 chunk）；Rollout / Monitor 复用。
12. **规则编辑组件（对齐 Agent）**：`ScriptEditor`（CodeMirror 高亮/补全/lint，`utils/scriptAssist.ts`）、`DiffConfirm`（保存前 LCS 行 diff）、`RuleSimulate`（可折叠模拟面板 + fixture 预设 + trace）、`AsyncPreview`（异步消息模板预览）、`DlpRegexTest`（自定义正则即时测试）。

## 6. 后端配套（可选增强，本期均不实施）

分页/搜索接口不做（决策 #1）。以下仅为将来可选，**均不改在线链路、本期一律不做**（§3.1 约束）：

- **结构化错误码**：门禁/校验失败返回 `{code, message, params}`，前端按 code 映射 i18n（规避 Agent"正则翻译英文报错"缺陷）。
- **轮询/增量**：列表支持 ETag 或 `revision` 头，减少全量重拉。
- **操作留痕（B5，对齐 Agent）**：放量/部署已由后端 `operator`+`comment` 字段承载（`tb_rule_rollout`/`deploy_rollout`），QA 有 `tb_qa_history.actor`；**不新建 `layer=ops` 全局审计流**，UI 在既有 rollout/QA 时间线内展示 operator。

## 7. 认证模型（双轨道设计，本期后端零改动）

### 7.1 现状事实

| | VirbiusLLM | VirbiusAgent |
|---|---|---|
| 依赖 | 无 Spring Security、无 OIDC | operator JWT + 外部 OIDC（默认 `127.0.0.1:8082`）+ JWKS |
| 过滤器 | 单轨道：`Bearer`/`X-Virbius-Api-Key` | 双轨道：API key + operator JWT（cookie，`ApiKeyAuthFilter.java:177-200`） |
| `/ui` | 完全豁免（`ApiKeyAuthFilter.java:49`） | 开 JWT 后要求 cookie；豁免 `/ui/assets/` 等 |
| 兜底 | `enabled:false` 给 `PLATFORM_ADMIN`，无 strict | dev 仅限 dev；strict 档全关则启动失败 |

### 7.2 对比

| 维度 | 纯 API key | Cookie 会话 |
|---|---|---|
| 对象 | 机器/脚本/CI | 人（浏览器） |
| 身份/审计 | 仅 credential label | 真实用户，可追溯到人 |
| 生命周期 | 长期 | 短期（60 分钟） |
| XSS | localStorage 可被读 | httpOnly |
| CSRF | 免疫 | 需 SameSite=Lax |
| 前端复杂度 | 低 | 中 |
| 部署依赖 | 零 | 需外部 auth |
| 角色模型 | 现有 viewer/tenant_admin/platform_admin | JWT claims 映射同一体系 |

### 7.3 决策（决策 #5）

1. **双轨道设计**：两开关 + 参数留 env；**本期只交付 API key 轨道**，OIDC 开关位预留，外部 auth 不引入。
2. **默认策略**：维持现状（两开关默认关，dev principal）——**本期不改后端鉴权行为**。**可选加固（Phase 4 或单独小 PR）**：prod/staging 两开关全关则启动失败（对齐 Agent strict）。此项是唯一涉及后端逻辑的行为变更，按"后端只修 BUG"约束暂不纳入。
3. **Phase 1 内容**：修 localStorage 恢复、401/403 统一处理；零后端改造。
4. **启用会话轨道时的约束**：豁免清单照抄 Agent；**401/会话过期禁止直接跳登录**——先弹"可保存草稿后重新登录"，与脏守卫联动。
5. **前端契约**：`api/client.ts` 统一注入凭证，401/403 集中处理。

## 8. 权限与操作留痕

### 8.1 权限（前端不做门控，对齐 Agent）

Agent 前端**无任何角色门控**（session store 无 role 字段），靠后端 403 强校验。参考同款：

- 不做角色矩阵 UI、不做 `v-permission`；面板/按钮对任何登录者可见。
- 后端 401/403 统一由 `api/client.ts` 捕获：401 走 §7 登录/凭证流程；403 在面板内提示"权限不足"，不静默。
- dev（关鉴权）与 prod 行为一致，无需角色种子即可验证。

### 8.2 操作留痕

见 §6；放量/部署/QA 的 operator 由既有时间线展示，不新增全局审计流。

## 9. 分阶段计划

### Phase 0 — 收尾约定（旧控制台不修）
- 不修 §2 的旧 bug（弃件）；旧控制台**冻结不维护**，仅保留到快切。
- 认证维持现状，后端零改动；strict 兜底列为可选加固（§7.3-2）。
- 统一工具约定。

### Phase 1 — 壳层与深链
- 搭建 `frontend/`；pom 接 `frontend-maven-plugin`；CI 加 node + Playwright；`OpsUiController` 指向新 SPA（§3.1）。
- DefaultLayout、router（popstate/脏守卫钩子）、session/feedback store、api client（API key 轨道 + `field()`）、`AppErrorBoundary`。
- 12 view 先占位挂骨架；开发期旧控制台仍是正式入口。
- 验收：`vue-tsc` 零错误；冒烟"全路由无 console error"通过；切租户/层零残留；语言切换生效。

### Phase 2 — 统一编辑范式

迁移顺序、体量、理由：

| 序 | 面板 | 体量 | 理由 |
|---|---|---|---|
| 1 | QA | M | 已有后端发布链、交互最完整，作范式样板 |
| 2 | 名单、累计、租户 | S–M | 标准 CRUD，验证 DataTable/DrawerEditor/SchemaForm |
| 3 | bindings / ext-bindings / 场景 / 网关路由 | S–M | 统一"本地数组+保存"语义 |
| 4 | 规则 | L | 双模式编辑器 + 原 8 个 sync 联动，组件压力测试 |
| 5 | 策略上线（单 view 双 tab） | L | 合并放量 + 机器灰度编排，最大工作量 |
| 6 | 监控、审计 | M–L | 图表 + trace 查看，复用 §5-11 组件 |

- 交付 §5 的 11 个基础组件。
- QA 迁移时**下线 `scripts/test-ops-qa-ui.sh`**；断言转冒烟用例。
- 每面板 DoD：`vue-tsc` 零错误 + 冒烟通过 + 手工冒烟清单（新建/编辑/保存/脏守卫/删除/发布）。
- 全部完成后**快切**：删旧控制台，`/ui` 指向新 SPA。

### Phase 3 — 数据与轮询治理
- 客户端分页/过滤统一到 `DataTable`；集中请求编排与 loading/error 边界；轮询集中治理（值守 5s / 监控 30s / 其余按需）。
- 验收：万级数据不卡；失败可重试不丢状态；401 统一处理。
- 服务端分页作为后续立项（触发：单表超万级且客户端分页不满足）。

### Phase 4（可选）
审计↔trace↔放量互跳、操作历史、导出；启用会话轨道（§7.3-4）。

## 10. 测试与切换策略

### 10.1 测试门禁（决策 #2）
- **类型门禁**：`vue-tsc --noEmit`（build/CI 内）。
- **Playwright 冒烟**（mock API，CI `frontend` job 必跑）：打开全部 12 路由无 console error / 未捕获异常；默认路由重定向；切租户重挂载 + 持久化；中英切换；各面板渲染。策略上线另有专项 `frontend/e2e/rollout.spec.ts`：Tab 深链、部署状态卡 pending 下钻 modal、选规则流程条/阶梯/KPI、force 未填说明内联报错、审计样本 trace 下钻、机器灰度 prepare 版本确认弹窗。
- **Playwright 集成（真实 control，CI `ops-console-e2e` job）**：`frontend/e2e/integration/ops-console.spec.ts`，由 [`scripts/test-ops-console-e2e.sh`](../scripts/test-ops-console-e2e.sh) 构建并启动 dev control（临时端口/数据目录）后驱动真实 SPA，覆盖：
  1. **QA 全流程**：建库 → 加条目 → 保存（重载校验持久化）→ 发布（触发发布链，产生 job）；
  2. **规则**：新建规则 → 编辑 → 离开被脏守卫拦截；
  3. **QA 脏守卫**：编辑条目后离开被拦截；
  4. **租户隔离**：切租户后数据按租户隔离。
- **后端 E2E 保持**：`test-qa-reply.sh`、`test-openresty-fullchain.sh` 等继续跑。
- `test-ops-qa-ui.sh`（jsdom）已随旧控制台下线，断言迁移至上述 smoke/集成用例。

### 10.2 量化验收
- bundle gzip 上限（主包 ≤ 500KB，按需加载 Element Plus + manualChunks）；`codemirror` 独立懒加载 chunk（~175KB gzip，仅 `/rules` 加载），`chart.js`/`vue-chartjs` 独立 chunk（~58KB gzip，仅 `/rollout`、`/monitor` 加载），主包不受影响；
- 首屏可交互 ≤ 1.5s（内网）；
- 冒烟全绿、`vue-tsc` 零错误、12 面板冒烟清单 100% 通过。

### 10.3 切换
**快切**：开发期旧控制台为入口；就绪并通过冒烟后，一个版本内删 `ops.html` 与 `static/js/ops-*.js`、`i18n.js`，`/ui` 指向新 SPA。无存量用户，回滚为常规发布回退。

## 11. 风险

1. **工作量**：12 面板全量重写，rules / 策略上线为 L 级；接受单 view 巨型化，缓解 = 内部子组件化。
2. **自动化天花板**：冒烟覆盖"能打开 + 主流程"，不覆盖复杂交互分支；深层回归靠手工清单。
3. **快切**：无存量用户，风险低；切换前须冒烟全绿。
4. **数据量超阈值**：客户端分页在单表过万级可能不足，需补服务端分页（一次返工风险，有意识接受）。
5. **构建链引入**：Node/Vite/frontend-maven-plugin/Playwright/CI node。
6. **i18n 迁移**：79KB 扁平字典转 vue-i18n；`data-i18n-html` 的 `{N}` 机制替换为插槽。
7. **类型契约手工维护**：`field()` 归一化靠人肉同步 snake_case 字段（对齐 Agent，无快照），漂移风险保留。
8. **文档同步**：README、user-guide、CHANGELOG 随切换更新。
