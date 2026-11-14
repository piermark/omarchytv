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

test("Home is a still-fractal slideshow: CSS crossfade, no canvas loop, accents follow the still", () => {
  assert.ok(app.indexOf("function fractalVisible") > 0 || app.indexOf("function fractalVisible()") > 0);
  assert.ok(app.indexOf("!document.hidden") > 0 && app.indexOf("fractalTick") > 0);
  assert.ok(app.indexOf("fractal-slides") > 0);
  assert.ok(app.indexOf("document.createElement(\"img\")") > 0 || app.indexOf("document.createElement('img')") > 0 || app.indexOf('document.createElement("img")') > 0);
  assert.ok(app.indexOf("OmarchyFractals.create") < 0 || app.indexOf("home: true") < 0, "live GL is not used for the Home wallpaper");
  assert.ok(app.indexOf("clearFractalAccent") > 0);
  assert.ok(app.indexOf("applyFractalAccentFromPal") > 0);
  assert.ok(app.indexOf("ensureWallpaperMusic") > 0);
  const html = fs.readFileSync(path.join(APP, "index.html"), "utf8");
  assert.ok(html.indexOf('src="fractal-slides.js"') > 0);
  const cat = fs.readFileSync(path.join(APP, "fractal-slides.js"), "utf8");
  const mark = "OmarchyFractalSlides =";
  const json = cat.slice(cat.indexOf(mark) + mark.length).replace(/;\s*\}\)\(this\);?\s*$/, "").trim();
  const slides = JSON.parse(json);
  assert.strictEqual(slides.intervalMs, 12000);
  assert.ok(slides.slides.length >= 8 && slides.slides.length <= 12);
  assert.strictEqual(slides.fadeMs, 1600);
  for (const s of slides.slides) {
    assert.ok(s.file.startsWith("fractals/") && s.file.endsWith(".jpg"));
    assert.ok(fs.existsSync(path.join(APP, s.file)), s.file);
    assert.ok(s.pal && s.pal.length === 4);
  }
});
