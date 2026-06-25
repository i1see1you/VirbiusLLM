# virbius-engine

云侧规则引擎：Evaluate + RuleCache（MVP PoC）。

- **JDK 17**（继承父 `pom.xml`）
- 规则来自 control 发布后的 RuleCache

| 接口 | 端口 |
|------|------|
| HTTP Evaluate | `8082` (`POST /v1/evaluate`) |
| HTTP admin | `8082` (`/admin/policy-version`) |
| gRPC Evaluate | `50051`（MVP 走 HTTP；gRPC 规划中） |

## Prompt 规则 + 1B 模型

所有 `runtime=prompt` 规则聚合成【安全规则矩阵】，**单次**调用 Ollama/vLLM（OpenAI 兼容 API）。详见 [DESIGN.md §8](../docs/DESIGN.md)。

```bash
export VIRBIUS_PROMPT_LLM_BASE_URL=http://127.0.0.1:11434
export VIRBIUS_PROMPT_LLM_MODEL=virbius-prompt-1b
mvn -q -pl virbius-engine spring-boot:run
```

- Evaluate HTTP：`POST /v1/evaluate`（见 [用户使用手册 §4](../docs/user-guide.en.md)）
- Admin：`GET /admin/policy-version`（见 [DESIGN.md](../docs/DESIGN.md)）

```bash
mvn -q -pl virbius-engine spring-boot:run
```
