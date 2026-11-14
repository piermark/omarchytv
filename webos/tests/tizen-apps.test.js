// node --test webos/tests/tizen-apps.test.js — boots the shared web app in a fake DOM and checks the
// Samsung Tizen app list contract (window.OMARCHY_TIZEN_APPS + the "omarchy-tizen-apps" event).
const test = require("node:test");
const assert = require("assert");
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const APP = path.join(__dirname, "../app");
const SRC = ["data.js", "app.js"].map((f) => [f, fs.readFileSync(path.join(APP, f), "utf8")]);

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
    ctx, store,
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

function fakeTizen(onLaunch) {
  const calls = [];
  return {
    calls,
    application: { launch(id, ok, err) { calls.push(id); if (onLaunch) onLaunch(id, ok, err); } },
    tvinputdevice: { registerKey() {} }
  };
}

const SAMSUNG = [
  { id: "netflix", name: "Netflix", appId: "3201907018807", tizenId: "3201907018807", brand: "netflix" },
  { id: "youtube", name: "YouTube", appId: "111299001912", tizenId: "111299001912", brand: "youtube" },
  { id: "hbomax", name: "HBO Max", appId: "3202301029760", tizenId: "3202301029760" }
];

test("webOS keeps the static LG app list", () => {
  const t = boot();
  assert.strictEqual(t.tiles().length, 6);
  assert.strictEqual(t.focusedName(), "TV");
  assert.doesNotMatch(t.tiles().map((d) => d.className + d.innerHTML).join(), /tile-icon|tile-fallback|icon-failed/, "no Tizen artwork markup");
});

test("Tizen shows the adapter's installed apps instead of the LG defaults, keeping brand tiles", () => {
  const t = boot({ tizen: fakeTizen(), apps: SAMSUNG });
  assert.strictEqual(t.tiles().length, 3);
  assert.strictEqual(t.focusedName(), "Netflix");
  assert.match(t.tiles()[0].innerHTML, /#e50914/, "Netflix keeps its Omarchy brand tile");
  assert.match(t.tiles()[2].innerHTML, /HBO Max/, "an app without a known brand still gets a tile");
});

test("Tizen apps arriving by event replace the list, keep saved order/hidden apps and focus by id", () => {
  const t = boot({ tizen: fakeTizen(), cfg: { appOrder: ["youtube", "netflix"], hiddenApps: ["hbomax"] } });
  t.emit(SAMSUNG);
  assert.deepStrictEqual(t.tiles().map((d) => d.innerHTML.match(/>([^<>]+)<\/div><\/div>$/)[1]), ["YouTube", "NETFLIX"]);
  t.ctx.__OMARCHY_STATE.dockIdx = 1;   // focus Netflix
  t.ctx.OmarchyTestAPI.switchWorkspace(0);
  assert.strictEqual(t.focusedName(), "Netflix");
  // A newly installed app is added before the focused one in default order: focus stays on Netflix.
  t.emit([{ id: "disney", name: "Disney+", tizenId: "3201901017640", brand: "disney" }].concat(SAMSUNG));
  assert.strictEqual(t.focusedName(), "Netflix");
  assert.strictEqual(t.tiles().length, 3, "youtube, netflix, disney (hbomax stays hidden)");
  const saved = JSON.parse(t.store.omarchytv_cfg_2110);
  assert.deepStrictEqual(saved.appOrder, ["youtube", "netflix"]);
  assert.deepStrictEqual(saved.hiddenApps, ["hbomax"]);
});

test("empty or malformed Tizen app lists never wipe the dock", () => {
  const t = boot({ tizen: fakeTizen(), apps: SAMSUNG });
  t.emit([]);
  t.emit(null);
  t.emit([{ name: "no id" }, { id: "x", name: "no tizen id" }]);
  assert.strictEqual(t.tiles().length, 3);
  assert.strictEqual(t.focusedName(), "Netflix");
});

const DISNEY = { id: "disney", name: "Disney+", tizenId: "3201901017640", brand: "disney" };
const focused = (tiles) => tiles.filter((d) => / focused/.test(d.className));

test("uninstalling the focused All Apps tile moves focus to the last remaining app, Enter launches it", () => {
  const tz = fakeTizen();
  const cfg = { appOrder: ["youtube", "netflix", "hbomax"], hiddenApps: ["disney"] };
  const t = boot({ tizen: tz, apps: SAMSUNG.concat(DISNEY), cfg });
  t.ctx.OmarchyTestAPI.switchWorkspace(2);
  t.press("ArrowRight"); t.press("ArrowRight");
  assert.match(focused(t.grid())[0].innerHTML, /HBO Max/);
  t.emit([SAMSUNG[0], SAMSUNG[1], DISNEY]);   // HBO Max uninstalled
  assert.strictEqual(t.grid().length, 2, "youtube, netflix: disney stays hidden");
  assert.strictEqual(focused(t.grid()).length, 1, "exactly one focused tile");
  assert.match(focused(t.grid())[0].innerHTML, /NETFLIX/);
  assert.strictEqual(t.ctx.document.getElementById("ws3-title").textContent, "Netflix");
  t.press("Enter");
  assert.deepStrictEqual(tz.calls, ["3201907018807"]);
  t.press("ArrowLeft"); t.press("Enter");
  assert.deepStrictEqual(tz.calls, ["3201907018807", "111299001912"], "navigation still works after the shrink");
  const saved = JSON.parse(t.store.omarchytv_cfg_2110);
  for (const k of ["saverMode", "saverArt", "living", "pulseDefault215", "ssBefore215", "pulseNotice", "fractalWp220", "wallpaper", "randomWallpaper"]) delete saved[k];   // one-time Pulse + 2.20 fractals default
  assert.deepStrictEqual(saved, cfg, "saved order and hidden apps are untouched");
});

test("a catalogue shrink keeps a surviving focused app by id on both the dock and All Apps", () => {
  const tz = fakeTizen();
  const t = boot({ tizen: tz, apps: SAMSUNG });
  t.press("ArrowRight"); t.press("ArrowRight");   // dock: HBO Max
  t.ctx.OmarchyTestAPI.switchWorkspace(2);
  t.press("ArrowRight");   // All Apps: YouTube
  t.emit([SAMSUNG[2], SAMSUNG[1]]);   // Netflix uninstalled, the rest reordered
  assert.match(focused(t.grid())[0].innerHTML, />YouTube</);
  t.press("Enter");
  assert.deepStrictEqual(tz.calls, ["111299001912"]);
  t.ctx.OmarchyTestAPI.switchWorkspace(0);
  assert.strictEqual(t.focusedName(), "HBO Max");
  t.emit([SAMSUNG[1]]);   // HBO Max gone too: dock clamps to what is left
  assert.strictEqual(focused(t.tiles()).length, 1);
  assert.strictEqual(t.focusedName(), "YouTube");
});

test("a native Tizen launch failure is reported, not shown as a launch", () => {
  const tz = fakeTizen((id, ok, err) => err({ name: "NotFoundError", message: "app not installed" }));
  const t = boot({ tizen: tz, apps: SAMSUNG });
  t.tiles()[0].onclick();
  assert.deepStrictEqual(tz.calls, ["3201907018807"]);
  assert.match(t.toast(), /Netflix/);
  assert.doesNotMatch(t.toast(), /^Launching/);
  assert.match(t.toast(), /app not installed/);
});

test("a synchronous Tizen launch exception is reported too", () => {
  const t = boot({ tizen: fakeTizen(() => { throw new Error("SecurityError: privilege"); }), apps: SAMSUNG });
  t.tiles()[1].onclick();
  assert.doesNotMatch(t.toast(), /^Launching/);
  assert.match(t.toast(), /YouTube/);
});

test("a Tizen app with no Samsung id says it is unavailable instead of pretending to launch", () => {
  const tz = fakeTizen();
  const t = boot({ tizen: tz });   // adapter has not supplied apps: the static list is still shown
  assert.ok(t.tiles().length > 0, "the dock is never empty before discovery");
  t.ctx.OmarchyTestAPI.switchWorkspace(0);
  const plex = t.tiles().find((d) => /plex/.test(d.innerHTML));
  plex.onclick();
  assert.deepStrictEqual(tz.calls, []);
  assert.doesNotMatch(t.toast(), /^Launching/);
  assert.match(t.toast(), /Plex/);
});

// ---- Installed app artwork (adapter iconPath from tizen.application.getAppsInfo) ----

const ICONS = [
  { id: "netflix", name: "Netflix", tizenId: "3201907018807", brand: "netflix", iconPath: "/opt/usr/globalapps/3201907018807/shared/res/icon.png" },
  { id: "youtube", name: "YouTube", tizenId: "111299001912", brand: "youtube", iconPath: "file:///opt/usr/globalapps/111299001912/shared/res/yt.png" },
  { id: "evil", name: "Evil", tizenId: "1234567890", iconPath: 'x.png" onerror="alert(1)' },
  { id: "remote", name: "Remote", tizenId: "1234567891", iconPath: "http://example.com/icon.png" },
  { id: "js", name: "Js", tizenId: "1234567892", iconPath: "javascript:alert(1)" }
];

test("Tizen tiles show the installed app's native icon from iconPath", () => {
  const t = boot({ tizen: fakeTizen(), apps: ICONS });
  const [netflix, youtube] = t.tiles().map((d) => d.innerHTML);
  assert.match(netflix, /<img class="tile-icon" src="\/opt\/usr\/globalapps\/3201907018807\/shared\/res\/icon\.png"/);
  assert.match(youtube, /src="file:\/\/\/opt\/usr\/globalapps\/111299001912\/shared\/res\/yt\.png"/);
  assert.doesNotMatch(netflix, />NETFLIX</, "no typographic recreation when real artwork exists");
  assert.match(netflix, /Netflix/, "the app name is still in the tile for the broken-icon fallback");
});

test("unsafe or remote iconPaths are dropped, never injected into the tile", () => {
  const t = boot({ tizen: fakeTizen(), apps: ICONS });
  const html = t.tiles().map((d) => d.innerHTML).join("\n");
  assert.doesNotMatch(html, /onerror=|alert|example\.com|javascript:/);
  assert.strictEqual(t.tiles().length, 5, "apps with a bad icon still get a tile");
});

test("native artwork stays on for Tizen even with the text-tiles setting, webOS still honours it", () => {
  const tz = boot({ tizen: fakeTizen(), apps: ICONS, cfg: { textTiles: true } });
  assert.match(tz.tiles()[0].innerHTML, /<img class="tile-icon"/);
  const web = boot({ cfg: { textTiles: true } });
  assert.match(web.tiles()[1].innerHTML, /tile-text-only/);
  assert.doesNotMatch(web.tiles()[1].innerHTML, /<img/);
});

test("a broken Tizen icon falls back to a generic app icon plus the name, and stays that way", () => {
  const t = boot({ tizen: fakeTizen(), apps: ICONS });
  const tile = t.tiles()[0];
  tile.querySelector("img.tile-icon").onerror();
  assert.match(tile.className, /icon-failed/);
  assert.match(tile.innerHTML, /tile-fallback[\s\S]*<svg[\s\S]*Netflix/, "generic icon + name are in the tile");
  t.ctx.OmarchyTestAPI.switchWorkspace(0);   // re-render: the known-broken icon is not retried
  assert.doesNotMatch(t.tiles()[0].innerHTML, /<img/);
  assert.match(t.tiles()[0].className, /icon-failed/);
  assert.match(t.tiles()[1].innerHTML, /<img class="tile-icon"/, "other icons are unaffected");
});

// Adapter contract: iconPaths = [verified store cache if known, the app's native icon if different], iconPath = iconPaths[0].
const CACHE = "/opt/share/webappservice/apps_icon/all/3201907018807/304x304.png";
const NATIVE = "/opt/usr/globalapps/3201907018807/shared/res/icon.png";
const CANDIDATES = [
  { id: "netflix", name: "Netflix", tizenId: "3201907018807", brand: "netflix", iconPath: CACHE, iconPaths: [CACHE, NATIVE] },
  { id: "youtube", name: "YouTube", tizenId: "111299001912", brand: "youtube", iconPath: "/opt/usr/globalapps/111299001912/icon.png" }
];

test("a missing store-cache icon falls through to the native icon, and redraws skip the bad cache", () => {
  const t = boot({ tizen: fakeTizen(), apps: CANDIDATES });
  const tile = t.tiles()[0];
  assert.match(tile.innerHTML, /<img class="tile-icon" src="\/opt\/share\/webappservice\//, "store cache tried first");
  const img = tile.querySelector("img.tile-icon");
  img.onerror();
  assert.strictEqual(img.src, NATIVE);
  assert.doesNotMatch(tile.className, /icon-failed/, "no generic icon while a candidate is left");
  t.ctx.OmarchyTestAPI.switchWorkspace(0);
  assert.match(t.tiles()[0].innerHTML, /src="\/opt\/usr\/globalapps\/3201907018807\/shared\/res\/icon\.png"/);
  assert.doesNotMatch(t.tiles()[0].innerHTML, /webappservice/, "known-bad cache is not retried");
  t.ctx.OmarchyTestAPI.switchWorkspace(2);
  assert.match(t.grid()[0].innerHTML, /globalapps\/3201907018807/, "All Apps shares the per-path failure cache");
});

test("the generic icon and name appear only after every candidate fails", () => {
  const t = boot({ tizen: fakeTizen(), apps: CANDIDATES });
  const tile = t.tiles()[0];
  const img = tile.querySelector("img.tile-icon");
  img.onerror();
  assert.doesNotMatch(tile.className, /icon-failed/);
  img.onerror();
  assert.match(tile.className, /icon-failed/);
  t.ctx.OmarchyTestAPI.switchWorkspace(0);
  assert.doesNotMatch(t.tiles()[0].innerHTML, /<img/);
  assert.match(t.tiles()[0].className, /icon-failed/);
  assert.match(t.tiles()[0].innerHTML, /tile-fallback[\s\S]*<svg[\s\S]*Netflix/);
  assert.match(t.tiles()[1].innerHTML, /<img class="tile-icon"/, "other apps keep their icons");
});

test("unsafe or remote candidates are skipped, a later local one is used", () => {
  const t = boot({ tizen: fakeTizen(), apps: [{ id: "netflix", name: "Netflix", tizenId: "3201907018807", iconPath: 'x.png" onerror="alert(1)',
    iconPaths: ["http://example.com/i.png", "javascript:alert(1)", "/opt/../etc/passwd", NATIVE] }] });
  const html = t.tiles()[0].innerHTML;
  assert.doesNotMatch(html, /onerror=|alert|example\.com|javascript:|\.\./);
  assert.match(html, /src="\/opt\/usr\/globalapps\/3201907018807\/shared\/res\/icon\.png"/);
  t.tiles()[0].querySelector("img.tile-icon").onerror();
  assert.match(t.tiles()[0].className, /icon-failed/, "the unsafe entries are never tried");
});

test("All Apps grid uses the same artwork and order/hidden state", () => {
  const t = boot({ tizen: fakeTizen(), apps: ICONS, cfg: { appOrder: ["youtube"], hiddenApps: ["evil", "remote", "js"] } });
  t.ctx.OmarchyTestAPI.switchWorkspace(2);
  const grid = t.ctx.document.getElementById("ws3-grid").children;
  assert.strictEqual(grid.length, 2);
  assert.match(grid[0].innerHTML, /yt\.png/);
  assert.match(grid[1].innerHTML, /icon\.png/);
});

// ---- HDMI / TV inputs via window.OMARCHY_TIZEN_INPUTS + OMARCHY_TIZEN_SWITCH_INPUT ----

const INPUTS = [
  { id: "tv", name: "TV", sub: "TV", tizenInput: true },
  { id: "hdmi1", name: "HDMI 1", sub: "HDMI 1 · Connected", tizenInput: true },
  { id: "hdmi2", name: "HDMI 2", sub: "HDMI 2 · No signal", tizenInput: true }
];

test("webOS keeps its four LG HDMI inputs", () => {
  const t = boot();
  t.ctx.OmarchyTestAPI.switchWorkspace(1);
  assert.strictEqual(t.inputTiles().length, 5);
});

test("Tizen shows only TV until discovery, never fictitious HDMI ports", () => {
  const t = boot({ tizen: fakeTizen() });
  t.ctx.OmarchyTestAPI.switchWorkspace(1);
  assert.strictEqual(t.inputTiles().length, 1);
  assert.match(t.inputTiles()[0].innerHTML, />TV</);
});

test("Tizen inputs workspace uses the adapter's hardware list", () => {
  const t = boot({ tizen: fakeTizen(), inputs: INPUTS });
  t.ctx.OmarchyTestAPI.switchWorkspace(1);
  assert.strictEqual(t.inputTiles().length, 3);
  assert.match(t.inputTiles()[2].innerHTML, /HDMI 2/);
  t.ctx.__OMARCHY_STATE.ws2Idx = 1;
  t.ctx.OmarchyTestAPI.switchWorkspace(1);
  assert.strictEqual(t.inputTitle(), "HDMI 1 · Connected");
});

test("selecting a Tizen input calls the switch bridge with its id, never an LG app id", () => {
  const calls = [];
  const tz = fakeTizen();
  const t = boot({ tizen: tz, inputs: INPUTS, switchInput: (id, ok, err) => { calls.push(id); ok(); } });
  t.ctx.OmarchyTestAPI.switchWorkspace(1);
  t.inputTiles()[1].onclick();
  assert.deepStrictEqual(calls, ["hdmi1"]);
  assert.deepStrictEqual(tz.calls, [], "no application.launch for inputs");
  assert.doesNotMatch(t.toast(), /com\.webos|Launching/);
});

test("a failed Tizen input switch is reported", () => {
  const t = boot({ tizen: fakeTizen(), inputs: INPUTS, switchInput: (id, ok, err) => err({ name: "NotSupportedError", message: "port busy" }) });
  t.ctx.OmarchyTestAPI.switchWorkspace(1);
  t.inputTiles()[2].onclick();
  assert.match(t.toast(), /HDMI 2/);
  assert.match(t.toast(), /port busy/);
});

test("a throwing or missing Tizen switch bridge is reported too", () => {
  const t = boot({ tizen: fakeTizen(), inputs: INPUTS, switchInput: () => { throw new Error("no privilege"); } });
  t.ctx.OmarchyTestAPI.switchWorkspace(1);
  t.inputTiles()[1].onclick();
  assert.match(t.toast(), /no privilege/);
  const t2 = boot({ tizen: fakeTizen(), inputs: INPUTS });
  t2.ctx.OmarchyTestAPI.switchWorkspace(1);
  t2.inputTiles()[1].onclick();
  assert.match(t2.toast(), /HDMI 1/);
  assert.doesNotMatch(t2.toast(), /^Launching/);
});

test("input list updates keep the selected input by id, clamp when it disappears, ignore junk", () => {
  const t = boot({ tizen: fakeTizen(), inputs: INPUTS });
  t.ctx.OmarchyTestAPI.switchWorkspace(1);
  t.ctx.__OMARCHY_STATE.ws2Idx = 2;   // HDMI 2
  t.emitInputs([INPUTS[0], { id: "hdmi3", name: "HDMI 3", sub: "HDMI 3 · Connected", tizenInput: true }, INPUTS[1], INPUTS[2]]);
  assert.strictEqual(t.inputTiles().length, 4, "workspace re-rendered with the new list");
  assert.strictEqual(t.ctx.__OMARCHY_STATE.ws2Idx, 3);
  assert.strictEqual(t.inputTitle(), "HDMI 2 · No signal");
  t.emitInputs(null);
  t.emitInputs([{ name: "no id" }, { id: "x" }]);
  assert.strictEqual(t.inputTiles().length, 4);
  t.emitInputs([INPUTS[0], INPUTS[1]]);   // HDMI 2 unplugged / gone
  assert.strictEqual(t.inputTiles().length, 2);
  assert.strictEqual(t.ctx.__OMARCHY_STATE.ws2Idx, 1);
  assert.strictEqual(t.inputTitle(), "HDMI 1 · Connected");
});

test("input names are escaped and huge lists are bounded", () => {
  const many = [{ id: "tv", name: "TV", sub: "TV" }, { id: "hdmi1", name: "<b>HDMI</b>", sub: "x" }];
  for (let i = 2; i < 100; i++) many.push({ id: "hdmi" + i, name: "HDMI " + i, sub: "HDMI " + i });
  const t = boot({ tizen: fakeTizen(), inputs: many });
  t.ctx.OmarchyTestAPI.switchWorkspace(1);
  assert.doesNotMatch(t.inputTiles()[1].innerHTML, /<b>/);
  assert.ok(t.inputTiles().length <= 16);
});

// Cinemarchy (cinemarchy.js) reads the inputs through its host API each time it draws; a stand-in records that API.
function fakeCinemarchy(t) {
  const hub = { api: null, refreshed: 0 };
  t.ctx.OmarchyCinemarchy = { keyName: () => "", open: (el, api) => { hub.api = api; return { key: () => true, destroy() {}, refresh() { hub.refreshed++; } }; } };
  return hub;
}

test("Cinemarchy's TV & inputs list the actual Tizen inputs and follow updates", () => {
  const calls = [];
  const t = boot({ tizen: fakeTizen(), inputs: INPUTS, switchInput: (id) => calls.push(id) });
  const hub = fakeCinemarchy(t);
  t.ctx.OmarchyTestAPI.openCinemarchyModal(2);
  const labels = () => hub.api.inputs().map((i) => i.name).join("|");
  assert.match(labels(), /HDMI 1 · Connected/);
  assert.doesNotMatch(labels(), /HDMI 3|HDMI 4|HDMI 1 · HDMI 1/);
  t.emitInputs([INPUTS[0], INPUTS[2]]);
  assert.doesNotMatch(labels(), /HDMI 1/, "the open hub reads the new inputs");
  assert.ok(hub.refreshed >= 1, "and redraws");
  hub.api.inputs().find((i) => /HDMI 2/.test(i.name)).launch();
  assert.deepStrictEqual(calls, ["hdmi2"]);
});

test("an authoritative empty input catalogue has no invented TV source and remains navigable", () => {
  const calls = [];
  const t = boot({ tizen: fakeTizen(), inputs: [], switchInput: id => calls.push(id) });
  t.ctx.OmarchyTestAPI.switchWorkspace(1);
  assert.strictEqual(t.inputTiles().length, 0);
  assert.strictEqual(t.inputTitle(), "No inputs available");
  for (const key of ["ArrowRight", "ArrowLeft", "ArrowDown", "Enter"]) t.press(key);
  assert.strictEqual(t.ctx.__OMARCHY_STATE.ws2Idx, 0);
  assert.deepStrictEqual(calls, []);
  t.press("ArrowUp");
  assert.strictEqual(t.ctx.__OMARCHY_STATE.zone, "bar");
});

test("an empty input update clears stale sources and accepts a later hardware catalogue", () => {
  const calls = [];
  const t = boot({ tizen: fakeTizen(), inputs: INPUTS, switchInput: id => calls.push(id) });
  t.ctx.OmarchyTestAPI.switchWorkspace(1);
  t.ctx.__OMARCHY_STATE.ws2Idx = 2;
  t.emitInputs([]);
  assert.strictEqual(t.inputTiles().length, 0);
  assert.strictEqual(t.ctx.__OMARCHY_STATE.ws2Idx, 0);
  assert.strictEqual(t.inputTitle(), "No inputs available");
  const hub = fakeCinemarchy(t);
  t.ctx.OmarchyTestAPI.openCinemarchyModal(2);
  assert.strictEqual(hub.api.inputs().length, 0, "no invented source in Cinemarchy");
  t.emitInputs(INPUTS);
  assert.strictEqual(hub.api.inputs().length, 3);
});

test("webOS app launches preserve the upstream service API path", () => {
  const calls = [];
  const t = boot({ webOS: { service: { request: (uri, request) => calls.push({ uri, request }) } } });
  t.tiles()[2].onclick();
  assert.strictEqual(calls.length, 1);
  assert.strictEqual(calls[0].uri, "luna://com.webos.applicationManager");
  assert.strictEqual(calls[0].request.method, "launch");
  assert.strictEqual(calls[0].request.parameters.id, "netflix");
});

test("webOS app launches preserve the upstream Palm bridge callback lifecycle", () => {
  const calls = [], bridges = [];
  function Bridge() {
    bridges.push(this);
    this.call = (uri, payload) => calls.push({ uri, payload: JSON.parse(payload) });
  }
  const t = boot({ palmServiceBridge: Bridge });
  calls.length = 0;
  t.tiles()[2].onclick();
  assert.strictEqual(calls.length, 1);
  assert.strictEqual(calls[0].uri, "luna://com.webos.applicationManager/launch");
  assert.strictEqual(calls[0].payload.id, "netflix");
  assert.strictEqual(typeof bridges[bridges.length - 1].onservicecallback, "function");
  assert.doesNotThrow(() => bridges[bridges.length - 1].onservicecallback("{\"returnValue\":true}"));
});
