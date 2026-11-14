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

test("quality levels: 1080p cap, 720p floor, Mali-T8xx and low RAM start at light", () => {
  assert.deepStrictEqual(Array.from(F.LEVELS, (l) => l.name), ["ultra", "high", "medium", "low", "light"]);
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
  assert.strictEqual(F.firstLevel("Mali-450 MP", false), F.LIGHT_LEVEL);
  assert.strictEqual(F.firstLevel("anything", true), F.LIGHT_LEVEL);
  assert.strictEqual(F.firstLevel("Mali-G52", false), 1);
  assert.strictEqual(F.firstLevel("Mali-T860", false), F.LIGHT_LEVEL);
  assert.strictEqual(F.firstLevel("Mali-G52", false, 2), F.LIGHT_LEVEL);
  assert.strictEqual(F.firstLevel("Adreno (TM) 630", false, 4), 1);
  assert.strictEqual(F.firstLevel("Mali-G52", false, 4, true), F.LIGHT_LEVEL);
  assert.strictEqual(F.LEVELS[F.LIGHT_LEVEL].name, "light");
  assert.strictEqual(F.isLight(4), true);
  assert.strictEqual(F.isLight(2), false);
  assert.strictEqual(F.cheapScaleFor(F.LIGHT_LEVEL, 1), 1);
  assert.strictEqual(F.BAKE_H, 160);
  assert.strictEqual(F.BAKE_ITERS, 20);
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
  assert.strictEqual(F.inGracePeriod(false, 100, 4000), true);
  assert.strictEqual(F.inGracePeriod(true, 0, 1000), true);
  assert.strictEqual(F.inGracePeriod(true, 100, F.GRACE_MS - 1), true);
  assert.strictEqual(F.inGracePeriod(true, 0, 600), true);
  assert.strictEqual(F.inGracePeriod(true, F.GRACE_FRAMES, F.GRACE_MS), false);
  assert.strictEqual(F.inGracePeriod(true, F.GRACE_FRAMES - 1, F.GRACE_MAX_MS), false);
  assert.strictEqual(F.inGracePeriod(false, 100, F.GRACE_MAX_MS), false);
  assert.strictEqual(F.inGracePeriod(true, 0, F.GRACE_MAX_MS), false);
  assert.strictEqual(F.GRACE_MAX_MS, 8000);
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

test("Sony sparse presents end grace and long stalls at light kill", () => {
  // Exact Sony KD-55XE9005 fix6 log: "grace: ignore UI stall 6499ms presented=3 elapsed=35139ms" …
  const presented = [3, 4, 5, 6, 7];
  const elapsed = [35139, 38718, 42533, 48570, 50935];
  const gap = [6499, 3533, 3805, 6016, 2349];
  for (let i = 0; i < presented.length; i++) {
    assert.strictEqual(F.inGracePeriod(true, presented[i], elapsed[i]), false);
    assert.strictEqual(F.afterGraceAction(F.LIGHT_LEVEL, gap[i], 0, 1), F.STALL_KILL);
  }
  assert.strictEqual(F.inGracePeriod(true, 3, 4000), true);
  assert.strictEqual(F.inGracePeriod(true, 0, 600), true);
  assert.strictEqual(F.afterGraceAction(F.LIGHT_LEVEL, 600, 0, 1), F.STALL_DEGRADE);
  assert.strictEqual(F.afterGraceAction(F.LIGHT_LEVEL, 600, 2, 1), F.STALL_DEGRADE);
  assert.strictEqual(F.afterGraceAction(F.LIGHT_LEVEL, 600, 3, 1), F.STALL_KILL);
  assert.strictEqual(F.afterGraceAction(F.LIGHT_LEVEL, 2000, 0, 1), F.STALL_KILL);
  assert.strictEqual(F.afterGraceAction(1, 6499, 0, 1), F.STALL_DEGRADE);
  assert.strictEqual(F.isSlowPresent(1000), true);
  assert.strictEqual(F.isSlowPresent(999), false);
  assert.strictEqual(F.isLongStall(2000), true);
  assert.strictEqual(F.isLongStall(1999), false);
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

test("live Radiomarchy keeps the 24/30 fps cap; the Home wallpaper is a still slideshow", () => {
  const src = fs.readFileSync(path.join(__dirname, "../app/fractals.js"), "utf8");
  assert.match(src, /HOME_FPS = 24/);
  assert.match(src, /SAVER_FPS = 30/);
  const app = fs.readFileSync(path.join(__dirname, "../app/app.js"), "utf8");
  assert.ok(app.indexOf("homeFxForSaver(true)") > 0);
  assert.ok(app.indexOf("!document.hidden") > 0);
  assert.ok(app.indexOf("fractal-slides") > 0);
  assert.ok(app.indexOf("ensureWallpaperMusic") > 0);
  assert.ok(app.indexOf("home: true") < 0);
});
