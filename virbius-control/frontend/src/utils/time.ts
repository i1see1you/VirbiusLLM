import { i18n } from '@/locales';

export function parseUtc(s?: string | null): Date | null {
  if (!s) return null;
  let iso = s.includes('T') ? s : s.replace(' ', 'T');
  if (!/[zZ]|[+-]\d{2}:?\d{2}$/.test(iso)) iso += 'Z';
  const t = Date.parse(iso);
  return Number.isNaN(t) ? null : new Date(t);
}

export function fmtTime(s?: string | null): string {
  if (!s) return '—';
  const d = parseUtc(s);
  if (!d) return String(s).replace('T', ' ').slice(0, 19);
  return d.toLocaleString(undefined, { hour12: false });
}

export function fmtTimeAgo(s?: string | null): string {
  if (!s) return '—';
  const d = parseUtc(s);
  if (!d) return '—';
  const t = i18n.global.t as (k: string, n?: number) => string;
  const diff = Date.now() - d.getTime();
  if (diff < 60000) return t('time.just-now');
  if (diff < 3600000) return t('time.minutes-ago', Math.floor(diff / 60000));
  if (diff < 86400000) return t('time.hours-ago', Math.floor(diff / 3600000));
  return t('time.days-ago', Math.floor(diff / 86400000));
}
