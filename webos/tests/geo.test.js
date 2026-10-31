// node webos/tests/geo.test.js — "Automatic (from IP)" weather place (issue #5, point 1).
const assert = require("assert");
const G = require("../app/geo.js");
assert.deepStrictEqual(G.parse({ city: "Torino", latitude: 45.0703, longitude: 7.6869 }), { city: "Torino", lat: 45.07, lon: 7.687 });
assert.deepStrictEqual(G.parse({ success: true, city: "Berlin", latitude: "52.52", longitude: "13.405" }), { city: "Berlin", lat: 52.52, lon: 13.405 });
assert.strictEqual(G.parse({ error: true, reason: "RateLimited" }), null);
assert.strictEqual(G.parse({ success: false }), null);
assert.strictEqual(G.parse({ latitude: 0, longitude: 0 }), null);
assert.strictEqual(G.parse({ latitude: 99, longitude: 1 }), null);
assert.strictEqual(G.parse(null), null);
assert.strictEqual(G.parse({ city: "<b>x</b>", latitude: 1, longitude: 2 }).city, "bx/b");
const calls = [];
G.locate((u) => { calls.push(u); return u.includes("ipapi") ? Promise.reject(new Error("429")) : Promise.resolve({ success: true, city: "Oslo", latitude: 59.91, longitude: 10.75 }); })
  .then((p) => {
    assert.deepStrictEqual(calls, G.SOURCES);
    assert.strictEqual(p.city, "Oslo");
    return G.locate(() => Promise.resolve({ error: true }));
  })
  .then((p) => {
    assert.strictEqual(p, null, "both fail → null, the caller keeps its city");
    return G.locate(() => { throw new Error("sync"); });
  })
  .then((p) => {
    assert.strictEqual(p, null);
    console.log("geo.test.js: all assertions passed");
  })
  .catch((e) => { console.error(e); process.exit(1); });
