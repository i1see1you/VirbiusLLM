import { admin, field } from '@/api/client';
import type { QaEntryInput, QaJob, QaMeta, QaRow, QaAckState } from '@/api/types';

function normMeta(raw: unknown): QaMeta {
  return {
    qaId: String(field<string>(raw, 'qaId', 'qa_id') || ''),
    name: String(field<string>(raw, 'name') || ''),
    description: (field<string>(raw, 'description') ?? null) as string | null,
    status: String(field<string>(raw, 'status') || 'enabled'),
    rolloutState: String(field<string>(raw, 'rolloutState', 'rollout_state') || 'dry_run'),
    canaryPercent: Number(field<number>(raw, 'canaryPercent', 'canary_percent') ?? 0),
    revision: Number(field<number>(raw, 'revision') ?? 0),
    updatedAt: (field<string>(raw, 'updatedAt', 'updated_at') ?? null) as string | null
  };
}

export function qaIdOf(m: { qaId: string }): string {
  return m.qaId;
}

export async function listQa(): Promise<QaMeta[]> {
  const data = (await admin('/qa')) as unknown[] | null;
  return (Array.isArray(data) ? data : []).map(normMeta);
}

export interface QaMetaBody {
  name: string;
  description: string | null;
  rollout_state: string;
  canary_percent: number;
}

export async function createQa(body: QaMetaBody): Promise<QaMeta> {
  return normMeta(await admin('/qa', { method: 'POST', body: JSON.stringify(body) }));
}

export async function updateQa(qaId: string, body: QaMetaBody): Promise<QaMeta> {
  return normMeta(await admin(`/qa/${encodeURIComponent(qaId)}`, { method: 'PUT', body: JSON.stringify(body) }));
}

export async function deleteQa(qaId: string): Promise<void> {
  await admin(`/qa/${encodeURIComponent(qaId)}`, { method: 'DELETE' });
}

export async function listEntries(qaId: string): Promise<QaRow[]> {
  const data = (await admin(`/qa/${encodeURIComponent(qaId)}/entries`)) as unknown[] | null;
  return (Array.isArray(data) ? data : []).map((e) => ({
    entryId: (field<string>(e, 'entryId', 'entry_id') ?? null) as string | null,
    question: String(field<string>(e, 'question') || ''),
    variants: (field<string[]>(e, 'variants') || []) as string[],
    answer: String(field<string>(e, 'answer') || ''),
    matchMode: String(field<string>(e, 'matchMode', 'match_mode') || 'exact'),
    priority: Number(field<number>(e, 'priority') ?? 0),
    enabled: field<boolean>(e, 'enabled') !== false,
    expiresAt: (field<string>(e, 'expiresAt', 'expires_at') ?? null) as string | null,
    lastPrecheckAt: (field<string>(e, 'lastPrecheckAt', 'last_precheck_at') ?? null) as string | null,
    lastPrecheckOk: (field<boolean>(e, 'lastPrecheckOk', 'last_precheck_ok') ?? null) as boolean | null
  }));
}

export async function saveEntries(qaId: string, rows: QaRow[]): Promise<QaRow[]> {
  const entries: QaEntryInput[] = rows.map((r) => {
    const item: QaEntryInput = {
      entry_id: r.entryId || null,
      question: r.question,
      variants: r.variants,
      answer: r.answer,
      match_mode: r.matchMode,
      priority: r.priority,
      enabled: r.enabled
    };
    if (r.expiresAt) item.expires_at = r.expiresAt;
    return item;
  });
  await admin(`/qa/${encodeURIComponent(qaId)}/entries`, {
    method: 'PUT',
    body: JSON.stringify({ entries })
  });
  return listEntries(qaId);
}

export async function publishQa(qaId: string): Promise<QaJob | null> {
  const raw = await admin(`/qa/${encodeURIComponent(qaId)}/publish`, { method: 'POST' });
  return raw ? normJob(raw) : null;
}

function normJob(raw: unknown): QaJob {
  return {
    publishId: String(field<string>(raw, 'publishId', 'publish_id') || ''),
    status: String(field<string>(raw, 'status') || 'pending'),
    snapshotRevision: (field<number>(raw, 'snapshotRevision', 'snapshot_revision') ?? null) as number | null,
    errors: (field<string>(raw, 'errors') ?? null) as string | null,
    updatedAt: (field<string>(raw, 'updatedAt', 'updated_at') ?? null) as string | null
  };
}

export async function listJobs(qaId: string): Promise<QaJob[]> {
  const data = (await admin(`/qa/${encodeURIComponent(qaId)}/jobs`)) as unknown[] | null;
  const list = (Array.isArray(data) ? data : []).map(normJob);
  return list.reverse();
}

export async function ackState(qaId: string): Promise<QaAckState> {
  const raw = (await admin(`/qa/${encodeURIComponent(qaId)}/ack`)) as Record<string, unknown> | null;
  const acks = (field<Record<string, unknown>>(raw, 'acks') || {}) as Record<string, unknown>;
  const out: QaAckState['acks'] = {};
  for (const [inst, a] of Object.entries(acks)) {
    out[inst] = {
      revision: Number(field<number>(a, 'revision') ?? 0),
      status: String(field<string>(a, 'status') || ''),
      updatedAt: (field<string>(a, 'updatedAt', 'updated_at') ?? null) as string | null,
      errors: (field<string>(a, 'errors') ?? null) as string | null
    };
  }
  return {
    revision: Number(field<number>(raw, 'revision') ?? 0),
    acks: out
  };
}
