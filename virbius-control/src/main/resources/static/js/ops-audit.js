    function renderAuditEventRow(h) {
      return `<td>${esc(fmtTime(h.intercepted_at))}</td>
        <td>${esc(h.layer || '')}</td><td>${esc(h.scene || '')}</td>
        <td>${esc(h.effective_action || '')}</td>
        <td>${esc(h.rule_id || '')}@${h.rule_revision ?? ''}</td>
        <td>${esc(h.reason_code || '')}</td>
        <td>${h.max_risk_score ?? ''}</td>
        <td>${esc(rolloutStateLabel(h.rollout_state, h.canary_percent))}</td>`;
    }

    async function searchAuditCenter(traceId) {
      const tid = (traceId || document.getElementById('acTraceId').value || '').trim();
      if (!tid) {
        log(__('ac.input-trace-id'), 'warn');
        return;
      }
      document.getElementById('acTraceId').value = tid;
      const summary = document.getElementById('acSummary');
      const dbTbody = document.querySelector('#acDbTable tbody');
      const allowTbody = document.querySelector('#acAllowTable tbody');
      const filesTbody = document.querySelector('#acLogFilesTable tbody');
      summary.textContent = __('ac.searching');
      dbTbody.innerHTML = '<tr><td colspan="8" class="hint">' + __('common.loading') + '</td></tr>';
      allowTbody.innerHTML = '<tr><td colspan="8" class="hint">' + __('common.loading') + '</td></tr>';
      try {
        const detail = await admin('/audit/trace/' + encodeURIComponent(tid));
        document.getElementById('acDbCount').textContent = detail.db_count ?? 0;
        document.getElementById('acAllowCount').textContent = detail.allow_log_count ?? 0;
        summary.textContent = detail.note || '';
        dbTbody.innerHTML = '';
        (detail.db_events || []).forEach(h => {
          const tr = document.createElement('tr');
          tr.innerHTML = renderAuditEventRow(h);
          dbTbody.appendChild(tr);
        });
        if (!(detail.db_events || []).length) {
          dbTbody.innerHTML = '<tr><td colspan="8" class="hint">' + __('ac.no-db-records') + '</td></tr>';
        }
        allowTbody.innerHTML = '';
        (detail.allow_log_events || []).forEach(h => {
          const tr = document.createElement('tr');
          tr.innerHTML = renderAuditEventRow(h);
          allowTbody.appendChild(tr);
        });
        if (!(detail.allow_log_events || []).length) {
          allowTbody.innerHTML = '<tr><td colspan="8" class="hint">' + __('ac.no-allow-records') + '</td></tr>';
        }
        filesTbody.innerHTML = '';
        (detail.allow_log_files || []).forEach(f => {
          const tr = document.createElement('tr');
          tr.innerHTML = `<td>${esc(f.label || '')}</td><td><code>${esc(f.path || '')}</code></td>
            <td>${f.exists ? __('common.yes') : __('common.no')}</td>`;
          filesTbody.appendChild(tr);
        });
        if (!(detail.allow_log_files || []).length) {
          filesTbody.innerHTML = '<tr><td colspan="3" class="hint">—</td></tr>';
        }
        log({ trace_id: tid, db_count: detail.db_count, allow_log_count: detail.allow_log_count });
      } catch (e) {
        summary.textContent = e.message;
        dbTbody.innerHTML = `<tr><td colspan="8" class="hint">${esc(e.message)}</td></tr>`;
        allowTbody.innerHTML = '<tr><td colspan="8" class="hint">—</td></tr>';
        filesTbody.innerHTML = '<tr><td colspan="3" class="hint">—</td></tr>';
      }
    }

    document.getElementById('acSearch').onclick = () => {
      searchAuditCenter().catch(e => log(e.message, 'err'));
    };
    document.getElementById('acTraceId').addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        searchAuditCenter().catch(err => log(err.message, 'err'));
      }
    });
