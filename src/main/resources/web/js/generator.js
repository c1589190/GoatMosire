// ── World Generator ─────────────────────────────────────
async function generateWorld() {
  const overwrite = confirm('替换当前世界「' + MapAPI.worldId + '」的地图？\n\n确定 = 替换当前世界\n取消 = 创建新世界');
  let targetWorld = MapAPI.worldId;

  if (!overwrite) {
    const newName = prompt('新世界名称:', 'continent-' + new Date().toISOString().slice(0,10));
    if (!newName) return;
    targetWorld = newName;
  }

  setStatus('🎲 生成大陆中...');
  try {
    const seedVal = document.getElementById('genSeed').value;
    const seed = seedVal ? parseInt(seedVal) : Date.now();
    const ridges = document.getElementById('genRidges').value;
    const fragments = document.getElementById('genFragments').value;
    const land = document.getElementById('genLand').value;
    const roughness = document.getElementById('genRoughness').value;
    const radius = document.getElementById('genRadius').value;
    const r = await fetch(`/api/map/${targetWorld}/generate?seed=${seed}&ridges=${ridges}&fragments=${fragments}&land=${land}&roughness=${roughness}&radius=${radius}`, {method:'POST'});
    const data = await r.json();
    showToast(`${targetWorld}: ${data.landHexes} 格陆地 / ${data.hexCount} 格总`);
    setStatus(`${targetWorld}: ${data.landHexes} 格陆地`);
    document.getElementById('genSeed').value = data.seed;

    if (targetWorld !== MapAPI.worldId) {
      MapAPI.worldId = targetWorld;
      MapAPI.nodeId = null;
      document.getElementById('worldSelect').value = targetWorld;
      try {
        const wr = await fetch('/api/map');
        if (wr.ok) {
          const wd = await wr.json();
          const sel = document.getElementById('worldSelect');
          sel.innerHTML = (wd.worlds||[]).map(w => `<option value="${w}">${w}</option>`).join('');
          sel.value = targetWorld;
        }
      } catch(e) {}
    }
    await loadNodes();
    await loadMap();
  } catch(e) {
    setStatus('生成失败: ' + e.message);
  }
}
