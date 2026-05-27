# virbius-engine

云侧规则引擎：Evaluate + RuleCache（MVP PoC）。

- **JDK 17**（继承父 `pom.xml`）
- 规则来自 control 发布后的 RuleCache；关键字仅读 `body.keywords`（见 [POC-SEED-API.md](../docs/POC-SEED-API.md)）

| 接口 | 端口 |
|------|------|
| HTTP admin | `8082` (`/admin/policy-version`) |
| gRPC Evaluate | `50051`（待接入） |

- [evaluate.proto](../docs/openspec/evaluate.proto)
- [engine-admin.openapi.yaml](../docs/openspec/engine-admin.openapi.yaml)

```bash
mvn -q -pl virbius-engine spring-boot:run
```
