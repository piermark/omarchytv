// node --test webos/tests/grokdirect.test.js — Grok Bot diretto (same rules as Android GrokbotDirect).
const test = require("node:test");
const assert = require("assert");
const G = require("../app/grokdirect.js");

function mem() { const m = {}; return { getItem: (k) => (k in m ? m[k] : null), setItem: (k, v) => { m[k] = String(v); }, m }; }

test("topic is otv- plus 32 lowercase alphanumerics and persists", () => {
  const t = G.newTopic();
  assert.match(t, /^otv-[a-z0-9]{32}$/);
  const s = mem();
  const a = G.load(s), b = G.load(s);
  assert.ok(G.validTopic(a.topic));
  assert.strictEqual(a.topic, b.topic);
  assert.strictEqual(a.speak, true);
});

test("headers copy grokbot-ask; key is masked; URL must be https", () => {
  const h = G.headers("crsr_placeholder123");
  assert.strictEqual(h.Authorization, "Bearer crsr_placeholder123");
  assert.strictEqual(h["X-Automation-Key"], "crsr_placeholder123");
  assert.ok(!G.mask("crsr_abcdefghijklmnop1234").includes("abcdefgh"));
  assert.ok(G.validUrl("https://example.com/hook/PLACEHOLDER"));
  assert.ok(!G.validUrl("http://example.com/x"));
  assert.ok(!G.validKey("bad key"));
});

test("body has question, lang, reply_topic, id, apps, context, reply_server", () => {
  const b = JSON.parse(G.body("hi", "it", "otv-x", "id1", ["Netflix", ""], "ctx"));
  assert.deepStrictEqual(b, { question: "hi", lang: "it", reply_topic: "otv-x", id: "id1", apps: ["Netflix"], context: "ctx", reply_server: "https://ntfy.sh/" });
});

test("find matches on id and validates actions", () => {
  const msg = (o) => JSON.stringify({ event: "message", message: JSON.stringify(o) });
  const nd = [JSON.stringify({ event: "open" }), msg({ id: "x", text: "no" }), msg({ id: "me", text: "Ciao", action: { type: "play_video", url: "https://youtu.be/dQw4w9WgXcQ" } })].join("\n");
  const r = G.find(nd, "me");
  assert.strictEqual(r.text, "Ciao");
  assert.strictEqual(r.action.youtube, true);
  assert.strictEqual(r.action.videoId, "dQw4w9WgXcQ");
  assert.strictEqual(G.find(nd, "none"), null);
  assert.strictEqual(G.action({ type: "play_video", url: "https://example.com/a.mp4" }).youtube, false);
  assert.strictEqual(G.action({ type: "play_video", url: "javascript:alert(1)" }), null);
  assert.strictEqual(G.action({ type: "rm" }), null);
  assert.strictEqual(G.action({ type: "none" }), null);
  assert.strictEqual(G.youtubeSearchUrl("dhh rails"), "https://www.youtube.com/results?search_query=dhh+rails");
  assert.strictEqual(G.pollUrl("otv-a", 100), "https://ntfy.sh/otv-a/json?poll=1&since=100");
});

function fakeClock() {
  let t = 1_000_000;
  return { now: () => t, setTimeout: (fn, ms) => { t += ms; Promise.resolve().then(fn); } };
}

test("ask posts with the key, polls ntfy and returns the matching reply", async () => {
  const st = { enabled: true, url: "https://example.com/hook", key: "crsr_placeholder123", topic: G.newTopic(), speak: true };
  const calls = []; let id = null, polls = 0;
  const fetch = (u, o) => {
    calls.push([u, o]);
    if (o && o.method === "POST") { id = JSON.parse(o.body).id; return Promise.resolve({ ok: true, status: 200 }); }
    polls++;
    const body = polls < 2 ? "" : JSON.stringify({ event: "message", message: JSON.stringify({ id, text: "ok", action: { type: "open_app", name: "Netflix" } }) });
    return Promise.resolve({ ok: true, text: () => Promise.resolve(body) });
  };
  const c = fakeClock();
  const r = await new Promise((res) => G.ask(st, "q", "it", [], "", (e, rep) => res({ e, rep }), { fetch, now: c.now, setTimeout: c.setTimeout }));
  assert.strictEqual(r.e, null);
  assert.strictEqual(r.rep.text, "ok");
  assert.strictEqual(calls[0][1].headers.Authorization, "Bearer crsr_placeholder123");
  assert.ok(calls[1][0].startsWith("https://ntfy.sh/" + st.topic + "/json?poll=1&since="));
});

test("ask gives a clear error after 90 s with no reply", async () => {
  const st = { enabled: true, url: "https://example.com/hook", key: "crsr_placeholder123", topic: G.newTopic() };
  const fetch = (u, o) => Promise.resolve(o && o.method === "POST" ? { ok: true, status: 200 } : { ok: true, text: () => Promise.resolve("") });
  const c = fakeClock();
  const e = await new Promise((res) => G.ask(st, "q", "en", [], "", (err) => res(err), { fetch, now: c.now, setTimeout: c.setTimeout }));
  assert.match(e, /within 90 s/);
});

test("ask reports a refused key without echoing it", async () => {
  const st = { enabled: true, url: "https://example.com/hook", key: "crsr_placeholder123", topic: G.newTopic() };
  const e = await new Promise((res) => G.ask(st, "q", "en", [], "", (err) => res(err), { fetch: () => Promise.resolve({ ok: false, status: 401 }) }));
  assert.match(e, /HTTP 401/);
  assert.ok(!e.includes("crsr_placeholder123"));
});

test("tolerant actions: play_video with only a query or title searches YouTube; app aliases name", () => {
  assert.deepStrictEqual(G.action({ type: "play_video", query: "dhh rails" }), { type: "search_youtube", query: "dhh rails" });
  assert.deepStrictEqual(G.action({ type: "play_video", url: "", title: "Omarchy" }), { type: "search_youtube", query: "Omarchy" });
  assert.strictEqual(G.action({ type: "play_video" }), null);
  assert.strictEqual(G.action({ type: "open_app", app: "Netflix" }).name, "Netflix");
});

test("dropped actions are flagged, none is not", () => {
  assert.strictEqual(G.parse(JSON.stringify({ id: "a", text: "x", action: { type: "rm" } }), "a").dropped, true);
  assert.strictEqual(G.parse(JSON.stringify({ id: "b", text: "x", action: { type: "none" } }), "b").dropped, false);
  assert.strictEqual(G.parse(JSON.stringify({ id: "c", text: "x" }), "c").dropped, false);
  assert.strictEqual(G.parse(JSON.stringify({ id: "d", text: "x", action: "play_video" }), "d").dropped, true);
});

test("reply lang is kept only when it is a valid BCP-47 tag", () => {
  assert.strictEqual(G.parse(JSON.stringify({ id: "a", text: "x", lang: "it" }), "a").lang, "it");
  assert.strictEqual(G.parse(JSON.stringify({ id: "a", text: "x", lang: "pt-BR" }), "a").lang, "pt-BR");
  assert.strictEqual(G.parse(JSON.stringify({ id: "a", text: "x", lang: "english please" }), "a").lang, "");
  assert.strictEqual(G.parse(JSON.stringify({ id: "a", text: "x" }), "a").lang, "");
});

test("xAI voice: body, voice list, 8 s timeout falls back, blob on success", async () => {
  assert.deepStrictEqual(JSON.parse(G.ttsBody("Ciao", "eve", "it")), { text: "Ciao", voice_id: "eve", language: "it" });
  assert.deepStrictEqual(JSON.parse(G.ttsBody("Hi", "bad voice!", "")), { text: "Hi", voice_id: "eve", language: "auto" });
  assert.deepStrictEqual(G.voices({ voices: [{ voice_id: "eve" }, { voice_id: "ara" }, { voice_id: "x y" }] }), ["eve", "ara"]);
  assert.deepStrictEqual(G.voices("nope"), []);
  const st = { xaiOn: true, xaiKey: "placeholder-key-123", xaiVoice: "eve" };
  assert.ok(G.xaiOn(st));
  assert.ok(!G.xaiOn({ xaiOn: false, xaiKey: "placeholder-key-123" }));
  let sent;
  const blob = await G.xaiAudio(st, "Ciao", "it", { fetch: (u, o) => { sent = [u, o]; return Promise.resolve({ ok: true, blob: () => Promise.resolve("MP3") }); }, setTimeout: () => {} });
  assert.strictEqual(blob, "MP3");
  assert.strictEqual(sent[0], "https://api.x.ai/v1/tts");
  assert.strictEqual(sent[1].headers.Authorization, "Bearer placeholder-key-123");
  await assert.rejects(G.xaiAudio(st, "x", "", { fetch: () => new Promise(() => {}), setTimeout: (fn) => fn() }), /timeout/);
  await assert.rejects(G.xaiAudio(st, "x", "", { fetch: () => Promise.resolve({ ok: false, status: 401 }), setTimeout: () => {} }), /401/);
});

test("your own reply server (#70): https only, ntfy.sh otherwise; the routine is told where", () => {
  const G = require("../app/grokdirect.js");
  assert.strictEqual(G.server(""), "https://ntfy.sh/");
  assert.strictEqual(G.server(undefined), "https://ntfy.sh/");
  assert.strictEqual(G.server("https://ntfy.example.com"), "https://ntfy.example.com/");
  assert.strictEqual(G.server("https://ntfy.example.com:8443"), "https://ntfy.example.com:8443/");
  assert.ok(!G.validServer("http://192.168.1.20:2586/"), "the cloud routine can't reach the LAN");
  assert.ok(!G.validServer("http://ntfy.example.com/"));
  assert.ok(!G.validServer("http://8.8.8.8/"));
  assert.ok(!G.validServer("https://x.com/?a=b"));
  assert.ok(!G.validServer("javascript:alert(1)"));
  assert.strictEqual(G.serverHost(""), "ntfy.sh");
  assert.strictEqual(G.serverHost("https://ntfy.example.com:8443"), "ntfy.example.com");
  assert.strictEqual(G.pollUrl("otv-t", 5), "https://ntfy.sh/otv-t/json?poll=1&since=5");
  assert.strictEqual(G.pollUrl("otv-t", 5, "https://ntfy.example.com"), "https://ntfy.example.com/otv-t/json?poll=1&since=5");
  assert.strictEqual(JSON.parse(G.body("q", "it", "otv-t", "id", [], "c", "https://ntfy.example.com")).reply_server, "https://ntfy.example.com/");
  assert.strictEqual(JSON.parse(G.body("q", "it", "otv-t", "id", [], "c")).reply_server, "https://ntfy.sh/");
  assert.ok(G.validToken("tk_abcdefgh12345678") && !G.validToken("abc"));
});

test("ask() posts the server to the routine and reads the replies from it, with the token", async () => {
  const G = require("../app/grokdirect.js");
  const calls = [];
  let t = 1000;
  const s = { enabled: true, url: "https://hook.example.com/x", key: "crsr_placeholder123", topic: "otv-" + "a".repeat(32),
    server: "https://ntfy.example.com", ntfyToken: "tk_abcdefgh12345678" };
  const fetch = (u, o) => {
    calls.push([u, o]);
    if (u === s.url) return Promise.resolve({ ok: true, status: 200 });
    const id = JSON.parse(calls[0][1].body).id;
    return Promise.resolve({ ok: true, text: () => Promise.resolve(JSON.stringify({ event: "message", message: JSON.stringify({ id, text: "hi" }) })) });
  };
  const rep = await new Promise((res) => G.ask(s, "q", "it", [], "c", (e, r) => res(r), { fetch, now: () => (t += 100), setTimeout: (f) => f() }));
  assert.strictEqual(rep.text, "hi");
  assert.strictEqual(JSON.parse(calls[0][1].body).reply_server, "https://ntfy.example.com/");
  assert.ok(calls[1][0].startsWith("https://ntfy.example.com/otv-"));
  assert.strictEqual(calls[1][1].headers.Authorization, "Bearer tk_abcdefgh12345678");
});
