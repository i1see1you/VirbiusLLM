    let qaMetas = [];
    let qaSelected = null;
    let qaRows = [];
    let qaDirty = false;
    let qaPollTimer = null;
    let qaPollPublishId = null;

    const QA_ACTIVE_JOB_STATES = ['pending', 'checking', 'publishing', 'waiting_ack'];
    const QA_POLL_MS = 2000;
    const QA_LIMITS = { entries: 10000, regex: 200, question: 512, answer: 4096, variants: 20 };

    function qaIsDirty() { return qaDirty; }
    function resetQaDirty() { qaDirty = false; updateQaEntriesHint(); }
    function markQaDirty() { qaDirty = true; updateQaEntriesHint(); }

    function qaIdOf(m) { return field(m, 'qa_id', 'qaId'); }
    function qaRolloutOf(m) { return field(m, 'rollout_state', 'rolloutState') || 'dry_run'; }
    function qaCanaryOf(m) { const v = field(m, 'canary_percent', 'canaryPercent'); return v == null ? 0 : v; }

    function qaStatusTag(st) {
      const s = st || 'enabled';
      const cls = s === 'disabled' ? 'disabled' : 'allow';
      return `<span class="tag ${cls}">${esc(s)}</span>`;
    }

    function qaJobTag(st) {
      const s = st || 'pending';
      const cls = s === 'failed' ? 'deny' : (s === 'acked' ? 'allow' : 'draft');
      return `<span class="tag ${cls}">${esc(s)}</span>`;
    }

    function qaFormatErrors(s) {
      if (!s) return '—';
      let arr = null;
      try { arr = JSON.parse(s); } catch (_) {}
      if (Array.isArray(arr)) return esc(arr.join('; ')) || '—';
      return esc(String(s));
    }

    function updateQaEntriesHint() {
      const el = document.getElementById('qaEntriesHint');
      if (!el) return;
      const n = document.querySelectorAll('#qaEntriesTable tbody tr').length;
      el.textContent = __('qa.entries-count', n) + (qaDirty ? ' · ' + __('qa.unsaved') : '');
    }

    function renderQaList() {
      const tbody = document.querySelector('#qaTable tbody');
      if (!tbody) return;
      tbody.innerHTML = '';
      if (!qaMetas.length) {
        const tr = document.createElement('tr');
        tr.innerHTML = `<td colspan="8" class="hint">${esc(__('qa.none'))}</td>`;
        tbody.appendChild(tr);
        return;
      }
      qaMetas.forEach(m => {
        const id = qaIdOf(m);
        const tr = document.createElement('tr');
        if (qaSelected && qaIdOf(qaSelected) === id) tr.classList.add('qa-row-active');
        const rollout = qaRolloutOf(m) + (qaRolloutOf(m) === 'canary' ? ' ' + esc(String(qaCanaryOf(m))) + '%' : '');
        tr.innerHTML = `<td><strong>${esc(m.name || '—')}</strong>${m.description ? '<br><span class="hint">' + esc(m.description) + '</span>' : ''}</td>
          <td><code>${esc(id)}</code></td>
          <td>${qaStatusTag(m.status)}</td>
          <td>${esc(rollout)}</td>
          <td>${esc(String(field(m, 'revision') != null ? field(m, 'revision') : '—'))}</td>
          <td>${esc(fmtTimeAgo(field(m, 'updated_at', 'updatedAt')))}</td>
          <td><button class="secondary" data-open="${escAttr(id)}">${__('qa.btn-entries')}</button></td>
          <td><button class="danger" data-del="${escAttr(id)}" data-name="${escAttr(m.name || '')}">${__('common.delete')}</button></td>`;
        tbody.appendChild(tr);
      });
      tbody.querySelectorAll('button[data-open]').forEach(b => {
        b.onclick = () => openQa(b.dataset.open).catch(e => log(e.message, 'err'));
      });
      tbody.querySelectorAll('button[data-del]').forEach(b => {
        b.onclick = () => deleteQa(b.dataset.del, b.dataset.name).catch(e => log(e.message, 'err'));
      });
    }

    function toggleQaCanary() {
      const isCanary = document.getElementById('qaRollout').value === 'canary';
      document.getElementById('qaCanaryWrap').style.display = isCanary ? '' : 'none';
    }

    function fillQaMetaForm(m) {
      document.getElementById('qaName').value = m.name || '';
      document.getElementById('qaDesc').value = m.description || '';
      document.getElementById('qaRollout').value = qaRolloutOf(m);
      document.getElementById('qaCanary').value = qaCanaryOf(m);
      toggleQaCanary();
    }

    async function loadQa() {
      const data = await admin('/qa');
      qaMetas = Array.isArray(data) ? data : [];
      if (qaSelected) {
        const found = qaMetas.find(m => qaIdOf(m) === qaIdOf(qaSelected));
        if (found) { qaSelected = found; } else { clearQaSelection(); }
      }
      renderQaList();
    }

    function clearQaSelection() {
      stopQaPoll();
      qaSelected = null;
      qaRows = [];
      resetQaDirty();
      document.getElementById('qaEditor').style.display = 'none';
      document.getElementById('qaPublish').style.display = 'none';
      document.getElementById('btnQaMetaSave').style.display = 'none';
      document.getElementById('btnQaMetaCancel').style.display = 'none';
      document.getElementById('qaEditorTitle').textContent = '';
      document.getElementById('qaJobList').innerHTML = '';
      document.getElementById('qaAckPanel').innerHTML = '';
      document.querySelector('#qaEntriesTable tbody').innerHTML = '';
    }

    async function openQa(qaId) {
      if (qaDirty && qaSelected && qaIdOf(qaSelected) !== qaId && !confirm(__('qa.confirm-unsaved'))) return;
      resetQaDirty();
      qaSelected = qaMetas.find(m => qaIdOf(m) === qaId) || null;
      renderQaList();
      if (!qaSelected) return;
      fillQaMetaForm(qaSelected);
      document.getElementById('qaEditor').style.display = '';
      document.getElementById('qaPublish').style.display = '';
      document.getElementById('btnQaMetaSave').style.display = '';
      document.getElementById('btnQaMetaCancel').style.display = '';
      document.getElementById('qaEditorTitle').textContent = qaSelected.name || qaId;
      await Promise.all([loadQaEntries(), loadQaJobs(), loadQaAck()]);
    }

    function qaPrecheckCell(e) {
      const at = field(e, 'last_precheck_at', 'lastPrecheckAt');
      if (!at) return `<span class="hint">${esc(__('common.none'))}</span>`;
      const ok = field(e, 'last_precheck_ok', 'lastPrecheckOk') === true;
      const cls = ok ? 'allow' : 'deny';
      const txt = ok ? __('qa.precheck-ok') : __('qa.precheck-fail');
      return `<span class="tag ${cls}" title="${escAttr(fmtTime(at))}">${esc(txt)}</span>`;
    }

    function qaEntryRow(e) {
      const tr = document.createElement('tr');
      const variants = field(e, 'variants');
      const variantsText = (Array.isArray(variants) && variants.length) ? variants.join('\n') : '';
      const modes = ['exact', 'contains', 'regex'];
      const mode = field(e, 'match_mode', 'matchMode') || 'exact';
      const enabled = field(e, 'enabled') !== false;
      const priority = field(e, 'priority');
      tr.innerHTML = `<td><input type="checkbox" class="qa-enabled" ${enabled ? 'checked' : ''} /></td>
        <td><input type="text" class="qa-entry-id" size="10" value="${escAttr(field(e, 'entry_id', 'entryId') || '')}" placeholder="auto" /></td>
        <td><input type="text" class="qa-question" size="16" value="${escAttr(field(e, 'question') || '')}" /></td>
        <td><textarea class="qa-variants" rows="2" placeholder="${escAttr(__('qa.variants-ph'))}">${esc(variantsText)}</textarea></td>
        <td><textarea class="qa-answer" rows="2">${esc(field(e, 'answer') || '')}</textarea></td>
        <td><select class="qa-mode">${modes.map(m => `<option value="${m}" ${mode === m ? 'selected' : ''}>${m}</option>`).join('')}</select></td>
        <td><input type="number" class="qa-priority" style="width:4.5rem" value="${escAttr(String(priority == null ? 0 : priority))}" /></td>
        <td><input type="text" class="qa-expires" size="12" placeholder="2026-01-01T00:00:00Z" value="${escAttr(field(e, 'expires_at', 'expiresAt') || '')}" /></td>
        <td class="qa-precheck-cell">${qaPrecheckCell(e)}</td>
        <td><button class="danger qa-del" title="${escAttr(__('common.delete'))}">×</button></td>`;
      tr.querySelector('.qa-del').onclick = () => { tr.remove(); markQaDirty(); };
      tr.querySelectorAll('input,textarea,select').forEach(inp => {
        inp.addEventListener('input', markQaDirty);
        inp.addEventListener('change', markQaDirty);
      });
      return tr;
    }

    function renderQaEntries() {
      const tbody = document.querySelector('#qaEntriesTable tbody');
      tbody.innerHTML = '';
      (qaRows || []).forEach(e => tbody.appendChild(qaEntryRow(e)));
      updateQaEntriesHint();
    }

    async function loadQaEntries() {
      const data = await admin(`/qa/${encodeURIComponent(qaIdOf(qaSelected))}/entries`);
      qaRows = Array.isArray(data) ? data : [];
      renderQaEntries();
      resetQaDirty();
    }

    function collectQaRows() {
      const items = [];
      document.querySelectorAll('#qaEntriesTable tbody tr').forEach(tr => {
        const variants = (tr.querySelector('.qa-variants').value || '')
          .split('\n').map(s => s.trim()).filter(Boolean);
        const exp = tr.querySelector('.qa-expires').value.trim();
        const row = {
          entry_id: tr.querySelector('.qa-entry-id').value.trim() || null,
          question: tr.querySelector('.qa-question').value.trim(),
          variants: variants,
          answer: tr.querySelector('.qa-answer').value,
          match_mode: tr.querySelector('.qa-mode').value,
          priority: Number(tr.querySelector('.qa-priority').value || 0),
          enabled: tr.querySelector('.qa-enabled').checked
        };
        if (exp) row.expires_at = exp;
        items.push({ row, tr });
      });
      return items;
    }

    function qaUnsupportedRegex(p) {
      return p.includes('(?=') || p.includes('(?!') || p.includes('(?<=') || p.includes('(?<!') || /\\[1-9]/.test(p);
    }

    function qaValidate(items) {
      if (!items.length) return { msg: __('qa.err-entries-required') };
      if (items.length > QA_LIMITS.entries) return { msg: __('qa.err-entries-max', QA_LIMITS.entries) };
      const ids = new Set();
      let regexCount = 0;
      for (const { row, tr } of items) {
        if (row.answer == null || !String(row.answer).trim()) return { msg: __('qa.err-answer-required'), tr };
        if (String(row.answer).length > QA_LIMITS.answer) return { msg: __('qa.err-answer-len', QA_LIMITS.answer), tr };
        if ((row.question || '').length > QA_LIMITS.question) return { msg: __('qa.err-question-len', QA_LIMITS.question), tr };
        if (row.variants.length > QA_LIMITS.variants) return { msg: __('qa.err-variants', QA_LIMITS.variants), tr };
        if (!['exact', 'contains', 'regex'].includes(row.match_mode)) return { msg: __('qa.err-mode'), tr };
        const patterns = [row.question].concat(row.variants).filter(p => p && p.trim());
        if (!patterns.length) return { msg: __('qa.err-question-required'), tr };
        if (row.entry_id) {
          if (ids.has(row.entry_id)) return { msg: __('qa.err-dup-id', row.entry_id), tr };
          ids.add(row.entry_id);
        }
        if (row.match_mode === 'regex') {
          regexCount++;
          for (const p of patterns) {
            if (qaUnsupportedRegex(p)) return { msg: __('qa.err-regex-unsupported', p), tr };
            try { new RegExp(p); } catch (_) { return { msg: __('qa.err-regex-invalid', p), tr }; }
          }
        }
      }
      if (regexCount > QA_LIMITS.regex) return { msg: __('qa.err-regex-count', QA_LIMITS.regex) };
      return null;
    }

    function markQaInvalid(tr) {
      if (!tr) return;
      tr.classList.add('qa-invalid');
      tr.scrollIntoView({ block: 'center' });
      setTimeout(() => tr.classList.remove('qa-invalid'), 3000);
    }

    async function saveQaEntries() {
      if (!qaSelected) return;
      const items = collectQaRows();
      const err = qaValidate(items);
      if (err) { markQaInvalid(err.tr); log(err.msg, 'err'); return; }
      setBtnLoading('btnQaEntriesSave', true);
      try {
        const saved = await admin(`/qa/${encodeURIComponent(qaIdOf(qaSelected))}/entries`, {
          method: 'PUT',
          body: JSON.stringify({ entries: items.map(i => i.row) })
        });
        qaRows = Array.isArray(saved) ? saved : [];
        renderQaEntries();
        resetQaDirty();
        log(__('qa.saved'), 'ok');
      } finally {
        setBtnLoading('btnQaEntriesSave', false);
      }
    }

    async function loadQaJobs() {
      const jobs = await admin(`/qa/${encodeURIComponent(qaIdOf(qaSelected))}/jobs`);
      const list = Array.isArray(jobs) ? jobs.slice().reverse() : [];
      const active = list.find(j => QA_ACTIVE_JOB_STATES.includes(j.status));
      document.getElementById('btnQaPublish').disabled = !!active;
      document.getElementById('qaPublishHint').textContent =
        active ? __('qa.publish-active', active.status) : '';
      const box = document.getElementById('qaJobList');
      if (!list.length) {
        box.innerHTML = `<p class="hint">${esc(__('qa.job-none'))}</p>`;
        return list;
      }
      box.innerHTML = `<table class="data-table" style="width:100%"><thead><tr>
          <th>${esc(__('qa.job-id'))}</th><th>${esc(__('qa.job-status'))}</th>
          <th>${esc(__('qa.col-updated'))}</th><th>${esc(__('qa.job-errors'))}</th></tr></thead><tbody>` +
        list.slice(0, 10).map(j => `<tr><td><code>${esc(field(j, 'publish_id', 'publishId'))}</code></td>
          <td>${qaJobTag(j.status)}</td><td>${esc(fmtTime(field(j, 'updated_at', 'updatedAt')))}</td>
          <td>${qaFormatErrors(j.errors)}</td></tr>`).join('') +
        '</tbody></table>';
      return list;
    }

    async function loadQaAck() {
      const ack = await admin(`/qa/${encodeURIComponent(qaIdOf(qaSelected))}/ack`);
      const acks = (ack && ack.acks) ? ack.acks : {};
      const entries = Object.entries(acks);
      const revision = ack && field(ack, 'revision') != null ? field(ack, 'revision') : '—';
      let html = `<h3 style="margin:0.5rem 0 0.25rem;font-size:0.9rem">${esc(__('qa.ack-title'))}</h3>
        <p class="hint" style="margin:0 0 0.5rem">${esc(__('qa.ack-revision'))} <code>${esc(String(revision))}</code> · ${esc(__('qa.ack-instances', entries.length))}</p>`;
      if (!entries.length) {
        html += `<p class="hint">${esc(__('qa.ack-none'))}</p>`;
      } else {
        html += `<table class="data-table" style="width:100%"><thead><tr>
            <th>${esc(__('qa.ack-instance'))}</th><th>revision</th><th>${esc(__('qa.ack-status'))}</th>
            <th>${esc(__('qa.ack-updated'))}</th><th>errors</th></tr></thead><tbody>` +
          entries.map(([inst, a]) => {
            const bad = a.status && a.status !== 'ok';
            return `<tr><td><code>${esc(inst)}</code></td><td>${esc(String(field(a, 'revision') != null ? field(a, 'revision') : '—'))}</td>
              <td>${bad ? `<span class="tag deny">${esc(a.status)}</span>` : '<span class="tag allow">ok</span>'}</td>
              <td>${esc(fmtTime(field(a, 'updated_at', 'updatedAt')))}</td><td>${qaFormatErrors(a.errors)}</td></tr>`;
          }).join('') + '</tbody></table>';
      }
      document.getElementById('qaAckPanel').innerHTML = html;
    }

    function stopQaPoll() {
      if (qaPollTimer) { clearInterval(qaPollTimer); qaPollTimer = null; }
    }

    function startQaPoll() {
      stopQaPoll();
      qaPollTimer = setInterval(() => { pollQaJob().catch(e => log(e.message, 'err')); }, QA_POLL_MS);
    }

    async function pollQaJob() {
      const panel = document.getElementById('panel-qa');
      if (!qaSelected || !panel.classList.contains('active')) { stopQaPoll(); return; }
      const list = await loadQaJobs();
      await loadQaAck();
      const target = (list || []).find(j => field(j, 'publish_id', 'publishId') === qaPollPublishId);
      if (target && (target.status === 'acked' || target.status === 'failed')) {
        stopQaPoll();
        if (target.status === 'acked') {
          log(__('qa.job-acked'), 'ok');
          const metas = await admin('/qa');
          qaMetas = Array.isArray(metas) ? metas : qaMetas;
          const found = qaMetas.find(m => qaIdOf(m) === qaIdOf(qaSelected));
          if (found) { qaSelected = found; fillQaMetaForm(found); }
          renderQaList();
          await loadQaEntries();
        } else {
          log(__('qa.job-failed'), 'err');
        }
      }
    }

    async function publishQa() {
      if (!qaSelected) return;
      setBtnLoading('btnQaPublish', true);
      try {
        const job = await admin(`/qa/${encodeURIComponent(qaIdOf(qaSelected))}/publish`, { method: 'POST' });
        qaPollPublishId = job ? field(job, 'publish_id', 'publishId') : null;
        log(__('qa.published', qaPollPublishId || ''), 'ok');
        await loadQaJobs();
        startQaPoll();
      } finally {
        setBtnLoading('btnQaPublish', false);
      }
    }

    async function deleteQa(qaId, name) {
      if (!confirm(__('qa.confirm-delete', name || qaId))) return;
      await admin(`/qa/${encodeURIComponent(qaId)}`, { method: 'DELETE' });
      if (qaSelected && qaIdOf(qaSelected) === qaId) clearQaSelection();
      await loadQa();
      log(__('qa.deleted'), 'ok');
    }

    function qaMetaBody() {
      return {
        name: document.getElementById('qaName').value.trim(),
        description: document.getElementById('qaDesc').value.trim() || null,
        rollout_state: document.getElementById('qaRollout').value,
        canary_percent: Number(document.getElementById('qaCanary').value || 0)
      };
    }

    document.getElementById('btnQaCreate').onclick = async () => {
      const body = qaMetaBody();
      if (!body.name) { log(__('qa.err-name-required'), 'warn'); return; }
      try {
        const meta = await admin('/qa', { method: 'POST', body: JSON.stringify(body) });
        document.getElementById('qaName').value = '';
        document.getElementById('qaDesc').value = '';
        await loadQa();
        const createdId = meta ? qaIdOf(meta) : null;
        if (createdId) await openQa(createdId);
        log(__('qa.created'), 'ok');
      } catch (e) { log(e.message, 'err'); }
    };

    document.getElementById('btnQaMetaSave').onclick = async () => {
      if (!qaSelected) return;
      const body = qaMetaBody();
      if (!body.name) { log(__('qa.err-name-required'), 'warn'); return; }
      try {
        const meta = await admin(`/qa/${encodeURIComponent(qaIdOf(qaSelected))}`, {
          method: 'PUT',
          body: JSON.stringify(body)
        });
        qaSelected = meta || qaSelected;
        await loadQa();
        fillQaMetaForm(qaSelected);
        renderQaList();
        log(__('qa.meta-saved'), 'ok');
      } catch (e) { log(e.message, 'err'); }
    };

    document.getElementById('btnQaMetaCancel').onclick = () => {
      if (qaDirty && !confirm(__('qa.confirm-unsaved'))) return;
      clearQaSelection();
      renderQaList();
    };

    document.getElementById('qaRollout').onchange = toggleQaCanary;

    document.getElementById('btnQaEntryAdd').onclick = () => {
      const tbody = document.querySelector('#qaEntriesTable tbody');
      tbody.appendChild(qaEntryRow({ enabled: true, match_mode: 'exact', priority: 0 }));
      markQaDirty();
    };

    document.getElementById('btnQaEntriesSave').onclick = () => {
      saveQaEntries().catch(e => log(e.message, 'err'));
    };

    document.getElementById('btnQaEntriesReload').onclick = async () => {
      if (!qaSelected) return;
      if (qaDirty && !confirm(__('qa.confirm-unsaved'))) return;
      try { await loadQaEntries(); } catch (e) { log(e.message, 'err'); }
    };

    document.getElementById('btnQaPublish').onclick = () => {
      publishQa().catch(e => log(e.message, 'err'));
    };
