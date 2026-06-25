# Release Process

## Versioning

VirbiusLLM follows [Semantic Versioning](https://semver.org/) (SemVer): `MAJOR.MINOR.PATCH`

- **MAJOR**: Incompatible API changes, breaking manifest format changes.
- **MINOR**: New features, new rule runtimes, backward-compatible API additions.
- **PATCH**: Bug fixes, performance improvements, documentation updates.

Pre-release versions use the `-SNAPSHOT` suffix during development (e.g., `0.1.0-SNAPSHOT`).

## Release checklist

1. Ensure `main` branch passes all tests:
   ```bash
   mvn clean package -DskipTests=false
   cd virbius-gateway-agent && cargo test && cd ..
   cd virbius-core && cargo test && cd ..
   ```

2. Run the local smoke test:
   ```bash
   bash scripts/run-local.sh
   bash scripts/smoke-test.sh
   ```

3. Update version in:
   - `pom.xml` (parent `<version>`)
   - `virbius-core/Cargo.toml` (`version`)
   - `virbius-gateway-agent/Cargo.toml` (`version`)

4. Update `CHANGELOG.md`:
   - Replace `[Unreleased]` with the new version
   - Add release date
   - Summarize changes since the last release

5. Commit and tag:
   ```bash
   git add pom.xml virbius-core/Cargo.toml virbius-gateway-agent/Cargo.toml CHANGELOG.md
   git commit -m "Release vX.Y.Z"
   git tag -a "vX.Y.Z" -m "Release vX.Y.Z"
   git push origin main --tags
   ```

6. Create a GitHub Release from the tag with changelog notes.

## Component versions

| Component | Language | Version file |
|-----------|----------|-------------|
| virbius-control | Java | `pom.xml` (Maven parent) |
| virbius-engine | Java | inherits parent `pom.xml` |
| virbius-compiler | Java | inherits parent `pom.xml` |
| virbius-policy | Java | inherits parent `pom.xml` |
| virbius-core | Rust | `virbius-core/Cargo.toml` |
| virbius-gateway-agent | Rust | `virbius-gateway-agent/Cargo.toml` |
| virbius-gateway | Lua | No version; ships with control |

## Release branches

- `main`: Active development. All PRs target `main`.
- Release tags (`vX.Y.Z`): Immutable release points.

## Backporting

Critical fixes for released versions should be:
1. Fixed on `main` first
2. Cherry-picked to a branch off the release tag
3. Released as a PATCH version
