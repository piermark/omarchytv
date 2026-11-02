const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const viewport = require('../app/saver/viewport.js');

function fixture(ratio = 1) {
  const box = { left: 345, top: 405, width: 1230, height: 270 };
  const calls = [], listeners = new Set();
  const view = {
    innerWidth: 1920, innerHeight: 1080, devicePixelRatio: ratio,
    addEventListener(name, fn) { assert.equal(name, 'resize'); listeners.add(fn); },
    removeEventListener(name, fn) { listeners.delete(fn); }
  };
  const context = {
    save() { calls.push(['save']); }, restore() { calls.push(['restore']); },
    setTransform(...args) { calls.push(['transform', ...args]); },
    clearRect(...args) { calls.push(['clear', ...args]); },
    fillRect(...args) { calls.push(['fill', ...args]); }
  };
  const canvas = { style: {} };
  const renderer = {
    source: { getBoundingClientRect: () => ({ ...box }) }, canvas, context,
    options: { background: '#000' }, width: 0, height: 0,
    resize() { this.width = box.width; this.height = box.height;
      this.cellWidth = box.width / 70; this.cellHeight = box.height / 9; },
    draw(state) { calls.push(['draw', state, this.width, this.height, this.cellWidth, this.cellHeight]); }
  };
  const effect = {
    renderer, destroyed: false,
    resize() { renderer.resize(); renderer.draw({ resized: true }); },
    destroy() { this.destroyed = true; calls.push(['destroy']); }
  };
  return { box, view, canvas, renderer, effect, calls, listeners };
}

test('fullscreen bitmap keeps the text grid and source position unchanged', () => {
  const f = fixture(); viewport.attach(f.effect, f.view);
  assert.equal(f.canvas.width, 1920); assert.equal(f.canvas.height, 1080);
  assert.equal(f.canvas.style.position, 'fixed');
  assert.equal(f.canvas.style.top, '0'); assert.equal(f.canvas.style.left, '0');
  assert.equal(f.renderer.width, 1230); assert.equal(f.renderer.height, 270);
  assert.equal(f.renderer.cellWidth, 1230 / 70); assert.equal(f.renderer.cellHeight, 30);
  const state = { cells: [{ column: -5, row: -4 }], particles: [{ column: 90, row: 15 }], beam: { column: -2 } };
  f.renderer.draw(state);
  assert.ok(f.calls.some(c => c[0] === 'clear' && c[3] === 1920 && c[4] === 1080));
  assert.ok(f.calls.some(c => c[0] === 'fill' && c[3] === 1920 && c[4] === 1080));
  assert.deepEqual(f.calls.filter(c => c[0] === 'transform').at(-1), ['transform', 1, 0, 0, 1, 345, 405]);
  assert.equal(f.calls.find(c => c[0] === 'draw')[1], state, 'no coordinate filtering or text-bound clipping');
  assert.deepEqual(f.calls.at(-1), ['restore']);
});

test('high-DPI backing resolution does not enlarge the logical logo or cell size', () => {
  const f = fixture(2); viewport.attach(f.effect, f.view); f.renderer.draw({});
  assert.equal(f.canvas.width, 3840); assert.equal(f.canvas.height, 2160);
  assert.equal(f.canvas.style.width, '1920px'); assert.equal(f.canvas.style.height, '1080px');
  assert.equal(f.renderer.cellHeight, 30);
  assert.deepEqual(f.calls.filter(c => c[0] === 'transform').at(-1), ['transform', 2, 0, 0, 2, 690, 810]);
});

test('font and layout changes refresh dimensions before drawing without ResizeObserver', () => {
  const f = fixture(); viewport.attach(f.effect, f.view);
  Object.assign(f.box, { left: 410, top: 420, width: 1100, height: 240 });
  f.renderer.draw({});
  assert.equal(f.renderer.width, 1100); assert.equal(f.renderer.height, 240);
  assert.equal(f.canvas.width, 1920);
  assert.deepEqual(f.calls.filter(c => c[0] === 'transform').at(-1), ['transform', 1, 0, 0, 1, 410, 420]);
});

test('viewport resize updates the backing surface and destroy removes its one listener', () => {
  const f = fixture(); viewport.attach(f.effect, f.view); viewport.attach(f.effect, f.view);
  assert.equal(f.listeners.size, 1);
  f.view.innerWidth = 1280; f.view.innerHeight = 720; f.box.left = 25; f.box.top = 225;
  [...f.listeners][0]();
  assert.equal(f.canvas.width, 1280); assert.equal(f.canvas.height, 720);
  assert.equal(f.renderer.width, 1230);
  f.effect.destroy(); assert.equal(f.effect.destroyed, true); assert.equal(f.listeners.size, 0);
});

test('context state is restored if the original renderer throws', () => {
  const f = fixture(); f.renderer.draw = () => { throw Error('render failed'); };
  viewport.attach(f.effect, f.view);
  assert.throws(() => f.renderer.draw({}), /render failed/);
  assert.deepEqual(f.calls.at(-1), ['restore']);
});

test('browser helper loads without CommonJS; shared logo path attaches before playback', () => {
  const context = vm.createContext({});
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../app/saver/viewport.js'), 'utf8'), context);
  assert.equal(vm.runInContext('typeof OmarchySaverViewport.attach', context), 'function');
  const html = fs.readFileSync(path.join(__dirname, '../app/saver/index.html'), 'utf8');
  assert.match(html, /<script src="viewport.js"><\/script>/);
  assert.ok(html.indexOf('OmarchySaverViewport.attach(fx)') < html.indexOf('fx.play().then'));
  assert.ok(html.indexOf("if(mode==='aquarium')") < html.indexOf('OmarchySaverViewport.attach(fx)'));
});
