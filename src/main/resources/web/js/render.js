// ── Rendering ───────────────────────────────────────────
function render() {
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  ctx.save();
  ctx.translate(offX, offY);
  ctx.scale(zoom, zoom);

  const invZoom = 1 / zoom;
  const vpLeft   = -offX * invZoom - GRID;
  const vpTop    = -offY * invZoom - GRID;
  const vpRight  = (-offX + canvas.width)  * invZoom + GRID;
  const vpBottom = (-offY + canvas.height) * invZoom + GRID;

  const entries = Object.entries(mapData.hexes || {});
  const hexCount = entries.length;

  if (hexCount === 0 && (!mapData.terrainBlocks || mapData.terrainBlocks.length === 0)) {
    ctx.strokeStyle = '#ffffff22';
    ctx.lineWidth = 1 / zoom;
    ctx.beginPath(); ctx.moveTo(-200, 0); ctx.lineTo(200, 0); ctx.stroke();
    ctx.beginPath(); ctx.moveTo(0, -200); ctx.lineTo(0, 200); ctx.stroke();
    ctx.beginPath(); ctx.arc(0, 0, 50, 0, Math.PI * 2); ctx.stroke();
    ctx.restore();
    ctx.fillStyle = '#ffffff44';
    ctx.font = '16px sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText('空画布 — 右键拖动圈出地形，选地形填充', canvas.width/2, canvas.height/2 + 70);
    return;
  }
  let drawn = 0;

  const byColor = {};
  for (const [key, cell] of entries) {
    const [q, r] = key.split('_').map(Number);
    const {x, y} = hexToPixel(q, r);
    if (x < vpLeft || x > vpRight || y < vpTop || y > vpBottom) continue;
    const color = resolveTerrainColor(q, r, cell);
    if (!byColor[color]) byColor[color] = [];
    byColor[color].push({x, y});
    drawn++;
  }

  const blocks = (mapData.terrainBlocks || []).filter(b => b.boundary && b.boundary.length >= 3);
  if (blocks.length > 0) {
    const tl = pixelToHex(vpLeft, vpTop);
    const br = pixelToHex(vpRight, vpBottom);
    for (let q = tl.q - 1; q <= br.q + 1; q++) {
      for (let r = Math.min(tl.r, br.r) - 1; r <= Math.max(tl.r, br.r) + 1; r++) {
        const key = q + '_' + r;
        if (mapData.hexes && mapData.hexes[key]) continue;
        const {x: hx, y: hy} = hexToPixel(q, r);
        if (hx < vpLeft || hx > vpRight || hy < vpTop || hy > vpBottom) continue;
        for (let i = blocks.length - 1; i >= 0; i--) {
          if (pointInPolygon(hx, hy, blocks[i].boundary)) {
            const color = getTerrainColor(blocks[i].terrain);
            if (!byColor[color]) byColor[color] = [];
            byColor[color].push({x: hx, y: hy});
            drawn++;
            break;
          }
        }
      }
    }
  }

  for (const [color, hexes] of Object.entries(byColor)) {
    ctx.fillStyle = color;
    ctx.strokeStyle = '#ffffff18';
    ctx.lineWidth = 0.5 / zoom;
    for (const {x, y} of hexes) {
      ctx.beginPath();
      const corners = hexCorners(x, y, GRID - 1);
      ctx.moveTo(corners[0][0], corners[0][1]);
      for (let i = 1; i < 6; i++) ctx.lineTo(corners[i][0], corners[i][1]);
      ctx.closePath();
      ctx.fill();
      ctx.stroke();
    }
  }

  if (hexCount > 1000) {
    setStatus(`渲染: ${drawn}/${hexCount} hex (${(100*drawn/hexCount)|0}%)`);
  }

  ctx.restore();
  renderRivers();
  renderProvinceHighlight();
}

function resizeCanvas() {
  canvas.width = wrap.clientWidth;
  canvas.height = wrap.clientHeight;
  render();
}

function fitView() {
  const hexes = mapData?.hexes;
  if (!hexes || Object.keys(hexes).length === 0) {
    zoom = 1; offX = canvas.width/2; offY = canvas.height/2;
    render(); return;
  }
  let minX=Infinity,maxX=-Infinity,minY=Infinity,maxY=-Infinity;
  Object.keys(hexes).forEach(key => {
    const [q,r] = key.split('_').map(Number);
    const {x,y} = hexToPixel(q,r);
    minX=Math.min(minX,x-GRID); maxX=Math.max(maxX,x+GRID);
    minY=Math.min(minY,y-GRID); maxY=Math.max(maxY,y+GRID);
  });
  const w=maxX-minX, h=maxY-minY;
  zoom = Math.min(canvas.width*0.85/w, canvas.height*0.85/h, 3);
  offX = canvas.width/2 - (minX+maxX)/2*zoom;
  offY = canvas.height/2 - (minY+maxY)/2*zoom;
  render();
}

// ── Province Highlight ──────────────────────────────────
function renderProvinceHighlight() {
  if (tool !== 'province') { canvas._boundaryHexes = null; return; }
  if (!mapData?.provinces) return;
  const provs = mapData.provinces;

  let toRender = [];
  const highlightSet = new Set();
  if (activeTag) {
    for (const [name, p] of Object.entries(provs)) {
      if (p.tag === activeTag && p.hexes?.length) {
        toRender.push({name, ...p});
        for (const k of p.hexes) highlightSet.add(k);
      }
    }
  } else if (selectedProvince && provs[selectedProvince]?.hexes?.length) {
    toRender = [{name: selectedProvince, ...provs[selectedProvince]}];
    for (const k of provs[selectedProvince].hexes) highlightSet.add(k);
    canvas._boundaryHexes = computeBoundaryHexes(selectedProvince);
  }
  if (!toRender.length) { canvas._boundaryHexes = null; return; }

  ctx.save();
  ctx.translate(offX, offY);
  ctx.scale(zoom, zoom);
  const invZ = 1/zoom;
  const vl = -offX * invZ, vt = -offY * invZ;
  const vr = vl + canvas.width * invZ, vb = vt + canvas.height * invZ;
  ctx.fillStyle = 'rgba(0,0,0,0.55)';
  ctx.fillRect(vl, vt, vr - vl, vb - vt);

  for (const region of toRender) {
    const phexes = region.hexes || [];
    if (!phexes.length) continue;

    ctx.fillStyle = (region.color || '#FF0000') + '66';
    ctx.strokeStyle = (region.color || '#FF0000') + 'aa';
    ctx.lineWidth = 0.8 / zoom;
    ctx.beginPath();
    let drawn = 0;
    for (const key of phexes) {
      const [q, r] = key.split('_').map(Number);
      const {x, y} = hexToPixel(q, r);
      if (x < vl || x > vr || y < vt || y > vb) continue;
      const corners = hexCorners(x, y, GRID - 1);
      ctx.moveTo(corners[0][0], corners[0][1]);
      for (let i = 1; i < 6; i++) ctx.lineTo(corners[i][0], corners[i][1]);
      ctx.closePath();
      drawn++;
      if (drawn > 2000) break;
    }
    ctx.fill();
    ctx.stroke();

    const cacheName = 'b_' + region.name;
    if (!canvas[cacheName]) {
      canvas[cacheName] = computeBoundaryHexes(region.name);
    }
    if (region.name === selectedProvince) {
      canvas._boundaryHexes = canvas[cacheName];
    }
    ctx.fillStyle = region.color;
    ctx.globalAlpha = 0.8;
    for (const b of canvas[cacheName]) {
      const {x, y} = hexToPixel(b.q, b.r);
      if (x < vl || x > vr || y < vt || y > vb) continue;
      ctx.beginPath(); ctx.arc(x, y, 5/zoom, 0, Math.PI*2); ctx.fill();
    }
    ctx.globalAlpha = 1;

    // ── Region name label ──
    if (phexes.length > 0) {
      let sq = 0, sr = 0;
      for (const k of phexes) { const [q,r] = k.split('_').map(Number); sq += q; sr += r; }
      const cx = sq / phexes.length, cy = sr / phexes.length;
      const center = hexToPixel(Math.round(cx), Math.round(cy));
      const fontSize = Math.max(8, Math.min(40, Math.sqrt(phexes.length) * 1.8)) / zoom;
      ctx.font = `bold ${fontSize}px sans-serif`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      // Outline for readability
      ctx.strokeStyle = '#000000cc';
      ctx.lineWidth = 3 / zoom;
      ctx.strokeText(region.name, center.x, center.y);
      ctx.fillStyle = '#ffffff';
      ctx.fillText(region.name, center.x, center.y);
    }
  }
  ctx.restore();
}

function computeBoundaryHexes(provinceName) {
  const p = mapData?.provinces?.[provinceName];
  if (!p?.hexes) return [];
  const hs = new Set(p.hexes), bnd = [];
  for (const key of p.hexes) {
    const [q,r]=key.split('_').map(Number);
    for (const [dq,dr] of DIR_VECTORS) if(!hs.has((q+dq)+'_'+(r+dr))){bnd.push({q,r,key});break;}
  }
  return bnd;
}
