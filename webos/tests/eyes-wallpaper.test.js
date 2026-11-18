// node --test webos/tests/eyes-wallpaper.test.js — 2.21: Last Horizon (Eyes Wide) is the default Home again
const test = require("node:test");
const assert = require("assert");
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const APP = path.join(__dirname, "../app");
const app = fs.readFileSync(path.join(APP, "app.js"), "utf8");
const W = require("../app/wploop.js");

// Runs the real migration block from app.js against a saved config.
function migrate(saved) {
  const start = app.indexOf("(function eyesWallpaperDefault()");
  const end = app.indexOf("})();", start) + 5;
  const store = { omarchytv_cfg_2110: saved == null ? null : JSON.stringify(saved) };
  const cfg = Object.assign({}, saved || {});
  const ctx = { cfg, JSON, Object, String,
    localStorage: { getItem: (k) => store[k] ?? null, setItem: (k, v) => { store[k] = v; } } };
  vm.runInNewContext(app.slice(start, end), ctx);
  return { cfg, saved: JSON.parse(store.omarchytv_cfg_2110) };
}

test("defaults: the theme's own wallpaper, Last Horizon, looping, no rotation", () => {
  assert.match(app, /theme:\s*"last-horizon"/);
  assert.match(app, /wallpaper:\s*"omarchy",/);
  assert.match(app, /randomTheme:\s*false/);
  assert.match(app, /wallpaperLoop:\s*true/);
});

test("new install and the 2.20 default fractals get the eyes; fractals picked by hand stay", () => {
  for (const saved of [null, { wallpaper: "radiomarchy:fractals", randomWallpaper: false, randomTheme: true, fractalWp220: true, wallpaperLoop: false }]) {
    const r = migrate(saved);
    assert.strictEqual(r.cfg.wallpaper, "omarchy");
    assert.strictEqual(r.cfg.theme, "last-horizon");
    assert.strictEqual(r.cfg.wallpaperLoop, true);
    assert.strictEqual(r.cfg.randomTheme, false);
    assert.strictEqual(r.saved.eyesWp221, true);
  }
  const picked = migrate({ wallpaper: "radiomarchy:fractals", wallpaperPicked: true, fractalWp220: true, wallpaperLoop: false });
  assert.strictEqual(picked.cfg.wallpaper, "radiomarchy:fractals");
  assert.strictEqual(picked.cfg.wallpaperLoop, false);
});

test("explicit choices stay: OmaFire, a still, a theme picked by hand", () => {
  assert.strictEqual(migrate({ wallpaper: "omafire:embers", fractalWp220: true }).cfg.wallpaper, "omafire:embers");
  const still = "wallpapers/tokyo-night/01-liftoff-sunset.webp";
  assert.strictEqual(migrate({ wallpaper: still, fractalWp220: true }).cfg.wallpaper, still);
  const th = migrate({ wallpaper: "radiomarchy:fractals", fractalWp220: true, theme: "gruvbox", randomTheme: false });
  assert.strictEqual(th.cfg.theme, "gruvbox");
  assert.strictEqual(th.cfg.wallpaper, "omarchy");
});

test("runs once; a pick by hand is marked", () => {
  const r = migrate(null);
  r.cfg.wallpaper = "omafire:embers";
  const again = (function () { const c = Object.assign({}, r.saved, { wallpaper: "omafire:embers" }); return migrate(c); })();
  assert.strictEqual(again.cfg.wallpaper, "omafire:embers");
  assert.ok(app.indexOf("cfg.wallpaperPicked = true") > 0);
});

test("weak TVs get the 540p / 15 fps copy, which ships next to the full one", () => {
  const data = fs.readFileSync(path.join(APP, "data.js"), "utf8");
  assert.ok(data.indexOf('"wallpaperIntrosLight"') > 0);
  assert.ok(data.indexOf("01-eyes-wide-light.mp4") > 0);
  const asset = path.join(__dirname, "../../launcher/app/src/main/assets/wallpapers/last-horizon/01-eyes-wide-light.mp4");
  assert.ok(fs.existsSync(asset) && fs.statSync(asset).size < 200 * 1024);
  assert.ok(app.indexOf("preferPlainHere(0)") > 0);
});

test("stutter guard: a smooth loop keeps going, a slow one stops once", () => {
  let g = {}, trips = 0, dec = 0;
  for (let t = 0; t <= 30000; t += 1000) { dec += 15; if (W.stutter(g, t, dec, 0, 15)) trips++; }
  assert.strictEqual(trips, 0);
  g = {}; dec = 0;
  for (let t = 0; t <= 30000; t += 1000) { dec += 4; if (W.stutter(g, t, dec, 0, 15)) trips++; }
  assert.strictEqual(trips, 1);
  g = {}; dec = 0; let drop = 0;
  for (let t = 0; t <= 30000; t += 1000) { dec += 24; drop += 16; W.stutter(g, t, dec, drop, 24); }
  assert.strictEqual(g.tripped, true);
  g = {};
  for (let t = 0; t <= 30000; t += 1000) assert.strictEqual(W.stutter(g, t, 0, 0, 15), false);   // paused: never
  assert.ok(app.indexOf("cfg.wpLoopStutter = false") > 0, "toggling the setting gives it another try");
});
