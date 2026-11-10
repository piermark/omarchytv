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

test("the TV's own panels with no PC: the sky, space, planes, the air, earthquakes, Omarchy; the PC's panels and colours when paired", async () => {
  const air = P.airFrom({ current: { european_aqi: 47.4, grass_pollen: 31, birch_pollen: 2 } });
  assert.deepStrictEqual(air, { aqi: 47, level: "moderate", pollen: "grass", pollenLevel: "moderate" });
  assert.strictEqual(P.airFrom({ current: { european_aqi: 12 } }).pollen, "");
  assert.strictEqual(P.airFrom(null), null);
  const q = P.quakesFrom({ features: [
    { properties: { place: "far away", mag: 6.8, time: 1e12 }, geometry: { coordinates: [140, 35] } },
    { properties: { place: "near", mag: 4.6, time: 1e12 }, geometry: { coordinates: [9.2, 45.4] } }] }, 44.91, 8.61);
  assert.strictEqual(q[0].title, "near", "a quake within 1000 km first");
  assert.ok(q[0].km < 100 && q[1].level === "crit" && q[1].right === "M6.8");
  const t = { v: 1794268800 };
  const h = hubAt(t, { answers: {
    "air-quality": { current: { european_aqi: 18 } },
    "earthquake.usgs.gov": { features: [{ properties: { place: "Off the coast", mag: 5.1, time: 1794260000000 }, geometry: { coordinates: [10, 40] } }] },
    "api.github.com": [{ tag_name: "v3.2.0", name: "Hyprland 0.52", published_at: "2026-11-01T10:00:00Z" }, { tag_name: "v3.3.0-rc", draft: true }] } });
  h._tick.night(); h._tick.air(); h._tick.quakes(); h._tick.omarchy();
  await new Promise((r) => setImmediate(r));
  let st = h.state();
  const ids = st.widgets.map((w) => w.id);
  ["tv-sky", "tv-space", "tv-planes", "tv-omarchy"].forEach((id) => assert.ok(ids.includes(id), id));
  assert.ok(!ids.includes("tv-quakes"), "fork: no earthquakes panel");
  const pp = st.widgets.find((w) => w.id === "tv-planes");
  assert.deepStrictEqual([pp.title, pp.order, pp.data.items.length, pp.data.note], ["planes · 200 nm", 30, 0, "looking for planes…"], "fork: the planes panel all the time");
  assert.strictEqual(st.air.level, "good", "the air row under the weather and the haze over the city");
  assert.ok(st.widgets.find((w) => w.id === "tv-sky").data.rows.some((r) => r[0] === "moon"));
  assert.deepStrictEqual(st.widgets.find((w) => w.id === "tv-omarchy").data.items.map((i) => i.title), ["Omarchy 3.2.0"]);
  // paired: the PC's panels and its theme
  const pcSt = { v: "1.6.0", widgets: [{ id: "github", type: "list" }], theme: { name: "kanagawa", colors: { accent: "#7e9cd8" } } };
  const h2 = hubAt(t, { cfg: { pc: () => Promise.resolve(pcSt) }, answers: { "earthquake.usgs.gov": { features: [] } } });
  h2._tick.night(); h2._tick.pc();
  await new Promise((r) => setImmediate(r));
  st = h2.state();
  assert.deepStrictEqual(st.widgets.map((w) => w.id), ["github"]);
  assert.strictEqual(st.theme.name, "kanagawa", "the same colours as on the PC");
});

test("planes: real altitude in the panel, plain type names, and a stale route is not shown", async () => {
  assert.strictEqual(P.typeName("a20n"), "A320neo");
  assert.strictEqual(P.typeName("B77W"), "777-300ER");
  assert.strictEqual(P.typeName("B38M"), "737 MAX 8");
  assert.strictEqual(P.typeName("ZZ99"), "ZZ99", "an unknown code stays as it is");
  assert.strictEqual(P.ftText(37975), "38,000 ft");
  assert.strictEqual(P.ftText(4250), "4,300 ft");
  // KUL -> CCU passes over Phuket heading north-west; the same flight number heading south-east is not on it
  const kulCcu = { from: "KUL", to: "CCU", oLat: 2.7456, oLon: 101.71, dLat: 22.6547, dLon: 88.4467 };
  assert.ok(P.routeFits(kulCcu, 8.0, 98.3, 320));
  assert.ok(!P.routeFits(kulCcu, 8.0, 98.3, 140));
  assert.ok(!P.routeFits({ oLat: 51.47, oLon: -0.45, dLat: 40.64, dLon: -73.78 }, 45.0, 8.7, 150), "LHR -> JFK over Italy");
  assert.ok(P.routeFits({ from: "X", to: "Y" }, 45, 8, 0), "no airport positions: can't tell, kept");

  const t = { v: 1794243552 };
  const ap = (iata, lat, lon, city) => ({ iata_code: iata, latitude: lat, longitude: lon, municipality: city });
  const h = hubAt(t, { answers: {
    "adsb.lol": { ac: [{ hex: "abc123", flight: "AZ123 ", lat: 45.0, lon: 8.7, alt_baro: 34000, gs: 450, track: 150, t: "A20N" },
      { hex: "def456", flight: "BA117 ", lat: 44.95, lon: 8.65, alt_baro: 36000, gs: 480, track: 150, t: "B77W" }] },
    "callsign/AZ123": { response: { flightroute: { origin: ap("MXP", 45.63, 8.72, "Milan"), destination: ap("FCO", 41.80, 12.25, "Rome"), airline: { name: "ITA Airways" } } } },
    "callsign/BA117": { response: { flightroute: { origin: ap("LHR", 51.47, -0.45, "London"), destination: ap("JFK", 40.64, -73.78, "New York"), airline: { name: "British Airways" } } } } } });
  h._tick.sky();
  await new Promise((r) => setImmediate(r));
  h._tick.route(); h._tick.route();
  await new Promise((r) => setImmediate(r));
  h._tick.sky();
  await new Promise((r) => setImmediate(r));
  const sky = h.state().sky;
  const az = sky.find((p) => p.flight === "AZ123"), ba = sky.find((p) => p.flight === "BA117");
  assert.deepStrictEqual([az.type, az.from, az.to, az.ft], ["A320neo", "MXP", "FCO", 34000]);
  assert.ok(!ba.from && !ba.to, "LHR -> JFK is not where BA117 is: no route");
  assert.strictEqual(ba.airline, "British Airways", "the airline (from the callsign) still shows");
  const panel = h.state().widgets.find((w) => w.id === "tv-planes");
  const rows = panel.data.items;
  assert.deepStrictEqual(rows, [{ title: "BA117 · 777-300ER", text: "British Airways · 3 nm", right: "36,000 ft" },
    { title: "AZ123 · A320neo", text: "MXP → FCO · 7 nm", right: "34,000 ft" }], "fork: nearest first, with the distance");
});

test("fork: the planes panel looks 200 nm out, nearest five, low and far planes included", async () => {
  const t = { v: 1794243552 };
  const ac = [];
  for (let i = 0; i < 8; i++) ac.push({ hex: "h" + i, flight: "F" + i, lat: 44.91 + i * 0.4, lon: 8.61, alt_baro: 3000 + i * 1000, track: 0, t: "B738" });
  ac.push({ hex: "far", flight: "FAR", lat: 49.0, lon: 8.61, alt_baro: 39000 });   // ~245 nm: out of range
  ac.push({ hex: "gnd", flight: "GND", lat: 44.92, lon: 8.61, alt_baro: "ground" });
  let asked = "";
  const net = (u) => { if (u.indexOf("adsb.lol") >= 0) { asked = u; return Promise.resolve({ ac }); } return Promise.reject(new Error("offline")); };
  const h = P.create({ lat: 44.91, lon: 8.61, city: "Alessandria" }, net, () => t.v);
  h._tick.sky();
  await new Promise((r) => setImmediate(r));
  assert.ok(/\/dist\/200$/.test(asked), asked);
  const items = h.state().widgets.find((w) => w.id === "tv-planes").data.items;
  assert.deepStrictEqual(items.map((i) => i.title), ["F0 · 737-800", "F1 · 737-800", "F2 · 737-800", "F3 · 737-800", "F4 · 737-800"]);
  assert.strictEqual(items[0].right, "3,000 ft");
  assert.strictEqual(items[4].text, "96 nm");
});

test("fork: OpenSky fills in the planes adsb.lol doesn't see, moved on to now, with their type from adsbdb", async () => {
  // OpenSky state vectors: [icao24, callsign, country, time_position, last_contact, lon, lat, baro_alt_m, on_ground, velocity_ms, track, ...]
  const os = P.openskyStates([["8851EC", "THA222  ", "Thailand", 1000, 1000, 8.61, 45.5, 10000, false, 250, 0],
    ["abc123", "AZ123", "Italy", 1000, 1000, 8.7, 45.0, 10000, false, 200, 90],
    ["gone", "OLD1", "X", 1, 1, 8.6, 45.1, 9000, false, 200, 0]]);
  assert.strictEqual(os[0].hex, "8851ec");
  assert.strictEqual(Math.round(os[0].alt), 32808, "m -> ft");
  assert.strictEqual(Math.round(os[0].gs), 486, "m/s -> kt");
  const m = P.mergePlanes([{ hex: "abc123", flight: "AZ123", lat: 45.0, lon: 8.7, alt: 34000, type: "A20N" }], os, 1000 + 600, { "8851ec": "A320" });
  assert.deepStrictEqual(m.map((a) => a.hex), ["abc123", "8851ec"], "adsb.lol wins a plane both see; a 26-minute-old position is dropped");
  assert.ok(Math.abs(m[1].lat - (45.5 + 486 * 600 / 3600 / 60)) < 0.01, "10 minutes on at 486 kt due north: " + m[1].lat);
  assert.strictEqual(m[1].type, "A320");

  const t = { v: 1794243552 };
  const asked = [];
  const answers = {
    "opensky-network.org": { states: [["8851ec", "THA222  ", "Thailand", t.v - 60, t.v - 60, 8.61, 45.2, 10000, false, 230, 180]] },
    "adsbdb.com/v0/aircraft/8851ec": { response: { aircraft: { icao_type: "B788" } } } };
  const net = (u) => { asked.push(u); for (const k of Object.keys(answers)) if (u.indexOf(k) >= 0) return Promise.resolve(answers[k]); return Promise.reject(new Error("offline")); };
  const h = P.create({ lat: 44.91, lon: 8.61, city: "Alessandria" }, net, () => t.v);
  h._tick.sky(); h._tick.opensky();
  await new Promise((r) => setImmediate(r));
  assert.ok(asked.some((u) => /opensky-network\.org\/api\/states\/all\?lamin=41\.58&lamax=48\.24&lomin=3\.90&lomax=13\.32/.test(u)), asked.join("\n"));
  let items = h.state().widgets.find((w) => w.id === "tv-planes").data.items;
  assert.strictEqual(items.length, 1, "adsb.lol offline: OpenSky's plane still shows");
  assert.strictEqual(items[0].title, "THA222");
  h._tick.route();   // no route to look up yet... THA222's route is queued first
  h._tick.route();   // ...then its type
  await new Promise((r) => setImmediate(r));
  items = h.state().widgets.find((w) => w.id === "tv-planes").data.items;
  assert.strictEqual(items[0].title, "THA222 · 787-8");
  assert.strictEqual(items[0].right, "32,800 ft");
});

test("fork: OpenSky every 8 minutes anonymously, every minute when the launcher is signed in", () => {
  const net = () => Promise.reject(new Error("offline"));
  assert.strictEqual(P.create({ lat: 7.78, lon: 98.33 }, net, () => 1).openskyEvery, 480000);
  assert.strictEqual(P.create({ lat: 7.78, lon: 98.33, openskyAuth: true }, net, () => 1).openskyEvery, 60000);
  const bridge = fs.readFileSync(A + "pulse-tv.js", "utf8");
  assert.ok(/openskyAuth: !!cfg\.openskyAuth/.test(bridge), "the bridge passes the flag on");
  const hub = fs.readFileSync(A + "pulse-tv-hub.js", "utf8");
  assert.ok(!/clientSecret|client_secret|Bearer/.test(hub), "no OpenSky secret or token handling in the page");
});
