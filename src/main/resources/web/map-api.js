// map-api.js — GoatMosire API bridge for the hex map editor
// Replaces Hex-Cartographer's Obsidian vault file I/O with HTTP calls.

const MapAPI = {
  /** Current world/node context, set from URL params or editor init */
  worldId: null,
  nodeId: null,

  /** Initialize from URL: ?world=kirk&node=n0002 */
  init() {
    const params = new URLSearchParams(window.location.search);
    this.worldId = params.get('world') || 'default';
    this.nodeId = params.get('node') || null; // null = active node
    return { worldId: this.worldId, nodeId: this.nodeId };
  },

  /** Build API URL */
  _url(extra) {
    let u = `/api/map/${this.worldId}`;
    if (extra) u += extra;
    if (this.nodeId) u += (u.includes('?') ? '&' : '?') + `node=${this.nodeId}`;
    return u;
  },

  /** Load the full resolved map (replaces reloadFile()) */
  async load() {
    const r = await fetch(this._url());
    if (!r.ok) throw new Error(`Load failed: ${r.status}`);
    return r.json();
  },

  /** Save a diff (replaces saveData()) */
  async save(data) {
    const r = await fetch(this._url(), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data)
    });
    if (!r.ok) throw new Error(`Save failed: ${r.status}`);
    return r.json();
  },

  /** Create a new full map */
  async create(mapData) {
    const r = await fetch(this._url(), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(mapData)
    });
    if (!r.ok) throw new Error(`Create failed: ${r.status}`);
    return r.json();
  },

  /** Load history for the current world */
  async history() {
    const r = await fetch(this._url('/history'));
    if (!r.ok) throw new Error(`History failed: ${r.status}`);
    return r.json();
  },

  /** List all worlds with maps */
  async listWorlds() {
    const r = await fetch('/api/map');
    if (!r.ok) throw new Error(`List failed: ${r.status}`);
    return r.json();
  }
};

// ── Toast notification (replaces Obsidian Notice) ──

function showToast(message, duration = 3000) {
  const toast = document.createElement('div');
  toast.className = 'goat-toast';
  toast.textContent = message;
  document.body.appendChild(toast);
  setTimeout(() => {
    toast.style.opacity = '0';
    setTimeout(() => toast.remove(), 300);
  }, duration);
}

// ── Simple modal dialog (replaces Obsidian Modal) ──

function showModal(title, content, onConfirm, onCancel) {
  const overlay = document.createElement('div');
  overlay.className = 'goat-modal-overlay';
  overlay.innerHTML = `
    <div class="goat-modal">
      <h3>${title}</h3>
      <div class="goat-modal-content">${content}</div>
      <div class="goat-modal-buttons">
        <button class="goat-btn-cancel">Cancel</button>
        <button class="goat-btn-confirm">OK</button>
      </div>
    </div>`;
  overlay.querySelector('.goat-btn-confirm').onclick = () => { overlay.remove(); onConfirm?.(); };
  overlay.querySelector('.goat-btn-cancel').onclick = () => { overlay.remove(); onCancel?.(); };
  overlay.onclick = (e) => { if (e.target === overlay) { overlay.remove(); onCancel?.(); } };
  document.body.appendChild(overlay);
}
