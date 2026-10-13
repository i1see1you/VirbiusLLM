import type { CompletionSource, CompletionContext, CompletionResult } from '@codemirror/autocomplete';
import type { Diagnostic } from '@codemirror/lint';
import type { EditorView } from '@codemirror/view';
import { admin } from '@/api/client';

export interface ScriptCompletionContext {
  runtime: 'groovy' | 'lua' | 'json' | 'prompt';
  listNames: string[];
  cumNames: string[];
  contextVars: string[];
}

const GROOVY_API = [
  { label: 'def decide(ctx)', detail: 'kw', type: 'keyword', insert: 'def decide(ctx) {\n  \n}' },
  { label: 'ctx.listMatch(name)', detail: 'fn', type: 'function', insert: "ctx.listMatch('')" },
  { label: 'ctx.listMatch(name, value)', detail: 'fn', type: 'function', insert: "ctx.listMatch('', ctx.content)" },
  { label: 'ctx.getCumulative(name)', detail: 'fn', type: 'function', insert: "ctx.getCumulative('')" },
  { label: 'ctx.var(logical)', detail: 'fn', type: 'function', insert: "ctx.var('')" },
  { label: 'ctx.content', detail: 'field', type: 'variable', insert: 'ctx.content' },
  { label: 'ctx.user_id', detail: 'field', type: 'variable', insert: 'ctx.user_id' },
  { label: 'ctx.device_id', detail: 'field', type: 'variable', insert: 'ctx.device_id' },
  { label: 'ctx.client_ip', detail: 'field', type: 'variable', insert: 'ctx.client_ip' },
  { label: 'ctx.session_id', detail: 'field', type: 'variable', insert: 'ctx.session_id' },
  { label: 'ctx.scene', detail: 'field', type: 'variable', insert: 'ctx.scene' },
  { label: 'ctx.route_uri', detail: 'field', type: 'variable', insert: 'ctx.route_uri' },
  { label: 'return', detail: 'kw', type: 'keyword', insert: 'return ' },
  { label: 'if else', detail: 'kw', type: 'keyword', insert: 'if () {\n  \n} else {\n  \n}' }
];

const LUA_API = [
  { label: 'function decide(ctx)', detail: 'kw', type: 'keyword', insert: 'function decide(ctx)\n  \nend' },
  { label: 'listMatch(name, value)', detail: 'fn', type: 'function', insert: "listMatch('', ctx.content)" },
  { label: 'getCumulative(name)', detail: 'fn', type: 'function', insert: "getCumulative('')" },
  { label: 'ctx.var(logical)', detail: 'fn', type: 'function', insert: "ctx.var('')" },
  { label: 'ctx.content', detail: 'field', type: 'variable', insert: 'ctx.content' },
  { label: 'ctx.user_id', detail: 'field', type: 'variable', insert: 'ctx.user_id' },
  { label: 'ctx.device_id', detail: 'field', type: 'variable', insert: 'ctx.device_id' },
  { label: 'ctx.client_ip', detail: 'field', type: 'variable', insert: 'ctx.client_ip' },
  { label: 'ctx.session_id', detail: 'field', type: 'variable', insert: 'ctx.session_id' },
  { label: 'ctx.scene', detail: 'field', type: 'variable', insert: 'ctx.scene' },
  { label: 'ctx.route_uri', detail: 'field', type: 'variable', insert: 'ctx.route_uri' },
  { label: 'return', detail: 'kw', type: 'keyword', insert: 'return ' },
  { label: 'if then else end', detail: 'kw', type: 'keyword', insert: 'if  then\n  \nelse\n  \nend' },
  { label: 'end', detail: 'kw', type: 'keyword', insert: 'end' }
];

function buildApiCompletionSource(ctx: ScriptCompletionContext): CompletionSource {
  const items = ctx.runtime === 'lua' ? LUA_API : ctx.runtime === 'groovy' ? GROOVY_API : [];
  return (completionCtx: CompletionContext): CompletionResult | null => {
    const word = completionCtx.matchBefore(/[\w.]+/);
    if (!word || (word.from === word.to && !completionCtx.explicit)) return null;
    const filter = word.text.toLowerCase();
    const filtered = filter ? items.filter((it) => it.label.toLowerCase().includes(filter)) : items;
    if (!filtered.length) return null;
    return {
      from: word.from,
      options: filtered.map((it) => ({
        label: it.label,
        type: it.type,
        detail: it.detail,
        apply: (view: EditorView, _c: unknown, from: number, to: number) => {
          const insertText = it.insert;
          let cursorOffset = insertText.length;
          const quoteIdx = insertText.indexOf("''");
          if (quoteIdx >= 0) cursorOffset = quoteIdx + 1;
          view.dispatch({ changes: { from, to, insert: insertText }, selection: { anchor: from + cursorOffset } });
        }
      }))
    };
  };
}

function quotedInsert(view: EditorView, from: number, name: string): string {
  const before = view.state.doc.sliceString(0, from);
  const quoted = before.endsWith("'") || before.endsWith('"');
  const quote = before.endsWith('"') ? '"' : "'";
  return quoted ? name + quote + ')' : "'" + name + "')";
}

function buildArgCompletionSource(ctx: ScriptCompletionContext, fnPattern: RegExp, names: string[], detail: string): CompletionSource {
  return (completionCtx: CompletionContext): CompletionResult | null => {
    const head = completionCtx.state.doc.sliceString(0, completionCtx.pos);
    const m = head.match(fnPattern);
    if (!m) return null;
    const filter = m[1] || '';
    const filtered = names.filter((n) => !filter || n.toLowerCase().includes(filter.toLowerCase()));
    if (!filtered.length) return null;
    const from = completionCtx.pos - filter.length;
    return {
      from,
      options: filtered.map((n) => ({
        label: n,
        type: 'variable',
        detail,
        apply: (view: EditorView, _c: unknown, f: number, t: number) => {
          const insertText = quotedInsert(view, f, n);
          view.dispatch({ changes: { from: f, to: t, insert: insertText }, selection: { anchor: f + insertText.length } });
        }
      }))
    };
  };
}

export function buildCompletionSources(ctx: ScriptCompletionContext): CompletionSource[] {
  if (ctx.runtime !== 'groovy' && ctx.runtime !== 'lua') return [];
  return [
    buildArgCompletionSource(ctx, /(?:listMatch|ctx\.listMatch)\s*\(\s*['"]?([^'"]*)$/, ctx.listNames, 'list'),
    buildArgCompletionSource(ctx, /(?:getCumulative|ctx\.getCumulative)\s*\(\s*['"]?([^'"]*)$/, ctx.cumNames, 'cumulative'),
    buildArgCompletionSource(ctx, /ctx\.var\s*\(\s*['"]?([^'"]*)$/, ctx.contextVars, 'context var'),
    buildApiCompletionSource(ctx)
  ];
}

export interface LintContext {
  runtime: 'groovy' | 'lua' | 'json' | 'prompt';
  layer: string;
  listNames: string[];
  cumNames: string[];
  contextVars: string[];
}

const DECLARED = new Set([
  'rule_id',
  'rule_revision',
  'tenant_id',
  'reason_code',
  'intent_action',
  'risk_score',
  'hit_at',
  'user_id',
  'device_id',
  'client_ip',
  'session_id',
  'content',
  'scene',
  'route_uri',
  'app_id'
]);

export async function runLint(view: EditorView, ctx: LintContext): Promise<Diagnostic[]> {
  const doc = view.state.doc.toString();
  if (!doc.trim()) return [];
  const diagnostics: Diagnostic[] = [];

  if (ctx.runtime === 'groovy' || ctx.runtime === 'lua') {
    try {
      const data = (await admin('/rules/validate-script', {
        method: 'POST',
        body: JSON.stringify({ layer: ctx.layer, runtime: ctx.runtime, body: doc })
      })) as { valid?: boolean; errors?: string[]; warnings?: string[] };
      const push = (arr: string[] | undefined, severity: 'error' | 'warning') => {
        for (const msg of arr || []) {
          const lineMatch = msg.match(/line (\d+)/i);
          const lineNum = lineMatch ? Number(lineMatch[1]) : 1;
          const line = view.state.doc.line(Math.min(lineNum, view.state.doc.lines));
          diagnostics.push({ from: line.from, to: line.to, severity, message: msg });
        }
      };
      if (!data.valid) push(data.errors, 'error');
      push(data.warnings, 'warning');
    } catch {
      /* offline / server error: skip server lint */
    }

    const declared = new Set(DECLARED);
    ctx.contextVars.forEach((v) => v && declared.add(v));
    const warned = new Set<string>();
    const varRe = /ctx\.var\s*\(\s*['"]([^'"]+)['"]\s*\)/g;
    let m: RegExpExecArray | null;
    while ((m = varRe.exec(doc)) !== null) {
      if (!declared.has(m[1]) && !warned.has(m[1])) {
        warned.add(m[1]);
        diagnostics.push({ from: m.index, to: m.index + m[0].length, severity: 'warning', message: `Undeclared variable: ${m[1]}` });
      }
    }
    const listRe = /(?:listMatch|ctx\.listMatch)\s*\(\s*['"]([^'"]+)['"]/g;
    while ((m = listRe.exec(doc)) !== null) {
      if (ctx.listNames.length && !ctx.listNames.includes(m[1])) {
        diagnostics.push({ from: m.index, to: m.index + m[0].length, severity: 'warning', message: `List not found: ${m[1]}` });
      }
    }
    const cumRe = /(?:getCumulative|ctx\.getCumulative)\s*\(\s*['"]([^'"]+)['"]/g;
    while ((m = cumRe.exec(doc)) !== null) {
      if (ctx.cumNames.length && !ctx.cumNames.includes(m[1])) {
        diagnostics.push({ from: m.index, to: m.index + m[0].length, severity: 'warning', message: `Cumulative not found: ${m[1]}` });
      }
    }
  } else if (ctx.runtime === 'json') {
    try {
      JSON.parse(doc);
    } catch (e) {
      const msg = (e as Error).message;
      const posMatch = msg.match(/position (\d+)/);
      const pos = posMatch ? Number(posMatch[1]) : 0;
      diagnostics.push({ from: Math.min(pos, view.state.doc.length), to: Math.min(pos + 1, view.state.doc.length), severity: 'error', message: msg });
    }
  }
  return diagnostics;
}
