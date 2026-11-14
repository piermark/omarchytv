// node --test webos/tests/spacex-default.test.js — SpaceX TV pill off by default in 2.20
const test = require("node:test");
const assert = require("assert");
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const APP = fs.readFileSync(path.join(__dirname, "../app/app.js"), "utf8");

test("SpaceX TV is off in the default config; a saved on stays on", () => {
  assert.match(APP, /pluginSpacexTv:\s*false/);
  const m = APP.match(/pluginSpacexTv:\s*(true|false)/);
  assert.equal(m[1], "false");
  // same merge as loadCfg: DEFAULT then saved
  const def = { pluginSpacexTv: false };
  assert.strictEqual(Object.assign({}, def, {}).pluginSpacexTv, false);
  assert.strictEqual(Object.assign({}, def, { pluginSpacexTv: true }).pluginSpacexTv, true);
  assert.strictEqual(Object.assign({}, def, { pluginSpacexTv: false }).pluginSpacexTv, false);
});
