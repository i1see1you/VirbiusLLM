# virbius-engine

云侧规则引擎：Evaluate + RuleCache（MVP PoC）。

- **JDK 17**（继承父 `pom.xml`）
- 规则来自 control 发布后的 RuleCache

| 接口 | 端口 |
|------|------|
| HTTP Evaluate | `8082` (`POST /v1/evaluate`) |
| HTTP admin | `8082` (`/admin/policy-version`) |
| gRPC Evaluate | `50051`（待接入） |

## Prompt 规则 + 1B 模型

所有 `runtime=prompt` 规则聚合成【安全规则矩阵】，**单次**调用 Ollama/vLLM（OpenAI 兼容 API）。详见 [prompt-llm.md](../docs/openspec/prompt-llm.md)。

```bash
export VIRBIUS_PROMPT_LLM_BASE_URL=http://127.0.0.1:11434
export VIRBIUS_PROMPT_LLM_MODEL=virbius-prompt-1b
mvn -q -pl virbius-engine spring-boot:run
```

- [evaluate.proto](../docs/openspec/evaluate.proto)
- [engine-admin.openapi.yaml](../docs/openspec/engine-admin.openapi.yaml)

```bash
mvn -q -pl virbius-engine spring-boot:run
```
