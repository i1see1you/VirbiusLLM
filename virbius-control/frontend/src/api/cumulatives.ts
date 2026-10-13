import { admin, field } from '@/api/client';

export interface Cumulative {
  cumulativeName: string;
  description: string | null;
  dimension: string;
  status: string;
  priority: number;
  windowKind: string;
  windowMinutes: number | null;
  windowHours: number | null;
  timezone: string | null;
  ingestPredicate: string | null;
}

export interface CumulativePayload {
  description: string | null;
  dimension: string;
  window_kind: string;
  priority: number;
  status: string;
  ingest_predicate_runtime: string | null;
  ingest_predicate: string | null;
  window_hours: number | null;
  window_minutes: number | null;
  timezone: string | null;
}

function norm(raw: unknown): Cumulative {
  return {
    cumulativeName: String(field<string>(raw, 'cumulative_name', 'cumulativeName') || ''),
    description: (field<string>(raw, 'description') ?? null) as string | null,
    dimension: String(field<string>(raw, 'dimension') || ''),
    status: String(field<string>(raw, 'status') || 'active'),
    priority: Number(field<number>(raw, 'priority') ?? 10),
    windowKind: String(field<string>(raw, 'window_kind', 'windowKind') || 'rolling'),
    windowMinutes: (field<number>(raw, 'window_minutes', 'windowMinutes') ?? null) as number | null,
    windowHours: (field<number>(raw, 'window_hours', 'windowHours') ?? null) as number | null,
    timezone: (field<string>(raw, 'timezone') ?? null) as string | null,
    ingestPredicate: (field<string>(raw, 'ingest_predicate', 'ingestPredicate') ?? null) as string | null
  };
}

export async function listCumulatives(): Promise<Cumulative[]> {
  const data = (await admin('/cumulatives')) as unknown[] | null;
  return (Array.isArray(data) ? data : []).map(norm);
}

export async function getCumulative(name: string): Promise<Cumulative> {
  const raw = await admin(`/cumulatives/${encodeURIComponent(name)}`);
  const rec = raw as Record<string, unknown>;
  return norm(field<unknown>(rec, 'definition') || rec);
}

export async function saveCumulative(name: string, body: CumulativePayload): Promise<unknown> {
  return admin(`/cumulatives/${encodeURIComponent(name)}`, { method: 'PUT', body: JSON.stringify(body) });
}

export async function deleteCumulative(name: string): Promise<unknown> {
  return admin(`/cumulatives/${encodeURIComponent(name)}`, { method: 'DELETE' });
}

export function formatCumWindow(c: Cumulative): string {
  if (c.windowKind === 'calendar_day') {
    return `calendar_day (${c.timezone || 'UTC'})`;
  }
  if (c.windowMinutes) return `rolling ${c.windowMinutes}m`;
  if (c.windowHours) return `rolling ${c.windowHours}h`;
  return 'rolling';
}
