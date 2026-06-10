# OpenClaw + OpenResty + Virbius + 本地 Ollama

OpenClaw 的 **Gateway（:18789）** 仍是 Agent 控制面（WebSocket）；**大模型 HTTP 流量**经 Virbius OpenResty（**:9080**）审计后转发到 **Ollama（:11434**）。

```text
OpenClaw Agent (:18789)
    → models.providers.ollama  HTTP
        → Virbius OpenResty (:9080/v1/chat/completions)
            → access.lua + gateway-agent (:9070) + engine (:8082)
            → Ollama (:11434)
```

## 一键配置

```bash
cd ~/Projects/VirbiusLLM

# 1. Virbius 管侧（control + engine + gateway-agent）
./scripts/run-local.sh

# 2. 编译 OpenResty nginx 配置
./scripts/compile-openresty-poc.sh

# 3. 启动 OpenResty（需已安装 openresty / nginx+lua）
export OPENRESTY_HOME="/Users/iseeyou/Documents/openresty-master/openresty-1.31.1.1"
./scripts/start-openresty-poc.sh
# 或：source scripts/openresty-env.local.sh（从 openresty-env.local.example 复制）

# 4. 改 OpenClaw 指向 Virbius（本地 Ollama 模型）
./scripts/configure-openclaw-virbius.sh

# 5. 重启 OpenClaw Gateway
launchctl kickstart -k gui/$(id -u)/ai.openclaw.gateway
```

## OpenClaw 改了什么

| 项 | 值 |
|----|-----|
| `models.providers.ollama.baseUrl` | `http://127.0.0.1:9080/v1` |
| `models.providers.ollama.api` | `openai-completions`（Virbius 只代理 `/v1/chat/completions`） |
| `models.providers.ollama.headers` | `X-App-Id: beta`（scene 解析，见 poc-default bundle） |
| 默认模型 | `ollama/qwen3.5:9b`（**推荐**：支持 Agent tools；`gemma3:*` 需 `supportsTools: false`） |

**说明**：Virbius OpenResty 审计并代理 **`/v1/chat/completions`** 与 **`/api/chat`**（OpenClaw embedded agent 常用后者）。Ollama Cloud 模型（`*:cloud`）不能走本地 Virbius 上游。

### 常见错误：`does not support tools`

OpenClaw Agent 默认会发 **tool schemas**。Ollama 上 **gemma3:4b / gemma3:1b 不支持 tools**，会返回 400：

```json
{"error":"registry.ollama.ai/library/gemma3:4b does not support tools"}
```

这不是 Virbius 拦截（`access.log` 里仍是 **400 来自 Ollama**，body ~71 字节）。

**处理**（`configure-openclaw-virbius.sh` 已写入 batch）：

| 方案 | 做法 |
|------|------|
| 推荐 | 主模型用 **`ollama/qwen3.5:9b`** 或 **`ollama/qwen3.5:27b`**（支持 tools） |
| 坚持用 Gemma | 模型条目设 **`compat.supportsTools: false`**（Agent 无 tool 能力） |
| 减 tool 面 | 在 agent 条目设 `experimental.localModelLean: true`（见 OpenClaw 文档） |

改完后 **重启 OpenClaw Gateway** 清 cooldown。

### 常见错误：`POST /api/chat` → 404 / 400 UNSUPPORTED_API

OpenClaw 或其它客户端把 **`http://127.0.0.1:9080`** 当成 Ollama 地址。Virbius 已代理 **`/v1/chat/completions`** 与 **`/api/chat`**（均走 access.lua 审计）。

**正确配置**（已通过 `configure-openclaw-virbius.sh` 写入）：

| 键 | 值 |
|----|-----|
| `models.providers.ollama.baseUrl` | `http://127.0.0.1:9080/v1` |
| `models.providers.ollama.api` | `openai-completions` |

修改后 **重启 OpenClaw Gateway**：

```bash
launchctl kickstart -k gui/$(id -u)/ai.openclaw.gateway
```

自检：

```bash
openclaw config get models.providers.ollama.baseUrl
openclaw config get models.providers.ollama.api
tail -f .openresty-poc/logs/access.log   # 应见 /v1/chat/completions，而非 /api/chat
```

若仍出现 `/api/chat`，说明仍有组件把 9080 当 Ollama 原生端点；聊天若仍能回复，往往是 **直连 :11434**，未走 Virbius 审计。

## 环境变量

| 变量 | 默认 | 含义 |
|------|------|------|
| `VIRBIUS_OPENRESTY_URL` | `http://127.0.0.1:9080/v1` | OpenClaw → Virbius |
| `VIRBIUS_APP_ID` | `beta` | `X-App-Id` / scene |
| `OPENCLAW_PRIMARY_MODEL` | `ollama/qwen3.5:9b` | 默认 Agent 模型 |
| `OPENRESTY_NGINX_BIN` | 自动探测 | nginx/openresty 二进制 |

## 验证

```bash
# Virbius 网关直连
curl -sS -X POST http://127.0.0.1:9080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -H 'X-App-Id: beta' \
  -d '{"model":"gemma3:4b","messages":[{"role":"user","content":"hello"}]}'

# OpenClaw 配置
openclaw config get models.providers.ollama.baseUrl
openclaw config get agents.defaults.model
```

## OpenResty 未在 PATH

```bash
brew tap openresty/brew
brew install openresty/brew/openresty
# 或指定二进制：
OPENRESTY_NGINX_BIN=/usr/local/openresty/nginx/sbin/nginx ./scripts/start-openresty-poc.sh
```

## 相关文档

- [virbius-gateway/README.md](../../virbius-gateway/README.md)、[用户使用手册 §4.7](../../docs/user-guide.md)
- [examples/gateway/openresty-poc/0.1.0/README.md](../../examples/gateway/openresty-poc/0.1.0/README.md)
