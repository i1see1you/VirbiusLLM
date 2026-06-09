# virbius-compiler

Registry → edge bin / gateway 产物 / cloud 校验。

- **JDK 17**（继承父 `pom.xml`）
- CLI 契约：[MVP-OPENSPEC §5](../docs/openspec/MVP-OPENSPEC.md)
- OpenResty：[openspec/openresty-gateway.md](../docs/openspec/openresty-gateway.md)

## 基本用法

```bash
mvn -q -pl virbius-compiler package

java -jar virbius-compiler/target/virbius-compiler-0.1.0-SNAPSHOT.jar \
  -i examples/poc-default-bundle.yaml \
  -o /tmp/virbius-compile-out
```

## Gateway 后端

| `--gateway` | 产物 |
|-------------|------|
| `apisix`（默认） | `gateway/apisix-*.json`、`scene-registry-*.json` |
| `openresty` | `gateway/openresty/*`（effective、locations、upstreams） |
| `all` | 两者 |

```bash
# APISIX
java -jar ... -i examples/poc-default-bundle.yaml -o staging/default/0.1.0 \
  --target=gateway --gateway=apisix

# OpenResty（本地与 control 对齐：lists/scene 指 data/gateway/）
./scripts/compile-openresty-poc.sh
```

### OpenResty 路径参数

| 参数 | 说明 |
|------|------|
| `--deploy-prefix` | effective 内 `lists_file` / `scene_registry_file` 的根目录 |
| `--deploy-layout` | `control-data` → `{prefix}/gateway/...`（virbius-control）；`staged` → `{prefix}/{version}/gateway/...` |

PoC 推荐（先 `./scripts/run-local.sh`）：

```bash
--deploy-prefix="$PWD/data" --deploy-layout=control-data
```
