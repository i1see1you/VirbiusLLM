    async function loadSceneRegistry() {
      sceneRegistryHydrating = true;
      try {
        const data = await admin(`/bundles/${encodeURIComponent(bundleId())}/versions/${encodeURIComponent(bundleVer())}/metadata`);
        const reg = data.scene_registry || {};
        document.getElementById('srFailUnknown').checked = !!reg.fail_on_unknown_app;
        document.getElementById('srFailUnresolved').checked = !!reg.fail_on_unresolved_scene;
        sceneEntries = (data.scene_entries || []).map(r => ({
          sceneId: r.scene_id || '',
          appId: r.app_id || '',
          defaultScene: !!r.default,
          uris: Array.isArray(r.uris) ? r.uris : [],
          priority: r.priority != null ? Number(r.priority) : 0,
          matchQuery: (r.match && r.match.query) ? { ...r.match.query } : {}
        }));
        renderSceneRegistryTable();
      } finally {
        sceneRegistryHydrating = false;
      }
    }

    async function saveSceneRegistry(sync) {
      if (!sceneEntries.length) {
        throw new Error(__('sr.at-least-one'));
      }
      const data = await admin(
        `/bundles/${encodeURIComponent(bundleId())}/versions/${encodeURIComponent(bundleVer())}/metadata/scene-registry?sync=${sync}`,
        { method: 'PUT', body: JSON.stringify(readSceneRegistryPayload()) }
      );
      log(sync ? __('sr.synced') : __('sr.saved'), 'ok');
      await loadSceneRegistry();
      return data;
    }

    function onSceneRegistryFlagsChange() {
      if (sceneRegistryHydrating || !sceneEntries.length) return;
      saveSceneRegistry(false).catch(e => {
        log(e.message, 'err');
        loadSceneRegistry();
      });
    }

    function formatMatchQuery(q) {
      if (!q || !Object.keys(q).length) return '—';
      return Object.entries(q).map(([k, v]) => `${k}=${v}`).join(', ');
    }

    function renderSceneRegistryTable() {
      const tbody = document.querySelector('#sceneRegTable tbody');
      tbody.innerHTML = '';
      sceneEntries.forEach((row, idx) => {
        const tr = document.createElement('tr');
        tr.innerHTML = `<td><code>${esc(row.sceneId)}</code></td>
          <td><code>${esc(row.appId)}</code></td>
          <td>${row.defaultScene ? '✓' : '—'}</td>
          <td><code>${esc((row.uris || []).join(', '))}</code></td>
          <td>${row.priority != null ? row.priority : 0}</td>
          <td><code>${esc(formatMatchQuery(row.matchQuery))}</code></td>
          <td><button class="danger">${__('common.delete')}</button></td>`;
        tr.querySelector('button').onclick = async () => {
          if (sceneEntries.length <= 1) {           log(__('sr.at-least-one-warn'), 'warn'); return; }
          sceneEntries.splice(idx, 1);
          renderSceneRegistryTable();
          try {
            await saveSceneRegistry(false);
          } catch (e) {
            log(e.message, 'err');
            await loadSceneRegistry();
          }
        };
        tbody.appendChild(tr);
      });
    }

    function readSceneRegistryPayload() {
      return {
        fail_on_unknown_app: document.getElementById('srFailUnknown').checked,
        fail_on_unresolved_scene: document.getElementById('srFailUnresolved').checked,
        scenes: sceneEntries.map(e => {
          const row = {
            scene_id: e.sceneId,
            app_id: e.appId,
            default_scene: e.defaultScene,
            uris: e.uris && e.uris.length ? e.uris : [],
            priority: e.priority != null ? e.priority : 0
          };
          if (e.matchQuery && Object.keys(e.matchQuery).length) row.match_query = e.matchQuery;
          return row;
        })
      };
    }

    document.getElementById('srFailUnknown').onchange = onSceneRegistryFlagsChange;
    document.getElementById('srFailUnresolved').onchange = onSceneRegistryFlagsChange;

    document.getElementById('btnSceneRegAdd').onclick = async () => {
      const sceneId = document.getElementById('srSceneId').value.trim();
      const appId = document.getElementById('srAppId').value.trim();
      const uriRaw = document.getElementById('srUris').value.trim();
      if (!sceneId || !appId) { log(__('sr.fill-id-and-app'), 'warn'); return; }
      if (sceneEntries.some(e => e.sceneId === sceneId)) { log(__('sr.id-exists'), 'warn'); return; }
      const matchQuery = {};
      const mq = document.getElementById('srMatchQuery').value.trim();
      if (mq && mq.includes('=')) {
        const eq = mq.indexOf('=');
        matchQuery[mq.slice(0, eq).trim()] = mq.slice(eq + 1).trim();
      }
      sceneEntries.push({
        sceneId,
        appId,
        defaultScene: document.getElementById('srDefault').checked,
        uris: uriRaw ? uriRaw.split(',').map(s => s.trim()).filter(Boolean) : [],
        priority: Number(document.getElementById('srPriority').value) || 0,
        matchQuery
      });
      document.getElementById('srSceneId').value = '';
      document.getElementById('srAppId').value = '';
      document.getElementById('srDefault').checked = false;
      document.getElementById('srUris').value = '';
      document.getElementById('srMatchQuery').value = '';
      renderSceneRegistryTable();
      try {
        await saveSceneRegistry(false);
      } catch (e) {
        log(e.message, 'err');
        await loadSceneRegistry();
      }
    };
    document.getElementById('btnSyncSceneRegistry').onclick = () =>
      saveSceneRegistry(true).catch(e => log(e.message, 'err'));
