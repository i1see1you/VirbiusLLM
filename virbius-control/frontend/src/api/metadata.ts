import { admin, field } from '@/api/client';
import { useSessionStore } from '@/stores/session';

export interface Scope {
  bind_scope: 'global' | 'service' | 'route';
  app_ids?: string[];
  scenes?: string[];
}

export interface ContextVar {
  logical: string;
  from: string;
  name?: string;
  field?: string;
  scope?: Scope;
}

export interface ExtendedVar {
  logical: string;
  expr: string;
  scope?: Scope;
}

export interface SceneEntry {
  sceneId: string;
  appId: string;
  defaultScene: boolean;
  uris: string[];
  priority: number;
  matchQuery: Record<string, string>;
}

export interface SceneRegistry {
  failOnUnknownApp: boolean;
  failOnUnresolvedScene: boolean;
}

export interface GatewayRoute {
  uri: string;
  methods: string[];
}

export interface GatewaySettings {
  evaluate: boolean;
  failMode: string;
  agentUrl: string;
  timeoutMs: number;
}

export interface BundleMetadata {
  contextVars: ContextVar[];
  extendedVars: ExtendedVar[];
  sceneEntries: SceneEntry[];
  sceneRegistry: SceneRegistry;
  gateway: GatewaySettings;
  gatewayRoutes: GatewayRoute[];
}

function metaPath(): string {
  const s = useSessionStore();
  return `/bundles/${encodeURIComponent(s.bundleId)}/versions/${encodeURIComponent(s.bundleVer)}/metadata`;
}

function normalizeScope(raw: unknown): Scope | undefined {
  if (!raw || typeof raw !== 'object') return undefined;
  const rec = raw as Record<string, unknown>;
  const bs = String(field<string>(rec, 'bind_scope', 'bindScope') || 'global') as Scope['bind_scope'];
  const appIds = (field<string[]>(rec, 'app_ids', 'appIds') || []) as string[];
  const scenes = (field<string[]>(rec, 'scenes') || []) as string[];
  const scope: Scope = { bind_scope: bs };
  if (appIds.length) scope.app_ids = appIds;
  if (scenes.length) scope.scenes = scenes;
  return scope;
}

export async function getMetadata(): Promise<BundleMetadata> {
  const data = (await admin(metaPath())) as Record<string, unknown>;
  const contextVars = ((field<unknown[]>(data, 'context_vars') || []) as unknown[]).map((v) => {
    const rec = v as Record<string, unknown>;
    return {
      logical: String(field<string>(rec, 'logical') || ''),
      from: String(field<string>(rec, 'from') || 'query'),
      name: (field<string>(rec, 'name') ?? undefined) as string | undefined,
      field: (field<string>(rec, 'field') ?? undefined) as string | undefined,
      scope: normalizeScope(field(rec, 'scope'))
    };
  });
  const extendedVars = ((field<unknown[]>(data, 'extended_var_list') || []) as unknown[]).map((v) => {
    const rec = v as Record<string, unknown>;
    return {
      logical: String(field<string>(rec, 'logical') || ''),
      expr: String(field<string>(rec, 'expr') || ''),
      scope: normalizeScope(field(rec, 'scope'))
    };
  });
  const sceneEntries = ((field<unknown[]>(data, 'scene_entries') || []) as unknown[]).map((r) => {
    const rec = r as Record<string, unknown>;
    const match = (field<Record<string, unknown>>(rec, 'match') || {}) as Record<string, unknown>;
    const query = (field<Record<string, string>>(match, 'query') || {}) as Record<string, string>;
    return {
      sceneId: String(field<string>(rec, 'scene_id', 'sceneId') || ''),
      appId: String(field<string>(rec, 'app_id', 'appId') || ''),
      defaultScene: field<boolean>(rec, 'default') === true,
      uris: ((field<string[]>(rec, 'uris') || []) as string[]).slice(),
      priority: Number(field<number>(rec, 'priority') ?? 0),
      matchQuery: { ...query }
    };
  });
  const reg = (field<Record<string, unknown>>(data, 'scene_registry') || {}) as Record<string, unknown>;
  const gw = (field<Record<string, unknown>>(data, 'gateway') || {}) as Record<string, unknown>;
  const cloudScan = (field<Record<string, unknown>>(gw, 'cloud_scan', 'cloudScan') || {}) as Record<string, unknown>;
  const gatewayRoutes = ((field<unknown[]>(data, 'gateway_routes') || []) as unknown[]).map((r) => {
    const rec = r as Record<string, unknown>;
    const methods = (field<string[]>(rec, 'methods') || ['POST']) as string[];
    return { uri: String(field<string>(rec, 'uri') || ''), methods: methods.length ? methods : ['POST'] };
  });
  return {
    contextVars,
    extendedVars,
    sceneEntries,
    sceneRegistry: {
      failOnUnknownApp: field<boolean>(reg, 'fail_on_unknown_app', 'failOnUnknownApp') === true,
      failOnUnresolvedScene: field<boolean>(reg, 'fail_on_unresolved_scene', 'failOnUnresolvedScene') === true
    },
    gateway: {
      evaluate: field<boolean>(gw, 'evaluate') !== false,
      failMode: String(field<string>(gw, 'fail_mode', 'failMode') || 'open').toLowerCase(),
      agentUrl: String(field<string>(cloudScan, 'agent_url', 'agentUrl') || ''),
      timeoutMs: Number(field<number>(cloudScan, 'timeout_ms', 'timeoutMs') ?? 3000)
    },
    gatewayRoutes
  };
}

type ScopeOpt = Scope | undefined;

export async function saveContextBindings(vars: ContextVar[]): Promise<unknown> {
  const body = {
    vars: vars.map((v) => {
      const m: Record<string, unknown> = { logical: v.logical, from: v.from };
      if (v.name) m.name = v.name;
      if (v.field) m.field = v.field;
      if (v.scope && v.scope.bind_scope !== 'global') m.scope = v.scope;
      return m;
    })
  };
  return admin(`${metaPath()}/context-bindings?sync=true`, { method: 'PUT', body: JSON.stringify(body) });
}

export async function deleteContextBinding(logical: string): Promise<unknown> {
  return admin(`${metaPath()}/context-bindings/${encodeURIComponent(logical)}`, { method: 'DELETE' });
}

export async function saveExtendedVars(vars: ExtendedVar[]): Promise<unknown> {
  const body = { vars: vars.map((v) => buildScoped({ logical: v.logical, expr: v.expr }, v.scope)) };
  return admin(`${metaPath()}/extended-vars?sync=true`, { method: 'PUT', body: JSON.stringify(body) });
}

export async function deleteExtendedVar(logical: string): Promise<unknown> {
  return admin(`${metaPath()}/extended-vars/${encodeURIComponent(logical)}`, { method: 'DELETE' });
}

function buildScoped<T extends Record<string, unknown>>(base: T, scope: ScopeOpt): T {
  if (scope && scope.bind_scope && scope.bind_scope !== 'global') {
    return { ...base, scope } as T;
  }
  return base;
}

export async function saveSceneRegistry(
  reg: SceneRegistry,
  entries: SceneEntry[],
  sync: boolean
): Promise<unknown> {
  const body = {
    fail_on_unknown_app: reg.failOnUnknownApp,
    fail_on_unresolved_scene: reg.failOnUnresolvedScene,
    scenes: entries.map((e) => {
      const row: Record<string, unknown> = {
        scene_id: e.sceneId,
        app_id: e.appId,
        default_scene: e.defaultScene,
        uris: e.uris && e.uris.length ? e.uris : [],
        priority: e.priority ?? 0
      };
      if (e.matchQuery && Object.keys(e.matchQuery).length) row.match_query = e.matchQuery;
      return row;
    })
  };
  return admin(`${metaPath()}/scene-registry?sync=${sync}`, { method: 'PUT', body: JSON.stringify(body) });
}

export async function saveGatewayRoutes(gw: GatewaySettings, routes: GatewayRoute[]): Promise<unknown> {
  const cloudScan: Record<string, unknown> = {};
  if (gw.agentUrl) cloudScan.agent_url = gw.agentUrl;
  if (!Number.isNaN(gw.timeoutMs) && gw.timeoutMs > 0) cloudScan.timeout_ms = gw.timeoutMs;
  const body = {
    evaluate: gw.evaluate,
    fail_mode: gw.failMode || 'open',
    cloud_scan: cloudScan,
    routes: routes.map((r) => ({ uri: r.uri, methods: r.methods && r.methods.length ? r.methods : ['POST'] }))
  };
  return admin(`${metaPath()}/gateway-routes?sync=true`, { method: 'PUT', body: JSON.stringify(body) });
}

export function parseMethodsText(s: string): string[] {
  return String(s || 'POST')
    .split(/[,，\s]+/)
    .map((x) => x.trim().toUpperCase())
    .filter(Boolean);
}
