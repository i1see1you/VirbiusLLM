import { createRouter, createWebHashHistory } from 'vue-router';
import type { RouteRecordRaw } from 'vue-router';
import { ElMessageBox } from 'element-plus';
import { useUiStore } from '@/stores/ui';
import { t } from '@/locales';

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/monitor' },
  { path: '/monitor', component: () => import('@/views/MonitorView.vue'), meta: { titleKey: 'nav.monitor' } },
  { path: '/audit', component: () => import('@/views/AuditCenterView.vue'), meta: { titleKey: 'nav.audit-center' } },
  {
    path: '/rules',
    component: () => import('@/views/RulesView.vue'),
    meta: { titleKey: 'nav.rules' }
  },
  { path: '/rollout', component: () => import('@/views/RolloutView.vue'), meta: { titleKey: 'nav.rollout' } },
  { path: '/qa', component: () => import('@/views/QaView.vue'), meta: { titleKey: 'nav.qa' } },
  { path: '/lists', component: () => import('@/views/ListsView.vue'), meta: { titleKey: 'nav.lists' } },
  { path: '/cumulatives', component: () => import('@/views/CumulativesView.vue'), meta: { titleKey: 'nav.cumulatives' } },
  { path: '/bindings', component: () => import('@/views/BindingsView.vue'), meta: { titleKey: 'nav.bindings' } },
  { path: '/ext-bindings', component: () => import('@/views/ExtBindingsView.vue'), meta: { titleKey: 'nav.ext-bindings' } },
  { path: '/scenes', component: () => import('@/views/ScenesView.vue'), meta: { titleKey: 'nav.scene-registry' } },
  { path: '/gateway-routes', component: () => import('@/views/GatewayRoutesView.vue'), meta: { titleKey: 'nav.gateway-routes' } },
  { path: '/tenants', component: () => import('@/views/TenantsView.vue'), meta: { titleKey: 'nav.tenants' } }
];

const router = createRouter({
  history: createWebHashHistory(),
  routes
});

/** 脏表单守卫：编辑中切换路由先确认（§5-2） */
router.beforeEach(async () => {
  const ui = useUiStore();
  if (!ui.hasDirty) return true;
  try {
    await ElMessageBox.confirm(t('shell.confirm-leave'), t('shell.confirm-title'), {
      type: 'warning',
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel')
    });
    ui.markDirty(false);
    return true;
  } catch {
    return false;
  }
});

/** 页面级未捕获错误 → ui store 错误页（§5-10） */
router.onError((err, to) => {
  const isChunkLoad = /Failed to fetch dynamically imported module|Importing a module script failed|error loading dynamically imported module/i.test(
    String(err?.message || err)
  );
  if (isChunkLoad) {
    // 资源已更新：刷新到目标路由
    window.location.assign(to.fullPath);
  }
});

export default router;
