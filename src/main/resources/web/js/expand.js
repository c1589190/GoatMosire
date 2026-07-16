// ── Map Expansion Panel ──────────────────────────────────
let expandCollapsed = true;
let expandDirection = null;
const EXPAND_DIRS = [
  {key:'NW', label:'↖ 西北', q:-1, r:0},
  {key:'NE', label:'↗ 东北', q:0, r:-1},
  {key:'W',  label:'← 西',  q:-1, r:1},
  {key:'E',  label:'→ 东',  q:1, r:-1},
  {key:'SW', label:'↙ 西南', q:0, r:1},
  {key:'SE', label:'↘ 东南', q:1, r:0},
];

function toggleExpandPanel() {
  expandCollapsed = !expandCollapsed;
  document.getElementById('expandBody').classList.toggle('collapsed', expandCollapsed);
  document.getElementById('expandArrow').textContent = expandCollapsed ? '▶' : '▼';
}

function selectExpandDir(key) {
  expandDirection = key;
  document.querySelectorAll('.expand-dir-btn').forEach(b => b.classList.remove('active'));
  const btn = document.querySelector(`.expand-dir-btn[data-dir="${key}"]`);
  if (btn) btn.classList.add('active');
  document.getElementById('expandInfo').textContent = `已选: ${key} — 点击「执行扩充」`;
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
    btn.disabled = false;
  }
}

// Build direction buttons on init
(function initExpandPanel() {
  const container = document.getElementById('expandDirs');
  let html = '';
  // Layout: NW(0,0)  NE(0,1)  (empty=0,2)
  //         W(1,0)    (1,1)   E(1,2)
  //         SW(2,0)   (2,1)  SE(2,2)
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

  // Add the Go button
  const body = document.getElementById('expandBody');
  const btn = document.createElement('button');
  btn.id = 'expandGoBtn';
  btn.className = 'expand-go-btn';
  btn.textContent = '⚡ 执行扩充';
  btn.onclick = doExpand;
  body.appendChild(btn);

  // Add compress button
  const compBtn = document.createElement('button');
  compBtn.id = 'compressBtn';
  compBtn.className = 'expand-go-btn';
  compBtn.style.cssText = 'margin-top:4px;background:#555';
  compBtn.textContent = '🗜 压缩地图';
  compBtn.title = '检测大连通同地形区域，压缩为大色块存储';
  compBtn.onclick = async () => {
    const size = parseInt(prompt('最小区域大小 (>=此大小的连通区域将被压缩):', '50')) || 50;
    compBtn.disabled = true;
    compBtn.textContent = '压缩中...';
    try {
      const r = await fetch(`/api/map/${MapAPI.worldId}/compress?minSize=${size}`, {method:'POST'});
      const data = await r.json();
      if (data.ok) {
        showToast(`压缩完成: ${data.compressedCount} 格 → ${data.regions} 个区域 (留存 ${data.compressionRatio})`);
        await loadMap();
      } else {
        showToast('压缩失败: ' + (data.error || 'unknown'));
      }
    } catch(e) { showToast('压缩失败: ' + e.message); }
    compBtn.disabled = false;
    compBtn.textContent = '🗜 压缩地图';
  };
  body.appendChild(compBtn);
})();
