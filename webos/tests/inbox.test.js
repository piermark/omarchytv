// v2.21 agents on LG / Samsung: the inbox the app polls from the PC bridge
const test = require("node:test");
const assert = require("assert");
const path = require("path");
const IB = require(path.join(__dirname, "../app/inbox.js"));

function fakeDoc() {
  const byId = {};
  function el(tag) {
    const e = { tag, children: [], style: {}, attrs: {}, parentNode: null, innerHTML: "",
      setAttribute(k, v) { this.attrs[k] = v; if (k === "id") byId[v] = this; },
      appendChild(c) { c.parentNode = this; this.children.push(c); return c; },
      removeChild(c) { this.children.splice(this.children.indexOf(c), 1); c.parentNode = null; if (c.id) delete byId[c.id]; },
      get firstChild() { return this.children[0]; },
      querySelectorAll(sel) { const n = (this.innerHTML.match(/class='otv-choice'/g) || []).length; return Array.from({ length: n }, () => ({ style: {} })); } };
    Object.defineProperty(e, "id", { get() { return this.attrs.id; }, set(v) { this.setAttribute("id", v); } });
    return e;
  }
  const body = el("body");
  return { body, createElement: el, getElementById: (id) => byId[id] || null };
}

function resp(body) { return { ok: true, json: () => Promise.resolve(body) }; }

test("only LAN and tailnet pages, and HTML is escaped", () => {
  assert.ok(IB.lanUrl("http://192.168.1.20:8080/x"));
  assert.ok(IB.lanUrl("http://100.101.2.3/"));
  assert.ok(!IB.lanUrl("https://192.168.1.20/"));
  assert.ok(!IB.lanUrl("http://8.8.8.8/"));
  assert.ok(!IB.lanUrl("http://127.0.0.1/"));
  assert.ok(!IB.lanUrl("http://evil.example/"));
  assert.strictEqual(IB.esc("<b>'x'</b>"), "&lt;b&gt;&#39;x&#39;&lt;/b&gt;");
});

test("notes, a page, then a question answered with the remote", async () => {
  const doc = fakeDoc(), posts = [];
  const box = { seq: 3, notes: [{ seq: 1, from: "CI", text: "<script>x</script>", level: "info" }], page: null,
    asks: [{ id: "0123456789abcdef", from: "Claude", question: "Merge?", choices: ["Merge", "Wait"], state: "pending" }] };
  const fetch = (u, o) => {
    if (o && o.method === "POST") { posts.push([u, JSON.parse(o.body), o.headers["X-Omarchy-Token"]]); return Promise.resolve({ ok: true }); }
    assert.ok(u.endsWith("/v1/inbox?since=0"));
    assert.strictEqual(o.headers["X-Omarchy-Token"], "ABCD1234");
    return Promise.resolve(resp(box));
  };
  const ib = IB.create({ doc, fetch, base: () => "http://192.168.1.10:8765", token: () => "ABCD1234" });
  await ib.tick();
  const notes = doc.getElementById("otv-notes");
  assert.strictEqual(notes.children.length, 1);
  assert.ok(!notes.children[0].innerHTML.includes("<script>"), "escaped");
  assert.strictEqual(ib.kind(), "ask");
  assert.ok(ib.key(39));   // → Wait
  assert.ok(ib.key(13));   // OK answers
  await new Promise((r) => setImmediate(r));
  assert.deepStrictEqual(posts[0].slice(0, 2), ["http://192.168.1.10:8765/v1/ask/0123456789abcdef", { answer: "Wait" }]);
  assert.strictEqual(ib.isOpen(), false);
  ib.apply(box);   // the same question again: already answered here, not shown twice
  assert.strictEqual(ib.isOpen(), false);
});

test("a page opens, BACK closes it, the PC can close it too", () => {
  const doc = fakeDoc();
  const ib = IB.create({ doc, fetch: null, base: () => "", token: () => "" });
  ib.apply({ seq: 5, notes: [], page: { url: "http://192.168.1.20:8080/", seq: 5 }, asks: [] });
  assert.strictEqual(ib.kind(), "page");
  assert.strictEqual(ib.key(38), false, "arrows go to the page");
  assert.ok(ib.key(461), "BACK (webOS) closes it");
  assert.strictEqual(ib.isOpen(), false);
  ib.apply({ seq: 6, notes: [], page: { url: "http://192.168.1.20:8080/", seq: 6 }, asks: [] });
  assert.strictEqual(ib.kind(), "page");
  ib.apply({ seq: 7, notes: [], page: null, asks: [] });
  assert.strictEqual(ib.isOpen(), false, "closed from the PC");
  ib.apply({ seq: 8, notes: [], page: { url: "https://internet.example/", seq: 8 }, asks: [] });
  assert.strictEqual(ib.isOpen(), false, "never an internet page");
});

test("a question answered elsewhere (or expired) leaves the screen", () => {
  const doc = fakeDoc();
  const ib = IB.create({ doc, fetch: null, base: () => "", token: () => "" });
  const q = { id: "a1b2c3d4e5f60718", from: "PC", question: "Merge?", choices: ["Merge", "Wait"] };
  ib.apply({ seq: 1, notes: [], page: null, asks: [q] });
  assert.strictEqual(ib.kind(), "ask");
  ib.apply({ seq: 2, notes: [], page: null, asks: [q] });
  assert.strictEqual(ib.kind(), "ask", "still pending: stays");
  ib.apply({ seq: 3, notes: [], page: null, asks: [] });
  assert.strictEqual(ib.isOpen(), false, "no longer pending: closed");
});

test("no PC or no ask code: no request", async () => {
  let called = false;
  const ib = IB.create({ doc: fakeDoc(), fetch: () => { called = true; }, base: () => "", token: () => "X" });
  assert.strictEqual(await ib.tick(), null);
  assert.strictEqual(called, false);
});
