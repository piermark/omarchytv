// node --test webos/tests/cinemarchy.test.js — Cinemarchy on LG/Samsung (webos/app/cinemarchy.js).
const test = require("node:test");
const assert = require("assert");
const fs = require("fs");
const path = require("path");
const C = (() => { const ctx = {}; require("vm").runInNewContext(fs.readFileSync(path.join(__dirname, "../app/cinemarchy.js"), "utf8"), Object.assign(ctx, { window: ctx })); return ctx.OmarchyCinemarchy; })();

test("M3U playlists: names, groups, logos, only real stream lines", () => {
  const m3u = '#EXTM3U\n#EXTINF:-1 tvg-id="a" tvg-logo="http://l/a.png" group-title="News",Channel A\nhttps://s/a.m3u8\n' +
    "#EXTVLCOPT:http-user-agent=x\n#EXTINF:-1,Channel B\nhttp://s/b.ts\n\n#EXTINF:-1 group-title=\"Sport\",\njavascript:alert(1)\nhttps://s/c.m3u8\n";
  const ch = C.parseM3U(m3u);
  assert.strictEqual(ch.length, 3);
  assert.deepStrictEqual(JSON.parse(JSON.stringify(ch[0])), { name: "Channel A", url: "https://s/a.m3u8", group: "News", logo: "http://l/a.png" });
  assert.strictEqual(ch[1].name, "Channel B");
  assert.strictEqual(ch.some((c) => /javascript/.test(c.url)), false);
  assert.strictEqual(C.parseM3U("").length, 0);
});

test("ASCII bars and times", () => {
  assert.strictEqual(C.bar(0.5, 10), "█████░░░░░");
  assert.strictEqual(C.bar(2, 4), "████");
  assert.strictEqual(C.fmtTime(3725), "1:02:05");
  assert.strictEqual(C.fmtTime(65), "1:05");
});

test("resume: real positions only, forgets barely started and finished videos, keeps the newest 200", () => {
  const mem = {};
  const store = { getItem: (k) => mem[k] || null, setItem: (k, v) => { mem[k] = v; } };
  const r = C.Resume(store);
  r.put("u1", 600, 1200, "Film");
  assert.deepStrictEqual([r.get("u1").pos, r.get("u1").dur], [600, 1200]);
  assert.strictEqual(r.progress("u1"), 0.5);
  r.put("u1", 1190, 1200); assert.strictEqual(r.get("u1"), null);
  r.put("u2", 5, 1200); assert.strictEqual(r.get("u2"), null);
  r.put("u3", 100, 0); assert.strictEqual(r.get("u3"), null);
  for (let i = 0; i < 230; i++) r.put("x" + i, 100, 1000);
  assert.strictEqual(Object.keys(JSON.parse(mem["omarchytv.cine.resume.v1"])).length, 200);
});

test("remote keys: colours, channels, digits; letters typed in the URL field stay text", () => {
  const k = (keyCode, extra) => C.keyName(Object.assign({ keyCode, key: "" }, extra || {}));
  assert.deepStrictEqual([k(403), k(404), k(405), k(406)], ["red", "green", "yellow", "blue"]);
  assert.deepStrictEqual([k(461), k(10009), k(13), k(33), k(34)], ["back", "back", "ok", "pageup", "pagedown"]);
  assert.strictEqual(k(50), "2");
  assert.strictEqual(k(50, { target: { tagName: "INPUT" } }), "");
  assert.strictEqual(k(8, { target: { tagName: "INPUT" } }), "");
  assert.strictEqual(k(8), "back");
});

test("strings in every language, the page loads it, the open movies are labelled as demo", () => {
  const app = fs.readFileSync(path.join(__dirname, "../app/app.js"), "utf8");
  const i18n = fs.readFileSync(path.join(__dirname, "../app/i18n.js"), "utf8");
  const src = fs.readFileSync(path.join(__dirname, "../app/cinemarchy.js"), "utf8");
  const keys = Array.from(new Set((src.match(/t\("(cine_[a-z_]+)"\)/g) || []).map((s) => s.slice(3, -2))));
  assert.ok(keys.length > 30);
  keys.forEach((k) => {
    assert.strictEqual((app.match(new RegExp("\\b" + k + ":", "g")) || []).length, 3, k + " en/it/de");
    assert.strictEqual((i18n.match(new RegExp('"' + k + '"', "g")) || []).length, 20, k + " in the 20 other languages");
  });
  assert.ok(fs.readFileSync(path.join(__dirname, "../app/index.html"), "utf8").indexOf('<script src="cinemarchy.js"></script>') > 0);
  assert.strictEqual(C.DEMO.length, 4);
  assert.ok(!/4K HDR|OLED|60fps/.test(src));
});
