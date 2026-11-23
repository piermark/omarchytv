/*
 * OmarchyTV on LG webOS: a tiny client for the TV's own remote control API (SSAP, ws://127.0.0.1:3000).
 *
 * Why it exists (#173): on webOS 26, and on recent webOS 22 firmware, the TV closes the web page's own
 * WebSocket with 1008 "invalid origin" right after the handshake, because the page's origin is
 * file://<app id>. The API still accepts "Origin: file://", which only a native client can send. This
 * file is that client: it runs inside the app's JS service (service.js), opens the socket with
 * Origin: file://, pairs with the normal on-screen PROMPT (READ_INSTALLED_APPS only) and lists the
 * launch points. Idea and diagnosis by Stargazer-ops (#173) and simbasjungles/free-my-tv PR 285; this
 * is a fresh implementation.
 *
 * Plain ES5 and Node core modules only (net, tls, crypto): older webOS ships a very old Node (0.12),
 * so no arrow functions, let/const, template literals, classes or Promises, and no npm dependency.
 */
"use strict";

var crypto = require("crypto");

var GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
var DEFAULT_URLS = ["ws://127.0.0.1:3000", "wss://127.0.0.1:3001"];
var LIST_URI = "ssap://com.webos.applicationManager/listLaunchPoints";
var PERMISSIONS = ["READ_INSTALLED_APPS"];

// Buffer.from / Buffer.alloc only behave on Node >= 4.5 (on 4.0-4.4 Buffer.from is Uint8Array.from).
var MODERN_BUFFER = typeof Buffer.alloc === "function" && typeof Buffer.from === "function";
function bufFrom(x, enc) { return MODERN_BUFFER ? Buffer.from(x, enc) : new Buffer(x, enc); }
function bufAlloc(n) {
  if (MODERN_BUFFER) return Buffer.alloc(n);
  var b = new Buffer(n); b.fill(0); return b;
}

// ---- RFC 6455 frames -------------------------------------------------------------------------------------

// One complete frame (FIN set). Client frames must be masked: mask is 4 bytes, or random when omitted.
function encodeFrame(opcode, payload, mask) {
  if (typeof payload === "string") payload = bufFrom(payload, "utf8");
  payload = payload || bufAlloc(0);
  var n = payload.length, head;
  if (n < 126) { head = bufAlloc(2); head[1] = n; }
  else if (n < 65536) { head = bufAlloc(4); head[1] = 126; head.writeUInt16BE(n, 2); }
  else { head = bufAlloc(10); head[1] = 127; head.writeUInt32BE(Math.floor(n / 4294967296), 2); head.writeUInt32BE(n % 4294967296, 6); }
  head[0] = 0x80 | (opcode & 0x0f);
  if (mask === false) return Buffer.concat([head, payload]);   // server side (tests only)
  head[1] |= 0x80;
  mask = mask || crypto.randomBytes(4);
  var out = bufAlloc(n);
  for (var i = 0; i < n; i++) out[i] = payload[i] ^ mask[i % 4];
  return Buffer.concat([head, mask, out]);
}

// Splits buf into complete frames: { frames: [{fin, opcode, payload}], rest } (rest: an incomplete tail).
function decodeFrames(buf) {
  var frames = [];
  for (;;) {
    if (buf.length < 2) break;
    var b0 = buf[0], b1 = buf[1], n = b1 & 0x7f, off = 2;
    if (n === 126) { if (buf.length < 4) break; n = buf.readUInt16BE(2); off = 4; }
    else if (n === 127) { if (buf.length < 10) break; n = buf.readUInt32BE(2) * 4294967296 + buf.readUInt32BE(6); off = 10; }
    var masked = (b1 & 0x80) !== 0, mask = null;
    if (masked) { if (buf.length < off + 4) break; mask = buf.slice(off, off + 4); off += 4; }
    if (buf.length < off + n) break;
    var payload = buf.slice(off, off + n);
    if (masked) {
      var clear = bufAlloc(n);
      for (var i = 0; i < n; i++) clear[i] = payload[i] ^ mask[i % 4];
      payload = clear;
    }
    frames.push({ fin: (b0 & 0x80) !== 0, opcode: b0 & 0x0f, payload: payload });
    buf = buf.slice(off + n);
  }
  return { frames: frames, rest: buf };
}

function acceptFor(key) { return crypto.createHash("sha1").update(key + GUID).digest("base64"); }

function handshakeRequest(host, port, key, origin) {
  return "GET / HTTP/1.1\r\n" +
    "Host: " + host + ":" + port + "\r\n" +
    "Upgrade: websocket\r\n" +
    "Connection: Upgrade\r\n" +
    "Sec-WebSocket-Key: " + key + "\r\n" +
    "Sec-WebSocket-Version: 13\r\n" +
    "Origin: " + origin + "\r\n\r\n";
}

function parseUrl(url) {
  var m = /^(wss?):\/\/([^\/:]+)(?::(\d+))?/.exec(url || "");
  if (!m) return null;
  return { secure: m[1] === "wss", host: m[2], port: m[3] ? parseInt(m[3], 10) : (m[1] === "wss" ? 443 : 80) };
}

// ---- a minimal WebSocket client --------------------------------------------------------------------------
// connect(url, handlers, opts): handlers.onopen(), onmessage(text), onclose(code, reason, opened), onerror(err).
// opts.origin (default "file://"), opts.net / opts.tls (injectable for tests). Returns { send, close }.
function connect(url, handlers, opts) {
  opts = opts || {};
  var u = parseUrl(url), origin = opts.origin || "file://";
  var sock, opened = false, closed = false, buf = bufAlloc(0), text = [], textOp = 0, closeSent = false;
  var key = crypto.randomBytes(16).toString("base64");
  function emitClose(code, reason) {
    if (closed) return;
    closed = true;
    try { sock.destroy(); } catch (e) {}
    if (handlers.onclose) handlers.onclose(code, reason || "", opened);
  }
  function write(b) { try { sock.write(b); } catch (e) {} }
  var api = {
    send: function (s) { if (opened && !closed) write(encodeFrame(1, s)); },
    close: function (code) {
      if (closed) return;
      if (opened && !closeSent) {
        closeSent = true;
        var p = bufAlloc(2); p.writeUInt16BE(code || 1000, 0);
        write(encodeFrame(8, p));
      }
      emitClose(code || 1000, "client close");
    }
  };
  if (!u) { setTimeout(function () { emitClose(1006, "bad url"); }, 0); return api; }
  try {
    if (u.secure) {
      // The TV's certificate is self-signed and this only ever talks to 127.0.0.1.
      sock = (opts.tls || require("tls")).connect({ host: u.host, port: u.port, rejectUnauthorized: false }, onConnect);
    } else {
      sock = (opts.net || require("net")).connect(u.port, u.host, onConnect);
    }
  } catch (e) { setTimeout(function () { emitClose(1006, String(e && e.message || e)); }, 0); return api; }
  function onConnect() { write(handshakeRequest(u.host, u.port, key, origin)); }
  function onFrames() {
    var res = decodeFrames(buf);
    buf = res.rest;
    for (var i = 0; i < res.frames.length && !closed; i++) {
      var f = res.frames[i];
      if (f.opcode === 8) {   // close: answer it, then report its code and reason
        var code = f.payload.length >= 2 ? f.payload.readUInt16BE(0) : 1005;
        var reason = f.payload.length > 2 ? f.payload.slice(2).toString("utf8") : "";
        if (!closeSent) { closeSent = true; var p = bufAlloc(2); p.writeUInt16BE(code === 1005 ? 1000 : code, 0); write(encodeFrame(8, p)); }
        return emitClose(code, reason);
      }
      if (f.opcode === 9) { write(encodeFrame(10, f.payload)); continue; }   // ping -> pong
      if (f.opcode === 10) continue;
      if (f.opcode === 1 || f.opcode === 2) { text = [f.payload]; textOp = f.opcode; }
      else if (f.opcode === 0) text.push(f.payload);
      else continue;
      if (f.fin) {
        var msg = Buffer.concat(text); text = [];
        if (textOp === 1 && handlers.onmessage) handlers.onmessage(msg.toString("utf8"));
      }
    }
  }
  sock.on("data", function (chunk) {
    if (closed) return;
    buf = Buffer.concat([buf, chunk]);
    if (!opened) {
      var end = buf.toString("binary").indexOf("\r\n\r\n");
      if (end < 0) { if (buf.length > 16384) emitClose(1002, "bad handshake"); return; }
      var head = buf.slice(0, end).toString("utf8");
      buf = buf.slice(end + 4);
      var accept = /\r\nsec-websocket-accept:\s*([^\r\n]+)/i.exec(head);
      if (!/^HTTP\/1\.[01] 101/.test(head) || !accept || accept[1].trim() !== acceptFor(key)) {
        var status = /^HTTP\/1\.[01] (\d{3})/.exec(head);
        return emitClose(status && status[1] === "403" ? 1008 : 1002, "handshake refused" + (status ? " (" + status[1] + ")" : ""));
      }
      opened = true;
      if (handlers.onopen) handlers.onopen();
      if (closed) return;
    }
    onFrames();
  });
  sock.on("error", function (err) { if (handlers.onerror) handlers.onerror(err); emitClose(1006, String(err && err.message || err)); });
  sock.on("close", function () { emitClose(1006, "connection closed"); });
  return api;
}

// ---- SSAP: register (PROMPT pairing) + one request -------------------------------------------------------
// store: { get() -> key or null, set(key), clear() }.
// opts: urls, timeoutMs (time to answer the TV's prompt), holdMs (keep the session open after the answer, so
// the TV keeps serving the icon URLs it just handed out), connect (injectable), origin.
// done(err, payload): err.info is one of "not approved", "invalid origin", "unavailable", "closed", "no answer", "error".
function request(uri, store, opts, done) {
  opts = opts || {};
  var urls = opts.urls || DEFAULT_URLS, idx = 0, finished = false, timer, holdTimer;
  var open = opts.connect || connect, timeoutMs = opts.timeoutMs || 90000, holdMs = Math.max(0, Math.min(opts.holdMs || 0, 15000));
  function fail(info, detail) {
    var e = new Error(info + (detail ? ": " + detail : "")); e.info = info; return e;
  }
  function finish(err, payload, ws) {
    if (finished) return;
    finished = true; clearTimeout(timer);
    if (!err && holdMs && ws) holdTimer = setTimeout(function () { try { ws.close(1000); } catch (e) {} }, holdMs);
    else if (ws) { try { ws.close(1000); } catch (e) {} }
    done(err, payload);
  }
  timer = setTimeout(function () { finish(fail("no answer"), null, current); }, timeoutMs);
  var current = null;
  function attempt() {
    var usedKey = null, gotAnswer = false;
    try { usedKey = store && store.get(); } catch (e) { usedKey = null; }
    var ws = current = open(urls[idx], {
      onopen: function () {
        var payload = { forcePairing: false, pairingType: "PROMPT",
          manifest: { manifestVersion: 1, appVersion: "1.0", permissions: PERMISSIONS } };
        if (usedKey) payload["client-key"] = usedKey;
        ws.send(JSON.stringify({ type: "register", id: "reg", payload: payload }));
      },
      onmessage: function (text) {
        if (finished) return;
        var m;
        try { m = JSON.parse(text); } catch (e) { return; }
        if (!m || typeof m !== "object") return;
        if (m.id === "reg") {
          if (m.type === "registered") {
            var k = m.payload && m.payload["client-key"];
            if (k && k !== usedKey) { try { store.set(k); } catch (e) {} }
            ws.send(JSON.stringify({ type: "request", id: "req", uri: uri, payload: {} }));
          } else if (m.type === "error") {
            if (usedKey) { try { store.clear(); } catch (e) {} }
            finish(fail("not approved", m.error), null, ws);
          }
          // type "response" (pairingType PROMPT, returnValue true) is the TV saying the prompt is on screen:
          // keep waiting for "registered".
          return;
        }
        if (m.id === "req") {
          gotAnswer = true;
          if (m.type === "response" && !(m.payload && m.payload.returnValue === false)) finish(null, m.payload || {}, ws);
          else finish(fail("error", m.error || (m.payload && m.payload.errorText)), null, ws);
        }
      },
      onclose: function (code, reason, opened) {
        if (finished) return;
        if (code === 1008 || /origin/i.test(reason || "")) return finish(fail("invalid origin", reason));
        if (!opened && idx + 1 < urls.length) { idx++; return attempt(); }
        finish(fail(opened ? "closed" : "unavailable", reason));
      }
    }, { origin: opts.origin || "file://", net: opts.net, tls: opts.tls });
  }
  attempt();
  return { cancel: function () { clearTimeout(holdTimer); finish(fail("closed", "cancelled"), null, current); } };
}

// Only what the launcher uses from each launch point, as plain JSON (the Luna bus carries it to the page).
var LP_FIELDS = ["id", "title", "launchPointId", "lptype", "icon", "largeIcon", "extraLargeIcon", "mediumLargeIcon",
  "iconColor", "params", "visible", "hidden", "systemApp"];
function mapLaunchPoints(payload) {
  var lps = payload && payload.launchPoints;
  if (!Array.isArray(lps)) return null;
  var out = [];
  for (var i = 0; i < lps.length; i++) {
    var lp = lps[i];
    if (!lp || typeof lp.id !== "string" || !lp.id) continue;
    var o = {};
    for (var j = 0; j < LP_FIELDS.length; j++) if (lp[LP_FIELDS[j]] !== undefined) o[LP_FIELDS[j]] = lp[LP_FIELDS[j]];
    out.push(o);
  }
  return out;
}

function listLaunchPoints(store, opts, done) {
  return request(LIST_URI, store, opts, function (err, payload) {
    if (err) return done(err);
    var lps = mapLaunchPoints(payload);
    if (!lps) { var e = new Error("error: no launchPoints"); e.info = "error"; return done(e); }
    done(null, lps);
  });
}

// ---- the client key, in a file the service can write ------------------------------------------------------
// Tries each path in turn (the service's own folder is read-only on some firmware); the key also stays in
// memory, so a TV where no path is writable still prompts only once per service run.
function fileKeyStore(paths, fs) {
  fs = fs || require("fs");
  var mem = null;
  function read(p) {
    try { var d = JSON.parse(fs.readFileSync(p, "utf8")); return d && typeof d["client-key"] === "string" && d["client-key"] ? d["client-key"] : null; }
    catch (e) { return null; }
  }
  return {
    get: function () {
      if (mem) return mem;
      for (var i = 0; i < paths.length; i++) { var k = read(paths[i]); if (k) { mem = k; return k; } }
      return null;
    },
    set: function (k) {
      mem = k;
      for (var i = 0; i < paths.length; i++) {
        try { fs.writeFileSync(paths[i], JSON.stringify({ "client-key": k }), { mode: 384 }); return true; } catch (e) {}
      }
      return false;
    },
    clear: function () {
      mem = null;
      for (var i = 0; i < paths.length; i++) { try { fs.unlinkSync(paths[i]); } catch (e) {} }
    }
  };
}

module.exports = {
  encodeFrame: encodeFrame, decodeFrames: decodeFrames, acceptFor: acceptFor, handshakeRequest: handshakeRequest,
  connect: connect, request: request, listLaunchPoints: listLaunchPoints, mapLaunchPoints: mapLaunchPoints,
  fileKeyStore: fileKeyStore, DEFAULT_URLS: DEFAULT_URLS, LIST_URI: LIST_URI, PERMISSIONS: PERMISSIONS
};
