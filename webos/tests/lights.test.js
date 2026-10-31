// node --test webos/tests/lights.test.js — lights hub leave policy (same rules as Android LightsLeave).
const test = require("node:test");
const assert = require("assert");
const L = require("../app/lights.js");

test("turn off is the default; restore without a snapshot turns off; keep does nothing", () => {
  assert.strictEqual(L.policy(undefined), "off");
  assert.strictEqual(L.action(undefined, false), "off");
  assert.strictEqual(L.action("restore", true), "restore");
  assert.strictEqual(L.action("restore", false), "off");
  assert.strictEqual(L.action("keep", true), "none");
  assert.deepStrictEqual([L.next("off"), L.next("restore"), L.next("keep")], ["restore", "keep", "off"]);
  assert.ok(L.takeSnapshot("restore", false));
  assert.ok(!L.takeSnapshot("restore", true));
  assert.ok(!L.takeSnapshot("off", false));
});

test("Hue snapshot keeps the group's lights and their colour mode; restore body", () => {
  const s = L.hueSnapshot({ lights: ["1", "3"] }, {
    "1": { state: { on: true, bri: 200, colormode: "ct", ct: 366, xy: [0.4, 0.4] } },
    "2": { state: { on: true } },
    "3": { state: { on: false, bri: 50, colormode: "xy", xy: [0.3, 0.2] } } });
  assert.deepStrictEqual(Object.keys(s), ["1", "3"]);
  assert.deepStrictEqual(L.hueRestoreBody(s["1"]), { bri: 200, ct: 366, on: true, transitiontime: 8 });
  assert.deepStrictEqual(L.hueRestoreBody(s["3"]), { on: false, transitiontime: 8 });
});

test("Home Assistant restore: turn_on with the old colour, or turn_off", () => {
  const on = L.haState({ state: "on", attributes: { brightness: 120, color_mode: "rgb", rgb_color: [255, 0, 10] } });
  assert.deepStrictEqual(L.haRestoreCall("light.tv", on), { service: "turn_on", body: { entity_id: "light.tv", brightness: 120, rgb_color: [255, 0, 10], transition: 1 } });
  const ct = L.haState({ state: "on", attributes: { color_mode: "color_temp", color_temp_kelvin: 2700, rgb_color: [1, 2, 3] } });
  assert.strictEqual(L.haRestoreCall("light.a", ct).body.color_temp_kelvin, 2700);
  assert.strictEqual(L.haRestoreCall("light.a", L.haState({ state: "off", attributes: {} })).service, "turn_off");
});
