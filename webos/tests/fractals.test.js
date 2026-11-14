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

test("quality levels: 1080p cap, 720p floor, Mali-T8xx and low RAM start at medium", () => {
  assert.deepStrictEqual(Array.from(F.LEVELS, (l) => l.name), ["ultra", "high", "medium", "low"]);
  assert.strictEqual(F.LEVELS[0].aa, 2);
  assert.strictEqual(F.scaleFor(1, 1080), 1);
  assert.strictEqual(F.scaleFor(2, 1080), 720 / 1080);
  assert.strictEqual(F.scaleFor(3, 1080), 720 / 1080);
  assert.strictEqual(F.scaleFor(3, 720), 1);
  assert.strictEqual(F.scaleFor(1, 2160), 1080 / 2160);
  const high4k = F.bufferSize(1, 3840, 2160, 1, 1080);
  assert.strictEqual(high4k.w, 1920);
  assert.strictEqual(high4k.h, 1080);
  const medHome = F.bufferSize(2, 1920, 1080, 2, 1080);
  assert.strictEqual(medHome.w, 1280);
  assert.strictEqual(medHome.h, 720);
  assert.strictEqual(F.firstLevel("Mali-450 MP", false), 3);
  assert.strictEqual(F.firstLevel("anything", true), 3);
  assert.strictEqual(F.firstLevel("Mali-G52", false), 1);
  assert.strictEqual(F.firstLevel("Mali-T860", false), 2);
  assert.strictEqual(F.firstLevel("Mali-G52", false, 2), 2);
  assert.strictEqual(F.firstLevel("Adreno (TM) 630", false, 4), 1);
});

test("present cost treats the fps cap as on time and swap delay as backpressure", () => {
  assert.strictEqual(F.isBackpressured(1000 / 24, 24), false);
  assert.strictEqual(F.isBackpressured(1000 / 24 + 5, 24), false);
  assert.strictEqual(F.isBackpressured(1000 / 24 + 20, 24), true);
  assert.strictEqual(F.shouldDisableAtFloor(2, 5), false);
  assert.strictEqual(F.shouldDisableAtFloor(3, 1), false);
  assert.strictEqual(F.shouldDisableAtFloor(3, 2), true);
  assert.strictEqual(F.HOME_FPS, 24);
  assert.strictEqual(F.SAVER_FPS, 30);
  assert.strictEqual(F.sceneFps("Mali-T860", 0), 24);
  assert.strictEqual(F.sceneFps("Mali-G52", 2), 24);
  assert.strictEqual(F.sceneFps("Mali-G52", 4), 30);
  assert.strictEqual(F.sceneFps("Mali-G52", 0), 30);
});

test("startup stall is ignored during the grace period", () => {
  assert.strictEqual(F.inGracePeriod(false, 0, 0), true);
  assert.strictEqual(F.inGracePeriod(false, 100, 10000), true);
  assert.strictEqual(F.inGracePeriod(true, 0, 1000), true);
  assert.strictEqual(F.inGracePeriod(true, F.GRACE_FRAMES - 1, 10000), true);
  assert.strictEqual(F.inGracePeriod(true, 100, F.GRACE_MS - 1), true);
  assert.strictEqual(F.inGracePeriod(true, 0, 600), true);
  assert.strictEqual(F.inGracePeriod(true, F.GRACE_FRAMES, F.GRACE_MS), false);
  assert.strictEqual(F.isUiStall(600), true);
  assert.strictEqual(F.isUiStall(499), false);
});

test("a single UI stall degrades quality and does not disable the wallpaper", () => {
  assert.strictEqual(F.stallAction(1, false, 1), F.STALL_DEGRADE);
  assert.strictEqual(F.stallAction(2, false, 1), F.STALL_DEGRADE);
  assert.strictEqual(F.stallAction(3, false, 1), F.STALL_DEGRADE);
  assert.strictEqual(F.stallAction(3, true, 1), F.STALL_DEGRADE);
  assert.strictEqual(F.stallAction(3, true, 2), F.STALL_DEGRADE);
  assert.strictEqual(F.countStallsInWindow([1000], 5000, F.STALL_WINDOW_MS), 2);
  assert.strictEqual(F.countStallsInWindow([1000], 30000, F.STALL_WINDOW_MS), 1);
});

test("repeated UI stalls at the 720p floor disable the wallpaper even if iterations were reset", () => {
  assert.strictEqual(F.stallAction(3, true, 3), F.STALL_KILL);
  assert.strictEqual(F.stallAction(3, true, 4), F.STALL_KILL);
  assert.strictEqual(F.stallAction(2, true, 5), F.STALL_DEGRADE);
  assert.strictEqual(F.stallAction(1, true, 5), F.STALL_DEGRADE);
  assert.strictEqual(F.stallAction(3, false, 5), F.STALL_KILL);
  assert.strictEqual(F.STALLS_TO_KILL, 3);
});

test("Sony persistent floor stalls every 750 ms with reset iters/fps cap=24 kill", () => {
  let t = 0, times = [], last = F.STALL_DEGRADE;
  for (let i = 0; i < 8; i++) {
    t += 750;
    const count = F.countStallsInWindow(times, t, F.STALL_WINDOW_MS);
    times.push(t);
    last = F.stallAction(3, false, count);
    if (i < 2) assert.strictEqual(last, F.STALL_DEGRADE);
    else { assert.strictEqual(last, F.STALL_KILL); break; }
  }
  assert.strictEqual(last, F.STALL_KILL);
  assert.strictEqual(F.internalHeight(720, 2), 360);
  assert.strictEqual(F.internalHeight(720, 3), 240);
  assert.strictEqual(F.internalHeight(720, 1), 720);
  assert.strictEqual(F.cheapScaleFor(3, 1), 2);
  assert.strictEqual(F.cheapScaleFor(2, 1), 1);
  assert.strictEqual(F.CHEAP_FPS, 12);
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

test("Home fractals start at a 24 fps cap, pause when hidden, and can fall back for the session", () => {
  const src = fs.readFileSync(path.join(__dirname, "../app/fractals.js"), "utf8");
  assert.match(src, /HOME_FPS = 24/);
  assert.match(src, /SAVER_FPS = 30/);
  const app = fs.readFileSync(path.join(__dirname, "../app/app.js"), "utf8");
  assert.ok(app.indexOf("home: true") > 0);
  assert.ok(app.indexOf("homeFxForSaver(true)") > 0);
  assert.ok(app.indexOf("!document.hidden") > 0);
  assert.ok(app.indexOf("fractalSessionOff") > 0);
  assert.ok(app.indexOf("onFail:") > 0);
});
