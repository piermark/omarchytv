// node --test webos/tests/tui-ui.test.js — boots the shared web app in a fake DOM and drives the Grok Bot ·
// Omarchy TUI window (harness copied from tizen-apps.test.js).
const test = require("node:test");
const assert = require("assert");
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const APP = path.join(__dirname, "../app");
const SRC = ["data.js", "tui.js", "app.js"].map((f) => [f, fs.readFileSync(path.join(APP, f), "utf8")]);

function fakeElement() {
  const el = {
    style: { setProperty() {}, removeProperty() {} }, dataset: {}, children: [], textContent: "", value: "", className: "",
    classList: { contains: () => false, add() {}, remove() {}, toggle() {} },
    appendChild(c) { this.children.push(c); return c; },
    insertBefore(c) { this.children.push(c); return c; },
    removeChild() {}, remove() {}, setAttribute() {}, getAttribute: () => null,
    addEventListener() {}, removeEventListener() {}, focus() {}, blur() {},
    // Same element per selector, so handlers wired after innerHTML (e.g. img.onerror) can be fired.
    querySelector(sel) { const q = this._q || (this._q = {}); return q[sel] || (q[sel] = fakeElement()); },
    querySelectorAll: () => [],
    getBoundingClientRect: () => ({ left: 0, top: 0, width: 0, height: 0 }),
    getContext: () => new Proxy({}, { get: () => () => {} }),
    play: () => Promise.resolve(), pause() {}, load() {}
  };
  let html = "";
  Object.defineProperty(el, "innerHTML", {
    get: () => html,
    set: (v) => { html = v; if (v === "") el.children = []; }
  });
  return el;
}

// Boots app.js. `tizen` is the fake window.tizen (omit for the plain webOS/browser path).
function boot({ tizen, apps, cfg, inputs, switchInput, webOS, palmServiceBridge } = {}) {
  const byId = {};
  const listeners = {};
  const store = {};
  if (cfg) store.omarchytv_cfg_2110 = JSON.stringify(cfg);
  const doc = {
    getElementById: (id) => byId[id] || (byId[id] = fakeElement()),
    createElement: () => fakeElement(),
    createElementNS: () => fakeElement(),
    querySelector: () => fakeElement(),
    querySelectorAll: () => [],
    addEventListener() {}, removeEventListener() {},
    body: fakeElement(), documentElement: fakeElement(), hidden: false, title: ""
  };
  const ctx = {
    document: doc,
    navigator: { language: "en-US", userAgent: "node" },
    localStorage: { getItem: (k) => (k in store ? store[k] : null), setItem: (k, v) => { store[k] = String(v); }, removeItem: (k) => { delete store[k]; } },
    location: { href: "file:///index.html", search: "", hash: "" },
    setTimeout: () => 1, clearTimeout() {}, setInterval: () => 0, clearInterval() {},   // truthy id: a short OK press launches on keyup
    requestAnimationFrame: () => 0, cancelAnimationFrame() {},
    fetch: () => new Promise(() => {}),
    addEventListener: (t, f) => { (listeners[t] = listeners[t] || []).push(f); },
    removeEventListener() {},
    innerWidth: 1920, innerHeight: 1080,
    console, Math, Date, JSON, Promise, Object, Array, String, Number, Error, RegExp, encodeURIComponent, parseInt, parseFloat, isNaN
  };
  ctx.window = ctx;
  if (webOS) ctx.webOS = webOS;
  if (palmServiceBridge) ctx.PalmServiceBridge = palmServiceBridge;
  if (tizen) { ctx.tizen = tizen; ctx.OMARCHY_PLATFORM = "tizen"; }
  if (apps) ctx.OMARCHY_TIZEN_APPS = apps;
  if (inputs) ctx.OMARCHY_TIZEN_INPUTS = inputs;
  if (switchInput) ctx.OMARCHY_TIZEN_SWITCH_INPUT = switchInput;
  vm.createContext(ctx);
  for (const [f, src] of SRC) vm.runInContext(src, ctx, { filename: f });
  return {
    ctx, store, byId,
    tiles: () => byId["dock-strip"].children,
    focusedName: () => byId["dock-app-name"].textContent,
    toast: () => (byId.toast ? byId.toast.textContent : ""),
    inputTiles: () => byId["ws2-grid"].children,
    inputTitle: () => byId["ws2-title"].textContent,
    // A short remote press: keydown then keyup, through the app's own key handlers.
    press: (key) => ["keydown", "keyup"].forEach((t) => (listeners[t] || []).forEach((f) => f({ type: t, key, keyCode: key === "Enter" ? 13 : 0, preventDefault() {} }))),
    grid: () => byId["ws3-grid"].children,
    emit: (detail) => (listeners["omarchy-tizen-apps"] || []).forEach((f) => f({ type: "omarchy-tizen-apps", detail })),
    emitInputs: (detail) => (listeners["omarchy-tizen-inputs"] || []).forEach((f) => f({ type: "omarchy-tizen-inputs", detail }))
  };
}


function runCmd(t, cmd) {
  const inp = t.byId["modal-input"];
  inp.value = cmd; inp.oninput();
  t.press("Enter");
}

test("the TUI opens with the Omarchy box and runs local commands", () => {
  const t = boot();
  t.ctx.OMARCHY_OPEN_TUI();
  assert.strictEqual(t.byId["modal-title"].textContent, "Grok Bot · Omarchy TUI");
  assert.match(t.byId["modal-sub"].innerHTML, /tui-win/);
  runCmd(t, "help");
  assert.match(t.byId["tui-log"].innerHTML, /theme &lt;name&gt;|theme <name>/);
  const before = JSON.parse(t.store.omarchytv_cfg_2110 || "{}").theme;
  runCmd(t, "next theme");
  const after = JSON.parse(t.store.omarchytv_cfg_2110).theme;
  assert.ok(after && after !== before, "theme switched");
  assert.match(t.byId["tui-legend"].textContent, new RegExp("grok bot · omarchy tui  \\[" + after));
  runCmd(t, "status");
  assert.match(t.byId["tui-log"].innerHTML, /PC bridge not paired/);
});

test("open <app> launches the matching dock app through luna", () => {
  const calls = [];
  function Bridge() {}
  Bridge.prototype.call = function (uri, p) { calls.push([uri, p]); };
  const t = boot({ palmServiceBridge: Bridge });
  t.ctx.OMARCHY_OPEN_TUI();
  runCmd(t, "open netflix");
  assert.ok(calls.some(([u, p]) => /applicationManager\/launch/.test(u) && /"netflix"/.test(p)), JSON.stringify(calls));
});

test("questions without a PC or Grok Bot diretto fall back to grok.com", () => {
  const calls = [];
  function Bridge() {}
  Bridge.prototype.call = function (uri, p) { calls.push([uri, p]); };
  const t = boot({ palmServiceBridge: Bridge });
  t.ctx.OMARCHY_OPEN_TUI();
  runCmd(t, "what should I watch tonight?");
  assert.ok(calls.some(([, p]) => /grok\.com\/\?q=what/.test(p)), JSON.stringify(calls));
});

test("questions go to the paired PC bridge with the ask code", () => {
  const seen = [];
  const t = boot({ cfg: { pcHost: "192.168.1.9", grokbotCode: "ABCD1234" } });
  t.ctx.fetch = (url, o) => { seen.push([url, o]); return new Promise(() => {}); };
  t.ctx.OMARCHY_OPEN_TUI();
  runCmd(t, "hello there");
  const ask = seen.find(([u]) => /grokbot\/ask$/.test(u));
  assert.strictEqual(ask[0], "http://192.168.1.9:8765/v1/grokbot/ask");
  assert.strictEqual(ask[1].headers["X-Omarchy-Token"], "ABCD1234");
  assert.match(t.byId["tui-log"].innerHTML, /asking/);
});

test("PC agents (Claude, Codex, Grok, Hermes…) are listed and asked through /v1/ai/ask", async () => {
  const seen = [];
  const t = boot({ cfg: { pcHost: "192.168.1.9", grokbotCode: "ABCD1234" } });
  t.ctx.fetch = (url, o) => {
    seen.push([url, o]);
    if (/providers$/.test(url)) return Promise.resolve({ ok: true, json: () => Promise.resolve({ providers: [
      { id: "claude-code", name: "Claude" }, { id: "codex", name: "ChatGPT (Codex)" }, { id: "grok-cli", name: "Grok" }, { id: "hermes-cli", name: "Hermes" }] }) });
    return Promise.resolve({ ok: true, json: () => Promise.resolve({ text: "ciao da hermes", provider: "hermes-cli", session: "s1" }) });
  };
  t.ctx.OMARCHY_OPEN_TUI();
  await new Promise((r) => setImmediate(r));
  const names = JSON.parse(t.store.omarchytv_cfg_2110).tuiProviders.map((p) => p.id);
  assert.deepStrictEqual(names, ["claude-code", "codex", "grok-cli", "hermes-cli"]);
  runCmd(t, "agent hermes");
  assert.strictEqual(JSON.parse(t.store.omarchytv_cfg_2110).tuiAgent, "pc:hermes-cli");
  runCmd(t, "consigliami un film");
  const ask = seen.find(([u]) => /\/v1\/ai\/ask$/.test(u));
  assert.ok(ask, "asked the PC agent");
  assert.strictEqual(JSON.parse(ask[1].body).provider, "hermes-cli");
  await new Promise((r) => setImmediate(r));
  assert.match(t.byId["tui-log"].innerHTML, /tui-bot/);
  runCmd(t, "agent codex");
  assert.strictEqual(JSON.parse(t.store.omarchytv_cfg_2110).tuiAgent, "pc:codex");
});

test("Grok Bot & AI hub and Web apps pages (Android parity)", () => {
  const t = boot();
  t.ctx.OMARCHY_OPEN_AI_HUB();
  assert.strictEqual(t.byId["modal-title"].textContent, "Grok Bot & AI");
  t.ctx.OMARCHY_OPEN_WEBAPPS();
  const inp = t.byId["modal-input"];
  inp.value = "Perplexity=https://www.perplexity.ai/"; inp.oninput();
  t.press("Enter");
  assert.deepStrictEqual(JSON.parse(t.store.omarchytv_cfg_2110).webapps, [{ name: "Perplexity", url: "https://www.perplexity.ai/" }]);
});
