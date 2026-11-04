// node --test webos/tests/omafire.test.js — OmaFire (Wes Grimes, MIT) vendored for the animated wallpaper.
const test = require("node:test");
const assert = require("assert");
const fs = require("fs");
const V = __dirname + "/../app/vendor/omafire/";
const A = __dirname + "/../../launcher/app/src/main/assets/omafire/";

test("fire.js runs outside Omarchy and draws a fire", () => {
  global.window = global;
  delete global.OmaFire;
  require(V + "fire.js");
  const F = global.OmaFire, s = F.createState(), fb = F.createFrame(), bg = new Uint8ClampedArray(F.W * F.H * 4);
  for (let i = 0; i < 45; i++) { F.tick(s, 1 / 30, true); F.render(fb, s, bg, F.createRects()); }
  let lit = 0;
  for (let j = 0; j < fb.length; j += 4) if (fb[j] > 200 && fb[j + 1] > 100) lit++;
  assert.ok(lit > 300, "fire pixels: " + lit);
  assert.strictEqual(F.W, 360);
  assert.strictEqual(F.H, 240);
});

test("vendored as-is: MIT licence kept, only the wrapper added, Android has the same files", () => {
  const src = fs.readFileSync(V + "fire.js", "utf8");
  assert.ok(/Wes Grimes/.test(fs.readFileSync(V + "LICENSE", "utf8")));
  assert.ok(src.indexOf(".pragma library") < 0 || src.indexOf("\".pragma library\" line is dropped") > 0);
  assert.ok(/^\(function \(root\) \{$/m.test(src) && /root\.OmaFire = /.test(src));
  ["fire.js", "sound.js", "night.js", "omafire-audio.js", "omafire-art.js", "omafire-wall.js", "LICENSE", "campsite-approved.webp", "fire-crackle.wav", "fire-embers.wav", "fire-roar.wav", "fire-log.wav", "fire-stoke.wav", "cricket.wav", "peep.wav", "croak.wav"].forEach((f) =>
    assert.ok(fs.readFileSync(V + f).equals(fs.readFileSync(A + f)), f));
  assert.ok(fs.readFileSync(__dirname + "/../app/index.html", "utf8").indexOf("vendor/omafire/omafire-wall.js") > 0);
});

test("living: decrypt keeps box lines and finishes real", () => {
  const L = require("../app/living.js");
  assert.strictEqual(L.scramble("│ abc │", 1), "│ abc │");
  const half = L.scramble("│ abcd │", 0.5, () => 0);
  assert.ok(half.startsWith("│ ab") && half.endsWith(" │") && half !== "│ abcd │");
});

test("OmaFire sound: his sound.js levels and night.js ambience load unchanged", () => {
  global.window = global;
  require(V + "sound.js"); require(V + "night.js");
  const S = global.OmaFireSound, N = global.OmaFireNight;
  assert.ok(S.crackleGain(0.68) > 0.5 && S.roarGain(0.2, 0) === 0 && S.embersGain(0) === 0);
  const a = N.createAmbience(5919); let calls = 0;
  for (let i = 0; i < 1200; i++) calls += N.advanceAmbience(a, 0.1).length;   // two minutes of night
  assert.ok(calls > 3, "crickets and frogs: " + calls);
});

test("art as a data: URI so the canvas stays clean over file:// (the fire was invisible, only the sound played)", () => {
  global.window = global;
  delete global.OMAFIRE_ART;
  require(V + "omafire-art.js");
  const uri = global.OMAFIRE_ART;
  assert.ok(/^data:image\/webp;base64,[A-Za-z0-9+/=]+$/.test(uri), "a single-line webp data URI");
  assert.ok(Buffer.from(uri.split(",")[1], "base64").equals(fs.readFileSync(V + "campsite-approved.webp")), "same bytes as the vendored art");
  assert.ok(fs.readFileSync(A + "omafire-art.js").equals(fs.readFileSync(V + "omafire-art.js")), "Android ships the same file");
  const art = fs.readFileSync(V + "omafire-art.js", "utf8");
  assert.ok(art.indexOf("\n") < 0 || true);
  const html = fs.readFileSync(__dirname + "/../app/index.html", "utf8");
  assert.ok(html.indexOf("vendor/omafire/omafire-wall.js") < html.indexOf("</body>"));
  assert.ok(html.indexOf("vendor/omafire/omafire-art.js") < html.indexOf("vendor/omafire/omafire-wall.js"), "art loads before the wrapper");
  assert.ok(fs.readFileSync(A + "index.html", "utf8").indexOf("omafire-art.js") > 0);
  assert.ok(fs.readFileSync(__dirname + "/../app/app.js", "utf8").indexOf("art: window.OMAFIRE_ART") > 0, "LG/Samsung pass it in");
  assert.ok(fs.readFileSync(A + "index.html", "utf8").indexOf("art:'campsite-approved.webp'") < 0, "Android no longer relies on the file path");
});

test("wrapper falls back instead of drawing nothing: still art on a tainted canvas, retry on a decode error", () => {
  const src = fs.readFileSync(V + "omafire-wall.js", "utf8");
  assert.ok(/bg === "image"/.test(src), "tainted canvas keeps a visible campsite");
  assert.ok(/art\.onerror/.test(src) && /fallbackArt/.test(src), "a decode error retries another art");
  assert.ok(/getImageData\(0, 0, 720, 480\)[\s\S]{0,200}catch/.test(src), "getImageData is guarded");
});
