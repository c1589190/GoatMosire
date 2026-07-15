// ── Tag Manager ────────────────────────────────────────

function initTags() {
  if (!mapData.tags) mapData.tags = {};
  for (const [name, r] of Object.entries(mapData.provinces || {})) {
    if (r.tag && !mapData.tags[r.tag]) {
      mapData.tags[r.tag] = {color: r.color || '#888888', description: ''};
    }
  }
}

function saveTags() {
  try {
    localStorage.setItem('goatmosire_tags_' + MapAPI.worldId, JSON.stringify(mapData.tags||{}));
  } catch(e) {}
}

function loadTags() {
  try {
    const saved = localStorage.getItem('goatmosire_tags_' + MapAPI.worldId);
    if (saved) { mapData.tags = JSON.parse(saved); }
    else { mapData.tags = {}; }
  } catch(e) { mapData.tags = {}; }
}

function createTag() {
  const name = prompt('标签名称 (如: 王国A, 气候带):');
  if (!name) return;
  if (mapData.tags[name]) { showToast('标签已存在'); return; }
  const color = prompt('标签颜色:', '#'+Math.floor(Math.random()*16777215).toString(16).padStart(6,'0'));
  mapData.tags[name] = {color: color||'#ff0000', description: ''};
  activeTag = name;
  selectedProvince = null;
  showTagList();
  render();
  saveTags();
  showToast(`标签「${name}」已创建`);
}

function deleteTag(name) {
  if (!confirm(`删除标签「${name}」？其下区域不会被删除。`)) return;
  delete mapData.tags[name];
  for (const [rn, r] of Object.entries(mapData.provinces||{})) {
    if (r.tag === name) r.tag = '';
  }
  if (activeTag === name) activeTag = null;
  showTagList();
  render();
  saveTags();
}

function selectTag(name) {
  activeTag = name;
  selectedProvince = null;
  showTagList();
  render();
}

function showTagList() {
  initTags();
  const body = document.getElementById('rightBody');
  const title = document.getElementById('rightTitle');
  const tags = Object.entries(mapData.tags);
  const allProvs = Object.entries(mapData.provinces||{});

  let html = '<div style="margin-bottom:6px"><button onclick="createTag()" style="font-size:11px;background:var(--accent);border:none;color:#fff;border-radius:4px;padding:2px 8px;cursor:pointer">+ 新建标签</button></div>';

  if (tags.length === 0) {
    html += '<div style="color:var(--dim);font-size:11px;padding:4px">暂无标签，点击上方新建</div>';
  } else {
    const order = getTagOrder();
    tags.sort((a, b) => {
      const ai = order.indexOf(a[0]), bi = order.indexOf(b[0]);
      if (ai >= 0 && bi >= 0) return ai - bi;
      if (ai >= 0) return -1;
      if (bi >= 0) return 1;
      return 0;
    });

    html += tags.map(([name, t], idx) => {
      const regions = allProvs.filter(([rn,r]) => r.tag === name);
      const active = activeTag === name ? 'outline: 2px solid var(--accent);' : '';
      const collapsed = isTagCollapsed(name);
      return `<div class="tag-card" style="${active}" draggable="true"
          ondragstart="onTagDragStart(event,'${name}')"
          ondragover="onTagDragOver(event)"
          ondragleave="onTagDragLeave(event)"
          ondrop="onTagDrop(event,'${name}')"
          ondragend="onTagDragEnd(event)">
        <div class="tag-header">
          <span class="tag-drag-handle" title="拖动排序">⠿</span>
          <span class="tag-fold${collapsed?' folded':''}" onclick="event.stopPropagation();toggleTag('${name}')">▼</span>
          <span style="display:inline-block;width:8px;height:8px;border-radius:2px;background:${t.color};margin-right:4px;flex-shrink:0"></span>
          <b style="flex:1;font-size:12px;cursor:pointer" onclick="event.stopPropagation();selectTag('${name}')">${name}</b>
          <span style="font-size:10px;color:var(--dim);margin-right:4px">${regions.length}</span>
          <button onclick="event.stopPropagation();deleteTag('${name}')" style="font-size:12px;background:none;border:none;color:var(--dim);cursor:pointer;padding:0 2px">✕</button>
        </div>
        <div class="tag-body${collapsed?' collapsed':''}">
          ${regions.length > 0 ? regions.map(([rn,r]) =>
            `<div style="margin-top:2px;font-size:10px;cursor:pointer;padding:2px 4px;${selectedProvince===rn?'background:#333;border-radius:2px;':''}" onclick="event.stopPropagation();selectProvince('${rn}')">
              <span style="display:inline-block;width:6px;height:6px;border-radius:1px;background:${r.color||t.color};margin-right:4px"></span>${rn} (${r.hexes.length}格)
            </div>`
          ).join('') : '<div style="font-size:9px;color:var(--dim);padding:2px 4px">右键在地图上圈地创建区域</div>'}
        </div>
      </div>`;
    }).join('');
  }

  const untagged = allProvs.filter(([rn,r]) => !r.tag);
  if (untagged.length > 0) {
    html += '<div style="margin-top:8px;padding-top:4px;border-top:1px solid var(--border)">';
    html += '<div style="font-size:10px;color:var(--dim);margin-bottom:4px">未分类区域</div>';
    html += untagged.map(([rn,r]) =>
      `<div style="margin-bottom:3px;padding:4px 6px;background:var(--bg);border-radius:4px;cursor:pointer;${selectedProvince===rn?'border:1px solid var(--accent);':''}" onclick="selectProvince('${rn}')">
        <span style="display:inline-block;width:8px;height:8px;border-radius:2px;background:${r.color||'#888'};margin-right:6px"></span>
        <b style="font-size:11px">${rn}</b> <span style="color:var(--dim);font-size:10px">${r.hexes.length}格</span>
      </div>`
    ).join('');
    html += '</div>';
  }

  title.textContent = `📐 标签管理`;
  body.innerHTML = html;
  document.getElementById('rightPanel').style.display = 'block';
}

// ── Tag fold/collapse ──────────────────────────────────
function isTagCollapsed(name) {
  try {
    const s = localStorage.getItem('goatmosire_collapsed_' + MapAPI.worldId);
    return s ? JSON.parse(s).includes(name) : false;
  } catch(e) { return false; }
}
function toggleTag(name) {
  try {
    const key = 'goatmosire_collapsed_' + MapAPI.worldId;
    let arr = JSON.parse(localStorage.getItem(key) || '[]');
    const idx = arr.indexOf(name);
    if (idx >= 0) arr.splice(idx, 1); else arr.push(name);
    localStorage.setItem(key, JSON.stringify(arr));
  } catch(e) {}
  showTagList();
}

// ── Tag drag reorder ──────────────────────────────────
let tagDragSource = null;
function getTagOrder() {
  try { return JSON.parse(localStorage.getItem('goatmosire_tagOrder_' + MapAPI.worldId) || '[]'); }
  catch(e) { return []; }
}
function saveTagOrder(order) {
  localStorage.setItem('goatmosire_tagOrder_' + MapAPI.worldId, JSON.stringify(order));
}
function onTagDragStart(e, name) {
  tagDragSource = name;
  e.target.classList.add('dragging');
  e.dataTransfer.effectAllowed = 'move';
  e.dataTransfer.setData('text/plain', name);
}
function onTagDragOver(e) {
  e.preventDefault();
  e.dataTransfer.dropEffect = 'move';
  e.currentTarget.classList.add('drag-over');
}
function onTagDragLeave(e) {
  e.currentTarget.classList.remove('drag-over');
}
function onTagDrop(e, targetName) {
  e.preventDefault();
  e.currentTarget.classList.remove('drag-over');
  if (!tagDragSource || tagDragSource === targetName) return;
  const order = getTagOrder();
  const tags = Object.keys(mapData.tags);
  for (const t of tags) { if (!order.includes(t)) order.push(t); }
  const si = order.indexOf(tagDragSource);
  const ti = order.indexOf(targetName);
  if (si >= 0) order.splice(si, 1);
  const newTi = order.indexOf(targetName);
  order.splice(newTi, 0, tagDragSource);
  saveTagOrder(order);
  tagDragSource = null;
  showTagList();
}
function onTagDragEnd(e) {
  e.target.classList.remove('dragging');
  document.querySelectorAll('.tag-card.drag-over').forEach(el => el.classList.remove('drag-over'));
  tagDragSource = null;
}
