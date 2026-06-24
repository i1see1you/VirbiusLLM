    async function loadBindings() {
      const data = await admin(`/bundles/${encodeURIComponent(bundleId())}/versions/${encodeURIComponent(bundleVer())}/metadata`);
      contextVars = (data.context_vars || []).map(v => ({
        logical: v.logical,
        from: v.from,
        name: v.name || '',
        field: v.field || ''
      }));
      renderBindingsTable();
      fillListVarLogicalSelect();
      fillCumVarLogicalSelect();
    }

    function renderBindingsTable() {
      const tbody = document.querySelector('#bindTable tbody');
      tbody.innerHTML = '';
      contextVars.forEach((row, idx) => {
        const wire = row.from === 'subject' || row.from === 'network' ? row.field : row.name;
        const tr = document.createElement('tr');
        tr.innerHTML = `<td><code>${esc(row.logical)}</code></td><td>${esc(row.from)}</td><td><code>${esc(wire)}</code></td>
                     <td><button class="danger" data-i="${idx}">${esc(__('common.delete'))}</button></td>`;
        tr.querySelector('button').onclick = () => { contextVars.splice(idx, 1); renderBindingsTable(); };
        tbody.appendChild(tr);
      });
    }

    document.getElementById('btnBindAdd').onclick = () => {
      const logical = document.getElementById('bLogical').value.trim();
      if (!logical) return;
      const from = document.getElementById('bFrom').value || 'query';
      const wireRaw = document.getElementById('bWire').value.trim();
      const wire = wireRaw || logical;
      const row = { logical, from, name: '', field: '' };
      if (from === 'subject' || from === 'network') row.field = wire;
      else row.name = wire;
      if (!contextVars.some(x => x.logical === logical)) contextVars.push(row);
      document.getElementById('bLogical').value = '';
      document.getElementById('bWire').value = '';
      renderBindingsTable();
    };
    document.getElementById('btnSaveBindings').onclick = () =>
      admin(`/bundles/${encodeURIComponent(bundleId())}/versions/${encodeURIComponent(bundleVer())}/metadata/context-bindings?sync=true`, {
        method: 'PUT',
        body: JSON.stringify({ vars: contextVars.map(v => {
          const m = { logical: v.logical, from: v.from };
          if (v.name) m.name = v.name;
          if (v.field) m.field = v.field;
          return m;
        })})
      }).then(log).catch(e => log(e.message, 'err'));
