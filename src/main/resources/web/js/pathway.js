// ── Pathway Tool ─────────────────────────────────────────
// Replaces the legacy river.js — operates on HexCell.edgeTags
// and MapData.pathwayGroups instead of riverMask.
// State variables (activePathwayGroup, pathwayStart, pathwayHighlightMode)
// are declared in js/state.js.

// ── Data helpers ─────────────────────────────────────────

/** Ensure cell has edgeTags, migrating from legacy riverMask if needed. */
function ensureEdgeTags(cell) {
  if (!cell) return;
  if (!cell.edgeTags) {
    cell.edgeTags = {};
    const mask = cell.riverMask || 0;
    if (mask > 0) {
      for (let d = 0; d < 6; d++) {
        if (mask & (1 << d)) {
          cell.edgeTags[d] = ['river'];
        }
      }
    }
  }
}

/** Get edgeTags map from a cell (with migration). */
function getEdgeTags(cell) {
  if (!cell) return {};
  ensureEdgeTags(cell);
  return cell.edgeTags || {};
}

/** Check if a cell's edge has a specific tag. */
function hasEdgeTag(cell, edge, tag) {
  const tags = getEdgeTags(cell);
  const list = tags[edge];
  return list && Array.isArray(list) && list.includes(tag);
}

/** Add a tag to a cell's edge. */
function setEdgeTag(cell, edge, tag) {
  ensureEdgeTags(cell);
  if (!cell.edgeTags[edge]) cell.edgeTags[edge] = [];
  if (!cell.edgeTags[edge].includes(tag)) cell.edgeTags[edge].push(tag);
}

/** Remove a tag from a cell's edge. Returns true if removed. */
function removeEdgeTag(cell, edge, tag) {
  ensureEdgeTags(cell);
  if (!cell.edgeTags[edge]) return false;
  const idx = cell.edgeTags[edge].indexOf(tag);
  if (idx >= 0) {
    cell.edgeTags[edge].splice(idx, 1);
    if (cell.edgeTags[edge].length === 0) delete cell.edgeTags[edge];
    return true;
  }
  return false;
}

/** Get all pathway groups, ensuring defaults. */
function getPathwayGroups() {
  if (!mapData) return {};
  if (!mapData.pathwayGroups || Object.keys(mapData.pathwayGroups).length === 0) {
    mapData.pathwayGroups = {
      river: {id:'river', name:'河流', color:'#3295D2', description:'天然水系', visible:true},
      road: {id:'road', name:'道路', color:'#8B7355', description:'陆路通道', visible:true}
    };
  }
  return mapData.pathwayGroups;
}

// ── Pathway waypoint editing ─────────────────────────────
let _lastPathwayTarget = null; // dedup for drag mode

function addPathwayWaypoint(q, r, groupId) {
  if (!groupId) groupId = activePathwayGroup;
  if (!pathwayStart) {
    pathwayStart = {q, r};
    _lastPathwayTarget = `${q}_${r}`;
    setStatus(`通路起点: (${q},${r}) [${groupId}] — 按住右键拖拽继续，松开结束`);
    render();
    return;
  }
  // Click same hex = cancel
  if (q === pathwayStart.q && r === pathwayStart.r) {
    if (!mouseDown) {
      pathwayStart = null; _lastPathwayTarget = null;
      setStatus('通路: 已取消');
      render();
    }
    return;
  }
  // Dedup: skip if same target as last (drag over same hex)
  const targetKey = `${q}_${r}`;
  if (targetKey === _lastPathwayTarget) return;
  // Check adjacency
  const d = direction(pathwayStart.q, pathwayStart.r, q, r);
  if (d < 0) {
    // In drag mode, silently skip non-adjacent hexes; in click mode, end pathway
    if (!mouseDown) {
      pathwayStart = null; _lastPathwayTarget = null;
      setStatus('通路: 已结束（非相邻格）');
      render();
    }
    return;
  }
  // Ensure both hexes exist
  const ak = `${pathwayStart.q}_${pathwayStart.r}`, bk = `${q}_${r}`;
  if (!mapData.hexes[ak]) mapData.hexes[ak] = {color:'#6CC261',terrain:'plains',riverMask:0,edgeTags:{}};
  if (!mapData.hexes[bk]) mapData.hexes[bk] = {color:'#6CC261',terrain:'plains',riverMask:0,edgeTags:{}};
  // Set edge tags on both hexes
  setEdgeTag(mapData.hexes[ak], d, groupId);
  setEdgeTag(mapData.hexes[bk], OPPOSITE_DIR[d], groupId);
  render();
  if (!mouseDown) {
    const g = getPathwayGroups()[groupId];
    showToast(`${g ? g.name : groupId}: (${pathwayStart.q},${pathwayStart.r}) → (${q},${r})`);
  }
  pathwayStart = {q, r};
  _lastPathwayTarget = targetKey;
  setStatus(mouseDown
    ? `通路 [${groupId}]: 拖拽中… 松开右键结束`
    : `通路 [${groupId}]: 从 (${q},${r}) 继续，右键相邻格；右键非相邻格或起点结束`);
}

function removePathwaySegment(q, r, edge, groupId) {
  const key = `${q}_${r}`;
  const cell = mapData.hexes[key];
  if (!cell) return false;
  const removed = removeEdgeTag(cell, edge, groupId);
  if (removed) {
    // Also remove from adjacent hex's opposite edge
    const [dq, dr] = DIR_VECTORS[edge];
    const adjKey = `${q+dq}_${r+dr}`;
    const adjCell = mapData.hexes[adjKey];
    if (adjCell) removeEdgeTag(adjCell, OPPOSITE_DIR[edge], groupId);
    render();
    const g = getPathwayGroups()[groupId];
    showToast(`已删除 ${g ? g.name : groupId} 线段`);
    return true;
  }
  return false;
}

// ── Hit testing for segment click ────────────────────────

/** Pixel distance from point to line segment. */
function pointToSegmentDist(px, py, x1, y1, x2, y2) {
  const dx = x2 - x1, dy = y2 - y1;
  const lenSq = dx * dx + dy * dy;
  if (lenSq === 0) return Math.hypot(px - x1, py - y1);
  let t = ((px - x1) * dx + (py - y1) * dy) / lenSq;
  t = Math.max(0, Math.min(1, t));
  return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
}

/**
 * Find a pathway segment near the given canvas pixel (in screen coords).
 * Returns {q, r, edge, tag} or null.
 */
function findSegmentAtPixel(screenX, screenY, groupId) {
  if (!mapData?.hexes) return null;
  const threshold = 12; // pixels in screen space
  const r = canvas.getBoundingClientRect();
  // Convert screen coords to world coords
  const wx = (screenX - r.left - offX) / zoom;
  const wy = (screenY - r.top - offY) / zoom;

  let best = null, bestDist = threshold;

  for (const [key, cell] of Object.entries(mapData.hexes)) {
    const tags = getEdgeTags(cell);
    const [q, r] = key.split('_').map(Number);
    const {x: cx, y: cy} = hexToPixel(q, r);

    for (let d = 0; d < 6; d++) {
      const list = tags[d];
      if (!list || !Array.isArray(list)) continue;
      if (!list.includes(groupId)) continue;
      // Compute segment midpoint
      const [dq, dr] = DIR_VECTORS[d];
      const nx = cx + (hexToPixel(q+dq, r+dr).x - cx) * 0.5;
      const ny = cy + (hexToPixel(q+dq, r+dr).y - cy) * 0.5;
      const dist = pointToSegmentDist(wx, wy, cx, cy, nx, ny);
      if (dist < bestDist) {
        bestDist = dist;
        best = {q, r, edge: d, tag: groupId};
      }
    }
  }
  return best;
}

// ── Pathway Rendering ────────────────────────────────────

/** Render all pathway groups (replaces renderRivers). */
function renderPathways() {
  if (!mapData?.hexes) return;
  const groups = getPathwayGroups();
  const groupIds = Object.keys(groups);

  // In pathway editing mode, only render the active group
  const visibleIds = (tool === 'pathway')
    ? [activePathwayGroup]
    : groupIds.filter(id => groups[id].visible !== false);

  if (visibleIds.length === 0) return;

  ctx.save();
  ctx.translate(offX, offY);
  ctx.scale(zoom, zoom);
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';

  const isEditing = (tool === 'pathway');

  for (const gid of visibleIds) {
    const group = groups[gid];
    if (!group) continue;
    const color = group.color || '#808080';

    // Glow layer (white underlay)
    ctx.strokeStyle = 'rgba(255,255,255,0.35)';
    ctx.lineWidth = (isEditing ? 8 : 5) / zoom;
    ctx.beginPath();
    for (const [key, cell] of Object.entries(mapData.hexes)) {
      const tags = getEdgeTags(cell);
      const [q, r] = key.split('_').map(Number);
      const {x, y} = hexToPixel(q, r);
      for (let d = 0; d < 6; d++) {
        const list = tags[d];
        if (!list || !Array.isArray(list)) continue;
        if (!list.includes(gid)) continue;
        const [dq, dr] = DIR_VECTORS[d];
        const tx = hexToPixel(q+dq, r+dr).x, ty = hexToPixel(q+dq, r+dr).y;
        const nx = x + (tx - x) * 0.5, ny = y + (ty - y) * 0.5;
        ctx.moveTo(x, y); ctx.lineTo(nx, ny);
      }
    }
    ctx.stroke();

    // Color line
    ctx.strokeStyle = color;
    ctx.lineWidth = (isEditing ? 5 : 3) / zoom;
    ctx.beginPath();
    for (const [key, cell] of Object.entries(mapData.hexes)) {
      const tags = getEdgeTags(cell);
      const [q, r] = key.split('_').map(Number);
      const {x, y} = hexToPixel(q, r);
      for (let d = 0; d < 6; d++) {
        const list = tags[d];
        if (!list || !Array.isArray(list)) continue;
        if (!list.includes(gid)) continue;
        const [dq, dr] = DIR_VECTORS[d];
        const tx = hexToPixel(q+dq, r+dr).x, ty = hexToPixel(q+dq, r+dr).y;
        const nx = x + (tx - x) * 0.5, ny = y + (ty - y) * 0.5;
        ctx.moveTo(x, y); ctx.lineTo(nx, ny);
      }
    }
    ctx.stroke();

    // Active waypoint indicator
    if (isEditing && pathwayStart && gid === activePathwayGroup) {
      const sp = hexToPixel(pathwayStart.q, pathwayStart.r);
      ctx.fillStyle = color;
      ctx.beginPath();
      ctx.arc(sp.x, sp.y, 5 / zoom, 0, Math.PI * 2);
      ctx.fill();
      ctx.strokeStyle = '#fff';
      ctx.lineWidth = 2 / zoom;
      ctx.stroke();
    }
  }
  ctx.restore();
}

// ── Pathway Group UI ─────────────────────────────────────

/** Show pathway group list in right panel. */
function showPathwayGroupList() {
  const groups = getPathwayGroups();
  const panel = document.getElementById('rightPanel');
  const title = document.getElementById('rightTitle');
  const body = document.getElementById('rightBody');
  if (!panel || !body) return;

  title.textContent = '🛤️ 通路组别';
  panel.style.display = 'block';

  let html = '';
  for (const [id, g] of Object.entries(groups)) {
    const activeClass = (id === activePathwayGroup) ? ' style="outline:2px solid var(--accent);"' : '';
    html += `
    <div class="pathway-group-card"${activeClass} data-group-id="${id}">
      <div class="pgc-header" onclick="selectPathwayGroup('${id}')">
        <span class="pgc-swatch" style="background:${g.color||'#808080'}"></span>
        <span class="pgc-name">${g.name||id}</span>
      </div>
      <div class="pgc-controls">
        <label class="pgc-vis-toggle" title="默认显示">
          <input type="checkbox" ${g.visible !== false ? 'checked' : ''}
            onchange="togglePathwayGroupVisibility('${id}', this.checked)"> 显示
        </label>
        <button class="pgc-del-btn" onclick="event.stopPropagation();deletePathwayGroup('${id}')"
          title="删除组别">✕</button>
      </div>
    </div>`;
  }
  html += `<button class="pgc-add-btn" onclick="createPathwayGroup()">+ 新建组别</button>`;
  body.innerHTML = html;
}

/** Show pathway group detail in left panel. */
function showPathwayGroupDetail(groupId) {
  const groups = getPathwayGroups();
  const g = groups[groupId];
  if (!g) return;

  const panel = document.getElementById('leftPanel');
  const title = document.getElementById('leftTitle');
  const body = document.getElementById('leftBody');
  if (!panel || !body) return;

  title.textContent = `🛤️ ${g.name || groupId}`;
  panel.style.display = 'block';

  body.innerHTML = `
    <div class="field">
      <label>ID</label>
      <div class="val">${groupId}</div>
    </div>
    <div class="field">
      <label>名称</label>
      <input type="text" id="pgDetailName" value="${escHtml(g.name||'')}"
        onchange="updatePathwayGroupField('${groupId}','name',this.value)">
    </div>
    <div class="field">
      <label>颜色</label>
      <div style="display:flex;gap:6px;align-items:center;">
        <input type="color" id="pgDetailColor" value="${g.color||'#808080'}"
          onchange="updatePathwayGroupField('${groupId}','color',this.value)"
          style="width:40px;height:28px;padding:0;border:none;cursor:pointer;">
        <span style="font-size:11px;color:var(--dim);">${g.color||'#808080'}</span>
      </div>
    </div>
    <div class="field">
      <label>描述</label>
      <textarea id="pgDetailDesc" rows="2"
        onchange="updatePathwayGroupField('${groupId}','description',this.value)">${escHtml(g.description||'')}</textarea>
    </div>
    <div class="field">
      <label>默认显示</label>
      <label style="display:flex;align-items:center;gap:6px;cursor:pointer;">
        <input type="checkbox" id="pgDetailVisible" ${g.visible !== false ? 'checked' : ''}
          onchange="updatePathwayGroupField('${groupId}','visible',this.checked)">
        <span style="font-size:11px;">在地图上显示此组别的通路</span>
      </label>
    </div>
    <button onclick="savePathwayGroups()" style="width:100%;margin-top:8px;">💾 保存组别设置</button>
  `;
}

function selectPathwayGroup(groupId) {
  activePathwayGroup = groupId;
  pathwayStart = null; // reset waypoint
  showPathwayGroupList();
  showPathwayGroupDetail(groupId);
  render();
}

function updatePathwayGroupField(groupId, field, value) {
  const groups = getPathwayGroups();
  if (groups[groupId]) {
    groups[groupId][field] = value;
  }
}

async function savePathwayGroups() {
  try {
    const r = await fetch(MapAPI._url('/pathway-groups'), {
      method: 'PUT',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(mapData.pathwayGroups || {})
    });
    if (!r.ok) throw new Error(`Save failed: ${r.status}`);
    showToast('通路组别已保存');
  } catch(e) {
    showToast('保存组别失败: ' + e.message);
  }
}

function togglePathwayGroupVisibility(groupId, visible) {
  const groups = getPathwayGroups();
  if (groups[groupId]) {
    groups[groupId].visible = visible;
    render();
  }
}

function createPathwayGroup() {
  const id = prompt('请输入新组别 ID（英文，如 "railway"）：');
  if (!id || !id.trim()) return;
  const trimmedId = id.trim().toLowerCase().replace(/[^a-z0-9_]/g, '_');
  if (!trimmedId) return;
  const groups = getPathwayGroups();
  if (groups[trimmedId]) {
    showToast(`组别 "${trimmedId}" 已存在`);
    return;
  }
  const name = prompt('请输入组别名称（中文，如 "铁路"）：', trimmedId);
  groups[trimmedId] = {
    id: trimmedId,
    name: name || trimmedId,
    color: '#E74C3C',
    description: '',
    visible: true
  };
  showPathwayGroupList();
  selectPathwayGroup(trimmedId);
  showToast(`已创建组别: ${name || trimmedId}`);
}

function deletePathwayGroup(groupId) {
  const groups = getPathwayGroups();
  const g = groups[groupId];
  if (!g) return;
  if (!confirm(`确定删除通路组别 "${g.name || groupId}" 及其所有线段？`)) return;
  // Remove all edgeTags for this group
  if (mapData?.hexes) {
    for (const cell of Object.values(mapData.hexes)) {
      if (!cell.edgeTags) continue;
      for (let d = 0; d < 6; d++) {
        if (cell.edgeTags[d]) {
          cell.edgeTags[d] = cell.edgeTags[d].filter(t => t !== groupId);
          if (cell.edgeTags[d].length === 0) delete cell.edgeTags[d];
        }
      }
    }
  }
  delete groups[groupId];
  if (activePathwayGroup === groupId) {
    const remaining = Object.keys(groups);
    activePathwayGroup = remaining.length > 0 ? remaining[0] : 'river';
  }
  showPathwayGroupList();
  if (activePathwayGroup) showPathwayGroupDetail(activePathwayGroup);
  render();
  showToast(`已删除组别: ${g.name || groupId}`);
}

// ── Utility ──────────────────────────────────────────────

function escHtml(s) {
  if (!s) return '';
  return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}
