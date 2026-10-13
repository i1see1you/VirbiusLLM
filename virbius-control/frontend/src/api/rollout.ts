import { admin, field } from '@/api/client';
import { useSessionStore } from '@/stores/session';

export interface RolloutPolicy {
  canaryLadder: number[];
}

export interface RuleMetrics {
  totals: Record<string, number>;
  series: unknown[];
  series1m: unknown[];
}

export interface RuleTimelineEvent {
  fromState: string;
  toState: string;
  pass: boolean;
  operator: string;
  comment: string;
  createdAt: string;
}

export interface AuditSample {
  traceId: string;
  [k: string]: unknown;
}

export interface DeployStatus {
  [layer: string]: {
    hasUnpublished: boolean;
    deployedAt: string | null;
    pendingRules: { rule_id: string; rollout_state: string }[];
  };
}

export interface ApplyRolloutBody {
  rollout_state: string;
  canary_percent: number | null;
  force: boolean;
  comment: string | null;
}

export interface DeployRollout {
  deployId: string;
  state: string;
  canaryPercent: number;
  bundleId: string;
  layer: string;
  startedAt: string;
  [k: string]: unknown;
}

export async function getRolloutPolicy(): Promise<RolloutPolicy> {
  const raw = (await admin('/rollout-policy')) as Record<string, unknown>;
  return { canaryLadder: (field<number[]>(raw, 'canary_ladder', 'canaryLadder') || [5, 20, 50, 100]) as number[] };
}

export async function ruleMetrics(ruleId: string, hours = 24): Promise<RuleMetrics> {
  const raw = (await admin(`/rules/${encodeURIComponent(ruleId)}/metrics?hours=${hours}`)) as Record<string, unknown>;
  return {
    totals: (field<Record<string, number>>(raw, 'totals') || {}) as Record<string, number>,
    series: (field<unknown[]>(raw, 'series') || []) as unknown[],
    series1m: (field<unknown[]>(raw, 'series_1m', 'series1m') || []) as unknown[]
  };
}

export async function ruleTimeline(ruleId: string): Promise<RuleTimelineEvent[]> {
  const data = (await admin(`/rules/${encodeURIComponent(ruleId)}/rollout/timeline`)) as unknown[] | null;
  return (Array.isArray(data) ? data : []).map((e) => {
    const rec = e as Record<string, unknown>;
    return {
      fromState: String(field<string>(rec, 'from_state', 'fromState') || ''),
      toState: String(field<string>(rec, 'to_state', 'toState') || ''),
      pass: field<boolean>(rec, 'pass') === true,
      operator: String(field<string>(rec, 'operator') || ''),
      comment: String(field<string>(rec, 'comment') || ''),
      createdAt: String(field<string>(rec, 'created_at', 'createdAt') || '')
    };
  });
}

export async function ruleAuditSamples(ruleId: string, limit = 30): Promise<AuditSample[]> {
  const data = (await admin(`/rules/${encodeURIComponent(ruleId)}/audit-samples?limit=${limit}`)) as unknown[] | null;
  return (Array.isArray(data) ? data : []) as AuditSample[];
}

export async function applyRollout(ruleId: string, body: ApplyRolloutBody): Promise<unknown> {
  return admin(`/rules/${encodeURIComponent(ruleId)}/rollout`, { method: 'PATCH', body: JSON.stringify(body) });
}

export async function dashboardOverview(): Promise<{ deployStatus: DeployStatus }> {
  const raw = (await admin('/dashboard/overview')) as Record<string, unknown>;
  const ds = (field<Record<string, unknown>>(raw, 'deploy_status', 'deployStatus') || {}) as Record<string, unknown>;
  const out: DeployStatus = {};
  for (const [layer, v] of Object.entries(ds)) {
    const rec = (v || {}) as Record<string, unknown>;
    out[layer] = {
      hasUnpublished: field<boolean>(rec, 'has_unpublished', 'hasUnpublished') === true,
      deployedAt: (field<string>(rec, 'deployed_at', 'deployedAt') ?? null) as string | null,
      pendingRules: ((field<unknown[]>(rec, 'pending_rules', 'pendingRules') || []) as unknown[]).map((r) => {
        const rr = r as Record<string, unknown>;
        return { rule_id: String(field(rr, 'rule_id', 'ruleId') || ''), rollout_state: String(field(rr, 'rollout_state', 'rolloutState') || '') };
      })
    };
  }
  return { deployStatus: out };
}

export async function deployLayer(layer: string): Promise<unknown> {
  return admin(`/deploy/${encodeURIComponent(layer)}`, { method: 'POST' });
}

export async function gatewayArtifactStatus(): Promise<number | null> {
  const raw = (await admin('/gateway-artifacts/policy-version')) as Record<string, unknown>;
  return (field<number>(raw, 'artifact_revision', 'artifactRevision') ?? null) as number | null;
}

export async function ingestHealth(layer: string, hours = 24): Promise<number> {
  const raw = (await admin(`/rollout/ingest-health?layer=${encodeURIComponent(layer)}&hours=${hours}`)) as Record<string, unknown>;
  return Number(field<number>(raw, 'audit_events', 'auditEvents') ?? 0);
}

export async function auditTrace(traceId: string): Promise<unknown> {
  return admin(`/audit/trace/${encodeURIComponent(traceId)}`);
}

// ---- 机器灰度（deploy-rollout） ----

function drPath(path: string): string {
  const s = useSessionStore();
  return `/deploy-rollout${path.replace('{tenant}', encodeURIComponent(s.tenant))}`;
}

async function drRequest(path: string, method: string, body?: unknown): Promise<unknown> {
  return admin(drPath(path), { method, body: body ? JSON.stringify(body) : undefined });
}

export async function drActive(): Promise<DeployRollout | null> {
  const raw = await drRequest('/active', 'GET');
  if (!raw || typeof raw !== 'object' || !(raw as Record<string, unknown>).deploy_id) return null;
  const rec = raw as Record<string, unknown>;
  return {
    deployId: String(field<string>(rec, 'deploy_id', 'deployId') || ''),
    state: String(field<string>(rec, 'state') || ''),
    canaryPercent: Number(field<number>(rec, 'canary_percent', 'canaryPercent') ?? 0),
    bundleId: String(field<string>(rec, 'bundle_id', 'bundleId') || ''),
    layer: String(field<string>(rec, 'layer') || ''),
    startedAt: String(field<string>(rec, 'started_at', 'startedAt') || ''),
    ...rec
  };
}

export async function drList(): Promise<DeployRollout[]> {
  const data = (await drRequest('/list', 'GET')) as unknown[] | null;
  return (Array.isArray(data) ? data : []) as DeployRollout[];
}

export async function drPrepare(body: { bundle_id: string; bundle_version?: string; layer: string; description: string }): Promise<unknown> {
  return drRequest('/prepare', 'POST', body);
}

export async function drAction(deployId: string, action: 'upgrade' | 'pause' | 'rollback' | 'finalize', note: string): Promise<unknown> {
  return drRequest(`/${encodeURIComponent(deployId)}/${action}`, 'POST', { note });
}
