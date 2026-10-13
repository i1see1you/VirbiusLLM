import { defineStore } from 'pinia';
import { ref } from 'vue';

const LS_TENANT = 'virbius.ops.tenant';
const LS_APIKEY = 'virbius.ops.apiKey';
const LS_LOCALE = 'virbius.i18n.locale';

function lsGet(key: string): string {
  try {
    return localStorage.getItem(key) || '';
  } catch {
    return '';
  }
}
function lsSet(key: string, value: string): void {
  try {
    if (value) localStorage.setItem(key, value);
    else localStorage.removeItem(key);
  } catch {
    /* ignore */
  }
}

/** 对齐旧 i18n.js detectLocale：localStorage > navigator.language */
function detectLocale(): 'zh' | 'en' {
  const saved = lsGet(LS_LOCALE);
  if (saved === 'zh' || saved === 'en') return saved;
  return (navigator.language || '').toLowerCase().startsWith('zh') ? 'zh' : 'en';
}

export const useSessionStore = defineStore('session', () => {
  const tenant = ref(lsGet(LS_TENANT) || 'default');
  const apiKey = ref(lsGet(LS_APIKEY));
  const locale = ref<'zh' | 'en'>(detectLocale());
  const bundleId = ref('poc-default');
  const bundleVer = ref('0.1.0');
  const sidebarCollapsed = ref(false);

  function setTenant(value: string): void {
    const v = value.trim() || 'default';
    if (v === tenant.value) return;
    tenant.value = v;
    lsSet(LS_TENANT, v);
  }
  function setApiKey(value: string): void {
    apiKey.value = value.trim();
    lsSet(LS_APIKEY, apiKey.value);
  }
  function setLocale(value: 'zh' | 'en'): void {
    locale.value = value;
    lsSet(LS_LOCALE, value);
  }
  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value;
  }

  return {
    tenant,
    apiKey,
    locale,
    bundleId,
    bundleVer,
    sidebarCollapsed,
    setTenant,
    setApiKey,
    setLocale,
    toggleSidebar
  };
});
