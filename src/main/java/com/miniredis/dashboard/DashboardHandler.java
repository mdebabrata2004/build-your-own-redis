package com.miniredis.dashboard;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Serves the embedded developer dashboard web console.
 */
public final class DashboardHandler implements HttpHandler {

    private static final String DASHBOARD_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>LocalData | Managed Redis Platform Console</title>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;600&family=Plus+Jakarta+Sans:wght@400;500;600;700&display=swap" rel="stylesheet">
  <style>
    :root {
      --bg: #090d16;
      --card: #111827;
      --card-border: #1f2937;
      --text: #f3f4f6;
      --text-muted: #9ca3af;
      --primary: #dc2626;
      --primary-hover: #b91c1c;
      --accent: #3b82f6;
      --success: #10b981;
      --warning: #f59e0b;
      --danger: #ef4444;
      --mono: 'JetBrains Mono', monospace;
      --sans: 'Plus Jakarta Sans', sans-serif;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { background: var(--bg); color: var(--text); font-family: var(--sans); min-height: 100vh; display: flex; flex-direction: column; }
    header { background: #0f172a; border-bottom: 1px solid var(--card-border); padding: 1rem 2rem; display: flex; justify-content: space-between; align-items: center; }
    .brand { display: flex; align-items: center; gap: 0.75rem; font-weight: 700; font-size: 1.25rem; }
    .brand-badge { background: linear-gradient(135deg, #ef4444, #dc2626); color: white; padding: 0.2rem 0.6rem; border-radius: 6px; font-size: 0.8rem; font-family: var(--mono); }
    .header-stats { display: flex; gap: 1.5rem; font-size: 0.875rem; color: var(--text-muted); }
    .header-stats span { color: var(--text); font-weight: 600; font-family: var(--mono); }
    
    .nav-tabs { display: flex; gap: 1rem; padding: 1rem 2rem 0; border-bottom: 1px solid var(--card-border); background: #0b0f19; }
    .nav-tab { padding: 0.75rem 1.25rem; cursor: pointer; color: var(--text-muted); font-weight: 600; border-bottom: 2px solid transparent; transition: all 0.2s; }
    .nav-tab:hover { color: var(--text); }
    .nav-tab.active { color: var(--primary); border-bottom-color: var(--primary); }
    
    main { padding: 2rem; flex: 1; max-width: 1400px; margin: 0 auto; width: 100%; }
    .tab-content { display: none; }
    .tab-content.active { display: block; }
    
    .card { background: var(--card); border: 1px solid var(--card-border); border-radius: 12px; padding: 1.5rem; margin-bottom: 1.5rem; }
    .card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1.25rem; }
    .card-title { font-size: 1.125rem; font-weight: 600; }
    
    .grid-4 { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 1rem; margin-bottom: 1.5rem; }
    .stat-card { background: #0f172a; border: 1px solid var(--card-border); border-radius: 10px; padding: 1.25rem; }
    .stat-label { font-size: 0.8rem; text-transform: uppercase; letter-spacing: 0.05em; color: var(--text-muted); margin-bottom: 0.5rem; }
    .stat-val { font-size: 1.75rem; font-weight: 700; font-family: var(--mono); }
    
    table { width: 100%; border-collapse: collapse; font-size: 0.9rem; text-align: left; }
    th { background: #0d131f; padding: 0.75rem 1rem; color: var(--text-muted); font-weight: 600; border-bottom: 1px solid var(--card-border); }
    td { padding: 0.875rem 1rem; border-bottom: 1px solid var(--card-border); }
    tr:hover td { background: rgba(255, 255, 255, 0.02); }
    
    .btn { background: var(--primary); color: white; border: none; padding: 0.5rem 1rem; border-radius: 6px; font-weight: 600; cursor: pointer; font-family: var(--sans); font-size: 0.875rem; transition: background 0.2s; }
    .btn:hover { background: var(--primary-hover); }
    .btn-secondary { background: #1f2937; color: var(--text); border: 1px solid #374151; }
    .btn-secondary:hover { background: #374151; }
    .btn-danger { background: rgba(239, 68, 68, 0.2); color: #f87171; border: 1px solid rgba(239, 68, 68, 0.3); }
    .btn-danger:hover { background: rgba(239, 68, 68, 0.4); }
    
    .badge { display: inline-block; padding: 0.2rem 0.5rem; border-radius: 4px; font-size: 0.75rem; font-weight: 600; font-family: var(--mono); }
    .badge-success { background: rgba(16, 185, 129, 0.2); color: #34d399; }
    .badge-danger { background: rgba(239, 68, 68, 0.2); color: #f87171; }
    .badge-info { background: rgba(59, 130, 246, 0.2); color: #60a5fa; }
    
    .code-box { font-family: var(--mono); background: #06090e; padding: 0.75rem 1rem; border-radius: 6px; border: 1px solid var(--card-border); word-break: break-all; }
    
    input, select, textarea { width: 100%; background: #06090e; border: 1px solid var(--card-border); border-radius: 6px; padding: 0.6rem 0.8rem; color: var(--text); font-family: var(--sans); font-size: 0.9rem; margin-top: 0.4rem; }
    input:focus, select:focus, textarea:focus { outline: none; border-color: var(--accent); }
    
    .modal { display: none; position: fixed; inset: 0; background: rgba(0, 0, 0, 0.75); backdrop-filter: blur(4px); justify-content: center; align-items: center; z-index: 1000; }
    .modal.active { display: flex; }
    .modal-box { background: var(--card); border: 1px solid var(--card-border); border-radius: 12px; width: 100%; max-width: 520px; padding: 1.5rem; }
    .modal-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem; }
    .modal-actions { display: flex; justify-content: flex-end; gap: 0.75rem; margin-top: 1.25rem; }
  </style>
</head>
<body>
  <header>
    <div class="brand">
      <span class="brand-badge">REDIS-CORE</span>
      <span>LocalData Platform</span>
    </div>
    <div class="header-stats">
      <div>Uptime: <span id="hdr-uptime">-</span></div>
      <div>Active Conns: <span id="hdr-conns">0</span></div>
      <div>Total Keys: <span id="hdr-keys">0</span></div>
      <div>Engine: <span style="color:#10b981;">EMBEDDED-ACTIVE</span></div>
    </div>
  </header>

  <div class="nav-tabs">
    <div class="nav-tab active" onclick="switchTab('projects')">Projects & Namespaces</div>
    <div class="nav-tab" onclick="switchTab('keys')">API Keys & Security</div>
    <div class="nav-tab" onclick="switchTab('explorer')">Data Explorer</div>
    <div class="nav-tab" onclick="switchTab('metrics')">Metrics & Performance</div>
    <div class="nav-tab" onclick="switchTab('audit')">Audit Logs</div>
  </div>

  <main>
    <!-- TAB 1: PROJECTS -->
    <div id="tab-projects" class="tab-content active">
      <div class="card">
        <div class="card-header">
          <div class="card-title">Multi-Tenant Projects</div>
          <button class="btn" onclick="openModal('modal-create-project')">+ New Project</button>
        </div>
        <table>
          <thead>
            <tr>
              <th>ID</th>
              <th>Name</th>
              <th>Slug / Namespace</th>
              <th>Rate Limit (RPS)</th>
              <th>Max Storage</th>
              <th>Created</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody id="projects-table-body">
            <tr><td colspan="7" style="text-align:center;">Loading projects...</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- TAB 2: API KEYS -->
    <div id="tab-keys" class="tab-content">
      <div class="card">
        <div class="card-header">
          <div class="card-title">Scoped API Keys</div>
          <div style="display:flex; gap:0.5rem;">
            <select id="keys-project-select" onchange="loadKeysForSelectedProject()" style="width:220px; margin-top:0;"></select>
            <button class="btn" onclick="openModal('modal-create-key')">+ Generate Key</button>
          </div>
        </div>
        <table>
          <thead>
            <tr>
              <th>Key ID</th>
              <th>Name</th>
              <th>Prefix</th>
              <th>Scopes</th>
              <th>Status</th>
              <th>Last Used</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody id="keys-table-body">
            <tr><td colspan="7" style="text-align:center;">Select or create a project to inspect API keys.</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- TAB 3: DATA EXPLORER -->
    <div id="tab-explorer" class="tab-content">
      <div class="card">
        <div class="card-header">
          <div class="card-title">Interactive Data Explorer</div>
          <div style="display:flex; gap:0.5rem; flex-wrap:wrap;">
            <select id="explorer-project-select" style="width:200px; margin-top:0;"></select>
            <input type="text" id="explorer-api-key" placeholder="API Key (ld_live_...)" style="width:240px; margin-top:0;">
            <input type="text" id="explorer-pattern" placeholder="Search pattern (e.g. * or user:*)" style="width:220px; margin-top:0;">
            <button class="btn btn-secondary" onclick="scanDataKeys()">SCAN</button>
          </div>
        </div>
        <div style="display:grid; grid-template-columns: 1fr 1fr; gap:1.5rem;">
          <div>
            <h4 style="margin-bottom:0.75rem; color:var(--text-muted); font-size:0.85rem;">MATCHED KEYS</h4>
            <div id="explorer-key-list" class="code-box" style="height:350px; overflow-y:auto;">
              (Enter your API key and click SCAN to list keys)
            </div>
          </div>
          <div>
            <h4 style="margin-bottom:0.75rem; color:var(--text-muted); font-size:0.85rem;">KEY VALUE & OPERATIONS</h4>
            <div style="margin-bottom:0.75rem;">
              <input type="text" id="op-key" placeholder="Key name">
              <textarea id="op-value" rows="4" placeholder="Value (string or JSON)" style="margin-top:0.5rem;"></textarea>
              <div style="display:flex; gap:0.5rem; margin-top:0.5rem;">
                <input type="number" id="op-ttl" placeholder="TTL in seconds (0 = none)" style="width:180px; margin-top:0;">
                <button class="btn" onclick="executeSet()">SET</button>
                <button class="btn btn-secondary" onclick="executeGet()">GET</button>
                <button class="btn btn-danger" onclick="executeDel()">DEL</button>
              </div>
            </div>
            <div id="op-result" class="code-box" style="height:140px; overflow-y:auto; color:#34d399;">
              Ready.
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- TAB 4: METRICS -->
    <div id="tab-metrics" class="tab-content">
      <div class="grid-4">
        <div class="stat-card">
          <div class="stat-label">Total Requests</div>
          <div class="stat-val" id="stat-reqs">0</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">Hit Ratio</div>
          <div class="stat-val" id="stat-hits" style="color:#10b981;">100%</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">Average Latency</div>
          <div class="stat-val" id="stat-latency">0.0 ms</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">Rate Limited</div>
          <div class="stat-val" id="stat-ratelimited" style="color:#f59e0b;">0</div>
        </div>
      </div>
      <div class="card">
        <div class="card-header">
          <div class="card-title">Live System Telemetry</div>
          <button class="btn btn-secondary" onclick="loadMetrics()">Refresh</button>
        </div>
        <pre id="metrics-raw" class="code-box" style="height:250px; overflow-y:auto;"></pre>
      </div>
    </div>

    <!-- TAB 5: AUDIT LOGS -->
    <div id="tab-audit" class="tab-content">
      <div class="card">
        <div class="card-header">
          <div class="card-title">Administrative Audit Trail</div>
          <button class="btn btn-secondary" onclick="loadAuditLogs()">Refresh</button>
        </div>
        <table>
          <thead>
            <tr>
              <th>Timestamp</th>
              <th>Project</th>
              <th>Action</th>
              <th>Actor</th>
              <th>Details</th>
              <th>IP Address</th>
            </tr>
          </thead>
          <tbody id="audit-table-body">
            <tr><td colspan="6" style="text-align:center;">Loading audit trail...</td></tr>
          </tbody>
        </table>
      </div>
    </div>
  </main>

  <!-- MODAL: CREATE PROJECT -->
  <div id="modal-create-project" class="modal">
    <div class="modal-box">
      <div class="modal-header">
        <h3 class="card-title">Create Multi-Tenant Project</h3>
        <span style="cursor:pointer;" onclick="closeModal('modal-create-project')">&times;</span>
      </div>
      <div>
        <label style="font-size:0.85rem; color:var(--text-muted);">Project Name</label>
        <input type="text" id="new-proj-name" placeholder="e.g. E-Commerce Backend">
      </div>
      <div style="margin-top:0.75rem;">
        <label style="font-size:0.85rem; color:var(--text-muted);">Slug / Namespace</label>
        <input type="text" id="new-proj-slug" placeholder="e.g. ecommerce-prod">
      </div>
      <div style="display:flex; gap:0.5rem; margin-top:0.75rem;">
        <div style="flex:1;">
          <label style="font-size:0.85rem; color:var(--text-muted);">Rate Limit (RPS)</label>
          <input type="number" id="new-proj-rps" value="100">
        </div>
        <div style="flex:1;">
          <label style="font-size:0.85rem; color:var(--text-muted);">Burst Limit</label>
          <input type="number" id="new-proj-burst" value="200">
        </div>
      </div>
      <div class="modal-actions">
        <button class="btn btn-secondary" onclick="closeModal('modal-create-project')">Cancel</button>
        <button class="btn" onclick="submitCreateProject()">Create Project</button>
      </div>
    </div>
  </div>

  <!-- MODAL: CREATE API KEY -->
  <div id="modal-create-key" class="modal">
    <div class="modal-box">
      <div class="modal-header">
        <h3 class="card-title">Generate Scoped API Key</h3>
        <span style="cursor:pointer;" onclick="closeModal('modal-create-key')">&times;</span>
      </div>
      <div>
        <label style="font-size:0.85rem; color:var(--text-muted);">Key Label</label>
        <input type="text" id="new-key-name" placeholder="e.g. Server Lambda Key">
      </div>
      <div style="margin-top:0.75rem;">
        <label style="font-size:0.85rem; color:var(--text-muted);">Permission Scopes</label>
        <select id="new-key-scopes">
          <option value="read,write">Read & Write (Standard App)</option>
          <option value="read">Read Only (Cache Reader)</option>
          <option value="admin">Admin (Full Control + Flush)</option>
        </select>
      </div>
      <div class="modal-actions">
        <button class="btn btn-secondary" onclick="closeModal('modal-create-key')">Cancel</button>
        <button class="btn" onclick="submitCreateKey()">Generate Key</button>
      </div>
    </div>
  </div>

  <!-- MODAL: ONE-TIME SECRET DISPLAY -->
  <div id="modal-secret-display" class="modal">
    <div class="modal-box">
      <div class="modal-header">
        <h3 class="card-title" style="color:#10b981;">API Key Generated!</h3>
        <span style="cursor:pointer;" onclick="closeModal('modal-secret-display')">&times;</span>
      </div>
      <div style="background:rgba(239, 68, 68, 0.15); border:1px solid rgba(239, 68, 68, 0.3); border-radius:6px; padding:0.75rem; margin-bottom:1rem; font-size:0.85rem; color:#f87171;">
        <strong>Important:</strong> Copy your API key secret now. This plaintext key is NEVER stored and will NOT be shown again!
      </div>
      <div class="code-box" id="display-secret-box" style="margin-bottom:1rem; user-select:all; color:#38bdf8;"></div>
      <div class="modal-actions">
        <button class="btn" onclick="copySecret()">Copy to Clipboard</button>
        <button class="btn btn-secondary" onclick="closeModal('modal-secret-display')">Done</button>
      </div>
    </div>
  </div>

  <script>
    let activeKeySecret = '';
    let projectsCache = [];

    async function api(path, opts = {}) {
      try {
        const res = await fetch(path, opts);
        return await res.json();
      } catch (err) {
        return { success: false, error: { message: err.message } };
      }
    }

    function switchTab(tab) {
      document.querySelectorAll('.nav-tab').forEach(t => t.classList.remove('active'));
      document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
      event.target.classList.add('active');
      document.getElementById('tab-' + tab).classList.add('active');
      if (tab === 'projects') loadProjects();
      if (tab === 'keys') loadProjectsForKeys();
      if (tab === 'explorer') loadProjectsForExplorer();
      if (tab === 'metrics') loadMetrics();
      if (tab === 'audit') loadAuditLogs();
    }

    function openModal(id) { document.getElementById(id).classList.add('active'); }
    function closeModal(id) { document.getElementById(id).classList.remove('active'); }

    async function loadProjects() {
      const data = await api('/api/projects');
      const tbody = document.getElementById('projects-table-body');
      if (!data.success && data.error) {
        tbody.innerHTML = '<tr><td colspan="7" style="text-align:center; color:#f87171;">Error loading projects: ' + data.error.message + '</td></tr>';
        return;
      }
      projectsCache = data.projects || [];
      if (!projectsCache.length) {
        tbody.innerHTML = '<tr><td colspan="7" style="text-align:center;">No projects created yet. Click "+ New Project" above.</td></tr>';
        return;
      }
      tbody.innerHTML = projectsCache.map(p => `
        <tr>
          <td style="font-family:var(--mono);">${p.id}</td>
          <td><strong>${p.name}</strong></td>
          <td><span class="badge badge-info">${p.slug}</span></td>
          <td style="font-family:var(--mono);">${p.rateLimitRps} / ${p.rateLimitBurst}</td>
          <td style="font-family:var(--mono);">${(p.maxStorageBytes / (1024*1024)).toFixed(0)} MB</td>
          <td style="color:var(--text-muted);">${new Date(p.createdAt).toLocaleDateString()}</td>
          <td>
            <button class="btn btn-danger" style="padding:0.25rem 0.5rem; font-size:0.75rem;" onclick="deleteProject('${p.id}')">Delete</button>
          </td>
        </tr>
      `).join('');
    }

    async function submitCreateProject() {
      const name = document.getElementById('new-proj-name').value;
      const slug = document.getElementById('new-proj-slug').value;
      const rps = parseInt(document.getElementById('new-proj-rps').value) || 100;
      const burst = parseInt(document.getElementById('new-proj-burst').value) || 200;
      const res = await api('/api/projects', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, slug, rateLimitRps: rps, rateLimitBurst: burst })
      });
      if (!res.success) {
        alert('Failed to create project: ' + (res.error?.message || 'Unknown error'));
        return;
      }
      closeModal('modal-create-project');
      loadProjects();
    }

    async function deleteProject(id) {
      if (!confirm('Are you sure you want to delete project ' + id + '? All data will be removed.')) return;
      await api('/api/projects/' + id, { method: 'DELETE' });
      loadProjects();
    }

    async function loadProjectsForKeys() {
      await loadProjects();
      const sel = document.getElementById('keys-project-select');
      if (!projectsCache.length) {
        sel.innerHTML = '<option value="">(No projects available)</option>';
        document.getElementById('keys-table-body').innerHTML = '<tr><td colspan="7" style="text-align:center;">Create a project first before generating API keys.</td></tr>';
        return;
      }
      sel.innerHTML = projectsCache.map(p => `<option value="${p.id}">${p.name} (${p.slug})</option>`).join('');
      loadKeysForSelectedProject();
    }

    async function loadKeysForSelectedProject() {
      const sel = document.getElementById('keys-project-select');
      if (!sel || !sel.value || sel.value.trim() === '') {
        document.getElementById('keys-table-body').innerHTML = '<tr><td colspan="7" style="text-align:center;">Select or create a project to inspect API keys.</td></tr>';
        return;
      }
      const pId = sel.value.trim();
      const data = await api('/api/projects/' + encodeURIComponent(pId) + '/keys');
      const tbody = document.getElementById('keys-table-body');
      const keys = data.keys || [];
      if (!keys.length) {
        tbody.innerHTML = '<tr><td colspan="7" style="text-align:center;">No keys generated for this project yet.</td></tr>';
        return;
      }
      tbody.innerHTML = keys.map(k => `
        <tr>
          <td style="font-family:var(--mono);">${k.id}</td>
          <td><strong>${k.name}</strong></td>
          <td style="font-family:var(--mono); color:#38bdf8;">${k.keyPrefix}</td>
          <td><span class="badge badge-info">${k.scopes}</span></td>
          <td>${k.isRevoked ? '<span class="badge badge-danger">REVOKED</span>' : '<span class="badge badge-success">ACTIVE</span>'}</td>
          <td style="color:var(--text-muted); font-size:0.8rem;">${k.lastUsedAt}</td>
          <td>
            ${!k.isRevoked ? `<button class="btn btn-danger" style="padding:0.25rem 0.5rem; font-size:0.75rem;" onclick="revokeKey('${pId}', '${k.id}')">Revoke</button>` : ''}
          </td>
        </tr>
      `).join('');
    }

    async function submitCreateKey() {
      const sel = document.getElementById('keys-project-select');
      if (!sel || !sel.value) {
        alert('Please create or select a project first!');
        return;
      }
      const pId = sel.value;
      const name = document.getElementById('new-key-name').value || 'Default Key';
      const scopes = document.getElementById('new-key-scopes').value;
      const res = await api('/api/projects/' + encodeURIComponent(pId) + '/keys', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, scopes })
      });
      if (!res.success) {
        alert('Failed to generate key: ' + (res.error?.message || 'Unknown error'));
        return;
      }
      closeModal('modal-create-key');
      activeKeySecret = res.plaintextSecret;
      document.getElementById('display-secret-box').innerText = activeKeySecret;
      const explorerKeyInput = document.getElementById('explorer-api-key');
      if (explorerKeyInput) explorerKeyInput.value = activeKeySecret;
      openModal('modal-secret-display');
      loadKeysForSelectedProject();
    }

    async function revokeKey(pId, keyId) {
      if (!confirm('Revoke this API key immediately? Connected clients will lose access.')) return;
      await api('/api/projects/' + encodeURIComponent(pId) + '/keys/' + encodeURIComponent(keyId), { method: 'DELETE' });
      loadKeysForSelectedProject();
    }

    function copySecret() {
      navigator.clipboard.writeText(activeKeySecret);
      alert('Secret copied to clipboard!');
    }

    async function loadProjectsForExplorer() {
      await loadProjects();
      const sel = document.getElementById('explorer-project-select');
      if (!projectsCache.length) {
        sel.innerHTML = '<option value="">(No projects)</option>';
        return;
      }
      sel.innerHTML = projectsCache.map(p => `<option value="${p.id}">${p.name} (${p.slug})</option>`).join('');
    }

    function getAuthKey() {
      const input = document.getElementById('explorer-api-key');
      const val = input ? input.value.trim() : '';
      return val || activeKeySecret;
    }

    async function scanDataKeys() {
      const sel = document.getElementById('explorer-project-select');
      if (!sel || !sel.value) {
        alert('Select a project first!');
        return;
      }
      const apiKey = getAuthKey();
      if (!apiKey) {
        alert('Please paste or generate an API key (e.g. ld_live_...) in the API Key box to run queries.');
        return;
      }
      const pattern = document.getElementById('explorer-pattern').value || '*';
      const res = await api('/v1/scan?pattern=' + encodeURIComponent(pattern), {
        headers: { 'Authorization': 'Bearer ' + apiKey }
      });

      const listDiv = document.getElementById('explorer-key-list');
      if (res && res.keys && res.keys.length) {
        listDiv.innerHTML = res.keys.map(k => `
          <div style="cursor:pointer; padding:0.25rem 0;" onclick="selectKey('${k}')">🔑 ${k}</div>
        `).join('');
      } else if (res && !res.success) {
        listDiv.innerHTML = '<span style="color:#f87171;">Error: ' + (res.error?.message || 'Access denied') + '</span>';
      } else {
        listDiv.innerHTML = '<span style="color:var(--text-muted);">(No keys matching pattern)</span>';
      }
    }

    function selectKey(k) {
      document.getElementById('op-key').value = k;
      executeGet();
    }

    async function executeGet() {
      const k = document.getElementById('op-key').value.trim();
      const apiKey = getAuthKey();
      if (!k) return;
      if (!apiKey) {
        alert('Please enter your API key.');
        return;
      }
      const res = await api('/v1/keys/' + encodeURIComponent(k), {
        headers: { 'Authorization': 'Bearer ' + apiKey }
      });
      document.getElementById('op-result').innerText = JSON.stringify(res, null, 2);
      if (res.value !== undefined) document.getElementById('op-value').value = res.value;
    }

    async function executeSet() {
      const k = document.getElementById('op-key').value.trim();
      const v = document.getElementById('op-value').value;
      const ttl = document.getElementById('op-ttl').value;
      const apiKey = getAuthKey();
      if (!k) return;
      if (!apiKey) {
        alert('Please enter your API key.');
        return;
      }
      let url = '/v1/keys/' + encodeURIComponent(k);
      if (ttl && parseInt(ttl) > 0) url += '?ex=' + parseInt(ttl);
      const res = await api(url, {
        method: 'POST',
        headers: {
          'Authorization': 'Bearer ' + apiKey,
          'Content-Type': 'text/plain'
        },
        body: v
      });
      document.getElementById('op-result').innerText = JSON.stringify(res, null, 2);
      scanDataKeys();
    }

    async function executeDel() {
      const k = document.getElementById('op-key').value.trim();
      const apiKey = getAuthKey();
      if (!k) return;
      if (!apiKey) {
        alert('Please enter your API key.');
        return;
      }
      const res = await api('/v1/keys/' + encodeURIComponent(k), {
        method: 'DELETE',
        headers: { 'Authorization': 'Bearer ' + apiKey }
      });
      document.getElementById('op-result').innerText = JSON.stringify(res, null, 2);
      scanDataKeys();
    }

    async function loadMetrics() {
      const data = await api('/api/metrics');
      const m = data.metrics || {};
      document.getElementById('stat-reqs').innerText = m.totalRequests || 0;
      document.getElementById('stat-hits').innerText = ((m.hitRatio || 1.0) * 100).toFixed(1) + '%';
      document.getElementById('stat-latency').innerText = (m.averageLatencyMs || 0).toFixed(2) + ' ms';
      document.getElementById('stat-ratelimited').innerText = m.totalRateLimited || 0;
      document.getElementById('hdr-conns').innerText = m.activeConnections || 0;
      document.getElementById('hdr-keys').innerText = m.totalKeysInMemory || 0;
      document.getElementById('metrics-raw').innerText = JSON.stringify(m, null, 2);
    }

    async function loadAuditLogs() {
      const data = await api('/api/audit-logs');
      const tbody = document.getElementById('audit-table-body');
      const logs = data.logs || [];
      if (!logs.length) {
        tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;">No audit logs recorded yet.</td></tr>';
        return;
      }
      tbody.innerHTML = logs.map(l => `
        <tr>
          <td style="color:var(--text-muted); font-size:0.8rem;">${new Date(l.timestamp).toLocaleString()}</td>
          <td style="font-family:var(--mono);">${l.projectId || 'system'}</td>
          <td><span class="badge badge-info">${l.action}</span></td>
          <td>${l.actor}</td>
          <td>${l.details}</td>
          <td style="font-family:var(--mono); font-size:0.8rem;">${l.ipAddress}</td>
        </tr>
      `).join('');
    }

    // Initial load
    loadProjects();
    loadMetrics();
    setInterval(loadMetrics, 5000);
  </script>
</body>
</html>
""";

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        byte[] bytes = DASHBOARD_HTML.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
