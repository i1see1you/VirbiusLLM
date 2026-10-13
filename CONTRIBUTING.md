# Contributing

Thank you for your interest in VirbiusLLM. This document outlines the process for contributing to the project.

## How to Contribute

1. Fork the repository.
2. Create a feature branch (`git checkout -b feat/my-change`).
3. Make your changes.
4. Ensure the project builds and tests pass:
   ```bash
   mvn clean package -DskipTests=false
   cd virbius-gateway-agent && cargo build
   ```
5. Commit with a clear message describing the change.
6. Open a Pull Request against the `main` branch.

## Pull Request Guidelines

- Keep PRs focused on a single concern.
- Include a description of the problem and solution.
- Reference any related issues.
- Rebase onto the latest `main` before submitting.

## Code Style

- **Java**: Follow existing conventions in the codebase (no extra comments, 4-space indent).
- **Rust**: `cargo fmt` before committing.
- **SQL**: Use PostgreSQL/MySQL/SQLite compatible JDBC syntax; avoid vendor-specific features.
- **Frontend** (ops console): Vue 3 + Element Plus + Pinia + vue-router + Vite in `virbius-control/frontend/`. Run `npm ci` once, `npm run build` (type-checks with `vue-tsc` then builds to `static/ui/`, gitignored) and `npx playwright test` for the smoke suite. `mvn package` builds the UI automatically via `frontend-maven-plugin`. For the real-backend end-to-end suite, run `bash scripts/test-ops-console-e2e.sh` (builds + starts a dev control, drives the SPA with Playwright).

## Reporting Issues

Use GitHub Issues for bug reports and feature requests. For security vulnerabilities, see [SECURITY.md](SECURITY.md).
