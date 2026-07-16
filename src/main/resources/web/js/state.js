// ── Constants ──────────────────────────────────────────
const GRID = 30;
const DIR_VECTORS = [[1,0],[0,1],[-1,1],[-1,0],[0,-1],[1,-1]];
const OPPOSITE_DIR = [3,4,5,0,1,2];

const DEFAULT_TERRAINS = {
  water:    {name:"water",    color:"#3295D2", food:1, gold:0, stone:0, moveCost:99, description:"水域"},
  lowland:  {name:"lowland",  color:"#5B8C3E", food:3, gold:1, stone:1, moveCost:1,  description:"沿海低地"},
  hills:    {name:"hills",    color:"#A0522D", food:2, gold:1, stone:3, moveCost:2,  description:"丘陵过渡带"},
  plains:   {name:"plains",   color:"#B8A88A", food:2, gold:2, stone:1, moveCost:1,  description:"内陆山区高原"},
  mountain: {name:"mountain", color:"#6B6B6B", food:0, gold:2, stone:5, moveCost:3,  description:"高山峰簇"},
  forest:   {name:"forest",   color:"#228B22", food:2, gold:1, stone:3, moveCost:2,  description:"森林"},
  desert:   {name:"desert",   color:"#DDC88D", food:0, gold:1, stone:2, moveCost:2,  description:"沙漠"},
  swamp:    {name:"swamp",    color:"#556B2F", food:2, gold:0, stone:1, moveCost:3,  description:"海岸沼泽"},
  tundra:   {name:"tundra",   color:"#B0C4DE", food:1, gold:0, stone:1, moveCost:2,  description:"冻土"}
};

// ── Global State ───────────────────────────────────────
let mapData = null;
let terrainTypes = DEFAULT_TERRAINS;
let activeTerrain = 'plains';
let tool = 'pen';
let selectedProvince = null;
let activeTag = null;
let clickHex = null;

let provinceLasso = [];
let relassoTarget = null;
let zoom = 1;
let offX = 0, offY = 0;
let mouseDown = false;
let panStart = null, panOff = null;
let lastHex = null;

// ── DOM Refs ───────────────────────────────────────────
const canvas = document.getElementById('mapCanvas');
const ctx = canvas.getContext('2d');
const wrap = document.getElementById('canvasWrap');
const tooltip = document.getElementById('tooltip');
