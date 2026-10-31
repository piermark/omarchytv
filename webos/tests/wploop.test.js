// node --test webos/tests/wploop.test.js — "Always-animated wallpaper" pause rule (webOS/Tizen).
const test = require("node:test");
const assert = require("assert");
const W = require("../app/wploop.js");

test("off by default: the intro plays once, never loops", () => {
  assert.strictEqual(W.shouldPlay({}), false);
  assert.strictEqual(W.shouldPlay(undefined), false);
});
test("on: loops on Home, pauses behind another app, Cinemarchy or the screensaver", () => {
  assert.strictEqual(W.shouldPlay({ loop: true }), true);
  assert.strictEqual(W.shouldPlay({ loop: true, hidden: true }), false);
  assert.strictEqual(W.shouldPlay({ loop: true, cinemarchy: true }), false);
  assert.strictEqual(W.shouldPlay({ loop: true, saver: true }), false);
});
test("index.html loads wploop.js before app.js", () => {
  const html = require("fs").readFileSync(__dirname + "/../app/index.html", "utf8");
  assert.ok(html.indexOf("wploop.js") > 0 && html.indexOf("wploop.js") < html.indexOf("app.js"));
});
