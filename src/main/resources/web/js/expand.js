// ── Map Expansion Panel ──────────────────────────────────
let expandDirection = null;
const EXPAND_DIRS = [
  {key:'NW', label:'↖ 西北', q:-1, r:0},
  {key:'NE', label:'↗ 东北', q:0, r:-1},
  {key:'W',  label:'← 西',  q:-1, r:1},
  {key:'E',  label:'→ 东',  q:1, r:-1},
  {key:'SW', label:'↙ 西南', q:0, r:1},
  {key:'SE', label:'↘ 东南', q:1, r:0},
];

function selectExpandDir(key) {
  expandDirection = key;
  document.querySelectorAll('.expand-dir-btn').forEach(b => b.classList.remove('active'));
  const btn = document.querySelector(`.expand-dir-btn[data-dir="${key}"]`);
  if (btn) btn.classList.add('active');
  document.getElementById('expandGoBtn').disabled = false;
  document.getElementById('expandInfo').textContent = `已选: ${key} — 点击「扩充」`;
}

async function doExpand() {
  if (!expandDirection) {
    showToast('请先选择一个扩充方向');
    return;
  }
  const radius = parseInt(document.getElementById('expandRadius').value) || 0;
  const info = document.getElementById('expandInfo');
  const btn = document.getElementById('expandGoBtn');
  info.textContent = '扩充中...';
  btn.disabled = true;

  try {
    const url = `/api/map/${MapAPI.worldId}/expand?direction=${expandDirection}&radius=${radius}`;
    const r = await fetch(url, {method:'POST'});
    const data = await r.json();
    if (data.ok) {
      info.textContent = `✅ +${data.added} hex (陆${data.landAdded} 水${data.waterAdded})`;
      showToast(`扩充完成: +${data.added} hex, 新半径=${data.newRadius}`);
      await loadMap();
    } else {
      info.textContent = `❌ ${data.error || '失败'}`;
      showToast('扩充失败: ' + (data.error || 'unknown'));
    }
  } catch(e) {
    info.textContent = `❌ ${e.message}`;
    showToast('扩充失败: ' + e.message);
  } finally {
    btn.disabled = true;
  }
}

// Build direction buttons on init
(function initExpandPanel() {
  const container = document.getElementById('expandDirs');
  if (!container) return;
  let html = '';
  const layout = [
    {key:'NW', row:0, col:0}, {key:'NE', row:0, col:1}, {key:null, row:0, col:2},
    {key:'W',  row:1, col:0}, {key:null, row:1, col:1}, {key:'E',  row:1, col:2},
    {key:'SW', row:2, col:0}, {key:null, row:2, col:1}, {key:'SE', row:2, col:2},
  ];
  for (const cell of layout) {
    if (cell.key) {
      const d = EXPAND_DIRS.find(d => d.key === cell.key);
      html += `<button class="expand-dir-btn" data-dir="${d.key}" onclick="selectExpandDir('${d.key}')"
        style="grid-row:${cell.row+1};grid-column:${cell.col+1}" title="向${d.label}扩充">${d.label}</button>`;
    } else {
      html += `<span style="grid-row:${cell.row+1};grid-column:${cell.col+1}"></span>`;
    }
  }
  container.innerHTML = html;
})();
