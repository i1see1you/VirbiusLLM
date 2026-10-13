<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessageBox } from 'element-plus';
import { useI18n } from 'vue-i18n';
import { useSessionStore } from '@/stores/session';
import { useRulesStore, LAYER_ORDER, type LayerKey } from '@/stores/rules';
import TenantSelect from '@/components/TenantSelect.vue';
import FeedbackCenter from '@/components/FeedbackCenter.vue';
import SettingsDrawer from '@/components/SettingsDrawer.vue';

const route = useRoute();
const router = useRouter();
const { t } = useI18n();
const session = useSessionStore();
const rules = useRulesStore();
const settingsOpen = ref(false);

const sidebarWidth = computed(() =>
  session.sidebarCollapsed ? 'var(--ops-sidebar-collapsed)' : 'var(--ops-sidebar-width)'
);

/** routeKey = path:tenant:layer（对齐 Agent：切租户/层强制重挂载） */
const routeKey = computed(() => `${route.path}:${session.tenant}:${rules.currentLayer}`);

const icons: Record<string, string> = {
  monitor: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M3 16l5-7 4 3 5-8"/></svg>',
  audit: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="9" cy="9" r="5"/><path d="M13 13l4 4"/></svg>',
  rules: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M4 3h12v14H4z"/><path d="M7 7h6M7 11h6"/></svg>',
  rollout: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M10 3v12M6 11l4 4 4-4"/><path d="M3 17h14"/></svg>',
  qa: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M4 5h12v8H8l-4 3z"/></svg>',
  lists: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M3.2 5.2 4.6 6.6 7 4"/><path d="M9 5.2h8"/><path d="M3.2 10.2 4.6 11.6 7 9"/><path d="M9 10.2h8"/><path d="M3.2 15.2 4.6 16.6 7 14"/><path d="M9 15.2h6"/></svg>',
  cumulatives: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M3.5 7.2h13"/><path d="M5.5 16.2V12M9 16.2V9.2M12.5 16.2V4.6M16 16.2V10.5"/></svg>',
  bindings: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M6 7h5a3 3 0 0 1 0 6H8"/><path d="M14 13H9a3 3 0 0 1 0-6h3"/></svg>',
  ext: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M10 3v4M10 13v4M3 10h4M13 10h4"/><circle cx="10" cy="10" r="2.4"/></svg>',
  scenes: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="14" height="12" rx="1.5"/><path d="M3 8h14M7 8v8"/></svg>',
  gw: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M4 6h12M4 10h12M4 14h8"/><path d="M14.5 12.5 17 15l-2.5 2.5"/></svg>',
  tenants: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="7" cy="7" r="2.5"/><path d="M2 17c0-3 2-5 5-5s5 2 5 5"/><circle cx="14" cy="7" r="2.5"/><path d="M11 17c0-3 2-5 4-5s4 2 4 5"/></svg>',
  cloud: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M6.2 15.2h7.6a2.9 2.9 0 0 0 .3-5.78 4.1 4.1 0 0 0-7.9-1.15A3 3 0 0 0 6.2 15.2Z"/></svg>',
  gateway: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M4 16.5V7.2a6 6 0 0 1 12 0v9.3"/><path d="M4 16.5h12"/><path d="M10 16.5V9"/></svg>',
  edge: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="6" y="2.8" width="8" height="14.4" rx="1.6"/><path d="M9 15.2h2"/></svg>'
};

interface NavItem {
  path: string;
  labelKey: string;
  icon: string;
}
const groups: { titleKey: string; items: NavItem[] }[] = [
  {
    titleKey: 'nav.group-ops',
    items: [
      { path: '/monitor', labelKey: 'nav.monitor', icon: 'monitor' },
      { path: '/audit', labelKey: 'nav.audit-center', icon: 'audit' }
    ]
  },
  {
    titleKey: 'nav.group-policy',
    items: [
      { path: '/rollout', labelKey: 'nav.rollout', icon: 'rollout' },
      { path: '/qa', labelKey: 'nav.qa', icon: 'qa' }
    ]
  },
  {
    titleKey: 'nav.group-assets',
    items: [
      { path: '/lists', labelKey: 'nav.lists', icon: 'lists' },
      { path: '/cumulatives', labelKey: 'nav.cumulatives', icon: 'cumulatives' },
      { path: '/bindings', labelKey: 'nav.bindings', icon: 'bindings' },
      { path: '/ext-bindings', labelKey: 'nav.ext-bindings', icon: 'ext' },
      { path: '/scenes', labelKey: 'nav.scene-registry', icon: 'scenes' },
      { path: '/gateway-routes', labelKey: 'nav.gateway-routes', icon: 'gw' }
    ]
  },
  {
    titleKey: 'nav.group-platform',
    items: [{ path: '/tenants', labelKey: 'nav.tenants', icon: 'tenants' }]
  }
];
const layerLabels: Record<LayerKey, string> = { cloud: 'nav.cloud', gateway: 'nav.gateway', edge: 'nav.edge' };

const activeIndex = computed(() => (route.path === '/rules' ? `layer:${rules.currentLayer}` : route.path));

async function confirmDirty(): Promise<boolean> {
  if (!rules.ruleFormDirty) return true;
  try {
    await ElMessageBox.confirm(t('rules.confirm-unsaved'), t('shell.confirm-title'), {
      type: 'warning',
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel')
    });
    return true;
  } catch {
    return false;
  }
}

async function onSelect(index: string): Promise<void> {
  if (index.startsWith('layer:')) {
    const layer = index.slice('layer:'.length) as LayerKey;
    if (!(await confirmDirty())) return;
    rules.resetDirty();
    rules.setLayer(layer);
    if (route.path !== '/rules') router.push('/rules');
    return;
  }
  if (index === route.path) return;
  if (!(await confirmDirty())) return;
  rules.resetDirty();
  router.push(index);
}
</script>

<template>
  <el-container class="ops-layout">
    <el-aside :width="sidebarWidth" class="ops-sidebar">
      <div class="logo">
        <span v-if="!session.sidebarCollapsed">{{ $t('nav.title') }}</span>
        <span v-else>V</span>
      </div>
      <el-menu :default-active="activeIndex" :collapse="session.sidebarCollapsed" class="ops-nav" @select="onSelect">
        <el-menu-item-group :title="$t('nav.group-ops')">
          <el-menu-item v-for="item in groups[0].items" :key="item.path" :index="item.path">
            <span class="nav-icon" v-html="icons[item.icon]"></span>
            <span>{{ $t(item.labelKey) }}</span>
          </el-menu-item>
        </el-menu-item-group>

        <el-menu-item-group :title="$t('nav.group-policy')">
          <el-sub-menu index="rules-group">
            <template #title>
              <span class="nav-icon" v-html="icons.rules"></span>
              <span>{{ $t('nav.rules') }}</span>
            </template>
            <el-menu-item v-for="l in LAYER_ORDER" :key="l" :index="`layer:${l}`" :data-testid="`rules-layer-${l}`">
              <span class="nav-icon" v-html="icons[l]"></span>
              <span>{{ $t(layerLabels[l]) }}</span>
            </el-menu-item>
          </el-sub-menu>
          <el-menu-item v-for="item in groups[1].items" :key="item.path" :index="item.path">
            <span class="nav-icon" v-html="icons[item.icon]"></span>
            <span>{{ $t(item.labelKey) }}</span>
          </el-menu-item>
        </el-menu-item-group>

        <el-menu-item-group :title="$t('nav.group-assets')">
          <el-menu-item v-for="item in groups[2].items" :key="item.path" :index="item.path">
            <span class="nav-icon" v-html="icons[item.icon]"></span>
            <span>{{ $t(item.labelKey) }}</span>
          </el-menu-item>
        </el-menu-item-group>

        <el-menu-item-group :title="$t('nav.group-platform')">
          <el-menu-item v-for="item in groups[3].items" :key="item.path" :index="item.path">
            <span class="nav-icon" v-html="icons[item.icon]"></span>
            <span>{{ $t(item.labelKey) }}</span>
          </el-menu-item>
        </el-menu-item-group>
      </el-menu>
    </el-aside>
    <el-container direction="vertical" class="ops-main">
      <header class="ops-topbar">
        <el-tooltip :content="$t(session.sidebarCollapsed ? 'nav.expand' : 'nav.collapse')">
          <el-button text size="small" @click="session.toggleSidebar()">
            {{ session.sidebarCollapsed ? '»' : '«' }}
          </el-button>
        </el-tooltip>
        <TenantSelect />
        <div style="flex: 1"></div>
        <FeedbackCenter />
        <el-tooltip :content="$t('shell.settings')">
          <el-button text size="small" data-testid="settings-btn" @click="settingsOpen = true">
            <span class="settings-gear">⚙</span>
            <span class="settings-dot" :class="{ on: !!session.apiKey }"></span>
          </el-button>
        </el-tooltip>
        <el-button text size="small" data-testid="lang-btn" @click="session.setLocale(session.locale === 'zh' ? 'en' : 'zh')">
          {{ $t('topbar.lang') }}
        </el-button>
      </header>
      <main class="ops-content">
        <router-view :key="routeKey" />
      </main>
    </el-container>
  </el-container>
  <SettingsDrawer v-model="settingsOpen" />
</template>

<style scoped>
.nav-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  margin-right: 8px;
  flex-shrink: 0;
}
.nav-icon :deep(svg) {
  width: 18px;
  height: 18px;
}
.settings-gear {
  font-size: 15px;
}
.settings-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--el-text-color-placeholder);
  margin-left: 2px;
  vertical-align: middle;
}
.settings-dot.on {
  background: var(--el-color-success);
}
</style>
