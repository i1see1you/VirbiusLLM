import { admin, field } from '@/api/client';
import { useSessionStore } from '@/stores/session';

export interface RuleScope {
  bind_scope: 'global' | 'route' | 'service';
  bind_ref?: { scenes?: string[]; app_ids?: string[] };
}

export interface RuleItem {
  ruleId: string;
  layer: string;
  runtime: string;
  scope: RuleScope | undefined;
  rolloutState: string;
  currentRevision: number;
  riskScore: number;
  intentAction: string;
  enforceMode: string;
  canaryPercent: number;
  isAsync: boolean;
  reasonCode: string;
}

export interface RuleDetail extends RuleItem {
  body: unknown;
  asyncActionConfig: string | null;
}

export interface AsyncActionConfig {
  action_type: string;
  stream_key?: string;
  webhook_url?: string;
  message?: string;
}

export interface ConditionLeaf {
  type: 'list_match' | 'cumulative';
  list_name?: string;
  value_source?: string;
  cumulative_name?: string;
  compare?: string;
  threshold?: number;
}

export interface CondPayload {
  op?: 'and';
  children?: ConditionLeaf[];
  type?: string;
}

export interface RuleSaveBody {
  rule_id: string;
  bundle_id: string;
  layer: string;
  runtime: string;
  reason_code: string;
  risk_score: number;
  intent_action: string;
  scope: RuleScope;
  body: unknown;
  editor_mode: string | null;
  condition: CondPayload | null;
  is_async: boolean;
  async_action_config: AsyncActionConfig | null;
}

function normScope(raw: unknown): RuleScope | undefined {
  if (!raw || typeof raw !== 'object') return undefined;
  const rec = raw as Record<string, unknown>;
  const bs = String(field<string>(rec, 'bind_scope', 'bindScope') || 'global') as RuleScope['bind_scope'];
  const ref = (field<Record<string, unknown>>(rec, 'bind_ref', 'bindRef') || {}) as Record<string, unknown>;
  const scenes = (field<string[]>(ref, 'scenes') || []) as string[];
  const appIds = (field<string[]>(ref, 'app_ids', 'appIds') || []) as string[];
  const scope: RuleScope = { bind_scope: bs };
  const r: RuleScope['bind_ref'] = {};
  if (scenes.length) r.scenes = scenes;
  if (appIds.length) r.app_ids = appIds;
  if (Object.keys(r).length) scope.bind_ref = r;
  return scope;
}

function normItem(raw: unknown): RuleItem {
  const rec = raw as Record<string, unknown>;
  return {
    ruleId: String(field<string>(rec, 'rule_id', 'ruleId') || ''),
    layer: String(field<string>(rec, 'layer') || ''),
    runtime: String(field<string>(rec, 'runtime') || ''),
    scope: normScope(field(rec, 'scope')),
    rolloutState: String(field<string>(rec, 'rollout_state', 'rolloutState') || 'draft'),
    currentRevision: Number(field<number>(rec, 'current_revision', 'currentRevision') ?? 0),
    riskScore: Number(field<number>(rec, 'risk_score', 'riskScore') ?? 0),
    intentAction: String(field<string>(rec, 'intent_action', 'intentAction') || 'deny'),
    enforceMode: String(field<string>(rec, 'enforce_mode', 'enforceMode') || ''),
    canaryPercent: Number(field<number>(rec, 'canary_percent', 'canaryPercent') ?? 0),
    isAsync: field<boolean>(rec, 'is_async', 'isAsync') === true,
    reasonCode: String(field<string>(rec, 'reason_code', 'reasonCode') || '')
  };
}

export function formatBindScope(scope?: RuleScope): string {
  const s = scope;
  if (!s || s.bind_scope === 'global') return 'global';
  const ref = s.bind_ref || {};
  if (s.bind_scope === 'route') {
    const scenes = (ref.scenes || []).join(', ');
    return scenes ? `route:${scenes}` : 'route';
  }
  if (s.bind_scope === 'service') {
    const ids = (ref.app_ids || []).join(', ');
    return ids ? `service:${ids}` : 'service';
  }
  return 'global';
}

export async function listRules(layer: string): Promise<RuleItem[]> {
  const data = (await admin('/rules?layer=' + encodeURIComponent(layer))) as unknown[] | null;
  return (Array.isArray(data) ? data : []).map(normItem);
}

export async function getRule(ruleId: string): Promise<RuleDetail> {
  const raw = await admin('/rules/' + encodeURIComponent(ruleId));
  const base = normItem(raw);
  const rec = raw as Record<string, unknown>;
  return {
    ...base,
    body: field(rec, 'body') ?? '',
    asyncActionConfig: (field<string | object>(rec, 'async_action_config', 'asyncActionConfig') ?? null) as string | null
  };
}

export async function validateScript(layer: string, runtime: string, body: unknown): Promise<{ valid: boolean; errors?: string[]; warnings?: string[] }> {
  const raw = (await admin('/rules/validate-script', {
    method: 'POST',
    body: JSON.stringify({ layer, runtime, body })
  })) as Record<string, unknown>;
  return {
    valid: field<boolean>(raw, 'valid') === true,
    errors: (field<string[]>(raw, 'errors') || []) as string[],
    warnings: (field<string[]>(raw, 'warnings') || []) as string[]
  };
}

export async function compileCondition(layer: string, runtime: string, condition: CondPayload): Promise<string> {
  const raw = (await admin('/rules/compile-condition', {
    method: 'POST',
    body: JSON.stringify({ layer, runtime, condition })
  })) as Record<string, unknown>;
  return String(field<string>(raw, 'script') || '');
}

export async function parseCondition(layer: string, runtime: string, script: unknown): Promise<{ parseable: boolean; condition: CondPayload | null }> {
  const raw = (await admin('/rules/parse-condition', {
    method: 'POST',
    body: JSON.stringify({ layer, runtime, script })
  })) as Record<string, unknown>;
  return {
    parseable: field<boolean>(raw, 'parseable') === true,
    condition: (field<CondPayload>(raw, 'condition') ?? null) as CondPayload | null
  };
}

export async function listRecipes(layer: string): Promise<{ recipe_id: string; label: string; bind_scope: string; bind_ref?: RuleScope['bind_ref']; condition?: CondPayload; risk_score?: number; intent_action?: string }[]> {
  const raw = (await admin('/rules/recipes?layer=' + encodeURIComponent(layer))) as Record<string, unknown>;
  return (field<unknown[]>(raw, 'recipes') || []) as never[];
}

export interface SimStep {
  id: string;
  ok: boolean;
  detail: unknown;
}
export interface SimResult {
  steps: SimStep[];
  summary: { message?: string };
}

export async function simulateRule(payload: unknown): Promise<SimResult> {
  const raw = (await admin('/rules/simulate', { method: 'POST', body: JSON.stringify(payload) })) as Record<string, unknown>;
  return {
    steps: ((field<unknown[]>(raw, 'steps') || []) as unknown[]).map((s) => {
      const rec = s as Record<string, unknown>;
      return { id: String(field(rec, 'id') || ''), ok: field<boolean>(rec, 'ok') === true, detail: field(rec, 'detail') };
    }),
    summary: (field<Record<string, unknown>>(raw, 'summary') || {}) as { message?: string }
  };
}

export async function saveRule(body: RuleSaveBody): Promise<void> {
  await admin('/rules', { method: 'POST', body: JSON.stringify(body) });
}

export type RolloutAction = 'disable' | 'publish' | 'recover' | 'rollback';

export async function ruleRollout(ruleId: string, action: RolloutAction): Promise<unknown> {
  return admin(`/rules/${encodeURIComponent(ruleId)}/rollout/${action}`, { method: 'POST' });
}

export function bundleId(): string {
  return useSessionStore().bundleId;
}

export function inExecutionPlane(st: string): boolean {
  return st === 'dry_run' || st === 'canary' || st === 'full';
}
