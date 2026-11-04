// node --test webos/tests/menu-ui.test.js — drive shared TV menus through the app's real key handlers.
// The fake DOM follows the existing tizen-apps.test.js UI harness.
const test = require("node:test");
const assert = require("assert");
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const APP = path.join(__dirname, "../app");
// Load optional source modules when testing an integration, without requiring them upstream.
const SRC = ["data.js", "theme.js", "homeprofile.js", "tui.js", "app.js"]
  .filter((f) => fs.existsSync(path.join(APP, f)))
  .map((f) => [f, fs.readFileSync(path.join(APP, f), "utf8")]);

function fakeElement() {
  const el = {
    style: { setProperty() {}, removeProperty() {} }, dataset: {}, children: [], textContent: "", value: "", className: "",
    classList: { contains: () => false, add() {}, remove() {}, toggle() {} },
    appendChild(c) { this.children.push(c); return c; },
    insertBefore(c) { this.children.push(c); return c; },
    removeChild() {}, remove() {}, attributes: {},
    setAttribute(k, v) { this.attributes[k] = String(v); },
    getAttribute(k) { return Object.prototype.hasOwnProperty.call(this.attributes, k) ? this.attributes[k] : null; },
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


test("compact root menu has icon rows and retains remote activation", () => {
  const t = boot();
  t.ctx.OmarchyTestAPI.openOmarchyMenu();
  assert.strictEqual(t.byId["modal-title"].textContent, "Omarchy");
  assert.strictEqual(t.byId["modal-sub"].style.display, "none");
  assert.strictEqual(t.byId["modal-box"].className, "box");
  const rows = t.byId["modal-list"].children;
  assert.strictEqual(rows.length, 14);   // 12 icon rows + About, plus Arcade from main
  rows.forEach((row, i) => {
    if (i < 13) assert.match(row.innerHTML, /mi-icon.*<svg.*<path.*mi-label/);
    else assert.match(row.innerHTML, /mi-label.*About/);
    assert.strictEqual(row.getAttribute("role"), "option");
    assert.strictEqual(row.getAttribute("aria-selected"), i === 0 ? "true" : "false");
  });
  t.press("ArrowDown");
  assert.strictEqual(t.byId["modal-list"].children[1].getAttribute("aria-selected"), "true");
  t.press("ArrowDown");   // row 1 is Arcade (main); Plugins sits under it
  assert.strictEqual(t.byId["modal-list"].children[2].getAttribute("aria-selected"), "true");
  t.press("Enter");
  assert.match(t.byId["modal-title"].textContent, /Plugins/);
  assert.strictEqual(t.byId["modal-box"].className, "box");
  t.press("Escape");
  assert.strictEqual(t.ctx.__OMARCHY_STATE.modalOpen, false);
});

test("settings share the compact frame and rich dialogs retain reading width", () => {
  const t = boot();
  t.ctx.OmarchyTestAPI.openAllSettingsModal();
  assert.strictEqual(t.byId["modal-box"].className, "box");
  assert.match(t.byId["modal-list"].children[0].innerHTML, /mi-label/);
  t.ctx.OmarchyTestAPI.openWalkerModal();
  assert.strictEqual(t.byId["modal-box"].className, "box wide");
  t.ctx.OmarchyTestAPI.openAboutModal();
  assert.strictEqual(t.byId["modal-box"].className, "box wide");
  t.ctx.OmarchyTestAPI.openOmarchyMenu();
  assert.strictEqual(t.byId["modal-box"].className, "box");
});

test("compact labels and remote hints retain Italian and German localization", () => {
  for (const [lang, setup, hint] of [["it", "Impostazioni", "seleziona"], ["de", "Einrichtung", "auswählen"]]) {
    const t = boot({ cfg: { lang } });
    t.ctx.OmarchyTestAPI.openOmarchyMenu();
    assert.ok(t.byId["modal-list"].children[8].innerHTML.includes(setup));
    assert.ok(t.byId["modal-hint"].textContent.includes(hint));
  }
});
