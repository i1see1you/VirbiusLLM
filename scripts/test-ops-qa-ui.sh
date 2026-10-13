#!/bin/bash
# ============================================================
# E2E test: ops console QA reply UI (DOM-level, via jsdom)
#   Exercises the real panel wiring: nav/panel presence, create library,
#   open editor, add/save entries, client-side regex validation, publish+ack
#   polling, delete — all through ops.html's own JS (no API-only shortcuts).
#
# Prerequisites:
#   - virbius-control on http://127.0.0.1:8080 (serves ops.html + /api)
#   - node + npm (jsdom is installed into a temp dir on first run)
#   Start services:  VIRBIUS_QA_SYNC_POLL_SEC=1 bash scripts/run-local.sh
# ============================================================

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTROL="http://127.0.0.1:8080"

RED='\033[31m'; GREEN='\033[32m'; YELLOW='\033[33m'; CYAN='\033[36m'; BLUE='\033[34m'; NC='\033[0m'
log_info() { echo -e "  ${CYAN}INFO${NC}: $1"; }
log_skip() { echo -e "  ${YELLOW}SKIP${NC}: $1"; }
log_step() { echo -e "\n${BLUE}=== $1 ===${NC}"; }

log_step "Step 0: Prerequisites"

if ! curl -sf -m 3 "${CONTROL}/api/v1/health" >/dev/null 2>&1; then
    echo -e "${RED}virbius-control not reachable at ${CONTROL} — start with: bash scripts/run-local.sh${NC}"
    exit 1
fi
log_info "control reachable"

if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
    log_skip "node/npm not found; skipping ops QA UI DOM test"
    exit 0
fi

WORK="$(mktemp -d)"
cleanup() {
    rm -rf "${WORK}"
    if command -v redis-cli >/dev/null 2>&1 && redis-cli ping >/dev/null 2>&1; then
        redis-cli del "virbius:config:qa:default" >/dev/null 2>&1 || true
        for k in $(redis-cli keys "virbius:artifacts:qa:default:*" 2>/dev/null); do
            redis-cli del "$k" >/dev/null 2>&1 || true
        done
    fi
}
trap cleanup EXIT

if [ ! -d "${WORK}/node_modules/jsdom" ]; then
    log_info "installing jsdom into ${WORK} ..."
    (cd "${WORK}" && npm i --silent jsdom@24 >/dev/null 2>&1) || {
        log_skip "jsdom install failed (offline?); skipping ops QA UI DOM test"
        exit 0
    }
fi

cat > "${WORK}/harness.js" <<'HARNESS'
const { JSDOM, VirtualConsole } = require('jsdom');
const PAGE_URL = process.env.OPS_URL || 'http://127.0.0.1:8080/ops.html';
const ORIGIN = 'http://127.0.0.1:8080';
const NAME = 'ui-jsdom-' + Date.now();
const nodeFetch = globalThis.fetch;
const virtualConsole = new VirtualConsole();
virtualConsole.on('jsdomError', () => {});
const sleep = ms => new Promise(r => setTimeout(r, ms));
const results = [];
const check = (name, ok, detail) => { results.push({ name, ok }); console.log(`${ok ? 'PASS' : 'FAIL'}: ${name}${ok ? '' : ' — ' + (detail || '')}`); };

async function main() {
  const dom = await JSDOM.fromURL(PAGE_URL, {
    runScripts: 'dangerously', resources: 'usable', pretendToBeVisual: true, virtualConsole,
    beforeParse(window) {
      window.fetch = (input, init) => nodeFetch(typeof input === 'string' ? new globalThis.URL(input, ORIGIN).toString() : input, init);
      window.confirm = () => true;
      window.Element.prototype.scrollIntoView = function () {};
    }
  });
  const { window } = dom;
  const doc = window.document;
  await new Promise((res, rej) => { window.addEventListener('load', res); setTimeout(() => rej(new Error('page load timeout')), 20000); });
  await sleep(500);
  const waitFor = async (fn, ms = 15000, step = 200) => { const end = Date.now() + ms; while (Date.now() < end) { try { if (fn()) return true; } catch (_) {} await sleep(step); } return false; };
  const logText = () => doc.getElementById('log') ? doc.getElementById('log').textContent : '';
  const rows = () => Array.from(doc.querySelectorAll('#qaTable tbody tr'));
  const rowFor = n => rows().find(tr => tr.textContent.includes(n));
  const setVal = (el, v) => { el.value = v; el.dispatchEvent(new window.Event('input', { bubbles: true })); el.dispatchEvent(new window.Event('change', { bubbles: true })); };

  check('QA panel present', !!doc.getElementById('panel-qa'));
  check('QA nav present', !!doc.querySelector('.nav-item[data-tab="qa"]'));
  check('ops-qa.js loaded (loadQa fn)', typeof window.loadQa === 'function');
  await window.loadQa();

  doc.getElementById('qaName').value = NAME;
  doc.getElementById('qaDesc').value = 'jsdom harness';
  doc.getElementById('qaRollout').value = 'full';
  doc.getElementById('btnQaCreate').click();
  const created = await waitFor(() => !!rowFor(NAME), 20000);
  check('create library via UI', created, logText());
  if (!created) return finish(dom);
  const qaId = rowFor(NAME).cells[1].textContent.trim();
  check('library has qa_id', !!qaId, qaId);

  await window.openQa(qaId);
  const editorOpen = doc.getElementById('qaEditor').style.display !== 'none';
  await sleep(200);
  check('editor opened', editorOpen);

  doc.getElementById('btnQaEntryAdd').click();
  await sleep(50);
  const trow = doc.querySelector('#qaEntriesTable tbody tr');
  setVal(trow.querySelector('.qa-entry-id'), 'e1');
  setVal(trow.querySelector('.qa-question'), '什么是核心价值观');
  setVal(trow.querySelector('.qa-answer'), '富强民主文明和谐');
  setVal(trow.querySelector('.qa-mode'), 'exact');
  check('dirty hint shows unsaved', /未保存|unsaved/.test(doc.getElementById('qaEntriesHint').textContent), doc.getElementById('qaEntriesHint').textContent);
  doc.getElementById('btnQaEntriesSave').click();
  const saved = await waitFor(() => !/未保存|unsaved/.test(doc.getElementById('qaEntriesHint').textContent), 15000);
  check('save entries (dirty cleared)', saved, logText());

  const trow2 = doc.querySelector('#qaEntriesTable tbody tr');
  setVal(trow2.querySelector('.qa-mode'), 'regex');
  setVal(trow2.querySelector('.qa-question'), '(?=abc)');
  doc.getElementById('btnQaEntriesSave').click();
  await sleep(600);
  const errLogged = /unsupported|正则|环视/i.test(logText());
  const stillDirty = /未保存|unsaved/.test(doc.getElementById('qaEntriesHint').textContent);
  check('client rejects lookaround regex (stays dirty)', errLogged && stillDirty, JSON.stringify({ errLogged, stillDirty, log: logText().slice(0, 120) }));
  setVal(trow2.querySelector('.qa-question'), '什么是核心价值观');
  setVal(trow2.querySelector('.qa-mode'), 'exact');
  doc.getElementById('btnQaEntriesSave').click();
  await waitFor(() => !/未保存|unsaved/.test(doc.getElementById('qaEntriesHint').textContent), 15000);

  if (typeof window.showPanel === 'function') window.showPanel('qa');
  await sleep(300);
  doc.getElementById('btnQaPublish').click();
  const acked = await waitFor(() => /acked/.test(doc.getElementById('qaJobList').textContent), 90000, 1000);
  check('publish → job acked shown', acked, doc.getElementById('qaJobList').textContent.slice(0, 200));
  check('ack panel shows revision', /revision/.test(doc.getElementById('qaAckPanel').textContent), doc.getElementById('qaAckPanel').textContent.slice(0, 120));

  rowFor(NAME).querySelector('button[data-del]').click();
  const gone = await waitFor(() => !rowFor(NAME), 15000);
  check('delete library via UI', gone, logText());

  return finish(dom);
}

function finish(dom) {
  const failed = results.filter(r => !r.ok);
  console.log(`OPS-QA-UI: ${results.length - failed.length} passed, ${failed.length} failed`);
  try { dom.window.close(); } catch (_) {}
  process.exit(failed.length ? 1 : 0);
}

main().catch(e => { console.error('harness error:', (e && e.stack) || e); process.exit(2); });
HARNESS

log_step "Step 1: Run DOM-level UI harness"
set +e
(cd "${WORK}" && OPS_URL="${CONTROL}/ops.html" node harness.js)
RC=$?
set -e

if [ "${RC}" -eq 0 ]; then
    echo -e "\n${GREEN}ops QA UI E2E: passed${NC}"
else
    echo -e "\n${RED}ops QA UI E2E: failed (rc=${RC})${NC}"
    exit 1
fi
