const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const blocks = require('../app/saver/block-glyphs.js');

function renderer() {
  const rectangles = [], letters = [];
  const context = { fillRect: (...args) => rectangles.push(args) };
  return {
    context, rectangles, letters, cellWidth: 18.1, cellHeight: 30,
    drawCharacter(cell) { letters.push({ cell, receiver: this }); }
  };
}
function effect(r) {
  function Base(target, options) { this.renderer = r; this.target = target; this.options = options; }
  Base.prototype.play = function () { return 'played'; };
  const Wrapped = blocks.wrap(Base);
  const instance = new Wrapped('target', { autoplay: false });
  assert.ok(instance instanceof Base);
  assert.ok(instance instanceof Wrapped);
  assert.equal(instance.play(), 'played');
  assert.deepEqual(instance.options, { autoplay: false });
  return instance;
}

test('full blocks meet across fractional cell boundaries, including animated positions', () => {
  const r = renderer(); effect(r);
  r.drawCharacter({ character: '█', column: 0.25, row: 0.5 });
  r.drawCharacter({ character: '█', column: 1.25, row: 0.5 });
  const [left, right] = r.rectangles;
  assert.ok(left[0] + left[2] >= right[0]);
  assert.equal(left[1], right[1]);
  assert.equal(r.letters.length, 0);
});

test('half, fractional and quadrant block shapes preserve terminal geometry', () => {
  assert.deepEqual(blocks.rectangles('▀'), [[0, 0, 1, 0.5]]);
  assert.deepEqual(blocks.rectangles('▄'), [[0, 0.5, 1, 0.5]]);
  assert.deepEqual(blocks.rectangles('█'), [[0, 0, 1, 1]]);
  assert.deepEqual(blocks.rectangles('▌'), [[0, 0, 0.5, 1]]);
  assert.deepEqual(blocks.rectangles('▐'), [[0.5, 0, 0.5, 1]]);
  assert.deepEqual(blocks.rectangles('▁'), [[0, 0.875, 1, 0.125]]);
  assert.deepEqual(blocks.rectangles('▚'), [[0, 0, 0.5, 0.5], [0.5, 0.5, 0.5, 0.5]]);
});

test('non-block characters and shade glyphs keep the original font renderer', () => {
  const r = renderer(); effect(r);
  for (const character of ['A', ' ', '0', '░', '▒', '▓']) {
    const cell = { character, column: 3, row: 2 };
    r.drawCharacter(cell);
    assert.equal(r.letters.at(-1).cell, cell);
    assert.equal(r.letters.at(-1).receiver, r);
  }
  assert.equal(r.rectangles.length, 0);
});

test('block rendering retains effect colour, zero alpha and glow', () => {
  const r = renderer(); effect(r);
  r.drawCharacter({ character: '█', column: 0, row: 0, color: '#123456', alpha: 0, glow: 8 });
  assert.equal(r.context.fillStyle, '#123456');
  assert.equal(r.context.shadowColor, '#123456');
  assert.equal(r.context.globalAlpha, 0);
  assert.equal(r.context.shadowBlur, 8);
});

test('helper loads as an ES5-style browser global without CommonJS', () => {
  const context = vm.createContext({});
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../app/saver/block-glyphs.js'), 'utf8'), context);
  assert.equal(vm.runInContext('typeof OmarchyBlockGlyphs.wrap', context), 'function');
});

test('shared TV saver opts in for both bundle and module paths at full cell size', () => {
  const html = fs.readFileSync(path.join(__dirname, '../app/saver/index.html'), 'utf8');
  assert.match(html, /<script src="block-glyphs.js"><\/script>/);
  assert.match(html, /pre\{[^}]*line-height:1;/);
  assert.match(html, /OmarchyBlockGlyphs\.wrap\(TTE\.TextEffect\)/);
  assert.match(html, /fontScale:1,/);
  assert.match(html, /new TextEffect\(pre,fo\)/);
});
