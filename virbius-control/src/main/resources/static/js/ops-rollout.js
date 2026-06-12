    function rolloutRuleId() {
      return document.getElementById('roRuleSelect').value.trim();
    }

    function fmtPct(n) {
      if (n == null || Number.isNaN(n)) return 'N/A';
      return (n * 100).toFixed(2) + '%';
    }

    function rolloutStateLabel(st, pct) {
      if (st === 'canary' && pct != null) return `canary@${pct}%`;
      return st || 'draft';
    }

    function renderRolloutFlow(st, pct) {
      const el = document.getElementById('roFlowStrip');
      const order = { draft: 0, disabled: -1, dry_run: 1, canary: 2, full: 3 };
      const cur = st === 'disabled' ? 'draft' : (st || 'draft');
      const curIdx = order[cur] ?? 0;
      const parts = [];
      FLOW_STEPS.forEach((step, i) => {
        if (i > 0) parts.push('<span class="flow-arrow">→</span>');
        let cls = 'flow-step';
        if (step === cur) cls += ' active';
        else if (i < curIdx) cls += ' done';
        let label = step;
        if (step === 'canary' && cur === 'canary' && pct != null) label = `canary ${pct}%`;
        parts.push(`<span class="${cls}">${esc(label)}</span>`);
      });
      if (st === 'disabled') {
        parts.push('<span class="flow-arrow">|</span><span class="flow-step active">disabled</span>');
      }
      el.innerHTML = parts.join('');
    }

    function renderRolloutKpi(totals) {
      const t = totals || {};
      const cards = [
        ['review (24h)', t.review ?? 0],
        ['block (24h)', t.block ?? 0],
        ['captcha (24h)', t.captcha ?? 0],
        ['total requests', t.total_requests ?? 0],
        ['hit_rate', t.hit_rate != null ? fmtPct(t.hit_rate) : 'N/A'],
        ['review_rate', t.review_rate != null ? fmtPct(t.review_rate) : 'N/A']
      ];
      document.getElementById('roKpi').innerHTML = cards.map(([label, value]) =>
        `<div class="kpi-card"><div class="label">${esc(label)}</div><div class="value">${esc(String(value))}</div></div>`
      ).join('');
    }

    function renderMiniBar(review, block, captcha, allow) {
      const total = Math.max(review + block + captcha + allow, 1);
      const segs = [
        { n: review, c: '#fbbf24' },
        { n: block, c: '#ef4444' },
        { n: captcha, c: '#a855f7' },
        { n: allow, c: '#22c55e' }
      ];
      return '<div class="bar-cell">' + segs.map(s => {
        const h = Math.max(2, Math.round((s.n / total) * 32));
        return `<div class="bar-seg" style="height:${h}px;background:${s.c}" title="${s.n}"></div>`;
      }).join('') + '</div>';
    }

    function renderRolloutMetrics(series) {
      const tbody = document.querySelector('#roMetricsTable tbody');
      tbody.innerHTML = '';
      (series || []).forEach(row => {
        const tr = document.createElement('tr');
        tr.innerHTML = `<td>${esc(fmtTime(row.bucket))}</td>
          <td>${renderMiniBar(row.review || 0, row.block || 0, row.captcha || 0, row.allow || 0)}</td>
          <td>${row.review ?? 0}</td><td>${row.block ?? 0}</td><td>${row.captcha ?? 0}</td>
          <td>${row.allow ?? 0}</td><td>${row.total_requests ?? 0}</td>`;
        tbody.appendChild(tr);
      });
      if (!series || !series.length) {
        tbody.innerHTML = '<tr><td colspan="7" class="hint">暂无指标（需 audit ingest + metrics rollup）</td></tr>';
      }
    }

    function renderRolloutTimeline(events) {
      const tbody = document.querySelector('#roTimelineTable tbody');
      tbody.innerHTML = '';
      (events || []).forEach(ev => {
        const tr = document.createElement('tr');
        tr.innerHTML = `<td>${esc(fmtTime(ev.effective_at))}</td>
          <td>${ruleStatusTag(ev.rollout_state)}</td>
          <td>${ev.canary_percent ?? '—'}</td><td>${esc(ev.trigger || '')}</td>
          <td>${esc(ev.operator || '')}</td><td>${ev.rule_revision ?? ''}</td>`;
        tbody.appendChild(tr);
      });
      if (!events || !events.length) {
        tbody.innerHTML = '<tr><td colspan="6" class="hint">暂无变更记录</td></tr>';
      }
    }

    function updateRolloutSuggestedHint(meta) {
      const hint = document.getElementById('roSuggestedHint');
      if (!meta) {
        hint.textContent = '选择规则后可操作';
        return;
      }
      const st = meta.rollout_state;
      if (st === 'draft') {
        hint.textContent = 'draft 规则请先点「上线」进入 dry_run';
        return;
      }
      if (st === 'disabled') {
        hint.textContent = '已停用，可「恢复草稿」后编辑';
        return;
      }
      if (st === 'full') {
        hint.textContent = '已在 full，无下一步升级';
        return;
      }
      const target = rolloutEvaluateTarget(meta);
      if (!target) {
        hint.textContent = '当前无下一步升级目标';
        return;
      }
      hint.textContent = `下一步：${rolloutStateLabel(target.target_state, target.canary_percent)}`
        + '（未达标时返回 409，可勾选 force 并填写说明）';
    }

    function renderRolloutSamples(samples) {
      const tbody = document.querySelector('#roSamplesTable tbody');
      tbody.innerHTML = '';
      (samples || []).forEach(s => {
        const tr = document.createElement('tr');
        const traceCell = s.trace_id
          ? `<a href="#" class="trace-link" data-trace="${escAttr(s.trace_id)}"><code>${esc(s.trace_id)}</code></a>`
          : '';
        tr.innerHTML = `<td>${esc(fmtTime(s.intercepted_at))}</td>
          <td>${traceCell}</td>
          <td>${esc(s.effective_action || '')}</td><td>${esc(s.reason_code || '')}</td>
          <td>${s.max_risk_score ?? ''}</td>
          <td>${esc(rolloutStateLabel(s.rollout_state, s.canary_percent))}</td>`;
        tbody.appendChild(tr);
      });
      tbody.querySelectorAll('.trace-link').forEach(a => {
        a.onclick = (e) => {
          e.preventDefault();
          openTraceModal(a.getAttribute('data-trace'));
        };
      });
      if (!samples || !samples.length) {
        tbody.innerHTML = '<tr><td colspan="6" class="hint">暂无 audit 样本</td></tr>';
      }
    }

    async function openTraceModal(traceId) {
      if (!traceId) return;
      const modal = document.getElementById('roTraceModal');
      const hint = document.getElementById('roTraceHint');
      const tbody = document.querySelector('#roTraceTable tbody');
      modal.style.display = '';
      hint.textContent = `trace_id: ${traceId}`;
      tbody.innerHTML = '<tr><td colspan="7" class="hint">加载中…</td></tr>';
      try {
        const detail = await admin('/audit/trace/' + encodeURIComponent(traceId));
        tbody.innerHTML = '';
        const rows = [];
        (detail.db_events || []).forEach(h => rows.push({ ...h, _source: 'db' }));
        (detail.allow_log_events || []).forEach(h => rows.push({ ...h, _source: 'allow-log' }));
        rows.sort((a, b) => String(a.intercepted_at || '').localeCompare(String(b.intercepted_at || '')));
        rows.forEach(h => {
          const tr = document.createElement('tr');
          tr.innerHTML = `<td>${esc(fmtTime(h.intercepted_at))}</td>
            <td>${esc(h.layer || '')}</td><td>${esc(h.effective_action || '')}</td>
            <td>${esc(h.rule_id || '')}@${h.rule_revision ?? ''}</td>
            <td>${esc(rolloutStateLabel(h.rollout_state, h.canary_percent))}</td>
            <td>${h.max_risk_score ?? ''}</td>
            <td>${esc(h._source || '')}</td>`;
          tbody.appendChild(tr);
        });
        if (!rows.length) {
          tbody.innerHTML = '<tr><td colspan="7" class="hint">无 audit 记录（DB 与 allow 日志均为空）</td></tr>';
        }
        if (detail.note) {
          hint.textContent = `trace_id: ${traceId} — ${detail.note}`;
        }
      } catch (e) {
        tbody.innerHTML = `<tr><td colspan="7" class="hint">${esc(e.message)}</td></tr>`;
      }
    }

    function updateRolloutActions(meta) {
      const st = meta ? meta.rollout_state : null;
      const isDraft = st === 'draft';
      const isDisabled = st === 'disabled';
      const exec = inExecutionPlane(st);
      document.getElementById('roPublish').style.display = isDraft ? '' : 'none';
      document.getElementById('roRecover').style.display = isDisabled ? '' : 'none';
      document.getElementById('roDisable').style.display = meta && !isDisabled ? '' : 'none';
      document.getElementById('roRollback').style.display = exec && st !== 'dry_run' ? '' : 'none';
      document.getElementById('roApply').style.display = meta && !isDraft && !isDisabled && st !== 'full' ? '' : 'none';
      document.getElementById('roLadderStart').style.display = exec && st !== 'full' ? '' : 'none';
      document.getElementById('roLadderPause').style.display = exec ? '' : 'none';
      updateRolloutSuggestedHint(meta);
    }

    function rolloutEvaluateTarget(meta) {
      if (!meta) return null;
      const st = meta.rollout_state;
      const ladder = rolloutCanaryLadder;
      if (st === 'dry_run') {
        return { target_state: 'canary', canary_percent: ladder[0] };
      }
      if (st === 'canary') {
        const pct = meta.canary_percent;
        const idx = ladder.indexOf(pct != null ? pct : ladder[0]);
        const nextIdx = idx < 0 ? 0 : idx + 1;
        if (nextIdx >= ladder.length) return null;
        const step = ladder[nextIdx];
        if (step >= 100) return { target_state: 'full', canary_percent: null };
        return { target_state: 'canary', canary_percent: step };
      }
      return null;
    }

    async function loadAllRulesForRollout() {
      const layers = ['cloud', 'gateway', 'edge'];
      const all = [];
      for (const layer of layers) {
        const rules = await admin('/rules?layer=' + encodeURIComponent(layer));
        rules.forEach(r => all.push(r));
      }
      rolloutAllRules = all.sort((a, b) => a.rule_id.localeCompare(b.rule_id));
      const sel = document.getElementById('roRuleSelect');
      const prev = sel.value;
      sel.innerHTML = '<option value="">— 选择规则 —</option>' +
        rolloutAllRules.map(r => {
          const label = `${r.rule_id} [${r.layer}] ${rolloutStateLabel(r.rollout_state, r.canary_percent)}`;
          return `<option value="${escAttr(r.rule_id)}">${esc(label)}</option>`;
        }).join('');
      if (prev && rolloutAllRules.some(r => r.rule_id === prev)) sel.value = prev;
    }

    async function refreshRolloutDashboard(opts) {
      if (rolloutRefreshBusy) return;
      rolloutRefreshBusy = true;
      try {
        await loadAllRulesForRollout();
        try {
          const pol = await admin('/rollout-policy');
          if (pol && pol.canary_ladder && pol.canary_ladder.length) {
            rolloutCanaryLadder = pol.canary_ladder;
          }
        } catch (_) { /* 读 tb_tenant_rollout_policy；无 UI 配置 */ }
        const ruleId = rolloutRuleId();
        if (!ruleId) {
          rolloutRuleMeta = null;
          document.getElementById('roBadge').innerHTML = '';
          renderRolloutFlow('draft');
          renderRolloutKpi({});
          renderRolloutMetrics([]);
          renderRolloutTimeline([]);
          renderRolloutSamples([]);
          updateRolloutActions(null);
          return;
        }
        const meta = await admin('/rules/' + encodeURIComponent(ruleId));
        rolloutRuleMeta = meta;
        const st = meta.rollout_state || 'draft';
        const pct = meta.canary_percent;
        document.getElementById('roBadge').innerHTML = ruleStatusTag(st)
          + (pct != null ? ` <span class="tag">${pct}%</span>` : '')
          + ` <span class="hint">${esc(meta.layer)}/${esc(meta.runtime)}</span>`;
        if (meta.layer === 'edge') {
          try {
            const health = await admin('/rollout/ingest-health?layer=edge&hours=24');
            document.getElementById('roBadge').innerHTML += ` <span class="hint">edge audit 24h: ${health.audit_events ?? 0}</span>`;
          } catch (_) { /* optional */ }
        }
        renderRolloutFlow(st, pct);
        updateRolloutActions(meta);
        const [metrics, timeline, samples] = await Promise.all([
          admin('/rules/' + encodeURIComponent(ruleId) + '/metrics?hours=24').catch(() => ({ series: [], totals: {} })),
          admin('/rules/' + encodeURIComponent(ruleId) + '/rollout/timeline').catch(() => []),
          admin('/rules/' + encodeURIComponent(ruleId) + '/audit-samples?effective_action=review&limit=30').catch(() => [])
        ]);
        renderRolloutKpi(metrics.totals || {});
        renderRolloutMetrics(metrics.series || []);
        renderRolloutTimeline(timeline);
        renderRolloutSamples(samples);
        const comparePromise = loadCompareData().catch(() => {});
      } finally {
        rolloutRefreshBusy = false;
        loadGatewayArtifactStatus().catch(() => {});
      }
    }

    async function loadCompareData() {
      const ruleId = rolloutRuleId();
      const el = document.getElementById('roCompareCards');
      const section = document.getElementById('roCompareSection');
      if (!ruleId || !el || !section) { if (section) section.style.display = 'none'; return; }
      try {
        const data = await admin('/rules/' + encodeURIComponent(ruleId) + '/rollout/compare?hours=24');
        if (data.pending) {
          section.style.display = '';
          renderCompareCards('active', data.active, 'pending', data.pending);
          document.getElementById('roDeployPending').style.display = '';
        } else {
          section.style.display = 'none';
          document.getElementById('roDeployPending').style.display = 'none';
        }
      } catch (e) {
        section.style.display = 'none';
        document.getElementById('roDeployPending').style.display = 'none';
      }
    }

    function renderCompareCards(label1, data1, label2, data2) {
      const el = document.getElementById('roCompareCards');
      if (!el) return;
      const card = (label, d) => {
        const t = d.totals || {};
        const total = t.total_requests || 1;
        const pct = (v) => ((v / total) * 100).toFixed(2) + '%';
        return `<div class="compare-card">
          <div class="card-title"><span class="tag">${esc(label)}</span> rev=${esc(String(d.revision ?? '?'))}</div>
          <div class="stat-row"><span class="stat-label">review</span><span class="stat-value">${t.review ?? 0} (${pct(t.review)})</span></div>
          <div class="stat-row"><span class="stat-label">block</span><span class="stat-value">${t.block ?? 0} (${pct(t.block)})</span></div>
          <div class="stat-row"><span class="stat-label">captcha</span><span class="stat-value">${t.captcha ?? 0} (${pct(t.captcha)})</span></div>
          <div class="stat-row"><span class="stat-label">allow</span><span class="stat-value">${t.allow ?? 0} (${pct(t.allow)})</span></div>
          <div class="stat-row"><span class="stat-label">total</span><span class="stat-value">${t.total_requests ?? 0}</span></div>
        </div>`;
      };
      el.innerHTML = card(label1, data1) + card(label2, data2);
    }

    async function loadGatewayArtifactStatus() {
      const el = document.getElementById('gatewayArtifactStatus');
      if (!el) return;
      try {
        const data = await admin('/gateway-artifacts/policy-version');
        const nodes = await admin('/gateway-artifacts/nodes');
        const okCount = data.nodes_ok ?? 0;
        const totalCount = data.nodes_total ?? 0;
        const rows = (nodes ?? []).map(n =>
          `<tr><td>${esc(n.hostname)}</td><td>r${esc(n.artifact_revision)}</td><td>${ruleStatusTag(n.status)}</td><td style="font-size:0.75rem">${esc(n.loaded_at || '').replace('T',' ').slice(0,19)}</td></tr>`).join('');
        el.innerHTML =
          (rows ? `<table class="striped" style="font-size:0.8rem;width:auto">
            <thead><tr><th>节点</th><th>版本</th><th>状态</th><th>加载时间</th></tr></thead>
            <tbody>${rows}</tbody>
          </table>` : '') +
          `<p style="margin:0.25rem 0 0;font-size:0.75rem;color:var(--text-muted)">
            revision ${esc(String(data.artifact_revision))} · ${okCount}/${totalCount} nodes ok
            ${data.published_at ? '· ' + esc(data.published_at).replace('T',' ').slice(0,19) : ''}
          </p>`;
      } catch (e) {
        el.textContent = '不可用: ' + e.message;
      }
    }

    function startRolloutAutoRefresh() {
      stopRolloutAutoRefresh();
      rolloutRefreshTimer = setInterval(() => {
        if (document.getElementById('panel-rollout').classList.contains('active')) {
          refreshRolloutDashboard({ silent: true }).catch(() => {});
        }
      }, ROLLOUT_REFRESH_MS);
    }

    function stopRolloutAutoRefresh() {
      if (rolloutRefreshTimer) {
        clearInterval(rolloutRefreshTimer);
        rolloutRefreshTimer = null;
      }
    }

    document.getElementById('roRuleSelect').onchange = () =>
      refreshRolloutDashboard().catch(e => log(e.message, 'err'));
    document.getElementById('roRefresh').onclick = () =>
      refreshRolloutDashboard().then(() => log('策略看板已刷新', 'ok')).catch(e => log(e.message, 'err'));

    document.getElementById('roApply').onclick = async () => {
      const ruleId = rolloutRuleId();
      if (!ruleId || !rolloutRuleMeta) return;
      const target = rolloutEvaluateTarget(rolloutRuleMeta);
      if (!target || (!target.rollout_state && !target.target_state)) {
        log('当前无下一步升级目标', 'warn');
        return;
      }
      const force = document.getElementById('roForce').checked;
      const comment = document.getElementById('roForceComment').value.trim();
      if (force && !comment) {
        log('force 绕过须填写说明', 'warn');
        return;
      }
      const rolloutState = target.rollout_state || target.target_state;
      let canaryPercent = target.canary_percent;
      if (rolloutState === 'full') canaryPercent = null;
      const body = { rollout_state: rolloutState, canary_percent: canaryPercent, force, comment: force ? comment : null };
      try {
        const data = await admin('/rules/' + encodeURIComponent(ruleId) + '/rollout', {
          method: 'PATCH',
          body: JSON.stringify(body)
        });
        log(data);
        await refreshRolloutDashboard();
        if (selectedRuleId === ruleId) await selectRule(ruleId);
      } catch (e) {
        log(e.message, 'err');
      }
    };

    document.getElementById('roPublish').onclick = () => {
      const ruleId = rolloutRuleId();
      if (!ruleId) return;
      admin('/rules/' + encodeURIComponent(ruleId) + '/rollout/publish', { method: 'POST' })
        .then(log).then(() => refreshRolloutDashboard()).catch(e => log(e.message, 'err'));
    };
    document.getElementById('roRollback').onclick = () => {
      const ruleId = rolloutRuleId();
      if (!ruleId) return;
      admin('/rules/' + encodeURIComponent(ruleId) + '/rollout/rollback', { method: 'POST' })
        .then(log).then(() => refreshRolloutDashboard()).catch(e => log(e.message, 'err'));
    };
    document.getElementById('roDisable').onclick = () => {
      const ruleId = rolloutRuleId();
      if (!ruleId) return;
      admin('/rules/' + encodeURIComponent(ruleId) + '/rollout/disable', { method: 'POST' })
        .then(log).then(() => refreshRolloutDashboard()).catch(e => log(e.message, 'err'));
    };
    document.getElementById('roRecover').onclick = () => {
      const ruleId = rolloutRuleId();
      if (!ruleId) return;
      admin('/rules/' + encodeURIComponent(ruleId) + '/rollout/recover', { method: 'POST' })
        .then(log).then(() => refreshRolloutDashboard()).catch(e => log(e.message, 'err'));
    };
    document.getElementById('roLadderStart').onclick = () => {
      const ruleId = rolloutRuleId();
      if (!ruleId) return;
      admin('/rules/' + encodeURIComponent(ruleId) + '/rollout/ladder/start', { method: 'POST' })
        .then(log).catch(e => log(e.message, 'err'));
    };
    document.getElementById('roLadderPause').onclick = () => {
      const ruleId = rolloutRuleId();
      if (!ruleId) return;
      admin('/rules/' + encodeURIComponent(ruleId) + '/rollout/ladder/pause', { method: 'POST' })
        .then(log).catch(e => log(e.message, 'err'));
    };
    document.getElementById('roDeployPending').onclick = async () => {
      const ruleId = rolloutRuleId();
      if (!ruleId) return;
      const state = prompt('部署初始状态：dry_run（默认）/ canary\n如选 canary 请输入 "canary@百分比"', 'dry_run');
      if (state === null) return;
      let initialState = 'dry_run';
      let canaryPercent = null;
      if (state.trim().toLowerCase().startsWith('canary')) {
        const m = state.match(/canary@?(\d+)/i);
        if (!m) { log('格式错误：canary@百分比，如 canary@5', 'err'); return; }
        initialState = 'canary';
        canaryPercent = parseInt(m[1]);
      } else if (state.trim().toLowerCase() !== 'dry_run') {
        log('请输入 dry_run 或 canary@百分比', 'err');
        return;
      }
      try {
        const data = await admin('/rules/' + encodeURIComponent(ruleId) + '/rollout/deploy-pending', {
          method: 'POST',
          body: JSON.stringify({ initial_state: initialState, canary_percent: canaryPercent })
        });
        log('部署成功: ' + JSON.stringify(data));
        await refreshRolloutDashboard();
      } catch (e) {
        log(e.message, 'err');
      }
    };
    document.getElementById('roTraceClose').onclick = () => {
      document.getElementById('roTraceModal').style.display = 'none';
    };
    document.getElementById('btnGatewayArtifact').onclick = () =>
      loadGatewayArtifactStatus().then(() => log('网关产物状态已刷新')).catch(e => log(e.message, 'err'));
