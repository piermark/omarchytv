/*
 * OmarchyTV 2.15.1: F1, MotoGP and the other series on LG and Samsung (Grant Charge, #123).
 * Same calendars and rules as Android's Racing.java: official sources, no key; assets/racing_2026.json
 * (copied into this app at build) is the bundled snapshot when the TV cannot reach a source.
 * ES5, so webOS 4's Chromium 53 can run it. MIT, Marco Porro.
 */
(function (root) {
  "use strict";

  var YEAR = 2026;
  var F1 = "f1", MOTOGP = "motogp";
  var SERIES = [
    [F1, "F1"], [MOTOGP, "MotoGP"], ["moto2", "Moto2"], ["moto3", "Moto3"],
    ["nascar", "NASCAR Cup"], ["nascar2", "NASCAR O'Reilly"], ["truck", "NASCAR Trucks"],
    ["fe", "Formula E"], ["wec", "WEC"]
  ];
  var MAX_AGE_MS = 6 * 3600 * 1000, WEEKEND_AGE_MS = 3600 * 1000, DEFAULT_LENGTH_MS = 2 * 3600 * 1000;
  var MGP_API = "https://api.motogp.pulselive.com/motogp/v1/events?seasonYear=";
  var NASCAR_API = "https://cf.nascar.com/cacher/";
  var FE_PAGE = "https://www.fiaformulae.com/en/calendar";
  var WEC_PAGE = "https://www.fiawec.com/en/";
  var F1_PAGE = "https://www.formula1.com/en/racing/" + YEAR + "/";
  var DAYS = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"];
  var MONTHS = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"];
  var fetching = false;

  function label(id) {
    var i;
    for (i = 0; i < SERIES.length; i++) if (SERIES[i][0] === id) return SERIES[i][1];
    return id;
  }
  function known(id) {
    var i;
    for (i = 0; i < SERIES.length; i++) if (SERIES[i][0] === id) return true;
    return false;
  }
  function pad(n) { return n < 10 ? "0" + n : "" + n; }

  function Session(code, name, start, end) {
    this.code = code;
    this.name = name;
    this.start = start;
    this.end = end;
    this.dateOnly = false;
  }
  Session.prototype.endOrDefault = function () {
    return this.end > this.start ? this.end : this.start + DEFAULT_LENGTH_MS;
  };
  Session.prototype.live = function (now) {
    return now >= this.start && now < this.endOrDefault();
  };

  function Meeting(slug, name, sessions) {
    this.slug = slug;
    this.name = name;
    this.sessions = sessions;
  }
  Meeting.prototype.lastEnd = function () {
    return this.sessions.length ? this.sessions[this.sessions.length - 1].endOrDefault() : 0;
  };

  function utc(s) {
    if (!s || s.length < 19) return -1;
    var m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(Z|([+-]\d{2}):?(\d{2}))$/.exec(s);
    if (!m) return -1;
    var ms = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]);
    if (m[7] === "Z") return ms;
    var oh = parseInt(m[8], 10);
    var sign = oh < 0 || m[8].charAt(0) === "-" ? -1 : 1;
    return ms - (oh * 3600000 + sign * parseInt(m[9], 10) * 60000);
  }

  function iso(t) {
    var d = new Date(t);
    return d.getUTCFullYear() + "-" + pad(d.getUTCMonth() + 1) + "-" + pad(d.getUTCDate()) + "T" +
      pad(d.getUTCHours()) + ":" + pad(d.getUTCMinutes()) + ":" + pad(d.getUTCSeconds()) + "Z";
  }

  function parse(a) {
    var out = [], i, k;
    for (i = 0; a && i < a.length; i++) {
      var m = a[i], ss = m && m.sessions, sessions = [];
      if (!ss) continue;
      for (k = 0; k < ss.length; k++) {
        var s = ss[k];
        var st = s ? utc(s.start) : -1;
        if (st < 0) continue;
        var en = s.end == null || s.end === "" ? -1 : utc(s.end);
        var x = new Session(s.code || "", s.name || "", st, Math.max(en, 0));
        x.dateOnly = !!s.dateOnly;
        sessions.push(x);
      }
      if (!sessions.length) continue;
      sessions.sort(function (x, y) { return x.start - y.start; });
      out.push(new Meeting(m.slug || "", m.name || "", sessions));
    }
    out.sort(function (x, y) { return x.sessions[0].start - y.sessions[0].start; });
    return out;
  }

  function next(list, now) {
    var i;
    for (i = 0; i < list.length; i++) if (list[i].lastEnd() > now) return list[i];
    return null;
  }
  function meetingStart(m) {
    var r = race(m);
    return r && r.start ? r.start : (m.sessions[0] ? m.sessions[0].start : 0);
  }
  function upcoming(list, now) {
    var out = [], i;
    for (i = 0; i < list.length; i++) if (list[i].lastEnd() > now) out.push(list[i]);
    out.sort(function (a, b) { return meetingStart(a) - meetingStart(b); });
    return out;
  }
  function race(m) {
    var i;
    for (i = m.sessions.length - 1; i >= 0; i--) if (m.sessions[i].code === "RACE") return m.sessions[i];
    return m.sessions[m.sessions.length - 1];
  }
  function current(m, now) {
    var i;
    for (i = 0; i < m.sessions.length; i++) if (m.sessions[i].endOrDefault() > now) return m.sessions[i];
    return null;
  }
  function shortName(m) {
    return m.name.length > 3 && m.name.slice(-3) === " GP" ? m.name.slice(0, -3) : m.name;
  }

  function pulseRows(all, series, now) {
    var out = [], i, k;
    for (i = 0; i < series.length; i++) {
      var id = series[i], left = upcoming(all[id] || [], now);
      if (!left.length) continue;
      var meet = left[0], s = current(meet, now), r = race(meet), name = shortName(meet);
      var xs = !s || s === r ? [r] : [s, r];
      for (k = 0; k < xs.length; k++) {
        var x = xs[k];
        if (!x || x.endOrDefault() <= now || out.length >= 10) continue;
        out.push({ series: label(id), code: x.code, name: name, start: x.start / 1000, end: x.endOrDefault() / 1000, day: !!x.dateOnly });
      }
    }
    return out;
  }

  function Next(series, meeting, session) {
    this.series = series;
    this.meeting = meeting;
    this.session = session;
  }

  function soonestMap(all, now) {
    var best = null, id;
    for (id in all) if (Object.prototype.hasOwnProperty.call(all, id)) {
      var list = all[id], m = next(list, now), s = m ? current(m, now) : null;
      if (!s || s.dateOnly) continue;
      var n = new Next(id, m, s);
      if (!best) best = n;
      else if (s.live(now) !== best.session.live(now)) { if (s.live(now)) best = n; }
      else if (s.start < best.session.start) best = n;
    }
    return best;
  }
  function soonest(a, b, c) {
    if (arguments.length >= 3) {
      var m = {};
      m[F1] = a;
      m[MOTOGP] = b;
      return soonestMap(m, c);
    }
    return soonestMap(a, b);
  }

  function clock(ms) {
    var sec = Math.max(0, Math.floor(ms / 1000)), d = Math.floor(sec / 86400), h = Math.floor(sec / 3600) % 24, mi = Math.floor(sec / 60) % 60, s = sec % 60;
    var hms = pad(h) + ":" + pad(mi) + ":" + pad(s);
    return d > 0 ? d + "d " + hms : hms;
  }
  function clockShort(ms) {
    var sec = Math.max(0, Math.floor(ms / 1000)), d = Math.floor(sec / 86400), h = Math.floor(sec / 3600) % 24, mi = Math.floor(sec / 60) % 60;
    if (d > 0) return d + "d " + pad(h) + "h";
    if (h > 0) return pad(h) + "h " + pad(mi) + "m";
    return pad(mi) + "m " + pad(sec % 60) + "s";
  }

  function nextStart(list, now) {
    var i, k;
    for (i = 0; i < list.length; i++) for (k = 0; k < list[i].sessions.length; k++) {
      if (list[i].sessions[k].endOrDefault() > now) return list[i].sessions[k].start;
    }
    return Number.POSITIVE_INFINITY;
  }

  function defaultZone() {
    try {
      if (typeof Intl !== "undefined" && Intl.DateTimeFormat) {
        var id = Intl.DateTimeFormat().resolvedOptions().timeZone;
        if (id) return id;
      }
    } catch (e) {}
    return "";
  }
  function zone(id) {
    if (!id) return defaultZone();
    try {
      if (typeof Intl !== "undefined" && Intl.DateTimeFormat) {
        new Intl.DateTimeFormat("en-US", { timeZone: id }).format(new Date());
        return id;
      }
    } catch (e) { return defaultZone(); }
    return defaultZone();
  }
  function zoneLabel(tz) {
    var id = tz || defaultZone() || "";
    var s = id.lastIndexOf("/");
    return (s >= 0 ? id.slice(s + 1) : id).replace(/_/g, " ").toLowerCase();
  }

  function dateParts(d, utcMode) {
    return utcMode
      ? { w: DAYS[d.getUTCDay()], day: d.getUTCDate(), mon: MONTHS[d.getUTCMonth()], h: pad(d.getUTCHours()), m: pad(d.getUTCMinutes()) }
      : { w: DAYS[d.getDay()], day: d.getDate(), mon: MONTHS[d.getMonth()], h: pad(d.getHours()), m: pad(d.getMinutes()) };
  }
  function zoneParts(ms, tz) {
    if (!tz) return dateParts(new Date(ms), false);
    try {
      if (typeof Intl !== "undefined" && Intl.DateTimeFormat && Intl.DateTimeFormat.prototype.formatToParts) {
        var fmt = new Intl.DateTimeFormat("en-US", { timeZone: tz, weekday: "short", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit", hour12: false });
        var p = {}, parts = fmt.formatToParts(new Date(ms)), i;
        for (i = 0; i < parts.length; i++) p[parts[i].type] = parts[i].value;
        var hh = p.hour === "24" ? "00" : p.hour;
        if (hh && hh.length === 1) hh = "0" + hh;
        var mm = p.minute && p.minute.length === 1 ? "0" + p.minute : p.minute;
        return { w: String(p.weekday || "").replace(".", "").toLowerCase(), day: +p.day, mon: String(p.month || "").replace(".", "").toLowerCase(), h: hh, m: mm };
      }
    } catch (e) {}
    return dateParts(new Date(ms), false);
  }
  function when(t, tz) {
    var p = zoneParts(t, tz);
    return p.w + " " + p.day + " " + p.mon + " " + p.h + ":" + p.m;
  }
  function day(t) {
    var p = dateParts(new Date(t), true);
    return p.w + " " + p.day + " " + p.mon;
  }

  function meetingsFrom(asset, cache, series) {
    if (cache && cache.meetings) {
      var live = parse(cache.meetings);
      if (live.length) return live;
    }
    return parse(asset && asset[series]);
  }

  function mgpCode(shortname) {
    switch (String(shortname || "").trim().toUpperCase()) {
      case "FP1": return "FP1";
      case "PR": return "PR";
      case "FP2": return "FP2";
      case "Q1": return "Q1";
      case "Q2": return "Q2";
      case "SPR": return "SPR";
      case "RAC": return "RACE";
      default: return null;
    }
  }
  function mgpName(official) {
    var n = official ? String(official).trim() : "";
    var m = /GRAND PRIX OF (THE )?(.+)$/i.exec(n);
    var place = m ? m[2].trim() : n;
    var b = [], words = place.toLowerCase().split(/\s+/), i;
    for (i = 0; i < words.length; i++) {
      if (!words[i]) continue;
      if (b.length) b.push(" ");
      b.push(words[i].charAt(0).toUpperCase() + words[i].slice(1));
    }
    return b.join("") + " GP";
  }

  function parseMotoGp(events, acronym) {
    if (!acronym) acronym = "MGP";
    var out = [], i, k;
    for (i = 0; i < (events || []).length; i++) {
      var e = events[i];
      if (!e || e.kind !== "GP" || e.test) continue;
      var b = e.broadcasts || [], sessions = [];
      for (k = 0; k < b.length; k++) {
        var s = b[k];
        if (!s || s.type !== "SESSION") continue;
        var cat = s.category;
        if (!cat || cat.acronym !== acronym) continue;
        var code = mgpCode(s.shortname), name = String(s.name || "").trim();
        if (!code || String(s.status || "").toUpperCase().indexOf("CANCEL") >= 0 || name.toLowerCase().indexOf("cancel") >= 0) continue;
        var st = utc(s.date_start), en = utc(s.date_end || "");
        if (st < 0) continue;
        sessions.push({ code: code, name: name, start: iso(st), end: en > st ? iso(en) : null });
      }
      if (!sessions.length) continue;
      out.push({ slug: String(e.id || ""), name: mgpName(e.name), sessions: sessions });
    }
    return out;
  }

  function parseNascar(races) {
    var out = [], i, k;
    for (i = 0; races && i < races.length; i++) {
      var r = races[i], sch = r && r.schedule, sessions = [], practice = 0;
      for (k = 0; sch && k < sch.length; k++) {
        var e = sch[k], type = e ? +e.run_type : 0;
        if (type < 1 || type > 3) continue;
        var st = utc(String(e.start_time_utc || "") + "Z");
        if (st < 0) continue;
        var code = type === 3 ? "RACE" : type === 2 ? "QUALI" : "P" + (++practice);
        sessions.push({ code: code, name: String(e.event_name || "").trim(), start: iso(st), end: null });
      }
      if (!sessions.length) continue;
      out.push({ slug: String(r.race_id != null ? r.race_id : ""), name: String(r.track_name || r.race_name || "").trim(), sessions: sessions });
    }
    return out;
  }

  function parseFePage(html) {
    var re = /<script[^>]*application\/ld\+json[^>]*>([\s\S]*?)<\/script>/g, out = [], m;
    while ((m = re.exec(html))) {
      var o;
      try { o = JSON.parse(m[1].trim()); } catch (e) { continue; }
      var items = o && o.itemListElement, i;
      for (i = 0; items && i < items.length; i++) {
        var it = items[i], ev = it && it.item;
        if (!ev || ev["@type"] !== "SportsEvent") continue;
        if (String(ev.eventStatus || "").indexOf("Cancelled") >= 0) continue;
        var dayMs = utc(String(ev.startDate || "") + "T00:00:00Z");
        if (dayMs < 0) continue;
        var name = String(ev.name || "").replace(/^R\d+\s+/, "").trim();
        out.push({ slug: String(ev.name || ""), name: name, sessions: [
          { code: "RACE", name: "E-Prix", start: iso(dayMs), end: iso(dayMs + 86400000), dateOnly: true }
        ] });
      }
    }
    return out;
  }

  function wecName(slug) {
    var s = String(slug || "").replace(/-\d{4}$/, "").replace(/^(totalenergies|rolex|bapco-energies|qatar-airways)-/, "");
    var b = [], words = s.split("-"), i;
    for (i = 0; i < words.length; i++) {
      if (!words[i]) continue;
      if (b.length) b.push(" ");
      b.push(words[i] === "of" ? words[i] : words[i].charAt(0).toUpperCase() + words[i].slice(1));
    }
    return b.join("");
  }

  function parseWecPage(html) {
    var re = /href="\/en\/race\/([a-z0-9-]+)"(?:(?!href="\/en\/race\/)[\s\S]){0,3000}?data-date="(\d{9,11})"/g;
    var seen = {}, list = [], m;
    while ((m = re.exec(html))) {
      if (seen[m[1]]) continue;
      seen[m[1]] = 1;
      var st = parseInt(m[2], 10) * 1000;
      list.push({ slug: m[1], name: wecName(m[1]), sessions: [
        { code: "FP1", name: "Free Practice 1", start: iso(st), end: null }
      ] });
    }
    return list;
  }

  function f1Code(s) {
    var type = String(s.sessionType || "").toLowerCase();
    var shortName = String(s.shortName || "").toUpperCase();
    var desc = String(s.description || "").toLowerCase();
    if ((desc.indexOf("sprint") >= 0 && desc.indexOf("qual") >= 0) || shortName === "SQ" || shortName === "SS") return "SQ";
    if (desc.indexOf("sprint") >= 0 || shortName === "S") return "SPR";
    if (type === "practice" || shortName.indexOf("FP") === 0) return shortName || ("FP" + (s.sessionNumber || 0));
    if (type === "qualifying") return "QUALI";
    if (type === "race") return "RACE";
    return shortName || desc.toUpperCase();
  }

  function parseF1Page(html) {
    var t = String(html).replace(/\\"/g, '"');
    var re = /\{[^{}]*"startTime":"[^"]+"[^{}]*\}/g, seen = {}, list = [], m;
    while ((m = re.exec(t))) {
      var s;
      try { s = JSON.parse(m[0]); } catch (e) { continue; }
      if (!s.sessionType || !s.gmtOffset) continue;
      if (String(s.state || "").toLowerCase().indexOf("cancel") >= 0) continue;
      var key = s.startTime + "|" + s.description;
      if (seen[key]) continue;
      seen[key] = 1;
      var off = s.gmtOffset;
      var st = utc(s.startTime + off), en = s.endTime ? utc(s.endTime + off) : -1;
      if (st < 0) continue;
      list.push({ code: f1Code(s), name: s.description || s.sessionType, start: iso(st), end: en > st ? iso(en) : null });
    }
    list.sort(function (x, y) { return x.start < y.start ? -1 : x.start > y.start ? 1 : 0; });
    return list;
  }

  function toJson(sessions) {
    var a = [], i;
    for (i = 0; i < sessions.length; i++) {
      var s = sessions[i];
      a.push({ code: s.code, name: s.name, start: iso(s.start), end: s.end > 0 ? iso(s.end) : null });
    }
    return a;
  }

  function yearNow() { return new Date().getUTCFullYear(); }

  function latest(get, base, tail, parseBody, now) {
    var y = yearNow();
    function one(k) {
      return get(base + (y + k) + tail).then(function (body) {
        var a = body ? parseBody(body) : null;
        return a && a.length ? a : null;
      }, function () { return null; });
    }
    return one(0).then(function (first) {
      if (first && upcoming(parse(first), now).length) return first;
      return one(1).then(function (second) {
        if (second && upcoming(parse(second), now).length) return second;
        return second || first;
      });
    });
  }

  function quietly(p) {
    return p.then(function (v) { return v; }, function () { return null; });
  }

  // webOS and Tizen package the app as file://; their Fetch API refuses that scheme
  // ("URL scheme file is not supported"). XMLHttpRequest reads local files (status 0 or 200).
  function getText(url) {
    return new Promise(function (resolve, reject) {
      var Xhr = typeof XMLHttpRequest !== "undefined" ? XMLHttpRequest : null;
      if (!Xhr) { reject(new Error("no XMLHttpRequest")); return; }
      var x = new Xhr(), done = false;
      function finish() {
        if (done || x.readyState && x.readyState !== 4) return;
        done = true;
        if (x.status === 200 || x.status === 0) {
          if (x.responseText == null || x.responseText === "") { reject(new Error("HTTP " + x.status)); return; }
          resolve(x.responseText);
          return;
        }
        reject(new Error("HTTP " + x.status));
      }
      x.onreadystatechange = function () { if (x.readyState === 4) finish(); };
      x.onload = finish;
      x.onerror = function () { if (!done) { done = true; reject(new Error("network")); } };
      try { x.open("GET", url, true); x.send(); } catch (e) { reject(e); }
    });
  }

  function refreshIfStale(io) {
    var now = io.now != null ? io.now : Date.now();
    var series = io.series || [F1, MOTOGP];
    if (fetching) return Promise.resolve();
    var due = {}, i, need = false;
    for (i = 0; i < series.length; i++) {
      var id = series[i];
      var list = meetingsFrom(io.asset, io.read(id), id);
      var soon = nextStart(list, now);
      var maxAge = soon !== Number.POSITIVE_INFINITY && soon - now < 48 * 3600 * 1000 ? WEEKEND_AGE_MS : MAX_AGE_MS;
      var rec = io.read(id);
      if (!rec || now - (rec.saved || 0) > maxAge) { due[id] = 1; need = true; }
    }
    if (!need) return Promise.resolve();
    fetching = true;
    var jobs = [];
    if (due[MOTOGP] || due.moto2 || due.moto3) jobs.push(quietly(refreshMotoGp(io, now)));
    if (due.nascar || due.nascar2 || due.truck) jobs.push(quietly(refreshNascar(io, now)));
    if (due.fe) jobs.push(quietly(refreshFe(io)));
    if (due.wec) jobs.push(quietly(refreshWec(io)));
    if (due[F1]) jobs.push(quietly(refreshF1(io, now)));
    return Promise.all(jobs).then(function () {
      fetching = false;
      if (io.onDone) io.onDone();
    }, function () {
      fetching = false;
      if (io.onDone) io.onDone();
    });
  }

  function refreshMotoGp(io, now) {
    var cls = [[MOTOGP, "MGP"], ["moto2", "MT2"], ["moto3", "MT3"]], chain = Promise.resolve(), i;
    function step(k) {
      return latest(io.get, MGP_API, "", function (body) {
        try { return parseMotoGp(JSON.parse(body), k[1]); } catch (e) { return null; }
      }, now).then(function (meetings) {
        if (meetings && meetings.length) io.write(k[0], meetings);
      });
    }
    for (i = 0; i < cls.length; i++) (function (k) { chain = chain.then(function () { return step(k); }); })(cls[i]);
    return chain;
  }

  function refreshNascar(io, now) {
    var ser = [["nascar", "series_1"], ["nascar2", "series_2"], ["truck", "series_3"]], chain = Promise.resolve(), i;
    function step(k) {
      return latest(io.get, NASCAR_API, "/race_list_basic.json", function (body) {
        try { return parseNascar(JSON.parse(body)[k[1]]); } catch (e) { return null; }
      }, now).then(function (meetings) {
        if (meetings && meetings.length) io.write(k[0], meetings);
      });
    }
    for (i = 0; i < ser.length; i++) (function (k) { chain = chain.then(function () { return step(k); }); })(ser[i]);
    return chain;
  }

  function refreshFe(io) {
    return io.get(FE_PAGE).then(function (page) {
      var m = page ? parseFePage(page) : null;
      if (m && m.length) io.write("fe", m);
    });
  }

  function refreshWec(io) {
    return io.get(WEC_PAGE).then(function (page) {
      var m = page ? parseWecPage(page) : null;
      if (m && m.length) io.write("wec", m);
    });
  }

  function refreshF1(io, now) {
    var list = meetingsFrom(io.asset, io.read(F1), F1);
    var m = next(list, now);
    if (!m || !m.slug || !/^[a-z0-9-]+$/.test(m.slug)) return Promise.resolve();
    return io.get(F1_PAGE + m.slug).then(function (page) {
      if (!page) return;
      var sessions = parseF1Page(page);
      if (!sessions.length) return;
      var out = [], i;
      for (i = 0; i < list.length; i++) {
        var x = list[i];
        out.push({ slug: x.slug, name: x.name, sessions: x === m ? sessions : toJson(x.sessions) });
      }
      io.write(F1, out);
    });
  }

  var Racing = {
    YEAR: YEAR, SERIES: SERIES, F1: F1, MOTOGP: MOTOGP, DEFAULT_LENGTH_MS: DEFAULT_LENGTH_MS,
    MAX_AGE_MS: MAX_AGE_MS, WEEKEND_AGE_MS: WEEKEND_AGE_MS,
    label: label, known: known, utc: utc, iso: iso, parse: parse, next: next, upcoming: upcoming,
    race: race, current: current, shortName: shortName, pulseRows: pulseRows, soonest: soonest,
    clock: clock, clockShort: clockShort, nextStart: nextStart, zone: zone, zoneLabel: zoneLabel, when: when, day: day,
    meetingsFrom: meetingsFrom, getText: getText, mgpCode: mgpCode, mgpName: mgpName, parseMotoGp: parseMotoGp,
    parseNascar: parseNascar, parseFePage: parseFePage, parseWecPage: parseWecPage, wecName: wecName,
    parseF1Page: parseF1Page, f1Code: f1Code, refreshIfStale: refreshIfStale
  };

  if (typeof module !== "undefined" && module.exports) module.exports = Racing;
  root.OmarchyRacing = Racing;
})(typeof window !== "undefined" ? window : typeof global !== "undefined" ? global : this);
