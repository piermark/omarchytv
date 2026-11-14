// node --test webos/tests/fractal-wallpaper.test.js — Radiomarchy fractals as the default Home wallpaper
const test = require("node:test");
const assert = require("assert");
const fs = require("fs");
const path = require("path");

const APP = path.join(__dirname, "../app");
const app = fs.readFileSync(path.join(APP, "app.js"), "utf8");

test("Radiomarchy fractals is the default Home wallpaper; OmaFire stays selectable", () => {
  assert.ok(app.indexOf('cfg.wallpaper === "radiomarchy:fractals"') > 0);
  assert.ok(app.indexOf('pick("radiomarchy:fractals")') > 0);
  assert.ok(app.indexOf('tr("wp_radiomarchy_fractals")') > 0);
  assert.match(app, /wallpaper:\s*"radiomarchy:fractals"/);
  assert.match(app, /randomWallpaper:\s*false/);
  assert.match(app, /fractalWp220/);
  const oma = app.indexOf('pick("omafire:embers")');
  const frac = app.indexOf('pick("radiomarchy:fractals")');
  assert.ok(oma > 0 && frac > oma, "listed after OmaFire");
});

test("Pulse stays the default screensaver; the wallpaper does not change that", () => {
  assert.match(app, /saverMode:\s*"pulse"/);
  assert.match(app, /saverArt:\s*"pulse"/);
});

test("pauses when Home is not visible, falls back if WebGL cannot start, accents follow the palette", () => {
  assert.ok(app.indexOf("function fractalVisible") > 0 || app.indexOf("function fractalVisible()") > 0);
  assert.ok(app.indexOf("!document.hidden") > 0 && app.indexOf("fractalTick") > 0);
  assert.ok(app.indexOf("OmarchyFractals.create") > 0);
  assert.ok(/if \(!fx\)/.test(app) && app.indexOf("fractalFails") > 0, "WebGL failure leaves the still wallpaper");
  assert.ok(app.indexOf("onPalette: applyFractalAccent") > 0);
  assert.ok(app.indexOf("clearFractalAccent") > 0);
  assert.ok(app.indexOf("home: true") > 0 && app.indexOf("fractalSessionOff") > 0);
  assert.ok(app.indexOf('src="fractals.js"') > 0 || fs.readFileSync(path.join(APP, "index.html"), "utf8").indexOf('src="fractals.js"') > 0);
});
