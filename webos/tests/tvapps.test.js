// node --test webos/tests/tvapps.test.js — LG webOS installed-apps merge (app/tvapps.js).
const test = require("node:test");
const assert = require("assert");
const { merge, iconUrl } = require("../app/tvapps.js");

const DEF = [
  { id: "tv", name: "TV", appId: "com.webos.app.livetv", brand: "tv" },
  { id: "netflix", name: "Netflix", appId: "netflix", brand: "netflix", bg: "#000", fg: "#e50914", sub: "NETFLIX" },
  { id: "raiplay", name: "RaiPlay", appId: "it.rai.raiplay", brand: "raiplay", sub: "RaiPlay" },
  { id: "plex", name: "Plex", appId: "cdp-30", brand: "plex" },
  { id: "settings", name: "LG Settings", appId: "com.palm.app.settings", brand: "settings" }
];
const R = "https://127.0.0.1:3001/resources/abc/";
const LPS = [
  { id: "netflix", title: "Netflix", icon: R + "n.png" },
  { id: "com.atlas.tv", title: "NOW", icon: R + "now.png", largeIcon: R + "nowL.png", iconColor: "#112233" },
  { id: "it.rainet.raiplay", title: "RaiPlay", icon: R + "r.png" },
  { id: "com.mubi", title: "MUBI", icon: R + "m.png", extraLargeIcon: "/media/cryptofs/apps/usr/palm/applications/com.mubi/x.png" },
  { id: "com.webos.app.discovery", title: "Apps", systemApp: true, icon: R + "lgstore.png" },
  { id: "com.webos.app.livetv", title: "Canali TV", systemApp: true },
  { id: "com.omarchytv.home", title: "OmarchyTV" },
  { id: "bad id\"", title: "x" },
  { id: "hid", title: "Hidden", visible: false },
  { id: "com.mubi", title: "MUBI" }
];

test("defaults keep styling, take the real appId, uninstalled ones drop", () => {
  const out = merge(DEF, LPS);
  const ids = out.map((a) => a.id);
  assert.deepStrictEqual(ids.slice(0, 4), ["tv", "netflix", "raiplay", "settings"]);
  assert.ok(!ids.includes("plex"), "not installed: dropped");
  assert.strictEqual(out[1].sub, "NETFLIX");
  assert.deepStrictEqual(out[1].iconPaths, [R + "n.png"]);
  assert.strictEqual(out[0].iconPaths, undefined, "TV tile keeps its own look");
  assert.strictEqual(out[2].appId, "it.rainet.raiplay");
  assert.strictEqual(DEF[2].appId, "it.rai.raiplay", "defaults are not mutated");
});

test("other launch points become icon tiles; self, hidden, invalid and duplicates skipped", () => {
  const out = merge(DEF, LPS);
  const extra = out.slice(4);
  assert.deepStrictEqual(extra.map((a) => a.name), ["NOW", "MUBI", "Apps"]);
  assert.deepStrictEqual(extra[0].iconPaths, [R + "nowL.png", R + "now.png"]);
  assert.strictEqual(extra[0].bg, "#112233");
  assert.strictEqual(extra[0].appId, "com.atlas.tv");
  assert.strictEqual(extra[1].iconPaths[1], "/media/cryptofs/apps/usr/palm/applications/com.mubi/x.png");
  assert.strictEqual(extra[2].appId, "com.webos.app.discovery", "LG app store stays");
  assert.ok(extra.every((a) => a.tvApp && /^lg\./.test(a.id)));
});

test("invalid or empty lists return null so DEFAULT_APPS stay", () => {
  assert.strictEqual(merge(DEF, null), null);
  assert.strictEqual(merge(DEF, []), null);
  assert.strictEqual(merge(DEF, [{ id: "com.omarchytv.home", title: "OmarchyTV" }]), null);
});

test("icon urls are restricted", () => {
  assert.strictEqual(iconUrl("https://evil.example/resources/x.png"), undefined);
  assert.strictEqual(iconUrl('https://127.0.0.1:3001/resources/a".png'), undefined);
  assert.strictEqual(iconUrl("/a/../b.png"), undefined);
  assert.strictEqual(iconUrl("http://192.168.1.50:3000/resources/a.png"), "http://192.168.1.50:3000/resources/a.png");
});
