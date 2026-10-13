import { test, expect, type Page, type Route } from '@playwright/test';

/**
 * 策略上线（RolloutView）UI 测试（mock API，无后端）。
 * 覆盖：Tab 深链、部署状态卡与 pending 下钻、规则流程条/阶梯/KPI、立即操作与 force 校验、
 * 审计样本 trace 下钻、机器灰度 prepare 版本确认弹窗。
 */

const ok = (route: Route, data: unknown): Promise<void> =>
  route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, data }) });

const RULES: Record<string, unknown[]> = {
  cloud: [
    { rule_id: 'Rule_draft', layer: 'cloud', runtime: 'prompt', rollout_state: 'draft', canary_percent: null, current_revision: 1, risk_score: 100, intent_action: 'deny', enforce_mode: 'dry_run', reason_code: 'DRAFT', is_async: false },
    { rule_id: 'Rule_dry', layer: 'cloud', runtime: 'groovy', rollout_state: 'dry_run', canary_percent: null, current_revision: 2, risk_score: 90, intent_action: 'deny', enforce_mode: 'dry_run', reason_code: 'DRY', is_async: false }
  ],
  gateway: [],
  edge: []
};

async function mockApi(page: Page): Promise<void> {
  await page.route('**/api/v1/admin/tenants', (r) => ok(r, [{ tenant_id: 'default', name: 'Default' }]));
  await page.route('**/api/v1/admin/tenants/*/rollout-policy', (r) => ok(r, { canary_ladder: [5, 20, 50, 100] }));
  await page.route('**/api/v1/admin/tenants/*/dashboard/overview', (r) =>
    ok(r, {
      deploy_status: {
        cloud: { has_unpublished: true, deployed_at: null, pending_rules: [{ rule_id: 'Rule_draft', rollout_state: 'draft' }] },
        gateway: { has_unpublished: false, deployed_at: '2026-10-13T00:00:00Z', pending_rules: [] }
      }
    })
  );
  await page.route('**/api/v1/admin/tenants/*/deploy-rollout/active', (r) => ok(r, {}));
  await page.route('**/api/v1/admin/tenants/*/deploy-rollout/list', (r) => ok(r, []));
  await page.route('**/api/v1/admin/tenants/*/deploy-rollout/metrics*', (r) => ok(r, { totals: {}, series: [], series_1m: [] }));
  await page.route('**/api/v1/admin/tenants/*/deploy-rollout/next-version*', (r) => ok(r, { version: '0.1.1' }));
  await page.route('**/api/v1/admin/tenants/*/deploy-rollout/diff-rules*', (r) =>
    ok(r, { base_version: '0.1.0', summary: { added: 1, removed: 0, modified: 1 }, layers: { cloud: [{ rule_id: 'Rule_draft', change: 'added' }, { rule_id: 'Rule_dry', change: 'modified' }] } })
  );
  // rule list per layer
  await page.route(/\/api\/v1\/admin\/tenants\/[^/]+\/rules\?layer=(\w+)/, (r) => {
    const m = r.request().url().match(/layer=(\w+)/);
    return ok(r, RULES[m?.[1] || 'cloud'] || []);
  });
  // single rule + sub-resources
  await page.route(/\/api\/v1\/admin\/tenants\/[^/]+\/rules\/Rule_draft$/, (r) =>
    ok(r, { rule_id: 'Rule_draft', layer: 'cloud', runtime: 'prompt', rollout_state: 'draft', canary_percent: null, current_revision: 1, risk_score: 100, intent_action: 'deny', reason_code: 'DRAFT', body: 'desc' })
  );
  await page.route(/\/api\/v1\/admin\/tenants\/[^/]+\/rules\/Rule_dry$/, (r) =>
    ok(r, { rule_id: 'Rule_dry', layer: 'cloud', runtime: 'groovy', rollout_state: 'dry_run', canary_percent: null, current_revision: 2, risk_score: 90, intent_action: 'deny', reason_code: 'DRY', body: 'def decide(ctx){return true}' })
  );
  await page.route('**/rules/Rule_*/metrics*', (r) =>
    ok(r, { totals: { review: 12, block: 3, challenge: 1, total_requests: 100, hit_rate: 0.13, review_rate: 0.12, block_rate: 0.03 }, series: [], series_1m: [] })
  );
  await page.route('**/rules/Rule_*/rollout/timeline', (r) =>
    ok(r, [{ effective_at: '2026-10-13T00:00:00Z', rollout_state: 'dry_run', canary_percent: null, trigger: 'publish', operator: 'dev' }])
  );
  await page.route('**/rules/Rule_*/audit-samples*', (r) =>
    ok(r, [{ intercepted_at: '2026-10-13T00:00:00Z', trace_id: 'trace-1', effective_action: 'review', reason_code: 'DRY', max_risk_score: 60, rollout_state: 'dry_run' }])
  );
  await page.route('**/audit/trace/*', (r) => ok(r, { db_events: [{ intercepted_at: '2026-10-13T00:00:00Z', layer: 'cloud', effective_action: 'review', rule_id: 'Rule_dry', rule_revision: 2, rollout_state: 'dry_run', max_risk_score: 60 }] }));
}

async function selectRule(page: Page, name: string): Promise<void> {
  await page.locator('.rollout-view .el-select').first().click();
  await page.getByRole('option', { name: new RegExp(name) }).click();
}

test.beforeEach(async ({ page }) => {
  await mockApi(page);
});

test('tab 深链：?tab=canary 打开机器灰度 tab', async ({ page }) => {
  await page.goto('/ui/#/rollout?tab=canary');
  await expect(page.getByTestId('rollout-tabs')).toBeVisible();
  await expect(page.getByRole('tab', { name: '机器灰度' })).toHaveAttribute('aria-selected', 'true');
});

test('部署状态卡：pending 下钻 modal', async ({ page }) => {
  await page.goto('/ui/#/rollout');
  await expect(page.getByText('cloud ·', { exact: false })).toBeVisible();
  // 点击可点值（pending-link 的 kpi-value）→ 打开清单
  await page.locator('.kpi-value.pending-link').first().click();
  const dialog = page.locator('.el-dialog:visible');
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText('Rule_draft');
});

test('选规则：流程条/阶梯/KPI 渲染，draft 显示上线按钮', async ({ page }) => {
  await page.goto('/ui/#/rollout');
  await selectRule(page, 'Rule_draft');
  await expect(page.getByTestId('rollout-flow')).toBeVisible();
  await expect(page.getByTestId('rollout-flow')).toContainText('draft');
  await expect(page.getByRole('button', { name: /上线/ })).toBeVisible();
  await expect(page.getByText('复核(24h)')).toBeVisible();
});

test('dry_run 规则：显示升级与 force，force 未填说明报错', async ({ page }) => {
  await page.goto('/ui/#/rollout');
  await selectRule(page, 'Rule_dry');
  await expect(page.getByTestId('rollout-apply')).toBeVisible();
  // 勾选 force → 出现说明输入（点击 label，el-checkbox 原生 input 不可见）
  await page.locator('.el-checkbox', { hasText: 'force 绕过' }).click();
  await page.getByTestId('rollout-apply').click();
  await expect(page.locator('.ro-field-error').first()).toContainText('force');
});

test('审计样本：点击 trace_id 打开 trace 详情', async ({ page }) => {
  await page.goto('/ui/#/rollout');
  await selectRule(page, 'Rule_dry');
  await page.getByRole('button', { name: 'trace-1' }).click();
  const dialog = page.locator('.el-dialog:visible');
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText('Rule_dry');
});

test('机器灰度：prepare 打开版本确认弹窗（含 diff）', async ({ page }) => {
  await page.goto('/ui/#/rollout');
  await page.getByRole('tab', { name: '机器灰度' }).click();
  await page.getByTestId('dr-prepare-all').click();
  const dialog = page.locator('.el-dialog:visible');
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText('确认版本号');
  await expect(dialog).toContainText('Rule_draft');
  await expect(dialog.locator('input').first()).toHaveValue('0.1.1');
});
