# VirbiusLLM

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange)](https://adoptium.net/)
[![Rust](https://img.shields.io/badge/Rust-1.80%2B-orange)](https://www.rust-lang.org/)
English: [README.md](README.md)

VirbiusLLM 是一款为大模型定制的安全防护工具，支持实时 Skill 与近线 Skill 配置，通过 Agent 辅助生成和优化规则 Skill，主要应用场景是大模型 prompt 越狱和敏感指令的拦截，以及大模型输出的二次审计。

整体技术方案基于 **「端-管-云」协同架构** 与 **统一控制面、分层执行面**：

```
用户请求
   │
   ▼
┌────────────────────┐
│ ① 端 L0 SDK       │  virbius-core（Rust / C ABI）
│   scan + DLP      │  本地同步，毫秒级
└────────┬───────────┘
         │ HTTP + Virbius headers
         ▼
┌────────────────────┐
│ ② 管 网关         │  APISIX/Kong + virbius-guard
│   Lua 规则        │  必经流量，十～数百 ms
└────────┬───────────┘
         │ gateway-agent sidecar
         ▼
┌────────────────────┐
│ ③ 云 Engine       │  virbius-engine
│   Prompt L1 +     │  按需 Evaluate
│   Groovy L3       │  策略合并（ActionMerge）
└────────┬───────────┘
         ▼
       LLM API
```

| 层 | 职责 | 组件 |
|----|------|------|
| **① 端** | 本地违禁词/黑名单/DLP 脱敏；同步、毫秒级；可离线 | `virbius-core` |
| **② 管** | 实时防火墙：静态 Lua 规则、名单、限流；按需调 engine | `virbius-gateway`（APISIX/Kong 插件）+ `virbius-gateway-agent`（Rust sidecar） |
| **③ 云** | 语义检测（Prompt L1）、Groovy 策略（L3）、多规则合并；按风险等级选择性调用 | `virbius-engine` |

| 跨层 | 职责 | 组件 |
|------|------|------|
| **控制面** | 规则唯一真源、放量状态、运营台；发布产物到各层 | `virbius-control` |
| **Compiler** | Registry 规则 → 各层产物（edge manifest、gateway JSON、engine 输入） | `virbius-compiler` |

版本真源为 **`rule_history` / `rule_revision`**。发布流：**Compiler + PublishOrchestrator** —— 端走 CDN、管走 etcd/文件、云走 **Registry DB → RuleCache**。

**MVP 范围（已冻结）**：端 L0 + **APISIX 必达** + engine（L1 Prompt + Groovy）；放量模式 `dry_run` / `canary` / `full`。

## 文档

| 主题 | 链接 |
|------|------|
| 系统设计 | [docs/DESIGN.md](docs/DESIGN.md) |
| 用户使用手册（中文） | [docs/user-guide.md](docs/user-guide.md) |
| User guide (EN) | [docs/user-guide.en.md](docs/user-guide.en.md) |
| 种子数据与运营 API | [docs/seed-api.md](docs/seed-api.md) |
| 仓库布局 | [docs/repo-layout.md](docs/repo-layout.md) |
| 术语表 | [docs/GLOSSARY.md](docs/GLOSSARY.md) |
| 发布流程 | [docs/RELEASING.md](docs/RELEASING.md) |

## 环境要求

- **JDK 17**，**Maven 3.9+**
- **Rust**（用于 `virbius-core` 和 `virbius-gateway-agent`）
- 可选：**Redis**、**Ollama/vLLM**（Engine Prompt 1B 规则）

详见 [docs/repo-layout.md](docs/repo-layout.md)。

## 快速开始

```bash
# 1. 构建
mvn clean install -DskipTests          # virbius-control + virbius-engine
cargo build --release                   # gateway-agent + virbius-core

# 2. 本地启动（H2 内存数据库，自动建表）
cd virbius-control
mvn spring-boot:run \
  -Dspring-boot.run.profiles=local

# 3. smoke test：创建租户 → 创建 Skill → 发布 → 验证放量
curl -s -X POST http://localhost:8080/api/tenants \
  -H 'Content-Type: application/json' \
  -d '{"name":"smoke-tenant","code":"smoke"}'
# 详见 docs/user-guide.md
```

**最低要求**：JDK 17、Maven 3.9+、Rust（详见 [repo-layout.md §环境要求](docs/repo-layout.md)）。

### 端侧 SDK（virbius-core）

Rust 实现的端侧规则引擎，提供 C ABI，可嵌入移动端/Web/小程序：

```rust
use virbius_core::engine::Engine;

let mut engine = Engine::new("path/to/manifest.yaml");
engine.load_blocklist("blocklist.txt");
engine.load_sensitive_words("sensitive.txt");

let result = engine.evaluate(&request);
match result.action {
    Action::Block => println!("拦截"),
    Action::Allow => println!("放行"),
    Action::Mask   => println!("脱敏"),
}
```

**本地运行 gate-way-agent**：

```bash
cd gateway-agent
cargo run -- --config-path ./config.yaml
```

详见 [docs/user-guide.md](docs/user-guide.md)。

### 网关插件

支持 **APISIX** 和 **OpenResty** 两种部署：

**APISIX**：
```yaml
# 在 routes 中引用
plugins:
  - virbius-guard
virbius-guard:
  agent_host: 127.0.0.1
  agent_port: 9081
  enable: true
```

**OpenResty**（直接 Lua 加载，不需额外网关层）：
```nginx
http {
    lua_package_path "/path/to/virbius-gateway/lualib/?.lua;;";
    init_worker_by_lua_block {
        local guard = require("virbius.guard")
        guard.init()
    }
    server {
        location /v1/chat {
            access_by_lua_block {
                local guard = require("virbius.guard")
                guard.handle()
            }
        }
    }
}
```

API 生命周期由 **virbius-control** Admin API 统一管理；详见 [docs/seed-api.md](docs/seed-api.md)。

## 分层职责

### 一、 端侧：轻量级交互防护与行为感知

端侧作为用户与大模型交互的第一触点，核心职责是在不影响用户体验的前提下，完成前置风险过滤与用户行为基线采集。

1. **风控数据采集与用户行为分析**：在SDK或前端应用中嵌入轻量级探针，实时采集用户的输入行为（如打字速率、异常高频请求）、设备指纹及操作序列。通过本地行为分析模型，快速识别脚本自动化攻击、撞库或异常遍历行为，对可疑用户提前打上风险标签。
2. **协议保护与挑战响应**：针对端侧发起的请求，实施严格的协议校验。当检测到疑似恶意爬虫或非正常人类行为时，自动触发无感或轻量级的人机验证（挑战响应），有效拦截批量化的提示词注入尝试和DDoS攻击，将低阶威胁阻挡在业务入口之外。
3. **前置合规过滤**：内置精简版敏感词库与正则规则，对用户输入的明显违规内容（如涉政、涉黄关键词）进行本地拦截，减少无效流量向下游传输，降低整体计算成本。

### 二、 网关侧：实时语义拦截与协议校验

网关作为流量调度的核心枢纽，承担着“实时防火墙”的角色，重点解决提示词注入、越狱攻击及敏感数据泄露等高频风险。

1. **双向语义检测与拦截执行**：部署高性能的大模型安全网关，对进出大模型的流量进行双向深度检测。
- **输入侧**：利用分层检测架构，精准识别提示词注入（如角色扮演、指令劫持）、越狱攻击（如DAN模式）及恶意Payload。采用“指令重构”技术，在剥离恶意语义的同时保留合法业务诉求，避免暴力拦截导致的误杀。
- **输出侧**：对大模型生成的流式内容进行实时还原与合规审查，拦截包含偏见歧视、暴力恐怖、幻觉或不符合价值观的生成内容。
1. **敏感数据防泄漏（DLP）**：在网关层集成隐私计算与正则匹配能力，对身份证号、手机号、企业核心代码等敏感信息进行实时识别。根据策略执行脱敏、去标识化或直接阻断，确保“数据不出域、隐私不外露”。
2. **协议校验与API管控**：对API调用进行资产梳理与合规校验，识别异常的参数篡改、未授权访问及超频调用。基于“最小必要权限”原则，防止API滥用导致的算力资源耗尽或数据窃取。

网关侧（**APISIX / Kong**）安全规则按 **Global → Service → Route** 三层绑定；**virbius-gateway** 通过 **共享 Lua 核心 + 薄插件 + gateway-agent** 对接 **virbius-engine**。**MVP** 含 **端侧 L0**（本地违禁词、黑名单拦截）及 **APISIX/Kong** 插件（同一 Bundle；端 CDN / 管 etcd / 云 engine 分层下发）。详见 [DESIGN.md §11.6](docs/DESIGN.md)。

### 三、 云侧（主链路）：全局策略计算与处置渲染

云侧主链路是安全大脑的实时决策中心，负责汇聚端管数据，进行复杂逻辑计算并下发最终处置指令。

1. **数据汇聚与多维关联**：实时接收来自端侧的行为日志与网关侧的流量日志，结合用户画像、业务场景及历史风控记录，进行多维数据关联分析。
2. **动态策略计算**：基于预置的风控规则引擎与实时计算模型，对当前会话进行综合风险评分。支持细粒度的策略配置（如按租户、按业务线区分），动态调整拦截阈值。例如，对高风险用户触发更严格的审核策略，对可信用户放行以降低延迟。
3. **处置渲染与安全代答**：根据策略计算结果，向网关或端侧下发处置动作（放行、拦截、告警、脱敏）。针对高敏感问题，支持“安全代答”机制，即不经过大模型生成，直接返回预设的合规安全回复，在确保绝对安全的同时提升响应速度。

### 四、 云侧（异步链路）：智能进化与模型调控

异步链路作为安全体系的“进化引擎”，通过旁路分析实现长周期的威胁狩猎与模型迭代，持续提升整体防控的准确率。

1. **旁路特征采集与大数据汇总**：全量采集主链路的交互数据（包括被拦截的攻击样本与正常业务数据），构建大规模的安全语料库。通过离线大数据计算，挖掘隐蔽的长尾攻击特征与新型对抗样本。
2. **机器学习与对抗演练**：利用汇聚的高质量攻防语料，对安全检测模型进行持续的对抗训练与微调（Fine-tuning）。通过模拟红队攻击（Red Teaming），主动发现模型在逻辑推理、多轮对话中的潜在漏洞，不断迭代检测算法。
3. **模型调控与策略优化**：基于离线评估结果，量化主链路检测模型的误报率与漏报率。定期将优化后的模型参数、新增的威胁特征库及调优后的策略规则，自动下发同步至网关与端侧，形成“检测-分析-优化-部署”的闭环安全运营体系。

该方案通过端侧的轻量过滤、网关的实时阻断、云主链路的精准决策以及云异步链路的持续进化，构建了一个动静结合、纵深防御的大模型安全免疫系统，能够有效应对从传统内容违规到复杂智能体攻击的全方位挑战。

## 路线图

以下改进项按优先级划分，指导从 MVP 到生产级平台的演进。

### P0：核心模块

| 建议 | 说明 |
|------|------|
| **MVP（端-管-云）** | 端 `virbius-core`（违禁词+黑名单）+ 网关双插件 + engine + control 发布；**含 dry_run/canary/full**。详见 [§11.6](docs/DESIGN.md)。 |
| **定义检测分级 L0–L3** | L0 端；L1/L2 云检测（管侧 RPC 调用）；L3 云 Policy。管侧仅静态 Skill。明确每层最大延迟与触发条件。 |
| **统一决策模型** | 多端/管/云都产出「风险分 + 动作」，由 **`virbius-engine`**（`PolicyMerger` / ActionMerge）合并（避免多处各拦各的）。 |
| **流式输出审计规范** | 规定 chunk 大小、缓冲窗口、是否允许「先出后撤」；高合规场景建议 hold-then-release。 |
| **Skill 生命周期** | `draft` → **publish** → **dry_run** → **canary** → **full**；详见 [DESIGN.md](docs/DESIGN.md) 与 [seed-api.md](docs/seed-api.md) |
| **Fail 策略表** | 按租户配置：核心金融 fail-close，内部工具 fail-open + 异步告警。 |

### P1：差异化与竞争力

| 建议 | 说明 |
|------|------|
| **会话级风控** | 不只看当前 turn，维护 session risk score（多轮越狱、话题漂移）。 |
| **攻击类型图谱** | 将 skill 按 MITRE 式分类（直接注入、间接注入、越狱模板、数据渗出），便于运营与报表。 |
| **人机协同队列** | 灰区（0.4–0.7 分）进人工审核或二次模型，降低误杀。 |
| **与业务上下文绑定** | 同一句话在「通用聊天」vs「医疗问诊」vs「代码助手」策略不同，需要 tenant + scene + role 三维策略。 |
| **Agent 生成 skill 的护栏** | Agent 只产出候选规则 + 测试用例，禁止直推生产；自动跑回归集通过后才可合并。 |

### P2：中长期演进，暂不实现

| 建议 | 说明 |
|------|------|
| **专用安全小模型** | 网关用蒸馏后的轻量分类/序列标注模型，降低对大模型二次调用的依赖与成本。 |
| **对抗样本库运营** | 公开基准（如 JailbreakBench）+ 自有线上样本；每周自动回归。 |
| **可解释性输出** | 拦截原因对用户/运营可见（规则 ID、相似样本、风险维度），方便申诉与调优。 |
| **Supply chain** | 若接第三方 MCP/插件，增加插件签名、权限白名单、调用预算。 |

## 系统分层说明

在「端-管-云」架构之上，系统可进一步划分为五层。各层职责不同，通过 L0–L3 检测分级与 **`virbius-engine`** 协同工作。

### 分层总览

| 层级 | 路径属性 | 典型延迟 | 是否阻塞用户请求 |
|------|----------|----------|------------------|
| ① 端侧 SDK | 同步、本地 | 毫秒级 | 是（仅本地逻辑） |
| ② 网关（L1–L2、SSE） | 同步、必经流量 | 十～数百 ms | 是 |
| ③ 云主链路（L3、租户、策略） | 同步/近线 | 百 ms 级 | 可选（常按需调用） |
| ④ 异步链路 | 异步、旁路 | 秒～小时 | 否 |
| ⑤ ML 推理 | 被②③调用 + 离线训练 | 不等 | 在线推理阻塞对应层；训练不阻塞 |

**请求主路径**：用户输入 → 端侧 SDK（L0）→ 网关（静态 Skill）→ 云 Scan（L1/L2）+ Policy（L3）→ 大模型 → 网关 SSE 输出审计 → 用户。全程旁路日志进入异步链路；模型类 Skill 仅在云侧执行，在异步链路离线迭代后下发。

### ① 端侧 SDK

**特点**：最靠近用户；运行 L0 词库/正则与精简 Skill 包，可离线拦截；轻量嵌入 App/Web/小程序；以行为采集与风险打标为主。

**作用**：

- 拦截明显违规内容（涉政、涉黄等），减少无效流量进入网关与大模型。
- 采集设备指纹、打字速率、请求频率等，识别脚本、撞库、异常遍历，触发验证码或抬高后续检测等级。
- 对异常流量实施协议校验与人机挑战，阻挡批量注入与爬虫。
- 将 `risk_tag`、规则命中情况等上下文带给网关与云侧，供统一策略使用。

**边界**：不做复杂语义越狱判断；可被绕过（直接调 API），不能作为唯一防线，需与网关配合。

### ② 网关（L1–L2、SSE）

**特点**：所有 LLM 流量的必经枢纽；分层检测——L1 为轻量分类/规则（低延迟），L2 为语义检测与指令重构（按需触发）；对输入与流式输出双向审计；承载实时 Skill。

**作用**：

- 作为实时防火墙：请求进 LLM 前拦截或重构，响应出 LLM 后合规审查。
- 提供 OpenAI 兼容代理，统一鉴权、限流、日志与 API 管控。
- 执行 DLP（手机号、身份证、密钥等脱敏或阻断）。
- 根据 **virbius-engine** 返回的 decision 执行放行、拦截、脱敏或改写后转发。

**L1 与 L2 分工**：

| | L1 | L2 |
|--|----|----|
| 手段 | 小模型、正则、签名规则 | 语义模型、指令重构 |
| 延迟 | 目标 &lt; 50ms | 更重，仅对高风险请求触发 |
| 场景 | 已知越狱模板、明显注入 | 变体攻击、多义 prompt |

**SSE 流式审计**：对大模型流式返回逐 chunk 检测；需定义缓冲窗口与是否 hold-then-release（高合规场景建议先审后发）；违规时截断或撤回已下发内容（依策略配置）。

### ③ 云主链路（L3、租户、策略）

**特点**：在线决策中心；汇聚端侧标签、网关信号、用户画像与历史风控；支持 tenant + scene + role 三维策略；由 **virbius-engine**（Groovy + Prompt）统一合并各层「风险分 + 动作」，避免重复拦截。

**作用**：

- 综合风险评分（含会话级 session risk score、多轮越狱与话题漂移）。
- 动态策略与 Fail 策略（fail-open / fail-close）按租户配置。
- 处置渲染：放行、拦截、告警、脱敏、**安全代答**（不调 LLM，返回预设合规回复）。
- Skill 版本、灰度比例、回滚点管理；灰区样本进入人机协同队列。
- 承载近线 Skill（允许较 L1/L2 更复杂的逻辑）。

**与网关的关系**：网关负责快路径执行；云主链路负责准路径决策与配置。非每条请求都需 RPC 云侧，可按风险标签按需调用以控制延迟。

### ④ 异步链路

**特点**：旁路、不阻塞主请求；全量或采样采集日志与样本；长周期运行（评测、红队、报表、发布）。

**作用**：

- 构建安全语料库，挖掘长尾攻击与新型对抗样本。
- 驱动 Skill 生命周期：草稿 → 沙箱评测 → 灰度 → 全量，绑定数据集版本。
- Agent 生成候选 Skill 与测试用例（禁止直推生产，需回归通过）。
- 输出攻击类型图谱与误报/漏报报表，支撑运营与红队演练。
- 将优化后的规则、词库、模型参数下发至网关与端侧，形成「检测-分析-优化-部署」闭环。

**与云主链路的区别**：云主链路面向当前请求的毫秒～百毫秒级决策；异步链路面向未来规则与模型的持续进化，用户通常无直接感知。

### ⑤ ML 推理

**特点**：不为独立业务层，而为①～③提供检测能力，并在④中被训练与更新；常独立部署（ONNX Runtime、vLLM、Python Sidecar），与业务进程解耦；模型与评测集版本化发布。

**作用**：

| 类型 | 说明 | 调用方 |
|------|------|--------|
| 轻量分类模型 | 注入/越狱分类、话题违规 | 云 L1（管侧 RPC） |
| 语义/重构模型 | 指令重构、复杂意图判断 | 云 L2 |
| 输出审核模型 | 流式 chunk 违规检测 | 云（管侧 SSE 管道调用） |
| Embedding / 相似检索 | 可解释性、相似攻击样本 | 云主链路 / 异步链路 |
| 训练与微调 | 提升召回、降低误报 | 仅异步链路触发 |
| 第三方 Scanner | 如 LlamaFirewall | 网关 Sidecar |

**与 Skill 的关系**：Skill 为人可读规则，热更新快；ML 处理变体与语义模糊，更新慢但泛化强。生产环境通常采用「规则 + 模型」串联：规则先筛，模型兜底。

### 分层记忆

| 层 | 一句话 |
|----|--------|
| 端侧 SDK | 本地快挡 + 行为画像，减负增效 |
| 网关 | 流量必经的实时盾，管输入与流式输出 |
| 云主链路 | 租户与策略大脑，统一拍板与代答 |
| 异步链路 | 旁路进化，评测、灰度、Agent 产规则 |
| ML 推理 | 为各层提供检测能力，在线推断、离线变强 |

## License

[MIT License](LICENSE) — Copyright (c) 2026 i1see1you.
