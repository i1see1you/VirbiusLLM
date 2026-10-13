import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  testIgnore: ['**/integration/**'],
  timeout: 30_000,
  fullyParallel: true,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL: 'http://localhost:4173',
    locale: 'zh-CN',
    trace: 'on-first-retry'
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run build:no-typecheck && npm run preview -- --port 4173 --strictPort --host localhost',
    url: 'http://localhost:4173/ui/',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000
  }
});
