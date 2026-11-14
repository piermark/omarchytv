// node --test webos/tests/pulse-adapt.test.js — host wrapper around vendored Pulse (dpr, 720p floor, fps cap)
const test = require("node:test");
const assert = require("assert");
const fs = require("fs");
const path = require("path");

const APP = path.join(__dirname, "../app");
const A = require(path.join(APP, "pulse-adapt.js"));
const androidCopy = path.join(__dirname, "../../launcher/app/src/main/assets/omarchytv-pulse-adapt.js");

test("the Android asset is the same host wrapper", () => {
  const a = fs.readFileSync(path.join(APP, "pulse-adapt.js"), "utf8");
  const b = fs.readFileSync(androidCopy, "utf8");
  assert.strictEqual(a, b);
});

test("pulse-host.html loads the adapter first and does not touch vendor sources", () => {
  const html = fs.readFileSync(path.join(APP, "pulse-host.html"), "utf8");
  assert.ok(html.indexOf('src="pulse-adapt.js"') < html.indexOf("pulse-tv.js"));
  assert.ok(html.indexOf("vendor/pulse/pulse-tv.js") > 0);
  assert.ok(html.indexOf("vendor/pulse/pulse.js") > 0);
  const app = fs.readFileSync(path.join(APP, "app.js"), "utf8");
  assert.match(app, /pulse-host\.html#cfg=/);
  assert.ok(!app.includes("vendor/pulse/index.html#cfg="), "LG/Samsung open the host page, not the vendored index directly");
  assert.ok(app.indexOf("homeFxForSaver(true)") > 0 && app.indexOf("homeFxForSaver(false)") > 0);
});

test("at high, the backing store is CSS × dpr (full device pixels), never shaved down to 1× CSS", () => {
  const full1 = A.clampBacking(1920, 1080, 1080, 1, 1, 1920);
  assert.strictEqual(full1.w, 1920);
  assert.strictEqual(full1.h, 1080);
  const full2 = A.clampBacking(1920, 1080, 1080, 1, 2, 1920);
  assert.strictEqual(full2.w, 3840);
  assert.strictEqual(full2.h, 2160, "a 4K panel with a 1080p UI keeps a 4K scene and sharp plane labels");
  const already = A.clampBacking(3840, 2160, 1080, 1, 2, 1920);
  assert.strictEqual(already.w, 3840);
  assert.strictEqual(already.h, 2160);
});

test("720p is the floor of adaptive scale, including when dpr is 2", () => {
  const floor = A.clampBacking(3840, 2160, 1080, 720 / 1080, 2, 1920);
  assert.strictEqual(floor.h, 720);
  assert.strictEqual(floor.w, 1280);
  const ui720 = A.clampBacking(1280, 720, 720, 1, 1, 1280);
  assert.strictEqual(ui720.h, 720);
  assert.strictEqual(A.scaleFloor(1080), 720 / 1080);
  assert.strictEqual(A.scaleFloor(720), 1);
});

test("the policy starts at high 30 fps, steps down to the 720p floor, then may drop fps, and climbs back", () => {
  assert.strictEqual(A.FPS_HIGH, 30);
  let s = { scale: 1, slow: 0, fast: 0, floor: 720 / 1080, fps: 30 };
  for (let i = 0; i < 2; i++) s = A.stepPolicy(s, 22);
  assert.strictEqual(s.scale, 1);
  s = A.stepPolicy(s, 22);
  assert.strictEqual(s.changed, "down");
  assert.ok(Math.abs(s.scale - 720 / 1080) < 1e-9);
  assert.strictEqual(s.fps, A.FPS_FLOOR);
  for (let i = 0; i < 2; i++) s = A.stepPolicy(s, 22);
  s = A.stepPolicy(s, 22);
  assert.strictEqual(s.changed, "fps");
  assert.strictEqual(s.fps, 15);
  assert.ok(Math.abs(s.scale - 720 / 1080) < 1e-9, "never below the floor");
  for (let i = 0; i < 5; i++) s = A.stepPolicy(s, 8);
  s = A.stepPolicy(s, 8);
  assert.strictEqual(s.fps, A.FPS_FLOOR);
  for (let i = 0; i < 6; i++) s = A.stepPolicy(s, 8);
  assert.strictEqual(s.changed, "up");
  assert.strictEqual(s.scale, 1);
  assert.strictEqual(s.fps, 30);
});

test("the wrapper caps rAF and does not start a second animation loop of its own", () => {
  const src = fs.readFileSync(path.join(APP, "pulse-adapt.js"), "utf8");
  assert.match(src, /requestAnimationFrame\.__omarchyPulse/);
  assert.ok(!/requestAnimationFrame\(tick\)/.test(src), "no host rAF pump beside the engine");
  assert.match(src, /imageSmoothingEnabled/);
});
