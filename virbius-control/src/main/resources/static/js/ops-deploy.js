    // Deploy Rollout JS

    function drApi(path, method, body) {
      const tid = document.getElementById('tenantId').value;
      const url = '/api/v1/admin/tenants/' + encodeURIComponent(tid) + '/deploy-rollout' + path;
      return fetch(url, {
        method: method || 'POST',
        headers: authHeaders(body ? { 'Content-Type': 'application/json' } : {}),
        body: body ? JSON.stringify(body) : undefined,
      }).then(r => r.json()).then(j => {
        if (j.code === 0) return j;
        throw new Error(j.message || ('HTTP ' + j.code));
      }).catch(e => ({ code: -1, message: e.message }));
    }

    function drLog(msg, ok) {
      const el = document.getElementById('log');
      el.textContent = msg;
      el.className = ok ? 'log-ok' : 'log-err';
    }

    function esc(s) {
      if (s == null) return '';
      return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    async function drRefresh() {
      const res = await drApi('/active', 'GET');
      if (res.code === 0 && res.data && res.data.active) {
        renderActive(res.data);
      } else {
        document.getElementById('drActiveInfo').style.display = 'block';
        document.getElementById('drActiveDetail').style.display = 'none';
        renderStatusBar(null);
        updateButtonStates(null);
      }
      // history
      const hist = await drApi('/list', 'GET');
      renderHistory(hist.data || []);
    }

    function renderStatusBar(rollout) {
      const el = document.getElementById('drStatusBar');
      if (!rollout) {
        el.innerHTML = '<div class="kpi-card"><div class="label">状态</div><div class="value">无进行中部署</div></div>';
        return;
      }
      const pct = rollout.canary_percent || 0;
      const cards = [
        ['状态', rollout.state],
        ['canary%', pct + '%'],
        ['Bundle', rollout.bundle_id || '-'],
        ['操作者', rollout.operator || '-'],
      ];
      el.innerHTML = cards.map(([label, value]) =>
        `<div class="kpi-card"><div class="label">${esc(label)}</div><div class="value">${esc(String(value))}</div></div>`
      ).join('');
    }

    // Enable/disable rollout action buttons based on current state, with helpful tooltips.
    function updateButtonStates(rollout) {
      const state = rollout ? String(rollout.state || '').toLowerCase() : null;
      const pct = rollout ? (rollout.canary_percent || 0) : 0;

      const rules = {
        drPrepareEngine: rollout
          ? { enabled: false, hint: '已有进行中的部署，请先完结或回退' }
          : { enabled: true, hint: '开始 Engine 灰度部署' },
        drPrepareGateway: rollout
          ? { enabled: false, hint: '已有进行中的部署，请先完结或回退' }
          : { enabled: true, hint: '开始 Gateway 灰度部署' },
        drUpgrade: !rollout
          ? { enabled: false, hint: '无进行中部署' }
          : ['pending', 'canary', 'paused'].includes(state)
            ? { enabled: true, hint: '推进到下一灰度阶梯（或恢复暂停）' }
            : { enabled: false, hint: `当前状态 ${state} 不允许升级` },
        drPause: !rollout
          ? { enabled: false, hint: '无进行中部署' }
          : state === 'canary'
            ? { enabled: true, hint: '暂停当前灰度，停在当前 canary%' }
            : { enabled: false, hint: '仅 canary 状态可暂停' },
        drRollback: !rollout
          ? { enabled: false, hint: '无进行中部署' }
          : ['finalized', 'rolled_back'].includes(state)
            ? { enabled: false, hint: '部署已终结，无法回退' }
            : { enabled: true, hint: '回退到旧版本（终结前都可回退）' },
        drDeployEdge: !rollout
          ? { enabled: false, hint: '无进行中部署' }
          : state === 'full'
            ? { enabled: true, hint: '发布到边缘节点（全量、无灰度）' }
            : { enabled: false, hint: '需先升级到 FULL@100%' },
        drFinalize: !rollout
          ? { enabled: false, hint: '无进行中部署' }
          : ['full', 'edge_done'].includes(state)
            ? { enabled: true, hint: '⚠ 完结后切换稳定版指针，无法再回退' }
            : pct < 100
              ? { enabled: false, hint: `需先升级至 100%（当前 ${pct}%）` }
              : { enabled: false, hint: `当前状态 ${state} 不可完结` },
        drDeployEdgeOrFinalizeHint: null,
      };

      for (const [id, rule] of Object.entries(rules)) {
        if (rule == null) continue;
        const btn = document.getElementById(id);
        if (!btn) continue;
        btn.disabled = !rule.enabled;
        btn.title = rule.hint;
        btn.style.opacity = rule.enabled ? '' : '0.5';
        btn.style.cursor = rule.enabled ? '' : 'not-allowed';
      }
    }

    function renderActive(r) {
      document.getElementById('drActiveInfo').style.display = 'none';
      document.getElementById('drActiveDetail').style.display = 'block';
      renderStatusBar(r);
      updateButtonStates(r);

      const tbody = document.querySelector('#drActiveTable tbody');
      tbody.innerHTML = `<tr>
        <td>${esc(r.deploy_id)}</td>
        <td><span class="tag">${esc(r.state)}</span></td>
        <td>${esc(r.bundle_id)}</td>
        <td>${r.canary_percent || 0}%</td>
        <td>${r.canary_engine_revision || '-'}</td>
        <td>${r.stable_engine_revision || '-'}</td>
        <td>${r.canary_gateway_revision || '-'}</td>
        <td>${r.stable_gateway_revision || '-'}</td>
        <td>${esc(r.operator)}</td>
        <td>${esc(r.note)}</td>
      </tr>`;

      // node distribution
      const dist = r.pool_distribution || {};
      const nd = document.getElementById('drNodeDistribution');
      const fullStates = ['full', 'edge_done', 'finalized'];
      if (fullStates.includes(r.state) || (r.canary_percent || 0) >= 100) {
        nd.innerHTML = '<div class="kpi-card" style="border-color:#16a34a;grid-column:1/-1"><div class="label">状态</div><div class="value" style="color:#16a34a">✅ 已全量部署</div></div>';
      } else {
        const parts = [];
        for (const layer of ['cloud', 'gateway']) {
          const nodes = r[layer + '_nodes'] || [];
          const pools = dist[layer] || {};
          const total = Object.values(pools).reduce((a, b) => a + b, 0);
          if (total === 0) continue;
          parts.push(`<div class="kpi-card"><div class="label">${layer}</div><div class="value">${total} 台</div>`);
          for (const [pool, count] of Object.entries(pools)) {
            const tag = pool === 'canary' ? ' style="background:#16a34a;color:#fff"' : '';
            parts.push(`<span class="tag"${tag}>${pool}: ${count}</span>`);
          }
          parts.push(`</div>`);
          if (nodes.length > 2) {
            parts.push(`<details style="grid-column:1/-1;font-size:0.8rem;color:#666">`);
            parts.push(`<summary style="cursor:pointer">查看 ${layer} 节点详情</summary>`);
            for (const node of nodes) {
              const tag = node.pool === 'canary' ? ' style="background:#16a34a;color:#fff;font-size:0.75rem"' : ' style="font-size:0.75rem"';
              const seen = node.last_seen ? new Date(parseInt(node.last_seen) * 1000).toLocaleString() : '-';
              parts.push(`<span style="display:inline-block;margin:0.1rem 0">${esc(node.hostname || node.instance_id)} <span class="tag"${tag}>${node.pool}</span> ${seen}</span><br>`);
            }
            parts.push(`</details>`);
          }
        }
        nd.innerHTML = parts.join('') || '<div class="hint">暂无节点心跳</div>';
      }

      // events
      const evBody = document.querySelector('#drEventTable tbody');
      const events = r.events || [];
      evBody.innerHTML = events.map(e => `<tr>
        <td>${e.created_at ? new Date(e.created_at).toLocaleString() : '-'}</td>
        <td><span class="tag">${esc(e.event_type)}</span></td>
        <td>${esc(e.from_state)}${e.from_percent != null ? '@' + e.from_percent + '%' : ''}</td>
        <td>${esc(e.to_state)}${e.to_percent != null ? '@' + e.to_percent + '%' : ''}</td>
        <td>${esc(e.note || e.reason || '')}</td>
        <td>${esc(e.operator)}</td>
      </tr>`).join('');
    }

    function renderHistory(rollouts) {
      const tbody = document.querySelector('#drHistoryTable tbody');
      tbody.innerHTML = rollouts.map(r => `<tr>
        <td>${esc(r.deploy_id)}</td>
        <td>${esc(r.bundle_id)}</td>
        <td><span class="tag">${esc(r.state)}</span></td>
        <td>${r.started_at ? new Date(r.started_at).toLocaleString() : '-'}</td>
        <td>${r.finalized_at ? new Date(r.finalized_at).toLocaleString() : '-'}</td>
        <td>${esc(r.operator)}</td>
      </tr>`).join('');
    }

    document.addEventListener('DOMContentLoaded', function() {
      document.getElementById('drPrepareEngine').addEventListener('click', async () => {
        const desc = document.getElementById('drDescription').value.trim();
        const res = await drApi('/prepare', 'POST', { bundle_id: document.getElementById('bundleId').value, bundle_version: document.getElementById('bundleVer').value, layer: 'cloud', description: desc });
        if (res.code === 0) { drLog('Engine 灰度准备完成 deploy_id=' + res.data.deploy_id + ' bundle=' + (res.data.bundle_id || 'auto'), true); drRefresh(); }
        else { drLog('准备失败: ' + (res.message || JSON.stringify(res)), false); }
      });

      document.getElementById('drPrepareGateway').addEventListener('click', async () => {
        const desc = document.getElementById('drDescription').value.trim();
        const res = await drApi('/prepare', 'POST', { bundle_id: document.getElementById('bundleId').value, bundle_version: document.getElementById('bundleVer').value, layer: 'gateway', description: desc });
        if (res.code === 0) { drLog('Gateway 灰度准备完成 deploy_id=' + res.data.deploy_id + ' bundle=' + (res.data.bundle_id || 'auto'), true); drRefresh(); }
        else { drLog('准备失败: ' + (res.message || JSON.stringify(res)), false); }
      });

      document.getElementById('drUpgrade').addEventListener('click', async () => {
        const active = await drApi('/active', 'GET');
        const did = active.data && active.data.deploy_id;
        if (!did) { drLog('无进行中部署', false); return; }
        const res = await drApi('/' + did + '/upgrade', 'POST', { note: 'UI 升级' });
        if (res.code === 0) { drLog('升级成功 state=' + res.data.state + ' pct=' + res.data.canary_percent + '%', true); drRefresh(); }
        else { drLog('升级失败: ' + (res.message || JSON.stringify(res)), false); }
      });

      document.getElementById('drPause').addEventListener('click', async () => {
        const active = await drApi('/active', 'GET');
        const did = active.data && active.data.deploy_id;
        if (!did) { drLog('无进行中部署', false); return; }
        const res = await drApi('/' + did + '/pause', 'POST', { note: 'UI 暂停' });
        if (res.code === 0) { drLog('已暂停', true); drRefresh(); }
        else { drLog('暂停失败: ' + (res.message || JSON.stringify(res)), false); }
      });

      document.getElementById('drRollback').addEventListener('click', async () => {
        if (!confirm('确认回退当前部署？')) return;
        const active = await drApi('/active', 'GET');
        const did = active.data && active.data.deploy_id;
        if (!did) { drLog('无进行中部署', false); return; }
        const res = await drApi('/' + did + '/rollback', 'POST', { note: 'UI 回退' });
        if (res.code === 0) { drLog('已回退', true); drRefresh(); }
        else { drLog('回退失败: ' + (res.message || JSON.stringify(res)), false); }
      });

      document.getElementById('drDeployEdge').addEventListener('click', async () => {
        const active = await drApi('/active', 'GET');
        const did = active.data && active.data.deploy_id;
        if (!did) { drLog('无进行中部署', false); return; }
        const res = await drApi('/' + did + '/deploy-edge', 'POST', { note: 'UI 发布 Edge' });
        if (res.code === 0) { drLog('Edge 已发布', true); drRefresh(); }
        else { drLog('Edge 发布失败: ' + (res.message || JSON.stringify(res)), false); }
      });

      document.getElementById('drFinalize').addEventListener('click', async () => {
        if (!confirm('⚠ 确认完结部署？\n\n完结后将：\n  · 把新版本指针切换为稳定版\n  · 关闭回退通道（不可再 rollback）\n  · 归档当前 rollout 记录\n\n建议在线上指标观察期通过后再点。')) return;
        const active = await drApi('/active', 'GET');
        const did = active.data && active.data.deploy_id;
        if (!did) { drLog('无进行中部署', false); return; }
        const res = await drApi('/' + did + '/finalize', 'POST', { note: 'UI 完结' });
        if (res.code === 0) { drLog('已完结', true); drRefresh(); }
        else { drLog('完结失败: ' + (res.message || JSON.stringify(res)), false); }
      });

      document.getElementById('drRefresh').addEventListener('click', drRefresh);

      // auto-refresh when rollout panel is active
      const origTab = window._onTabSwitch;
      window._drTimer = null;
      window._onTabSwitch = function(tab) {
        if (origTab) origTab(tab);
        if (window._drTimer) clearInterval(window._drTimer);
        if (tab === 'rollout') {
          drRefresh();
          window._drTimer = setInterval(drRefresh, 10000);
        }
      };

    });
