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
