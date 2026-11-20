// v2.22 #157-#159 widget pages on LG / Samsung, read from the PC bridge (homegrid.js)
const test = require("node:test");
const assert = require("assert");
const path = require("path");
const HG = require(path.join(__dirname, "../app/homegrid.js"));

function fakeDoc() {
  const byId = {};
  let n = 0;
  function el(tag) {
    const e = { tag, children: [], style: {}, attrs: {}, parentNode: null, _text: "", i: n++,
      setAttribute(k, v) { this.attrs[k] = String(v); if (k === "id") byId[v] = this; },
      appendChild(c) { c.parentNode = this; this.children.push(c); return c; },
      removeChild(c) { this.children.splice(this.children.indexOf(c), 1); c.parentNode = null; },
      getBoundingClientRect() { return { left: this.i * 10, top: 0, width: 5, height: 5 }; },
      getContext() { return null; } };
    Object.defineProperty(e, "textContent", {
      get() { return this._text + this.children.map((c) => c.textContent).join(""); },
      set(v) { this._text = String(v); this.children = []; } });
    Object.defineProperty(e, "id", { get() { return this.attrs.id; }, set(v) { this.setAttribute("id", v); } });
    return e;
  }
  const body = el("body");
  return { body, createElement: el, createElementNS: (_ns, t) => el(t), getElementById: (id) => byId[id] || null };
}

const VIEW = {
  grid: { cols: 12, rows: 6 },
  widgets: [
    { id: "cpu", type: "stat", page: 1, x: 0, y: 0, w: 2, h: 1, title: "<b>CPU</b>", data: { value: "42", unit: "%" } },
    { id: "scenes", type: "homeassistant.scenes", page: 2, x: 0, y: 0, w: 4, h: 3, title: "Casa",
      data: { items: [{ entity: "scene.movie", label: "Movie" }, { entity: "scene.off", label: "Off", confirm: true }], columns: 3 } },
    { id: "news", type: "shortcuts", page: 2, x: 4, y: 0, w: 3, h: 2, title: "",
      data: { items: [{ id: "x", label: "X", action: { type: "view", uri: "https://x.com" } },
        { id: "nf", label: "Netflix", action: { type: "app", package: "com.netflix.ninja" } }] } }
  ],
  pages: [{ index: 1, name: "Page 1", rotate: false }, { index: 2, name: "Casa", rotate: true }],
  visible: [1, 2], show: { index: 0, at: 0 }
};

function make(over) {
  const doc = fakeDoc(), calls = [];
  const fetch = (u, o) => {
    calls.push([u, o]);
    if (/\/api\/states$/.test(u)) return Promise.resolve({ ok: true, json: () => Promise.resolve([{ entity_id: "scene.movie", state: "x" }]) });
    if (/\/api\/services\//.test(u)) return Promise.resolve({ ok: true, json: () => Promise.resolve([]) });
    if (/\/v1\/home$/.test(u)) return Promise.resolve({ ok: true, json: () => Promise.resolve(over.view || VIEW) });
    return Promise.resolve({ ok: true, json: () => Promise.resolve({}) });
  };
  const opened = [];
  const g = HG.create(Object.assign({ doc, fetch, base: () => "http://192.168.1.10:8765", token: () => "ABCD1234",
    ha: () => ({ url: "http://192.168.1.5:8123", token: "tok" }), idle: () => true, openUrl: (u) => opened.push(u) }, over));
  return { g, doc, calls, opened };
}

test("navigator windows and CH+ / CH- order, as on Android", () => {
  assert.deepStrictEqual(HG.windowOf(1, 3, 5), [1, 3, 0, 0]);
  assert.deepStrictEqual(HG.windowOf(7, 42, 5), [6, 10, 1, 1]);
  assert.deepStrictEqual(HG.windowOf(42, 42, 5), [41, 42, 1, 0]);
  assert.strictEqual(HG.stepPage([1, 2, 5], 0, 1), 1);
  assert.strictEqual(HG.stepPage([1, 2, 5], 5, 1), 0);
  assert.strictEqual(HG.stepPage([1, 2, 5], 0, -1), 5);
  assert.deepStrictEqual(HG.haService("scene.movie"), ["scene", "turn_on"]);
  assert.deepStrictEqual(HG.haService("light.sofa"), ["light", "toggle"]);
  assert.strictEqual(HG.haService("sensor.temp"), null);
  assert.ok(!HG.webUrl("http://8.8.8.8/x.png") && HG.webUrl("https://a.example/x.png") && HG.webUrl("http://192.168.1.2/x"));
});

test("CH+ opens page 1, text stays text, BACK returns to the Home", async () => {
  const { g, doc, calls } = make({});
  await g.tick();
  assert.strictEqual(g.isOpen(), false);
  assert.ok(g.key(427));
  assert.strictEqual(g.page(), 1);
  const layer = doc.getElementById("otv-wgrid");
  assert.ok(layer.textContent.indexOf("<b>CPU</b>") >= 0);   // as text, never parsed as HTML
  assert.ok(calls.some(([u, o]) => /\/v1\/home\/shown$/.test(u) && JSON.parse(o.body).index === 1));
  assert.ok(g.key(427));
  assert.strictEqual(g.page(), 2);
  assert.ok(g.key(427));
  assert.strictEqual(g.isOpen(), false);   // past the last page: the Home
  assert.ok(g.key(428));
  assert.strictEqual(g.page(), 2);
  assert.ok(g.key(461));
  assert.strictEqual(g.isOpen(), false);
});

test("scenes run on Home Assistant from the TV, the confirm one needs OK twice", async () => {
  const { g, calls, opened } = make({});
  await g.tick();
  g.goTo(2, "test");
  await new Promise((r) => setTimeout(r, 10));
  assert.strictEqual(g.focused(), "scenes:scene.movie");
  g.key(13);
  await new Promise((r) => setTimeout(r, 10));
  const run = calls.filter(([u]) => /\/api\/services\/scene\/turn_on$/.test(u));
  assert.strictEqual(run.length, 1);
  assert.strictEqual(run[0][1].headers.Authorization, "Bearer tok");
  assert.deepStrictEqual(JSON.parse(run[0][1].body), { entity_id: "scene.movie" });
  g.key(39);   // → the next button to the right
  assert.strictEqual(g.focused(), "scenes:scene.off");
  g.key(13);
  await new Promise((r) => setTimeout(r, 10));
  assert.strictEqual(calls.filter(([u]) => /services/.test(u)).length, 1);   // armed, not run
  g.key(13);
  await new Promise((r) => setTimeout(r, 10));
  assert.strictEqual(calls.filter(([u]) => /services/.test(u)).length, 2);
  g.key(39);
  assert.strictEqual(g.focused(), "news:x");   // the Android app shortcut is shown, not focusable
  g.key(13);
  assert.deepStrictEqual(opened, ["https://x.com"]);
});

test("an agent's show brings its page up, an old one does not", async () => {
  const v1 = Object.assign({}, VIEW, { show: { index: 2, at: 1000 } });
  const box = make({ view: v1 });
  await box.g.tick();
  assert.strictEqual(box.g.isOpen(), false);   // from before the app started
  box.g.apply(Object.assign({}, VIEW, { show: { index: 2, at: 2000 } }));
  assert.strictEqual(box.g.page(), 2);
  box.g.apply(Object.assign({}, VIEW, { widgets: VIEW.widgets.slice(0, 1), visible: [1], show: { index: 2, at: 2000 } }));
  assert.strictEqual(box.g.isOpen(), false);   // its page went away
});

test("nothing happens on CH+ without widgets or away from the Home", async () => {
  const empty = make({ view: Object.assign({}, VIEW, { widgets: [], visible: [1] }) });
  await empty.g.tick();
  assert.strictEqual(empty.g.key(427), false);
  const busy = make({ idle: () => false });
  await busy.g.tick();
  assert.strictEqual(busy.g.key(427), false);
});
