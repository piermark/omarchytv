// node --test webos/tests/living.test.js — Living screensaver ASCII scene (LG/Samsung page and Android saver).
const test = require("node:test");
const assert = require("assert");
const fs = require("fs");
const L = require("../app/living.js");
const NOW = Date.parse("2026-11-02T16:00:00Z");
const DATA = {
  weather: { t: 9, unit: "", code: 0, desc: "sereno", min: 6, max: 16, place: "Alessandria" },
  notes: [{ app: "Slack", title: "Ola", body: "ci sei stasera? porto io la pizza e guardiamo il film sulla TV nuova", at: "2026-11-02T15:58:00Z" }],
  x: [{ kind: "mention", user: "@ana", text: "@MarcoPorro la boot screen di OmarchyTV è stupenda", at: "2026-11-02T15:30:00Z" }],
  github: [{ repo: "piermark/omarchytv", what: "★ star", who: "fan", text: "fan", at: "2026-11-02T13:00:00Z" }],
  xMode: "api"
};

test("every box line has the same width", () => {
  const lines = L.render(DATA, { width: 44, now: NOW });
  assert.ok(lines.length > 12);
  lines.forEach((l) => assert.strictEqual(l.length, 44, JSON.stringify(l)));
  const txt = lines.join("\n");
  ["weather · Alessandria", "9°", "sereno", "▸ Slack · Ola", "2m", "@ana", "30m", "● ★ star  omarchytv", "3h"]
    .forEach((s) => assert.ok(txt.indexOf(s) >= 0, s + "\n" + txt));
});

test("weather art is 12 wide for every sky", () => {
  Object.keys(L.ART).forEach((k) => L.ART[k].forEach((r) => assert.strictEqual(r.length, 12, k + ": " + JSON.stringify(r))));
  assert.strictEqual(L.artFor(0), "sun");
  assert.strictEqual(L.artFor(63), "rain");
  assert.strictEqual(L.artFor(95), "storm");
});

test("X API errors read as a message, empty sections are left out", () => {
  const t = L.render({ xMode: "api", xError: "x_budget" }, { width: 40, labels: { x_budget: "limit reached" }, now: NOW }).join("\n");
  assert.ok(t.indexOf("limit reached") > 0);
  assert.deepStrictEqual(L.render({}, { now: NOW }), []);
  assert.strictEqual(L.ago("2026-11-02T15:59:30Z", NOW), "now");
  assert.strictEqual(L.ago("bad", NOW), "");
});

test("wrap keeps long words and limits lines", () => {
  assert.deepStrictEqual(L.wrap("aaaa bbbb cccc", 9, 5), ["aaaa bbbb", "cccc"]);
  assert.strictEqual(L.wrap("x".repeat(30), 10, 2).length, 2);
});

test("newest changes when something arrives", () => {
  const a = L.newest(DATA), b = L.newest(Object.assign({}, DATA, { notes: [{ app: "Mail", title: "t", body: "b", at: "x" }] }));
  assert.notStrictEqual(a, b);
});

test("Android's saver copy is identical, both pages load it", () => {
  const web = fs.readFileSync(__dirname + "/../app/living.js", "utf8");
  const android = fs.readFileSync(__dirname + "/../../launcher/app/src/main/assets/saver/living.js", "utf8");
  assert.strictEqual(android, web);
  assert.ok(fs.readFileSync(__dirname + "/../app/index.html", "utf8").indexOf('src="living.js"') > 0);
  assert.ok(fs.readFileSync(__dirname + "/../../launcher/app/src/main/assets/saver/index.html", "utf8").indexOf('src="living.js"') > 0);
});
