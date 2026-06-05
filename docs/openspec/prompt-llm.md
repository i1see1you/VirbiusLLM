# Prompt 规则 + 1B 审计模型（Ollama / vLLM）

| 项 | 约定 |
|----|------|
| runtime | `cloud` + `prompt` |
| 推理位置 | **virbius-engine** `PromptRunner` |
| 调用方式 | 所有 prompt 规则 **聚合成一条** ChatML 提示，**单次** LLM 推理 |
| 后端 | Ollama / vLLM 的 **OpenAI 兼容** `POST /v1/chat/completions` |

## 1. 规则如何进入矩阵

运营台创建 `layer=cloud`、`runtime=prompt` 的规则：

- **`rule_id`** → 矩阵中的 ID（如 `Rule_201`），模型 JSON 的 `triggered_id` 须与此一致
- **`body`** → 该条规则的 natural-language 描述（矩阵中 `- [Rule_201]: ...` 后半句）
- **`scope.bind_scope` / `bind_ref`** → 与 groovy/lua 相同；**仅 bind 命中的 prompt 规则**进入本次矩阵（仍单次 1B）
- **`intent_action` / `risk_score` / `reason_code`** → 命中后 Signal 元数据（LLM 只负责判是否命中哪条）

示例三条规则即对应你 SFT 模板里的【安全规则矩阵】。

## 2. 聚合后的提示（ChatML）

Engine 按 **bind 命中**的 prompt 规则聚合（仍 **单次 1B**），生成类似：

```text
<|im_start|>system
你是一个严格的大模型输入审计员...
【安全规则矩阵】:
- [Rule_201]: 检查用户是否在诱导...
- [Rule_202]: 严禁打听 2026 未公开财报...
...
【输出格式要求】: JSON { hit_rule, triggered_id, reason }

<|im_start|>user
【User Prompt】: "<实际用户输入>"

<|im_start|>assistant
```

与 SFT 格式不一致时，可配置 `virbius.prompt-llm.im-start` / `im-end`。

## 3. 配置（virbius-engine）

存在 `runtime=prompt` 规则时，engine **始终**走 1B 矩阵推理（无开关）。仅需配置模型端点：

```bash
export VIRBIUS_PROMPT_LLM_BASE_URL=http://127.0.0.1:11434   # Ollama OpenAI 兼容根地址
export VIRBIUS_PROMPT_LLM_MODEL=virbius-prompt-1b           # ollama create 后的模型名
```

`application.yml` 默认值同上；LLM 超时/失败时由 `fail-open: true` 控制是否产 Signal。

**Ollama**

```bash
ollama create virbius-prompt-1b -f Modelfile   # 指向你的 SFT 权重
export VIRBIUS_PROMPT_LLM_MODEL=virbius-prompt-1b
```

**vLLM**

```bash
vllm serve /path/to/1b --port 8000
export VIRBIUS_PROMPT_LLM_BASE_URL=http://127.0.0.1:8000
```

## 4. 请求链路

```text
用户 prompt（OpenAI JSON 或纯文本）
  → gateway virbius-guard：extract_prompt_content（从 messages[].content 抽取 user 文本）
  → gateway-agent POST /v1/evaluate（content = 抽取结果）
  → virbius-engine PromptRunner：聚合 prompt 规则 → 调 1B 模型 → 解析 JSON
  → Signal(rule_id=triggered_id) → PolicyMerger（ActionMerge 代码合并，无 cloud_groovy_l3）→ effective_action
```

### 4.1 网关 prompt 抽取（virbius-guard）

对 `/v1/chat/completions` 等 POST body：

| 输入 | 抽取结果 |
|------|----------|
| `{"messages":[{"role":"user","content":"..."}]}` | 所有 `role=user` 的 `content` 用换行拼接 |
| multimodal `content: [{type:text,text:...}]` | 提取 `text` 字段 |
| `{"input":"..."}` / `{"prompt":"..."}` | 对应字段 |
| 非 JSON 或无法解析 | 原样 body 字符串 |

名单 / Lua 脚本同样对抽取后的文本做 `listMatch`。

每次 Evaluate **只打 1 次** 1B 模型，不是每条 prompt 规则各打一次。

## 5. 模型输出契约

```json
{
  "hit_rule": true,
  "triggered_id": "Rule_201",
  "reason": "涉及 com.baidu 架构敏感逻辑"
}
```

- `hit_rule=false` → 不产 cloud prompt Signal  
- `triggered_id` 须与某条规则的 `rule_id` 一致；无法匹配时回退到第一条 prompt 规则并打 WARN 日志  

## 6. 关联

- [script-rules.md](./script-rules.md) — runtime 分层  
- [rule-rollout.md](./rule-rollout.md) — dry_run / canary / full  
- [evaluate.proto](./evaluate.proto) — `content` 字段即待审计 prompt  
