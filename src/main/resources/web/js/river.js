// ── River Tool ─────────────────────────────────────────
let riverStart = null;

function addRiverWaypoint(q, r) {
  if (!riverStart) {
    riverStart = {q, r};
    setStatus(`河流起点: (${q},${r}) — 请点击相邻格子，或再次点击此处取消`);
    return;
  }
  if (q === riverStart.q && r === riverStart.r) {
    riverStart = null;
    setTool('pen');
    setStatus('河流: 已取消');
    return;
  }
  const d = direction(riverStart.q, riverStart.r, q, r);
  if (d < 0) {
    riverStart = null;
    setTool('pen');
    setStatus('河流: 已结束（非相邻格）');
    return;
  }
  const ak = `${riverStart.q}_${riverStart.r}`, bk = `${q}_${r}`;
  if (!mapData.hexes[ak]) mapData.hexes[ak] = {color:'#6CC261',terrain:'plains',riverMask:0};
  if (!mapData.hexes[bk]) mapData.hexes[bk] = {color:'#6CC261',terrain:'plains',riverMask:0};
  mapData.hexes[ak].riverMask = (mapData.hexes[ak].riverMask || 0) | (1 << d);
  mapData.hexes[bk].riverMask = (mapData.hexes[bk].riverMask || 0) | (1 << OPPOSITE_DIR[d]);
  render();
  showToast(`河流: (${riverStart.q},${riverStart.r}) → (${q},${r})`);
  riverStart = {q, r};
  setStatus(`河流: 从 (${q},${r}) 继续，或点非相邻格/起点结束`);
}

// ── River Rendering ─────────────────────────────────────
function renderRivers() {
  if (!mapData?.hexes) return;
  ctx.save();
  ctx.translate(offX, offY);
  ctx.scale(zoom, zoom);
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';

  ctx.strokeStyle = 'rgba(255,255,255,0.4)';
  ctx.lineWidth = 5 / zoom;
  ctx.beginPath();
  for (const [key, cell] of Object.entries(mapData.hexes)) {
    const mask = cell.riverMask || 0;
    if (mask === 0) continue;
    const [q, r] = key.split('_').map(Number);
    const {x, y} = hexToPixel(q, r);
    for (let d = 0; d < 6; d++) {
      if (mask & (1 << d)) {
        const [dq, dr] = DIR_VECTORS[d];
        const tx = hexToPixel(q+dq, r+dr).x, ty = hexToPixel(q+dq, r+dr).y;
        const nx = x + (tx - x) * 0.5, ny = y + (ty - y) * 0.5;
        ctx.moveTo(x, y); ctx.lineTo(nx, ny);
      }
    }
  }
  ctx.stroke();

  ctx.strokeStyle = '#3295D2';
  ctx.lineWidth = 3 / zoom;
  ctx.beginPath();
  for (const [key, cell] of Object.entries(mapData.hexes)) {
    const mask = cell.riverMask || 0;
    if (mask === 0) continue;
    const [q, r] = key.split('_').map(Number);
    const {x, y} = hexToPixel(q, r);
    for (let d = 0; d < 6; d++) {
      if (mask & (1 << d)) {
        const [dq, dr] = DIR_VECTORS[d];
        const tx = hexToPixel(q+dq, r+dr).x, ty = hexToPixel(q+dq, r+dr).y;
        const nx = x + (tx - x) * 0.5, ny = y + (ty - y) * 0.5;
        ctx.moveTo(x, y); ctx.lineTo(nx, ny);
      }
    }
  }
  ctx.stroke();
  ctx.restore();
}
