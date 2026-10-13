import { createI18n } from 'vue-i18n';
import zh from '@/locales/zh';
import en from '@/locales/en';
import { useSessionStore } from '@/stores/session';

export const i18n = createI18n({
  legacy: false,
  locale: 'zh',
  fallbackLocale: 'en',
  messages: { zh, en },
  missingWarn: false,
  fallbackWarn: false
});

export function setupI18n(): void {
  const session = useSessionStore();
  i18n.global.locale.value = session.locale;
}

/** 类似旧 __()：t(key, ...args) 按 {0}{1} 替换 */
export function t(key: string, ...args: unknown[]): string {
  if (args.length === 0) return i18n.global.t(key) as unknown as string;
  return i18n.global.t(key, args.map(String) as unknown[]) as unknown as string;
}
