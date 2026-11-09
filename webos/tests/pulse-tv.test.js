// OmarchyTV 2.15: Pulse on the TV (vendor/pulse): Pulse 1.6's page with the TV's own hub.
const test = require("node:test");
const assert = require("node:assert");
const fs = require("fs");
const path = require("path");

const V = path.join(__dirname, "../app/vendor/pulse/");
const A = path.join(__dirname, "../../launcher/app/src/main/assets/pulse/");
global.window = global;
require(V + "pulse-tv-data.js");
const P = require(V + "pulse-tv-hub.js");

test("Pulse on the TV: one copy for LG/Samsung and Android, the TV's files in plain ES5", () => {
  const files = fs.readdirSync(V).sort();
  assert.deepStrictEqual(fs.readdirSync(A).sort(), files, "same files");
  for (const f of files) assert.ok(fs.readFileSync(V + f).equals(fs.readFileSync(A + f)), f + " identical");
  for (const f of ["index.html", "pulse.js", "pulse.css", "pulse-tv-data.js", "pulse-tv-hub.js", "pulse-tv.js", "pulse-tv-moments.js"]) assert.ok(files.includes(f), f);
  for (const f of ["pulse-tv-hub.js", "pulse-tv.js", "pulse-tv-moments.js"]) {   // webOS 4 runs Chromium 53
    const src = fs.readFileSync(V + f, "utf8").replace(/\/\/.*$/gm, "").replace(/\/\*[\s\S]*?\*\//g, "");
    assert.ok(!/=>|\blet\s|\bconst\s|`|\?\.|\?\?|\binset:/.test(src), f + " is ES5 and old-CSS");
  }
  const html = fs.readFileSync(V + "index.html", "utf8");
  const order = ["pulse-tv-data.js", "pulse-tv-hub.js", "pulse-tv.js", "pulse.js", "pulse-tv-moments.js"].map((s) => html.indexOf('src="' + s + '"'));
  assert.ok(order.every((i, k) => i > 0 && (k === 0 || i > order[k - 1])), "the TV's hub loads before pulse.js, the moments after");
  assert.ok(/CaskaydiaMonoNerdFontMono-Regular\.ttf[\s\S]*caskaydia_mono_regular\.ttf/.test(html), "the bundled font on both platforms");
});

test("the TV's sky: the Sun, the Moon, the planets and the stars from the PC hub's formulas", () => {
  // 21 June 2026, 11:27 UTC: solar noon over Alessandria (8.61° E): the Sun due south, 90 - 44.91 + 23.44 = 68.5° high
  const n = P.nightSky(44.91, 8.61, Date.UTC(2026, 5, 21, 11, 27) / 1000);
  assert.ok(Math.abs(n.sun.el - 68.5) < 0.6, "sun el " + n.sun.el);
  assert.ok(Math.abs(n.sun.az - 180) < 2, "sun az " + n.sun.az);
  assert.ok(n.moon.lit >= 0 && n.moon.lit <= 1 && typeof n.moon.name === "string");
  assert.ok(n.stars.length > 10 && n.stars.every((s) => s.el > -2));
  assert.ok(n.lines.every((l) => l[0] < n.stars.length && l[1] < n.stars.length));
  // Polaris stays at the latitude's height
  const pol = P.nightSky(44.91, 8.61, 1794243552).stars.find((s) => s.n === "Polaris");
  assert.ok(Math.abs(pol.el - 44.9) < 1.2, "Polaris " + pol.el);
  assert.strictEqual(P.cityKey("Alessandria"), "alessandria");
  assert.strictEqual(P.cityKey("Torino"), "turin");
  assert.strictEqual(P.cityKey("Fubine Monferrato"), "");
});

test("planes, launches and the Kp index read like the hub's", () => {
  const pl = P.planesOver(44.91, 8.61, [
    { hex: "abc123", flight: "az123 ", lat: 45.0, lon: 8.7, alt: 34000, gs: 450, track: 90, type: "a320" },
    { hex: "low", flight: "X", lat: 46.5, lon: 8.6, alt: 3000 },   // far and low: on the horizon, left out
    { hex: "gnd", flight: "G", lat: 44.91, lon: 8.61, alt: 0, ground: true }]);
  assert.strictEqual(pl.length, 1);
  assert.strictEqual(pl[0].flight, "AZ123");
  assert.strictEqual(pl[0].type, "A320");
  assert.ok(pl[0].el > 10);
  const L = P.parseLaunch({ id: "x", name: "Falcon 9 Block 5 | Starlink 12-9", net: "2026-11-10T03:00:00Z", status: { abbrev: "Go" } });
  assert.deepStrictEqual([L.rocket, L.mission, L.status], ["Falcon 9", "Starlink 12-9", "Go"]);
  assert.strictEqual(P.countdown(3725), "T-1h 02m");
  assert.strictEqual(P.countdown(-1), "liftoff");
  assert.strictEqual(P.latestKp([["time_tag", "Kp"], ["x", "3.33"]]), 3.33);
});

function hubAt(t, extra) {
  const answers = extra.answers || {};
  const net = (u) => {
    for (const k of Object.keys(answers)) if (u.indexOf(k) >= 0) return Promise.resolve(answers[k]);
    return Promise.reject(new Error("offline"));
  };
  return P.create(Object.assign({ lat: 44.91, lon: 8.61, city: "Alessandria", theme: { name: "nord", colors: { accent: "#88c0d0" } } }, extra.cfg || {}), net, () => t.v);
}

test("the state the page reads: TV only, then with the PC's Pulse on top", async () => {
  const t = { v: 1794243552 };
  const h = hubAt(t, { cfg: { tv: () => ({ cpu: 12, ram: 40, rx: 1000, tx: 10, host: "TV" }) } });
  h._tick.tv(); h._tick.night();
  let st = h.state();
  assert.strictEqual(st.v, "1.6.0-tv");
  assert.strictEqual(st.ui.scene, "sphere", "the sphere is the default scene");
  assert.strictEqual(st.ui.skyline, "alessandria");
  assert.strictEqual(st.ui.system, true);
  assert.strictEqual(st.sys.host, "TV");
  assert.ok(st.night && st.night.sun);
  assert.deepStrictEqual(st.agents, []);
  // without the TV's numbers (LG without root): no system panel rather than empty bars
  const h2 = hubAt(t, {});
  h2._tick.tv();
  assert.strictEqual(h2.state().ui.system, false);
  // the PC paired: its agents, panels and planes; the TV keeps its own sky
  const pcSt = { v: "1.6.0", agents: [{ name: "claude", state: "working" }], tower: [{ id: 1 }], widgets: [{ id: "github", type: "list" }],
    sys: { cpu: 30, host: "omarchy" }, sky: [{ flight: "PC1", az: 1, el: 50 }], starlink: { sats: [{ az: 1, el: 20 }], next: null }, ui: { ivy: true } };
  const h3 = hubAt(t, { cfg: { pc: () => Promise.resolve(pcSt), tv: () => ({ cpu: 5 }) } });
  h3._tick.night(); h3._tick.tv(); h3._tick.pc();
  await new Promise((r) => setImmediate(r));
  st = h3.state();
  assert.strictEqual(st.agents[0].name, "claude");
  assert.strictEqual(st.tower.length, 1);
  assert.strictEqual(st.sys.host, "omarchy", "the PC's numbers in the system panel");
  assert.strictEqual(st.sky[0].flight, "PC1", "the PC's planes when the TV can't read adsb.lol (LG)");
  assert.strictEqual(st.starlink.sats.length, 1);
  assert.strictEqual(st.ui.ivy, true);
  assert.ok(st.night, "the TV's own sky stays");
});

test("launch: the countdown takes the screen at T-10 min (Go only), then liftoff as Pulse's own effect", async () => {
  const t = { v: 1800000000 };
  const iso = (s) => new Date(s * 1000).toISOString();
  const h = hubAt(t, { answers: { thespacedevs: { results: [{ id: "a", name: "Falcon 9 | Starlink 1", net: iso(t.v + 900), status: { abbrev: "Go" } },
    { id: "b", name: "Starship | Flight 13", net: iso(t.v + 120), status: { abbrev: "TBC" } }] } } });
  const fx = [];
  h.onFx((f) => fx.push(f));
  h._tick.launches();
  await new Promise((r) => setImmediate(r));
  h._tick.launchTick();
  assert.deepStrictEqual(h.events().filter((e) => e.kind === "launch"), [], "T-2 but not Go: nothing; Falcon at T-15: not yet");
  t.v += 330;   // T-9:30 for the Falcon
  h._tick.launchTick();
  const e = h.events().find((x) => x.kind === "launch");
  assert.ok(e && e.rocket === "Falcon 9" && Math.round(e.in) === 570, JSON.stringify(e));
  t.v += 571;
  h._tick.launchTick();
  assert.deepStrictEqual(fx.map((f) => f.kind), ["launch"]);
  assert.ok(/Falcon 9 · Starlink 1 · liftoff/.test(fx[0].text));
  h._tick.launchTick();
  assert.strictEqual(fx.length, 1, "liftoff once");
  assert.ok(h.state().widgets.some((w) => w.id === "launches"), "the launches in the ticker");
});

test("look up: the ISS only at night while the Sun lights it, three minutes before it rises", async () => {
  // a night in November over Alessandria (the Sun far below the horizon)
  const t = { v: 1794268800 };
  const track = [];
  for (let i = 0; i < 10; i++) track.push({ timestamp: t.v + 60 * i, latitude: 44.0 + i * 0.2, longitude: 8.0 + i * 0.1, altitude: 420, velocity: 27600,
    visibility: i < 2 ? "eclipsed" : "daylight" });
  track[0].latitude = 30; track[1].latitude = 33;   // still far away (low) for the first two minutes
  const h = hubAt(t, { answers: { wheretheiss: track } });
  h._tick.night();
  assert.ok(h.state().night.sun.el < -6, "dark");
  h._tick.iss();
  await new Promise((r) => setImmediate(r));
  h._tick.iss();
  const e = h.events().find((x) => x.kind === "iss");
  assert.ok(e, "announced before it rises");
  assert.strictEqual(e.phase, "soon");
  assert.ok(e.in > 0 && e.in <= 180 && e.el > 10, JSON.stringify(e));
  // by day: never
  const day = { v: Date.UTC(2026, 5, 21, 11, 10) / 1000 };
  const hd = hubAt(day, { answers: { wheretheiss: track.map((p, i) => Object.assign({}, p, { timestamp: day.v + 60 * i })) } });
  hd._tick.night(); hd._tick.iss();
  await new Promise((r) => setImmediate(r));
  hd._tick.iss();
  assert.deepStrictEqual(hd.events().filter((x) => x.kind === "iss"), []);
});

test("the page's bridge: /api/state and /api/live answered on the TV, nothing reaches a PC hub", () => {
  const src = fs.readFileSync(V + "pulse-tv.js", "utf8");
  assert.ok(/u\.indexOf\("\/api\/state"\) === 0\) return Promise\.resolve\(resp\(hub\.state\(\)\)\)/.test(src));
  assert.ok(/root\.EventSource = function/.test(src), "effects as server-sent events");
  assert.ok(/d\.pulseTV === "pc"/.test(src), "LG/Samsung post the PC's Pulse into the frame");
  assert.ok(/host\.pcPulse/.test(src) && /host\.tvStats/.test(src) && /host\.pulse\(\)/.test(src), "Android's OmarchySaver bridge");
  const pj = fs.readFileSync(V + "pulse.js", "utf8");
  assert.ok(/getJSON\("\/api\/state"\)/.test(pj) && /new EventSource\("\/api\/live/.test(pj), "pulse.js as Pulse 1.6 ships it");
});
