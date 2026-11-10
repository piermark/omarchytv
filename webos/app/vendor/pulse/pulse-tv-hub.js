/*
 * OmarchyTV 2.15: Pulse on the TV. The PC's Pulse hub (omarchy-pulse, Python) computes what the page draws; on the TV
 * this file does the same in JavaScript, so Pulse runs with no PC at all: the real sky (Sun, Moon, planets, stars,
 * meteor showers), the planes over you, the ISS, the Starlink trains, the next rocket launch, the weather, and the TV's
 * own numbers. The formulas are the hub's own (low precision, a fraction of a degree, offline). With a PC paired
 * through omarchy-link, the PC's full Pulse state is merged on top (agents, workspaces, repos, panels...).
 * ES5 only: it runs on webOS 4 (Chromium 53) and older Android WebViews. MIT, Marco Porro.
 */
(function (root) {
  "use strict";
  var D = root.OmarchyPulseData || {};
  var R = Math.PI / 180;
  function now() { return Date.now() / 1000; }
  function rnd(v, d) { var k = Math.pow(10, d || 0); return Math.round(v * k) / k; }
  function num(v) { var n = parseFloat(v); return isFinite(n) ? n : null; }
  function mod(a, b) { return ((a % b) + b) % b; }

  // ------------------------------------------------------------------ the sky, as the hub computes it
  function julian(t) { return t / 86400 + 2440587.5; }
  function gmst(jd) { return mod(280.46061837 + 360.98564736629 * (jd - 2451545.0), 360); }
  function altaz(ra, dec, lat, lon, jd) {
    var h = mod(gmst(jd) + lon - ra, 360) * R, la = lat * R, d = dec * R;
    var el = Math.asin(Math.max(-1, Math.min(1, Math.sin(la) * Math.sin(d) + Math.cos(la) * Math.cos(d) * Math.cos(h))));
    var az = Math.atan2(-Math.sin(h) * Math.cos(d), Math.cos(la) * Math.sin(d) - Math.sin(la) * Math.cos(d) * Math.cos(h));
    return [mod(az / R, 360), el / R];
  }
  function eclToEq(lon, lat, eps) {
    eps = (eps === undefined ? 23.4393 : eps) * R;
    var lo = lon * R, la = lat * R;
    var x = Math.cos(la) * Math.cos(lo), y = Math.cos(eps) * Math.cos(la) * Math.sin(lo) - Math.sin(eps) * Math.sin(la);
    var z = Math.sin(eps) * Math.cos(la) * Math.sin(lo) + Math.cos(eps) * Math.sin(la);
    return [mod(Math.atan2(y, x) / R, 360), Math.asin(Math.max(-1, Math.min(1, z))) / R];
  }
  function sunEcl(jd) {
    var n = jd - 2451545.0, L = mod(280.460 + 0.9856474 * n, 360), g = mod(357.528 + 0.9856003 * n, 360) * R;
    return mod(L + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g), 360);
  }
  function sunRadec(jd) { return eclToEq(sunEcl(jd), 0, 23.439 - 0.0000004 * (jd - 2451545.0)); }
  function moonGeo(jd) {
    var d = jd - 2451543.5, N = 125.1228 - 0.0529538083 * d, i = 5.1454, w = 318.0634 + 0.1643573223 * d;
    var e = 0.054900, M = mod(115.3654 + 13.0649929509 * d, 360), E = M * R, k;
    for (k = 0; k < 8; k++) E = E - (E - e * Math.sin(E) - M * R) / (1 - e * Math.cos(E));
    var xv = Math.cos(E) - e, yv = Math.sqrt(1 - e * e) * Math.sin(E), v = Math.atan2(yv, xv), r = Math.sqrt(xv * xv + yv * yv);
    var vw = v + w * R, Nr = N * R, ir = i * R;
    var xh = r * (Math.cos(Nr) * Math.cos(vw) - Math.sin(Nr) * Math.sin(vw) * Math.cos(ir));
    var yh = r * (Math.sin(Nr) * Math.cos(vw) + Math.cos(Nr) * Math.sin(vw) * Math.cos(ir));
    var zh = r * Math.sin(vw) * Math.sin(ir);
    var lon = Math.atan2(yh, xh) / R, lat = Math.atan2(zh, Math.sqrt(xh * xh + yh * yh)) / R;
    var Ms = mod(356.0470 + 0.9856002585 * d, 360) * R, Mm = M * R, Ls = Ms + (282.9404 + 4.70935e-5 * d) * R;
    var Lm = Nr + w * R + M * R, Dd = Lm - Ls, F = Lm - Nr, s = Math.sin;
    lon += -1.274 * s(Mm - 2 * Dd) + 0.658 * s(2 * Dd) - 0.186 * s(Ms) - 0.059 * s(2 * Mm - 2 * Dd) - 0.057 * s(Mm - 2 * Dd + Ms) +
      0.053 * s(Mm + 2 * Dd) + 0.046 * s(2 * Dd - Ms) + 0.041 * s(Mm - Ms) - 0.035 * s(Dd) - 0.031 * s(Mm + Ms) -
      0.015 * s(2 * F - 2 * Dd) + 0.011 * s(Mm - 4 * Dd);
    lat += -0.173 * s(F - 2 * Dd) - 0.055 * s(Mm - F - 2 * Dd) - 0.046 * s(Mm + F - 2 * Dd) + 0.033 * s(F + 2 * Dd) + 0.017 * s(2 * Mm + F);
    return [mod(lon, 360), lat];
  }
  function moonPhase(jd) {
    var elong = mod(moonGeo(jd)[0] - sunEcl(jd), 360), lit = (1 - Math.cos(elong * R)) / 2, waxing = elong < 180;
    var name = lit < 0.03 ? "new moon" : lit > 0.97 ? "full moon" :
      Math.abs(lit - 0.5) < 0.06 ? (waxing ? "first quarter" : "last quarter") :
      lit < 0.5 ? (waxing ? "waxing crescent" : "waning crescent") : (waxing ? "waxing gibbous" : "waning gibbous");
    return { lit: rnd(lit, 3), waxing: waxing, age: rnd(elong / 360 * 29.530589, 1), name: name };
  }
  function helio(name, jd) {
    var p = D.PLANETS[name], el0 = p[0], rate = p[1], T = (jd - 2451545.0) / 36525, v = [], k;
    for (k = 0; k < 6; k++) v.push(el0[k] + rate[k] * T);
    var a = v[0], e = v[1], I = v[2] * R, L = v[3], wb = v[4], Om = v[5] * R;
    var w = (wb - v[5]) * R, M = (mod(L - wb + 180, 360) - 180) * R, E = M + e * Math.sin(M);
    for (k = 0; k < 10; k++) E = E - (E - e * Math.sin(E) - M) / (1 - e * Math.cos(E));
    var xp = a * (Math.cos(E) - e), yp = a * Math.sqrt(1 - e * e) * Math.sin(E);
    var cw = Math.cos(w), sw = Math.sin(w), cO = Math.cos(Om), sO = Math.sin(Om), cI = Math.cos(I), sI = Math.sin(I);
    return [(cw * cO - sw * sO * cI) * xp + (-sw * cO - cw * sO * cI) * yp,
      (cw * sO + sw * cO * cI) * xp + (-sw * sO + cw * cO * cI) * yp, (sw * sI) * xp + (cw * sI) * yp];
  }
  function planetRadec(name, jd) {
    var p = helio(name, jd), g = helio("earth", jd), x = p[0] - g[0], y = p[1] - g[1], z = p[2] - g[2];
    return eclToEq(mod(Math.atan2(y, x) / R, 360), Math.atan2(z, Math.sqrt(x * x + y * y)) / R);
  }
  function meteorShower(t) {
    var d = new Date(t * 1000), list = D.SHOWERS || [], i, y;
    for (i = 0; i < list.length; i++) {
      var s = list[i];
      for (y = d.getFullYear() - 1; y <= d.getFullYear() + 1; y++) {
        var off = Math.abs(d.getTime() - new Date(y, s[1] - 1, s[2], 3).getTime()) / 864e5;
        if (off <= s[3] + 0.5) return { name: s[0], zhr: s[4], ra: s[5], dec: s[6], strength: rnd(Math.max(0.15, 1 - off / (s[3] + 1)), 2) };
      }
    }
    return null;
  }
  function nightSky(lat, lon, t) {
    var jd = julian(t), s = altaz(sunRadec(jd)[0], sunRadec(jd)[1], lat, lon, jd);
    var mq = moonGeo(jd), meq = eclToEq(mq[0], mq[1]), m = altaz(meq[0], meq[1], lat, lon, jd);
    m[1] -= 0.95 * Math.cos(m[1] * R);
    var stars = [], seen = {}, i;
    (D.STARS || []).forEach(function (st) {
      var a = altaz(st[1], st[2], lat, lon, jd);
      if (a[1] > -2) { seen[st[0]] = stars.length; stars.push({ n: st[0], az: rnd(a[0], 2), el: rnd(a[1], 2), m: st[3] }); }
    });
    var lines = [], names = [], C = D.CONSTELLATIONS || {};
    Object.keys(C).forEach(function (cn) {
      var pairs = C[cn], mem = {}, members = [];
      pairs.forEach(function (p) { mem[p[0]] = 1; mem[p[1]] = 1; if (p[0] in seen && p[1] in seen) lines.push([seen[p[0]], seen[p[1]]]); });
      if (cn === "LYRA") mem.Vega = 1;
      Object.keys(mem).forEach(function (n) { if (n in seen) members.push(seen[n]); });
      if (members.length) names.push({ n: cn, i: members });
    });
    var planets = [];
    ["venus", "mars", "jupiter", "saturn"].forEach(function (p) {
      var q = planetRadec(p, jd), a = altaz(q[0], q[1], lat, lon, jd);
      if (a[1] > -2) planets.push({ n: p, az: rnd(a[0], 2), el: rnd(a[1], 2), m: D.PLANETS[p][2] });
    });
    var sh = meteorShower(t);
    if (sh) { var r = altaz(sh.ra, sh.dec, lat, lon, jd); delete sh.ra; delete sh.dec; sh.az = rnd(r[0], 1); sh.el = rnd(r[1], 1); }
    var ph = moonPhase(jd), moon = { az: rnd(m[0], 2), el: rnd(m[1], 2) };
    for (i in ph) if (ph.hasOwnProperty(i)) moon[i] = ph[i];
    return { t: t, sun: { az: rnd(s[0], 2), el: rnd(s[1], 2) }, moon: moon, planets: planets, stars: stars, lines: lines, names: names, shower: sh };
  }

  // ------------------------------------------------------------------ satellites (ISS, Starlink) and planes
  function lookAngles(lat, lon, slat, slon, altKm) {
    var Re = 6371.0;
    function ecef(la, lo, r) { la *= R; lo *= R; return [r * Math.cos(la) * Math.cos(lo), r * Math.cos(la) * Math.sin(lo), r * Math.sin(la)]; }
    var o = ecef(lat, lon, Re), s = ecef(slat, slon, Re + altKm), d = [s[0] - o[0], s[1] - o[1], s[2] - o[2]];
    var la = lat * R, lo = lon * R;
    var e = [-Math.sin(lo), Math.cos(lo), 0], n = [-Math.sin(la) * Math.cos(lo), -Math.sin(la) * Math.sin(lo), Math.cos(la)];
    var u = [Math.cos(la) * Math.cos(lo), Math.cos(la) * Math.sin(lo), Math.sin(la)];
    function dot(a, b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    var dist = Math.sqrt(dot(d, d));
    return [mod(Math.atan2(dot(d, e), dot(d, n)) / R, 360), Math.asin(Math.max(-1, Math.min(1, dot(d, u) / dist))) / R];
  }
  function ommEpoch(s) { return Date.parse(String(s).replace(/Z$/, "") + "Z") / 1000; }
  // The hub's J2 orbit (it uses SGP4 when python-sgp4 is there): close enough for a train in its first weeks.
  function satEci(o, t) {
    var mu = 398600.4418, Re = 6378.137, J2 = 1.08263e-3, k;
    var nk = Number(o.MEAN_MOTION) * 2 * Math.PI / 86400, e = Number(o.ECCENTRICITY), i = Number(o.INCLINATION) * R;
    var a1 = Math.pow(mu / (nk * nk), 1 / 3), k2 = 0.5 * J2 * Re * Re;
    var d1 = 1.5 * k2 * (3 * Math.cos(i) * Math.cos(i) - 1) / Math.pow(1 - e * e, 1.5), dl = d1 / (a1 * a1);
    var a0 = a1 * (1 - dl / 3 - dl * dl - 134 / 81 * dl * dl * dl), n0 = nk / (1 + d1 / (a0 * a0)), a = Math.pow(mu / (n0 * n0), 1 / 3);
    var dt = t - ommEpoch(o.EPOCH), p = a * (1 - e * e), kk = 1.5 * J2 * Math.pow(Re / p, 2) * n0;
    var raan = Number(o.RA_OF_ASC_NODE) * R - kk * Math.cos(i) * dt;
    var argp = Number(o.ARG_OF_PERICENTER) * R + kk * (2 - 2.5 * Math.sin(i) * Math.sin(i)) * dt;
    var M = Number(o.MEAN_ANOMALY) * R + (n0 + kk * Math.sqrt(1 - e * e) * (1 - 1.5 * Math.sin(i) * Math.sin(i))) * dt +
      2 * Math.PI * Number(o.MEAN_MOTION_DOT || 0) * Math.pow(dt / 86400, 2);
    var E = M;
    for (k = 0; k < 8; k++) E = E - (E - e * Math.sin(E) - M) / (1 - e * Math.cos(E));
    var nu = 2 * Math.atan2(Math.sqrt(1 + e) * Math.sin(E / 2), Math.sqrt(1 - e) * Math.cos(E / 2)), r = a * (1 - e * Math.cos(E)), u = argp + nu;
    var cO = Math.cos(raan), sO = Math.sin(raan), cu = Math.cos(u), su = Math.sin(u), ci = Math.cos(i), si = Math.sin(i);
    return [r * (cO * cu - sO * su * ci), r * (sO * cu + cO * su * ci), r * su * si];
  }
  function satLook(o, lat, lon, t) {
    var x = satEci(o, t), g = gmst(julian(t)) * R;
    var xe = x[0] * Math.cos(g) + x[1] * Math.sin(g), ye = -x[0] * Math.sin(g) + x[1] * Math.cos(g);
    var r = Math.sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2]);
    var la = lookAngles(lat, lon, Math.asin(x[2] / r) / R, Math.atan2(ye, xe) / R, r - 6371.0);
    var lam = sunEcl(julian(t)) * R, eps = 23.439 * R;
    var along = x[0] * Math.cos(lam) + x[1] * Math.cos(eps) * Math.sin(lam) + x[2] * Math.sin(eps) * Math.sin(lam);
    var perp = Math.sqrt(Math.max(0, r * r - along * along));
    return [la[0], la[1], along > 0 || perp > 6371.0];
  }
  function starlinkGroups(rows) {
    var g = {};
    (rows || []).forEach(function (o) {
      if (!o || String(o.OBJECT_NAME || "").toUpperCase().indexOf("STARLINK") !== 0) return;
      var id = String(o.OBJECT_ID || "").slice(0, 8);
      if (id) (g[id] = g[id] || []).push(o);
    });
    var out = {};
    Object.keys(g).forEach(function (k) {
      if (g[k].length >= 5) out[k] = g[k].sort(function (a, b) { return String(a.OBJECT_ID) < String(b.OBJECT_ID) ? -1 : 1; });
    });
    return out;
  }
  function starlinkPasses(groups, lat, lon, t0, hours, step) {
    hours = hours || 24; step = step || 30;
    var out = [];
    Object.keys(groups).forEach(function (gid) {
      var sats = groups[gid], lead = sats[sats.length >> 1], best = null, start = null, t;
      for (t = t0; t < t0 + hours * 3600; t += step) {
        var l = satLook(lead, lat, lon, t), jd = julian(t), sr = sunRadec(jd), sunEl = altaz(sr[0], sr[1], lat, lon, jd)[1];
        if (l[1] > 10 && l[2] && sunEl < -6) {
          start = start || t;
          if (!best || l[1] > best[1]) best = [t, l[1], l[0]];
        } else if (start) {
          out.push({ group: gid, start: start, end: t, max_el: Math.round(best[1]), az: Math.round(best[2]), n: sats.length });
          start = null; best = null;
        }
      }
    });
    return out.sort(function (a, b) { return a.start - b.start; });
  }
  function issAt(track, t) {
    var pts = (track || []).filter(function (p) { return p && "timestamp" in p; }), i;
    for (i = 0; i + 1 < pts.length; i++) {
      var a = pts[i], b = pts[i + 1];
      if (a.timestamp <= t && t <= b.timestamp) {
        var u = (t - a.timestamp) / Math.max(1, b.timestamp - a.timestamp), dl = mod(b.longitude - a.longitude + 540, 360) - 180;
        return { latitude: a.latitude + (b.latitude - a.latitude) * u, longitude: mod(a.longitude + dl * u + 540, 360) - 180,
          altitude: a.altitude + (b.altitude - a.altitude) * u, velocity: a.velocity, visibility: a.visibility };
      }
    }
    return null;
  }
  // ICAO type designators (A20N, B77W, B38M) as people say them: the codes alone read like a name cut short
  var TYPES = { A318: "A318", A319: "A319", A320: "A320", A321: "A321", A19N: "A319neo", A20N: "A320neo", A21N: "A321neo",
    BCS1: "A220-100", BCS3: "A220-300", A306: "A300-600", A310: "A310", A332: "A330-200", A333: "A330-300", A338: "A330-800neo",
    A339: "A330-900neo", A342: "A340-200", A343: "A340-300", A345: "A340-500", A346: "A340-600", A359: "A350-900",
    A35K: "A350-1000", A388: "A380", B712: "717", B732: "737-200", B733: "737-300", B734: "737-400", B735: "737-500",
    B736: "737-600", B737: "737-700", B738: "737-800", B739: "737-900", B37M: "737 MAX 7", B38M: "737 MAX 8",
    B39M: "737 MAX 9", B3XM: "737 MAX 10", B742: "747-200", B744: "747-400", B748: "747-8", B752: "757-200", B753: "757-300",
    B762: "767-200", B763: "767-300", B764: "767-400", B772: "777-200", B77L: "777-200LR", B773: "777-300", B77W: "777-300ER",
    B778: "777-8", B779: "777-9", B788: "787-8", B789: "787-9", B78X: "787-10", AT43: "ATR 42-300", AT45: "ATR 42-500",
    AT46: "ATR 42-600", AT72: "ATR 72", AT75: "ATR 72-500", AT76: "ATR 72-600", DH8A: "Dash 8-100", DH8B: "Dash 8-200",
    DH8C: "Dash 8-300", DH8D: "Dash 8 Q400", CRJ2: "CRJ-200", CRJ7: "CRJ-700", CRJ9: "CRJ-900", CRJX: "CRJ-1000",
    E135: "ERJ-135", E145: "ERJ-145", E170: "E170", E75L: "E175", E75S: "E175", E190: "E190", E195: "E195",
    E290: "E190-E2", E295: "E195-E2", AJ27: "ARJ21", C919: "C919", SU95: "Superjet 100", MD11: "MD-11", MD82: "MD-82",
    MD83: "MD-83", MD88: "MD-88", MD90: "MD-90", DC10: "DC-10", A400: "A400M", C130: "C-130 Hercules", C30J: "C-130J",
    C208: "Cessna Caravan", C172: "Cessna 172", PC12: "Pilatus PC-12", GLF5: "Gulfstream V", GLF6: "Gulfstream G650",
    GLEX: "Global Express", CL60: "Challenger 600", E55P: "Phenom 300", BE20: "King Air 200", B350: "King Air 350",
    A139: "AW139", EC35: "H135", EC45: "H145", S76: "S-76", IL76: "Il-76", AN12: "An-12", AN26: "An-26", Y12: "Y-12" };
  function typeName(code) {
    var c = String(code || "").toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 4);
    return TYPES[c] || c;
  }
  function bearing(a1, o1, a2, o2) {
    var r = Math.PI / 180, y = Math.sin((o2 - o1) * r) * Math.cos(a2 * r),
        x = Math.cos(a1 * r) * Math.sin(a2 * r) - Math.sin(a1 * r) * Math.cos(a2 * r) * Math.cos((o2 - o1) * r);
    return (Math.atan2(y, x) / r + 360) % 360;
  }
  /**
   * adsbdb knows a route by callsign only, and airlines reuse flight numbers, so a route can be years old. It is shown
   * only when the plane is plausibly on it: no big detour between the two airports and, away from the destination,
   * heading towards it. A route without airport positions can't be checked and is kept.
   */
  function routeFits(r, lat, lon, track) {
    if (!r || r.oLat == null || r.dLat == null || lat == null || lon == null) return true;
    var od = distKm(r.oLat, r.oLon, r.dLat, r.dLon), dO = distKm(r.oLat, r.oLon, lat, lon), dD = distKm(lat, lon, r.dLat, r.dLon);
    if (dO + dD > od * 1.15 + 75) return false;
    if (track != null && isFinite(track) && dD > 80) {
      var off = Math.abs(((bearing(lat, lon, r.dLat, r.dLon) - track) % 360 + 540) % 360 - 180);
      if (off > 90) return false;
    }
    return true;
  }
  function withRoute(p, r) {
    if (!r) return;
    if (r.from && r.to && routeFits(r, p.lat, p.lon, p.track)) { p.from = r.from; p.to = r.to; p.fromCity = r.fromCity; p.toCity = r.toCity; }
    if (r.airline) p.airline = r.airline;
  }
  function ftText(ft) {
    return String(Math.round((Number(ft) || 0) / 100) * 100).replace(/\B(?=(\d{3})+(?!\d))/g, ",") + " ft";
  }
  /**
   * How far the planes panel looks (screensaver.planesNm). 25 nm, the default, is the planes in your sky ("overhead");
   * further out it lists the nearest five with their distance, high enough to see or not.
   */
  var SCENES = { mars: 1, monolith: 1 };   // 2.15.1 Pulse 1.8's scenes in code: each runs in its own sandboxed frame
  var PLANES_NM = [25, 50, 100, 150, 200, 250];
  function planesNm(v) { v = Number(v); return PLANES_NM.indexOf(v) >= 0 ? v : 25; }
  /**
   * OpenSky's state vectors (its own receivers: where adsb.lol has few feeders, it can see several times more) in
   * adsb.lol's shape. Altitude m -> ft, speed m/s -> kt; `at` is when the position was seen.
   */
  function openskyStates(states) {
    return (states || []).map(function (v) {
      var m = v[7] != null ? v[7] : v[13];
      return { hex: String(v[0] || "").toLowerCase(), flight: v[1], lon: v[5], lat: v[6], alt: m != null ? m * 3.28084 : null,
        gs: v[9] != null ? v[9] * 1.94384 : null, track: v[10], ground: !!v[8], at: v[3] || v[4] || 0 };
    }).filter(function (a) { return a.hex && a.lat != null && a.lon != null; });
  }
  /**
   * adsb.lol's planes, then OpenSky's that adsb.lol doesn't have, moved on to `t` along their track (OpenSky is
   * read every few minutes, so a plane has flown on since). Positions older than 15 minutes are dropped.
   */
  function mergePlanes(adsb, os, t, types) {
    var out = [], seen = {};
    (adsb || []).forEach(function (a) { seen[String(a.hex || "").toLowerCase()] = 1; out.push(a); });
    (os || []).forEach(function (a) {
      var age = t - (a.at || t);
      if (seen[a.hex] || age > 900) return;
      var b = {}, k;
      for (k in a) if (a.hasOwnProperty(k)) b[k] = a[k];
      if (!a.ground && a.gs && a.track != null && age > 0) {
        var d = a.gs * age / 3600 / 60, r = Math.PI / 180;   // nautical miles flown, in degrees of latitude
        b.lat = a.lat + d * Math.cos(a.track * r);
        b.lon = a.lon + d * Math.sin(a.track * r) / Math.max(0.2, Math.cos(a.lat * r));
      }
      if (!b.type && types && types[a.hex]) b.type = types[a.hex];
      out.push(b);
    });
    return out;
  }
  /** The planes within `reach` nm, nearest first, whether or not they are high enough to be in your sky. */
  function planesNear(lat, lon, raw, n, reach) {
    var out = [];
    (raw || []).forEach(function (a) {
      if (!a || a.ground || a.lat == null || a.lon == null) return;
      var nm = distKm(lat, lon, Number(a.lat), Number(a.lon)) / 1.852;
      if (nm > (reach || 25)) return;
      out.push({ id: String(a.hex || "").slice(0, 8), flight: String(a.flight || "").toUpperCase().replace(/[^A-Z0-9-]/g, "").slice(0, 8),
        type: typeName(a.type), lat: rnd(Number(a.lat), 3), lon: rnd(Number(a.lon), 3), ft: Math.round((Number(a.alt) || 0) / 100) * 100,
        track: Number(a.track) || 0, nm: Math.round(nm), hex: String(a.hex || "").toLowerCase() });
    });
    return out.sort(function (a, b) { return a.nm - b.nm; }).slice(0, n || 5);
  }
  /**
   * 2.15.1 the race weekends the launcher follows (Android: screensaver.racing), as one of Pulse's side panels: the
   * next sessions, soonest first, each with its countdown ("● live" while it runs; a day-only one says the days).
   * `list`: [{series, code, name, start, end, day}], times in seconds.
   */
  function racingItems(list, t, n) {
    return (list || []).filter(function (x) { return x && Number(x.start) > 0 && (Number(x.end) || Number(x.start) + 7200) > t; })
      .sort(function (a, b) { return a.start - b.start; }).slice(0, n || 5).map(function (x) {
        var left = x.start - t, live = left <= 0;
        return { title: String(x.series || "").slice(0, 12) + (x.code ? " · " + String(x.code).toLowerCase().slice(0, 8) : ""),
          text: String(x.name || "").slice(0, 28),
          right: live ? "● live" : x.day ? (left < 86400 ? "today" : "in " + Math.ceil(left / 86400) + "d") : countdown(left).slice(2) };
      });
  }
  function planesOver(lat, lon, raw) {
    var out = [];
    (raw || []).forEach(function (a) {
      if (!a || a.ground || a.lat == null || a.lon == null) return;
      var ft = Number(a.alt) || 0;
      if (ft < 300) return;
      var l = lookAngles(lat, lon, Number(a.lat), Number(a.lon), ft * 0.0003048);
      if (l[1] < 10) return;
      var row = { id: String(a.hex || "").slice(0, 8), flight: String(a.flight || "").toUpperCase().replace(/[^A-Z0-9-]/g, "").slice(0, 8),
        type: typeName(a.type), az: rnd(l[0], 1), el: rnd(l[1], 1), lat: rnd(Number(a.lat), 3), lon: rnd(Number(a.lon), 3),
        ft: Math.round(ft / 100) * 100, kt: Math.floor((a.gs != null && a.gs !== "" ? Number(a.gs) : Number(a.kt)) || 0), track: Number(a.track) || 0 };
      if (a.from) row.from = a.from;
      if (a.to) row.to = a.to;
      if (a.fromCity) row.fromCity = a.fromCity;
      if (a.toCity) row.toCity = a.toCity;
      out.push(row);
    });
    return out.sort(function (a, b) { return b.el - a.el; }).slice(0, 16);
  }
  function latestKp(rows) {
    if (!rows || !rows.length) return null;
    var last = rows[rows.length - 1], v = null;
    if (last && typeof last === "object" && !(last instanceof Array)) v = last.Kp != null ? last.Kp : last.kp != null ? last.kp : last.kp_index != null ? last.kp_index : last.estimated_kp;
    else if (last instanceof Array && rows[0] instanceof Array && rows[0].indexOf("Kp") >= 0) v = last[rows[0].indexOf("Kp")];
    var n = parseFloat(v);
    return isNaN(n) ? null : rnd(n, 2);
  }
  function parseLaunch(r) {
    var net = Date.parse(r && r.net) / 1000;
    if (!r || isNaN(net)) return null;
    var name = String(r.name || ""), bar = name.indexOf("|");
    var rocket = (bar >= 0 ? name.slice(0, bar) : name).replace(/\s+Block\s+\d+/, "").trim().slice(0, 24) || "rocket";
    var mission = (bar >= 0 ? name.slice(bar + 1).trim() : "").slice(0, 32) || name.slice(0, 32);
    return { id: String(r.id || name), rocket: rocket, mission: mission, net: net, status: String((r.status || {}).abbrev || "TBD").slice(0, 10) };
  }
  function countdown(s) {
    if (s < 0) return "liftoff";
    var d = Math.floor(s / 86400), h = Math.floor(s % 86400 / 3600), m = Math.floor(s % 3600 / 60), pad = function (x) { return (x < 10 ? "0" : "") + x; };
    return "T-" + (d ? d + "d " + h + "h" : h ? h + "h " + pad(m) + "m" : m + "m " + pad(Math.floor(s) % 60) + "s");
  }
  function compass(az) { return ["N", "NE", "E", "SE", "S", "SW", "W", "NW"][Math.round(mod(az, 360) / 45) % 8]; }
  function cityKey(name) {
    var n = String(name || "");
    try { n = n.normalize("NFKD").replace(/[̀-ͯ]/g, ""); } catch (e) {}
    n = n.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
    n = (D.CITY_ALIASES || {})[n] || n;
    return (D.CITY_KEYS || []).indexOf(n) >= 0 ? n : "";
  }
  function weatherFrom(j, label, imperial) {
    var cur = j.current, ht = j.hourly.time, nowIso = String(cur.time).slice(0, 13), start = 0, i, hours = [];
    for (i = 0; i < ht.length; i++) if (ht[i].slice(0, 13) >= nowIso) { start = i; break; }
    for (i = start; i < Math.min(start + 24, ht.length); i++)
      hours.push({ h: ht[i].slice(11, 13), t: j.hourly.temperature_2m[i], p: j.hourly.precipitation_probability[i], c: j.hourly.weather_code[i] });
    return { city: label, temp: cur.temperature_2m, feels: cur.apparent_temperature, code: cur.weather_code, wind: cur.wind_speed_10m,
      windDir: cur.wind_direction_10m, gust: cur.wind_gusts_10m, cloud: cur.cloud_cover, hum: cur.relative_humidity_2m, day: cur.is_day,
      rain: cur.precipitation || 0, hi: j.daily.temperature_2m_max[0], lo: j.daily.temperature_2m_min[0],
      sunrise: String(j.daily.sunrise[0]).slice(11, 16), sunset: String(j.daily.sunset[0]).slice(11, 16),
      unit: imperial ? "°F" : "°C", windUnit: imperial ? "mph" : "km/h", hours: hours, t: now() };
  }

  // The Omarchy tips of the hot-air balloon (the PC reads them from your key bindings; the TV keeps the classics).
  var TIPS = ["SUPER + SPACE · Launch apps", "SUPER + RETURN · Terminal", "SUPER + K · Show key bindings", "SUPER + W · Close window",
    "SUPER + ALT + SPACE · Omarchy menu", "SUPER + CTRL + SPACE · Next background", "SUPER + SHIFT + B · Browser", "SUPER + ESCAPE · Lock, suspend, restart"];

  /** Open-Meteo's air: {aqi, level, pollen, pollenLevel} as the PC's hub hands it to Pulse. */
  function airFrom(j) {
    var c = j && j.current;
    if (!c || c.european_aqi == null) return null;
    var a = c.european_aqi, level = a <= 20 ? "good" : a <= 40 ? "fair" : a <= 60 ? "moderate" : a <= 80 ? "poor" : a <= 100 ? "very poor" : "extremely poor";
    var best = null, v = 0;
    ["alder", "birch", "grass", "mugwort", "olive", "ragweed"].forEach(function (p) { var x = +c[p + "_pollen"] || 0; if (x > v) { v = x; best = p; } });
    return { aqi: Math.round(a), level: level, pollen: v >= 1 ? best : "", pollenLevel: v < 1 ? "" : v < 20 ? "low" : v < 50 ? "moderate" : v < 100 ? "high" : "very high" };
  }
  /** USGS's last day of M4.5+, nearest first when one is within 1000 km, else the strongest. */
  function quakesFrom(j, lat, lon) {
    return (j.features || []).map(function (f) {
      var p = f.properties || {}, g = (f.geometry || {}).coordinates || [], km = isFinite(lat) && g.length > 1 ? distKm(lat, lon, g[1], g[0]) : 1e9;
      return { title: String(p.place || "?").slice(0, 40), text: km < 1e8 ? Math.round(km) + " km away" : "", right: "M" + (+p.mag || 0).toFixed(1),
        level: p.mag >= 6.5 ? "crit" : p.mag >= 5.5 ? "warn" : "", t: Math.floor((p.time || 0) / 1000), mag: +p.mag || 0, km: km };
    }).sort(function (a, b) { return (a.km < 1000) !== (b.km < 1000) ? (a.km < 1000 ? -1 : 1) : b.mag - a.mag; });
  }
  function distKm(a1, o1, a2, o2) {
    var r = Math.PI / 180, d = Math.sin((a2 - a1) * r / 2), e = Math.sin((o2 - o1) * r / 2);
    return 12742 * Math.asin(Math.sqrt(d * d + Math.cos(a1 * r) * Math.cos(a2 * r) * e * e));
  }

  // ------------------------------------------------------------------ the hub
  /**
   * cfg: {lat, lon, city, imperial, theme: {name, colors}, quality, motion, scene, pc: {state: fn() -> Promise|null},
   *       tv: fn() -> {cpu, ram, ..., host} | null, every: {...} (tests)}
   * net(url) -> Promise of parsed JSON. clock() -> seconds (tests).
   */
  function create(cfg, net, clock) {
    clock = clock || now;
    var lat = Number(cfg.lat), lon = Number(cfg.lon), hasLoc = isFinite(lat) && isFinite(lon) && !(lat === 0 && lon === 0);
    var S = { air: null, quakes: [], omarchy: [], weather: null, sky: [], skyT: 0, space: { iss: null, kp: null, launch: null }, launches: [], starlink: { sats: [], next: null },
      night: null, fxSeq: 0, fx: [], hist: { cpu: [], rx: [], tx: [] }, sys: null, pc: null };
    var groups = {}, passes = [], track = [], issUp = false, launched = {}, told = {}, routes = {}, routeQ = [], timers = [], fxCbs = [];
    var adsbRaw = [], adsbT = 0, osRaw = [], osT = 0, types = {}, typeQ = [];   // two plane feeds, types by hex
    var reach = planesNm(cfg.planesNm);
    var polish = cfg.polish !== false;   // 2.15.1 Pulse 1.8's look; pulse-tv.js turns it off on a TV that can't keep up
    function fx(kind, text, extra) {
      S.fxSeq++;
      var f = { seq: S.fxSeq, kind: kind, text: String(text || "").slice(0, 60), small: false, t: clock() }, k;
      for (k in (extra || {})) if (extra.hasOwnProperty(k)) f[k] = extra[k];
      S.fx.push(f); if (S.fx.length > 30) S.fx.shift();
      fxCbs.forEach(function (cb) { try { cb(f); } catch (e) {} });
    }
    function every(ms, fn) { fn(); timers.push(setInterval(fn, ms)); }
    function quiet(p) { return p && p.then ? p.then(null, function () { return null; }) : p; }

    function weather() {
      if (!hasLoc) return;
      var q = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon +
        "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m,relative_humidity_2m,is_day,precipitation,cloud_cover" +
        "&hourly=temperature_2m,precipitation_probability,weather_code&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset&timezone=auto&forecast_days=2" +
        (cfg.imperial ? "&temperature_unit=fahrenheit&wind_speed_unit=mph" : "");
      quiet(net(q)).then(function (j) { if (j && j.current) S.weather = weatherFrom(j, cfg.city || "", !!cfg.imperial); });
    }
    function night() { if (hasLoc) S.night = nightSky(lat, lon, clock()); }
    function sky() {
      if (!hasLoc || cfg.planes === false) return;
      quiet(net("https://api.adsb.lol/v2/lat/" + lat.toFixed(4) + "/lon/" + lon.toFixed(4) + "/dist/" + reach)).then(function (j) {
        if (j) {
          adsbRaw = (j.ac || j.aircraft || []).map(function (a) {
            return { hex: a.hex, flight: a.flight, lat: a.lat, lon: a.lon, alt: a.alt_baro === "ground" ? 0 : a.alt_baro, gs: a.gs, track: a.track, type: a.t, ground: a.alt_baro === "ground" };
          });
          adsbT = clock();
        }
        mergeSky();
      });
    }
    // OpenSky (screensaver.opensky, off by default). Up to 25 sq deg costs 1 credit, a 200 nm box 2: anonymous (400 a day)
    // one read every 8 minutes; signed in through the
    // launcher (cfg.openskyAuth, 4000 a day) one a minute, which leaves room for the saver running all day and night
    function opensky() {
      if (!hasLoc || cfg.planes === false || !cfg.opensky) return;
      var dLat = reach / 60, dLon = reach / (60 * Math.max(0.2, Math.cos(lat * Math.PI / 180)));
      quiet(net("https://opensky-network.org/api/states/all?lamin=" + (lat - dLat).toFixed(2) + "&lamax=" + (lat + dLat).toFixed(2) +
        "&lomin=" + (lon - dLon).toFixed(2) + "&lomax=" + (lon + dLon).toFixed(2))).then(function (j) {
        if (j && j.states) { osRaw = openskyStates(j.states); osT = clock(); mergeSky(); }
      });
    }
    function mergeSky() {
      var t = clock(), adsb = t - adsbT < 120 ? adsbRaw : [], os = t - osT < 900 ? osRaw : [];
      if (!adsbT && !osT) return;
      var raw = mergePlanes(adsb, os, t, types);
      var pl = planesOver(lat, lon, raw), near = planesNear(lat, lon, raw, 5, reach);
      pl.concat(near).forEach(function (p) {
        var r = routes[p.flight];
        if (r) withRoute(p, r);
        else if (p.flight && routeQ.indexOf(p.flight) < 0 && !(p.flight in routes)) routeQ.push(p.flight);
      });
      near.forEach(function (p) { if (!p.type && p.hex && !(p.hex in types) && typeQ.indexOf(p.hex) < 0) typeQ.push(p.hex); });
      S.sky = pl; S.near = near; S.skyT = t;
    }
    function route() {   // where a flight comes from and goes (adsbdb.com), one lookup every few seconds, kept for the day
      var f = routeQ.shift();
      if (!f) {   // no route to look up: the type of a plane OpenSky saw (adsbdb, by its ICAO address)
        var h = typeQ.shift();
        if (!h) return;
        types[h] = "";
        quiet(net("https://api.adsbdb.com/v0/aircraft/" + encodeURIComponent(h))).then(function (j) {
          var a = j && j.response && j.response.aircraft;
          if (a && a.icao_type) { types[h] = String(a.icao_type); mergeSky(); }   // the code: planesNear names it
        });
        return;
      }
      routes[f] = null;
      quiet(net("https://api.adsbdb.com/v0/callsign/" + encodeURIComponent(f))).then(function (j) {
        var r = j && j.response && j.response.flightroute;
        if (!r) { routes[f] = {}; return; }
        var o = r.origin || {}, d = r.destination || {};
        routes[f] = { from: o.iata_code || o.icao_code || "", to: d.iata_code || d.icao_code || "", fromCity: o.municipality || "",
          toCity: d.municipality || "", airline: (r.airline || {}).name || "",
          oLat: num(o.latitude), oLon: num(o.longitude), dLat: num(d.latitude), dLon: num(d.longitude) };
        mergeSky();   // 1.8 tag: the route line (FCO → JFK) as soon as adsbdb answers, not at the next poll
      });
    }
    function iss() {
      if (!hasLoc || cfg.iss === false) return;
      var t = clock();
      if (!track.length || track[track.length - 1].timestamp - t < 120) {
        var ts = [], i;
        for (i = 0; i < 10; i++) ts.push(Math.floor(t + i * 60));
        quiet(net("https://api.wheretheiss.at/v1/satellites/25544/positions?units=kilometers&timestamps=" + ts.join(","))).then(function (j) { if (j && j.length) track = j; });
      }
      // 2.15 "Look up": the next minutes of the track say when the ISS rises above 10°, where, and how high it goes
      var nx = null, k;
      for (k = 0; k < track.length; k++) {
        var q = track[k];
        if (!q || q.timestamp <= t) continue;
        var lq = lookAngles(lat, lon, q.latitude, q.longitude, q.altitude);
        if (lq[1] > 10 && q.visibility === "daylight") { if (!nx) nx = { start: q.timestamp, az: rnd(lq[0]), max_el: Math.round(lq[1]) }; else if (lq[1] > nx.max_el) { nx.max_el = Math.round(lq[1]); nx.az = rnd(lq[0]); } }
        else if (nx) break;
      }
      S.issNext = nx;
      var p = issAt(track, t);
      if (!p) return;
      var l = lookAngles(lat, lon, p.latitude, p.longitude, p.altitude);
      S.space.iss = l[1] > 0 ? { az: rnd(l[0], 1), el: rnd(l[1], 1), alt: Math.round(p.altitude), vel: Math.round(p.velocity || 0), lit: p.visibility === "daylight" } : null;
      if (l[1] > 10 && !issUp) fx("iss", "ISS overhead · " + Math.round(p.velocity || 27600).toLocaleString("en") + " km/h", { az: rnd(l[0], 1) });
      issUp = l[1] > 10;
    }
    // 2.15 more of what the TV can read on its own (free public services, no key): the air, earthquakes, Omarchy
    function airq() {
      if (!hasLoc) return;
      quiet(net("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + lat + "&longitude=" + lon +
        "&current=european_aqi,alder_pollen,birch_pollen,grass_pollen,mugwort_pollen,olive_pollen,ragweed_pollen")).then(function (j) { S.air = airFrom(j); });
    }
    function quakes() {
      quiet(net("https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/4.5_day.geojson")).then(function (j) { if (j && j.features) S.quakes = quakesFrom(j, lat, lon); });
    }
    function omarchy() {   // 60 calls an hour without a key: 4 a day here
      quiet(net("https://api.github.com/repos/basecamp/omarchy/releases?per_page=4")).then(function (j) {
        if (j && j.length) S.omarchy = j.filter(function (r) { return r && r.tag_name && !r.draft; }).slice(0, 4).map(function (r) {
          return { title: "Omarchy " + String(r.tag_name).replace(/^v/, ""), text: String(r.name || "") === String(r.tag_name) ? "" : String(r.name || "").slice(0, 60), t: Math.floor(Date.parse(r.published_at) / 1000) || 0 };
        });
      });
    }
    function kp() { quiet(net("https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json")).then(function (j) { var k = latestKp(j); if (k != null) S.space.kp = k; }); }
    function launches() {
      if (cfg.launches === "off") return;
      var who = { spacex: "SpaceX", rocketlab: "Rocket Lab", blueorigin: "Blue Origin" }[cfg.launches || "spacex"];
      quiet(net("https://ll.thespacedevs.com/2.3.0/launches/upcoming/?limit=5&mode=list" + (who ? "&lsp__name=" + encodeURIComponent(who) : ""))).then(function (j) {
        if (j && j.results) S.launches = j.results.map(parseLaunch).filter(Boolean);
      });
    }
    function launchTick() {
      var t = clock(), ls = S.launches.filter(function (x) { return x.net > t - 120; });
      ls.forEach(function (x) {
        if (!launched[x.id] && x.net <= t && t < x.net + 90 && /^(Go|In Flight|Success)$/.test(x.status)) {
          launched[x.id] = 1; fx("launch", x.rocket + " · " + x.mission + " · liftoff");
        }
      });
      var nxt = null, i;
      for (i = 0; i < ls.length; i++) if (ls[i].net > t - 60) { nxt = ls[i]; break; }
      S.space.launch = nxt;
    }
    function starlinkFetch() {
      if (!hasLoc || cfg.starlink === false) return;
      quiet(net("https://celestrak.org/NORAD/elements/gp.php?GROUP=last-30-days&FORMAT=json")).then(function (rows) {
        if (!rows || !rows.length) return;
        groups = starlinkGroups(rows);
        passes = starlinkPasses(groups, lat, lon, clock(), 24);
      });
    }
    function starlinkTick() {
      var t = clock();
      passes = passes.filter(function (p) { return p.end > t; });
      var nxt = passes[0] || null, sats = [];
      if (nxt && nxt.start - 120 <= t && t <= nxt.end + 60) {
        (groups[nxt.group] || []).forEach(function (o) { var l = satLook(o, lat, lon, t); if (l[1] > 0 && l[2]) sats.push({ az: rnd(l[0], 2), el: rnd(l[1], 2) }); });
      }
      if (nxt && 0 < nxt.start - t && nxt.start - t <= 300 && !told[nxt.group + nxt.start]) {
        told[nxt.group + nxt.start] = 1;
        var at = new Date(nxt.start * 1000), hh = at.getHours(), mm = at.getMinutes();
        fx("starlink", "Starlink train at " + (hh < 10 ? "0" : "") + hh + ":" + (mm < 10 ? "0" : "") + mm + " · " + compass(nxt.az) + " · " + nxt.n + " satellites");
      }
      S.starlink = { sats: sats, next: nxt ? { start: nxt.start, end: nxt.end, max_el: nxt.max_el, az: nxt.az, n: nxt.n } : null };
    }
    function tvNumbers() {
      var s = cfg.tv ? cfg.tv() : null;
      if (!s) { S.sys = null; return; }
      S.sys = s;
      ["cpu", "rx", "tx"].forEach(function (k) { if (typeof s[k] === "number") { S.hist[k].push(s[k]); if (S.hist[k].length > 60) S.hist[k].shift(); } });
    }
    function pcState() {
      var p = cfg.pc ? cfg.pc() : null;
      if (!p) { S.pc = null; return; }
      quiet(p).then(function (j) { S.pc = j && j.v ? j : null; });
    }

    function start() {
      var E = cfg.every || {};
      every(E.weather || 900000, weather);
      every(E.night || 30000, night);
      every(E.sky || 20000, sky);
      if (cfg.opensky) every(E.opensky || (cfg.openskyAuth ? 60000 : 480000), opensky);
      every(E.route || 4000, route);
      every(E.iss || 5000, iss);
      every(E.kp || 900000, kp);
      every(E.air || 1800000, airq);
      if (cfg.earthquakes !== false) every(E.quakes || 900000, quakes);   // screensaver.earthquakes: false, no panel, no USGS fetch
      every(E.omarchy || 6 * 3600000, omarchy);
      every(E.launches || 900000, launches);   // the free API allows 15 calls an hour: 4 here
      every(E.launchTick || 1000, launchTick);
      every(E.starlink || 6 * 3600000, starlinkFetch);
      every(E.starlinkTick || 5000, starlinkTick);
      every(E.tv || 2000, tvNumbers);
      if (cfg.pc) every(E.pc || 2000, pcState);
    }
    function stop() { timers.forEach(clearInterval); timers = []; }

    /** The /api/state the page reads: the PC's, when one is paired, with the TV's own sky and numbers underneath. */
    function state() {
      var t = clock(), w = S.weather, city = (w && w.city) || cfg.city || "";
      var ui = { fps: cfg.fps || 20, clock: cfg.clock || "24h", pause_when_covered: false, scene: cfg.scene || "sphere", motion: cfg.motion || "full",
        skyline: cityKey(city), skylineAuto: true, font: "", title: "", dim_with_windows: false, system: !!S.sys, network: !!(S.sys && S.sys.rx != null),
        face: "agents", agentsAs: "drones", banner: "OMARCHY TV · WE CAN FIX EVERYTHING", bannerEvery: 45, fireworks: true, infraWeather: true,
        wallpaper: null, musicCover: null, wled: false, tipsEvery: 40, ivy: false, secondary: "full", hackerStyle: "phosphor", nocHide: [],
        music: false, sun: true, polish: polish, quality: cfg.quality || "auto", sound: null, sphere: "real", cars: false, eyes: true };
      var ls = S.launches.filter(function (x) { return x.net > t - 120; }).slice(0, 3);
      var widgets = ls.length ? [{ id: "launches", title: "launches", type: "list", slot: "ticker", order: 27, icon: "󱓞",
        data: { items: ls.map(function (x) { return { title: x.rocket + " · " + x.mission, text: countdown(x.net - t) + " · " + x.status, t: x.net }; }) } }] : [];
      widgets = widgets.concat(tvPanels(t));
      var st = { v: "1.8.0-tv", t: t, demo: false, tv: true, sys: S.sys, hist: S.hist, weather: w, widgets: widgets, alerts: [], privacy: false, screencast: false,
        mons: {}, theme: cfg.theme || { name: "", colors: {} }, agents: [], focus: null, git: [], space: { iss: S.space.iss, kp: S.space.kp, launch: S.space.launch },
        heart: null, listen: null, speaking: false, fxSeq: S.fxSeq, music: false, perfProbe: false, growth: null,
        sky: t - S.skyT < 120 ? S.sky : [], calendar: [], air: S.air, meeting: false, night: S.night, season: {}, eclipses: [], expiry: [], energy: null, net: null, bolts: [],
        term: [], noc: { on: false, incident: null }, starlink: S.starlink, storms: null, robots: [], car: null, ring: false, timelapse: false,
        timelapseEvery: 0, away: false, digest: null, tower: [], tips: TIPS, updates: null, layout: {}, layoutEdit: false, friends: [], lan: [],
        scene: null, sceneTicket: "", ui: ui };
      if (SCENES[ui.scene] && cfg.scenes === false) ui.scene = "sphere";   // a TV browser that can't frame a scene: the sphere
      var pc = S.pc;
      if (pc) {   // the PC knows everything the TV can't: its agents, workspaces, repos, panels... the TV keeps its own sky
        ["agents", "focus", "git", "tower", "updates", "widgets", "alerts", "calendar", "meeting", "growth", "friends", "robots", "expiry",
          "energy", "car", "music", "heart", "digest", "away", "privacy", "tips", "lan", "term", "noc", "bolts", "storms", "air", "eclipses", "season"].forEach(function (k) {
          if (pc[k] !== undefined && pc[k] !== null) st[k] = pc[k];
        });
        var rp = pc.widgets ? racingPanel(t) : null;   // 2.15.1 the TV's own racing panel stays beside the PC's panels
        if (pc.sys) { st.sys = pc.sys; st.hist = pc.hist || st.hist; st.ui.system = true; st.ui.network = true; }
        // the PC's panels take the screen; of the TV's own only the launches stay (in the ticker)
        if (pc.widgets) st.widgets = pc.widgets.concat(widgets.filter(function (x) { return x.id === "launches" && !pc.widgets.some(function (y) { return y.id === x.id; }); }));
        if (rp) st.widgets.push(rp);
        if (pc.theme && pc.theme.colors && Object.keys(pc.theme.colors).length) st.theme = pc.theme;   // the PC's colours: the same Pulse on both
        if (!S.air && pc.air) st.air = pc.air;
        if (pc.ui) ["face", "agentsAs", "ivy", "banner", "music", "cars"].forEach(function (k) { if (pc.ui[k] !== undefined) st.ui[k] = pc.ui[k]; });
        if (!w && pc.weather) st.weather = pc.weather;
        if (!S.night && pc.night) st.night = pc.night;
        // LG and Samsung pages can't read adsb.lol or CelesTrak (no CORS): the PC's planes and Starlink train fill in
        if (!st.sky.length && pc.sky && pc.sky.length) st.sky = pc.sky;
        if (!(st.starlink.sats.length || st.starlink.next) && pc.starlink) st.starlink = pc.starlink;
        if (!st.space.launch && pc.space && pc.space.launch) st.space.launch = pc.space.launch;
      }
      if (SCENES[ui.scene]) { st.sceneTicket = "tv"; st.scene = sceneState(st, t); }   // 2.15.1 Pulse 1.8's scenes (mars, monolith)
      return st;
    }
    /** What a scene sees (Pulse's docs/SCENES.md): the same read-only copy the PC's hub gives, from what the TV knows. */
    function sceneState(st, t) {
      var d = new Date(t * 1000), w = st.weather, c = (st.theme && st.theme.colors) || {}, n = st.night || {}, th = {};
      [["bg", "background"], ["fg", "foreground"], ["accent", "accent"], ["accent2", "color5"], ["ok", "color2"], ["warn", "color3"], ["crit", "color1"], ["dim", "color8"]]
        .forEach(function (k) { if (/^#[0-9a-fA-F]{6}$/.test(c[k[1]] || "")) th[k[0]] = c[k[1]]; });
      var sy = st.sys || {}, panels = {};
      (st.widgets || []).forEach(function (x) {   // the PC's status panels (rows with a level): a scene's services
        var rows = x && x.data && x.data.rows;
        if (x && x.id && rows && rows.some && rows.some(function (r) { return r && r.level; }))
          panels[String(x.id).slice(0, 32)] = { rows: rows.slice(0, 24).map(function (r) { return { name: String(r.name || r.title || "").slice(0, 40), level: r.level || "" }; }) };
      });
      return { v: "1.8.0-tv", t: t, demo: false, privacy: !!st.privacy,
        time: { hour: d.getHours(), minute: d.getMinutes(), second: d.getSeconds(), weekday: (d.getDay() + 6) % 7, day: d.getDate(), month: d.getMonth() + 1, year: d.getFullYear() },
        sys: { cpu: sy.cpu, cores: sy.cores, ram: sy.ram, swap: sy.swap, gpu: sy.gpu, temp: sy.temp, disk: sy.disk, rx: sy.rx, tx: sy.tx, load: sy.load },
        agents: (st.agents || []).slice(0, 24).map(function (a) { return { name: a.name || "", state: a.state || "working", since: a.since || 0, text: a.text || "" }; }),
        git: (st.git || []).map(function (r) { return { name: r.name || "", branch: r.branch || "", week: r.week || 0, last: r.last || 0, dirty: r.dirty || 0, ci: r.ci || "" }; }),
        weather: w && !w.error ? { code: w.code, temp: w.temp, day: w.day, wind: w.wind, windDir: w.windDir, cloud: w.cloud, rain: w.rain } : null,
        focus: st.focus ? { start: st.focus.start, until: st.focus.until } : null, away: !!st.away, meeting: !!st.meeting, theme: th,
        ui: { quality: st.ui.quality === "half" ? "half" : st.ui.quality === "full" ? "full" : "auto", motion: st.ui.motion === "reduced" ? "reduced" : "full", fps: st.ui.fps || 20 },
        events: S.fx.slice(-12).map(function (x) { return { kind: x.kind, text: x.text, t: x.t }; }), season: st.season || {}, music: !!st.music,
        space: { sun: n.sun ? n.sun.el : null, moon: n.moon && n.moon.lit != null ? Math.round(n.moon.lit * 100) : null, planes: (st.sky || []).length, kp: st.space ? st.space.kp : null,
          launchIn: st.space && st.space.launch && st.space.launch.net ? Math.round((st.space.launch.net - t) / 60) : null },
        panels: panels, layout: { horizon: 0.745 } };
    }
    function racingPanel(t) {
      var items = cfg.racing && cfg.racing.length ? racingItems(cfg.racing, t, 5) : [];
      return items.length ? { id: "tv-racing", title: "racing", type: "list", slot: "right", order: 21, icon: "󰄶", data: { items: items } } : null;
    }
    /** 2.15 the panels of a TV with no PC: everything it can read by itself. */
    function tvPanels(t) {
      var out = [], n = S.night, w = S.weather;
      if (n && n.moon) {
        var rows = [["moon", n.moon.name + " · " + Math.round(n.moon.lit * 100) + "%"]];
        if (w && w.sunrise) rows.push(["sun", "↑ " + w.sunrise + "  ↓ " + w.sunset]);
        var up = (n.planets || []).filter(function (p) { return p.el > 5; }).map(function (p) { return p.n; });
        rows.push(["planets", up.length ? up.join(" · ") : "none up"]);
        if (n.shower) rows.push(["meteors", n.shower.name || n.shower.n || "shower"]);
        out.push({ id: "tv-sky", title: "sky tonight", type: "kv", slot: "left", order: 30, icon: "", data: { rows: rows } });
      }
      var sp = [], is = S.space.iss;
      sp.push(["ISS", is ? (is.el < 3 ? "rising · " : "up · ") + compass(is.az) + (is.el >= 3 ? " " + Math.round(is.el) + "°" : "") : S.issNext ? "rises in " + Math.max(0, Math.round((S.issNext.start - t) / 60)) + " min" : "below the horizon"]);
      var nx = S.starlink.next;
      if (nx) { var at = new Date(nx.start * 1000); sp.push(["Starlink", (at.getHours() < 10 ? "0" : "") + at.getHours() + ":" + (at.getMinutes() < 10 ? "0" : "") + at.getMinutes() + " · " + compass(nx.az) + " · " + nx.n + " sats"]); }
      if (S.space.kp != null) sp.push(["aurora", "Kp " + S.space.kp + (S.space.kp >= 5 ? " · storm!" : S.space.kp >= 4 ? " · active" : " · quiet")]);
      var l0 = S.launches.filter(function (x) { return x.net > t - 120; })[0];
      if (l0) sp.push(["launch", l0.rocket + " · " + countdown(l0.net - t)]);
      out.push({ id: "tv-space", title: "space", type: "kv", slot: "left", order: 31, icon: "󰑣", data: { rows: sp } });
      if (reach === 25) {   // the planes in your sky
        var pl = t - S.skyT < 120 ? S.sky : [];
        if (pl.length) out.push({ id: "tv-planes", title: "overhead", type: "list", slot: "right", order: 30, icon: "󰀝", data: { items: pl.slice(0, 4).map(function (p) {
          return { title: (p.flight || p.hex || "?") + (p.type ? " · " + p.type : ""), text: p.from && p.to ? p.from + " → " + p.to : p.airline || "", right: ftText(p.ft) };
        }) } });
      } else if (hasLoc && cfg.planes !== false) {   // further out: the nearest five, the panel stays up
        var fresh = S.skyT && t - S.skyT < 120, near = fresh ? S.near || [] : [];
        out.push({ id: "tv-planes", title: "planes · " + reach + " nm", type: "list", slot: "right", order: 30, icon: "󰀝", data: {
          items: near.map(function (p) {
            var where = p.from && p.to ? p.from + " → " + p.to : p.airline || "";
            return { title: (p.flight || p.hex || p.id || "?") + (p.type ? " · " + p.type : ""), text: (where ? where + " · " : "") + p.nm + " nm", right: ftText(p.ft) };
          }), note: near.length ? "" : fresh ? "no planes within " + reach + " nm" : "looking for planes…" } });
      }
      if (S.quakes.length) out.push({ id: "tv-quakes", title: "earthquakes", type: "list", slot: "right", order: 32, icon: "󰖟", data: { items: S.quakes.slice(0, 4) } });
      var rp = racingPanel(t);
      if (rp) out.push(rp);
      if (S.omarchy.length) out.push({ id: "tv-omarchy", title: "omarchy", type: "list", slot: "right", order: 34, icon: "", data: { items: S.omarchy.slice(0, 3) } });
      return out;
    }
    /**
     * 2.15 what deserves the whole screen, now: "look up" (the ISS or a Starlink train over you, from 3 minutes before
     * until it has gone; Starlink only when it is dark and lit) or a launch countdown (T-10 minutes to liftoff, Go only).
     */
    function events() {
      var t = clock(), out = [], dark = !!(S.night && S.night.sun && S.night.sun.el < -6);
      var is = S.space.iss;   // the ISS is seen only at night, while the Sun still lights it
      if (!dark) { /* by day: no "look up" for the ISS */ }
      else if (is && is.el > 10 && is.lit) out.push({ kind: "iss", phase: "now", az: is.az, el: is.el, text: "ISS" });
      else if (S.issNext && S.issNext.start - t <= 180) out.push({ kind: "iss", phase: "soon", az: S.issNext.az, el: S.issNext.max_el, in: S.issNext.start - t, text: "ISS" });
      var sn = S.starlink.next;
      if (sn && sn.start - t <= 180 && t <= sn.end) out.push({ kind: "starlink", phase: t >= sn.start ? "now" : "soon", az: sn.az, el: sn.max_el, in: sn.start - t, left: sn.end - t, n: sn.n, text: "Starlink" });
      var L = S.space.launch;
      if (L && L.status === "Go" && L.net - t <= 600 && L.net - t > -5) out.push({ kind: "launch", phase: "countdown", in: L.net - t, rocket: L.rocket, mission: L.mission });
      return out;
    }
    return { start: start, stop: stop, state: state,
      setPolish: function (on) { polish = !!on; }, polish: function () { return polish; },
      setQuality: function (q) { cfg.quality = q; }, quality: function () { return cfg.quality || "auto"; },
      events: events, onFx: function (cb) { fxCbs.push(cb); }, fx: fx, _S: S,
      openskyEvery: cfg.openskyAuth ? 60000 : 480000,
      _tick: { weather: weather, night: night, sky: sky, opensky: opensky, route: route, iss: iss, kp: kp, launches: launches, launchTick: launchTick,
        starlinkFetch: starlinkFetch, starlinkTick: starlinkTick, tv: tvNumbers, pc: pcState, air: airq, quakes: quakes, omarchy: omarchy } };
  }

  /**
   * Auto graphics for the TV: start at Pulse 1.8's look (rung 0), step down when frames are slow, step
   * back up when there is headroom. Down after 3 slow reports, up after 6 fast ones (hysteresis).
   * rungs: 0 polish+auto (PC default), 1 polish+half pixels, 2 plain look + half pixels.
   */
  var GFX_RUNGS = [{ polish: true, quality: "auto" }, { polish: true, quality: "half" }, { polish: false, quality: "half" }];
  function adaptPerf(s, pf) {
    s = s || { rung: 0, slow: 0, fast: 0 };
    pf = pf || {};
    var slow = pf.draw > 16 || (pf.want && pf.fps < pf.want * 0.6);
    var head = pf.draw < 10 && (!pf.want || pf.fps > pf.want * 0.9);
    var out = { rung: s.rung || 0, slow: 0, fast: 0, changed: "" };
    if (slow) { out.slow = (s.slow || 0) + 1; out.fast = 0; }
    else if (head) { out.fast = (s.fast || 0) + 1; out.slow = 0; }
    if (out.slow >= 3 && out.rung < GFX_RUNGS.length - 1) { out.rung += 1; out.slow = 0; out.changed = "down"; }
    else if (out.fast >= 6 && out.rung > 0) { out.rung -= 1; out.fast = 0; out.changed = "up"; }
    out.level = GFX_RUNGS[out.rung];
    return out;
  }

  root.OmarchyPulseTV = { create: create, airFrom: airFrom, quakesFrom: quakesFrom, nightSky: nightSky, altaz: altaz, lookAngles: lookAngles, satLook: satLook, starlinkGroups: starlinkGroups,
    starlinkPasses: starlinkPasses, issAt: issAt, planesOver: planesOver, racingItems: racingItems, planesNm: planesNm, openskyStates: openskyStates, mergePlanes: mergePlanes, typeName: typeName, routeFits: routeFits, ftText: ftText, latestKp: latestKp, parseLaunch: parseLaunch, countdown: countdown,
    cityKey: cityKey, weatherFrom: weatherFrom, compass: compass, julian: julian, moonPhase: moonPhase, TIPS: TIPS,
    adaptPerf: adaptPerf, GFX_RUNGS: GFX_RUNGS };
  if (typeof module !== "undefined" && module.exports) module.exports = root.OmarchyPulseTV;
})(typeof window !== "undefined" ? window : this);
