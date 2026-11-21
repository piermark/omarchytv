// v2.22 AI LIVE, the night shift and skills on LG / Samsung (ailive.js), read from the PC bridge
const test = require("node:test");
const assert = require("assert");
const path = require("path");
const AL = require(path.join(__dirname, "../app/ailive.js"));

function fakeDoc() {
  const byId = {};
  function el(tag) {
    const e = { tag, children: [], style: {}, attrs: {}, parentNode: null, _text: "", offsetWidth: 100,
      setAttribute(k, v) { this.attrs[k] = String(v); if (k === "id") byId[v] = this; },
      getAttribute(k) { return this.attrs[k]; },
      appendChild(c) { c.parentNode = this; this.children.push(c); return c; },
      removeChild(c) { this.children.splice(this.children.indexOf(c), 1); c.parentNode = null; } };
    Object.defineProperty(e, "textContent", { get() { return this._text + this.children.map((c) => c.textContent).join(""); },
      set(v) { this._text = String(v); this.children = []; } });
    Object.defineProperty(e, "id", { get() { return this.attrs.id; }, set(v) { this.setAttribute("id", v); } });
    return e;
  }
  return { body: el("body"), createElement: el, getElementById: (id) => byId[id] || null };
}

const NOW = Date.now();
const VIEW = {
  live: { agents: [
    { agent: "Claude · omarchytv", text: "Bash · npm test", progress: 40, at: NOW, since: NOW - 60000 },
    { agent: "Hermes", text: "git push", progress: -1, at: NOW, since: NOW, waiting: { id: "a1", question: "push <b>tv-2.22</b>?" },
      preview: { kind: "diff", text: "+ ok\n- bad" } }], events: [{ seq: 1, at: NOW, agent: "Hermes", kind: "ask", text: "push?" }] },
  shift: { state: "ended", wake: NOW - 1000, until: NOW - 2000, counts: { ok: 1, fail: 1, info: 0, waiting: 1 }, tasks: [{ text: "fix tests" }],
    log: [{ at: NOW - 9000, agent: "Hermes", level: "ok", text: "green" }, { at: NOW - 8000, agent: "Codex", level: "fail", text: "lint" }],
    queue: [{ id: "q1", at: NOW - 7000, agent: "Hermes", title: "push", state: "pending", risk: "push" }] },
  skillAsk: null, skills: [{ id: "homelab", name: "Homelab", author: "OmarchyTV", version: "1.0", description: "d", permissions: ["widgets"] }], installed: []
};

function make(view, over) {
  const doc = fakeDoc(), posts = [];
  let wake = 0;
  const fetch = (u, o) => {
    if (o && o.method === "POST") posts.push([u.replace(/^.*\/v1/, "/v1"), JSON.parse(o.body)]);
    return Promise.resolve({ ok: true, json: () => Promise.resolve(/tvlive/.test(u) ? view : { ok: true }) });
  };
  const g = AL.create(Object.assign({ doc, fetch, base: () => "http://pc:8765", token: () => "T", idle: () => true,
    wakeShown: () => wake, markWake: (w) => { wake = w; } }, over));
  return { g, doc, posts, wake: () => wake };
}

test("the director cuts to whoever waits for a yes, and the tape keeps time order", () => {
  const a = VIEW.live.agents;
  assert.strictEqual(AL.director(a, "Claude · omarchytv", NOW, NOW, 0).agent, "Hermes");
  assert.strictEqual(AL.director(a, "Claude · omarchytv", NOW, NOW, NOW + 30000).agent, "Claude · omarchytv");   // by hand
  const tape = AL.tapeOf(VIEW.shift);
  assert.deepStrictEqual(tape.map((x) => x.kind), ["ok", "fail", "queued"]);
  assert.strictEqual(AL.initials("Claude · omarchytv"), "CO");
  assert.ok(/^[A-Z]{3} \d\d \d{4}  \d\d:\d\d$/.test(AL.vcrDate(NOW)));
});

test("the morning page opens once by itself, and only the TV answers the queue", async () => {
  const b = make(VIEW);
  await b.g.tick();
  assert.strictEqual(b.g.mode(), "shift");
  assert.strictEqual(b.wake(), VIEW.shift.wake);
  b.g.close();
  await b.g.tick();
  assert.strictEqual(b.g.isOpen(), false);   // not twice
  b.g.open("shift");
  b.g.key(40);   // ▼ to the pending decision (the first row is the replay)
  b.g.key(13);
  assert.ok(b.g._state().dialog);
  b.g.key(39); // Approve is not ready for 1.5 s: the focus stays on Deny
  b.g.key(13);   // OK on Deny
  await new Promise((r) => setTimeout(r, 5));
  assert.deepStrictEqual(b.posts[0], ["/v1/shift/answer", { id: "q1", yes: false }]);
});

test("LIVE shows BREAKING as text and REPLAY plays the tape like a VCR", async () => {
  const b = make(VIEW, { wakeShown: () => VIEW.shift.wake });
  await b.g.tick();
  b.g.open("live");
  const layer = b.doc.getElementById("otv-ailive");
  assert.ok(layer.textContent.indexOf("BREAKING") >= 0);
  assert.ok(layer.textContent.indexOf("<b>tv-2.22</b>") >= 0);   // text, never HTML
  b.g.key(461);
  assert.strictEqual(b.g.isOpen(), false);
  b.g.open("replay");
  assert.ok(b.doc.getElementById("otv-ailive").textContent.indexOf("PLAY ▶") >= 0);
  b.g.key(39);   // the next highlight: the failure
  assert.strictEqual(b.g._state().cursor, 1);
  b.g.key(38);
  assert.strictEqual(b.g._state().speed, 2);
  b.g.key(13);
  assert.strictEqual(b.g._state().paused, true);
  b.g.close();
});

test("an agent's skill question opens the install question; Cancel has the focus", async () => {
  const v = Object.assign({}, VIEW, { skillAsk: VIEW.skills[0] });
  const b = make(v, { wakeShown: () => VIEW.shift.wake });
  await b.g.tick();
  assert.strictEqual(b.g.mode(), "skills");
  assert.ok(b.g._state().dialog);
  b.g.key(13);   // OK right away = Cancel
  await new Promise((r) => setTimeout(r, 5));
  assert.strictEqual(b.posts.length, 0);
  b.g.close();
});

test("the studio: the briefing goes on air once and is read aloud; only a held OK picks the debate's winner", async () => {
  const said = [];
  const view = Object.assign({}, VIEW, { shift: { state: "none" },
    briefing: { title: "Good morning", by: "Hermes", at: NOW, say: "Good morning Marco.", sections: [
      { title: "Overnight", lines: ["352 tests green", "1 decision waits"] }, { title: "Today", lines: ["standup 9:30"] }] },
    debate: null });
  const b = make(view, { speak: (t) => said.push(t) });
  await b.g.tick();
  assert.strictEqual(b.g.mode(), "briefing");
  assert.deepStrictEqual(said, ["Good morning Marco."]);
  assert.deepStrictEqual(b.posts[0], ["/v1/tvstudio/briefing-shown", {}]);
  assert.ok(/OVERNIGHT/.test(b.doc.body.textContent));
  b.g.key(39);
  assert.ok(/▶ Today/.test(b.doc.body.textContent));
  b.g.key(461);
  await b.g.tick();
  assert.strictEqual(b.g.isOpen(), false);   // once
  view.debate = { id: "d1", topic: "Cache the widgets how?", state: "open", entries: [{ agent: "Claude", text: "LRU of 32" }, { agent: "Grok", text: "disk" }] };
  await b.g.tick();
  assert.strictEqual(b.g.mode(), "debate");
  assert.ok(/YOU DECIDE/.test(b.doc.body.textContent));
  b.g.key(39);
  b.g.key(13);   // a reflex OK
  assert.strictEqual(b.posts.filter((p) => p[0] === "/v1/tvstudio/debate").length, 0);
  await new Promise((r) => setTimeout(r, 1600));
  b.g.key(13);
  assert.deepStrictEqual(b.posts.filter((p) => p[0] === "/v1/tvstudio/debate")[0], ["/v1/tvstudio/debate", { id: "d1", index: 1 }]);
  b.g.close();
});
