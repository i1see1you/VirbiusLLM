import { admin, field } from '@/api/client';

export interface MetricBucket {
  bucket: string;
  total_requests: number;
  allow: number;
  review: number;
  block: number;
  captcha: number;
  cnt_degraded?: number;
}

export interface Metrics {
  totals: Record<string, number>;
  series: MetricBucket[];
}

export interface RuleRankRow {
  rule_id: string;
  total_hits: number;
  block: number;
  review: number;
  captcha: number;
  hit_rate: number;
  block_rate: number;
  total_requests: number;
}

export interface SceneTrafficRow {
  scene: string;
  layer: string;
  total_requests: number;
}

export interface DegradationPoint {
  bucket: string;
  degraded_rate: number;
}

export interface EventRow {
  effective_at: string;
  rule_id: string;
  rollout_state: string;
  rule_revision: number;
  trigger: string;
  operator?: string;
}

export interface IngestStatus {
  enabled?: boolean;
  stream_key?: string;
  redis_ok?: boolean;
  db_events_24h?: number;
  db_events_total?: number;
  lag_estimate?: number;
  last_poll_at?: string;
  layer?: string;
  audit_events?: number;
  estimated_requests?: number;
  hours?: number;
}

function num(v: unknown): number {
  return Number(v ?? 0);
}

export async function monitorMetrics(hours: number): Promise<Metrics> {
  const raw = (await admin(`/deploy-rollout/metrics?hours=${hours}`)) as Record<string, unknown>;
  return {
    totals: (field<Record<string, number>>(raw, 'totals') || {}) as Record<string, number>,
    series: ((field<unknown[]>(raw, 'series') || []) as unknown[]).map((s) => {
      const r = s as Record<string, unknown>;
      return {
        bucket: String(field(r, 'bucket') || ''),
        total_requests: num(field(r, 'total_requests', 'totalRequests')),
        allow: num(field(r, 'allow')),
        review: num(field(r, 'review')),
        block: num(field(r, 'block')),
        captcha: num(field(r, 'captcha')),
        cnt_degraded: num(field(r, 'cnt_degraded', 'cntDegraded'))
      };
    })
  };
}

export async function ruleRanking(hours: number, limit = 20): Promise<RuleRankRow[]> {
  const raw = (await admin(`/monitor/rule-ranking?hours=${hours}&limit=${limit}`)) as Record<string, unknown>;
  return ((field<unknown[]>(raw, 'ranking') || []) as unknown[]).map((s) => {
    const r = s as Record<string, unknown>;
    return {
      rule_id: String(field(r, 'rule_id', 'ruleId') || ''),
      total_hits: num(field(r, 'total_hits', 'totalHits')),
      block: num(field(r, 'block')),
      review: num(field(r, 'review')),
      captcha: num(field(r, 'captcha')),
      hit_rate: num(field(r, 'hit_rate', 'hitRate')),
      block_rate: num(field(r, 'block_rate', 'blockRate')),
      total_requests: num(field(r, 'total_requests', 'totalRequests'))
    };
  });
}

export async function sceneTraffic(hours: number): Promise<SceneTrafficRow[]> {
  const raw = (await admin(`/monitor/scene-traffic?hours=${hours}`)) as Record<string, unknown>;
  return ((field<unknown[]>(raw, 'scenes') || []) as unknown[]).map((s) => {
    const r = s as Record<string, unknown>;
    return {
      scene: String(field(r, 'scene') || ''),
      layer: String(field(r, 'layer') || ''),
      total_requests: num(field(r, 'total_requests', 'totalRequests'))
    };
  });
}

export async function degradation(hours: number): Promise<DegradationPoint[]> {
  const raw = (await admin(`/monitor/degradation?hours=${hours}`)) as Record<string, unknown>;
  return ((field<unknown[]>(raw, 'series') || []) as unknown[]).map((s) => {
    const r = s as Record<string, unknown>;
    return { bucket: String(field(r, 'bucket') || ''), degraded_rate: num(field(r, 'degraded_rate', 'degradedRate')) };
  });
}

export async function eventTimeline(hours: number, limit = 20): Promise<EventRow[]> {
  const h = hours > 48 ? hours : 48;
  const raw = (await admin(`/monitor/event-timeline?hours=${h}&limit=${limit}`)) as Record<string, unknown>;
  return ((field<unknown[]>(raw, 'events') || []) as unknown[]).map((s) => {
    const r = s as Record<string, unknown>;
    return {
      effective_at: String(field(r, 'effective_at', 'effectiveAt') || ''),
      rule_id: String(field(r, 'rule_id', 'ruleId') || ''),
      rollout_state: String(field(r, 'rollout_state', 'rolloutState') || ''),
      rule_revision: num(field(r, 'rule_revision', 'ruleRevision')),
      trigger: String(field(r, 'trigger') || ''),
      operator: (field<string>(r, 'operator') ?? undefined) as string | undefined
    };
  });
}

export async function ingestStatus(): Promise<IngestStatus | null> {
  const raw = (await admin('/audit/ingest-status')) as Record<string, unknown> | null;
  if (!raw) return null;
  return {
    enabled: field<boolean>(raw, 'enabled'),
    stream_key: (field<string>(raw, 'stream_key', 'streamKey') ?? undefined) as string | undefined,
    redis_ok: field<boolean>(raw, 'redis_ok', 'redisOk'),
    db_events_24h: field<number>(raw, 'db_events_24h', 'dbEvents24h'),
    db_events_total: field<number>(raw, 'db_events_total', 'dbEventsTotal'),
    lag_estimate: field<number>(raw, 'lag_estimate', 'lagEstimate'),
    last_poll_at: (field<string>(raw, 'last_poll_at', 'lastPollAt') ?? undefined) as string | undefined,
    layer: (field<string>(raw, 'layer') ?? undefined) as string | undefined,
    audit_events: field<number>(raw, 'audit_events', 'auditEvents'),
    estimated_requests: field<number>(raw, 'estimated_requests', 'estimatedRequests'),
    hours: field<number>(raw, 'hours')
  };
}
