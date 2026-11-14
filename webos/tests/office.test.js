// node --test webos/tests/office.test.js — GrokBot Office on LG/Samsung (webos/app/office.js): the same rules as Android's Office.java.
const test = require("node:test");
const assert = require("assert");
const O = require("../app/office.js");
const G = require("../app/grokdirect.js");

test("bots and usage come only from the pushed JSON; bad entries are dropped, nothing is made up", () => {
  const d = O.parse(JSON.stringify({ title: "My office", bots: [
    { name: "Trimmer", role: "Cuts token use", color: "#FF9E64", status: "working" },
    { name: "  Scout  ", color: "cyan" }, { name: "" }, null, { role: "no name" }, { name: "Trimmer", color: "red" },
    { name: "<b>X</b>", color: "javascript:1", status: "hacking" }], usage: { weekly_pct: 46.25, label: "Weekly usage", resets: "Mon 09:00" } }));
  assert.strictEqual(d.title, "My office");
  assert.deepStrictEqual(d.bots.map((b) => b.name), ["Trimmer", "Scout", "bX/b"]);
  assert.strictEqual(d.bots[0].color, "#ff9e64");
  assert.strictEqual(d.bots[1].color, "#7dcfff");
  assert.match(d.bots[2].color, /^#[0-9a-f]{6}$/);
  assert.strictEqual(d.bots[2].status, "idle");
  assert.deepStrictEqual(d.usage, { pct: 46.3, label: "Weekly usage", resets: "Mon 09:00" });
  // the same name always gets the same colour
  assert.strictEqual(O.colorOf("", "Pixel"), O.colorOf(undefined, "Pixel"));
});

test("no usage numbers, no usage card: missing, text or out of range values give null", () => {
  assert.strictEqual(O.parse({ bots: [] }).usage, null);
  assert.strictEqual(O.parse({ usage: { label: "Weekly usage" } }).usage, null);
  assert.strictEqual(O.parse({ usage: { weekly_pct: "lots" } }).usage, null);
  assert.strictEqual(O.parse({ usage: { weekly_pct: 140 } }).usage, null);
  assert.strictEqual(O.parse({ usage: { pct: "12%" } }).usage.pct, 12);
  assert.deepStrictEqual(O.parse("not json"), { title: "", bots: [], usage: null, spend: [], at: "" });
  assert.strictEqual(O.parse({ bots: Array.from({ length: 40 }, (_, i) => ({ name: "b" + i })) }).bots.length, O.MAX_BOTS);
});

test("a reply's office payload: bots replaces, hired adds once, usage updates; and back to JSON", () => {
  let d = O.parse({ bots: [{ name: "Scout" }] });
  d = O.applyReply(d, { hired: { name: "Trimmer", role: "Cuts token use", color: "orange" } });
  assert.deepStrictEqual(d.bots.map((b) => b.name), ["Scout", "Trimmer"]);
  d = O.applyReply(d, { hired: { name: "Trimmer" } });
  assert.strictEqual(d.bots.length, 2);
  d = O.applyReply(d, { usage: { weekly_pct: 50 } });
  assert.strictEqual(d.usage.pct, 50);
  d = O.applyReply(d, { bots: [{ name: "Solo" }] });
  assert.deepStrictEqual(d.bots.map((b) => b.name), ["Solo"]);
  assert.strictEqual(O.applyReply(d, null), d);
  const j = O.toJson(d);
  assert.deepStrictEqual(O.parse(j).bots, d.bots);
  assert.strictEqual(O.parse(j).usage.pct, 50);
});

test("what the user asked: open the office, hire (name and role from their words only), or a question", () => {
  assert.deepStrictEqual(O.intent("open my office"), { type: "open" });
  assert.deepStrictEqual(O.intent("apri l'ufficio"), { type: "open" });
  assert.deepStrictEqual(O.intent("Hire a bot called Trimmer to trim my tokens"), { type: "hire", name: "Trimmer", role: "trim my tokens", provider: "grok" });
  assert.deepStrictEqual(O.intent("assumi un bot chiamato Ragioniere per controllare le spese"), { type: "hire", name: "Ragioniere", role: "controllare le spese", provider: "grok" });
  assert.deepStrictEqual(O.intent("hire someone who watches CI"), { type: "hire", name: "", role: "someone who watches CI", provider: "grok" });
  assert.strictEqual(O.intent("what's the weather tomorrow?").type, "ask");
  assert.strictEqual(O.intent("how is my office doing?").type, "ask");
  assert.strictEqual(O.intent("   ").type, "none");
});

test("hire and ask requests ride on Grok Bot diretto's own body, with an office object", () => {
  const d = O.parse({ bots: [{ name: "Scout" }] });
  const h = O.hireRequest({ name: "Trimmer", role: "trim my tokens" }, d);
  assert.match(h.question, /Hire a new bot for my GrokBot Office named Trimmer: trim my tokens/);
  assert.deepStrictEqual(h.office, { action: "hire", name: "Trimmer", role: "trim my tokens", description: "trim my tokens", bots: ["Scout"] });
  const b = JSON.parse(O.requestBody(G.body(h.question, "en", "otv-x", "id1", [], "ctx"), h.office));
  assert.strictEqual(b.reply_topic, "otv-x");
  assert.strictEqual(b.question, h.question);
  assert.strictEqual(b.office.action, "hire");
  assert.deepStrictEqual(O.askRequest("hi", d).office, { action: "ask", bots: ["Scout"] });
});

test("the office payload is read from the reply with our id only; pending hires confirm by name or by count", () => {
  const msg = (o) => JSON.stringify({ event: "message", message: JSON.stringify(o) });
  const nd = [msg({ id: "other", office: { hired: { name: "X" } } }), "garbage", msg({ id: "me", text: "Done", office: { hired: { name: "Trimmer" } } })].join("\n");
  assert.deepStrictEqual(O.findOffice(nd, "me"), { hired: { name: "Trimmer" } });
  assert.strictEqual(O.findOffice(msg({ id: "me", text: "no office" }), "me"), null);
  assert.strictEqual(O.findOffice(nd, "nobody"), null);
  const d = O.parse({ bots: [{ name: "Scout" }, { name: "trimmer" }] });
  assert.ok(O.confirmed(d, { name: "Trimmer" }));
  assert.ok(!O.confirmed(d, { name: "Pixel" }));
  assert.ok(O.confirmed(d, { name: "", before: 1 }));
  assert.ok(!O.confirmed(d, { name: "", before: 2 }));
  assert.ok(!O.confirmed(d, null));
});

test("desks: at least 6, at most 16, all inside the room; frames capped at 30 a second", () => {
  assert.strictEqual(O.layout(0).length, 6);
  assert.strictEqual(O.layout(7).length, 7);
  assert.strictEqual(O.layout(40).length, 16);
  for (const p of O.layout(16)) { assert.ok(p.x > 0.5 && p.x < 9.5 && p.y > 0.5 && p.y < 9.5, JSON.stringify(p)); }
  assert.ok(!O.due(1000, 1010));
  assert.ok(O.due(1000, 1034));
  assert.ok(O.FRAME_MS >= 33);
});

test("every language has every string", () => {
  for (const l of Object.keys(O.T)) for (const k of Object.keys(O.T.en)) assert.ok(O.T[l][k], l + ": " + k);
});

function fakeClock() {
  let t = 1_000_000;
  return { now: () => t, setTimeout: (fn, ms) => { t += ms; Promise.resolve().then(fn); } };
}

test("ask posts the office request with the routine's key and returns the reply and its office payload", async () => {
  const st = { enabled: true, url: "https://example.com/hook", key: "crsr_placeholder123", topic: G.newTopic(), speak: true };
  let posted = null;
  const fetch = (u, o) => {
    if (o && o.method === "POST") { posted = { u, o }; return Promise.resolve({ ok: true, status: 200 }); }
    const id = JSON.parse(posted.o.body).id;
    const line = JSON.stringify({ event: "message", message: JSON.stringify({ id, text: "Hired!", office: { hired: { name: "Trimmer" } } }) });
    return Promise.resolve({ ok: true, text: () => Promise.resolve(line) });
  };
  const c = fakeClock();
  const r = await new Promise((res) => O.ask(G, st, "Hire", "en", [], "ctx", { action: "hire", name: "Trimmer" }, (err, rep, office) => res({ err, rep, office }), { fetch, now: c.now, setTimeout: c.setTimeout }));
  assert.strictEqual(r.err, null);
  assert.strictEqual(r.rep.text, "Hired!");
  assert.deepStrictEqual(r.office, { hired: { name: "Trimmer" } });
  assert.strictEqual(posted.u, "https://example.com/hook");
  assert.strictEqual(posted.o.headers.Authorization, "Bearer crsr_placeholder123");
  assert.strictEqual(JSON.parse(posted.o.body).office.name, "Trimmer");
});

test("ask without Grok Bot diretto set up says so, and makes no request", () => {
  let called = false, err = null;
  O.ask(G, { enabled: false }, "hi", "en", [], "", {}, (e) => { err = e; }, { fetch: () => { called = true; } });
  assert.strictEqual(err, "not_set");
  assert.ok(!called);
});

test("provider: grok by default, a short lowercase word otherwise; only a tag", () => {
  const d = O.parse({ bots: [{ name: "A" }, { name: "B", provider: "Claude" }, { name: "C", provider: "ChatGPT" },
    { name: "D", provider: "<b>mistral large 2 with many words</b>" }, { name: "E", provider: "   " }, { name: "F", provider: 42 }, { name: "G", assistant: "Office-G!" }] });
  assert.deepStrictEqual(d.bots.map((b) => b.provider).slice(0, 3), ["grok", "claude", "chatgpt"]);
  assert.match(d.bots[3].provider, /^[a-z0-9._-]{1,16}$/);
  assert.strictEqual(d.bots[4].provider, "grok");
  assert.strictEqual(d.bots[5].provider, "42");
  assert.strictEqual(d.bots[6].assistant, "office-g");
  assert.strictEqual(O.providerColor("claude"), "#ff9e64");
  assert.strictEqual(O.providerColor("unknown-ai"), "#a9b1d6");
  assert.strictEqual(O.toJson(d).bots[1].provider, "claude");
  assert.strictEqual(O.applyReply(d, { hired: { name: "H", provider: "chatgpt" } }).bots[7].provider, "chatgpt");
});

test("spend per provider: real numbers only, a percentage only with a budget", () => {
  const d = O.parse({ bots: [], providers_usage: [
    { provider: "claude", spent: 12.345, unit: "$", budget: 50, label: "Claude API" }, { provider: "chatgpt", spent: 3.5 },
    { provider: "x", spent: "lots" }, { provider: "y", spent: -1 }, { provider: "z" }, null, "junk"] });
  assert.strictEqual(d.spend.length, 2);
  assert.deepStrictEqual(d.spend[0], { provider: "claude", spent: 12.35, unit: "$", label: "Claude API", budget: 50, pct: 24.7, resets: "" });
  assert.strictEqual(d.spend[1].pct, null);
  assert.deepStrictEqual(O.parse({ bots: [] }).spend, []);
  assert.strictEqual(O.toJson(d).providers_usage[0].spent, 12.35);
});

test("hire names the provider from the user's words", () => {
  assert.deepStrictEqual(O.intent("hire a claude bot called Trimmer to trim my tokens"), { type: "hire", name: "Trimmer", role: "trim my tokens", provider: "claude" });
  assert.strictEqual(O.intent("assumi un bot chiamato Scout su ChatGPT per cercare offerte").provider, "chatgpt");
  assert.strictEqual(O.intent("hire an OpenAI bot named Pixel").provider, "chatgpt");
});

test("start screen chrome fits 1280x720 and 1920x1080, and the title stays on one line", () => {
  const fs = require("fs");
  const path = require("path");
  const src = fs.readFileSync(path.join(__dirname, "../app/office.js"), "utf8");
  assert.match(src, /\.office \.start \.wm\{[^}]*white-space:nowrap/);
  assert.match(src, /\.office \.start \.wm\{[^}]*max-width:50vw/);
  assert.match(src, /\.office \.in\{[^}]*min-width:0/);
  assert.match(src, /\.office \.start \.bar\{[^}]*width:50vw/);
  assert.match(src, /\.office \.bar\{[^}]*width:50vw/);
  const tvdpi = 213 / 160, xhdpi = 2;
  for (const screen of [1280, 1920]) {
    for (const d of [tvdpi, xhdpi, 1]) {
      const c = O.chrome(screen, d);
      assert.ok(c.column <= Math.round(0.90 * screen));
      assert.ok(c.column <= screen);
      const title = "GrokBot Office".length * 34 * d * 0.62;
      assert.ok(title <= c.column, "title one line at " + screen + "@" + d);
      const enter = "Entrer au bureau ›".length * 16 * d * 0.62 + 32 * d;
      const mic = 16 * d * 0.62 + 42 * d;
      const prov = "Grok Bot".length * 16 * d * 0.62 + 42 * d;
      const leftover = c.column - enter - mic - prov;
      assert.ok(leftover >= Math.min(200 * d, screen / 6), "pill leftover " + leftover + " at " + screen + "@" + d);
      assert.ok(c.hire <= screen - 80 * d);
      assert.ok("Abbrechen".length * 16 * d * 0.62 + "Einstellen".length * 16 * d * 0.62 + 80 * d <= c.hire);
    }
  }
  assert.strictEqual(O.chrome(1920, 1).column, 960);
  // CSS vw at both TV sizes: 50vw is at least 560 CSS px, title 2.4vw × 14 glyphs fits
  for (const w of [1280, 1920]) {
    const col = 0.50 * w;
    assert.ok(col >= 560);
    const titleW = "GrokBot Office".length * (0.024 * w) * 0.62 + "GrokBot Office".length * (0.06 * 0.024 * w);
    assert.ok(titleW <= col, "CSS title at " + w);
  }
  // long de/fr copy fits the vw boxes at 1280 and 1920 (wrapping allowed on hint/hire/keys)
  const T = O.T;
  for (const lang of ["en", "it", "de", "fr", "es"]) {
    const L = T[lang];
    for (const w of [1280, 1920]) {
      const hireW = 0.48 * w;   // CSS max-width
      const hireTitle = (L.hire_title || "").length * (0.015 * w) * 0.62;
      assert.ok(hireTitle <= hireW * 2, lang + " hire title at " + w);
      const keysW = 0.50 * w;
      const keys = (L.keys || "").length * (0.0125 * w * 0.75) * 0.62;
      assert.ok(keys <= keysW * 2, lang + " keys at " + w);
    }
  }
});

test("usage card sits above the ask bar at 720p and 1080p, and does not cover the reply", () => {
  const fs = require("fs");
  const path = require("path");
  const src = fs.readFileSync(path.join(__dirname, "../app/office.js"), "utf8");
  const us = src.match(/\.office \.us\{[^}]*bottom:(\d+)vh/);
  const bar = src.match(/\.office \.bar\{[^}]*bottom:(\d+)vh/);
  const rp = src.match(/\.office \.rp\{[^}]*bottom:(\d+)vh/);
  assert.ok(us && bar && rp, "us/bar/rp bottoms are in the CSS");
  const usB = Number(us[1]), barB = Number(bar[1]), rpB = Number(rp[1]);
  // bar is ~5–6vh tall (1.3vh padding × 2 + 1.25vw type); 14vh clears it on 16:9
  assert.ok(usB >= barB + 8, "CSS usage bottom " + usB + "vh is above the " + barB + "vh ask bar");
  assert.ok(rpB >= usB + 10, "CSS reply bottom " + rpB + "vh is above the usage card");
  const tvdpi = 213 / 160, xhdpi = 2;
  for (const screen of [1280, 1920]) {
    const screenH = Math.round(screen * 9 / 16);
    for (const d of [tvdpi, xhdpi, 1]) {
      const c = O.chrome(screen, d);
      const tag = screen + "x" + screenH + "@" + d;
      assert.ok(c.usageBottom >= c.barBottom + c.barHeight + c.gap, "usage above bar " + tag);
      const usageRight = Math.round(36 * d) + c.usage;
      const colLeft = Math.round((screen - c.column) / 2);
      const reserve = Math.round(100 * d);
      if (usageRight + c.gap > colLeft) {
        assert.ok(c.replyBottom >= c.usageBottom + reserve + c.gap, "stacked reply " + tag);
      } else {
        assert.strictEqual(c.replyBottom, c.usageBottom, "side by side " + tag);
      }
      assert.ok(c.replyBottom + reserve <= screenH, "reply on panel " + tag);
    }
  }
  assert.strictEqual(O.chrome(1920, 1).usageBottom, 104);
  assert.strictEqual(O.chrome(1920, 1).replyBottom, 104);
  assert.strictEqual(O.chrome(1280, 2).replyBottom, 432);   // 216dp at xhdpi
  // long de/fr/es/it strings still fit the 22vw usage box (wrapping is allowed)
  for (const lang of ["en", "it", "de", "fr", "es"]) {
    const label = O.T[lang].usage + " · " + O.T[lang].resets.replace("%s", "Mon 09:00");
    for (const w of [1280, 1920]) {
      const box = 0.22 * w;
      const line = label.length * (0.0125 * w * 0.85) * 0.62;
      assert.ok(line <= box * 3, lang + " usage label wraps in 3 lines at " + w);
    }
  }
});
