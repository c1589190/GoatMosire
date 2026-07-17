// ── Tool Switcher ──────────────────────────────────────
function setTool(t) {
  tool = t;
  document.getElementById('btnPen').classList.toggle('active', t==='pen');
  document.getElementById('btnFill').classList.toggle('active', t==='fill');
  document.getElementById('btnEraser').classList.toggle('active', t==='eraser');
  document.getElementById('btnRiver').classList.toggle('active', t==='river');
  document.getElementById('btnProvince').classList.toggle('active', t==='province');
  document.getElementById('btnMapEdit').classList.toggle('active', t==='mapedit');
  riverStart = null;
  provinceLasso = [];
  lassoPts = [];
  if (t !== 'province') { relassoTarget = null; selectedProvince = null; activeTag = null; }
  if (t === 'province') {
    showTagList();
    document.getElementById('rightPanel').style.display = 'block';
    document.getElementById('mapEditPanel').style.display = 'none';
  } else if (t === 'mapedit') {
    document.getElementById('rightPanel').style.display = 'none';
  } else {
    document.getElementById('rightPanel').style.display = 'none';
    document.getElementById('mapEditPanel').style.display = 'none';
    document.getElementById('leftPanel').style.display = 'none';
    canvas._boundaryHexes = null;
    canvas.style.cursor = '';
  }
  render();
}

function setStatus(msg) { document.getElementById('statusBar').textContent = msg; }

// ── Info Panel ──────────────────────────────────────────
function showHexDetail(q, r) {
  const key = `${q}_${r}`;
  const cell = (mapData?.hexes || {})[key];

  const body = document.getElementById('leftBody');
  const title = document.getElementById('leftTitle');

  if (!cell) {
    document.getElementById('leftPanel').style.display = 'none';
    return;
  }

  // Check if this hex belongs to a compressed region
  const compMeta = mapData._compressedMeta?.get(key);
  const compLabel = compMeta
    ? ` <span style="font-size:10px;color:var(--accent);background:#333;padding:1px 6px;border-radius:3px">压缩区域 · ${compMeta.terrain} ×${compMeta.size}格</span>`
    : '';

  let provName = '';
  for (const [pname, prov] of Object.entries(mapData.provinces || {})) {
    if (prov.hexes?.includes(key)) { provName = pname; break; }
  }

  const tt = terrainTypes[cell.terrain] || {};
  title.innerHTML = `📍 (${q}, ${r})${compLabel}`;
  document.getElementById('leftPanel').style.display = 'block';

  body.innerHTML = `
    <div class="field"><label>地形</label>
      <span class="val"><span style="display:inline-block;width:12px;height:12px;border-radius:2px;background:${cell.color||getTerrainColor(cell.terrain)};margin-right:4px;vertical-align:middle"></span>${cell.terrain}</span>
    </div>
    <div class="field"><label>产出</label>
      <span class="val">\u{1F56F}${tt.food||0} \u{1F4B0}${tt.gold||0} \u{1FAA8}${tt.stone||0} \u{1F463}${tt.moveCost||0}</span>
    </div>
    ${cell.symbol ? `<div class="field"><label>符号</label><span class="val">${cell.symbol}</span></div>` : ''}
    ${cell.riverMask > 0 ? `<div class="field"><label>河流</label><span class="val">掩码: ${cell.riverMask}</span></div>` : ''}
    <div class="field"><label>描述</label>
      <textarea rows="2" onchange="updateHexDesc(${q},${r},this.value)">${cell.description||''}</textarea>
    </div>
    ${provName ? `<div class="field"><label>所属区域</label>
      <span class="val" style="cursor:pointer;color:var(--accent);text-decoration:underline" onclick="selectProvince('${provName}')">${provName}</span>
    </div>` : '<div class="field" style="color:var(--dim)">不属于任何区域</div>'}
    <div style="margin-top:12px">
      <button onclick="document.getElementById('leftPanel').style.display='none'" style="font-size:10px">关闭</button>
    </div>`;
}

function updateHexDesc(q, r, val) {
  const key = `${q}_${r}`;
  if (!mapData.hexes[key]) mapData.hexes[key] = {color:'#808080',terrain:'unknown'};
  mapData.hexes[key].description = val;
}

// ── Keyboard Shortcuts ─────────────────────────────────
document.addEventListener('keydown', e => {
  if (e.ctrlKey && e.key==='s') { e.preventDefault(); saveMap(); }
  if (e.key==='f' && !e.ctrlKey) { fitView(); }
  if (e.key==='p' && !e.ctrlKey) setTool('pen');
  if (e.key==='e' && !e.ctrlKey) setTool('eraser');
});

// ── Generator Panel Toggle ─────────────────────────────
let genCollapsed = false;
function toggleGenPanel() {
  genCollapsed = !genCollapsed;
  document.getElementById('genBody').classList.toggle('collapsed', genCollapsed);
  document.getElementById('genArrow').textContent = genCollapsed ? '▶' : '▼';
}
function syncGenRange(id) {
  const r = document.getElementById(id);
  document.getElementById(id+'Val').textContent = r.value;
}

// ── Latest Texts Panel ─────────────────────────────────
let latestCollapsed = false;
function toggleLatestPanel() {
  latestCollapsed = !latestCollapsed;
  document.getElementById('latestBody').classList.toggle('collapsed', latestCollapsed);
  document.getElementById('latestArrow').textContent = latestCollapsed ? '▶' : '▼';
}

async function loadLatestTexts() {
  const body = document.getElementById('latestBody');
  body.innerHTML = '<div style="color:var(--dim);font-size:10px;padding:4px">加载中...</div>';
  try {
    const nodeParam = MapAPI.nodeId ? `?node=${MapAPI.nodeId}` : '';
    const r = await fetch(`/api/map/${MapAPI.worldId}/latest-texts${nodeParam}`);
    if (!r.ok) throw new Error(r.status);
    const data = await r.json();
    const texts = data.texts || [];
    if (texts.length === 0) {
      body.innerHTML = '<div style="color:var(--dim);font-size:10px;padding:4px">暂无推文</div>';
      return;
    }
    body.innerHTML = texts.map(t => {
      const src = t.checkpoint === 'narrative' ? '📜 推文' : '🏛 势力';
      const key = t.key || '';
      return `<div class="item">
        <div class="src">${src} · ${key}</div>
        <div class="text" title="点击全选后复制">${escapeHtml(t.value||'')}</div>
      </div>`;
    }).join('');
  } catch(e) {
    body.innerHTML = `<div style="color:var(--dim);font-size:10px;padding:4px">加载失败: ${e.message}</div>`;
  }
}

function escapeHtml(s) {
  return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

// Auto-load on startup
setTimeout(loadLatestTexts, 2000);

// ── Map Edit Panel ─────────────────────────────────────
function toggleMapEdit() {
  const panel = document.getElementById('mapEditPanel');
  panel.style.display = panel.style.display === 'block' ? 'none' : 'block';
  setTool('mapedit');
}

function toggleMeSection(header) {
  const arrow = header.querySelector('.me-arrow');
  const body = header.nextElementSibling;
  body.classList.toggle('collapsed');
  arrow.classList.toggle('rotated');
}

async function doCompress() {
  const minSize = parseInt(document.getElementById('compressMinSize').value) || 100;
  const info = document.getElementById('compressInfo');
  info.textContent = '压缩中...';
  try {
    const r = await fetch(`/api/map/${MapAPI.worldId}/compress?minSize=${minSize}`, {method:'POST'});
    const data = await r.json();
    if (data.ok) {
      info.textContent = `✅ ${data.compressedCount} 格 → ${data.regions} 个区域 (${data.compressionRatio})`;
      showToast(`压缩完成: ${data.compressedCount} 格 → ${data.regions} 个区域`);
      await loadMap();
    } else {
      info.textContent = `❌ ${data.error || '失败'}`;
      showToast('压缩失败: ' + (data.error || 'unknown'));
    }
  } catch(e) {
    info.textContent = `❌ ${e.message}`;
    showToast('压缩失败: ' + e.message);
  }
}
