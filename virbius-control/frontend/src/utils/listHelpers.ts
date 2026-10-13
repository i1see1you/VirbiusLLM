import { field } from '@/api/client';
import { i18n } from '@/locales';

const t = (k: string): string => i18n.global.t(k) as unknown as string;

export function formatListDimension(dim?: string | null): string {
  if (!dim) return '—';
  if (String(dim).startsWith('var:')) return 'var(' + String(dim).slice(4) + ')';
  const hints: Record<string, string> = {
    keyword: t('lists.dim-keyword'),
    user_id: t('lists.dim-user'),
    device_id: t('lists.dim-device'),
    ip_cidr: 'IP/CIDR'
  };
  return (hints[dim] || dim) + ' `' + dim + '`';
}

export function formatCumDimension(dim?: string | null): string {
  if (!dim) return '—';
  if (String(dim).startsWith('var:')) return 'var(' + String(dim).slice(4) + ')';
  return String(dim);
}

export function inferListStorage(dim?: string | null, storage?: string | null): string {
  if (storage) return String(storage).toLowerCase();
  const d = (dim || '').toLowerCase();
  if (d === 'keyword' || d === 'ip_cidr' || d === 'ip' || d === 'content') return 'memory';
  if (d === 'user_id' || d === 'device_id' || d === 'var' || d.startsWith('var:')) return 'redis';
  return 'memory';
}

export function formatListStorage(storage?: string | null): string {
  return storage === 'redis' ? 'Redis' : t('lists.storage-memory');
}

export function isListEntryActive(expiresAt?: string | null): boolean {
  if (!expiresAt) return true;
  const tms = Date.parse(String(expiresAt));
  return !Number.isNaN(tms) && tms > Date.now();
}

export function countActiveListEntries(entries?: unknown[]): number {
  if (!Array.isArray(entries)) return 0;
  return entries.filter((e) => isListEntryActive(field<string>(e, 'expires_at', 'expiresAt'))).length;
}

export function listSyncHint(data: unknown): string {
  if (!data || typeof data !== 'object') return '';
  const rec = data as Record<string, unknown>;
  if (rec.engine_reload || rec.engineReload) return t('lists.synced-engine');
  if (rec.refreshed) return t('lists.synced-gateway');
  return '';
}

/** 构建 dimension：var 维度返回 var:<logical>，校验 logical 命名 */
export function buildVarDimension(base: string, logical: string, requiredKey: string, invalidKey: string): string {
  if (base !== 'var') return base;
  const l = logical.trim();
  if (!l) throw new Error(t(requiredKey));
  if (!/^[a-zA-Z_][a-zA-Z0-9_]*$/.test(l)) throw new Error(t(invalidKey));
  return 'var:' + l;
}
