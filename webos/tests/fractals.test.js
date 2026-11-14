// node --test webos/tests/fractals.test.js — the Radiomarchy fractals (webos/app/fractals.js) match Android's.
const test = require("node:test");
const assert = require("assert");
const fs = require("fs");
const path = require("path");
const F = (() => { const ctx = {}; require("vm").runInNewContext(fs.readFileSync(path.join(__dirname, "../app/fractals.js"), "utf8"), Object.assign(ctx, { window: ctx })); return ctx.OmarchyFractals; })();

function javaFrag() {
  const src = fs.readFileSync(path.join(__dirname, "../../launcher/app/src/main/java/com/omarchytv/home/FractalView.java"), "utf8");
  let body = src.slice(src.indexOf('static final String FRAG = ""'));
  body = body.slice(0, body.indexOf(";\n"));
  return (body.match(/"((?:[^"\\]|\\.)*)"/g) || []).map((s) => JSON.parse(s)).join("");
}

test("the shader is Android's word for word", () => {
  assert.ok(F.FRAG.length > 2000);
  assert.strictEqual(F.FRAG, javaFrag());
});

test("random palettes: four colours in range, different each time", () => {
  const a = F.randomPalette(), b = F.randomPalette();
  assert.strictEqual(a.length, 12);
  for (const v of a) assert.ok(v >= 0 && v <= 1);
  assert.notDeepStrictEqual(Array.from(a), Array.from(b));
});

test("edge points escape late: detail all the way down the zoom", () => {
  for (let i = 0; i < 5; i++) {
    const c = F.edgePoint(Math.random, 400, 4000), n = F.escape(c[0], c[1], 4000);
    assert.ok(n >= 400 && n < 4000, "escaped at " + n);
  }
});

test("quality levels: start at high, 720p floor at low, old Mali starts low", () => {
  assert.deepStrictEqual(Array.from(F.LEVELS, (l) => l.name), ["ultra", "high", "medium", "low"]);
  assert.strictEqual(F.LEVELS[0].aa, 2);
  assert.ok(F.LEVELS.every((l) => l.scale === 1));
  assert.strictEqual(F.scaleFor(1, 1080), 1);
  assert.strictEqual(F.scaleFor(3, 1080), 720 / 1080);
  assert.strictEqual(F.scaleFor(3, 720), 1);
  assert.strictEqual(F.firstLevel("Mali-450 MP", false), 3);
  assert.strictEqual(F.firstLevel("anything", true), 3);
  assert.strictEqual(F.firstLevel("Mali-G52", false), 1);
  assert.strictEqual(F.firstLevel("Mali-T860", false), 1);
});

test("WCAG-ish contrast lifts a dark accent off a dark background", () => {
  const bg = "#111111", dark = "#1a1a1a";
  assert.ok(F.contrastRatio(dark, bg) < 4.5);
  assert.ok(F.contrastRatio(F.ensureContrast(dark, bg), bg) >= 4.5);
  assert.strictEqual(F.ensureContrast("#7aa2f7", bg), "#7aa2f7");
});

test("the palette uniform is a reused Float32Array, not allocated every frame", () => {
  const src = fs.readFileSync(path.join(__dirname, "../app/fractals.js"), "utf8");
  assert.ok(!/uniform3fv\(\s*U\.uPal,\s*new Float32Array/.test(src));
  assert.match(src, /uniform3fv\(\s*U\.uPal,\s*pal\)/);
});

test("Home and screensaver fractals start at a 30 fps cap and pause from the host when hidden", () => {
  const src = fs.readFileSync(path.join(__dirname, "../app/fractals.js"), "utf8");
  assert.match(src, /fpsCap = 30/);
  const app = fs.readFileSync(path.join(__dirname, "../app/app.js"), "utf8");
  assert.ok(app.indexOf("homeFxForSaver(true)") > 0);
  assert.ok(app.indexOf("!document.hidden") > 0);
});
