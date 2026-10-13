import { test, expect, type Page } from '@playwright/test';

const ROUTES: { hash: string; testId: string }[] = [
  { hash: '#/monitor', testId: 'monitor-title' },
  { hash: '#/audit', testId: 'audit-table' },
  { hash: '#/rules', testId: 'rules-table' },
  { hash: '#/rollout', testId: 'rollout-tabs' },
  { hash: '#/qa', testId: 'qa-table' },
  { hash: '#/lists', testId: 'lists-table' },
  { hash: '#/cumulatives', testId: 'cum-table' },
  { hash: '#/bindings', testId: 'bind-table' },
  { hash: '#/ext-bindings', testId: 'ext-table' },
  { hash: '#/scenes', testId: 'sr-table' },
  { hash: '#/gateway-routes', testId: 'gw-table' },
  { hash: '#/tenants', testId: 'tenants-table' }
];

function collectConsoleErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('console', (msg) => {
    if (msg.type() === 'error') errors.push(msg.text());
  });
  page.on('pageerror', (err) => errors.push(String(err)));
  return errors;
}

const ok = (route: import('@playwright/test').Route, data: unknown): Promise<void> =>
  route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, data }) });

/** 无后端时的最小 mock */
async function mockApi(page: Page): Promise<void> {
  await page.route('**/api/v1/admin/tenants', (r) => ok(r, []));
  await page.route('**/api/v1/admin/tenants/*/qa', (r) => ok(r, []));
  await page.route('**/api/v1/admin/tenants/*/lists', (r) => ok(r, { lists: [] }));
  await page.route('**/api/v1/admin/tenants/*/cumulatives', (r) => ok(r, []));
  await page.route('**/api/v1/admin/tenants/*/api-credentials', (r) => ok(r, []));
  await page.route('**/api/v1/admin/platform/api-credentials', (r) => ok(r, []));
  await page.route('**/api/v1/admin/tenants/*/bundles/*/versions/*/metadata', (r) =>
    ok(r, {
      context_vars: [],
      extended_var_list: [],
      scene_entries: [],
      scene_registry: { fail_on_unknown_app: false, fail_on_unresolved_scene: false },
      gateway: { evaluate: true, fail_mode: 'open', cloud_scan: {} },
      gateway_routes: []
    })
  );
  await page.route('**/api/v1/admin/tenants/*/rules?layer=*', (r) => ok(r, []));
  await page.route('**/api/v1/admin/tenants/*/rules/recipes?layer=*', (r) => ok(r, { recipes: [] }));
  await page.route('**/api/v1/admin/tenants/*/rollout-policy', (r) => ok(r, { canary_ladder: [5, 20, 50, 100] }));
  await page.route('**/api/v1/admin/tenants/*/dashboard/overview', (r) => ok(r, { deploy_status: {} }));
  await page.route('**/api/v1/admin/tenants/*/deploy-rollout/active', (r) => ok(r, {}));
  await page.route('**/api/v1/admin/tenants/*/deploy-rollout/list', (r) => ok(r, []));
  await page.route('**/api/v1/admin/tenants/*/deploy-rollout/metrics*', (r) => ok(r, { totals: {}, series: [] }));
  await page.route('**/api/v1/admin/tenants/*/monitor/rule-ranking*', (r) => ok(r, { ranking: [] }));
  await page.route('**/api/v1/admin/tenants/*/monitor/scene-traffic*', (r) => ok(r, { scenes: [] }));
  await page.route('**/api/v1/admin/tenants/*/monitor/degradation*', (r) => ok(r, { series: [] }));
  await page.route('**/api/v1/admin/tenants/*/monitor/event-timeline*', (r) => ok(r, { events: [] }));
  await page.route('**/api/v1/admin/tenants/*/audit/ingest-status', (r) => ok(r, {}));
  await page.route('**/api/v1/admin/tenants/*/audit/recent*', (r) => ok(r, { db_count: 0, db_events: [] }));
}

test.beforeEach(async ({ page }) => {
  await mockApi(page);
});

test('all 12 routes render without console errors', async ({ page }) => {
  const errors = collectConsoleErrors(page);
  for (const r of ROUTES) {
    await page.goto(`/ui/${r.hash}`);
    await expect(page.locator(`[data-testid="${r.testId}"]`)).toBeVisible();
  }
  expect(errors, errors.join('\n')).toEqual([]);
});

test('default route redirects to /monitor', async ({ page }) => {
  await page.goto('/ui/#/');
  await expect(page).toHaveURL(/#\/monitor/);
  await expect(page.locator('[data-testid="monitor-title"]')).toBeVisible();
});

test('tenant switch forces remount and persists', async ({ page }) => {
  await page.route('**/api/v1/admin/tenants', (r) =>
    ok(r, [
      { tenant_id: 'default', name: 'Default' },
      { tenant_id: 'acme', name: 'Acme' }
    ])
  );
  await page.goto('/ui/#/tenants');
  await expect(page.locator('[data-testid="tenant-select"]')).toBeVisible();
  await page.getByTestId('tenant-select').click();
  await page.getByRole('option', { name: /acme/ }).click();
  await expect(page.locator('[data-testid="tenants-table"]')).toBeVisible();
  const stored = await page.evaluate(() => localStorage.getItem('virbius.ops.tenant'));
  expect(stored).toBe('acme');
});

test('language switch toggles nav labels', async ({ page }) => {
  await page.goto('/ui/#/audit');
  await expect(page.locator('[data-testid="audit-title"]')).toHaveText('审计中心');
  await page.getByTestId('lang-btn').click();
  await expect(page.locator('[data-testid="audit-title"]')).toHaveText('Audit Center');
  const stored = await page.evaluate(() => localStorage.getItem('virbius.i18n.locale'));
  expect(stored).toBe('en');
});

test('QA panel renders list and create form', async ({ page }) => {
  await page.goto('/ui/#/qa');
  await expect(page.locator('[data-testid="qa-table"]')).toBeVisible();
  await expect(page.getByTestId('qa-create')).toBeVisible();
  await expect(page.getByTestId('qa-title')).toBeVisible();
});

test('Lists and Tenants panels render', async ({ page }) => {
  await page.goto('/ui/#/lists');
  await expect(page.getByTestId('lists-title')).toBeVisible();
  await expect(page.getByTestId('lists-create')).toBeVisible();
  await page.goto('/ui/#/tenants');
  await expect(page.getByTestId('tenants-title')).toBeVisible();
  await expect(page.getByTestId('tenants-create')).toBeVisible();
});

test('设置入口：凭证仅显示状态，不显示明文', async ({ page }) => {
  await page.goto('/ui/#/lists');
  await page.getByTestId('settings-btn').click();
  const drawer = page.locator('.el-drawer:visible');
  await expect(drawer.getByTestId('cred-status')).toContainText('未配置');
  await drawer.getByTestId('cred-input').fill('vrb_tk_secret_value');
  await drawer.getByTestId('cred-save').click();
  await page.getByTestId('settings-btn').click();
  const drawer2 = page.locator('.el-drawer:visible');
  await expect(drawer2.getByTestId('cred-status')).toContainText('已配置');
  // 明文不得出现
  await expect(drawer2).not.toContainText('vrb_tk_secret_value');
});
