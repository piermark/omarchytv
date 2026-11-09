// 2.15 (#131): the Developer Mode countdown on unrooted LG TVs.
const test = require("node:test");
const assert = require("node:assert");
const fs = require("fs");
const path = require("path");
const D = require("../app/devmode.js");

test("dev mode: days, hours under 48 h, yellow under 14 days, red under 7, expired", () => {
  const now = Date.UTC(2026, 10, 9, 12);
  assert.strictEqual(D.left(0, now).level, "unset");
  const full = D.left(D.extended(now), now);
  assert.ok(/^41 days left · until \d\d\/\d\d$/.test(full.text), full.text);
  assert.strictEqual(full.level, "ok");
  assert.strictEqual(D.left(now + 10 * 86400000, now).level, "warn");
  assert.strictEqual(D.left(now + 3 * 86400000, now).level, "crit");
  assert.ok(/^30 h left/.test(D.left(now + 30 * 3600000, now).text));
  assert.strictEqual(D.left(now - 1, now).level, "over");
  assert.strictEqual(D.SESSION_MS, 1000 * 3600 * 1000);
});

test("dev mode: off by default, never on Samsung, loaded by the page, documented", () => {
  const app = fs.readFileSync(path.join(__dirname, "../app/app.js"), "utf8");
  assert.ok(/devmode: false/.test(app));
  assert.ok(/OMARCHY_PLATFORM !== "tizen" && window\.OmarchyDevMode/.test(app));
  assert.ok(fs.readFileSync(path.join(__dirname, "../app/index.html"), "utf8").indexOf('src="devmode.js"') > 0);
  assert.ok(/EXTEND/.test(fs.readFileSync(path.join(__dirname, "../INSTALL-WEBOS.md"), "utf8")));
});

test("#47: 1080p copies of the 4K wallpapers, used on 1080p panels (or by choice), never instead of a missing file", () => {
  const dir = path.join(__dirname, "../app/wallpapers-1080");
  const idx = fs.readFileSync(path.join(dir, "index.js"), "utf8");
  const names = JSON.parse(/OMARCHY_WP1080 = (\[.*\]);/.exec(idx)[1]);
  assert.ok(names.length >= 30);
  for (const n of names) {
    assert.ok(fs.existsSync(path.join(dir, n)), n);
    assert.ok(fs.existsSync(path.join(__dirname, "../../launcher/app/src/main/assets/wallpapers", n)), "a copy of a bundled one: " + n);
  }
  const app = fs.readFileSync(path.join(__dirname, "../app/app.js"), "utf8");
  assert.ok(/window\.devicePixelRatio \|\| 1\) <= 1/.test(app) && /OMARCHY_WP1080\.indexOf\(rel\) >= 0/.test(app));
  assert.ok(fs.readFileSync(path.join(__dirname, "../app/index.html"), "utf8").indexOf('src="wallpapers-1080/index.js"') > 0);
});
