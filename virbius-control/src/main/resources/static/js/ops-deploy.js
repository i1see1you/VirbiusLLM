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
      if (res.code === 0 && res.data && res.data.deploy_id) {
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
          : { enabled: true, hint: '开始 Engine 灰度部署（仅包含 cloud 层）' },
        drPrepareGateway: rollout
          ? { enabled: false, hint: '已有进行中的部署，请先完结或回退' }
          : { enabled: true, hint: '开始 Gateway 灰度部署（仅包含 gateway 层）' },
        drPrepareEdge: rollout
          ? { enabled: false, hint: '已有进行中的部署，请先完结或回退' }
          : { enabled: true, hint: '开始 Edge 按 device_id 灰度部署（仅包含 edge 层）' },
        drPrepareAll: rollout
          ? { enabled: false, hint: '已有进行中的部署，请先完结或回退' }
          : { enabled: true, hint: '开始三层（Engine+Gateway+Edge）同时灰度部署' },
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
        drFinalize: !rollout
          ? { enabled: false, hint: '无进行中部署' }
          : pct === 100
            ? { enabled: true, hint: '⚠ 完结后切换稳定版指针 + 清理 canary 清单，不可回退' }
            : { enabled: false, hint: `需先升级至 100%（当前 ${pct}%）` },
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
        <td>${r.canary_edge_revision || '-'}</td>
        <td>${r.stable_edge_revision || '-'}</td>
        <td>${esc(r.operator)}</td>
        <td>${esc(r.note)}</td>
      </tr>`;

      // node distribution
      const dist = r.pool_distribution || {};
      const nd = document.getElementById('drNodeDistribution');
      const pct = r.canary_percent || 0;
      if (pct >= 100) {
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
        // Edge status card (no heartbeats, show pool strategy info)
        const hasEdge = r.canary_edge_revision > 0 || r.stable_edge_revision > 0;
        if (hasEdge) {
          parts.push(`<div class="kpi-card" style="border-color:#818cf8"><div class="label">Edge</div><div class="value">按 device_id 分池</div>`);
          parts.push(`<span class="tag" style="background:#16a34a;color:#fff">canary: < ${pct}%</span>`);
          parts.push(`<span class="tag">stable: ≥ ${pct}%</span>`);
          parts.push(`</div>`);
        }
        nd.innerHTML = parts.join('') || '<div class="hint">暂无节点心跳</div>';
      }

      // aggregate metrics chart
      drApi('/metrics?hours=24', 'GET').then(res => {
        renderDrBlockRateChart(res.code === 0 ? res.data : null);
      });

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

    let drChart = null;

    function renderDrBlockRateChart(metrics) {
      if (!metrics) {
        if (drChart) { drChart.destroy(); drChart = null; }
        return;
      }
      const series = metrics.series || [];
      const series1m = metrics.series_1m || [];
      const cutoff = Date.now() - 2 * 3600 * 1000;
      const hourPoints = series.filter(p => {
        const t = new Date(p.bucket.replace(' ', 'T')).getTime();
        return !isNaN(t) && t < cutoff;
      });
      const merged = hourPoints.concat(series1m).sort((a, b) =>
        new Date(a.bucket.replace(' ', 'T')) - new Date(b.bucket.replace(' ', 'T'))
      );
      if (!merged.length) {
        if (drChart) { drChart.destroy(); drChart = null; }
        return;
      }
      const labels = merged.map(p => {
        const d = new Date(p.bucket.replace(' ', 'T'));
        return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
      });
      const blockRate = merged.map(p => {
        const t = p.total_requests || 0;
        return t > 0 ? ((p.block || 0) / t * 100) : null;
      });
      const totals = merged.map(p => p.total_requests || 0);
      const datasets = [
        {
          label: 'block_rate (%)',
          data: blockRate,
          yAxisID: 'y',
          borderColor: '#ef4444',
          backgroundColor: 'rgba(239,68,68,0.1)',
          fill: true,
          tension: 0.2,
          pointRadius: 0,
        },
        {
          label: 'total_requests',
          data: totals,
          yAxisID: 'y1',
          borderColor: '#94a3b8',
          backgroundColor: 'rgba(148,163,184,0.08)',
          fill: true,
          tension: 0.2,
          pointRadius: 0,
        },
      ];
      if (drChart) {
        drChart.data.labels = labels;
        drChart.data.datasets.forEach((ds, i) => ds.data = datasets[i].data);
        drChart.update('none');
      } else {
        drChart = new Chart(document.getElementById('drBlockRateChart'), {
          type: 'line',
          data: { labels, datasets },
          options: {
            responsive: true,
            maintainAspectRatio: false,
            spanGaps: true,
            interaction: { mode: 'index', intersect: false },
            scales: {
              y: {
                type: 'linear',
                position: 'left',
                title: { display: true, text: 'block_rate %' },
                min: 0,
                ticks: { callback: v => v + '%' },
              },
              y1: {
                type: 'linear',
                position: 'right',
                title: { display: true, text: 'total_requests' },
                grid: { drawOnChartArea: false },
              },
            },
            plugins: {
              legend: { position: 'bottom', labels: { boxWidth: 12, padding: 12, font: { size: 10 } } },
            },
          },
        });
      }
    }

    function drRenderDiff(data) {
      const area = document.getElementById('drDiffArea');
      const summary = document.getElementById('drDiffSummary');
      const list = document.getElementById('drDiffList');
      document.getElementById('drDiffLoading').style.display = 'none';
      area.style.display = 'block';
      const s = data && data.summary ? data.summary : { added: 0, removed: 0, modified: 0 };
      const baseVer = (data && data.base_version) || '首次发布';
      const parts = [];
      if (s.added > 0) parts.push('新增 ' + s.added);
      if (s.removed > 0) parts.push('删除 ' + s.removed);
      if (s.modified > 0) parts.push('修改 ' + s.modified);
      summary.textContent = '基于 ' + baseVer + ' 的变更' + (parts.length ? '：' + parts.join('，') : '（无变更）');
      list.innerHTML = '';
      if (data && data.layers) {
        for (const [layer, rules] of Object.entries(data.layers)) {
          for (const r of rules) {
            const div = document.createElement('div');
            div.style.cssText = 'font-size:0.8rem;padding:0.15rem 0;display:flex;gap:0.35rem;align-items:center';
            const tag = document.createElement('span');
            tag.className = 'tag';
            tag.style.cssText = 'font-size:0.7rem';
            if (r.change === 'added') { tag.textContent = '新增'; tag.style.background = '#16a34a'; tag.style.color = '#fff'; }
            else if (r.change === 'removed') { tag.textContent = '删除'; tag.style.background = '#dc2626'; tag.style.color = '#fff'; }
            else if (r.change === 'modified') { tag.textContent = '修改'; tag.style.background = '#d97706'; tag.style.color = '#fff'; }
            else { tag.textContent = r.change; }
            div.appendChild(tag);
            const idSpan = document.createElement('span');
            idSpan.textContent = r.rule_id;
            div.appendChild(idSpan);
            const layerTag = document.createElement('span');
            layerTag.className = 'tag';
            layerTag.style.cssText = 'font-size:0.7rem;background:#e2e8f0';
            layerTag.textContent = r.layer || layer;
            div.appendChild(layerTag);
            list.appendChild(div);
          }
        }
      }
    }

    function drOpenVersionModal(layer, label) {
      const desc = document.getElementById('drDescription').value.trim();
      document.getElementById('drDiffArea').style.display = 'none';
      document.getElementById('drDiffLoading').style.display = 'block';
      const bundleId = document.getElementById('bundleId').value;
      const diffUrl = layer
        ? '/diff-rules?bundle_id=' + encodeURIComponent(bundleId) + '&layer=' + encodeURIComponent(layer)
        : '/diff-rules?bundle_id=' + encodeURIComponent(bundleId);
      Promise.all([
        drApi('/next-version?bundle_id=' + encodeURIComponent(bundleId), 'GET'),
        drApi(diffUrl, 'GET')
      ]).then(([verRes, diffRes]) => {
        const nextVer = (verRes.code === 0 && verRes.data) ? verRes.data.version : '';
        document.getElementById('drVersionInput').value = nextVer;
        document.getElementById('drVersionLayer').value = layer;
        window._drPendingPrepare = { desc, label };
        document.getElementById('drVersionModal').style.display = 'flex';
        drRenderDiff(diffRes.code === 0 ? diffRes.data : null);
      }).catch(() => {
        document.getElementById('drDiffLoading').style.display = 'none';
        document.getElementById('drVersionModal').style.display = 'flex';
      });
    }

    function drCloseVersionModal() {
      document.getElementById('drVersionModal').style.display = 'none';
      document.getElementById('drDiffLoading').style.display = 'none';
    }

    document.addEventListener('DOMContentLoaded', function() {
      document.getElementById('drPrepareEngine').addEventListener('click', () => drOpenVersionModal('cloud', 'Engine 灰度'));
      document.getElementById('drPrepareGateway').addEventListener('click', () => drOpenVersionModal('gateway', 'Gateway 灰度'));
      document.getElementById('drPrepareEdge').addEventListener('click', () => drOpenVersionModal('edge', 'Edge 灰度'));
      document.getElementById('drPrepareAll').addEventListener('click', () => drOpenVersionModal('', '三层灰度'));

      document.getElementById('drVersionCancel').addEventListener('click', drCloseVersionModal);
      document.getElementById('drVersionModal').addEventListener('click', (e) => { if (e.target === e.currentTarget) drCloseVersionModal(); });
      document.getElementById('drVersionConfirm').addEventListener('click', async () => {
        const modal = document.getElementById('drVersionModal');
        modal.style.display = 'none';
        const version = document.getElementById('drVersionInput').value.trim();
        const layer = document.getElementById('drVersionLayer').value;
        const { desc, label } = window._drPendingPrepare || { desc: '', label: '' };
        const res = await drApi('/prepare', 'POST', {
          bundle_id: document.getElementById('bundleId').value,
          bundle_version: version || undefined,
          layer: layer,
          description: desc
        });
        if (res.code === 0) {
          drLog(label + ' 准备完成 deploy_id=' + res.data.deploy_id + ' bundle=' + (res.data.bundle_id || 'auto'), true);
          drRefresh();
        } else {
          drLog('准备失败: ' + (res.message || JSON.stringify(res)), false);
        }
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

      document.getElementById('drFinalize').addEventListener('click', async () => {
        if (!confirm('⚠ 确认完结部署？\n\n完结后将：\n  · 把新版本指针切换为稳定版\n  · 关闭回退通道（不可再 rollback）\n  · 清理 canary 文件（edge）\n  · 归档当前 rollout 记录\n\n建议在线上指标观察期通过后再点。')) return;
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
