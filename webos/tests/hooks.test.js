// node webos/tests/hooks.test.js — platform hooks for other TV browsers (issue #66).
const assert = require("assert");
const H = require("../app/hooks.js");
assert.strictEqual(H.clock24("auto", "en"), false);
assert.strictEqual(H.clock24("auto", "it"), true);
assert.strictEqual(H.clock24("auto", "en", true), true, "platform says 24 h (en-US browser in the Netherlands)");
assert.strictEqual(H.clock24("auto", "it", false), false);
assert.strictEqual(H.clock24("12", "it", true), false, "an explicit setting always wins");
assert.strictEqual(H.clock24("24", "en", false), true);
assert.strictEqual(H.stageScale(1920, 1080, false, 1920, 1080, 1.5), 1, "webOS/Tizen unchanged");
assert.strictEqual(H.stageScale(1280, 720, false), 1280 / 1920);
assert.ok(Math.abs(H.stageScale(1920, 1080, true, 1920, 1080, 1.5) - 2 / 3) < 1e-9, "Vestel: innerWidth lies");
assert.strictEqual(H.stageScale(1920, 1080, true, 0, 0, 1.5), 1);
assert.strictEqual(H.stageScale(0, 0, false), 1);
console.log("hooks.test.js: all assertions passed");
