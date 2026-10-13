import { defineConfig, devices } from '@playwright/test';

/**
 * 壳层全流程集成测试（真实 control 后端）。
 * 由 scripts/test-ops-console-e2e.sh 构建并启动 control 后调用：
 *   OPS_BASE_URL=http://127.0.0.1:<port> npx playwright test --config playwright.integration.config.ts
 * 前缀 /ui 由 control 的 OpsUiController 重定向到 SPA。
 */
export default defineConfig({
  testDir: './e2e/integration',
  timeout: 60_000,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL: process.env.OPS_BASE_URL || 'http://127.0.0.1:18091',
    locale: 'zh-CN',
    trace: 'retain-on-failure'
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }]
});
