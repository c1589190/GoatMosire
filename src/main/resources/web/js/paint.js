// ── Paint Tools ─────────────────────────────────────────
let lassoPts = [];

function applyTool(q, r) {
  const key = q + '_' + r;
  if (!mapData.hexes) mapData.hexes = {};

  if (tool === 'eraser') {
    delete mapData.hexes[key];
  } else if (tool === 'fill') {
    floodFill(key, activeTerrain);
  } else {
    const cell = mapData.hexes[key];
    if (!cell) {
      mapData.hexes[key] = {color: getTerrainColor(activeTerrain), terrain: activeTerrain, riverMask: 0};
    } else {
      cell.color = getTerrainColor(activeTerrain);
      cell.terrain = activeTerrain;
    }
  }
  render();
}

function floodFill(seedKey, terrain) {
  if (!mapData.hexes[seedKey]) return;
  const targetTerrain = mapData.hexes[seedKey].terrain;
  if (targetTerrain === terrain) return;
  const color = getTerrainColor(terrain);
  const visited = new Set();
  const stack = [seedKey];
  const MAX_FILL = 5000;
  while (stack.length && visited.size < MAX_FILL) {
    const key = stack.pop();
    if (visited.has(key)) continue;
    visited.add(key);
    const cell = mapData.hexes[key];
    if (!cell || cell.terrain !== targetTerrain) continue;
    cell.color = color;
    cell.terrain = terrain;
    const [q, r] = key.split('_').map(Number);
    for (const [dq, dr] of DIR_VECTORS) {
      stack.push((q+dq) + '_' + (r+dr));
    }
  }
}

// ── Terrain Lasso ──────────────────────────────────────
function applyTerrainLasso(q, r) {
  if (lassoPts.length > 2 && q === lassoPts[0].q && r === lassoPts[0].r) {
    finishTerrainLasso();
    return;
  }
  if (lassoPts.length > 0) {
    const last = lassoPts[lassoPts.length - 1];
    const line = hexLine(last.q, last.r, q, r);
    for (let i = 1; i < line.length; i++) lassoPts.push(line[i]);
  } else {
    lassoPts.push({q, r});
  }
  setStatus(`地形套索: ${lassoPts.length} 点 — 点起点闭合`);
  render();
  renderLassoPreview();
}

function finishTerrainLasso() {
  const terrain = activeTerrain;
  const rawKeys = lassoPts.map(p => `${p.q}_${p.r}`);
  lassoPts = [];
  fetch(`/api/map/${MapAPI.worldId}/blocks`, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({terrain, lassoKeys: rawKeys, seedKey: rawKeys[0]})
  }).then(r => r.json()).then(async data => {
    if (data.ok) {
      showToast(`${terrain} 区域已添加`);
      await loadMap();
      render();
    } else {
      showToast('添加失败: ' + (data.reason || 'unknown'));
    }
  }).catch(e => showToast('添加失败: '+e.message));
}

// ── RDP Boundary Simplification ────────────────────────
function simplifyBoundary(pts, epsilon) {
  if (pts.length < 4) return pts.slice();
  const result = rdp(pts, 0, pts.length - 1, epsilon);
  return result;
}

function rdp(pts, start, end, eps) {
  let maxDist = 0, maxIdx = start;
  const a = pts[start], b = pts[end];
  for (let i = start + 1; i < end; i++) {
    const d = perpDist(pts[i], a, b);
    if (d > maxDist) { maxDist = d; maxIdx = i; }
  }
  if (maxDist > eps) {
    const left = rdp(pts, start, maxIdx, eps);
    const right = rdp(pts, maxIdx, end, eps);
    left.pop();
    return left.concat(right);
  }
  return [{x:a.x, y:a.y}, {x:b.x, y:b.y}];
}

function perpDist(p, a, b) {
  const dx = b.x - a.x, dy = b.y - a.y;
  if (Math.abs(dx) < 0.001 && Math.abs(dy) < 0.001)
    return Math.hypot(p.x - a.x, p.y - a.y);
  let t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy);
  t = Math.max(0, Math.min(1, t));
  const px = a.x + t * dx, py = a.y + t * dy;
  return Math.hypot(p.x - px, p.y - py);
}

function renderLassoPreview() {
  ctx.save();
  ctx.translate(offX, offY);
  ctx.scale(zoom, zoom);
  ctx.strokeStyle = '#FFD700';
  ctx.lineWidth = 2 / zoom;
  ctx.setLineDash([4/zoom, 4/zoom]);
  ctx.beginPath();
  for (let i = 0; i < lassoPts.length; i++) {
    const {x, y} = hexToPixel(lassoPts[i].q, lassoPts[i].r);
    if (i === 0) ctx.moveTo(x, y);
    else ctx.lineTo(x, y);
  }
  if (lassoPts.length > 2) ctx.closePath();
  ctx.stroke();
  ctx.setLineDash([]);
  ctx.restore();
}
