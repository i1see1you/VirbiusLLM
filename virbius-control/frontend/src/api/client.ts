import { useSessionStore } from '@/stores/session';
import { useFeedbackStore } from '@/stores/feedback';

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: number
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/** snake_case / camelCase 多别名取值（决策 #7：仅 field() 归一化，无代码生成） */
export function field<T = unknown>(obj: unknown, ...keys: string[]): T | undefined {
  if (!obj || typeof obj !== 'object') return undefined;
  const rec = obj as Record<string, unknown>;
  for (const k of keys) {
    if (rec[k] !== undefined && rec[k] !== null) return rec[k] as T;
  }
  return undefined;
}

async function request(url: string, opts: RequestInit = {}): Promise<unknown> {
  const session = useSessionStore();
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...((opts.headers as Record<string, string>) || {})
  };
  const key = session.apiKey;
  if (key) headers['Authorization'] = 'Bearer ' + key;

  let res: Response;
  try {
    res = await fetch(url, { ...opts, headers });
  } catch (e) {
    const fb = useFeedbackStore();
    fb.banner('error', 'shell.network-err');
    throw new ApiError(String((e as Error)?.message || e), 0);
  }

  if (res.status === 401) {
    const fb = useFeedbackStore();
    fb.banner('error', 'shell.unauthorized');
    throw new ApiError('unauthorized', 401);
  }
  if (res.status === 403) {
    const fb = useFeedbackStore();
    fb.banner('error', 'shell.forbidden');
    throw new ApiError('forbidden', 403);
  }

  const body = (await res.json().catch(() => null)) as {
    code?: number;
    message?: string;
    data?: unknown;
  } | null;
  if (!body || body.code !== 0) {
    throw new ApiError(body?.message || 'HTTP ' + res.status, res.status, body?.code);
  }
  return body.data;
}

/** 平台级：/api/v1/admin + path */
export async function adminRoot(path: string, opts: RequestInit = {}): Promise<unknown> {
  return request('/api/v1/admin' + path, opts);
}

/** 租户级：/api/v1/admin/tenants/{tenant} + path */
export async function admin(path: string, opts: RequestInit = {}): Promise<unknown> {
  const session = useSessionStore();
  const url = '/api/v1/admin/tenants/' + encodeURIComponent(session.tenant) + path;
  return request(url, opts);
}
