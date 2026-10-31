// node --test webos/tests/companion.test.js — the Grok Bot companion's pure parts on webOS/Tizen.
const test = require("node:test");
const assert = require("assert");
global.window = global;
require("../app/vendor/grokbot/grokbot-avatar.js");
const C = require("../app/companion.js");

test("Tom's 14 shapes and 11 colours draw", () => {
  assert.strictEqual(window.GrokbotShapes.NAMES.length, 14);
  assert.deepStrictEqual(C.SHAPES, window.GrokbotShapes.NAMES);
  assert.deepStrictEqual(C.COLORS, window.GrokbotPalette.NAMES);
  for (const s of C.SHAPES) {
    const svg = C.avatarSvg({ shape: s, color: "blue", px: 64, gx: 0.6, gy: -0.4 });
    assert.match(svg, /<path d="M [\d.]+ [\d.]+ Q/);
    assert.strictEqual((svg.match(/<rect /g) || []).length, 2, s);
    assert.ok(!/NaN|undefined/.test(svg), s);
  }
  assert.match(C.avatarSvg({ shape: "pebble" }), new RegExp(window.GrokbotShapes.STEM_COLOR));
  assert.strictEqual(C.colors("orange").fill, "#FF6700");
  assert.strictEqual(C.colors("black").fill, "#FFFFFF");
  assert.strictEqual(C.colors("#000000").eye, "#F4F4F5");
});

test("blink and sleep close the eyes", () => {
  const h = (svg) => Number(/height="([\d.]+)" rx/.exec(svg)[1]);
  const open = h(C.avatarSvg({ shape: "blob" })), shut = h(C.avatarSvg({ shape: "blob", open: 0 })), sleep = h(C.avatarSvg({ shape: "blob", pose: "sleep" }));
  assert.ok(open > shut * 3 && open > sleep * 3);
});

test("links: http(s) only, YouTube ids", () => {
  const l = C.links("watch https://youtu.be/dQw4w9WgXcQ. and https://x.com/a, file:///etc/passwd javascript:alert(1) https://a.b/v.mp4");
  assert.deepStrictEqual(l.map((x) => x.kind), ["youtube", "url", "video"]);
  assert.strictEqual(l[0].videoId, "dQw4w9WgXcQ");
  assert.strictEqual(C.youtubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=3"), "dQw4w9WgXcQ");
  assert.strictEqual(C.youtubeId("https://evil.com/watch?v=dQw4w9WgXcQ"), "");
  assert.deepStrictEqual(C.cleanLinks([{ url: "javascript:x" }, { url: "https://youtube.com/shorts/dQw4w9WgXcQ", kind: "url" }]).map((x) => x.kind), ["youtube"]);
});

test("open / apri / launch match installed apps", () => {
  const apps = [{ id: "netflix", name: "Netflix" }, { id: "youtube", name: "YouTube" }, { id: "prime", name: "Prime Video" },
    { id: "disney", name: "Disney+" }, { id: "spotify", name: "Spotify" }];
  const pick = (t) => { const q = C.launchQuery(t); const a = q && C.matchApp(q, apps); return a ? a.id : null; };
  assert.strictEqual(pick("open Netflix"), "netflix");
  assert.strictEqual(pick("Apri Netflix"), "netflix");
  assert.strictEqual(pick("apri l'app YouTube"), "youtube");
  assert.strictEqual(pick("launch youtube please"), "youtube");
  assert.strictEqual(pick("avvia prime"), "prime");
  assert.strictEqual(pick("open disney plus"), "disney");
  assert.strictEqual(pick("apri spotifi"), "spotify");
  assert.strictEqual(pick("starte Netflix"), "netflix");
  assert.strictEqual(pick("open the pod bay doors on my ship now"), null);
  assert.strictEqual(pick("what is the weather"), null);
  assert.strictEqual(pick("open hbo"), null);
});

test("per-bot history and unread dots", () => {
  const store = { d: {}, getItem(k) { return this.d[k] ?? null; }, setItem(k, v) { this.d[k] = v; } };
  C.push(store, "bob", "user", "hi");
  C.push(store, "bob", "bot", "hello", { unread: true, links: [{ url: "https://a.b" }] });
  C.push(store, "ann", "bot", "yo", { unread: true });
  assert.strictEqual(C.history(store, "bob").length, 2);
  assert.strictEqual(C.unread(store), 2);
  C.markRead(store, "bob");
  assert.strictEqual(C.unread(store, "bob"), 0);
  for (let i = 0; i < 40; i++) C.push(store, "ann", "user", "q" + i);
  assert.strictEqual(C.history(store, "ann").length, 30);
  C.clear(store, "ann");
  assert.strictEqual(C.history(store, "ann").length, 0);
  assert.strictEqual(C.history(store, "bob").length, 2);
});

test("drift stays small", () => {
  for (let t = 0; t < 1.2e6; t += 7e3) { const d = C.drift(t); assert.ok(Math.abs(d.x) <= 14 && Math.abs(d.y) <= 8); }
});

test("PC-sent LAN streams and local files are never YouTube links (internal player)", () => {
  ["http://192.168.1.20:8765/cast/3f9a1c/movie.mkv",
   "http://192.168.1.20:8765/cast/3f9a1c/youtube.com/watch?v=dQw4w9WgXcQ",
   "http://omarchy.local:8765/cast/tok/My YouTube video [dQw4w9WgXcQ].webm",
   "file:///media/youtube-dQw4w9WgXcQ.mp4",
   "http://youtube.com.lan:8080/watch?v=dQw4w9WgXcQ"].forEach((u) => assert.strictEqual(C.youtubeId(u), "", u));
  assert.strictEqual(C.cleanLinks([{ url: "http://192.168.1.20:8765/cast/t/youtube.mp4", kind: "video" }])[0].kind, "video");
  ["https://www.youtube.com/watch?v=dQw4w9WgXcQ", "https://youtu.be/dQw4w9WgXcQ", "https://www.youtube.com/shorts/dQw4w9WgXcQ"]
    .forEach((u) => assert.strictEqual(C.youtubeId(u), "dQw4w9WgXcQ", u));
});
