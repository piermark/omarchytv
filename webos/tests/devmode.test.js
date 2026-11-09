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
