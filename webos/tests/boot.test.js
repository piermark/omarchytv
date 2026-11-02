// node --test webos/tests/boot.test.js — boot screen script (webOS/Tizen), same as Android BootScriptTest.
const test = require("node:test");
const assert = require("assert");
const fs = require("fs");
const B = require("../app/boot.js");

const JAVA = __dirname + "/../../launcher/app/src/main/java/com/omarchytv/home/";
const READY = (key) => key === "net" ? { ok: true, text: "Network online." } : { ok: true, text: "omarchy-link: connected to omarchy-pc." };
const NEVER = (key, timedOut) => timedOut ? { ok: false, text: key + " not reachable." } : null;
const opts = (live, extra) => Object.assign({ version: "2.14.0", theme: "gruvbox", model: "LG OLED55C3", ramMb: 2048,
  display: "1920x1080", apps: 12, user: "user", host: "omarchytv", live: live }, extra || {});
const has = (s, t) => s.lines.some((l) => l.text.indexOf(t) >= 0);
const first = (s, k) => s.lines.find((l) => l.kind === k);
const keyed = (s, k) => s.lines.find((l) => l.key === k);
const play = (s, upTo) => { let ms = 0; for (let r = 0; r <= upTo; r += 33) ms = B.clock(s, r); return ms; };

test("short enough to not annoy", () => {
  const s = B.build(opts(READY));
  assert.ok(s.endAt < 8000, "was " + s.endAt);
});
test("CRT opens before the first line", () => {
  const s = B.build(opts(READY));
  assert.strictEqual(s.lines[0].at, 520);
  assert.strictEqual(B.visibleAt(s, 519).length, 0);
});
test("lines in order and never wider than the columns", () => {
  let last = -1;
  for (const l of B.build(opts(READY)).lines) {
    assert.ok(l.at >= last);
    last = l.at;
    assert.ok(l.text.length <= B.COLUMNS, l.text);
  }
});
test("BIOS shows the real TV; unknown values leave their lines out", () => {
  const s = B.build(opts(READY));
  assert.ok(has(s, "System Model   : LG OLED55C3"));
  assert.ok(has(s, "Display Mode   : 1920x1080"));
  assert.ok(has(s, "Indexed 12 apps."));
  assert.ok(!has(s, "HDMI-CEC"));
  const u = B.build(opts(READY, { ramMb: 0, display: "", apps: -1 }));
  assert.ok(!has(u, "Memory Testing") && !has(u, "Display Mode") && !has(u, "Indexed"));
});
test("memory counts up then settles", () => {
  const mem = first(B.build(opts(READY)), "memtest");
  assert.strictEqual(B.memText(mem, mem.at), "Memory Testing : 0 MB");
  assert.strictEqual(B.memText(mem, mem.at + mem.anim / 2), "Memory Testing : 1024 MB");
  assert.strictEqual(B.memText(mem, mem.at + mem.anim), "Memory Testing : 2048 MB OK");
});
test("ready answers show real network and PC without delay", () => {
  const s = B.build(opts(READY));
  play(s, s.fadeAt);
  assert.strictEqual(B.clock(s, s.fadeAt), s.fadeAt);
  assert.strictEqual(B.doneText(keyed(s, "net")), "Network online.");
  assert.strictEqual(B.doneText(keyed(s, "pc")), "omarchy-link: connected to omarchy-pc.");
});
test("spinner really waits, then warns on timeout", () => {
  const s = B.build(opts(NEVER));
  const net = keyed(s, "net"), end = net.at + net.anim;
  assert.strictEqual(play(s, end + 1000), end);
  assert.strictEqual(B.spinning(net, end), true);
  const after = play(s, end + B.MAX_WAIT_MS + 200);
  assert.strictEqual(B.doneOk(net), false);
  assert.strictEqual(B.doneText(net), "net not reachable.");
  assert.ok(after > end && after < end + 400, "after " + after);
});
test("a failing probe never stops the boot screen", () => {
  const s = B.build(opts(() => { throw new Error("boom"); }));
  const ms = play(s, 15000);
  assert.strictEqual(B.doneOk(keyed(s, "pc")), false);
  assert.ok(ms >= s.fadeAt, "was " + ms);
});
test("live text is cleaned and fits", () => {
  const s = B.build(opts(() => ({ ok: true, text: "Wi-Fi \u001b[2J" + "x".repeat(100) })));
  play(s, 8000);
  const t = B.doneText(keyed(s, "net"));
  assert.ok(t.indexOf("\u001b") < 0 && t.length <= 64);
});
test("badge during the BIOS, then a flicker", () => {
  const s = B.build(opts(READY));
  assert.strictEqual(s.badge.length, 5);
  const clear = first(s, "clear");
  assert.strictEqual(clear.at, s.biosEnd);
  assert.strictEqual(B.flickering(s, clear.at), true);
  assert.strictEqual(B.flickering(s, clear.at + 140), false);
});
test("ends with omarchy-tv typed and the OMARCHY TV wordmark", () => {
  const s = B.build(opts(READY));
  const p = first(s, "prompt");
  assert.strictEqual(p.detail, "omarchy-tv");
  assert.strictEqual(B.typedChars(p, p.at + p.anim), 10);
  const end = B.visibleAt(s, s.fadeAt);
  assert.strictEqual(end[0].text, "Welcome to Omarchy TV!");
  assert.strictEqual(end.filter((l) => l.kind === "logo").length, 5);
  assert.strictEqual(end[end.length - 1].kind, "info");
});
test("same block font as BlockFont.java", () => {
  const src = fs.readFileSync(JAVA + "BlockFont.java", "utf8");
  const re = /GLYPHS\.put\('(.)', new String\[\]\{([^}]*)\}\)/g;
  let m, n = 0;
  while ((m = re.exec(src))) {
    const rows = m[2].match(/"([^"]*)"/g).map((q) => q.slice(1, -1).replace(/#/g, "█"));
    assert.deepStrictEqual(B.blockRows(m[1]), rows, "glyph " + m[1]);
    n++;
  }
  assert.ok(n >= 10);
  assert.strictEqual(B.blockRows("OMARCHY TV")[0].length, 66);
});
test("same script as Android: fixed lines, jobs and timings are also in BootScript.java", () => {
  const src = fs.readFileSync(JAVA + "BootScript.java", "utf8");
  for (const l of B.build(opts(READY)).lines) {
    if (["blank", "clear", "logo", "info"].indexOf(l.kind) >= 0) continue;
    if (/OmarchyBIOS|System Model|Display Mode|Memory|Loaded theme|Indexed|login:|@/.test(l.text)) continue;
    assert.ok(src.indexOf(l.text) >= 0, "missing in BootScript.java: " + l.text);
    if (l.detail) assert.ok(src.indexOf('"' + l.detail + '"') >= 0, "missing in BootScript.java: " + l.detail);
    if (l.key) assert.ok(src.indexOf('"' + l.key + '", t') >= 0, "live key differs: " + l.key);
  }
  for (const k of ["CRT_MS = 520", "FLICKER_MS = 140", "HOLD_MS = 900", "FADE_MS = 400", "COLUMNS = 72",
                   "MAX_WAIT_MS = 2500", "SPIN_MS = 420"])
    assert.ok(src.indexOf(k) >= 0, "timing differs: " + k);
});
test("control characters and empty values are cleaned", () => {
  const s = B.build({ model: "Evil\u001b[2JTV", theme: "", user: " " });
  assert.ok(has(s, "OmarchyBIOS vdev"));
  assert.ok(has(s, "System Model   : Evil[2JTV"));
  assert.ok(has(s, "omarchytv login: user"));
});
test("modes: boot plays at start, wake also on return, off never", () => {
  assert.strictEqual(B.shouldPlay("boot", "start"), true);
  assert.strictEqual(B.shouldPlay("boot", "wake"), false);
  assert.strictEqual(B.shouldPlay("wake", "wake"), true);
  assert.strictEqual(B.shouldPlay("off", "start"), false);
});
test("index.html loads boot.js before app.js", () => {
  const html = fs.readFileSync(__dirname + "/../app/index.html", "utf8");
  assert.ok(html.indexOf("boot.js") > 0 && html.indexOf("boot.js") < html.indexOf("app.js"));
});
