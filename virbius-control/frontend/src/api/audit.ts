import { admin, field } from '@/api/client';

export interface AuditEvent {
  interceptedAt: string;
  layer: string;
  scene: string;
  effectiveAction: string;
  ruleId: string;
  ruleRevision: number | null;
  reasonCode: string;
  maxRiskScore: number | null;
  rolloutState: string;
  canaryPercent: number | null;
  userId: string;
}

export interface AuditResult {
  dbCount: number;
  note: string;
  events: AuditEvent[];
}

function norm(raw: unknown): AuditEvent {
  const r = raw as Record<string, unknown>;
  return {
    interceptedAt: String(field<string>(r, 'intercepted_at', 'interceptedAt') || ''),
    layer: String(field<string>(r, 'layer') || ''),
    scene: String(field<string>(r, 'scene') || ''),
    effectiveAction: String(field<string>(r, 'effective_action', 'effectiveAction') || ''),
    ruleId: String(field<string>(r, 'rule_id', 'ruleId') || ''),
    ruleRevision: (field<number>(r, 'rule_revision', 'ruleRevision') ?? null) as number | null,
    reasonCode: String(field<string>(r, 'reason_code', 'reasonCode') || ''),
    maxRiskScore: (field<number>(r, 'max_risk_score', 'maxRiskScore') ?? null) as number | null,
    rolloutState: String(field<string>(r, 'rollout_state', 'rolloutState') || ''),
    canaryPercent: (field<number>(r, 'canary_percent', 'canaryPercent') ?? null) as number | null,
    userId: String(field<string>(r, 'user_id', 'userId') || '')
  };
}

function toResult(raw: unknown): AuditResult {
  const r = (raw || {}) as Record<string, unknown>;
  return {
    dbCount: Number(field<number>(r, 'db_count', 'dbCount') ?? 0),
    note: String(field<string>(r, 'note') || ''),
    events: ((field<unknown[]>(r, 'db_events', 'dbEvents') || []) as unknown[]).map(norm)
  };
}

export async function auditRecent(limit = 100): Promise<AuditResult> {
  return toResult(await admin(`/audit/recent?limit=${limit}`));
}

export async function auditTrace(traceId: string): Promise<AuditResult> {
  return toResult(await admin(`/audit/trace/${encodeURIComponent(traceId)}`));
}
