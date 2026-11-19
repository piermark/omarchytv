// node --test webos/tests/arrange.test.js — v2.21 (#5 point 8): Arrange panels on LG / Samsung
const test = require("node:test");
const assert = require("assert");
const fs = require("fs");
const path = require("path");
const A = require("../app/arrange.js");
const app = fs.readFileSync(path.join(__dirname, "../app/app.js"), "utf8");
const html = fs.readFileSync(path.join(__dirname, "../app/index.html"), "utf8");
const ALL = ["race-box", "wx-box", "pc-box", "ai-box", "devmode-box"];

test("saved order is cleaned: unknown and repeated ids out, missing ones appended", () => {
  assert.deepStrictEqual(A.order(null, ALL), ALL);
  assert.deepStrictEqual(A.order(["pc-box", "bogus", "pc-box", "wx-box"], ALL), ["pc-box", "wx-box", "race-box", "ai-box", "devmode-box"]);
});

test("OK moves a panel up; the top one goes to the bottom", () => {
  assert.deepStrictEqual(A.up(["a", "b", "c"], 2), ["a", "c", "b"]);
  assert.deepStrictEqual(A.up(["a", "b", "c"], 0), ["b", "c", "a"]);
});

test("the menu item opens the arrange list, not a toast; arrange.js loads before app.js", () => {
  assert.ok(app.indexOf('showToast("Arrange panels') < 0);
  assert.ok(app.indexOf("openArrangeModal(0)") > 0);
  assert.ok(html.indexOf('src="arrange.js"') > 0 && html.indexOf('src="arrange.js"') < html.indexOf('src="app.js"'));
});

test("#5 point 13: no English-only modal titles left", () => {
  const code = app.split("\n").filter((l) => l.indexOf("t_themes_sub:") < 0 && l.indexOf("pcRecent") < 0).join("\n");   // not the dictionaries
  for (const s of ['"Select a theme', '"Wallpaper dimming"', '"Pair Omarchy Linux PC"', 'openModal("Ambilight & Lights"',
    'openModal("Grok Bot & AI"', 'openModal("Web apps"', 'openModal("Pulse panels"', 'openModal("Forget hub?"', '"Filter and launch installed webOS apps"'])
    assert.ok(code.indexOf(s) < 0, "still hardcoded: " + s);
});
