// node --test webos/tests/liftoff.test.js — liftoff countdown (webOS/Tizen), same cases as Android LiftoffScriptTest.
const test = require("node:test");
const assert = require("assert");
const fs = require("fs");
const L = require("../app/liftoff.js");
const CASES = JSON.parse(fs.readFileSync(__dirname + "/liftoff-cases.json", "utf8"));
const JAVA = fs.readFileSync(__dirname + "/../../launcher/app/src/main/java/com/omarchytv/home/LiftoffScript.java", "utf8");

test("clock, progress and final count match the shared cases", () => {
  for (const c of CASES) {
    assert.strictEqual(L.clock(c.s, c.since), c.clock, JSON.stringify(c));
    assert.strictEqual(L.progress(c.s, c.since), c.progress, JSON.stringify(c));
    assert.strictEqual(L.finalCount(c.s, c.since), c.final, JSON.stringify(c));
  }
});

test("bar", () => {
  assert.strictEqual(L.bar(-1, 10), "");
  assert.strictEqual(L.bar(50, 10), "[█████░░░░░] 50%");
  assert.strictEqual(L.bar(100, 4), "[████] 100%");
});

test("the rocket and flames are the same as on Android", () => {
  const javaRows = (name) => {
    const m = JAVA.match(new RegExp("static final String\\[\\]\\[?\\]? " + name + " = \\{([\\s\\S]*?)\\};"));
    return JSON.parse("[" + m[1].replace(/\{/g, "[").replace(/\}/g, "]").replace(/,\s*$/, "").replace(/,(\s*[\]])/g, "$1") + "]");
  };
  assert.deepStrictEqual(javaRows("ROCKET"), L.ROCKET);
  assert.deepStrictEqual(javaRows("FLAME"), L.FLAME);
  L.ROCKET.forEach((r) => assert.strictEqual(r.length, 10));
});

test("the page loads liftoff.js before app.js", () => {
  const html = fs.readFileSync(__dirname + "/../app/index.html", "utf8");
  assert.ok(html.indexOf('src="liftoff.js"') > 0 && html.indexOf('src="liftoff.js"') < html.indexOf('src="app.js"'));
});
