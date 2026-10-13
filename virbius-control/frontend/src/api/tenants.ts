import { admin, adminRoot, field } from '@/api/client';

export interface Tenant {
  tenantId: string;
  name: string;
  ruleCount: number;
  createdAt: string;
}

export interface Credential {
  credentialId: string;
  tenantId: string;
  keyPrefix: string;
  role: string;
  label: string;
  status: string;
  lastUsedAt: string | null;
}

export interface IssuedKey {
  apiKey: string;
  credentialId: string;
}

export async function listTenants(): Promise<Tenant[]> {
  const data = (await adminRoot('/tenants')) as unknown[] | null;
  return (Array.isArray(data) ? data : []).map((t) => ({
    tenantId: String(field<string>(t, 'tenant_id', 'tenantId') || ''),
    name: String(field<string>(t, 'name') || ''),
    ruleCount: Number(field<number>(t, 'rule_count', 'ruleCount') ?? 0),
    createdAt: String(field<string>(t, 'created_at', 'createdAt') || '')
  }));
}

export async function createTenant(tenantId: string, name: string): Promise<void> {
  await adminRoot('/tenants', { method: 'POST', body: JSON.stringify({ tenant_id: tenantId, name }) });
}

function normCred(raw: unknown, fallbackTenant: string): Credential {
  return {
    credentialId: String(field<string>(raw, 'credential_id', 'credentialId') || ''),
    tenantId: String(field<string>(raw, 'tenant_id', 'tenantId') || fallbackTenant),
    keyPrefix: String(field<string>(raw, 'key_prefix', 'keyPrefix') || ''),
    role: String(field<string>(raw, 'role') || ''),
    label: String(field<string>(raw, 'label') || ''),
    status: String(field<string>(raw, 'status') || ''),
    lastUsedAt: (field<string>(raw, 'last_used_at', 'lastUsedAt') ?? null) as string | null
  };
}

export async function listCredentials(tid: string): Promise<Credential[]> {
  const [creds, platform] = await Promise.all([
    adminRoot(`/tenants/${encodeURIComponent(tid)}/api-credentials`) as Promise<unknown[] | null>,
    adminRoot('/platform/api-credentials').catch(() => []) as Promise<unknown[] | null>
  ]);
  return [
    ...(Array.isArray(creds) ? creds : []).map((c) => normCred(c, tid)),
    ...(Array.isArray(platform) ? platform : []).map((c) => normCred(c, '*'))
  ];
}

function normIssued(raw: unknown): IssuedKey {
  return {
    apiKey: String(field<string>(raw, 'api_key', 'apiKey') || ''),
    credentialId: String(field<string>(raw, 'credential_id', 'credentialId') || '')
  };
}

export async function issueTenantKey(role: string, label: string | null): Promise<IssuedKey> {
  return normIssued(await admin('/api-credentials', { method: 'POST', body: JSON.stringify({ role, label }) }));
}

export async function issuePlatformKey(role: string, label: string | null): Promise<IssuedKey> {
  return normIssued(
    await adminRoot('/platform/api-credentials', { method: 'POST', body: JSON.stringify({ role, label }) })
  );
}

export async function revokeCredential(tenantId: string, credentialId: string): Promise<void> {
  const path =
    tenantId === '*'
      ? `/api/v1/admin/platform/api-credentials/${encodeURIComponent(credentialId)}/revoke`
      : `/api/v1/admin/tenants/${encodeURIComponent(tenantId)}/api-credentials/${encodeURIComponent(credentialId)}/revoke`;
  await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' }
  }).then(async (res) => {
    const j = await res.json().catch(() => null);
    if (!j || j.code !== 0) throw new Error(j?.message || 'HTTP ' + res.status);
  });
}
