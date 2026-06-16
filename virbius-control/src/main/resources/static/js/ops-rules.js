    const SIM_FIXTURE_PRESETS = {
      clinical_chat: {
        route_uri: '/v1/chat/completions',
        headers: { 'X-App-Id': 'medical-prod' },
        query: { mode: 'clinical' },
        content: 'test prompt'
      },
      beta_chat: {
        route_uri: '/v1/chat/completions',
        headers: { 'X-App-Id': 'beta' },
        query: {},
        content: 'hello'
      },
      l3_prior: {
        route_uri: '/v1/chat/completions',
        headers: { 'X-App-Id': 'medical-prod' },
        query: { mode: 'clinical' },
        content: 'test prompt',
        prior_signals: [
          {
            rule_id: 'gateway_deny_1',
            intent_action: 'deny',
            risk_score: 100,
            reason_code: 'DENY_KEYWORD'
          }
        ]
      },
      cum_over_limit: {
        route_uri: '/v1/chat/completions',
        headers: { 'X-App-Id': 'medical-prod' },
        query: {},
        content: 'hello',
        overrides: { cumulative: { user_req_1h: 150 } }
      },
      force_list_hit: {
        route_uri: '/v1/chat/completions',
        headers: { 'X-App-Id': 'beta' },
        query: {},
        content: 'hello',
        overrides: { force_list_hit: true }
      }
    };

    function formatSimFixture(obj) {
      return JSON.stringify(obj, null, 2);
    }

    function resetSimFixture(presetKey) {
      const key = presetKey && SIM_FIXTURE_PRESETS[presetKey] ? presetKey : 'clinical_chat';
      document.getElementById('fSimFixture').value = formatSimFixture(SIM_FIXTURE_PRESETS[key]);
      document.getElementById('fSimFixturePreset').value = key;
      const errEl = document.getElementById('simFixtureErr');
      errEl.style.display = 'none';
      errEl.textContent = '';
    }

    function readSimFixture() {
      const raw = document.getElementById('fSimFixture').value.trim();
      const errEl = document.getElementById('simFixtureErr');
      if (!raw) {
        errEl.style.display = '';
        errEl.textContent = 'fixture JSON 不能为空';
        throw new Error(errEl.textContent);
      }
      try {
        const obj = JSON.parse(raw);
        if (obj === null || typeof obj !== 'object' || Array.isArray(obj)) {
          throw new Error('根节点须为 JSON 对象');
        }
        errEl.style.display = 'none';
        errEl.textContent = '';
        return obj;
      } catch (e) {
        errEl.style.display = '';
        errEl.textContent = 'fixture JSON 无效：' + e.message;
        throw new Error(errEl.textContent);
      }
    }

    function ensureSimFixtureEditor() {
      const ta = document.getElementById('fSimFixture');
      if (!ta.value.trim()) resetSimFixture();
    }

    function defaultRuleBody(layer, runtime) {
      if (runtime === 'groovy') {
        return `def decide(ctx) {
  return ctx.listMatch('deny_keyword')
}`;
      }
      if (runtime === 'lua') {
        return `function decide(ctx)
  return listMatch('deny_keyword', ctx.content)
end`;
      }
      if (runtime === 'prompt') {
        return '阻断越狱、DAN、ignore previous 等提示词注入与指令劫持。';
      }
      if (runtime === 'lua-dsl') {
        return JSON.stringify(defaultEdgeBody(), null, 2);
      }
      if (runtime === 'dlp-dsl') {
        return JSON.stringify(defaultDlpBody(), null, 2);
      }
      return JSON.stringify({ list_type: 'deny', keywords: [] }, null, 2);
    }

    function fillRuntimeSelect(layer, selected) {
      const sel = document.getElementById('fRuntime');
      const runtimes = LAYER_RUNTIMES[layer] || ['groovy'];
      sel.innerHTML = runtimes.map(r => `<option value="${r}">${r}</option>`).join('');
      if (selected && runtimes.includes(selected)) sel.value = selected;
    }

    function setRuleEditorReadOnly(disabled) {
      const ro = !!disabled;
      ['fReason', 'fRisk', 'fIntent', 'fBody', 'fRuleId', 'fRuntime',
        'fBindScope', 'fBindScenes', 'fBindAppIds', 'fEdgeListType', 'fEdgeKeywords',
        'fDlpEntityType', 'fDlpPattern', 'fDlpMaskTemplate', 'fDlpPriority',
        'fIsAsync', 'fActionType', 'fActionStreamKey', 'fActionWebhookUrl', 'fActionMessage', 'fAsyncActionConfig',
        'fBindUpstream', 'fBindConsumer', 'fBindApiKeyGroup',
        'wPattern', 'wListName', 'wCumName', 'wCompare', 'wThreshold', 'btnGenScript'].forEach(id => {
        const el = document.getElementById(id);
        if (el) el.disabled = ro;
      });
    }

    function setRuleEditorStatusUi(status) {
      const st = status || 'draft';
      const isDisabled = st === 'disabled';
      const isDraft = st === 'draft';
      const exec = inExecutionPlane(st);
      setRuleEditorReadOnly(isDisabled);
      syncDlpIntentReadonly();
      document.getElementById('btnSaveRule').style.display = (isDisabled || exec) ? 'none' : '';
      document.getElementById('btnSaveRule').textContent =
        isNewRule ? '创建规则' : '保存规则（新 revision）';
      document.getElementById('btnActivateRule').style.display = (!isNewRule && isDraft) ? '' : 'none';
      document.getElementById('btnDisableRule').style.display = (!isNewRule && !isDisabled) ? '' : 'none';
      document.getElementById('btnEnableRule').style.display = (!isNewRule && isDisabled) ? '' : 'none';
      setRuleEditorEnforceUi(exec);
    }

    function setRuleEditorEnforceUi(exec) {
      const showRuntime = !isNewRule && selectedRuleId && exec;
      document.getElementById('enforceWrap').style.display = showRuntime ? '' : 'none';
      document.getElementById('canaryWrap').style.display = showRuntime ? '' : 'none';

    }

    function setRuleEditorCloudOnly(layer) {
      const st = editRuleMeta && editRuleMeta.rollout_state;
      setRuleEditorEnforceUi(inExecutionPlane(st));
    }

    function isScriptRuntime(runtime) {
      return runtime === 'lua' || runtime === 'groovy';
    }

    function isPromptRuntime(runtime) {
      return runtime === 'prompt';
    }

    function isEdgeDslRuntime(runtime) {
      return runtime === 'lua-dsl';
    }

    function isDlpRuntime(runtime) {
      return runtime === 'dlp-dsl';
    }

    function isEdgeFormRuntime(runtime) {
      return isEdgeDslRuntime(runtime) || isDlpRuntime(runtime);
    }

    function parseEdgeKeywords(text) {
      return String(text || '').split(/[\n,]+/).map(s => s.trim()).filter(Boolean);
    }

    function formatEdgeKeywords(keywords) {
      return Array.isArray(keywords) ? keywords.join('\n') : '';
    }

    function defaultEdgeBody() {
      return { list_type: 'deny', keywords: [] };
    }

    function loadEdgeBodyIntoForm(body) {
      const obj = body && typeof body === 'object' ? body : defaultEdgeBody();
      document.getElementById('fEdgeListType').value = obj.list_type === 'allow' ? 'allow' : 'deny';
      document.getElementById('fEdgeKeywords').value = formatEdgeKeywords(obj.keywords);
    }

    function edgeBodyFromForm() {
      return {
        list_type: document.getElementById('fEdgeListType').value || 'deny',
        keywords: parseEdgeKeywords(document.getElementById('fEdgeKeywords').value)
      };
    }

    function defaultDlpBody() {
      return { entity_type: 'phone_cn', priority: 0 };
    }

    function loadDlpBodyIntoForm(body) {
      const obj = body && typeof body === 'object' ? body : defaultDlpBody();
      document.getElementById('fDlpEntityType').value = obj.entity_type || 'phone_cn';
      document.getElementById('fDlpPattern').value = obj.pattern || '';
      document.getElementById('fDlpMaskTemplate').value = obj.mask_template || '';
      document.getElementById('fDlpPriority').value = obj.priority != null ? obj.priority : 0;
      syncDlpPatternUi();
    }

    function dlpBodyFromForm() {
      const entityType = document.getElementById('fDlpEntityType').value || 'phone_cn';
      const body = { entity_type: entityType };
      const pattern = document.getElementById('fDlpPattern').value.trim();
      const mask = document.getElementById('fDlpMaskTemplate').value.trim();
      const priority = Number(document.getElementById('fDlpPriority').value);
      if (entityType === 'custom_regex') body.pattern = pattern;
      else if (pattern) body.pattern = pattern;
      if (mask) body.mask_template = mask;
      if (!Number.isNaN(priority) && priority !== 0) body.priority = priority;
      return body;
    }

    function isDlpBody(body) {
      if (!body || typeof body !== 'object' || Array.isArray(body)) return false;
      return typeof body.entity_type === 'string'
        && ['idcard_cn', 'phone_cn', 'email', 'bank_card_cn', 'custom_regex'].includes(body.entity_type);
    }

    function syncDlpPatternUi() {
      const show = document.getElementById('fDlpEntityType').value === 'custom_regex';
      document.getElementById('fDlpPatternWrap').style.display = show ? '' : 'none';
    }

    function syncAsyncUi() {
      const isAsync = document.getElementById('fIsAsync').checked;
      document.getElementById('asyncConfigRow').style.display = isAsync ? '' : 'none';
      if (isAsync) {
        document.getElementById('fIntent').value = 'allow';
        renderAsyncVarPalette();
        updateAsyncPreview();
      }
      syncDlpIntentReadonly();
    }

    function syncAsyncLayerUi(runtime) {
      const layer = isNewRule ? currentLayer : (editRuleMeta && editRuleMeta.layer) || currentLayer;
      const showAsync = layer === 'cloud' && (runtime === 'groovy' || runtime === 'prompt');
      document.getElementById('asyncWrap').style.display = showAsync ? '' : 'none';
      if (!showAsync) {
        document.getElementById('fIsAsync').checked = false;
        document.getElementById('asyncConfigRow').style.display = 'none';
      }
    }

    function syncDlpIntentReadonly() {
      const dlp = isDlpRuntime(currentEditorRuntime());
      const disabledRule = editRuleMeta && editRuleMeta.rollout_state === 'disabled';
      if (dlp) {
        document.getElementById('fRisk').value = 0;
        document.getElementById('fIntent').value = 'allow';
      }
      if (!disabledRule) {
        document.getElementById('fRisk').readOnly = dlp;
        document.getElementById('fIntent').disabled = dlp || document.getElementById('fIsAsync').checked;
      }
    }

    function syncActionTypeUi() {
      const type = document.getElementById('fActionType').value;
      document.getElementById('actionStreamKeyWrap').style.display = type === 'redis_stream' ? '' : 'none';
      document.getElementById('actionWebhookUrlWrap').style.display = type === 'webhook' ? '' : 'none';
    }

    function renderAsyncVarPalette() {
      const vars = [
        { label: 'rule_id', desc: '规则ID' },
        { label: 'rule_revision', desc: '规则版本' },
        { label: 'tenant_id', desc: '租户ID' },
        { label: 'reason_code', desc: '处置码' },
        { label: 'intent_action', desc: '意图' },
        { label: 'risk_score', desc: '风险分' },
        { label: 'hit_at', desc: '命中时间' },
        { label: 'user_id', desc: '用户ID' },
        { label: 'device_id', desc: '设备ID' },
        { label: 'client_ip', desc: '客户端IP' },
        { label: 'session_id', desc: '会话ID' },
        { label: 'content', desc: '内容' },
        { label: 'scene', desc: '场景' },
        { label: 'route_uri', desc: '路由URI' }
      ];
      (contextVars || []).forEach(v => {
        if (v.logical) vars.push({ label: 'vars.' + v.logical, desc: 'vars.' + v.logical });
      });
      const palette = document.getElementById('asyncVarPalette');
      palette.innerHTML = vars.map(v =>
        '<span class="async-var-chip" data-var="\{\{' + escAttr(v.label) + '}}" title="' + escAttr(v.desc) + '">\{\{' + esc(v.label) + '}}</span>'
      ).join('');
      palette.querySelectorAll('.async-var-chip').forEach(chip => {
        chip.onclick = () => {
          const ta = document.getElementById('fActionMessage');
          const text = chip.dataset.var;
          const pos = ta.selectionStart || ta.value.length;
          ta.value = ta.value.slice(0, pos) + text + ta.value.slice(pos);
          ta.focus();
          ta.selectionStart = ta.selectionEnd = pos + text.length;
          updateAsyncPreview();
        };
      });
    }

    function updateAsyncPreview() {
      const pre = document.getElementById('asyncActionPreview');
      try {
        const msg = JSON.parse(document.getElementById('fActionMessage').value || '{}');
        pre.textContent = JSON.stringify(msg, null, 2);
        pre.style.color = '#334155';
      } catch {
        pre.textContent = '（JSON 无效）';
        pre.style.color = '#991b1b';
      }
    }

    function buildAsyncActionConfig() {
      const type = document.getElementById('fActionType').value;
      let messageStr = document.getElementById('fActionMessage').value.trim();
      let message;
      try { message = JSON.parse(messageStr); } catch { message = messageStr || {}; }
      const cfg = { type };
      if (type === 'redis_stream') {
        const key = document.getElementById('fActionStreamKey').value.trim();
        if (key) cfg.stream_key = key;
        cfg.message = message;
      } else {
        const url = document.getElementById('fActionWebhookUrl').value.trim();
        if (url) cfg.url = url;
        cfg.body = message;
      }
      return JSON.stringify(cfg);
    }

    function loadAsyncActionConfig(jsonStr) {
      if (!jsonStr) {
        document.getElementById('fActionType').value = 'redis_stream';
        document.getElementById('fActionStreamKey').value = '';
        document.getElementById('fActionWebhookUrl').value = '';
        document.getElementById('fActionMessage').value = '';
        syncActionTypeUi();
        return;
      }
      try {
        const cfg = JSON.parse(jsonStr);
        document.getElementById('fActionType').value = cfg.type || 'redis_stream';
        syncActionTypeUi();
        if (cfg.type === 'webhook') {
          document.getElementById('fActionWebhookUrl').value = cfg.url || '';
          document.getElementById('fActionMessage').value = cfg.body ? JSON.stringify(cfg.body, null, 2) : '';
        } else {
          document.getElementById('fActionStreamKey').value = cfg.stream_key || '';
          document.getElementById('fActionMessage').value = cfg.message ? JSON.stringify(cfg.message, null, 2) : '';
        }
      } catch {
        document.getElementById('fActionType').value = 'redis_stream';
        document.getElementById('fActionStreamKey').value = '';
        document.getElementById('fActionMessage').value = '';
        syncActionTypeUi();
      }
      updateAsyncPreview();
    }

    function isEdgeKeywordBody(body) {
      if (!body || typeof body !== 'object' || Array.isArray(body)) return false;
      const lt = body.list_type;
      return (lt === 'deny' || lt === 'allow') && (body.keywords == null || Array.isArray(body.keywords));
    }

    function currentEditorRuntime() {
      return isNewRule ? document.getElementById('fRuntime').value : (editRuleMeta && editRuleMeta.runtime) || '';
    }

    function openNewRuleEditor() {
      isNewRule = true;
      selectedRuleId = null;
      editRuleMeta = { layer: currentLayer };
      document.getElementById('ruleEditor').style.display = 'block';
      document.getElementById('editTitle').textContent = '新建';
      document.getElementById('editRuleId').textContent = '';
      document.getElementById('newRuleRow').style.display = '';
      document.getElementById('fRuleId').value = '';
      fillRuntimeSelect(currentLayer, LAYER_RUNTIMES[currentLayer][0]);
      document.getElementById('fReason').value = 'CUSTOM_RULE';
      document.getElementById('fRisk').value = 100;
      document.getElementById('fIntent').value = 'deny';

      document.getElementById('fIsAsync').checked = false;
      loadAsyncActionConfig('');
      syncAsyncUi();
      const rt = document.getElementById('fRuntime').value;
      document.getElementById('fBody').value = defaultRuleBody(currentLayer, rt);
      if (isScriptRuntime(rt)) {
        conditionLeaves = [{ type: 'list_match', list_name: 'deny_keyword', value_source: 'content' }];
        document.getElementById('fEditorMode').value = 'simple';
        renderConditionLeaves();
        syncEditorModeUi();
      } else if (isEdgeDslRuntime(rt)) {
        conditionLeaves = [];
        document.getElementById('fEditorMode').value = 'simple';
        loadEdgeBodyIntoForm(defaultEdgeBody());
        syncEditorModeUi();
      } else if (isDlpRuntime(rt)) {
        conditionLeaves = [];
        document.getElementById('fEditorMode').value = 'simple';
        loadDlpBodyIntoForm(defaultDlpBody());
        syncEditorModeUi();
        syncDlpIntentReadonly();
      } else {
        conditionLeaves = [];
        document.getElementById('fEditorMode').value = 'advanced';
      }
      setRuleEditorCloudOnly(currentLayer);
      setRuleEditorStatusUi('draft');
      document.getElementById('fRuntime').onchange = () => {
        const nextRt = document.getElementById('fRuntime').value;
        document.getElementById('fBody').value = defaultRuleBody(currentLayer, nextRt);
        if (isScriptRuntime(nextRt)) {
          if (!conditionLeaves.length) {
            conditionLeaves = [{ type: 'list_match', list_name: 'deny_keyword', value_source: 'content' }];
          }
          document.getElementById('fEditorMode').value = 'simple';
          renderConditionLeaves();
          syncEditorModeUi();
        } else if (isEdgeDslRuntime(nextRt)) {
          conditionLeaves = [];
          document.getElementById('fEditorMode').value = 'simple';
          loadEdgeBodyIntoForm(defaultEdgeBody());
          syncEditorModeUi();
        } else if (isDlpRuntime(nextRt)) {
          conditionLeaves = [];
          document.getElementById('fEditorMode').value = 'simple';
          loadDlpBodyIntoForm(defaultDlpBody());
          syncEditorModeUi();
          syncDlpIntentReadonly();
        } else {
          conditionLeaves = [];
          syncDlpIntentReadonly();
        }
        syncScriptAssistUi(nextRt);
        clearScriptValidateMsg();
      };
      syncScriptAssistUi(rt);
      resetSimFixture();
      clearScriptValidateMsg();
      loadBindUiFromScope({ bind_scope: 'global' });
    }

    const COMPARE_OP_SYMBOL = { gte: '>=', gt: '>', lte: '<=', lt: '<', eq: '==' };

    function fillWizardListSelect(selected) {
      const sel = document.getElementById('wListName');
      const names = listCatalog.length ? listCatalog : ['deny_keyword'];
      sel.innerHTML = names.map(n => `<option value="${escAttr(n)}">${esc(n)}</option>`).join('');
      if (selected && names.includes(selected)) sel.value = selected;
    }

    function fillWizardCumSelect(selected) {
      const sel = document.getElementById('wCumName');
      const names = cumulativeRows.map(c => field(c, 'cumulative_name', 'cumulativeName')).filter(Boolean);
      sel.innerHTML = names.length
        ? names.map(n => `<option value="${escAttr(n)}">${esc(n)}</option>`).join('')
        : '<option value="user_req_1h">user_req_1h</option>';
      if (selected && names.includes(selected)) sel.value = selected;
      else if (names.length) sel.value = names[0];
    }

    function syncWizardFields() {
      const pattern = document.getElementById('wPattern').value;
      document.getElementById('wListWrap').style.display = pattern === 'list' || pattern === 'l3' ? '' : 'none';
      document.getElementById('wCumWrap').style.display = pattern === 'cumulative' ? '' : 'none';
      const l3Opt = document.querySelector('#wPattern option[value="l3"]');
      if (l3Opt) l3Opt.disabled = currentLayer !== 'cloud' || document.getElementById('fRuntime').value !== 'groovy';
      if (pattern === 'l3' && l3Opt && l3Opt.disabled) document.getElementById('wPattern').value = 'list';
    }

    function generateScriptFromWizard() {
      const runtime = document.getElementById('fRuntime').value;
      const pattern = document.getElementById('wPattern').value;
      const listName = document.getElementById('wListName').value || 'deny_keyword';
      const cumName = document.getElementById('wCumName').value || 'user_req_1h';
      const op = COMPARE_OP_SYMBOL[document.getElementById('wCompare').value] || '>=';
      const threshold = Number(document.getElementById('wThreshold').value) || 120;
      let script = '';
      if (pattern === 'list') {
        script = runtime === 'lua'
          ? `function decide(ctx)\n  return listMatch('${listName}', ctx.content)\nend`
          : `def decide(ctx) {\n  return ctx.listMatch('${listName}')\n}`;
      } else if (pattern === 'cumulative') {
        script = runtime === 'lua'
          ? `function decide(ctx)\n  return getCumulative('${cumName}') ${op} ${threshold}\nend`
          : `def decide(ctx) {\n  return ctx.getCumulative('${cumName}') ${op} ${threshold}\n}`;
      } else if (pattern === 'l3') {
        script = `def decide(ctx) {\n  if (ctx.listMatch('${listName}')) return true\n  if (!ctx.wouldHitBlock()) return false\n  return true\n}`;
      }
      if (script) {
        document.getElementById('fBody').value = script;
        clearScriptValidateMsg();
      }
    }

    function scriptSnippet(kind) {
      const runtime = document.getElementById('fRuntime').value;
      const listName = document.getElementById('wListName').value || listCatalog[0] || 'deny_keyword';
      const cumName = document.getElementById('wCumName').value
        || field(cumulativeRows[0], 'cumulative_name', 'cumulativeName') || 'user_req_1h';
      const logical = contextVars[0]?.logical || 'app_id';
      if (kind === 'list') {
        return runtime === 'lua'
          ? `listMatch('${listName}', ctx.content)`
          : `ctx.listMatch('${listName}')`;
      }
      if (kind === 'cumulative') {
        return runtime === 'lua'
          ? `getCumulative('${cumName}') >= 120`
          : `ctx.getCumulative('${cumName}') >= 120`;
      }
      if (kind === 'l3') {
        return `if (ctx.listMatch('${listName}')) return true\n  if (!ctx.wouldHitBlock()) return false\n  return true`;
      }
      if (kind === 'var') {
        return runtime === 'lua'
          ? `ctx.var('${logical}') == 'sensitive'`
          : `ctx.var('${logical}') == 'sensitive'`;
      }
      return '';
    }

    function insertAtCursor(ta, text) {
      const start = ta.selectionStart;
      const end = ta.selectionEnd;
      ta.value = ta.value.slice(0, start) + text + ta.value.slice(end);
      ta.selectionStart = ta.selectionEnd = start + text.length;
      ta.focus();
    }

    function clearScriptValidateMsg() {
      const el = document.getElementById('scriptValidateMsg');
      el.textContent = '';
      el.className = 'hint';
    }

    function showScriptValidateResult(data) {
      const el = document.getElementById('scriptValidateMsg');
      const errs = data.errors || [];
      const warns = data.warnings || [];
      if (data.valid) {
        el.className = 'hint script-validate-ok';
        el.textContent = warns.length ? '校验通过（' + warns.join('；') + '）' : '校验通过';
      } else {
        el.className = 'hint script-validate-err';
        el.textContent = errs.join('；');
      }
    }

    async function validateScriptBody(showLog) {
      const runtime = isNewRule ? document.getElementById('fRuntime').value : editRuleMeta.runtime;
      const layer = isNewRule ? currentLayer : editRuleMeta.layer;
      let body = await resolveBodyForSave();
      const data = await admin('/rules/validate-script', {
        method: 'POST',
        body: JSON.stringify({ layer, runtime, body })
      });
      showScriptValidateResult(data);
      if (showLog) log(data);
      return data;
    }

    function isSimpleEditorMode() {
      return document.getElementById('fEditorMode').value === 'simple';
    }

    function readConditionPayload() {
      if (!conditionLeaves.length) return null;
      if (conditionLeaves.length === 1) return { ...conditionLeaves[0] };
      return { op: 'and', children: conditionLeaves.map(c => ({ ...c })) };
    }

    async function resolveBodyForSave() {
      const runtime = isNewRule ? document.getElementById('fRuntime').value : editRuleMeta.runtime;
      const layer = isNewRule ? currentLayer : editRuleMeta.layer;
      if (isPromptRuntime(runtime)) {
        try { return JSON.parse(document.getElementById('fBody').value); } catch { return document.getElementById('fBody').value; }
      }
      if (isEdgeDslRuntime(runtime)) {
        if (isSimpleEditorMode()) {
          return edgeBodyFromForm();
        }
        try { return JSON.parse(document.getElementById('fBody').value); } catch {
          throw new Error('body JSON 无效');
        }
      }
      if (isDlpRuntime(runtime)) {
        if (isSimpleEditorMode()) {
          return dlpBodyFromForm();
        }
        try { return JSON.parse(document.getElementById('fBody').value); } catch {
          throw new Error('body JSON 无效');
        }
      }
      if (isSimpleEditorMode() && conditionLeaves.length) {
        const data = await admin('/rules/compile-condition', {
          method: 'POST',
          body: JSON.stringify({ layer, runtime, condition: readConditionPayload() })
        });
        return data.script;
      }
      try { return JSON.parse(document.getElementById('fBody').value); } catch { return document.getElementById('fBody').value; }
    }

    function syncEditorModeUi() {
      const runtime = currentEditorRuntime();
      if (isPromptRuntime(runtime)) {
        document.getElementById('simpleConditionPanel').style.display = 'none';
        document.getElementById('edgeDslPanel').style.display = 'none';
        document.getElementById('fBody').style.display = '';
        updateRuleSummaryCard();
        return;
      }
      if (isEdgeDslRuntime(runtime)) {
        const simple = isSimpleEditorMode();
        document.getElementById('simpleConditionPanel').style.display = 'none';
        document.getElementById('edgeDslPanel').style.display = simple ? '' : 'none';
        document.getElementById('dlpDslPanel').style.display = 'none';
        document.getElementById('fBody').style.display = simple ? 'none' : '';
        document.getElementById('ruleSummaryCard').style.display = simple ? '' : 'none';
        if (simple) updateRuleSummaryCard();
        syncDlpIntentReadonly();
        return;
      }
      if (isDlpRuntime(runtime)) {
        const simple = isSimpleEditorMode();
        document.getElementById('simpleConditionPanel').style.display = 'none';
        document.getElementById('edgeDslPanel').style.display = 'none';
        document.getElementById('dlpDslPanel').style.display = simple ? '' : 'none';
        document.getElementById('fBody').style.display = simple ? 'none' : '';
        document.getElementById('ruleSummaryCard').style.display = simple ? '' : 'none';
        if (simple) updateRuleSummaryCard();
        syncDlpIntentReadonly();
        return;
      }
      document.getElementById('edgeDslPanel').style.display = 'none';
      document.getElementById('dlpDslPanel').style.display = 'none';
      syncDlpIntentReadonly();
      const simple = isSimpleEditorMode();
      document.getElementById('simpleConditionPanel').style.display = simple ? '' : 'none';
      document.getElementById('fBody').style.display = simple ? 'none' : '';
      document.getElementById('scriptWizardRow').style.display = simple ? 'none' : (document.getElementById('scriptWizardRow').style.display);
      document.getElementById('scriptSnippetRow').style.display = simple ? 'none' : '';
      if (simple) updateRuleSummaryCard();
    }

    function renderConditionLeaves() {
      const host = document.getElementById('conditionLeaves');
      host.innerHTML = '';
      conditionLeaves.forEach((leaf, idx) => {
        const row = document.createElement('div');
        row.className = 'cond-row';
        if (leaf.type === 'list_match') {
          row.innerHTML = `<span>名单</span>
            <select data-i="${idx}" data-f="list_name">${listOptions(leaf.list_name)}</select>
            <span>匹配</span><select data-i="${idx}" data-f="value_source">
              <option value="content">content</option>
              <option value="var:app_id">var:app_id</option></select>
            <button type="button" class="danger" data-del="${idx}">删</button>`;
          row.querySelector('[data-f="list_name"]').value = leaf.list_name || 'deny_keyword';
          row.querySelector('[data-f="value_source"]').value = leaf.value_source || 'content';
        } else if (leaf.type === 'cumulative') {
          row.innerHTML = `<span>累计</span>
            <select data-i="${idx}" data-f="cumulative_name">${cumOptions(leaf.cumulative_name)}</select>
            <select data-i="${idx}" data-f="compare">
              <option value="gte">≥</option><option value="gt">&gt;</option><option value="eq">=</option></select>
            <input data-i="${idx}" data-f="threshold" type="number" min="1" style="width:5rem" />
            <button type="button" class="danger" data-del="${idx}">删</button>`;
          row.querySelector('[data-f="cumulative_name"]').value = leaf.cumulative_name || 'user_req_1h';
          row.querySelector('[data-f="compare"]').value = leaf.compare || 'gte';
          row.querySelector('[data-f="threshold"]').value = leaf.threshold != null ? leaf.threshold : 120;
        }
        row.querySelectorAll('select,input').forEach(el => {
          el.onchange = () => {
            const i = Number(el.dataset.i);
            const f = el.dataset.f;
            conditionLeaves[i][f] = f === 'threshold' ? Number(el.value) : el.value;
            updateRuleSummaryCard();
          };
        });
        row.querySelector('[data-del]').onclick = () => {
          conditionLeaves.splice(idx, 1);
          renderConditionLeaves();
          updateRuleSummaryCard();
        };
        host.appendChild(row);
      });
    }

    function listOptions(selected) {
      const names = listCatalog.length ? listCatalog : ['deny_keyword'];
      return names.map(n => `<option value="${escAttr(n)}">${esc(n)}</option>`).join('');
    }

    function cumOptions(selected) {
      const names = cumulativeRows.map(c => field(c, 'cumulative_name', 'cumulativeName')).filter(Boolean);
      if (!names.length) names.push('user_req_1h');
      return names.map(n => `<option value="${escAttr(n)}">${esc(n)}</option>`).join('');
    }

    async function loadRecipesForLayer(layer) {
      const data = await admin('/rules/recipes?layer=' + encodeURIComponent(layer));
      recipeCatalog = data.recipes || [];
      const sel = document.getElementById('fRecipe');
      sel.innerHTML = '<option value="">— 选择 —</option>' +
        recipeCatalog.map(r => `<option value="${escAttr(r.recipe_id)}">${esc(r.label || r.recipe_id)}</option>`).join('');
    }

    function applyRecipe(recipeId) {
      const r = recipeCatalog.find(x => x.recipe_id === recipeId);
      if (!r) return;
      loadBindUiFromScope({ bind_scope: r.bind_scope, bind_ref: r.bind_ref || {} });
      const c = r.condition || {};
      if (c.op === 'and' && Array.isArray(c.children)) conditionLeaves = c.children.map(x => ({ ...x }));
      else conditionLeaves = [{ ...c }];
      document.getElementById('fRisk').value = r.risk_score != null ? r.risk_score : 100;
      document.getElementById('fIntent').value = r.intent_action || 'deny';
      document.getElementById('fEditorMode').value = 'simple';
      renderConditionLeaves();
      syncEditorModeUi();
      previewCompiledScript();
    }

    async function parseBodyIntoForm(layer, runtime, script) {
      const data = await admin('/rules/parse-condition', {
        method: 'POST',
        body: JSON.stringify({ layer, runtime, script })
      });
      if (data.parseable && data.condition) {
        document.getElementById('fEditorMode').value = 'simple';
        const c = data.condition;
        if (c.op === 'and' && Array.isArray(c.children)) conditionLeaves = c.children.map(x => ({ ...x }));
        else if (c.type) conditionLeaves = [{ ...c }];
        else conditionLeaves = [];
        renderConditionLeaves();
      } else {
        document.getElementById('fEditorMode').value = 'advanced';
        conditionLeaves = [];
      }
      syncEditorModeUi();
    }

    async function previewCompiledScript() {
      if (!isSimpleEditorMode() || !conditionLeaves.length) {
        document.getElementById('scriptPreview').style.display = 'none';
        return;
      }
      const runtime = isNewRule ? document.getElementById('fRuntime').value : editRuleMeta.runtime;
      const layer = isNewRule ? currentLayer : editRuleMeta.layer;
      const data = await admin('/rules/compile-condition', {
        method: 'POST',
        body: JSON.stringify({ layer, runtime, condition: readConditionPayload() })
      });
      const pre = document.getElementById('scriptPreview');
      pre.textContent = data.script || '';
      pre.style.display = '';
    }

    function updateRuleSummaryCard() {
      const card = document.getElementById('ruleSummaryCard');
      const runtime = currentEditorRuntime();
      const bs = document.getElementById('fBindScope').value;
      const intent = document.getElementById('fIntent').value;
      const risk = document.getElementById('fRisk').value;
      if (isPromptRuntime(runtime)) {
        const body = document.getElementById('fBody').value.trim();
        const preview = body.length > 48 ? body.slice(0, 48) + '…' : body;
        card.style.display = preview || bs ? '' : 'none';
        card.textContent = `bind=${bs} → 矩阵描述「${preview || '未填写'}」→ ${intent}@${risk}`;
        return;
      }
      if (isEdgeDslRuntime(runtime) && isSimpleEditorMode()) {
        const body = edgeBodyFromForm();
        const kw = body.keywords.length ? body.keywords.slice(0, 5).join('、') + (body.keywords.length > 5 ? '…' : '') : '（空）';
        card.style.display = '';
        card.textContent = `bind=${bs} → ${body.list_type} 关键词「${kw}」→ ${intent}@${risk}`;
        return;
      }
      if (isDlpRuntime(runtime) && isSimpleEditorMode()) {
        const body = dlpBodyFromForm();
        card.style.display = '';
        card.textContent = `bind=${bs} → DLP ${body.entity_type} → allow@0`;
        return;
      }
      const parts = conditionLeaves.map(l => {
        if (l.type === 'list_match') return `名单 ${l.list_name}`;
        if (l.type === 'cumulative') return `累计 ${l.cumulative_name} ${l.compare || '>='} ${l.threshold || 120}`;
        return '';
      }).filter(Boolean);
      card.style.display = '';
      card.textContent = `当 bind=${bs}${parts.length ? ' 且 ' + parts.join(' 且 ') : ''} → ${intent}@${risk}`;
    }

    async function runRuleSimulate() {
      const runtime = isNewRule ? document.getElementById('fRuntime').value : editRuleMeta.runtime;
      const layer = isNewRule ? currentLayer : editRuleMeta.layer;
      const ruleId = isNewRule ? (document.getElementById('fRuleId').value.trim() || 'draft-preview') : selectedRuleId;
      const fixture = readSimFixture();
      const body = await resolveBodyForSave();
      const payload = {
        editor_mode: isSimpleEditorMode() ? 'simple' : 'advanced',
        condition: isSimpleEditorMode() ? readConditionPayload() : null,
        rule: {
          rule_id: ruleId,
          bundle_id: bundleId(),
          layer,
          runtime,
          scope: buildRuleScope(),
          body,
          intent_action: document.getElementById('fIntent').value,
          risk_score: Number(document.getElementById('fRisk').value),
          reason_code: document.getElementById('fReason').value,
          rollout_state: editRuleMeta && editRuleMeta.rollout_state ? editRuleMeta.rollout_state : 'dry_run'
        },
        fixture,
        options: { cumulative_source: 'mock' }
      };
      const data = await admin('/rules/simulate', { method: 'POST', body: JSON.stringify(payload) });
      const panel = document.getElementById('simulatePanel');
      panel.style.display = '';
      const ul = document.getElementById('simTraceList');
      ul.innerHTML = (data.steps || []).map(s =>
        `<li class="${s.ok ? 'ok' : 'fail'}"><strong>${esc(s.id)}</strong> ${esc(JSON.stringify(s.detail))}</li>`
      ).join('');
      document.getElementById('simSummary').textContent = data.summary && data.summary.message ? data.summary.message : '';
      log(data);
    }

    let scriptAcState = { open: false, items: [], index: 0, replaceStart: 0, replaceEnd: 0 };

    function scriptAcClose() {
      scriptAcState.open = false;
      document.getElementById('scriptAcPopup').style.display = 'none';
    }

    function scriptAcItems(kind, filter) {
      const q = (filter || '').toLowerCase();
      const match = (name) => !q || name.toLowerCase().includes(q);
      if (kind === 'list') {
        return listCatalog.filter(match).map(n => ({ label: n, insert: n }));
      }
      if (kind === 'cum') {
        return cumulativeRows.map(c => field(c, 'cumulative_name', 'cumulativeName')).filter(Boolean)
          .filter(match).map(n => ({ label: n, insert: n }));
      }
      if (kind === 'var') {
        return contextVars.map(v => v.logical).filter(Boolean).filter(match)
          .map(n => ({ label: n, insert: n }));
      }
      return [];
    }

    function detectScriptAcTrigger(ta) {
      const head = ta.value.slice(0, ta.selectionStart);
      const triggers = [
        { re: /(?:^|[^\w])listMatch\s*\(\s*['"]?([^'"]*)$/, kind: 'list' },
        { re: /(?:^|[^\w])getCumulative\s*\(\s*['"]?([^'"]*)$/, kind: 'cum' },
        { re: /ctx\.getCumulative\s*\(\s*['"]?([^'"]*)$/, kind: 'cum' },
        { re: /ctx\.var\s*\(\s*['"]?([^'"]*)$/, kind: 'var' },
      ];
      for (const t of triggers) {
        const m = head.match(t.re);
        if (m) {
          return { kind: t.kind, filter: m[1] || '', replaceStart: head.length - (m[1] || '').length };
        }
      }
      return null;
    }

    function scriptAcRender(items) {
      const ul = document.getElementById('scriptAcPopup');
      if (!items.length) {
        scriptAcClose();
        return;
      }
      ul.innerHTML = items.map((it, i) =>
        `<li class="${i === scriptAcState.index ? 'sel' : ''}" data-i="${i}">${esc(it.label)}</li>`
      ).join('');
      ul.style.display = 'block';
      ul.style.top = '2rem';
      scriptAcState.open = true;
      scriptAcState.items = items;
      ul.querySelectorAll('li').forEach(li => {
        li.onmousedown = (e) => {
          e.preventDefault();
          scriptAcPick(Number(li.dataset.i));
        };
      });
    }

    function scriptAcOpen(ta, kind, filter, replaceStart) {
      const items = scriptAcItems(kind, filter);
      scriptAcState.index = 0;
      scriptAcState.replaceStart = replaceStart;
      scriptAcState.replaceEnd = ta.selectionStart;
      scriptAcRender(items);
    }

    function scriptAcPick(idx) {
      const ta = document.getElementById('fBody');
      const item = scriptAcState.items[idx];
      if (!item) return;
      const before = ta.value.slice(0, scriptAcState.replaceStart);
      const after = ta.value.slice(scriptAcState.replaceEnd);
      const quoted = before.endsWith("'") || before.endsWith('"');
      const quote = before.endsWith('"') ? '"' : "'";
      const insertText = quoted ? (item.insert + quote + ')') : ("'" + item.insert + "')");
      ta.value = before + insertText + after;
      const pos = before.length + insertText.length;
      ta.selectionStart = ta.selectionEnd = pos;
      scriptAcClose();
      ta.focus();
    }

    function initScriptAutocomplete() {
      const ta = document.getElementById('fBody');
      ta.onkeydown = (e) => {
        if (scriptAcState.open) {
          if (e.key === 'ArrowDown') {
            e.preventDefault();
            scriptAcState.index = Math.min(scriptAcState.index + 1, scriptAcState.items.length - 1);
            scriptAcRender(scriptAcState.items);
            return;
          }
          if (e.key === 'ArrowUp') {
            e.preventDefault();
            scriptAcState.index = Math.max(scriptAcState.index - 1, 0);
            scriptAcRender(scriptAcState.items);
            return;
          }
          if (e.key === 'Enter' || e.key === 'Tab') {
            e.preventDefault();
            scriptAcPick(scriptAcState.index);
            return;
          }
          if (e.key === 'Escape') {
            scriptAcClose();
            return;
          }
        }
        if (e.ctrlKey && e.code === 'Space') {
          e.preventDefault();
          const tr = detectScriptAcTrigger(ta);
          if (tr) scriptAcOpen(ta, tr.kind, tr.filter, tr.replaceStart);
          else scriptAcOpen(ta, 'list', '', ta.selectionStart);
        }
      };
      ta.oninput = () => {
        clearScriptValidateMsg();
        if (isPromptRuntime(currentEditorRuntime())) {
          updateRuleSummaryCard();
          return;
        }
        const tr = detectScriptAcTrigger(ta);
        if (tr) scriptAcOpen(ta, tr.kind, tr.filter, tr.replaceStart);
        else scriptAcClose();
      };
      ta.onblur = () => setTimeout(scriptAcClose, 150);
    }

    function syncScriptAssistUi(runtime) {
      const scriptRt = isScriptRuntime(runtime);
      const promptRt = isPromptRuntime(runtime);
      const edgeRt = isEdgeDslRuntime(runtime);
      const dlpRt = isDlpRuntime(runtime);
      document.getElementById('conditionAuthoringRow').style.display = (scriptRt || edgeRt || dlpRt) ? '' : 'none';
      document.getElementById('simulateFixtureRow').style.display = 'none';
      document.getElementById('chkEnableSimulateWrap').style.display = (scriptRt || promptRt) ? 'flex' : 'none';
      document.getElementById('chkEnableSimulate').checked = false;
      document.getElementById('scriptWizardRow').style.display = scriptRt && !isSimpleEditorMode() ? '' : 'none';
      document.getElementById('scriptSnippetRow').style.display = scriptRt && !isSimpleEditorMode() ? '' : 'none';
      document.getElementById('scriptValidateRow').style.display = scriptRt ? '' : 'none';
      document.getElementById('groovyHint').style.display = runtime === 'groovy' ? '' : 'none';
      document.getElementById('luaHint').style.display = runtime === 'lua' ? '' : 'none';
      document.getElementById('promptHint').style.display = promptRt ? '' : 'none';
      document.getElementById('edgeDslHint').style.display = edgeRt ? '' : 'none';
      document.getElementById('dlpDslHint').style.display = dlpRt ? '' : 'none';
      document.getElementById('fRecipeWrap').style.display = scriptRt ? '' : 'none';
      document.getElementById('btnApplyRecipe').style.display = scriptRt ? '' : 'none';
      document.getElementById('btnPreviewScript').style.display = scriptRt ? '' : 'none';
      if (!edgeRt && !dlpRt && !scriptRt && !promptRt) {
        document.getElementById('fBody').style.display = '';
      }
      syncBindScopeUi(runtime);
      if (scriptRt) {
        fillWizardListSelect();
        fillWizardCumSelect();
        syncWizardFields();
        const layer = isNewRule ? currentLayer : (editRuleMeta && editRuleMeta.layer) || currentLayer;
        loadRecipesForLayer(layer).catch(() => {});
        syncEditorModeUi();
        ensureSimFixtureEditor();
      } else if (edgeRt) {
        conditionLeaves = [];
        syncEditorModeUi();
        clearScriptValidateMsg();
        document.getElementById('simulatePanel').style.display = 'none';
      } else if (dlpRt) {
        conditionLeaves = [];
        syncEditorModeUi();
        syncDlpIntentReadonly();
        clearScriptValidateMsg();
        document.getElementById('simulatePanel').style.display = 'none';
      } else if (promptRt) {
        conditionLeaves = [];
        document.getElementById('simpleConditionPanel').style.display = 'none';
        document.getElementById('ruleSummaryCard').style.display = '';
        updateRuleSummaryCard();
        scriptAcClose();
        clearScriptValidateMsg();
        ensureSimFixtureEditor();
        document.getElementById('simulatePanel').style.display = 'none';
      } else {
        scriptAcClose();
        clearScriptValidateMsg();
        document.getElementById('simulatePanel').style.display = 'none';
      }
      syncAsyncLayerUi(runtime);
    }

    function syncBindScopeOptions(runtime) {
      const sel = document.getElementById('fBindScope');
      const current = sel.value || 'global';
      const edge = isEdgeFormRuntime(runtime);
      sel.innerHTML = edge
        ? '<option value="global">global（全部 App manifest）</option>'
          + '<option value="service">service（指定 app_ids）</option>'
        : '<option value="global">global（租户内全流量）</option>'
          + '<option value="route">route（Scene 匹配）</option>'
          + '<option value="service">service（app_ids）</option>';
      sel.value = [...sel.options].some(o => o.value === current) ? current : 'global';
    }

    function syncBindScopeFieldsUi() {
      const bs = document.getElementById('fBindScope').value || 'global';
      const edge = isEdgeFormRuntime(currentEditorRuntime());
      const route = !edge && bs === 'route';
      const service = bs === 'service';
      document.getElementById('bindSceneWrap').style.display = route ? '' : 'none';
      document.getElementById('bindServiceWrap').style.display = service ? '' : 'none';
    }

    function syncBindScopeUi(runtime) {
      const show = runtime === 'lua' || runtime === 'groovy' || runtime === 'prompt' || isEdgeFormRuntime(runtime);
      document.getElementById('bindScopeRow').style.display = show ? '' : 'none';
      document.getElementById('bindScopeHint').style.display = show ? '' : 'none';
      if (show) {
        syncBindScopeOptions(runtime);
        syncBindScopeFieldsUi();
      }
    }

    function buildRuleScope() {
      const runtime = currentEditorRuntime();
      const bs = document.getElementById('fBindScope').value || 'global';
      if (isEdgeFormRuntime(runtime) && bs === 'route') {
        return { bind_scope: 'global' };
      }
      const scope = { bind_scope: bs };
      const ref = {};
      if (bs === 'route') {
        const scenes = document.getElementById('fBindScenes').value.split(',').map(s => s.trim()).filter(Boolean);
        if (scenes.length) ref.scenes = scenes;
      } else if (bs === 'service') {
        const ids = document.getElementById('fBindAppIds').value.split(',').map(s => s.trim()).filter(Boolean);
        if (ids.length) ref.app_ids = ids;
      }
      if (Object.keys(ref).length) scope.bind_ref = ref;
      return scope;
    }

    function loadBindUiFromScope(scope) {
      const s = scope || {};
      const runtime = currentEditorRuntime();
      syncBindScopeOptions(runtime);
      let bs = s.bind_scope || 'global';
      if (isEdgeFormRuntime(runtime) && bs === 'route') {
        bs = 'global';
      }
      document.getElementById('fBindScope').value = bs;
      const ref = s.bind_ref || {};
      document.getElementById('fBindScenes').value = Array.isArray(ref.scenes) ? ref.scenes.join(', ') : '';
      document.getElementById('fBindAppIds').value = Array.isArray(ref.app_ids) ? ref.app_ids.join(', ') : '';
      syncBindScopeFieldsUi();
    }

    function syncIntentFromRisk() {
      const risk = Number(document.getElementById('fRisk').value);
      if (risk <= 0) {
        document.getElementById('fIntent').value = 'allow';
      } else if (risk >= 100) {
        const cur = document.getElementById('fIntent').value;
        if (cur === 'allow') document.getElementById('fIntent').value = 'deny';
      }
    }

    async function loadRules() {
      const rules = await admin('/rules?layer=' + encodeURIComponent(currentLayer));
      const tbody = document.querySelector('#rulesTable tbody');
      tbody.innerHTML = '';
      rules.forEach(r => {
        const tr = document.createElement('tr');
        if (r.rule_id === selectedRuleId) tr.classList.add('sel');
        tr.innerHTML = `<td>${esc(r.rule_id)}</td><td>${esc(r.runtime)}</td>
          <td><code>${esc(formatBindScope(r.scope))}</code></td>
          <td>${ruleStatusTag(r.rollout_state)}</td><td>${r.current_revision}</td>
          <td>${r.risk_score}</td><td>${esc(r.intent_action || 'deny')}</td><td>${esc(r.enforce_mode)}${r.canary_percent ? '@'+r.canary_percent+'%' : ''}</td>
          <td>${r.is_async ? '<span class="tag" style="background:#dbeafe;color:#1e40af">async</span>' : '<span style="color:#94a3b8">—</span>'}</td>
          <td>${esc(r.reason_code)}</td>`;
        tr.onclick = () => selectRule(r.rule_id);
        tbody.appendChild(tr);
      });
    }

    async function selectRule(ruleId) {
      isNewRule = false;
      selectedRuleId = ruleId;
      const r = await admin('/rules/' + encodeURIComponent(ruleId));
      editRuleMeta = r;
      document.getElementById('ruleEditor').style.display = 'block';
      document.getElementById('editTitle').textContent = '编辑';
      document.getElementById('editRuleId').textContent = ruleId;
      document.getElementById('newRuleRow').style.display = 'none';
      document.getElementById('fReason').value = r.reason_code || '';
      document.getElementById('fRisk').value = r.risk_score ?? 100;
      document.getElementById('fIntent').value = r.intent_action || 'deny';

      document.getElementById('fIsAsync').checked = !!r.is_async;
      loadAsyncActionConfig(r.async_action_config || '');
      syncAsyncUi();
      const body = r.body;
      const script = typeof body === 'string' ? body : JSON.stringify(body, null, 2);
      document.getElementById('fBody').value = script;
      syncScriptAssistUi(r.runtime);
      if (isEdgeDslRuntime(r.runtime)) {
        const bodyObj = typeof body === 'object' && body !== null ? body : (() => {
          try { return JSON.parse(script); } catch { return defaultEdgeBody(); }
        })();
        if (isEdgeKeywordBody(bodyObj)) {
          document.getElementById('fEditorMode').value = 'simple';
          loadEdgeBodyIntoForm(bodyObj);
        } else {
          document.getElementById('fEditorMode').value = 'advanced';
        }
        syncEditorModeUi();
      } else if (isDlpRuntime(r.runtime)) {
        const bodyObj = typeof body === 'object' && body !== null ? body : (() => {
          try { return JSON.parse(script); } catch { return defaultDlpBody(); }
        })();
        if (isDlpBody(bodyObj)) {
          document.getElementById('fEditorMode').value = 'simple';
          loadDlpBodyIntoForm(bodyObj);
        } else {
          document.getElementById('fEditorMode').value = 'advanced';
        }
        syncEditorModeUi();
        syncDlpIntentReadonly();
      } else if (!isPromptRuntime(r.runtime)) {
        await parseBodyIntoForm(r.layer, r.runtime, script);
      } else {
        updateRuleSummaryCard();
      }
      clearScriptValidateMsg();
      loadBindUiFromScope(r.scope);
      setRuleEditorCloudOnly(r.layer);
      setRuleEditorStatusUi(r.rollout_state);
      await loadRules();
      const roSel = document.getElementById('roRuleSelect');
      if (roSel && [...roSel.options].some(o => o.value === ruleId)) roSel.value = ruleId;
    }

    document.getElementById('btnNewRule').onclick = () => openNewRuleEditor();
    document.getElementById('fIsAsync').onchange = () => {
      syncAsyncUi();
      syncDlpIntentReadonly();
      updateRuleSummaryCard();
    };
    document.getElementById('fActionType').onchange = () => { syncActionTypeUi(); updateAsyncPreview(); };
    document.getElementById('fActionMessage').oninput = () => updateAsyncPreview();
    document.getElementById('fRisk').onchange = () => {
      if (!isDlpRuntime(currentEditorRuntime()) && !document.getElementById('fIsAsync').checked) syncIntentFromRisk();
    };
    document.getElementById('fDlpEntityType').onchange = () => { syncDlpPatternUi(); updateRuleSummaryCard(); };
    document.getElementById('fDlpPattern').oninput = () => updateRuleSummaryCard();
    document.getElementById('fDlpMaskTemplate').oninput = () => updateRuleSummaryCard();
    document.getElementById('fDlpPriority').onchange = () => updateRuleSummaryCard();
    document.getElementById('wPattern').onchange = syncWizardFields;
    document.getElementById('btnGenScript').onclick = () => generateScriptFromWizard();
    document.getElementById('fEditorMode').onchange = () => syncEditorModeUi();
    document.getElementById('btnApplyRecipe').onclick = () => {
      const id = document.getElementById('fRecipe').value;
      if (!id) { log('请选择模板', 'warn'); return; }
      applyRecipe(id);
    };
    document.getElementById('btnPreviewScript').onclick = () => previewCompiledScript().catch(e => log(e.message, 'err'));
    document.getElementById('chkEnableSimulate').onchange = () => {
      const checked = document.getElementById('chkEnableSimulate').checked;
      document.getElementById('simulateFixtureRow').style.display = checked ? '' : 'none';
      if (!checked) document.getElementById('simulatePanel').style.display = 'none';
    };
    document.getElementById('btnSimulateRule').onclick = () => runRuleSimulate().catch(e => log(e.message, 'err'));
    document.getElementById('btnSimFixtureApplyPreset').onclick = () => {
      resetSimFixture(document.getElementById('fSimFixturePreset').value);
    };
    document.getElementById('btnSimFixtureReset').onclick = () => resetSimFixture('clinical_chat');
    document.getElementById('btnAddListCond').onclick = () => {
      conditionLeaves.push({ type: 'list_match', list_name: listCatalog[0] || 'deny_keyword', value_source: 'content' });
      renderConditionLeaves();
      updateRuleSummaryCard();
    };
    document.getElementById('btnAddCumCond').onclick = () => {
      const name = field(cumulativeRows[0], 'cumulative_name', 'cumulativeName') || 'user_req_1h';
      conditionLeaves.push({ type: 'cumulative', cumulative_name: name, compare: 'gte', threshold: 120 });
      renderConditionLeaves();
      updateRuleSummaryCard();
    };
    document.getElementById('btnValidateScript').onclick = () =>
      validateScriptBody(true).catch(e => log(e.message, 'err'));
    document.querySelectorAll('#scriptSnippetRow button[data-snippet]').forEach(btn => {
      btn.onclick = () => insertAtCursor(document.getElementById('fBody'), scriptSnippet(btn.dataset.snippet));
    });
      document.getElementById('fBindScope').onchange = () => {
      syncBindScopeFieldsUi();
      const rt = currentEditorRuntime();
      if (isPromptRuntime(rt) || isEdgeFormRuntime(rt)) updateRuleSummaryCard();
    };
    document.getElementById('fEdgeListType').onchange = () => updateRuleSummaryCard();
    document.getElementById('fEdgeKeywords').oninput = () => updateRuleSummaryCard();
    document.getElementById('fBindAppIds').oninput = () => {
      if (isEdgeFormRuntime(currentEditorRuntime())) updateRuleSummaryCard();
    };

    document.getElementById('btnSaveRule').onclick = async () => {
      if (editRuleMeta && editRuleMeta.rollout_state === 'disabled') {
        log('规则已停用，不可修改', 'warn');
        return;
      }
      if (editRuleMeta && inExecutionPlane(editRuleMeta.rollout_state)) {
        log('规则为 ' + editRuleMeta.rollout_state + '，正在线上运行中。请先下线再编辑', 'warn');
        return;
      }
      const ruleId = isNewRule
        ? document.getElementById('fRuleId').value.trim()
        : selectedRuleId;
      if (!ruleId) {
        log('请填写 rule_id', 'warn');
        return;
      }
      const layer = isNewRule ? currentLayer : editRuleMeta.layer;
      const runtime = isNewRule ? document.getElementById('fRuntime').value : editRuleMeta.runtime;
      const scope = buildRuleScope();
      if (isEdgeFormRuntime(runtime) && scope.bind_scope === 'service') {
        const ids = scope.bind_ref && scope.bind_ref.app_ids;
        if (!ids || !ids.length) {
          log('端 edge service bind 须填写 app_ids', 'warn');
          return;
        }
      }
      const body = await resolveBodyForSave();
      if (isDlpRuntime(runtime)) {
        if (body.entity_type === 'custom_regex' && !(body.pattern || '').trim()) {
          log('custom_regex 须填写 pattern', 'warn');
          return;
        }
      }
      if (runtime === 'lua' || runtime === 'groovy') {
        const vr = await admin('/rules/validate-script', {
          method: 'POST',
          body: JSON.stringify({ layer, runtime, body })
        });
        showScriptValidateResult(vr);
        if (!vr.valid) {
          log('脚本校验未通过：' + (vr.errors || []).join('；'), 'err');
          return;
        }
      }
      const isAsync = document.getElementById('fIsAsync').checked;
      const asyncActionConfig = isAsync ? buildAsyncActionConfig() : null;
      try {
        await admin('/rules', {
          method: 'POST',
          body: JSON.stringify({
            rule_id: ruleId,
            bundle_id: bundleId(),
            layer,
            runtime,
            reason_code: document.getElementById('fReason').value,
            risk_score: isDlpRuntime(runtime) ? 0 : Number(document.getElementById('fRisk').value),
            intent_action: isAsync ? 'allow' : (isDlpRuntime(runtime) ? 'allow' : document.getElementById('fIntent').value),
            scope,
            body,
            editor_mode: isPromptRuntime(runtime) ? null : (isSimpleEditorMode() ? 'simple' : 'advanced'),
            condition: (isPromptRuntime(runtime) || isEdgeFormRuntime(runtime) || !isSimpleEditorMode()) ? null : readConditionPayload(),
            is_async: isAsync,
            async_action_config: asyncActionConfig
          })
        });
        log(isNewRule ? '规则已创建' : '规则已保存', 'ok');
        isNewRule = false;
        await selectRule(ruleId);
      } catch (e) {
        log('保存失败：' + e.message, 'err');
      }
    };
    document.getElementById('btnDisableRule').onclick = () =>
      admin('/rules/' + encodeURIComponent(selectedRuleId) + '/rollout/disable', { method: 'POST' })
        .then(log).then(() => selectRule(selectedRuleId)).catch(e => log(e.message, 'err'));
    document.getElementById('btnActivateRule').onclick = () =>
      admin('/rules/' + encodeURIComponent(selectedRuleId) + '/rollout/publish', { method: 'POST' })
        .then(log).then(() => selectRule(selectedRuleId)).catch(e => log(e.message, 'err'));
    document.getElementById('btnEnableRule').onclick = () =>
      admin('/rules/' + encodeURIComponent(selectedRuleId) + '/rollout/recover', { method: 'POST' })
        .then(log).then(() => selectRule(selectedRuleId)).catch(e => log(e.message, 'err'));

    initScriptAutocomplete();
