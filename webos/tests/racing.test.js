// node --test webos/tests/racing.test.js — Grant Charge's racing calendars (#123) on LG/Samsung
const test = require("node:test");
const assert = require("node:assert");
const fs = require("fs");
const path = require("path");
const Racing = require("../app/racing.js");

const ASSET = path.join(__dirname, "../../launcher/app/src/main/assets/racing_" + Racing.YEAR + ".json");
const FIX = path.join(__dirname, "../../launcher/app/src/test/resources/racing");
const APP = fs.readFileSync(path.join(__dirname, "../app/app.js"), "utf8");
const HTML = fs.readFileSync(path.join(__dirname, "../app/index.html"), "utf8");

function asset() { return JSON.parse(fs.readFileSync(ASSET, "utf8")); }
function fixture(name) { return fs.readFileSync(path.join(FIX, name), "utf8"); }

test("parses every offset form", () => {
  const t = Racing.utc("2026-11-22T04:00:00Z");
  assert.strictEqual(t, Racing.utc("2026-11-21T20:00:00-08:00"));
  assert.strictEqual(t, Racing.utc("2026-11-21T20:00:00-0800"));
  assert.strictEqual(t, Racing.utc("2026-11-22T05:00:00+01:00"));
  assert.strictEqual(-1, Racing.utc("tomorrow"));
  assert.strictEqual("2026-11-22T04:00:00Z", Racing.iso(t));
});

test("thai time is 24 hour and crosses midnight", () => {
  assert.strictEqual(Racing.when(Racing.utc("2026-11-21T20:00:00-08:00"), "Asia/Bangkok"), "sun 22 nov 11:00");
  assert.strictEqual(Racing.when(Racing.utc("2026-11-08T17:00:00Z"), "Asia/Bangkok"), "mon 9 nov 00:00");
  assert.strictEqual(Racing.zone("Not/AZone"), Racing.zone(""));
  assert.strictEqual(Racing.zone(""), Racing.zone(""));
});

test("countdown clock", () => {
  assert.strictEqual(Racing.clock(-5), "00:00:00");
  assert.strictEqual(Racing.clock(65_000), "00:01:05");
  assert.strictEqual(Racing.clock(86_399_000), "23:59:59");
  assert.strictEqual(Racing.clock(86_400_000), "1d 00:00:00");
  assert.strictEqual(Racing.clock(((12 * 24 + 4) * 3600 + 12 * 60 + 9) * 1000), "12d 04:12:09");
  assert.strictEqual(Racing.clockShort(-5), "00m 00s");
  assert.strictEqual(Racing.clockShort(65_000), "01m 05s");
  assert.strictEqual(Racing.clockShort(86_399_000), "23h 59m");
  assert.strictEqual(Racing.clockShort(86_400_000), "1d 00h");
  assert.strictEqual(Racing.clockShort(((12 * 24 + 4) * 3600 + 12 * 60 + 9) * 1000), "12d 04h");
});

test("bundled calendar only shows what is left", () => {
  const a = asset();
  assert.strictEqual(a.year, Racing.YEAR);
  const f1 = Racing.parse(a.f1), mgp = Racing.parse(a.motogp);
  assert.ok(f1.length > 20);
  assert.ok(mgp.length > 20);
  const now = Racing.utc("2026-11-07T19:25:00+07:00");
  const f1Left = Racing.upcoming(f1, now), mgpLeft = Racing.upcoming(mgp, now);
  assert.strictEqual(f1Left[0].name, "São Paulo GP");
  assert.strictEqual(f1Left.length, 4);
  assert.strictEqual(f1Left[3].name, "Abu Dhabi GP");
  assert.strictEqual(mgpLeft[0].name, "Qatar GP");
  assert.strictEqual(mgpLeft.length, 3);
  assert.strictEqual(mgpLeft[2].name, "Valencia GP");
  f1Left.forEach((m) => assert.strictEqual(Racing.race(m).code, "RACE"));
  const n = Racing.soonest(f1, mgp, now);
  assert.ok(n);
  assert.ok(n.session.start >= now || n.session.live(now));
  const after = Racing.utc("2026-12-31T00:00:00Z");
  assert.strictEqual(Racing.upcoming(f1, after).length, 0);
  assert.strictEqual(Racing.soonest(f1, mgp, after), null);
});

test("running session wins the bar", () => {
  const f1 = Racing.parse(asset().f1);
  const vegas = f1.find((m) => m.name.startsWith("Las Vegas"));
  assert.ok(vegas);
  const race = Racing.race(vegas);
  const during = race.start + 10 * 60_000;
  assert.ok(race.live(during));
  const n = Racing.soonest(f1, Racing.parse([]), during);
  assert.strictEqual(n.session.start, race.start);
});

test("MotoGP feed keeps the MotoGP class and drops cancelled", () => {
  const events = JSON.parse("[{\"kind\":\"GP\",\"id\":\"x\",\"name\":\"QATAR AIRWAYS GRAND PRIX OF QATAR \",\"broadcasts\":["
    + "{\"type\":\"SESSION\",\"shortname\":\"FP1\",\"name\":\"Free Practice Nr. 1\",\"status\":\"FINISHED\",\"date_start\":\"2026-11-06T14:45:00+0300\",\"date_end\":\"2026-11-06T15:30:00+0300\",\"category\":{\"acronym\":\"MGP\"}},"
    + "{\"type\":\"SESSION\",\"shortname\":\"FP1\",\"name\":\"Free Practice Nr. 1\",\"status\":\"FINISHED\",\"date_start\":\"2026-11-06T13:00:00+0300\",\"date_end\":\"2026-11-06T13:35:00+0300\",\"category\":{\"acronym\":\"MT3\"}},"
    + "{\"type\":\"SESSION\",\"shortname\":\"WUP\",\"name\":\"Warm Up\",\"status\":\"NOT-STARTED\",\"date_start\":\"2026-11-08T12:40:00+0300\",\"date_end\":\"2026-11-08T12:50:00+0300\",\"category\":{\"acronym\":\"MGP\"}},"
    + "{\"type\":\"SESSION\",\"shortname\":\"RAC\",\"name\":\"Grand Prix\",\"status\":\"NOT-STARTED\",\"date_start\":\"2026-11-08T19:00:00+0300\",\"date_end\":\"2026-11-08T19:00:00+0300\",\"category\":{\"acronym\":\"MGP\"}}]},"
    + "{\"kind\":\"GP\",\"id\":\"y\",\"name\":\"RED BULL GRAND PRIX OF AUSTRALIA\",\"broadcasts\":["
    + "{\"type\":\"SESSION\",\"shortname\":\"RAC\",\"name\":\"Grand Prix - Cancelled\",\"status\":\"CANCELLED\",\"date_start\":\"2026-10-25T16:30:00+1100\",\"category\":{\"acronym\":\"MGP\"}}]},"
    + "{\"kind\":\"TEST\",\"id\":\"z\",\"name\":\"VALENCIA TEST\",\"broadcasts\":[]}]");
  const m = Racing.parse(Racing.parseMotoGp(events));
  assert.strictEqual(m.length, 1);
  assert.strictEqual(m[0].name, "Qatar GP");
  assert.strictEqual(m[0].sessions.length, 2);
  assert.strictEqual(m[0].sessions[0].code, "FP1");
  assert.strictEqual(m[0].sessions[1].code, "RACE");
  assert.strictEqual(m[0].sessions[1].start, Racing.utc("2026-11-08T16:00:00Z"));
  assert.strictEqual(m[0].sessions[1].end, 0);
});

test("F1 page sessions are read and deduplicated", () => {
  const s = "{\\\"session\\\":\\\"r\\\",\\\"shortName\\\":\\\"R\\\",\\\"description\\\":\\\"Race\\\",\\\"startTime\\\":\\\"2026-11-21T20:00:00\\\","
    + "\\\"endTime\\\":\\\"2026-11-21T22:00:00\\\",\\\"gmtOffset\\\":\\\"-08:00\\\",\\\"state\\\":\\\"upcoming\\\",\\\"sessionType\\\":\\\"Race\\\"}";
  const q = "{\\\"session\\\":\\\"q\\\",\\\"shortName\\\":\\\"Q\\\",\\\"description\\\":\\\"Qualifying\\\",\\\"startTime\\\":\\\"2026-11-20T20:00:00\\\","
    + "\\\"endTime\\\":\\\"2026-11-20T21:00:00\\\",\\\"gmtOffset\\\":\\\"-08:00\\\",\\\"state\\\":\\\"upcoming\\\",\\\"sessionType\\\":\\\"Qualifying\\\"}";
  const a = Racing.parseF1Page("<script>self.__next_f.push([1,\"" + s + "," + q + "," + s + "\"])</script>");
  assert.strictEqual(a.length, 2);
  assert.strictEqual(a[0].code, "QUALI");
  assert.strictEqual(a[1].code, "RACE");
  assert.strictEqual(a[1].start, "2026-11-22T04:00:00Z");
});

test("MotoGP API gives each class its own weekend", () => {
  const ev = JSON.parse(fixture("motogp-event.json"));
  for (const cls of ["MGP", "MT2", "MT3"]) {
    const a = Racing.parseMotoGp(ev, cls);
    assert.strictEqual(a.length, 1, cls);
    const ss = a[0].sessions;
    assert.ok(ss.length >= 3, cls);
    assert.strictEqual(ss[ss.length - 1].code, "RACE", cls);
  }
  assert.notStrictEqual(JSON.stringify(Racing.parseMotoGp(ev, "MGP")), JSON.stringify(Racing.parseMotoGp(ev, "MT3")));
});

test("NASCAR keeps practice, qualifying and the race only", () => {
  const d = JSON.parse(fixture("nascar.json"));
  for (const k of ["series_1", "series_2", "series_3"]) {
    const a = Racing.parseNascar(d[k]);
    assert.strictEqual(a.length, 2, k);
    for (const meet of a) {
      for (const s of meet.sessions) assert.ok(/^(P\d|QUALI|RACE)$/.test(s.code));
      assert.strictEqual(meet.sessions[meet.sessions.length - 1].code, "RACE");
      assert.ok(meet.name);
    }
  }
  const m = Racing.parse(Racing.parseNascar(d.series_1));
  assert.strictEqual(m[m.length - 1].name, "Homestead-Miami Speedway");
});

test("Formula E days without times", () => {
  const a = Racing.parseFePage(fixture("fe.html"));
  assert.ok(a.length >= 15);
  assert.strictEqual(a[0].name, "Jeddah E-Prix");
  const m = Racing.parse(a);
  assert.ok(m[0].sessions[0].dateOnly);
  assert.strictEqual(Racing.day(m[0].sessions[0].start), "fri 18 dec");
  assert.strictEqual(Racing.soonest({ fe: m }, m[0].sessions[0].start - 3600_000), null);
});

test("WEC rounds from the official site", () => {
  const a = Racing.parseWecPage(fixture("wec.html"));
  assert.strictEqual(a.length, 9);
  assert.strictEqual(a[0].name, "Qatar 1812km");
  assert.strictEqual(a[4].name, "24 Hours of Le Mans");
  assert.strictEqual(Racing.wecName("totalenergies-6-hours-of-spa-francorchamps-2027"), "6 Hours of Spa Francorchamps");
  assert.strictEqual(a[0].sessions[0].code, "FP1");
});

test("every series has a label and unknown ones are refused", () => {
  assert.ok(Racing.SERIES.length >= 9);
  Racing.SERIES.forEach((s) => assert.ok(Racing.known(s[0]) && Racing.label(s[0])));
  assert.ok(!Racing.known("drag-racing"));
  assert.strictEqual(Racing.label("nascar"), "NASCAR Cup");
});

test("Pulse rows: next session and the race, times in seconds", () => {
  const a = asset();
  const now = Racing.utc("2026-11-07T19:25:00+07:00");
  const rows = Racing.pulseRows({ f1: Racing.parse(a.f1), motogp: Racing.parse(a.motogp) }, ["f1", "motogp"], now);
  assert.ok(rows.length >= 2);
  rows.forEach((r) => {
    assert.ok(r.series && r.code && r.name && r.start > 0 && r.end > r.start);
    assert.ok(r.end * 1000 > now);
  });
});

test("2.15.1 LG and Samsung: racing toggle, pulseUrl, scene row unchanged", () => {
  assert.ok(/racing: false/.test(APP) && /widgets: \{[^}]*racing: false/.test(APP), "racing is off unless asked");
  assert.ok(/racingBar: false/.test(APP), "the bar countdown is off unless asked");
  assert.ok(/OmarchyRacing\.pulseRows/.test(APP) && /c\.racing = /.test(APP), "pulseCfg passes the rows");
  assert.ok(/function pulseCfg\(\)/.test(APP) && /window\.OmarchySaver\.pulse/.test(APP) && /pulseTV: "cfg"/.test(APP),
    "the screensaver host and a postMessage carry racing, not only the URL hash");
  assert.ok(/label: "racing"/.test(APP) && /racing countdown in the bar/.test(APP), "Settings › Home widgets");
  assert.ok(/racing panel on Pulse/.test(APP), "Settings › Screensaver");
  assert.ok(/id="race-box"/.test(HTML) && /id="bar-race"/.test(HTML) && /src="racing.js"/.test(HTML), "the Home panel and the bar");
  const line = APP.split("\n").find((l) => /var sc = \["sphere"/.test(l));
  assert.ok(line, "the Pulse scene row is still there");
});

test("upcoming meetings are sorted by start time", () => {
  const now = Racing.utc("2026-11-10T00:00:00Z");
  const raw = [
    { slug: "valencia", name: "Valencia GP", sessions: [{ code: "RACE", name: "Race", start: "2026-11-29T13:00:00Z", end: null }] },
    { slug: "portugal", name: "Portugal GP", sessions: [
      { code: "FP1", name: "Free Practice 1", start: "2026-11-20T10:45:00Z", end: "2026-11-20T11:30:00Z" },
      { code: "RACE", name: "Race", start: "2026-11-22T13:00:00Z", end: null }
    ] },
    { slug: "extra", name: "Friday Extra", sessions: [{ code: "RACE", name: "Race", start: "2026-11-20T09:00:00Z", end: null }] }
  ];
  const parsed = Racing.parse(raw);
  const shuffled = [parsed[2], parsed[0], parsed[1]];
  const left = Racing.upcoming(shuffled, now);
  assert.deepStrictEqual(left.map((m) => m.name), ["Friday Extra", "Portugal GP", "Valencia GP"]);
  const dates = left.map((m) => Racing.when(Racing.race(m).start, "Europe/Rome"));
  assert.ok(dates[0].indexOf("20 nov") >= 0, dates[0]);
  assert.ok(dates[1].indexOf("22 nov") >= 0, dates[1]);
  assert.ok(dates[2].indexOf("29 nov") >= 0, dates[2]);
});

test("bundled calendar loads with XMLHttpRequest so file:// works on the TV", async () => {
  assert.ok(/OmarchyRacing\.getText\("racing_2026\.json"\)/.test(APP), "the page uses getText for the snapshot");
  assert.ok(!/fetch\("racing_2026\.json"\)/.test(APP), "fetch is not used for the snapshot (webOS refuses file://)");
  assert.ok(typeof Racing.getText === "function");
  const prev = global.XMLHttpRequest;
  function mockXhr(status, body) {
    return function () {
      this.readyState = 0;
      this.status = 0;
      this.responseText = "";
      this.open = function (method, url) { this.method = method; this.url = url; };
      this.send = function () {
        this.readyState = 4;
        this.status = status;
        this.responseText = body;
        if (this.onreadystatechange) this.onreadystatechange();
        if (this.onload) this.onload();
      };
    };
  }
  try {
    global.XMLHttpRequest = mockXhr(0, "{\"year\":2026}");
    const text = await Racing.getText("file:///media/internal/racing_2026.json");
    assert.strictEqual(text, "{\"year\":2026}");
    global.XMLHttpRequest = mockXhr(200, "ok");
    assert.strictEqual(await Racing.getText("racing_2026.json"), "ok");
    global.XMLHttpRequest = mockXhr(0, "");
    await assert.rejects(() => Racing.getText("file:///missing.json"));
    global.XMLHttpRequest = mockXhr(404, "no");
    await assert.rejects(() => Racing.getText("racing_2026.json"));
  } finally {
    global.XMLHttpRequest = prev;
  }
});

test("the racing panel fits the other Home widgets", () => {
  assert.ok(!/#race-body\s*\{[^}]*position:\s*absolute/.test(HTML), "the body stays in flow so the box is as tall as its lines");
  assert.ok(!/#race-box\s*\{[^}]*overflow:\s*hidden/.test(HTML), "the box is not clipped out of flow");
  assert.ok(/text-overflow:\s*ellipsis/.test(HTML), "long names ellipsize");
  assert.ok(/clockShort/.test(APP), "the Home countdown is the short form");
  assert.ok(/function raceSeriesLine/.test(APP) && /extraWeekend && left\[1\]/.test(APP),
    "one line per series, plus the next weekend only when a single series is on");
});

test("Pulse on the TV shows the Home widget's series from the bundled snapshot", async () => {
  assert.ok(/pulseRacing === true \|\| cfg\.pulseRacing === false/.test(APP) && /return !!cfg\.widgets\.racing/.test(APP),
    "Pulse racing follows the Home widget unless the screensaver toggle is set");
  assert.ok(/pulseRows\(racingAll\(Date\.now\(\)\), racingSeries\(\)/.test(APP), "pulseUrl uses the Home series list");
  assert.ok(/if \(rows\.length\) c\.racing = rows/.test(APP), "non-empty rows reach Pulse as c.racing");
  const tvjs = fs.readFileSync(path.join(__dirname, "../app/vendor/pulse/pulse-tv.js"), "utf8");
  assert.ok(/racing: cfg\.racing/.test(tvjs), "the TV bridge hands c.racing to the hub");
  assert.ok(/root\.parent !== root && root\.parent\.OmarchySaver/.test(tvjs), "the iframe reads the parent's OmarchySaver");
  assert.ok(/d\.pulseTV === "cfg"/.test(tvjs) && /hubCfg\.racing = d\.racing/.test(tvjs), "a postMessage updates the hub's racing rows");
  assert.ok(/!ownHost/.test(tvjs), "the scene-frame sandbox patch still runs on LG/Samsung when the host is the parent");

  const prev = global.XMLHttpRequest;
  const body = fs.readFileSync(ASSET, "utf8");
  global.XMLHttpRequest = function () {
    this.readyState = 0; this.status = 0; this.responseText = "";
    this.open = function (method, url) { this.method = method; this.url = url; };
    this.send = function () {
      this.readyState = 4; this.status = 0; this.responseText = body;
      if (this.onreadystatechange) this.onreadystatechange();
      if (this.onload) this.onload();
    };
  };
  try {
    const text = await Racing.getText("file:///media/internal/racing_2026.json");
    const asset = JSON.parse(text);
    assert.ok(asset.f1 && asset.motogp, "the snapshot has F1 and MotoGP");
    const now = Racing.utc("2026-11-10T12:00:00Z");
    const allOf = (series) => {
      const all = {};
      series.forEach((id) => { all[id] = Racing.meetingsFrom(asset, null, id); });
      return all;
    };
    const home = ["f1", "motogp"];
    const rows = Racing.pulseRows(allOf(home), home, now);
    assert.ok(rows.length >= 2, "rows exist from the snapshot with no live fetch");
    assert.deepStrictEqual([...new Set(rows.map((r) => r.series))].sort(), ["F1", "MotoGP"]);

    if (!global.window) global.window = global;
    require("../app/vendor/pulse/pulse-tv-data.js");
    const PulseTV = require("../app/vendor/pulse/pulse-tv-hub.js");
    const net = () => Promise.resolve(null);
    const clock = () => now / 1000;
    const st = PulseTV.create({ lat: 44.91, lon: 8.61, racing: rows }, net, clock).state();
    const panel = st.widgets.filter((w) => w.id === "tv-racing");
    assert.strictEqual(panel.length, 1, "the hub renders the racing side panel on webOS");
    assert.strictEqual(panel[0].slot, "right");
    assert.strictEqual(panel[0].order, 21);
    assert.ok(panel[0].data.items.length, "the panel has sessions");
    assert.ok(panel[0].data.items.some((i) => /^F1/.test(i.title)));
    assert.ok(panel[0].data.items.some((i) => /^MotoGP/.test(i.title)));

    const motoOnly = Racing.pulseRows(allOf(["motogp"]), ["motogp"], now);
    assert.ok(motoOnly.length && motoOnly.every((r) => r.series === "MotoGP"));
    const p2 = PulseTV.create({ lat: 44.91, lon: 8.61, racing: motoOnly }, net, clock).state()
      .widgets.find((w) => w.id === "tv-racing");
    assert.ok(p2 && p2.data.items.every((i) => /^MotoGP/.test(i.title)));
    assert.ok(!p2.data.items.some((i) => /^F1/.test(i.title)), "only the series picked on Home");
  } finally {
    global.XMLHttpRequest = prev;
  }
});
