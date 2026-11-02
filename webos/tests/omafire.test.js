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
  ["fire.js", "omafire-wall.js", "LICENSE", "campsite-approved.webp"].forEach((f) =>
    assert.ok(fs.readFileSync(V + f).equals(fs.readFileSync(A + f)), f));
  assert.ok(fs.readFileSync(__dirname + "/../app/index.html", "utf8").indexOf("vendor/omafire/omafire-wall.js") > 0);
});

test("living: decrypt keeps box lines and finishes real", () => {
  const L = require("../app/living.js");
  assert.strictEqual(L.scramble("│ abc │", 1), "│ abc │");
  const half = L.scramble("│ abcd │", 0.5, () => 0);
  assert.ok(half.startsWith("│ ab") && half.endsWith(" │") && half !== "│ abcd │");
});
