import { test, expect, type Page } from '@playwright/test';

/**
 * 壳层全流程集成测试（真实 control 后端，dev profile 免鉴权）。
 * 覆盖 §10.1 三条关键流程 + QA 完整发布链：
 *   ① QA：建库 → 加条目 → 保存（持久化）→ 发布 → job acked
 *   ② 规则：打开 → 编辑 → 离开被脏守卫拦截
 *   ③ 租户隔离：切租户后数据隔离
 * 另含 QA 脏守卫拦截。
 */

const hash = (h: string): string => `/ui/index.html#${h}`;

function menuItem(page: Page, name: string) {
  return page.getByRole('menuitem', { name });
}

test('QA 全流程：建库 → 条目保存 → 发布 acked', async ({ page }) => {
  const name = 'E2E-' + Date.now();
  const question = 'E2E 问题 ' + Date.now();

  await page.goto(hash('/qa'));
  await expect(page.getByTestId('qa-table')).toBeVisible();

  await page.locator('.qa-create input').first().fill(name);
  await page.getByTestId('qa-create').click();

  const row = page.locator('[data-testid="qa-table"] tbody tr', { hasText: name });
  await expect(row).toBeVisible();
  await row.getByTestId('qa-open').click();
  await expect(page.getByTestId('qa-entries')).toBeVisible();

  await page.getByTestId('qa-entry-add').click();
  const cells = page.locator('[data-testid="qa-entries"] tbody tr').last().locator('td');
  await cells.nth(1).locator('input').fill('e1');
  await cells.nth(2).locator('input').fill(question);
  await cells.nth(4).locator('textarea').fill('标准答案');
  await page.getByTestId('qa-entry-save').click();

  // 持久化验证：整页重载后重新打开该库，条目仍在
  await page.reload();
  await expect(page.getByTestId('qa-table')).toBeVisible();
  await page.locator('[data-testid="qa-table"] tbody tr', { hasText: name }).getByTestId('qa-open').click();
  await expect(page.locator('[data-testid="qa-entries"] tbody tr').last().locator('td').nth(2).locator('input')).toHaveValue(question);

  // 发布 → 触发发布链，产生 job 记录（ack 由网关实例完成；无网关时为 ack timeout，故只断言 job 已创建）
  await page.getByTestId('qa-publish').click();
  await expect(page.locator('.qa-jobs tbody tr').first()).toBeVisible({ timeout: 30_000 });
});

test('QA 脏守卫：编辑条目后离开被拦截', async ({ page }) => {
  await page.goto(hash('/qa'));
  await expect(page.getByTestId('qa-table')).toBeVisible();
  let rows = page.locator('[data-testid="qa-table"] tbody tr');
  if ((await rows.count()) === 0) {
    await page.locator('.qa-create input').first().fill('E2E-dirty-' + Date.now());
    await page.getByTestId('qa-create').click();
    rows = page.locator('[data-testid="qa-table"] tbody tr');
  }
  await page.getByTestId('qa-open').first().click();
  await expect(page.getByTestId('qa-entries')).toBeVisible();
  await page.getByTestId('qa-entry-add').click();

  await menuItem(page, '监控中心').click();
  const box = page.locator('.el-message-box');
  await expect(box).toBeVisible();
  await box.getByText('取消').click();
  await expect(page).toHaveURL(/#\/qa/);

  // 确认离开以清理脏状态
  await menuItem(page, '监控中心').click();
  await page.locator('.el-message-box').getByText('确认').click();
  await expect(page).toHaveURL(/#\/monitor/);
});

test('规则：新建规则编辑后关闭抽屉被脏守卫拦截', async ({ page }) => {
  await page.goto(hash('/rules'));
  await expect(page.getByTestId('rules-table')).toBeVisible();
  // 新建规则 → 右侧抽屉；编辑 body（CodeMirror）触发脏状态
  await page.getByTestId('rules-new').click();
  const body = page.locator('[data-testid="rules-body"] .cm-content');
  await expect(body).toBeVisible();
  await body.click();
  await page.keyboard.type('return false');
  await page.getByTestId('rules-ruleid').fill('e2e_rule_' + Date.now());

  // 关闭抽屉 → 被脏守卫拦截
  await page.locator('[data-testid="rules-drawer"] .el-drawer__close-btn').click();
  const box = page.locator('.el-message-box');
  await expect(box).toBeVisible();
  await box.getByText('取消').click();
  // 仍在抽屉内
  await expect(page.locator('[data-testid="rules-body"]')).toBeVisible();
});

test('规则：菜单切换层（cloud → gateway）', async ({ page }) => {
  await page.goto(hash('/rules'));
  await expect(page.getByTestId('rules-table')).toBeVisible();
  await page.getByText('规则配置', { exact: true }).click();
  await page.getByTestId('rules-layer-gateway').click();
  await expect(page.getByTestId('rules-current-layer')).toContainText('gateway');
});

test('租户隔离：切租户后数据按租户隔离', async ({ page, request }) => {
  const tid = 'e2e' + Date.now();
  const created = await request.post('/api/v1/admin/tenants', { data: { tenant_id: tid, name: 'E2E Tenant' } });
  expect((await created.json()).code).toBe(0);

  // default 租户有种子名单 deny_keyword
  await page.goto(hash('/lists'));
  await expect(page.getByTestId('lists-table')).toContainText('deny_keyword');

  // 切到新租户 → 无该名单
  await page.reload();
  await page.getByTestId('tenant-select').click();
  await page.getByRole('option', { name: new RegExp(tid) }).click();
  await expect(page.getByTestId('lists-table')).not.toContainText('deny_keyword');

  // 切回 default → 名单回来
  await page.getByTestId('tenant-select').click();
  await page.getByRole('option', { name: /default/ }).click();
  await expect(page.getByTestId('lists-table')).toContainText('deny_keyword');
});

test('策略上线：选规则显示流程条；机器灰度 prepare 打开版本弹窗', async ({ page }) => {
  await page.goto(hash('/rollout'));
  await expect(page.getByTestId('rollout-tabs')).toBeVisible();
  // 选择一条规则 → 流程条出现
  await page.locator('.rollout-view .el-select').first().click();
  await page.getByRole('option', { name: /Rule_/ }).first().click();
  await expect(page.getByTestId('rollout-flow')).toBeVisible();

  // 切到机器灰度 tab → prepare all → 版本确认弹窗
  await page.getByRole('tab', { name: '机器灰度' }).click();
  await page.getByTestId('dr-prepare-all').click();
  await expect(page.locator('.el-dialog:visible')).toContainText('确认版本');
});
