    const logEl = document.getElementById('log');
    let currentLayer = 'cloud';
    let selectedRuleId = null;
    let isNewRule = false;
    let editRuleMeta = null;
    let contextVars = [];
    let gatewayRoutes = [];
    let sceneEntries = [];
    let sceneRegistryHydrating = false;
    let selectedCumulativeName = null;
    let isNewCumulative = false;
    let cumulativeRows = [];
    let rolloutRuleMeta = null;
    let rolloutAllRules = [];
    let rolloutCanaryLadder = [5, 20, 50, 100];
    let rolloutRefreshTimer = null;
    let rolloutRefreshBusy = false;
    const ROLLOUT_REFRESH_MS = 5000;
    let conditionLeaves = [];
    let recipeCatalog = [];

    const FLOW_STEPS = ['draft', 'dry_run', 'canary', 'full'];

    const LAYER_RUNTIMES = {
      cloud: ['prompt', 'groovy'],
      gateway: ['lua'],
      edge: ['lua-dsl', 'dlp-dsl']
    };

    function field(obj, ...keys) {
      if (!obj) return undefined;
      for (const k of keys) {
        if (obj[k] !== undefined && obj[k] !== null) return obj[k];
      }
      return undefined;
    }

    function esc(s) { return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }
    function escAttr(s) { return String(s).replace(/"/g, '&quot;'); }

    function ruleStatusTag(st) {
      const s = st || 'draft';
      const cls = s === 'disabled' ? 'disabled' : (s === 'draft' ? 'draft' : '');
      return `<span class="tag ${cls}">${esc(s)}</span>`;
    }

    function inExecutionPlane(st) {
      return st === 'dry_run' || st === 'canary' || st === 'full';
    }

    function tenant() { return document.getElementById('tenantId').value.trim() || 'default'; }
    function apiKey() {
      const el = document.getElementById('apiKey');
      return el ? el.value.trim() : '';
    }
    function authHeaders(extra) {
      const h = { 'Content-Type': 'application/json', ...(extra || {}) };
      const key = apiKey();
      if (key) h['Authorization'] = 'Bearer ' + key;
      return h;
    }
    function adminFetch(url, opts = {}) {
      return fetch(url, { ...opts, headers: authHeaders(opts.headers) }).then(async res => {
        const j = await res.json();
        if (j.code !== 0) throw new Error(j.message || ('HTTP ' + res.status));
        return j.data;
      });
    }
    function adminRoot(path, opts = {}) {
      return adminFetch('/api/v1/admin' + path, opts);
    }
    function bundleId() { return document.getElementById('bundleId').value.trim() || 'poc-default'; }
    function bundleVer() { return document.getElementById('bundleVer').value.trim() || '0.1.0'; }
    function syncTopbarBundle() {
      const bid = document.getElementById('topBundleId');
      const bver = document.getElementById('topBundleVer');
      if (bid) bid.textContent = bundleId();
      if (bver) bver.textContent = bundleVer();
    }
    function admin(path, opts = {}) {
      const url = '/api/v1/admin/tenants/' + encodeURIComponent(tenant()) + path;
      return adminFetch(url, opts);
    }
    function log(x, level) {
      const msg = typeof x === 'string' ? x : JSON.stringify(x, null, 2);
      logEl.textContent = msg;
      logEl.className = 'log-' + (level || 'info');
    }

    const DIM_HINT = {
      keyword: '关键字', user_id: '用户', device_id: '设备', ip_cidr: 'IP/CIDR'
    };

    function formatListDimension(dim) {
      if (!dim) return '—';
      if (String(dim).startsWith('var:')) return 'var(' + dim.slice(4) + ')';
      const label = DIM_HINT[dim] || dim;
      return label + ' `' + dim + '`';
    }

    function inferListStorage(dim, storage) {
      if (storage) return String(storage).toLowerCase();
      const d = (dim || '').toLowerCase();
      if (d === 'keyword' || d === 'ip_cidr' || d === 'ip' || d === 'content') return 'memory';
      if (d === 'user_id' || d === 'device_id' || d === 'var' || d.startsWith('var:')) return 'redis';
      return 'memory';
    }

    function formatListStorage(storage) {
      return storage === 'redis' ? 'Redis' : '内存';
    }

    function isListEntryActive(expiresAt) {
      if (!expiresAt) return true;
      const t = Date.parse(String(expiresAt));
      return !Number.isNaN(t) && t > Date.now();
    }

    function countActiveListEntries(entries) {
      if (!Array.isArray(entries)) return 0;
      return entries.filter(e => isListEntryActive(field(e, 'expires_at', 'expiresAt'))).length;
    }

    function listSyncHint(data) {
      if (data && data.engine_reload) return '（gateway + Engine 已同步）';
      if (data && data.refreshed) return '（gateway 产物已刷新）';
      return '';
    }

    function fmtTime(s) {
      if (!s) return '—';
      return String(s).replace('T', ' ').slice(0, 19);
    }

    function fmtTimeAgo(s) {
      if (!s) return '—';
      const t = Date.parse(String(s));
      if (Number.isNaN(t)) return '—';
      const diff = Date.now() - t;
      if (diff < 60000) return '刚刚';
      if (diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前';
      if (diff < 86400000) return Math.floor(diff / 3600000) + ' 小时前';
      return Math.floor(diff / 86400000) + ' 天前';
    }

    const LAYER_LABELS = {
      gateway: '网关 Gateway',
      cloud: '引擎 Engine',
      edge: '端 Edge'
    };

    function formatMatchHeaders(h) {
      if (!h || typeof h !== 'object' || !Object.keys(h).length) return '—';
      return Object.entries(h).map(([k, v]) => `${k}=${v}`).join(', ');
    }

    function parseMethodsText(s) {
      return String(s || 'POST').split(/[,，\s]+/).map(x => x.trim().toUpperCase()).filter(Boolean);
    }

    function formatBindScope(scope) {
      const s = scope || {};
      const bs = s.bind_scope || 'global';
      const ref = s.bind_ref || {};
      if (bs === 'route') {
        const scenes = Array.isArray(ref.scenes) ? ref.scenes.join(', ') : '';
        return scenes ? `route:${scenes}` : 'route';
      }
      if (bs === 'service') {
        const ids = Array.isArray(ref.app_ids) ? ref.app_ids.join(', ') : '';
        return ids ? `service:${ids}` : 'service';
      }
      return 'global';
    }
