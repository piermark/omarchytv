// node --test webos/tests/webos26-service.test.js — #173: webOS 26 closes the page's SSAP WebSocket with 1008
// "invalid origin"; the ipk's JS service (webos/service) lists the apps with Origin: file:// instead.
const test = require("node:test");
const assert = require("assert");
const fs = require("fs");
const os = require("os");
const net = require("net");
const path = require("path");
const crypto = require("crypto");
const Module = require("module");
const ssap = require("../service/ssap-client.js");
const TVA = require("../app/tvapps.js");

const ROOT = path.join(__dirname, "..");
const LPS = [
  { id: "netflix", title: "Netflix", icon: "http://127.0.0.1:3000/resources/n.png", launchPointId: "netflix_default", lptype: "default", extra: "x" },
  { id: "com.atlas.tv", title: "NOW", largeIcon: "http://127.0.0.1:3000/resources/now.png", params: { a: 1 } }
];

// ---- a fake TV: the SSAP WebSocket server side, written against the RFC (no ws dependency) ----
function fakeTv(opts) {
  opts = opts || {};
  const seen = { origins: [], registers: [], requests: [], unmasked: 0, pongs: 0, connections: 0 };
  const server = net.createServer((sock) => {
    seen.connections++;
    let buf = Buffer.alloc(0), open = false;
    const send = (obj) => sock.write(ssap.encodeFrame(1, JSON.stringify(obj), false));
    sock.on("error", () => {});
    sock.on("data", (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      if (!open) {
        const end = buf.indexOf("\r\n\r\n");
        if (end < 0) return;
        const head = buf.slice(0, end).toString();
        buf = buf.slice(end + 4);
        const origin = /\r\norigin: ([^\r\n]*)/i.exec(head);
        seen.origins.push(origin ? origin[1] : null);
        const key = /\r\nsec-websocket-key: ([^\r\n]*)/i.exec(head)[1];
        sock.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " +
          ssap.acceptFor(key) + "\r\n\r\n");
        open = true;
        if (opts.rejectOrigin && (!origin || origin[1] !== "file://")) {
          const p = Buffer.concat([Buffer.from([0x03, 0xf0]), Buffer.from("invalid origin")]);   // 1008
          sock.write(ssap.encodeFrame(8, p, false));
          return;
        }
        if (opts.ping) sock.write(ssap.encodeFrame(9, "hi", false));
      }
      const res = ssap.decodeFrames(buf);
      buf = res.rest;
      for (const f of res.frames) {
        if (f.opcode === 10) { seen.pongs++; continue; }
        if (f.opcode === 8) { sock.end(); continue; }
        const m = JSON.parse(f.payload.toString());
        if (m.type === "register") {
          seen.registers.push(m.payload);
          if (opts.refuse) { send({ type: "error", id: m.id, error: "403 User denied access" }); continue; }
          const k = m.payload["client-key"];
          if (k && k === opts.knownKey) send({ type: "registered", id: m.id, payload: { "client-key": k } });
          else {
            // the intermediate PROMPT ACK: the prompt is on screen, "registered" follows once accepted
            send({ type: "response", id: m.id, payload: { pairingType: "PROMPT", returnValue: true } });
            setTimeout(() => send({ type: "registered", id: m.id, payload: { "client-key": "KEY-NEW" } }), 20);
          }
        } else if (m.type === "request") {
          seen.requests.push(m);
          send({ type: "response", id: m.id, payload: { returnValue: true, launchPoints: LPS } });
        }
      }
    });
  });
  // raw frames are checked separately for the mask bit
  server.on("connection", (sock) => sock.on("data", (c) => { if (c[0] === 0x81 && !(c[1] & 0x80)) seen.unmasked++; }));
  return new Promise((resolve) => server.listen(0, "127.0.0.1", () => resolve({ server, seen, url: "ws://127.0.0.1:" + server.address().port })));
}
function memStore(k) {
  const s = { key: k || null, sets: 0, clears: 0 };
  s.get = () => s.key; s.set = (v) => { s.key = v; s.sets++; }; s.clear = () => { s.key = null; s.clears++; };
  return s;
}
const list = (store, opts) => new Promise((resolve) => ssap.listLaunchPoints(store, opts, (err, lps) => resolve({ err, lps })));

test("frames: masked client frames decode back, all three length forms, partial tail kept", () => {
  for (const n of [5, 300, 70000]) {
    const text = "x".repeat(n);
    const f = ssap.encodeFrame(1, text, Buffer.from([1, 2, 3, 4]));
    assert.strictEqual(f[0], 0x81);
    assert.ok(f[1] & 0x80, "client frames are masked");
    const out = ssap.decodeFrames(f);
    assert.strictEqual(out.frames.length, 1);
    assert.strictEqual(out.frames[0].payload.toString(), text);
    assert.strictEqual(out.rest.length, 0);
    const cut = ssap.decodeFrames(f.slice(0, f.length - 1));
    assert.strictEqual(cut.frames.length, 0, "incomplete frame waits for more data");
    assert.strictEqual(cut.rest.length, f.length - 1);
  }
  const two = Buffer.concat([ssap.encodeFrame(1, "a", false), ssap.encodeFrame(9, "p", false), ssap.encodeFrame(1, "b", false).slice(0, 2)]);
  const out = ssap.decodeFrames(two);
  assert.deepStrictEqual(out.frames.map((f) => [f.opcode, f.payload.toString()]), [[1, "a"], [9, "p"]]);
  assert.strictEqual(out.rest.length, 2);
});

test("handshake: Origin file://, RFC accept key", () => {
  const req = ssap.handshakeRequest("127.0.0.1", 3000, "dGhlIHNhbXBsZSBub25jZQ==", "file://");
  assert.match(req, /^GET \/ HTTP\/1\.1\r\n/);
  assert.match(req, /\r\nOrigin: file:\/\/\r\n/);
  assert.match(req, /\r\nSec-WebSocket-Version: 13\r\n/);
  assert.ok(req.endsWith("\r\n\r\n"));
  assert.strictEqual(ssap.acceptFor("dGhlIHNhbXBsZSBub25jZQ=="), "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=");   // RFC 6455 example
});

test("pairing: intermediate PROMPT ACK, then registered; key stored; list mapped; Origin file://; masked; READ_INSTALLED_APPS only", async () => {
  const tv = await fakeTv({ rejectOrigin: true, ping: true });
  const store = memStore();
  const r = await list(store, { urls: [tv.url] });
  tv.server.close();
  assert.ifError(r.err);
  assert.deepStrictEqual(tv.seen.origins, ["file://"]);
  assert.strictEqual(tv.seen.unmasked, 0);
  assert.strictEqual(tv.seen.pongs, 1, "ping answered with pong");
  assert.deepStrictEqual(tv.seen.registers[0].manifest.permissions, ["READ_INSTALLED_APPS"]);
  assert.strictEqual(tv.seen.registers[0].pairingType, "PROMPT");
  assert.strictEqual(tv.seen.registers[0]["client-key"], undefined);
  assert.strictEqual(tv.seen.requests[0].uri, "ssap://com.webos.applicationManager/listLaunchPoints");
  assert.strictEqual(store.key, "KEY-NEW");
  assert.strictEqual(r.lps.length, 2);
  assert.strictEqual(r.lps[0].extra, undefined, "only the fields the launcher uses");
  assert.strictEqual(r.lps[0].launchPointId, "netflix_default");
  assert.deepStrictEqual(r.lps[1].params, { a: 1 });
});

test("stored key is reused: no new prompt, no new write", async () => {
  const tv = await fakeTv({ knownKey: "KEY-OLD" });
  const store = memStore("KEY-OLD");
  const r = await list(store, { urls: [tv.url] });
  tv.server.close();
  assert.ifError(r.err);
  assert.strictEqual(tv.seen.registers[0]["client-key"], "KEY-OLD");
  assert.strictEqual(store.sets, 0);
  assert.strictEqual(r.lps.length, 2);
});

test("a no on the TV prompt is 'not approved' and drops the stored key", async () => {
  const tv = await fakeTv({ refuse: true });
  const store = memStore("STALE");
  const r = await list(store, { urls: [tv.url] });
  tv.server.close();
  assert.strictEqual(r.err.info, "not approved");
  assert.strictEqual(store.key, null);
});

test("a 1008 close is 'invalid origin'; a closed port moves on to the next url, then 'unavailable'", async () => {
  const tv = await fakeTv({ rejectOrigin: true });
  const r = await list(memStore(), { urls: [tv.url], origin: "file://com.omarchytv.home-webos" });
  assert.strictEqual(r.err.info, "invalid origin");
  const dead = net.createServer(); await new Promise((res) => dead.listen(0, "127.0.0.1", res));
  const deadUrl = "ws://127.0.0.1:" + dead.address().port; dead.close();
  const r2 = await list(memStore(), { urls: [deadUrl, tv.url] });
  assert.ifError(r2.err, "second url used");
  const r3 = await list(memStore(), { urls: [deadUrl] });
  assert.strictEqual(r3.err.info, "unavailable");
  tv.server.close();
});

test("no answer on the prompt times out as 'no answer'", async () => {
  const server = net.createServer((s) => s.on("error", () => {}));   // accepts, never answers the handshake
  await new Promise((res) => server.listen(0, "127.0.0.1", res));
  const r = await list(memStore(), { urls: ["ws://127.0.0.1:" + server.address().port], timeoutMs: 100 });
  server.close();
  assert.strictEqual(r.err.info, "no answer");
});

test("fileKeyStore: first writable path wins, read back, cleared", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "otv-key-"));
  const paths = [path.join(dir, "missing-dir", "k.json"), path.join(dir, "k.json")];
  const s = ssap.fileKeyStore(paths);
  assert.strictEqual(s.get(), null);
  assert.strictEqual(s.set("ABC"), true);
  assert.strictEqual(JSON.parse(fs.readFileSync(paths[1], "utf8"))["client-key"], "ABC");
  assert.strictEqual(ssap.fileKeyStore(paths).get(), "ABC", "a new service run reads it back");
  s.clear();
  assert.strictEqual(fs.existsSync(paths[1]), false);
  assert.strictEqual(ssap.fileKeyStore(paths).get(), null);
  const none = ssap.fileKeyStore([path.join(dir, "no", "a.json")]);
  assert.strictEqual(none.set("M"), false);
  assert.strictEqual(none.get(), "M", "kept in memory when nothing is writable");
  fs.rmSync(dir, { recursive: true, force: true });
});

test("mapLaunchPoints: invalid payloads give null, entries without an id are skipped", () => {
  assert.strictEqual(ssap.mapLaunchPoints(null), null);
  assert.strictEqual(ssap.mapLaunchPoints({ launchPoints: "x" }), null);
  assert.deepStrictEqual(ssap.mapLaunchPoints({ launchPoints: [null, { title: "no id" }, { id: "a", title: "A", visible: false }] }),
    [{ id: "a", title: "A", visible: false }]);
});

test("service.js: listApps answers through webos-service, key kept by the service", async () => {
  const tv = await fakeTv({ rejectOrigin: true });
  const registered = {};
  class FakeService {
    constructor(name) { this.name = name; this.activityManager = { create: (id, cb) => cb({ id }), complete: (a, cb) => cb && cb() }; }
    register(name, fn) { registered[name] = fn; }
  }
  const realResolve = Module._resolveFilename, realLoad = Module._load;
  Module._load = function (req) { return req === "webos-service" ? FakeService : realLoad.apply(this, arguments); };
  const home = fs.mkdtempSync(path.join(os.tmpdir(), "otv-home-")), oldHome = process.env.HOME;
  process.env.HOME = home;
  const svcPath = require.resolve("../service/service.js");
  delete require.cache[svcPath];
  const realDefault = ssap.DEFAULT_URLS.slice();
  ssap.DEFAULT_URLS.splice(0, ssap.DEFAULT_URLS.length, tv.url);   // the service's urls -> the fake TV
  try {
    require(svcPath);
    const answer = await new Promise((resolve) => registered.listApps({ payload: {}, respond: resolve }));
    assert.strictEqual(answer.returnValue, true);
    assert.strictEqual(answer.launchPoints.length, 2);
    assert.strictEqual(JSON.parse(fs.readFileSync(path.join(home, ".omarchytv-ssap-key.json"), "utf8"))["client-key"], "KEY-NEW");
    await new Promise((resolve) => registered.forget({ payload: {}, respond: resolve }));
    assert.strictEqual(fs.existsSync(path.join(home, ".omarchytv-ssap-key.json")), false);
  } finally {
    ssap.DEFAULT_URLS.splice(0, ssap.DEFAULT_URLS.length, ...realDefault);
    Module._load = realLoad; Module._resolveFilename = realResolve;
    process.env.HOME = oldHome;
    delete require.cache[svcPath];
    tv.server.close();
    fs.rmSync(home, { recursive: true, force: true });
  }
});

// ---- the page side: OmarchyTvApps.callService / discover ----
function bridgeEnv(reply, calls) {
  function PalmServiceBridge() {}
  PalmServiceBridge.prototype.call = function (uri, params) {
    calls.push({ uri, params: JSON.parse(params) });
    const self = this;
    if (reply !== undefined) setTimeout(() => self.onservicecallback(typeof reply === "string" ? reply : JSON.stringify(reply)), 1);
  };
  PalmServiceBridge.prototype.cancel = function () { calls.push({ cancelled: true }); };
  return { PalmServiceBridge };
}
const call = (env, timeout) => new Promise((resolve) => TVA.callService(env, TVA.SERVICE_URI, { holdMs: 10000 }, timeout || 1000,
  (ok, info, data) => resolve({ ok, info, data })));

test("callService: launch points, the service's own reason, a missing service, silence, no bridge", async () => {
  const calls = [];
  let r = await call(bridgeEnv({ returnValue: true, launchPoints: LPS }, calls));
  assert.deepStrictEqual([r.ok, r.data.launchPoints.length], [true, 2]);
  assert.deepStrictEqual(calls[0], { uri: "luna://com.omarchytv.home.service/listApps", params: { holdMs: 10000 } });
  r = await call(bridgeEnv({ returnValue: false, info: "not approved" }, []));
  assert.deepStrictEqual([r.ok, r.info], [false, "not approved"]);
  r = await call(bridgeEnv({ returnValue: false, errorCode: -1, errorText: "Unknown method \"listApps\" for category \"/\"" }, []));
  assert.deepStrictEqual([r.ok, r.info], [false, "no service"]);
  r = await call(bridgeEnv("not json", []));
  assert.deepStrictEqual([r.ok, r.info], [false, "no service"]);
  const silent = [];
  r = await call(bridgeEnv(undefined, silent), 30);
  assert.deepStrictEqual([r.ok, r.info], [false, "no service"]);
  assert.ok(silent.some((c) => c.cancelled), "a silent call is cancelled");
  r = await call({}, 1000);
  assert.deepStrictEqual([r.ok, r.info], [false, "no service"]);
  r = await new Promise((resolve) => TVA.callService({ webOS: { service: { request: (base, o) => {
    assert.strictEqual(base, "luna://com.omarchytv.home.service"); assert.strictEqual(o.method, "listApps");
    o.onSuccess({ returnValue: true, launchPoints: LPS });
  } } } }, TVA.SERVICE_URI, {}, 1000, (ok, info) => resolve({ ok, info })));
  assert.strictEqual(r.ok, true, "webOS.service.request when webOSTV.js is there");
});

function runDiscover(opts) {
  const used = [];
  const step = (via, answer) => (cb) => { used.push(via); setTimeout(() => cb.apply(null, answer), 0); };
  return new Promise((resolve) => TVA.discover({ preferService: opts.preferService, ssap: step("ssap", opts.ssap), service: step("service", opts.service) },
    (ok, info, data, via) => resolve({ ok, info, via, used })));
}
const OKL = [true, "response", { launchPoints: LPS }];

test("discover: the page's SSAP stays first; webOS 22 behaviour unchanged when it works or the user says no", async () => {
  let r = await runDiscover({ ssap: OKL, service: OKL });
  assert.deepStrictEqual([r.ok, r.via, r.used], [true, "ssap", ["ssap"]]);
  for (const info of ["not approved", "no answer", "error"]) {
    r = await runDiscover({ ssap: [false, info], service: OKL });
    assert.deepStrictEqual([r.ok, r.info, r.used], [false, info, ["ssap"]], info + ": the service is not asked");
  }
});

test("discover: 1008 / unavailable / closed on the page's SSAP -> the service", async () => {
  for (const info of ["invalid origin", "unavailable", "closed"]) {
    const r = await runDiscover({ ssap: [false, info], service: OKL });
    assert.deepStrictEqual([r.ok, r.via, r.used], [true, "service", ["ssap", "service"]], info);
  }
});

test("discover: a missing or failing service falls back to the page's SSAP result unchanged", async () => {
  let r = await runDiscover({ ssap: [false, "unavailable"], service: [false, "no service"] });
  assert.deepStrictEqual([r.ok, r.info, r.used], [false, "unavailable", ["ssap", "service"]], "reports the SSAP reason");
  r = await runDiscover({ ssap: [false, "invalid origin"], service: [false, "not approved"] });
  assert.deepStrictEqual([r.ok, r.info], [false, "not approved"]);
  // once the service worked it goes first; if it is gone (an older ipk) or fails, the page's SSAP is used
  r = await runDiscover({ preferService: true, service: [false, "no service"], ssap: OKL });
  assert.deepStrictEqual([r.ok, r.via, r.used], [true, "ssap", ["service", "ssap"]]);
  r = await runDiscover({ preferService: true, service: [false, "invalid origin"], ssap: OKL });
  assert.deepStrictEqual([r.ok, r.via], [true, "ssap"]);
  r = await runDiscover({ preferService: true, service: [false, "not approved"], ssap: OKL });
  assert.deepStrictEqual([r.ok, r.info, r.used], [false, "not approved", ["service"]]);
  r = await runDiscover({ preferService: true, service: OKL, ssap: OKL });
  assert.deepStrictEqual([r.via, r.used], ["service", ["service"]]);
});

// ---- wiring in app.js, the package and the service files ----
const APP = fs.readFileSync(path.join(ROOT, "app/app.js"), "utf8");

test("app.js: the page's SSAP reports a 1008 close at once and still waits through the PROMPT ACK", () => {
  const fn = APP.slice(APP.indexOf("function ssapRequest("), APP.indexOf("// LG webOS: the apps installed on the TV"));
  assert.match(fn, /ws\.onclose = function \(ev\) \{ finish\(false, ev && ev\.code === 1008 \? "invalid origin" : opened \? "closed" : "unavailable"\); \};/);
  assert.match(fn, /msg\.id === "reg" && msg\.type === "registered"/);
  assert.doesNotMatch(fn, /msg\.id === "reg" && msg\.type === "response"\) *finish/, "a reg response does not end the session");
});

test("app.js: refreshTvApps goes through discover, SSAP with its icon hold, the service with holdMs", () => {
  const fn = APP.slice(APP.indexOf("function refreshTvApps("), APP.indexOf("function tvAppsAnswer("));
  assert.match(fn, /TVA\.discover\(/);
  assert.match(fn, /ssapRequest\("ssap:\/\/com\.webos\.applicationManager\/listLaunchPoints", cb, null, 0,/);
  assert.match(fn, /cacheLaunchPointIcons\(data && data\.launchPoints, release\)/);
  assert.match(fn, /TVA\.callService\(window, TVA\.SERVICE_URI, \{ holdMs: 10000 \}, 100000,/);
  assert.match(APP, /function repairTv\(\) \{[\s\S]*?TVAPPS_VIA[\s\S]*?\/forget/);
});

test("package: build-ipk.sh packs webos/service; ids match the app id; service code is plain ES5 with core modules only", () => {
  const build = fs.readFileSync(path.join(ROOT, "build-ipk.sh"), "utf8");
  assert.match(build, /ares-package "\$APP" "\$SCRIPT_DIR\/service" -o "\$OUT_DIR"/);
  const appId = JSON.parse(fs.readFileSync(path.join(ROOT, "app/appinfo.json"), "utf8")).id;
  const pkg = JSON.parse(fs.readFileSync(path.join(ROOT, "service/package.json"), "utf8"));
  const svc = JSON.parse(fs.readFileSync(path.join(ROOT, "service/services.json"), "utf8"));
  assert.strictEqual(pkg.name, appId + ".service");
  assert.strictEqual(svc.id, appId + ".service");
  assert.strictEqual(svc.services[0].name, appId + ".service");
  assert.strictEqual(pkg.dependencies, undefined, "no npm dependency");
  assert.strictEqual(TVA.SERVICE_URI, "luna://" + appId + ".service/listApps");
  for (const f of ["service.js", "ssap-client.js"]) {
    const src = fs.readFileSync(path.join(ROOT, "service", f), "utf8").replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "");
    assert.doesNotMatch(src, /=>|`|\b(let|const|class|async|await)\s|\.\.\.\w/, f + " is ES5");
    for (const m of src.matchAll(/require\("([^"]+)"\)/g)) {
      assert.ok(["net", "tls", "crypto", "fs", "path", "webos-service", "./ssap-client.js", "./package.json"].includes(m[1]), f + " requires " + m[1]);
    }
  }
});
