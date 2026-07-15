// ── Terrain Types & Color Resolver ──────────────────────

function buildTerrainButtons(types) {
  terrainTypes = types || DEFAULT_TERRAINS;
  const container = document.getElementById('terrainButtons');
  container.innerHTML = '';
  Object.entries(terrainTypes).forEach(([name, t]) => {
    const btn = document.createElement('button');
    btn.className = 'terrain-btn' + (name === activeTerrain ? ' active' : '');
    btn.title = `${name}: ${t.description||''}  \u{1F56F}${t.food} \u{1F4B0}${t.gold} \u{1FAA8}${t.stone} \u{1F463}${t.moveCost}`;
    btn.onclick = () => selectTerrain(name);
    btn.innerHTML = `<span class="swatch" style="background:${t.color}"></span><span class="label">${name}</span>`;
    container.appendChild(btn);
  });
}

function selectTerrain(name) {
  activeTerrain = name;
  document.querySelectorAll('.terrain-btn').forEach(b => b.classList.remove('active'));
  const btns = document.getElementById('terrainButtons').children;
  for (const b of btns) {
    if (b.querySelector('.label')?.textContent === name) b.classList.add('active');
  }
}

function getTerrainColor(name) {
  return terrainTypes[name]?.color || '#808080';
}

function resolveTerrainColor(q, r, cell) {
  const key = q + '_' + r;
  const blocks = (mapData.terrainBlocks || []);
  for (let i = blocks.length - 1; i >= 0; i--) {
    const b = blocks[i];
    if (b._hexSet && b._hexSet.has(key)) {
      return getTerrainColor(b.terrain);
    }
    if (b.hexKeys && Array.isArray(b.hexKeys) && b.hexKeys.includes(key)) {
      return getTerrainColor(b.terrain);
    }
    if (b.boundary && b.boundary.length >= 3) {
      const z = hexToPixel(q, r);
      if (pointInPolygon(z.x, z.y, b.boundary)) {
        return getTerrainColor(b.terrain);
      }
    }
  }
  return (cell && cell.color) || getTerrainColor(cell?.terrain) || '#3295D2';
}
