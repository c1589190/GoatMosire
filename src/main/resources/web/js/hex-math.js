// ── Hex Math ────────────────────────────────────────────

function hexToPixel(q, r) {
  return {
    x: GRID * (Math.sqrt(3) * q + Math.sqrt(3)/2 * r),
    y: GRID * (3/2 * r)
  };
}
function pixelToHex(x, y) {
  const s = GRID;
  return hexRound((Math.sqrt(3)/3*x - 1/3*y)/s, (2/3*y)/s);
}
function hexRound(fq, fr) {
  const fs = -fq - fr;
  let q = Math.round(fq), r = Math.round(fr), s = Math.round(fs);
  const dq = Math.abs(q-fq), dr = Math.abs(r-fr), ds = Math.abs(s-fs);
  if (dq > dr && dq > ds) q = -r - s;
  else if (dr > ds) r = -q - s;
  return {q, r};
}
function hexCorners(cx, cy, size) {
  const pts = [];
  for (let i=0; i<6; i++) {
    const a = Math.PI/180*(60*i-30);
    pts.push([cx+size*Math.cos(a), cy+size*Math.sin(a)]);
  }
  return pts;
}

// ── Point-in-polygon (ray-casting) ───────────────────────
function pointInPolygon(px, py, polygon) {
  let inside = false;
  const n = polygon.length;
  for (let i = 0, j = n - 1; i < n; j = i++) {
    const xi = polygon[i].x, yi = polygon[i].y;
    const xj = polygon[j].x, yj = polygon[j].y;
    if ((yi > py) !== (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) {
      inside = !inside;
    }
  }
  return inside;
}

// ── Hex line (Bresenham-style) ──────────────────────────
function hexLine(aq, ar, bq, br) {
  const dist = (Math.abs(aq - bq) + Math.abs(ar - br) + Math.abs(-aq-ar + bq+br)) / 2;
  if (dist === 0) return [{q: aq, r: ar}];
  const pts = [];
  for (let i = 0; i <= dist; i++) {
    const t = i / dist;
    const fq = aq + (bq - aq) * t, fr = ar + (br - ar) * t;
    let q = Math.round(fq), r = Math.round(fr);
    const dq = Math.abs(fq - q), dr = Math.abs(fr - r), ds = Math.abs(-fq-fr - (-q-r));
    if (dq > dr && dq > ds) q = -r - (-q-r);
    else if (dr > ds) r = -q - (-q-r);
    pts.push({q, r});
  }
  return pts;
}

// ── Hex direction ───────────────────────────────────────
function direction(fromQ, fromR, toQ, toR) {
  const dq = toQ - fromQ, dr = toR - fromR;
  for (let d = 0; d < 6; d++) {
    if (DIR_VECTORS[d][0] === dq && DIR_VECTORS[d][1] === dr) return d;
  }
  return -1;
}
