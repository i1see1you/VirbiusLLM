import { admin } from '@/api/client';
import { field } from '@/api/client';

export interface ListEntry {
  value: string;
  createdAt: string;
  expiresAt: string;
  remark: string;
}

export interface ListRow extends ListEntry {
  listName: string;
  dimension: string;
  storage: string;
}

export interface ListMeta {
  dimension: string;
  storage: string;
  entries: unknown[];
  activeEntryCount?: number;
}

function entryValue(e: unknown): string {
  if (typeof e === 'string') return e;
  return String(field<string>(e, 'value') || '');
}

export function inferStorageLocal(dim: string, storage?: string | null): string {
  if (storage) return String(storage).toLowerCase();
  const d = (dim || '').toLowerCase();
  if (d === 'keyword' || d === 'ip_cidr' || d === 'ip' || d === 'content') return 'memory';
  if (d === 'user_id' || d === 'device_id' || d === 'var' || d.startsWith('var:')) return 'redis';
  return 'memory';
}

export async function listLists(): Promise<{ rows: ListRow[]; meta: Record<string, ListMeta> }> {
  const data = (await admin('/lists')) as { lists?: unknown[] } | null;
  const lists = (data?.lists || []) as unknown[];
  const meta: Record<string, ListMeta> = {};
  const rows: ListRow[] = [];
  for (const item of lists) {
    const name = String(field<string>(item, 'list_name', 'listName') || '');
    if (!name) continue;
    const dim = String(field<string>(item, 'dimension') || '');
    const storage = inferStorageLocal(dim, field<string>(item, 'storage'));
    const entries = (field<unknown[]>(item, 'entries') || []) as unknown[];
    meta[name] = {
      dimension: dim,
      storage,
      entries,
      activeEntryCount: field<number>(item, 'active_entry_count', 'activeEntryCount')
    };
    for (const e of entries) {
      rows.push({
        listName: name,
        dimension: dim,
        storage,
        value: entryValue(e),
        createdAt: String(field<string>(e, 'created_at', 'createdAt') || ''),
        expiresAt: String(field<string>(e, 'expires_at', 'expiresAt') || ''),
        remark: String(field<string>(e, 'remark') || '')
      });
    }
  }
  rows.sort((a, b) => (a.listName + a.value).localeCompare(b.listName + b.value));
  return { rows, meta };
}

export async function createList(name: string, dimension: string, remark: string | null): Promise<void> {
  await admin(`/lists/${encodeURIComponent(name)}`, {
    method: 'PUT',
    body: JSON.stringify({ dimension, remark })
  });
}

export async function addListEntries(name: string, entries: Record<string, unknown>[]): Promise<unknown> {
  return admin(`/lists/${encodeURIComponent(name)}/entries`, {
    method: 'POST',
    body: JSON.stringify({ entries })
  });
}

export async function deleteListEntry(name: string, value: string): Promise<unknown> {
  return admin(`/lists/${encodeURIComponent(name)}/entries/${encodeURIComponent(value)}`, { method: 'DELETE' });
}
