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
  assert.ok(/OmarchyRacing\.pulseRows/.test(APP) && /c\.racing = /.test(APP), "pulseUrl passes the rows");
  assert.ok(/label: "racing"/.test(APP) && /racing countdown in the bar/.test(APP), "Settings › Home widgets");
  assert.ok(/racing panel on Pulse/.test(APP), "Settings › Screensaver");
  assert.ok(/id="race-box"/.test(HTML) && /id="bar-race"/.test(HTML) && /src="racing.js"/.test(HTML), "the Home panel and the bar");
  const line = APP.split("\n").find((l) => /var sc = \["sphere"/.test(l));
  assert.ok(line && !/"mars"/.test(line) && !/"monolith"/.test(line), "mars and monolith stay off on LG");
  assert.ok(/var LG_SCENES = \{[^}]*noir: 1/.test(APP) && /scenes: false/.test(APP), "a saved mars still shows the sphere");
});
