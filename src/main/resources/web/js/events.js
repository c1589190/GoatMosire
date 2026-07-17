// ── Event Listeners ────────────────────────────────────
canvas.addEventListener('mousedown', e => {
  if (e.button === 2) {
    e.preventDefault();
    if (tool === 'province') {
      provinceLasso = [];
      const h = getHexAtEvent(e);
      if (h) addProvincePoint(h.q, h.r);
      return;
    }
    if (tool === 'river') return;
    mouseDown = true;
    clickHex = getHexAtEvent(e);
    if (clickHex) applyTool(clickHex.q, clickHex.r);
    return;
  }
  if (e.button === 0) {
    if (tool === 'province' && selectedProvince && canvas._boundaryHexes) {
      const h = getHexAtEvent(e);
      if (h) {
        for (const bp of canvas._boundaryHexes) {
          if (bp.q === h.q && bp.r === h.r) { dragPoint = bp; return; }
        }
      }
    }
    panStart = {x: e.clientX, y: e.clientY}; panOff = {x: offX, y: offY};
    mouseDown = true;
    clickHex = getHexAtEvent(e);
  }
});

canvas.addEventListener('contextmenu', e => e.preventDefault());

canvas.addEventListener('mousemove', e => {
  if (panStart) {
    offX = panOff.x + (e.clientX - panStart.x);
    offY = panOff.y + (e.clientY - panStart.y);
    if (Math.abs(e.clientX - panStart.x) > 3 || Math.abs(e.clientY - panStart.y) > 3) clickHex = null;
    render(); return;
  }

  if (tool === 'province' && provinceLasso.length > 0) {
    const h = getHexAtEvent(e);
    if (h) addProvincePoint(h.q, h.r);
    return;
  }

  if (dragPoint) {
    const h = getHexAtEvent(e);
    if (h && (h.q !== dragPoint.q || h.r !== dragPoint.r)) {
      const newKey = `${h.q}_${h.r}`;
      const oldKey = `${dragPoint.q}_${dragPoint.r}`;
      const p = mapData.provinces[selectedProvince];
      if (p?.hexes) {
        if (!p.hexes.includes(newKey) && mapData.hexes[newKey]) p.hexes.push(newKey);
        else if (p.hexes.includes(newKey) && newKey !== oldKey) p.hexes = p.hexes.filter(k => k !== oldKey);
        dragPoint = {q: h.q, r: h.r};
        canvas._boundaryCache = null;
        delete canvas['b_' + selectedProvince];
        render();
      }
    }
    return;
  }

  const h = getHexAtEvent(e);
  if (h) {
    tooltip.style.display = 'block';
    tooltip.style.left = (e.clientX + 15) + 'px';
    tooltip.style.top = (e.clientY - 20) + 'px';
    const key = `${h.q}_${h.r}`;
    const cell = (mapData?.hexes || {})[key];
    const tn = cell?.terrain || 'empty';
    const tt = terrainTypes[tn];
    tooltip.textContent = `(${h.q},${h.r}) ${tn}` + (tt ? ` \u{1F56F}${tt.food} \u{1F4B0}${tt.gold} \u{1FAA8}${tt.stone}` : '');
    if (mouseDown && (h.q !== clickHex?.q || h.r !== clickHex?.r)) { clickHex = null; }
    if (mouseDown && tool !== 'river' && tool !== 'province') applyTool(h.q, h.r);
    if (tool === 'province' && !dragPoint && provinceLasso.length === 0) {
      canvas.style.cursor = findProvinceAt(h.q, h.r) ? 'pointer' : 'crosshair';
    }
  } else { tooltip.style.display = 'none'; canvas.style.cursor = ''; }
});

canvas.addEventListener('mouseup', e => {
  if (tool === 'province' && provinceLasso.length > 2) {
    finishProvinceLasso();
    provinceLasso = [];
    return;
  }
  provinceLasso = [];
  if (dragPoint) { dragPoint = null; render(); return; }
  if (clickHex && tool === 'river') {
    addRiverWaypoint(clickHex.q, clickHex.r);
  } else if (clickHex && tool === 'province') {
    const found = findProvinceAt(clickHex.q, clickHex.r);
    if (found) { selectProvince(found); }
  } else if (clickHex && mouseDown) {
    showHexDetail(clickHex.q, clickHex.r);
    const key = `${clickHex.q}_${clickHex.r}`;
    const crMeta = mapData._compressedMeta?.get(key);
    const prev = selectedCompressedRegion;
    selectedCompressedRegion = crMeta ? crMeta.regionId : null;
    if (selectedCompressedRegion !== prev) render();
  }
  mouseDown = false; panStart = null; clickHex = null;
});

canvas.addEventListener('mouseleave', ()=>{mouseDown=false;panStart=null;tooltip.style.display='none';});
canvas.addEventListener('wheel', e => {
  e.preventDefault();
  const zf = e.deltaY < 0 ? 1.1 : 1/1.1;
  const nz = Math.max(0.1, Math.min(5, zoom * zf));
  const r = canvas.getBoundingClientRect();
  const mx = e.clientX - r.left, my = e.clientY - r.top;
  offX = mx - (mx - offX) * nz / zoom;
  offY = my - (my - offY) * nz / zoom;
  zoom = nz;
  render();
}, {passive: false});

function getHexAtEvent(e) {
  const r = canvas.getBoundingClientRect();
  return pixelToHex((e.clientX-r.left-offX)/zoom, (e.clientY-r.top-offY)/zoom);
}

// ── API & Init ─────────────────────────────────────────
async function init() {
  try { MapAPI.init(); } catch(e) { console.warn(e); }
  if (!MapAPI.worldId || MapAPI.worldId === 'default') MapAPI.worldId = 'logdemo';

  try { buildTerrainButtons(DEFAULT_TERRAINS); } catch(e) {}
  window.addEventListener('resize', resizeCanvas);
  try { resizeCanvas(); } catch(e) {}

  mapData = {hexes:{}, terrainBlocks:[], provinces:{}, tags:{}, cities:{}, terrainTypes:{...DEFAULT_TERRAINS}, gridSize:120, hexOrientation:false, rivers:[], roads:[]};
  initTags();
  setStatus('空画布就绪 — 右键拖动圈地形');

  document.getElementById('worldSelect').innerHTML = '<option value="logdemo">logdemo</option>';

  try {
    const r = await fetch('/api/map');
    if (r.ok) {
      const data = await r.json();
      const worlds = data.worlds || [];
      if (worlds.length > 0) {
        const sel = document.getElementById('worldSelect');
        sel.innerHTML = worlds.map(w => '<option value="'+w+'">'+w+'</option>').join('');
        sel.value = worlds.includes(MapAPI.worldId) ? MapAPI.worldId : worlds[0];
        MapAPI.worldId = sel.value;
      }
    }
    await loadNodes();
    await loadMap();
  } catch(e) {
    console.warn('Background load failed, using empty canvas:', e);
    try { fitView(); } catch(e2) {}
  }
}

async function onWorldChange() {
  MapAPI.worldId = document.getElementById('worldSelect').value;
  MapAPI.nodeId = null;
  await loadNodes();
  await loadMap();
  loadLatestTexts();
}

async function loadNodes() {
  try {
    const r = await fetch(`/api/map/${MapAPI.worldId}/nodes`);
    const data = await r.json();
    const sel = document.getElementById('nodeSelect');
    const savedNodeId = localStorage.getItem('goatmosire_nodeId') || '';
    sel.innerHTML = '<option value="">auto (活跃节点)</option>';
    (data.nodes||[]).forEach(n => {
      const marker = n.hasMap ? ' 🗺' : '';
      sel.innerHTML += `<option value="${n.nodeId}">${n.nodeId} (T${n.turn})${marker}</option>`;
    });
    if (savedNodeId) { sel.value = savedNodeId; MapAPI.nodeId = savedNodeId; }
  } catch(e) { console.warn('Failed to load nodes:', e); }
}

function onNodeChange() {
  MapAPI.nodeId = document.getElementById('nodeSelect').value || null;
  localStorage.setItem('goatmosire_nodeId', MapAPI.nodeId || '');
  mapData = null;
  loadMap();
  loadLatestTexts();
}

// ── Auto-refresh poller (detects MCP / external changes) ──
let lastMapVersion = 0;
async function pollMapVersion() {
  try {
    const nodeParam = MapAPI.nodeId ? `?node=${MapAPI.nodeId}` : '';
    const r = await fetch(`/api/map/${MapAPI.worldId}/version${nodeParam}`);
    if (!r.ok) return;
    const data = await r.json();
    if (lastMapVersion === 0) { lastMapVersion = data.version; return; }
    if (data.version !== lastMapVersion) {
      lastMapVersion = data.version;
      console.log('Map changed externally, reloading...');
      await loadMap();
    }
  } catch(e) { /* silent */ }
}
setInterval(pollMapVersion, 3000);

async function loadMap() {
  try {
    mapData = await MapAPI.load();
    loadTags();
    if (mapData.terrainTypes && Object.keys(mapData.terrainTypes).length > 0) {
      buildTerrainButtons(mapData.terrainTypes);
    } else {
      mapData.terrainTypes = {...DEFAULT_TERRAINS};
    }
    if (!mapData.terrainBlocks) mapData.terrainBlocks = [];
    if (!mapData.hexes) mapData.hexes = {};
    if (!mapData.tags) mapData.tags = {};

    // Build compressed region metadata for render optimization + UI
    // hexes() always contains ALL hexes (compression no longer removes them —
    // compressedRegions is a pure rendering cache, rebuilt by re-running compress)
    mapData._compressedMeta = new Map();
    mapData._compressedById = new Map();
    if (mapData.compressedRegions) {
      for (const cr of mapData.compressedRegions) {
        const meta = {regionId: cr.id, terrain: cr.terrain, color: cr.color};
        mapData._compressedById.set(cr.id, {terrain: cr.terrain, color: cr.color, hexKeys: new Set(cr.hexKeys)});
        if (cr.hexKeys) for (const key of cr.hexKeys) {
          mapData._compressedMeta.set(key, meta);
        }
      }
    }

    for (const b of mapData.terrainBlocks) {
      if (b.hexKeys && Array.isArray(b.hexKeys)) b._hexSet = new Set(b.hexKeys);
    }
    setStatus(`已加载: ${MapAPI.worldId}`);
  } catch(e) {
    mapData = {hexes:{}, terrainBlocks:[], provinces:{}, tags:{}, cities:{}, terrainTypes:{...DEFAULT_TERRAINS}, gridSize:120, hexOrientation:false, rivers:[], roads:[]};
    loadTags();
    buildTerrainButtons(DEFAULT_TERRAINS);
    setStatus('空画布 — 点 🎲 生成新大陆');
  }
  render();
}

async function saveMap() {
  if (!mapData) return;
  if (!mapData.terrainTypes || Object.keys(mapData.terrainTypes).length === 0) {
    mapData.terrainTypes = {...DEFAULT_TERRAINS};
  }
  try {
    // compressedRegions is a server-side rendering cache — no client cleanup needed
    const {tags, _compressedMeta, _compressedById, ...clean} = mapData;
    await MapAPI.save(clean);
    saveTags();
    showToast('已保存');
    setStatus('已保存');
  } catch(e) { showToast('保存失败: '+e.message); }
}
