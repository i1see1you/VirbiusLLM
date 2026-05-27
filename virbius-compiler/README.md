# virbius-compiler

Registry → edge bin / gateway `rules.lua` / cloud 校验。

- **JDK 17**（继承父 `pom.xml`）
- CLI 契约：[MVP-OPENSPEC §5](../docs/openspec/MVP-OPENSPEC.md)

```bash
mvn -q -pl virbius-compiler package
java -jar virbius-compiler/target/virbius-compiler-0.1.0-SNAPSHOT.jar -i examples/poc-default-bundle.yaml -o /tmp/virbius-compile-out
```
