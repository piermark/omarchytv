/* Omarchy Pulse: the wallpaper page. Reads /api/state from the local hub once a second and draws:
   a scene that is the data (the skyline is your CPU cores, the horizon beats with your load, the aurora is your
   network, the weather falls from the sky) and Omarchy-style panels for every source.
   (c) 2026 Marco Porro (x.com/MarcoPorro), MIT. */
(function () {
  "use strict";
  var Q = new URLSearchParams(location.search);
  var TOKEN = Q.get("token") || "";
  var MONKEY = (Q.get("mx") || "0") + "," + (Q.get("my") || "0");
  var MX = +Q.get("mx") || 0, MY = +Q.get("my") || 0;
  var PRIMARY = Q.get("primary") !== "0";
  var S = null, stateAt = 0;  // last state, and when it came
  function hubNowG() { return S && S.t ? S.t + (performance.now() - stateAt) / 1000 : Date.now() / 1000; }   // 1.6: the hub's clock
  var covered = false;        // this monitor is almost entirely covered by windows
  var seenAlert = -1;         // alerts already shown
  var themeKey = "";
  var $ = function (s, r) { return (r || document).querySelector(s); };

  // ------------------------------------------------------------------ colours from the Omarchy theme
  var COL = {};
  function hexRgb(h) {
    h = String(h || "").replace("#", "");
    if (h.length === 3) h = h.replace(/(.)/g, "$1$1");
    var n = parseInt(h.slice(0, 6), 16);
    return isNaN(n) ? null : [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }
  function lum(c) { return (0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]) / 255; }
  // 1.5.1: wallpaper_colors = "picture": the panels and the effects take their colours from your own wallpaper
  // (background, text, the two accents); green, amber and red keep the theme's, so their meaning never changes
  var pictureColors = null, musicColors = null, coverKey = "";
  function pictureTheme(t) {
    if (!pictureColors && !musicColors) return t;
    return { name: (t && t.name) || "", colors: Object.assign({}, (t && t.colors) || {}, pictureColors || {}, musicColors || {}) };
  }
  // 1.6: [music] colors = true: while a song plays, the accents come from its cover (Spotify, YouTube Music, any
  // player); background and text stay, so everything stays easy to read; when the music stops, the theme comes back
  function musicSync(c) {
    var key = c && c.v ? c.v : "";
    if (key === coverKey) return;
    coverKey = key;
    if (!key) { if (musicColors) { musicColors = null; themeKey = ""; setTimeout(render, 0); } return; }
    var im = new Image();
    im.onload = function () {
      if (coverKey !== key) return;
      var p = null; try { p = paletteOf(im); } catch (e) { p = null; }
      musicColors = p ? { accent: p.accent, color6: p.color6, color5: p.color5, color13: p.color13 } : null;
      themeKey = ""; render();
    };
    im.src = "/api/cover?token=" + encodeURIComponent(TOKEN) + "&v=" + encodeURIComponent(key);
  }
  function paletteOf(img) {
    var c = document.createElement("canvas"); c.width = 64; c.height = 36;
    var x = c.getContext("2d", { willReadFrequently: true }); x.drawImage(img, 0, 0, 64, 36);
    var d = x.getImageData(0, 0, 64, 36).data, px = [], bins = [];
    for (var i = 0; i < 24; i++) bins.push({ w: 0, r: 0, g: 0, b: 0 });
    for (i = 0; i < d.length; i += 4) {
      var r = d[i], g = d[i + 1], b = d[i + 2], mx = Math.max(r, g, b), mn = Math.min(r, g, b), v = mx / 255, sat = mx ? (mx - mn) / mx : 0;
      px.push([r, g, b, (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255]);
      if (sat > 0.3 && v > 0.3) {
        var h = mx === mn ? 0 : mx === r ? ((g - b) / (mx - mn) + 6) % 6 : mx === g ? (b - r) / (mx - mn) + 2 : (r - g) / (mx - mn) + 4;
        var bn = bins[Math.floor(h / 6 * 24) % 24], w = sat * v; bn.w += w; bn.r += r * w; bn.g += g * w; bn.b += b * w;
      }
    }
    px.sort(function (a, b) { return a[3] - b[3]; });
    var dark = [0, 0, 0], n = Math.max(1, Math.floor(px.length * 0.2));
    for (i = 0; i < n; i++) { dark[0] += px[i][0]; dark[1] += px[i][1]; dark[2] += px[i][2]; }
    dark = dark.map(function (v2) { return v2 / n; });
    var order = bins.map(function (bn, k) { return [bn.w, k]; }).sort(function (a, b) { return b[0] - a[0]; });
    var avgBin = function (k) { var bn = bins[k]; return bn.w ? [bn.r / bn.w, bn.g / bn.w, bn.b / bn.w] : null; };
    var bright = function (c2) { var m = Math.max(c2[0], c2[1], c2[2]) || 1, k2 = Math.max(1, 210 / m); return c2.map(function (v2) { return Math.min(255, v2 * k2); }); };
    var a1 = order[0][0] ? avgBin(order[0][1]) : null, a2 = null;
    for (i = 1; i < order.length && !a2; i++) if (order[i][0] > order[0][0] * 0.05 && Math.min(Math.abs(order[i][1] - order[0][1]), 24 - Math.abs(order[i][1] - order[0][1])) >= 4) a2 = avgBin(order[i][1]);
    if (!a1) return null;   // a picture without colour (black and white): keep the theme
    a1 = bright(a1); a2 = a2 ? bright(a2) : mix(a1, [255, 255, 255], 0.45);   // one colour only: a lighter tint of it, never a colour the picture does not have
    var hex = function (c2) { return "#" + c2.map(function (v2) { return ("0" + Math.round(v2).toString(16)).slice(-2); }).join(""); };
    var bg = mix(dark, [0, 0, 0], 0.45), fg = mix([238, 238, 242], a1, 0.12);
    return { background: hex(bg), foreground: hex(fg), accent: hex(a1), color6: hex(a1), color5: hex(a2), color13: hex(a2) };
  }
  function applyTheme(t) {
    t = pictureTheme(t);
    var c = (t && t.colors) || {};
    var key = JSON.stringify(c);
    if (key === themeKey) return;
    themeKey = key;
    var acc = c.accent || c.color6 || c.cyan || c.color4 || c.blue;
    var map = { bg: c.background, fg: c.foreground, accent: acc,
      accent2: c.accent2 || c.color5 || c.color13 || c.magenta || c.bright_magenta || acc,   // 1.5.2: never the default pink
      ok: c.color2 || c.green, warn: c.color3 || c.yellow, crit: c.color1 || c.red, dim: c.color8 || c.muted || c.bright_black };
    var root = document.documentElement.style;
    Object.keys(map).forEach(function (k) {
      var rgb = hexRgb(map[k]);
      if (!rgb) { root.removeProperty("--" + k); root.removeProperty("--" + k + "-rgb"); return; }
      root.setProperty("--" + k, map[k]);
      root.setProperty("--" + k + "-rgb", rgb.join(","));
    });
    var cs = getComputedStyle(document.documentElement);
    ["bg", "fg", "accent", "accent2", "ok", "warn", "crit", "dim"].forEach(function (k) {
      COL[k] = (cs.getPropertyValue("--" + k + "-rgb") || "0,0,0").trim().split(",").map(Number);
    });
    COL.light = lum(COL.bg) > 0.5;
    Scene.reset();
  }
  function rgba(c, a) { return "rgba(" + c[0] + "," + c[1] + "," + c[2] + "," + a + ")"; }
  function mix(a, b, t) { return [a[0] + (b[0] - a[0]) * t | 0, a[1] + (b[1] - a[1]) * t | 0, a[2] + (b[2] - a[2]) * t | 0]; }

  // ------------------------------------------------------------------ formatting
  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }
  function bytes(n, rate) {
    var u = rate ? ["B/s", "KB/s", "MB/s", "GB/s"] : ["B", "KB", "MB", "GB", "TB"], i = 0;
    n = +n || 0;
    while (n >= 1000 && i < u.length - 1) { n /= 1000; i++; }
    return (n >= 100 || i === 0 ? n.toFixed(0) : n.toFixed(1)) + " " + u[i];
  }
  function ago(t) {
    if (!t) return "";
    var s = Math.max(0, Date.now() / 1000 - t);
    if (s < 60) return "now";
    if (s < 3600) return Math.floor(s / 60) + "m";
    if (s < 86400) return Math.floor(s / 3600) + "h";
    return Math.floor(s / 86400) + "d";
  }
  function upt(s) {
    s = +s || 0;
    var d = Math.floor(s / 86400), h = Math.floor(s % 86400 / 3600), m = Math.floor(s % 3600 / 60);
    return (d ? d + "d " : "") + (d || h ? h + "h " : "") + m + "m";
  }
  function num(v) {
    if (typeof v !== "number") return esc(v);
    var a = Math.abs(v);
    return a >= 10000 ? v.toLocaleString("en-US", { maximumFractionDigits: 0 }) : a >= 100 ? v.toFixed(0) : a >= 10 ? v.toFixed(1) : v.toFixed(2).replace(/\.?0+$/, "") || "0";
  }

  // ------------------------------------------------------------------ sparklines
  function spark(cv, series, opts) {
    opts = opts || {};
    var w = cv.clientWidth, h = cv.clientHeight;
    if (!w || !h) return;
    var dpr = Math.min(2, window.devicePixelRatio || 1);
    if (cv.width !== (w * dpr | 0)) { cv.width = w * dpr | 0; cv.height = h * dpr | 0; }
    var g = cv.getContext("2d");
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, w, h);
    var max = opts.max || 0;
    series.forEach(function (s) { (s.v || []).forEach(function (x) { if (x > max) max = x; }); });
    if (!max) max = 1;
    g.strokeStyle = rgba(COL.dim, 0.35); g.lineWidth = 1;
    g.beginPath(); g.moveTo(0, h - 0.5); g.lineTo(w, h - 0.5); g.stroke();
    series.forEach(function (s) {
      var v = s.v || [];
      if (v.length < 2) return;
      var n = Math.max(v.length, opts.n || 60);
      var step = w / (n - 1), x0 = w - (v.length - 1) * step;
      g.beginPath();
      v.forEach(function (x, i) { var y = h - 2 - (x / max) * (h - 5); if (i) g.lineTo(x0 + i * step, y); else g.moveTo(x0, y); });
      var grad = g.createLinearGradient(0, 0, 0, h);
      grad.addColorStop(0, rgba(s.c, 0.35)); grad.addColorStop(1, rgba(s.c, 0));
      g.lineWidth = 1.6; g.strokeStyle = rgba(s.c, 0.95); g.stroke();
      g.lineTo(w, h); g.lineTo(x0, h); g.closePath(); g.fillStyle = grad; g.fill();
    });
  }

  // ------------------------------------------------------------------ weather icons (drawn, so every font works)
  var WX = { 0: "clear", 1: "mostly clear", 2: "partly cloudy", 3: "overcast", 45: "fog", 48: "fog", 51: "drizzle", 53: "drizzle", 55: "drizzle",
    56: "freezing drizzle", 57: "freezing drizzle", 61: "rain", 63: "rain", 65: "heavy rain", 66: "freezing rain", 67: "freezing rain",
    71: "snow", 73: "snow", 75: "heavy snow", 77: "snow grains", 80: "showers", 81: "showers", 82: "violent showers",
    85: "snow showers", 86: "snow showers", 95: "thunderstorm", 96: "thunderstorm", 99: "thunderstorm" };
  function wxKind(c) {
    c = +c;
    if (c >= 95) return "storm";
    if ((c >= 71 && c <= 77) || c === 85 || c === 86) return "snow";
    if (c >= 51) return "rain";
    if (c === 45 || c === 48) return "fog";
    if (c === 3) return "cloud";
    if (c === 1 || c === 2) return "part";
    return "clear";
  }
  function wxSvg(code, day) {
    var k = wxKind(code), a = "var(--accent)", b = "var(--accent2)", f = "var(--fg)";
    var sun = day ? '<circle cx="34" cy="30" r="11" fill="none" stroke="' + b + '" stroke-width="2.5"/>' +
      [0, 45, 90, 135, 180, 225, 270, 315].map(function (d) { var r = d * Math.PI / 180; return '<line x1="' + (34 + Math.cos(r) * 15) + '" y1="' + (30 + Math.sin(r) * 15) + '" x2="' + (34 + Math.cos(r) * 19) + '" y2="' + (30 + Math.sin(r) * 19) + '" stroke="' + b + '" stroke-width="2.5" stroke-linecap="round"/>'; }).join("")
      : '<path d="M42 18a13 13 0 1 0 8 22a11 11 0 1 1 -8 -22z" fill="none" stroke="' + b + '" stroke-width="2.5"/>';
    var cloud = '<path d="M22 58h34a11 11 0 0 0 0-22a15 15 0 0 0-28-4a10 10 0 0 0-6 26z" fill="rgba(var(--bg-rgb),.9)" stroke="' + a + '" stroke-width="2.5" stroke-linejoin="round"/>';
    var drops = function (n, sym) {
      var o = "";
      for (var i = 0; i < n; i++) {
        var x = 28 + i * 9;
        o += sym === "snow" ? '<text x="' + (x - 3) + '" y="76" font-size="11" fill="' + f + '">*</text>'
          : '<line x1="' + x + '" y1="64" x2="' + (x - 4) + '" y2="74" stroke="' + a + '" stroke-width="2.5" stroke-linecap="round"/>';
      }
      return o;
    };
    var body = { clear: sun, part: sun + cloud, cloud: cloud, fog: cloud + '<line x1="18" y1="66" x2="66" y2="66" stroke="' + f + '" stroke-width="2" opacity=".6"/><line x1="24" y1="73" x2="60" y2="73" stroke="' + f + '" stroke-width="2" opacity=".4"/>',
      rain: cloud + drops(4), snow: cloud + drops(4, "snow"),
      storm: cloud + '<path d="M44 60l-7 10h7l-5 9" fill="none" stroke="var(--warn)" stroke-width="2.8" stroke-linejoin="round"/>' + drops(2) }[k];
    return '<svg viewBox="0 0 84 84">' + body + "</svg>";
  }

  // ------------------------------------------------------------------ panels
  var panels = {};   // id -> element
  function panel(id, title, icon) {
    var el = panels[id];
    if (!el) {
      el = document.createElement("div");
      el.className = "panel"; el.dataset.id = id;
      el.innerHTML = '<div class="legend"></div><div class="corner"></div><div class="body"></div><div class="err"></div><div class="cover">PRIVATE</div>';
      panels[id] = el;
    }
    var lg = (icon ? '<span class="ic">' + esc(icon) + "</span>" : "") + esc(title);
    if (el._lg !== lg) { el.querySelector(".legend").innerHTML = lg; el._lg = lg; }
    return el;
  }
  function setHTML(el, html) { if (el._h !== html) { el.innerHTML = html; el._h = html; } }
  function bodyOf(el) { return el.querySelector(".body"); }

  function sysPanel(s, h) {
    var el = panel("sys", "system", "󰍛");
    var b = bodyOf(el);
    if (!b._built) {
      b.innerHTML = '<div class="cpuhead"><div class="big"><span class="cpu">0</span><small>% cpu</small></div><div class="side"></div></div>' +
        '<canvas class="spark"></canvas><div class="cores"></div><div class="meters"></div>';
      b._built = true;
    }
    $(".cpu", b).textContent = Math.round(s.cpu || 0);
    setHTML($(".side", b), "load " + esc((s.load || []).join(" ")) + "<br>up " + upt(s.uptime));
    var cores = s.cores || [];
    var cb = $(".cores", b);
    if (cb.children.length !== Math.min(cores.length, 32)) { cb.innerHTML = cores.slice(0, 32).map(function () { return "<i></i>"; }).join(""); }
    cores.slice(0, 32).forEach(function (v, i) { cb.children[i].style.height = Math.max(4, v) + "%"; });
    spark($(".spark", b), [{ v: h.cpu, c: COL.accent }], { max: 100, n: 90 });
    var gb = function (n) { return (n / 1e9).toFixed(n >= 1e10 ? 0 : 1); };
    var m = [["ram", s.ram, gb(s.ramUsed) + "/" + gb(s.ramTotal) + "G"]];
    if (s.gpu != null) m.push(["gpu", s.gpu, Math.round(s.gpu) + "%"]);
    if (s.temp != null) m.push(["temp", Math.min(100, s.temp), Math.round(s.temp) + "°C", s.temp >= 85 ? "var(--crit)" : s.temp >= 75 ? "var(--warn)" : null]);
    m.push(["disk", s.disk, Math.round(s.disk) + "%", s.disk >= 90 ? "var(--warn)" : null]);
    if (s.bat) m.push(["bat", s.bat.pct, s.bat.pct + "%" + (s.bat.status === "Charging" ? " ⚡" : ""), s.bat.pct < 15 ? "var(--crit)" : null]);
    setHTML($(".meters", b), m.map(function (r) {
      return '<div class="meter"><span class="k">' + r[0] + '</span><div class="seg"><i style="width:' + Math.max(0, Math.min(100, r[1] || 0)).toFixed(1) + "%" + (r[3] ? ";--c:" + r[3] : "") + '"></i></div><span class="v">' + esc(r[2]) + "</span></div>";
    }).join(""));
    return el;
  }

  function netPanel(s, h) {
    var el = panel("net", "network", "󰛳");
    var b = bodyOf(el);
    if (!b._built) {
      b.innerHTML = '<div class="row"><span class="k">↓ down</span><span class="v rx"></span></div><div class="row"><span class="k">↑ up</span><span class="v tx"></span></div>' +
        '<canvas class="spark"></canvas><div class="row"><span class="k">' + esc(s.user || "") + "@" + esc(s.host || "") + '</span><span class="v ip"></span></div>';
      b._built = true;
    }
    $(".rx", b).textContent = bytes(s.rx, true);
    $(".tx", b).textContent = bytes(s.tx, true);
    $(".ip", b).textContent = s.ip || "offline";
    spark($(".spark", b), [{ v: h.rx, c: COL.accent }, { v: h.tx, c: COL.accent2 }], { n: 90 });
    return el;
  }

  function weatherPanel(w) {
    var el = panel("weather", "weather" + (w && w.city ? " · " + w.city : ""), "󰖐");
    var b = bodyOf(el);
    if (!w || w.error) { setHTML(b, '<div class="note">' + esc(w ? w.error : "") + "</div>"); return el; }
    var hrs = (w.hours || []).slice(0, 12);
    var tmin = Math.min.apply(null, hrs.map(function (x) { return x.t; }).concat([w.lo])), tmax = Math.max.apply(null, hrs.map(function (x) { return x.t; }).concat([w.hi]));
    setHTML(b, '<div class="wx">' + wxSvg(w.code, w.day) + '<div><div class="big">' + Math.round(w.temp) + "<small>" + esc(w.unit) + '</small></div><div class="cond">' + esc(WX[w.code] || "") + "</div></div></div>" +
      '<div class="wxrow"><span>feels ' + Math.round(w.feels) + "°</span><span>↑" + Math.round(w.hi) + "° ↓" + Math.round(w.lo) + "°</span><span>" + Math.round(w.wind) + " " + esc(w.windUnit) + "</span><span>" + w.hum + "%</span></div>" +
      '<div class="hours">' + hrs.map(function (x) {
        var hh = 12 + 78 * (x.t - tmin) / Math.max(1, tmax - tmin);
        return '<div title="' + x.t + '"><b style="height:' + hh.toFixed(0) + "%;" + (x.p >= 40 ? "background:rgba(var(--accent2-rgb)," + (0.35 + x.p / 160).toFixed(2) + ")" : "") + '"></b><span>' + x.h + "</span></div>";
      }).join("") + '</div>' + airRow() + '<div class="wxrow"><span>☀ ' + esc(w.sunrise) + "</span><span>" + (w.stale ? "offline" : "rain " + Math.max.apply(null, hrs.map(function (x) { return x.p; }).concat([0])) + "% next 12h") + "</span><span>☾ " + esc(w.sunset) + "</span></div>");
    return el;
  }

  var typed = {};   // id -> {full, shown}
  function generic(w) {
    var el = panel(w.id, w.title || w.id, w.icon);
    var d = w.data || {}, b = bodyOf(el), t = w.type, html = "";
    var unit = d.unit || w.unit_override || "";
    if (t === "stat") {
      html = '<div class="big">' + num(d.value) + (unit ? "<small>" + esc(unit) + "</small>" : "") + "</div>" +
        (d.note ? '<div class="note">' + esc(d.note) + "</div>" : "") + ((d.history || []).length > 2 ? '<canvas class="spark"></canvas>' : "");
    } else if (t === "gauge") {
      var max = d.max || 100, min = d.min || 0, pct = Math.max(0, Math.min(100, 100 * ((+d.value || 0) - min) / (max - min || 1)));
      html = '<div class="row"><span class="big" style="font-size:2.2rem">' + num(d.value) + "<small>" + esc(unit) + '</small></span></div><div class="seg" style="height:.8rem"><i style="width:' + pct.toFixed(1) + '%"></i></div>' +
        (d.note ? '<div class="note">' + esc(d.note) + "</div>" : "");
    } else if (t === "list") {
      html = '<ul class="items">' + (d.items || []).slice(0, w.slot === "center" ? 4 : 6).map(function (it) {
        return '<li><span class="dot ' + (it.level ? "lv-" + it.level : "") + '"></span><div style="min-width:0"><div class="t">' + esc(it.title) + "</div>" +
          (it.text ? '<div class="s">' + esc(it.text) + "</div>" : "") + '</div><span class="r">' + esc(it.right || ago(it.t) || (it.value != null ? it.value + (it.unit || "") : "")) + "</span></li>";
      }).join("") + "</ul>" + (d.note ? '<div class="note">' + esc(d.note) + "</div>" : "");
    } else if (t === "status") {
      // long names (problems, alerts) get one tile per line instead of being cut in two columns
      var wide = (d.items || []).some(function (it) { return String(it.title || "").length > 15; });
      html = '<div class="tiles' + (wide ? " one" : "") + '">' + (d.items || []).slice(0, wide ? 6 : 12).map(function (it) {
        return '<div class="tile ' + (it.level || "") + '" data-i="' + (d.items || []).indexOf(it) + '"><span class="dot ' + (it.level ? "lv-" + it.level : "") + '"></span><span class="n">' + esc(it.title) + '</span><span class="m">' + esc(it.note || "") + "</span></div>";
      }).join("") + "</div>";
    } else if (t === "kv") {
      html = '<table class="kv">' + (d.rows || []).slice(0, 8).map(function (r) { return "<tr><td>" + esc(r[0]) + "</td><td>" + esc(r[1]) + "</td></tr>"; }).join("") + "</table>";
    } else if (t === "image") {
      if (!b._cam) {
        b.innerHTML = '<div class="cam"><span class="rec"></span><img alt=""><span class="ts"></span></div>';
        b._cam = true;
      }
      $(".rec", b).textContent = (d.live ? "LIVE · " : "") + (w.title || w.id);
      $(".ts", b).textContent = d.frame ? new Date(d.frame * 1000).toLocaleTimeString([], { hour12: false }) : "";
      if (d.frame && d.frame !== b._frame && !document.body.classList.contains("private")) {
        b._frame = d.frame;
        var img = new Image();
        img.onload = function () { var cur = $("img", b); if (cur) cur.src = img.src; };
        img.src = d.src + "?token=" + encodeURIComponent(TOKEN) + "&f=" + d.frame;
      }
      return finish(el, w);
    } else if (t === "media") {
      el.classList.toggle("media", true); el.classList.toggle("paused", d.status !== "Playing");
      html = '<div class="eq"><i></i><i></i><i></i><i></i><i></i></div>' +
        '<div class="ti">' + esc(d.title) + '</div><div class="ar">' + esc(d.artist || d.player) + '</div><div class="bar"><i style="width:' + (d.len ? (100 * d.pos / d.len).toFixed(1) : 0) + '%"></i></div>';
    } else if (t === "chart") {
      html = '<canvas class="spark" style="height:5rem"></canvas>';
    } else {   // text, typed out when it changes
      var full = String(d.text || "");
      var ty = typed[w.id] || (typed[w.id] = { full: "", shown: 0 });
      if (ty.full !== full) { ty.full = full; ty.shown = 0; }
      if (!b._text) { b.innerHTML = '<div class="text"></div>'; b._text = true; }
      return finish(el, w);
    }
    setHTML(b, html);
    var cv = $("canvas.spark", b);
    if (cv && t === "stat") spark(cv, [{ v: d.history, c: COL.accent }], { n: 90 });
    if (cv && t === "chart") spark(cv, (d.series || []).map(function (s, i) { return { v: s.values || s.v || s, c: [COL.accent, COL.accent2, COL.ok, COL.warn][i % 4] }; }), {});
    return finish(el, w);
  }
  function finish(el, w) {
    if (w.click && !el._click) {   // a panel with click = "..." in pulse.toml runs that command
      el._click = true; el.classList.add("clickable");
      el.addEventListener("click", function () {
        if (S && S.layoutEdit) return;
        fetch("/api/click/" + encodeURIComponent(w.id) + "?token=" + encodeURIComponent(TOKEN), { method: "POST" });
        el.classList.add("pressed"); setTimeout(function () { el.classList.remove("pressed"); }, 300);
      });
    }
    if (w.itemclick && !el._iclick) {   // a panel whose items can be clicked: your lights (Home Assistant)
      el._iclick = true; el.classList.add("itemclick");
      el.addEventListener("click", function (ev) {
        var tile = ev.target.closest && ev.target.closest("[data-i]"); if (!tile) return;
        if (S && S.layoutEdit) return;
        fetch("/api/click/" + encodeURIComponent(w.id) + "?item=" + tile.getAttribute("data-i") + "&token=" + encodeURIComponent(TOKEN), { method: "POST" });
        tile.classList.add("pressed"); setTimeout(function () { tile.classList.remove("pressed"); }, 300);
      });
    }
    el.classList.toggle("warn", w.level === "warn");
    el.classList.toggle("crit", w.level === "crit");
    el.classList.toggle("private", !!w.private);
    setHTML(el.querySelector(".corner"), w.updated && w.type !== "image" ? ago(w.updated) === "now" ? "" : ago(w.updated) : "");
    setHTML(el.querySelector(".err"), w.error ? "⚠ " + esc(w.error) : "");
    return el;
  }
  // typing animation for text panels
  setInterval(function () {
    if (covered) return;
    Object.keys(typed).forEach(function (id) {
      var ty = typed[id], el = panels[id];
      if (!el) return;
      var box = el.querySelector(".text");
      if (!box) return;
      if (ty.shown >= ty.full.length && box._h) return;
      if (ty.shown < ty.full.length) { ty.shown = Math.min(ty.full.length, ty.shown + 3); }
      var html = esc(ty.full.slice(0, ty.shown)) + '<span class="cur"></span>';
      if (box._h !== html) { box.innerHTML = html; box._h = html; }
    });
  }, 40);

  function place(col, list) {
    var want = list.filter(Boolean);
    var cur = Array.prototype.slice.call(col.children);
    var same = cur.length === want.length && cur.every(function (e, i) { return e === want[i]; });
    if (!same) want.forEach(function (e) { col.appendChild(e); });
    cur.forEach(function (e) { if (want.indexOf(e) < 0) col.removeChild(e); });
  }

  // a panel that does not fit in its column is hidden, never cut in half
  function fit(col) {
    var bottom = col.getBoundingClientRect().bottom, hid = false;
    Array.prototype.forEach.call(col.children, function (e) {
      e.style.visibility = "";
      if (hid || e.getBoundingClientRect().bottom > bottom + 1) { e.style.visibility = "hidden"; hid = true; }
    });
  }
  // ------------------------------------------------------------------ 1.6: move and hide the panels with the mouse
  // omarchy-pulse layout edit: every panel can be dragged to another column or place, or hidden (×); Save writes
  // ~/.config/omarchy-pulse/layout.json. The wallpaper has no keyboard, so Save and Cancel are buttons on screen.
  var layoutDraft = null, layoutDrag = null;
  function arrange(cols) {
    var L = (S.layoutEdit ? layoutDraft : S.layout) || {}, all = [], out = { left: [], center: [], right: [] };
    ["left", "center", "right"].forEach(function (c) {
      cols[c].forEach(function (el, i) { if (el) all.push({ el: el, slot: c, order: i * 10 + (c === "center" ? 0 : 0) }); });
    });
    all.forEach(function (it) {
      var o = L[it.el.dataset.id];
      it.hidden = !!(o && o.hidden);
      if (o && (o.slot === "left" || o.slot === "right" || o.slot === "center")) it.slot = o.slot;
      if (o && typeof o.order === "number") it.order = o.order;
      it.el.classList.toggle("lhidden", it.hidden && !!S.layoutEdit);
      it.el.classList.toggle("ledit", !!S.layoutEdit);
      if (S.layoutEdit && !it.el.querySelector(".lx")) {
        var x = document.createElement("button"); x.className = "lx"; x.textContent = "×"; x.title = "hide / show";
        x.addEventListener("pointerdown", function (e) { e.stopPropagation(); });
        x.addEventListener("click", function (e) {
          e.stopPropagation(); var id = it.el.dataset.id, o2 = layoutDraft[id] || (layoutDraft[id] = {});
          o2.hidden = !o2.hidden; render();
        });
        it.el.appendChild(x);
      }
    });
    all.sort(function (a, b) { return a.order - b.order; });
    all.forEach(function (it) { if (!it.hidden || S.layoutEdit) out[it.slot].push(it.el); });
    return out;
  }
  function layoutBar() {
    var bar = $("#layoutbar");
    if (!S.layoutEdit) { if (bar) bar.hidden = true; layoutDraft = null; document.body.classList.remove("layoutedit"); return; }
    if (!layoutDraft) layoutDraft = JSON.parse(JSON.stringify(S.layout || {}));
    document.body.classList.add("layoutedit");
    if (!bar) {
      bar = document.createElement("div"); bar.id = "layoutbar";
      bar.innerHTML = '<span>Drag the panels where you want them · × hides one</span><button data-do="save">Save</button>' +
        '<button data-do="cancel">Cancel</button><button data-do="reset">Reset</button>';
      bar.addEventListener("click", function (e) {
        var d = e.target.getAttribute && e.target.getAttribute("data-do"); if (!d) return;
        fetch("/api/layout?do=" + d + "&token=" + encodeURIComponent(TOKEN), { method: "POST", body: d === "save" ? JSON.stringify(layoutDraft || {}) : "" })
          .then(function () { S.layoutEdit = false; if (d === "save") S.layout = layoutDraft; if (d === "reset") S.layout = {}; layoutDraft = null; render(); }).catch(function () {});
      });
      document.body.appendChild(bar);
    }
    bar.hidden = false;
  }
  document.addEventListener("pointerdown", function (e) {
    if (!S || !S.layoutEdit) return;
    var el = e.target.closest && e.target.closest(".panel"); if (!el || !el.dataset.id) return;
    e.preventDefault();
    var r = el.getBoundingClientRect();
    layoutDrag = { el: el, dx: e.clientX - r.left, dy: e.clientY - r.top, x0: e.clientX, y0: e.clientY };
    el.classList.add("ldrag");
    el.setPointerCapture && el.setPointerCapture(e.pointerId);
  });
  document.addEventListener("pointermove", function (e) {
    if (!layoutDrag) return;
    layoutDrag.el.style.transform = "translate(" + (e.clientX - layoutDrag.x0) + "px," + (e.clientY - layoutDrag.y0) + "px)";
  });
  document.addEventListener("pointerup", function (e) {
    if (!layoutDrag) return;
    var el = layoutDrag.el, id = el.dataset.id; layoutDrag = null;
    el.classList.remove("ldrag"); el.style.transform = "";
    // which column is under the pointer, and which panel it goes before
    var slot = null, colEl = null;
    [["left", "#left"], ["center", "#center-w"], ["right", "#right"]].forEach(function (c) {
      var cr = (c[0] === "center" ? $("#center") : $(c[1])).getBoundingClientRect();
      if (e.clientX >= cr.left && e.clientX <= cr.right) { slot = c[0]; colEl = $(c[1]); }
    });
    if (!slot) { render(); return; }
    var sibs = Array.prototype.filter.call(colEl.children, function (x) { return x !== el && x.dataset.id; });
    var at = sibs.length;
    for (var i = 0; i < sibs.length; i++) { var sr = sibs[i].getBoundingClientRect(); if (e.clientY < sr.top + sr.height / 2) { at = i; break; } }
    sibs.splice(at, 0, el);
    sibs.forEach(function (x, k) { var o = layoutDraft[x.dataset.id] || (layoutDraft[x.dataset.id] = {}); o.slot = slot; o.order = k * 10; });
    layoutDraft[id].slot = slot;
    render();
  });

  var lastNote = null;
  function render() {
    if (!S) return;
    var s = S.sys || {}, h = S.hist || {};
    applyTheme(S.theme);
    var fnt = (S.ui && S.ui.font) || "";
    if (fnt !== document.body._font) { document.body._font = fnt; if (fnt) document.documentElement.style.setProperty("--font", "'" + fnt.replace(/'/g, "") + "', var(--font-base)"); else document.documentElement.style.removeProperty("--font"); }
    document.body.classList.toggle("private", !!S.privacy);
    $("#badge").textContent = "◉ PRIVACY" + (S.screencast ? " · screen shared" : "");
    var left = [], right = [], center = [], ticker = [];
    var ui = S.ui || {};
    if (s.cpu != null && ui.system !== false) left.push(sysPanel(s, h));
    if (s.cpu != null && ui.network !== false) left.push(netPanel(s, h));
    if (S.weather) right.push(weatherPanel(S.weather));
    var ws = (S.widgets || []).slice().sort(function (a, b) { return (a.order || 50) - (b.order || 50); });
    var nl = 2, nr = 1;
    ws.forEach(function (w) {
      if (w.slot === "ticker") { ticker.push(w); return; }
      var slot = w.slot;
      if (!slot || slot === "auto") {
        slot = w.type === "image" || w.type === "status" ? "right" : w.type === "text" ? "center" : nl <= nr ? "left" : "right";
      }
      var el = generic(w);
      if (slot === "left") { left.push(el); nl++; } else if (slot === "center") center.push(el); else { right.push(el); nr++; }
    });
    var cols = arrange({ left: left, center: center, right: right });
    if (!layoutDrag) {
      place($("#left"), cols.left); place($("#right"), cols.right); place($("#center-w"), cols.center);
      fit($("#left")); fit($("#right")); fit($("#center-w"));
    }
    layoutBar();
    clock();
    document.body.classList.toggle("blink");
    Array.prototype.forEach.call(document.querySelectorAll(".media .eq i"), function (i) { i.style.height = (15 + Math.random() * 85).toFixed(0) + "%"; });
    tickerUpdate(ticker);
    alerts();
    digest();
    heard();
    var m = (S.mons || {})[MONKEY] || null;
    Scene.mode(m, S);
    document.body.classList.toggle("focus", !!S.focus);
    // a new notification or news item: a shooting star
    var nt = (S.widgets || []).filter(function (x) { return x.id === "notifications" || x.id === "news"; })
      .map(function (x) { return ((x.data || {}).items || [])[0] ? x.data.items[0].title : ""; }).join("|");
    if (lastNote !== null && nt !== lastNote) Scene.shoot();
    lastNote = nt;
  }

  // ------------------------------------------------------------------ clock
  var DAYS = ["SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"], MONTHS = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"];
  function week(d) { var t = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate())); var day = t.getUTCDay() || 7; t.setUTCDate(t.getUTCDate() + 4 - day); var y = new Date(Date.UTC(t.getUTCFullYear(), 0, 1)); return Math.ceil(((t - y) / 864e5 + 1) / 7); }
  function clock() {
    var d = new Date(), h = d.getHours(), ui = (S && S.ui) || {};
    if (ui.clock === "12h") h = h % 12 || 12;
    var hm = (h < 10 ? "0" : "") + h + ":" + (d.getMinutes() < 10 ? "0" : "") + d.getMinutes();
    var hme = $("#clock .hm"); if (hme.textContent !== hm) hme.textContent = hm;
    $("#clock .ss").textContent = (d.getSeconds() < 10 ? "0" : "") + d.getSeconds();
    $("#clock .secbar i").style.width = (d.getSeconds() / 59 * 100).toFixed(1) + "%";
    setHTML($("#clock .date"), DAYS[d.getDay()] + " " + d.getDate() + " " + MONTHS[d.getMonth()] + " " + d.getFullYear() + " · W" + week(d));
    if (S && S.focus) {
      var left = Math.max(0, S.focus.until - hubNowG()), mm = Math.floor(left / 60), ss2 = Math.floor(left % 60);
      setHTML($("#clock .who"), "<b>FOCUS</b> · " + esc(S.focus.label || "") + " · " + mm + ":" + (ss2 < 10 ? "0" : "") + ss2 + " left");
      $("#clock .secbar i").style.width = (100 * (1 - left / Math.max(1, S.focus.until - S.focus.start))).toFixed(1) + "%";
    } else if (S && S.sys) {
      var t = S.theme && S.theme.name ? S.theme.name : "omarchy";
      setHTML($("#clock .who"), esc(S.sys.user || "") + "@" + esc(S.sys.host || "") + " · <b>" + esc(t) + "</b> · up " + upt(S.sys.uptime) +
        (S.heart ? ' · <span class="heart">♥ ' + (+S.heart) + "</span>" : "") +
        (ui.title ? '<span class="title">' + esc(ui.title) + "</span>" : ""));
    }
  }
  setInterval(function () { if (fails && !covered) clock(); }, 1000);   // render() ticks the clock while the hub answers

  // ------------------------------------------------------------------ ticker
  function tickerUpdate(ws) {
    var items = [];
    ws.forEach(function (w) {
      var d = w.data || {};
      if (d.items) d.items.forEach(function (it) { items.push({ t: (w.title && w.title !== w.id && w.id !== "ticker" ? w.title + " · " : "") + it.title + (it.text ? " · " + it.text : ""), l: it.level }); });
      else if (d.text) items.push({ t: (w.title || w.id) + " · " + String(d.text).split("\n")[0], l: w.level });
      else if (d.value != null) items.push({ t: (w.title || w.id) + " " + num(d.value) + " " + (d.unit || ""), l: w.level });
    });
    (S.alerts || []).slice(-3).forEach(function (a) { if (Date.now() / 1000 - a.t < 3600) items.push({ t: a.title + " · " + a.text + " · " + ago(a.t), l: a.level }); });
    var w = S.weather;
    if (w && !w.error) items.push({ t: (w.city || "") + " " + Math.round(w.temp) + w.unit + " " + (WX[w.code] || "") + " · sunset " + w.sunset });
    if (S.sys && S.sys.host) items.push({ t: "omarchy pulse · " + (S.widgets || []).length + " live sources" });
    tickItems = items;
  }
  // one headline at a time, every 6 seconds (a scrolling marquee would repaint the screen 60 times a second)
  var tickItems = [], tickIdx = -1;
  setInterval(function () {
    if (covered) return;
    if (!tickItems.length) return;
    var tr = $("#ticker .track");
    tickIdx = (tickIdx + 1) % tickItems.length;
    var it = tickItems[tickIdx];
    tr.innerHTML = '<span class="it ' + (it.l || "") + '">' + esc(it.t) + '</span><span class="count">' + (tickIdx + 1) + "/" + tickItems.length + "</span>";
  }, 6000);

  // ------------------------------------------------------------------ INCOMING
  var incT = null;
  function alerts() {
    var list = S.alerts || [];
    if (seenAlert < 0) { seenAlert = list.length ? list[list.length - 1].seq : 0; return; }   // not the old ones at start
    var fresh = list.filter(function (a) { return a.seq > seenAlert; });
    if (!fresh.length) return;
    var a = fresh[fresh.length - 1];
    seenAlert = a.seq;
    Scene.alert(a.level);
    if (!PRIMARY) return;
    var box = $("#incoming");
    box.className = a.level;
    box.hidden = false;
    $(".frame", box).style.animation = "none"; void box.offsetWidth; $(".frame", box).style.animation = "";
    decrypt($(".title", box), a.title || "");
    $(".text", box).textContent = S.privacy ? "" : (a.text || "");
    clearTimeout(incT);
    incT = setTimeout(function () { box.hidden = true; }, 7000);
  }
  // ------------------------------------------------------------------ hey sphere: what it heard, what it says
  function heard() {
    var l = S.listen, el = $("#heard");
    if (!el) { el = document.createElement("div"); el.id = "heard"; document.body.appendChild(el); }
    var html = !l ? "" : l.state === "listening" ? '<i>●</i> listening…' : l.state === "thinking" ? "…" :
      (l.heard ? '<q>' + esc(l.heard) + "</q>" : "") + '<span>' + esc(l.answer || "") + "</span>";
    setHTML(el, html); el.className = html ? "on" : "";
  }
  // ------------------------------------------------------------------ while you were away
  var seenDigest = 0;
  function digest() {
    var d = S.digest;
    if (!d || d.seq <= seenDigest) return;
    seenDigest = d.seq;
    if (Date.now() / 1000 - d.until > 120 || covered) return;
    var box = $("#digest");
    if (!box) {
      box = document.createElement("div"); box.id = "digest"; box.hidden = true; document.body.appendChild(box);
      box.addEventListener("click", function () { box.hidden = true; });
    }
    var h = Math.floor(d.mins / 60), mm = d.mins % 60;
    var icon = { alert: "▲", agent: "✓", news: "✦", notifications: "●" };
    var lines = (d.lines || []).map(function (l) {
      return '<li class="' + esc(l.level || "info") + '"><i>' + (icon[l.kind] || "·") + "</i>" + esc(l.text) + "</li>";
    }).join("") || '<li class="ok"><i>✓</i>all quiet</li>';
    if (d.more) lines += '<li class="dim"><i>+</i>' + d.more + " more</li>";
    setTimeout(function () {
      box.innerHTML = '<div class="frame"><div class="tag">◂ WHILE YOU WERE AWAY · ' + (h ? h + "h " : "") + mm + ' min</div><ul>' + lines + "</ul></div>";
      box.hidden = false;
      clearTimeout(box._t); box._t = setTimeout(function () { box.hidden = true; }, 40000);
    }, 2200);   // first the face wakes up
  }
  function decrypt(el, txt) {
    var glyph = "█▓▒░<>/\\|#$%&*+=?01", n = 0;
    var iv = setInterval(function () {
      n += 1;
      var out = "";
      for (var i = 0; i < txt.length; i++) out += i < n / 1.6 || txt[i] === " " ? txt[i] : glyph[Math.random() * glyph.length | 0];
      el.textContent = out;
      if (n / 1.6 >= txt.length) clearInterval(iv);
    }, 28);
  }

  // ------------------------------------------------------------------ the scene
  function airRow() {   // 1.4: the air you breathe, under the hours
    var a = S && S.air; if (!a || a.aqi == null) return "";
    var col = { good: "var(--ok)", fair: "var(--ok)", moderate: "var(--warn)" }[a.level] || "var(--crit)";
    return '<div class="wxrow"><span>air <b style="color:' + col + '">' + esc(a.level) + "</b> · " + a.aqi + "</span>" +
      (a.pollen ? "<span>" + esc(a.pollen) + " pollen " + esc(a.pollenLevel) + "</span>" : "<span>no pollen</span>") + "</div>";
  }
  function sunPhase(w) {
    if (!w || !w.sunrise || !w.sunset) return "night";
    var d = new Date(), now = d.getHours() * 60 + d.getMinutes();
    var mins = function (s) { var p = String(s).split(":"); return +p[0] * 60 + +p[1]; };
    var rise = mins(w.sunrise), set = mins(w.sunset);
    if (Math.abs(now - rise) <= 40) return "dawn";
    if (Math.abs(now - set) <= 40) return "dusk";
    return now > rise && now < set ? "day" : "night";
  }
  function fxText(txt) {
    var el = $("#fxtext");
    if (!el) { el = document.createElement("div"); el.id = "fxtext"; document.body.appendChild(el); }
    el.textContent = txt; el.className = "on";
    clearTimeout(el._t); el._t = setTimeout(function () { el.className = ""; }, 3800);
  }
  // 1.5 beta: NOC mode. Personal panels go away; a critical event opens an incident until someone acknowledges it
  function nocView(n, ui) {
    var hide = (ui.nocHide || ["media", "notifications"]);
    Object.keys(panels).forEach(function (id) { panels[id].classList.toggle("noc-hide", !!(n && n.on) && hide.indexOf(id) >= 0); });
    var el = $("#incident");
    if (!n || !n.on || !n.incident) { if (el) el.hidden = true; return; }
    if (!el) {
      el = document.createElement("div"); el.id = "incident"; document.body.appendChild(el);
      el.addEventListener("click", function () { fetch("/api/noc?do=ack&token=" + encodeURIComponent(TOKEN), { method: "POST" }); el.hidden = true; });
    }
    el.hidden = false;
    var mins = Math.floor((Date.now() / 1000 - n.incident.start) / 60);
    setHTML(el, '<div class="tag">▲ INCIDENT · ' + mins + " min · click to acknowledge</div>" + (n.incident.events || []).slice(-6).map(function (e) {
      var d = new Date(e.t * 1000); return '<div class="ev ' + esc(e.level) + '"><b>' + ("0" + d.getHours()).slice(-2) + ":" + ("0" + d.getMinutes()).slice(-2) + "</b> " + esc(e.text || "an event") + "</div>";
    }).join(""));
  }
  var Scene = (function () {
    var cv = $("#scene"), g = cv.getContext("2d");
    var W = 0, H = 0, SC = +Q.get("q") || 0.5, last = 0, t0 = performance.now();
    // how sharp the scene is: [pulse] quality. "full" = the screen's own pixels, "half" = half (light), "auto" = full,
    // but half on a screen bigger than 2560x1440, on battery, or as soon as the frames get slow
    var quality = "auto", qAuto = 1, qSlowAt = 0;
    function wantScale() {
      if (Q.get("q")) return +Q.get("q");
      if (quality === "half") return 0.5;
      if (quality === "full") return 1;
      return qAuto;
    }
    var stars = [], drops = [], spikes = [], far = [], heights = [], rings = [], ridge = [];
    var beatAt = 0, beatEnv = 0, flash = 0, alertEnv = 0, alertCol = null;
    var fps = 30, paused = false, kind = "skyline", wx = "clear", day = 0, reduced = false, season = {}, nyFx = 0, flakes = [], expiry = [], energy = null, infra = [], infraOn = true, infraBolt = {}, infraPos = {}, ackAsk = null;
    var cpu = 10, cores = [], ram = 40, net = 0, band = [0, 0];
    var audio = [], audioT = 0, bass = 0, mid = 0, music = false;          // cava bars, when something plays
    var agents = [], drones = {}, face = "agents", typing = false;        // AI agents: drones and the face
    var parts = [], rockets = [], fxOn = true, flashFx = 0;                            // fireworks
    var focus = null, sunOn = true, phase = "night", phaseKey = "", focusK = 0, simpleMon = false;       // focus mode, the sun
    var shooting = [], camImg = null, newsText = "", weatherNow = null, clockStr = "", ledStyle = false;
    var gaze = { x: 0, y: 0 }, cursorXY = null, cursorAt = -1e9, eyesOn = true, deskEmpty = true;   // the eyes follow you
    var away = false, wakeAt = -1e9, lan = [], carsOn = true, privacyOn = false, newAt = {};
    var gitRepos = [], gitFlash = {}, gitPos = {}, space = {}, issPos = null, issTrail = [], flareAt = -1e9;   // 1.2
    var heartBpm = null, listenSt = null, speakingOn = false, liftoffs = [];
    var growth = null, buildAt = -1e9, planes = [], planePos = {}, trains = [], ringOn = false, ringAt = -1e9;   // 1.3
    var car = null, carAt = -1e9, carText = "", tsunamis = [], windX = 0, windK = 0, gusts = [], quakes = [], shareMode = false, doorImg = null, tlEvery = 0, tlLast = 0, skyAuto = false;
    // the timelapse: every few minutes a picture of the scene alone (no panels, no names), for the video of your day
    function snapshot(now) {
      if (!tlEvery || covered || document.hidden || now - tlLast < tlEvery) return;
      tlLast = now;
      var keepCity = city;
      try {
        shareMode = true;
        if (skyAuto && city) { city = ""; paintStatic(); }   // a city found from your weather would tell where you live
        draw(now);
        var w = Math.min(1280, W), h = Math.round(w * H / W), oc = document.createElement("canvas");
        oc.width = w; oc.height = h; oc.getContext("2d").drawImage(cv, 0, 0, w, h);
        var url = oc.toDataURL("image/jpeg", 0.82);
        fetch("/api/timelapse?mon=" + encodeURIComponent(MONKEY) + "&token=" + encodeURIComponent(TOKEN), { method: "POST", body: url }).catch(function () {});
      } catch (e) {}
      shareMode = false;
      if (city !== keepCity) { city = keepCity; paintStatic(); }
    }             // sleep; cars = your network
    // 1.5: a community scene (docs/SCENES.md). It runs alone in a sandboxed frame between the sky and the panels: no
    // network, no token, no access to Pulse; it gets a read-only copy of the state and draws its own world.
    // 1.5.1: your own wallpaper ([pulse] wallpaper): "panels" = the picture and the panels only, "live" = the picture
    // with Pulse's live things on top (real rain and snow, planes, fireworks, rockets, the banner, the holidays)
    var wpOn = false, wpImg = null, wpKey = "", wpMode = "live", wpDim = 0.25;
    function wallpaperSync(w) {
      if (w && w.v) {
        wpMode = w.mode === "panels" ? "panels" : "live"; wpDim = Math.max(0, Math.min(0.9, +w.dim || 0));
        if (w.v !== wpKey) {
          wpKey = w.v;
          var im = new Image();
          im.onload = function () {
            if (wpKey !== w.v) return;
            wpImg = im; wpOn = true; document.body.classList.add("ownwall");
            var pc = null;
            if (w.colors === "picture") { try { pc = paletteOf(im); } catch (e) { pc = null; } }
            if (JSON.stringify(pc) !== JSON.stringify(pictureColors)) { pictureColors = pc; themeKey = ""; render(); }
            paintStatic();
          };
          im.src = "/api/wallpaper?token=" + encodeURIComponent(TOKEN) + "&v=" + encodeURIComponent(w.v);
        } else if (wpImg) {
          var want = w.colors === "picture";
          if (want !== !!pictureColors) { try { pictureColors = want ? paletteOf(wpImg) : null; } catch (e) { pictureColors = null; } themeKey = ""; setTimeout(render, 0); }
          paintStatic();
        }
      } else if (wpOn || wpKey) {
        wpOn = false; wpImg = null; wpKey = ""; document.body.classList.remove("ownwall");
        if (pictureColors) { pictureColors = null; themeKey = ""; setTimeout(render, 0); }
        paintStatic();
      }
    }
    function worldOff() { return plug.on || wpOn; }
    var BUILTIN = { sphere: 1, skyline: 1, orbit: 1, calm: 1, hacker: 1, noir: 1 };
    var plug = { on: false, name: "", frame: null, city: false, perf: null, err: "" };
    function pluginSync(k, st) {
      if (BUILTIN[k] || !/^[a-z0-9][a-z0-9-]{0,31}$/.test(k) || !st.sceneTicket) {
        if (plug.frame) { plug.frame.remove(); plug = { on: false, name: "", frame: null, city: false, perf: null, err: "" }; document.body.classList.remove("plugin"); paintStatic(); }
        return;
      }
      if (plug.name !== k) {
        if (plug.frame) plug.frame.remove();
        var f = document.createElement("iframe");
        f.id = "pscene"; f.setAttribute("sandbox", "allow-scripts"); f.setAttribute("aria-hidden", "true"); f.tabIndex = -1;
        f.src = "/scene/" + encodeURIComponent(k) + "?ticket=" + encodeURIComponent(st.sceneTicket);
        cv.parentNode.insertBefore(f, cv.nextSibling);
        plug = { on: false, name: k, frame: f, city: false, perf: null, err: "" };
      }
      var want = Math.min(Math.max(fps, 1), cap);
      if (plug.frame.contentWindow) plug.frame.contentWindow.postMessage({ pulse: "state", state: Object.assign({}, st.scene || {}, { layout: { horizon: 0.745 } }), fps: want, scale: SC }, "*");
    }
    addEventListener("message", function (ev) {
      if (!plug.frame || ev.source !== plug.frame.contentWindow) return;
      var m = ev.data || {};
      if (m.pulseScene === "ready") { plug.on = true; plug.city = !!m.city; document.body.classList.add("plugin"); paintStatic(); }
      else if (m.pulseScene === "error") {
        plug.err = String(m.error || "").slice(0, 300);
        try { fetch("/api/scene-error?token=" + encodeURIComponent(TOKEN), { method: "POST", body: JSON.stringify({ scene: plug.name, where: m.where, error: plug.err }) }).catch(function () {}); } catch (e) {}
      }
      else if (m.pulseScene === "show") fxText(m.text);
      else if (m.pulseScene === "perf") plug.perf = m;
    });
    cv.addEventListener("click", function (e) {   // a click on the scene: the scene may answer with a line of text
      var ex = e.clientX * W / innerWidth, ey = e.clientY * H / innerHeight;
      for (var id in infraPos) {   // 1.5: a double click on a storm acknowledges its problems on their system (if its source has ack)
        var ip = infraPos[id]; if (Math.hypot(ex - ip[0], ey - ip[1]) > ip[2]) continue;
        if (ackAsk && ackAsk.id === id && performance.now() - ackAsk.t < 5000) {
          ackAsk = null;
          fetch("/api/ack/" + encodeURIComponent(id) + "?token=" + encodeURIComponent(TOKEN), { method: "POST" })
            .then(function (r) { return r.json(); }).then(function (j) { if (!j.ok) fxText(j.why || "not acknowledged"); }).catch(function () {});
        } else { ackAsk = { id: id, t: performance.now() }; fxText("click again to acknowledge · " + ip[3]); }
        return;
      }
      if (plug.on && plug.frame.contentWindow) plug.frame.contentWindow.postMessage({ pulse: "click", x: e.clientX / innerWidth, y: e.clientY / innerHeight }, "*");
    });
    var tex = document.createElement("canvas"), tg = tex.getContext("2d", { willReadFrequently: true }), TW = 192, TH = 96, texData = null;
    var TX = 2;   // the texture is painted in 192x96 units on a 384x192 canvas: sharper letters on the sphere
    tex.width = TW * TX; tex.height = TH * TX;
    if (window.PULSE_DEBUG) window.__pulseTex = tex;
    var rnd = function (s) { return function () { s = (s * 16807) % 2147483647; return (s - 1) / 2147483646; }; };

    function resize() {
      SC = wantScale();
      var r = (SC >= 1 ? Math.min(2, window.devicePixelRatio || 1) : Math.min(1, window.devicePixelRatio || 1)) * SC;
      W = Math.max(320, innerWidth * r | 0); H = Math.max(200, innerHeight * r | 0);
      cv.width = W; cv.height = H;
      var R = rnd(42);
      stars = []; for (var i = 0; i < 130; i++) stars.push([R() * W, R() * H * 0.62, R(), 0.3 + R() * 1.1]);
      far = []; var x = 0; while (x < W) { var bw = W * (0.012 + R() * 0.03); far.push([x, bw, H * (0.03 + R() * 0.12)]); x += bw + W * 0.002; }
      drops = []; for (i = 0; i < 180; i++) drops.push([R() * W, R() * H, 0.6 + R() * 0.8]);
      ridge = []; var a1 = R() * 6, a2r = R() * 6;
      for (i = 0; i <= 64; i++) { var u = i / 64; ridge.push(0.5 + 0.28 * Math.sin(u * 7.1 + a1) + 0.16 * Math.sin(u * 17.3 + a2r) + 0.08 * Math.sin(u * 41 + a1 * 2)); }
      // the free band between the side columns, where the skyline stands
      var l = $("#left").getBoundingClientRect(), rr = $("#right").getBoundingClientRect();
      band = [(l.right + 30) * r, (rr.left - 30) * r];
      if (!(band[1] - band[0] > W * 0.25)) band = [W * 0.08, W * 0.92];
      paintStatic();
    }
    // ---------------- real cities: the skyline of your city behind the towers, drawn from simple silhouettes
    // (original line drawings of public buildings; no photos, no logos). x: -1..1 (left / right of the core),
    // h and w in screen heights.
    function sym(c, x, y, w, h, prof) {   // a symmetric outline: prof = [[half width 0..0.5, height 0..1], ...] bottom to top
      c.moveTo(x - prof[0][0] * w, y);
      for (var i = 0; i < prof.length; i++) c.lineTo(x - prof[i][0] * w, y - prof[i][1] * h);
      for (i = prof.length - 1; i >= 0; i--) c.lineTo(x + prof[i][0] * w, y - prof[i][1] * h);
      c.closePath();
    }
    function rect(c, x, y, w, h) { c.rect(x - w / 2, y - h, w, h); }
    function holeArch(c, x, y, w, h) {   // an arched opening, for even-odd fills
      c.moveTo(x - w / 2, y); c.lineTo(x - w / 2, y - h + w / 2); c.arc(x, y - h + w / 2, w / 2, Math.PI, 0); c.lineTo(x + w / 2, y); c.closePath();
    }
    function dome(c, x, y, r, hh) { c.moveTo(x - r, y); c.bezierCurveTo(x - r, y - hh * 0.75, x - r * 0.45, y - hh, x, y - hh); c.bezierCurveTo(x + r * 0.45, y - hh, x + r, y - hh * 0.75, x + r, y); c.closePath(); }
    function onion(c, x, y, r, hh) { c.moveTo(x - r * 0.7, y); c.bezierCurveTo(x - r * 1.5, y - hh * 0.45, x - r * 0.2, y - hh * 0.7, x, y - hh); c.bezierCurveTo(x + r * 0.2, y - hh * 0.7, x + r * 1.5, y - hh * 0.45, x + r * 0.7, y); c.closePath(); }
    var LM = {
      tower: function (c, x, y, w, h, p) { var n = p || 3, pr = []; for (var i = 0; i < n; i++) { pr.push([0.5 - i * 0.12, i / n]); pr.push([0.5 - i * 0.12, (i + 1) / n]); } sym(c, x, y, w, h, pr); },
      empire: function (c, x, y, w, h) { sym(c, x, y, w, h, [[.5, 0], [.5, .55], [.38, .55], [.38, .7], [.26, .7], [.26, .8], [.16, .8], [.16, .86], [.08, .86], [.06, .92], [.015, .93], [.008, 1]]); },
      chrysler: function (c, x, y, w, h) { sym(c, x, y, w, h, [[.5, 0], [.5, .62], [.36, .62], [.36, .7], [.3, .72], [.24, .78], [.18, .83], [.12, .88], [.06, .93], [.01, 1]]); },
      onewtc: function (c, x, y, w, h) { sym(c, x, y, w, h, [[.5, 0], [.5, .06], [.42, .08], [.2, .72], [.14, .73], [.012, .74], [.008, 1]]); },
      shard: function (c, x, y, w, h) { sym(c, x, y, w, h, [[.5, 0], [.3, .45], [.06, .97], [0, 1]]); },
      taper: function (c, x, y, w, h) { sym(c, x, y, w, h, [[.5, 0], [.44, .5], [.3, .88], [.05, 1]]); },
      burj: function (c, x, y, w, h) { sym(c, x, y, w, h, [[.5, 0], [.5, .12], [.36, .13], [.36, .28], [.27, .29], [.27, .45], [.19, .46], [.19, .6], [.12, .61], [.12, .74], [.06, .75], [.04, .85], [.01, 1]]); },
      pyramid: function (c, x, y, w, h) { sym(c, x, y, w, h, [[.5, 0], [.02, .96], [0, 1]]); },
      obelisk: function (c, x, y, w, h) { sym(c, x, y, w, h, [[.5, 0], [.36, .9], [0, 1]]); },
      needle: function (c, x, y, w, h) { sym(c, x, y, w, h, [[.5, 0], [.06, .96], [0, 1]]); },
      prang: function (c, x, y, w, h) { sym(c, x, y, w, h, [[.5, 0], [.46, .12], [.36, .3], [.3, .5], [.2, .72], [.1, .88], [.02, .96], [0, 1]]); },
      campanile: function (c, x, y, w, h) {
        sym(c, x, y, w, h, [[.5, 0], [.5, .64], [.56, .65], [.56, .68], [.5, .68], [.5, .82], [.44, .83], [.44, .86], [.32, .88], [.02, .99], [0, 1]]);
        for (var i = -1; i <= 1; i += 2) holeArch(c, x + i * w * 0.2, y - h * 0.8, w * 0.22, h * 0.1);
      },
      clock: function (c, x, y, w, h) {
        sym(c, x, y, w, h, [[.5, 0], [.5, .6], [.58, .61], [.58, .76], [.5, .77], [.5, .82], [.36, .86], [.0, 1]]);
        c.moveTo(x + w * 0.3, y - h * 0.685); c.arc(x, y - h * 0.685, w * 0.3, 0, Math.PI * 2);
      },
      leaning: function (c, x, y, w, h) {
        var a = 0.075, dx = Math.sin(a) * h, k = 0.5;
        c.moveTo(x - w * k, y); c.lineTo(x - w * k + dx, y - h * 0.93); c.lineTo(x - w * 0.36 + dx * 1.02, y - h * 0.94);
        c.lineTo(x - w * 0.36 + dx * 1.04, y - h); c.lineTo(x + w * 0.36 + dx * 1.04, y - h); c.lineTo(x + w * 0.36 + dx * 1.02, y - h * 0.94);
        c.lineTo(x + w * k + dx, y - h * 0.93); c.lineTo(x + w * k, y); c.closePath();
        for (var i = 1; i < 7; i++) { var yy = y - h * i / 7.6, ox = dx * i / 7.6; c.moveTo(x - w * 0.42 + ox, yy); c.lineTo(x + w * 0.42 + ox, yy); c.lineTo(x + w * 0.42 + ox, yy - h * 0.012); c.lineTo(x - w * 0.42 + ox, yy - h * 0.012); c.closePath(); }
      },
      gothic: function (c, x, y, w, h) {   // a cathedral: a nave, pinnacles and a central spire
        sym(c, x, y, w, h, [[.5, 0], [.5, .4], [.0, .62]]);
        for (var i = 0; i < 9; i++) { var px = x - w * 0.46 + i * w * 0.115; c.moveTo(px - w * 0.02, y - h * 0.38); c.lineTo(px, y - h * (0.55 + (i % 2) * 0.06)); c.lineTo(px + w * 0.02, y - h * 0.38); c.closePath(); }
        c.moveTo(x - w * 0.04, y - h * 0.55); c.lineTo(x - w * 0.03, y - h * 0.82); c.lineTo(x, y - h); c.lineTo(x + w * 0.03, y - h * 0.82); c.lineTo(x + w * 0.04, y - h * 0.55); c.closePath();
      },
      twinspire: function (c, x, y, w, h) {
        rect(c, x, y, w, h * 0.42);
        [-1, 1].forEach(function (s) { sym(c, x + s * w * 0.3, y, w * 0.32, h, [[.5, 0], [.5, .62], [.4, .64], [.0, 1]]); });
      },
      dome: function (c, x, y, w, h) {   // a drum, a dome, its lantern
        rect(c, x, y, w * 0.62, h * 0.38); dome(c, x, y - h * 0.38, w * 0.33, h * 0.42);
        rect(c, x, y - h * 0.8, w * 0.08, h * 0.12); c.moveTo(x - w * 0.02, y - h * 0.92); c.lineTo(x, y - h); c.lineTo(x + w * 0.02, y - h * 0.92); c.closePath();
      },
      capitol: function (c, x, y, w, h) { rect(c, x, y, w, h * 0.3); LM.dome(c, x, y - h * 0.28, w * 0.42, h * 0.72); },
      mole: function (c, x, y, w, h) { sym(c, x, y, w, h, [[.5, 0], [.5, .3], [.44, .33], [.38, .38], [.12, .7], [.08, .71], [.07, .78], [.04, .79], [.03, .92], [.006, 1]]); },
      tv: function (c, x, y, w, h, p) {   // a TV tower: shaft, a pod (ball, disc or saucer), the antenna
        var py = y - h * (p === "saucer" ? 0.8 : 0.66);
        sym(c, x, y, w, h * 0.98, [[.12, 0], [.07, .3], [.05, .66], [.05, .8], [.02, .82], [.012, 1]]);
        if (p === "ball") { c.moveTo(x + w * 0.34, py); c.arc(x, py, w * 0.34, 0, Math.PI * 2); }
        else if (p === "saucer") { c.moveTo(x - w * 0.5, py); c.lineTo(x + w * 0.5, py); c.lineTo(x + w * 0.2, py - h * 0.05); c.lineTo(x - w * 0.2, py - h * 0.05); c.closePath(); c.moveTo(x - w * 0.18, py); c.lineTo(x + w * 0.18, py); c.lineTo(x + w * 0.1, py + h * 0.06); c.lineTo(x - w * 0.1, py + h * 0.06); c.closePath(); }
        else { c.ellipse(x, py, w * 0.28, h * 0.035, 0, 0, Math.PI * 2); }
      },
      skytree: function (c, x, y, w, h) { sym(c, x, y, w, h, [[.5, 0], [.2, .3], [.1, .6], [.08, .8], [.03, .82], [.02, 1]]); c.ellipse(x, y - h * 0.5, w * 0.2, h * 0.02, 0, 0, Math.PI * 2); c.ellipse(x, y - h * 0.67, w * 0.15, h * 0.015, 0, 0, Math.PI * 2); },
      pearl: function (c, x, y, w, h) {
        [-1, 1].forEach(function (s) { c.moveTo(x + s * w * 0.4, y); c.lineTo(x + s * w * 0.1, y - h * 0.3); c.lineTo(x + s * w * 0.06, y - h * 0.3); c.lineTo(x + s * w * 0.32, y); c.closePath(); });
        rect(c, x, y, w * 0.08, h * 0.95);
        c.moveTo(x + w * 0.24, y - h * 0.3); c.arc(x, y - h * 0.3, w * 0.24, 0, Math.PI * 2);
        c.moveTo(x + w * 0.15, y - h * 0.68); c.arc(x, y - h * 0.68, w * 0.15, 0, Math.PI * 2);
        c.moveTo(x - w * 0.01, y - h * 0.9); c.lineTo(x, y - h); c.lineTo(x + w * 0.01, y - h * 0.9); c.closePath();
      },
      lattice: function (c, x, y, w, h) {   // an iron lattice tower, with its arch
        sym(c, x, y, w, h, [[.5, 0], [.36, .18], [.23, .34], [.13, .54], [.07, .76], [.04, .9], [.012, .96], [.004, 1]]);
        c.moveTo(x - w * 0.32, y); c.quadraticCurveTo(x, y - h * 0.3, x + w * 0.32, y); c.closePath();
      },
      wheel: function (c, x, y, w, h) {   // stroked
        var r = Math.min(w, h) / 2, cy = y - h + r;
        c.moveTo(x + r, cy); c.arc(x, cy, r, 0, Math.PI * 2);
        for (var i = 0; i < 12; i++) { var a = i / 12 * Math.PI * 2; c.moveTo(x, cy); c.lineTo(x + Math.cos(a) * r, cy + Math.sin(a) * r); }
        c.moveTo(x - r * 0.5, y); c.lineTo(x, cy); c.lineTo(x + r * 0.5, y);
      },
      bridge: function (c, x, y, w, h) {   // stroked: a suspension bridge
        var t = [x - w * 0.3, x + w * 0.3], dy = y - h * 0.22;
        c.moveTo(x - w / 2, dy); c.lineTo(x + w / 2, dy);
        t.forEach(function (tx) { c.moveTo(tx - w * 0.015, y); c.lineTo(tx - w * 0.015, y - h); c.moveTo(tx + w * 0.015, y); c.lineTo(tx + w * 0.015, y - h); c.moveTo(tx - w * 0.015, y - h * 0.7); c.lineTo(tx + w * 0.015, y - h * 0.7); });
        c.moveTo(t[0], y - h); c.quadraticCurveTo(x, dy + h * 0.1, t[1], y - h);
        c.moveTo(x - w / 2, dy); c.quadraticCurveTo(x - w * 0.42, y - h * 0.62, t[0], y - h);
        c.moveTo(x + w / 2, dy); c.quadraticCurveTo(x + w * 0.42, y - h * 0.62, t[1], y - h);
      },
      archbridge: function (c, x, y, w, h) {   // stroked
        var dy = y - h * 0.45;
        c.moveTo(x - w / 2, y); c.quadraticCurveTo(x, y - h * 2, x + w / 2, y);
        c.moveTo(x - w / 2, dy); c.lineTo(x + w / 2, dy);
        for (var i = 1; i < 10; i++) { var u = i / 10, px = x - w / 2 + u * w, py = y - 4 * h * u * (1 - u); if (py < dy) { c.moveTo(px, dy); c.lineTo(px, py); } }
      },
      arch: function (c, x, y, w, h) { rect(c, x, y, w, h); holeArch(c, x, y, w * 0.38, h * 0.62); },
      gate: function (c, x, y, w, h) { rect(c, x, y - h * 0.72, w, h * 0.28); for (var i = 0; i < 6; i++) rect(c, x - w * 0.45 + i * w * 0.18, y, w * 0.07, h * 0.74); rect(c, x, y - h * 0.98, w * 0.16, h * 0.04); },
      colosseum: function (c, x, y, w, h) {
        c.moveTo(x - w / 2, y); c.lineTo(x - w / 2, y - h * 0.82); c.quadraticCurveTo(x, y - h * 1.08, x + w * 0.2, y - h); c.lineTo(x + w * 0.32, y - h * 0.7); c.lineTo(x + w / 2, y - h * 0.5); c.lineTo(x + w / 2, y); c.closePath();
        for (var r = 0; r < 3; r++) for (var i = 0; i < 9; i++) { var ax = x - w * 0.44 + i * w * 0.105, top = y - h * (0.08 + r * 0.24); if (ax > x + w * 0.28 && r > 0) continue; holeArch(c, ax, top, w * 0.05, h * 0.17); }
      },
      temple: function (c, x, y, w, h) {   // a hill and a temple with columns
        c.moveTo(x - w * 0.6, y); c.quadraticCurveTo(x, y - h * 0.7, x + w * 0.6, y); c.closePath();
        var b = y - h * 0.48; rect(c, x, b, w * 0.6, h * 0.06);
        for (var i = 0; i < 8; i++) rect(c, x - w * 0.27 + i * w * 0.077, b - h * 0.06, w * 0.03, h * 0.3);
        c.moveTo(x - w * 0.32, b - h * 0.36); c.lineTo(x, b - h * 0.52); c.lineTo(x + w * 0.32, b - h * 0.36); c.closePath();
      },
      minaret: function (c, x, y, w, h) { sym(c, x, y, w, h, [[.5, 0], [.4, .74], [.7, .76], [.7, .8], [.35, .81], [.3, .92], [0, 1]]); },
      mosque: function (c, x, y, w, h) { rect(c, x, y, w * 0.7, h * 0.28); dome(c, x, y - h * 0.28, w * 0.26, h * 0.32); [-1, 1].forEach(function (s) { LM.minaret(c, x + s * w * 0.46, y, w * 0.06, h); }); },
      onion: function (c, x, y, w, h) {
        [[0, 1, .16], [-.3, .72, .11], [.3, .76, .12], [-.15, .6, .1], [.16, .58, .09]].forEach(function (o) {
          var tx = x + o[0] * w, th = h * o[1]; rect(c, tx, y, w * o[2] * 1.2, th * 0.72); onion(c, tx, y - th * 0.72, w * o[2], th * 0.26);
          c.moveTo(tx - w * 0.008, y - th * 0.97); c.lineTo(tx, y - th); c.lineTo(tx + w * 0.008, y - th * 0.97); c.closePath();
        });
      },
      pagoda: function (c, x, y, w, h) {
        var n = 5;
        for (var i = 0; i < n; i++) {
          var b = y - h * i / n * 0.9, ww = w * (1 - i * 0.14);
          rect(c, x, b, ww * 0.6, h * 0.12);
          c.moveTo(x - ww * 0.55, b - h * 0.12); c.quadraticCurveTo(x - ww * 0.4, b - h * 0.13, x - ww * 0.3, b - h * 0.18); c.lineTo(x + ww * 0.3, b - h * 0.18); c.quadraticCurveTo(x + ww * 0.4, b - h * 0.13, x + ww * 0.55, b - h * 0.12); c.closePath();
        }
        rect(c, x, y - h * 0.9, w * 0.02, h * 0.1);
      },
      roundtemple: function (c, x, y, w, h) {
        rect(c, x, y, w, h * 0.1);
        for (var i = 0; i < 3; i++) { var b = y - h * (0.1 + i * 0.26), ww = w * (0.8 - i * 0.18); rect(c, x, b, ww * 0.7, h * 0.12); c.moveTo(x - ww / 2, b - h * 0.12); c.quadraticCurveTo(x, b - h * 0.32, x + ww / 2, b - h * 0.12); c.closePath(); }
        c.moveTo(x - w * 0.03, y - h * 0.9); c.lineTo(x, y - h); c.lineTo(x + w * 0.03, y - h * 0.9); c.closePath();
      },
      twin: function (c, x, y, w, h) {
        [-1, 1].forEach(function (s) { LM.tower(c, x + s * w * 0.28, y, w * 0.3, h * 0.92, 5); c.moveTo(x + s * w * 0.28 - w * 0.01, y - h * 0.9); c.lineTo(x + s * w * 0.28, y - h); c.lineTo(x + s * w * 0.28 + w * 0.01, y - h * 0.9); c.closePath(); });
        c.rect(x - w * 0.15, y - h * 0.47, w * 0.3, h * 0.03);
      },
      mbs: function (c, x, y, w, h) { for (var i = -1; i <= 1; i++) { c.moveTo(x + i * w * 0.32 - w * 0.08, y); c.lineTo(x + i * w * 0.32 - w * 0.05, y - h * 0.9); c.lineTo(x + i * w * 0.32 + w * 0.06, y - h * 0.9); c.lineTo(x + i * w * 0.32 + w * 0.09, y); c.closePath(); } c.moveTo(x - w * 0.5, y - h * 0.9); c.lineTo(x + w * 0.56, y - h * 0.92); c.lineTo(x + w * 0.5, y - h); c.lineTo(x - w * 0.46, y - h * 0.98); c.closePath(); },
      taipei: function (c, x, y, w, h) { rect(c, x, y, w * 0.7, h * 0.22); for (var i = 0; i < 8; i++) { var b = y - h * (0.22 + i * 0.075); c.moveTo(x - w * 0.3, b); c.lineTo(x - w * 0.4, b - h * 0.075); c.lineTo(x + w * 0.4, b - h * 0.075); c.lineTo(x + w * 0.3, b); c.closePath(); } sym(c, x, y - h * 0.82, w, h * 0.18, [[.2, 0], [.1, .5], [.02, .6], [.01, 1]]); },
      sail: function (c, x, y, w, h) { c.moveTo(x - w * 0.45, y); c.lineTo(x - w * 0.45, y - h); c.bezierCurveTo(x + w * 0.2, y - h * 0.85, x + w * 0.55, y - h * 0.5, x + w * 0.5, y); c.closePath(); },
      shells: function (c, x, y, w, h) { [[-.38, .55, .22], [-.18, .8, .26], [.05, 1, .3], [.28, .7, .24], [.42, .45, .16]].forEach(function (s) { var sx = x + s[0] * w; c.moveTo(sx - w * s[2] / 2, y); c.quadraticCurveTo(sx - w * s[2] * 0.3, y - h * s[1], sx + w * s[2] * 0.55, y - h * s[1]); c.quadraticCurveTo(sx + w * s[2] * 0.2, y - h * s[1] * 0.4, sx + w * s[2] / 2, y); c.closePath(); }); },
      castle: function (c, x, y, w, h) {   // a star fort: low walls and pointed bastions
        c.moveTo(x - w / 2, y); var n = 6;
        for (var i = 0; i < n; i++) { var u = i / n; c.lineTo(x - w / 2 + u * w, y - h * 0.55); c.lineTo(x - w / 2 + (u + 0.5 / n) * w, y - h * (i % 2 ? 0.78 : 1)); c.lineTo(x - w / 2 + (u + 1 / n) * w, y - h * 0.55); }
        c.lineTo(x + w / 2, y); c.closePath();
      },
      hill: function (c, x, y, w, h) { c.moveTo(x - w / 2, y); c.bezierCurveTo(x - w * 0.3, y - h * 0.4, x - w * 0.15, y - h, x + w * 0.02, y - h); c.bezierCurveTo(x + w * 0.2, y - h, x + w * 0.3, y - h * 0.3, x + w / 2, y); c.closePath(); },
      mesa: function (c, x, y, w, h) { c.moveTo(x - w / 2, y); c.lineTo(x - w * 0.36, y - h); c.lineTo(x + w * 0.38, y - h * 0.97); c.lineTo(x + w / 2, y); c.closePath(); },
      volcano: function (c, x, y, w, h) { c.moveTo(x - w / 2, y); c.quadraticCurveTo(x - w * 0.2, y - h * 0.8, x - w * 0.06, y - h); c.lineTo(x + w * 0.04, y - h * 0.94); c.lineTo(x + w * 0.1, y - h * 0.97); c.quadraticCurveTo(x + w * 0.25, y - h * 0.7, x + w / 2, y); c.closePath(); },
      frame: function (c, x, y, w, h) { rect(c, x, y, w, h); c.rect(x - w * 0.38, y - h * 0.9, w * 0.76, h * 0.82); },
      bottle: function (c, x, y, w, h) { sym(c, x, y, w, h, [[.5, 0], [.42, .6], [.3, .94], [.3, 1]]); c.rect(x - w * 0.16, y - h * 0.94, w * 0.32, h * 0.08); },
      kingdom: function (c, x, y, w, h) { sym(c, x, y, w, h, [[.5, 0], [.5, .55], [.34, .7], [.3, 1]]); c.moveTo(x - w * 0.24, y - h); c.quadraticCurveTo(x, y - h * 0.62, x + w * 0.24, y - h); c.closePath(); },
      cctv: function (c, x, y, w, h) { c.moveTo(x - w / 2, y); c.lineTo(x - w * 0.5, y - h); c.lineTo(x + w * 0.5, y - h); c.lineTo(x + w * 0.5, y - h * 0.3); c.lineTo(x + w * 0.2, y - h * 0.3); c.lineTo(x + w * 0.2, y); c.lineTo(x - w * 0.05, y); c.lineTo(x - w * 0.05, y - h * 0.75); c.lineTo(x - w * 0.25, y - h * 0.75); c.lineTo(x - w * 0.25, y); c.closePath(); },
      boc: function (c, x, y, w, h) { sym(c, x, y, w, h, [[.5, 0], [.5, .5], [.34, .62], [.2, .82], [.06, .9], [.03, .92], [.01, 1]]); },
      willis: function (c, x, y, w, h) { LM.tower(c, x, y, w, h * 0.86, 4); [-1, 1].forEach(function (s) { c.rect(x + s * w * 0.06 - w * 0.008, y - h, w * 0.016, h * 0.16); }); },
      gherkin: function (c, x, y, w, h) { c.moveTo(x - w * 0.34, y); c.bezierCurveTo(x - w * 0.6, y - h * 0.55, x - w * 0.25, y - h, x, y - h); c.bezierCurveTo(x + w * 0.25, y - h, x + w * 0.6, y - h * 0.55, x + w * 0.34, y); c.closePath(); },
      sagrada: function (c, x, y, w, h) { rect(c, x, y, w, h * 0.3); [[-.38, .7], [-.22, .86], [-.08, .94], [.08, 1], [.22, .88], [.38, .72]].forEach(function (s) { sym(c, x + s[0] * w, y - h * 0.28, w * 0.1, h * (s[1] - 0.28), [[.5, 0], [.42, .7], [.2, .92], [0, 1]]); }); },
      lighthouse: function (c, x, y, w, h) { sym(c, x, y, w, h, [[.5, 0], [.4, .45], [.5, .46], [.5, .5], [.36, .5], [.32, .86], [.42, .87], [.42, .9], [.2, .92], [.05, 1]]); },
      twotowers: function (c, x, y, w, h) { c.rect(x - w * 0.3, y - h, w * 0.18, h); c.rect(x + w * 0.12, y - h * 0.48, w * 0.2, h * 0.48); },
      parliament: function (c, x, y, w, h) { rect(c, x, y, w, h * 0.34); LM.dome(c, x, y - h * 0.32, w * 0.2, h * 0.68); for (var i = 0; i < 10; i++) { var px = x - w * 0.46 + i * w * 0.1; if (Math.abs(px - x) < w * 0.1) continue; c.moveTo(px - w * 0.012, y - h * 0.34); c.lineTo(px, y - h * 0.5); c.lineTo(px + w * 0.012, y - h * 0.34); c.closePath(); } },
      stalin: function (c, x, y, w, h) { sym(c, x, y, w, h, [[.5, 0], [.5, .3], [.36, .3], [.36, .48], [.24, .48], [.24, .62], [.15, .63], [.13, .78], [.05, .8], [.006, 1]]); },
      blocks: function (c, x, y, w, h, p) { var R = rnd(p || 7); for (var i = 0; i < 7; i++) { var bw = w * (0.08 + R() * 0.08), bx = x - w / 2 + i * w / 7; var hh = h * (0.35 + R() * 0.65); c.rect(bx, y - hh, bw, hh); } },
      houses: function (c, x, y, w, h) { for (var i = 0; i < 7; i++) { var bx = x - w / 2 + i * w / 7, bw = w / 7 * 0.92, hh = h * (0.7 + ((i * 37) % 5) / 15); c.moveTo(bx, y); c.lineTo(bx, y - hh); c.lineTo(bx + bw * 0.2, y - hh); c.lineTo(bx + bw * 0.2, y - hh - h * 0.12); c.lineTo(bx + bw * 0.5, y - hh - h * 0.3); c.lineTo(bx + bw * 0.8, y - hh - h * 0.12); c.lineTo(bx + bw * 0.8, y - hh); c.lineTo(bx + bw, y - hh); c.lineTo(bx + bw, y); c.closePath(); } },
    };
    var STROKED = { wheel: 1, bridge: 1, archbridge: 1 };
    // [shape, x (-1 left .. 1 right of the core), height, width, extra]
    var CITIES = {
      // Italy
      "rome": [["colosseum", -0.55, .13, .3], ["dome", 0.4, .24, .16], ["obelisk", 0.75, .16, .02], ["blocks", -0.95, .07, .2]],
      "milan": [["gothic", -0.45, .24, .44], ["taper", 0.3, .3, .06], ["needle", 0.5, .34, .05], ["tower", 0.7, .22, .07, 2], ["blocks", 0.92, .1, .18]],
      "turin": [["mole", -0.35, .36, .1], ["dome", 0.55, .18, .16], ["hill", 0.8, .1, .4], ["blocks", -0.8, .08, .2]],
      "alessandria": [["campanile", -0.35, .3, .06], ["archbridge", 0.45, .09, .32], ["castle", -0.8, .05, .34], ["dome", 0.85, .14, .12], ["blocks", 0.15, .07, .18]],
      "florence": [["dome", -0.3, .26, .2], ["campanile", 0.2, .24, .05], ["campanile", 0.6, .2, .045], ["hill", 0.9, .08, .4]],
      "venice": [["campanile", -0.3, .3, .06], ["dome", 0.3, .14, .14], ["dome", 0.55, .12, .12], ["houses", -0.75, .07, .3]],
      "naples": [["volcano", 0.55, .2, .7], ["castle", -0.4, .06, .26], ["dome", -0.75, .12, .1], ["blocks", 0.15, .07, .2]],
      "pisa": [["leaning", -0.3, .24, .07], ["dome", 0.3, .16, .2], ["campanile", 0.7, .12, .05]],
      "bologna": [["twotowers", -0.25, .34, .12], ["gothic", 0.45, .16, .24], ["houses", -0.75, .07, .3]],
      "genoa": [["lighthouse", -0.4, .3, .05], ["blocks", 0.3, .12, .3], ["hill", 0.8, .1, .4]],
      // Europe
      "london": [["clock", -0.35, .3, .06], ["wheel", -0.75, .2, .2], ["shard", 0.35, .34, .09], ["gherkin", 0.6, .2, .07], ["dome", 0.85, .16, .14]],
      "paris": [["lattice", -0.3, .4, .16], ["arch", 0.4, .12, .1], ["tower", 0.75, .2, .07, 1], ["dome", -0.75, .14, .12]],
      "berlin": [["tv", -0.3, .4, .12, "ball"], ["gate", 0.4, .1, .18], ["dome", 0.75, .15, .16], ["blocks", -0.8, .1, .22]],
      "madrid": [["tower", -0.5, .28, .06, 2], ["tower", -0.32, .3, .06, 1], ["taper", 0.3, .3, .06], ["gate", 0.7, .09, .18], ["dome", -0.85, .12, .12]],
      "barcelona": [["sagrada", -0.3, .32, .22], ["gherkin", 0.45, .22, .07], ["hill", 0.85, .08, .4], ["blocks", -0.85, .08, .2]],
      "lisbon": [["bridge", 0.45, .18, .6], ["castle", -0.5, .08, .3], ["tower", -0.85, .1, .05, 3]],
      "amsterdam": [["houses", -0.5, .08, .45], ["campanile", 0.3, .2, .05], ["houses", 0.7, .07, .4]],
      "brussels": [["gothic", -0.35, .26, .26], ["tower", 0.4, .18, .08, 2], ["blocks", 0.8, .1, .22]],
      "vienna": [["gothic", -0.35, .3, .2], ["wheel", 0.45, .18, .18], ["dome", 0.85, .14, .14], ["blocks", -0.85, .08, .2]],
      "prague": [["twinspire", -0.4, .26, .16], ["castle", -0.75, .07, .34], ["dome", 0.4, .13, .12], ["campanile", 0.75, .18, .05]],
      "budapest": [["parliament", -0.3, .24, .45], ["dome", 0.5, .2, .16], ["bridge", 0.85, .1, .3]],
      "warsaw": [["stalin", -0.3, .38, .14], ["taper", 0.4, .3, .06], ["blocks", 0.75, .14, .22]],
      "moscow": [["onion", -0.4, .26, .22], ["stalin", 0.45, .34, .14], ["campanile", 0.8, .22, .05]],
      "athens": [["temple", -0.3, .22, .42], ["hill", 0.6, .12, .5]],
      "istanbul": [["mosque", -0.35, .3, .3], ["mosque", 0.4, .26, .26], ["tower", 0.85, .14, .04, 1]],
      "dublin": [["needle", -0.2, .36, .015], ["dome", 0.4, .12, .14], ["houses", -0.7, .07, .35]],
      "stockholm": [["campanile", -0.3, .26, .07], ["twinspire", 0.4, .18, .12], ["houses", 0.8, .07, .3]],
      "copenhagen": [["campanile", -0.3, .24, .05], ["dome", 0.3, .16, .14], ["houses", -0.75, .07, .32]],
      "oslo": [["twotowers", -0.3, .2, .14], ["shells", 0.45, .1, .3], ["hill", 0.85, .08, .4]],
      "helsinki": [["dome", -0.3, .22, .2], ["onion", 0.45, .18, .16], ["houses", 0.85, .06, .3]],
      "zurich": [["twinspire", -0.3, .2, .12], ["campanile", 0.3, .2, .05], ["hill", 0.8, .14, .5]],
      "munich": [["twinspire", -0.35, .24, .14], ["tv", 0.4, .34, .07, "disc"], ["blocks", 0.85, .08, .2]],
      "cologne": [["twinspire", -0.25, .36, .18], ["archbridge", 0.5, .07, .35]],
      "frankfurt": [["tower", -0.4, .32, .07, 2], ["taper", -0.2, .3, .06], ["tv", 0.35, .32, .06, "disc"], ["blocks", 0.7, .2, .25]],
      "kyiv": [["onion", -0.3, .2, .2], ["dome", 0.4, .18, .14], ["blocks", 0.8, .1, .2]],
      "bern": [["campanile", -0.3, .24, .05], ["dome", 0.35, .16, .2], ["hill", 0.85, .1, .4]],
      "reykjavik": [["stalin", -0.25, .3, .16], ["houses", 0.45, .06, .35], ["mesa", 0.85, .08, .35]],
      "edinburgh": [["castle", -0.4, .14, .36], ["hill", -0.4, .07, .5], ["needle", 0.35, .2, .04], ["houses", 0.75, .07, .3]],
      // Americas
      "new-york": [["empire", -0.35, .36, .08], ["chrysler", -0.6, .3, .06], ["onewtc", 0.35, .42, .08], ["bridge", 0.75, .14, .4], ["blocks", -0.85, .16, .2]],
      "washington": [["obelisk", -0.2, .36, .035], ["capitol", 0.45, .2, .3], ["blocks", -0.75, .07, .2]],
      "chicago": [["willis", -0.3, .4, .08], ["taper", 0.3, .34, .07], ["blocks", 0.65, .2, .25], ["blocks", -0.75, .18, .22]],
      "los-angeles": [["tower", -0.3, .3, .08, 2], ["taper", -0.1, .26, .05], ["hill", 0.6, .12, .6], ["blocks", -0.7, .14, .24]],
      "san-francisco": [["pyramid", -0.35, .3, .07], ["bridge", 0.5, .2, .55], ["blocks", -0.75, .12, .2]],
      "seattle": [["tv", -0.3, .34, .14, "saucer"], ["blocks", 0.3, .16, .3], ["volcano", 0.8, .14, .45]],
      "las-vegas": [["tv", -0.4, .38, .07, "disc"], ["lattice", 0.4, .2, .08], ["pyramid", 0.75, .1, .16], ["blocks", -0.8, .1, .22]],
      "miami": [["blocks", -0.5, .2, .4], ["tower", 0.4, .26, .06, 3], ["blocks", 0.75, .16, .3]],
      "boston": [["tower", -0.3, .28, .07, 1], ["obelisk", 0.35, .14, .03], ["blocks", 0.7, .12, .25]],
      "toronto": [["tv", -0.25, .42, .07, "disc"], ["blocks", 0.3, .2, .35], ["blocks", -0.7, .12, .22]],
      "montreal": [["hill", 0.5, .1, .6], ["needle", -0.3, .2, .08], ["blocks", -0.75, .12, .25]],
      "vancouver": [["blocks", -0.4, .14, .4], ["mesa", 0.6, .16, .6], ["tower", 0.2, .2, .05, 2]],
      "mexico-city": [["tower", -0.3, .26, .06, 3], ["needle", 0.3, .2, .02], ["volcano", 0.8, .14, .45], ["blocks", -0.75, .1, .22]],
      "sao-paulo": [["blocks", -0.5, .18, .45], ["tower", 0.35, .24, .06, 2], ["blocks", 0.75, .16, .3]],
      "rio-de-janeiro": [["hill", -0.4, .22, .25], ["hill", 0.5, .16, .4], ["blocks", 0.0, .07, .2]],
      "buenos-aires": [["obelisk", -0.2, .3, .035], ["capitol", 0.45, .18, .24], ["blocks", -0.7, .12, .24]],
      "ottawa": [["campanile", -0.25, .3, .06], ["gothic", -0.6, .16, .3], ["blocks", 0.5, .1, .3]],
      "havana": [["capitol", -0.3, .24, .3], ["lighthouse", 0.5, .16, .04], ["houses", 0.85, .06, .3]],
      "lima": [["twinspire", -0.3, .16, .14], ["blocks", 0.4, .14, .35], ["hill", 0.85, .1, .4]],
      "bogota": [["taper", -0.3, .26, .06], ["hill", 0.55, .2, .7], ["blocks", -0.75, .12, .22]],
      "santiago": [["taper", -0.25, .4, .06], ["mesa", 0.55, .22, .8], ["blocks", -0.7, .12, .25]],
      // Asia and the Middle East
      "tokyo": [["lattice", -0.35, .34, .12], ["skytree", 0.35, .44, .08], ["blocks", 0.75, .16, .25], ["volcano", -0.85, .1, .3]],
      "beijing": [["roundtemple", -0.35, .22, .2], ["cctv", 0.35, .26, .14], ["pagoda", 0.8, .16, .1], ["blocks", -0.8, .1, .2]],
      "shanghai": [["pearl", -0.35, .36, .12], ["taper", 0.25, .44, .08], ["bottle", 0.5, .4, .07], ["tower", 0.72, .34, .06, 4]],
      "hong-kong": [["taper", -0.3, .38, .07], ["boc", 0.25, .34, .07], ["blocks", 0.6, .2, .3], ["hill", -0.75, .16, .5]],
      "shenzhen": [["taper", -0.3, .42, .07], ["tower", 0.2, .34, .07, 3], ["blocks", 0.65, .2, .3]],
      "seoul": [["tv", -0.4, .32, .07, "disc"], ["taper", 0.35, .42, .07], ["pagoda", 0.75, .1, .14], ["hill", -0.8, .1, .35]],
      "singapore": [["mbs", -0.3, .2, .26], ["wheel", 0.35, .18, .16], ["blocks", 0.75, .2, .28]],
      "kuala-lumpur": [["twin", -0.25, .4, .2], ["tv", 0.4, .32, .06, "disc"], ["blocks", 0.8, .14, .22]],
      "taipei": [["taipei", -0.2, .44, .1], ["hill", 0.6, .1, .5], ["blocks", -0.7, .1, .22]],
      "osaka": [["pagoda", -0.3, .2, .16], ["tv", 0.3, .24, .05, "disc"], ["blocks", 0.7, .16, .3]],
      "kyoto": [["pagoda", -0.3, .26, .12], ["tv", 0.35, .18, .05, "disc"], ["hill", 0.8, .1, .4]],
      "hanoi": [["pagoda", -0.3, .16, .12], ["tower", 0.35, .3, .06, 3], ["blocks", 0.75, .12, .25]],
      "manila": [["blocks", -0.45, .2, .4], ["dome", 0.35, .14, .14], ["tower", 0.7, .22, .06, 2]],
      "jakarta": [["obelisk", -0.25, .3, .04], ["blocks", 0.4, .2, .4], ["mosque", -0.75, .16, .24]],
      "kathmandu": [["pagoda", -0.3, .18, .14], ["dome", 0.3, .12, .14], ["mesa", 0.8, .16, .5]],
      "tehran": [["tv", -0.3, .4, .08, "disc"], ["arch", 0.35, .14, .12], ["mesa", 0.8, .16, .5]],
      "ankara": [["tv", -0.3, .3, .06, "disc"], ["mosque", 0.4, .2, .3]],
      "bangkok": [["prang", -0.3, .26, .14], ["tower", 0.35, .3, .07, 3], ["blocks", 0.75, .14, .25]],
      "mumbai": [["arch", -0.35, .14, .12], ["blocks", 0.3, .22, .35], ["dome", -0.75, .14, .12]],
      "delhi": [["arch", -0.3, .16, .08], ["minaret", 0.3, .24, .05], ["mosque", 0.75, .18, .24]],
      "dubai": [["burj", -0.2, .48, .07], ["sail", 0.4, .26, .08], ["frame", 0.75, .16, .1], ["blocks", -0.7, .18, .25]],
      "abu-dhabi": [["mosque", -0.3, .22, .36], ["blocks", 0.45, .18, .3]],
      "doha": [["gherkin", -0.3, .3, .07], ["blocks", 0.3, .2, .35]],
      "riyadh": [["kingdom", -0.3, .34, .08], ["taper", 0.3, .32, .06], ["blocks", 0.7, .12, .25]],
      "tel-aviv": [["blocks", -0.4, .2, .4], ["tower", 0.4, .24, .06, 2]],
      "cairo": [["pyramid", -0.5, .2, .3], ["pyramid", -0.2, .16, .24], ["tv", 0.45, .3, .06, "disc"], ["minaret", 0.8, .2, .04]],
      // Oceania and Africa
      "sydney": [["shells", -0.35, .14, .3], ["archbridge", 0.4, .14, .45], ["tv", 0.85, .26, .05, "disc"]],
      "melbourne": [["taper", -0.3, .34, .06], ["blocks", 0.35, .2, .35], ["tower", -0.7, .2, .05, 2]],
      "auckland": [["tv", -0.2, .4, .08, "disc"], ["volcano", 0.6, .1, .4], ["blocks", -0.7, .1, .22]],
      "cape-town": [["mesa", 0.45, .2, .6], ["blocks", -0.5, .08, .3]],
      "johannesburg": [["tv", -0.3, .3, .06, "ball"], ["blocks", 0.3, .2, .35]],
      "lagos": [["blocks", -0.45, .18, .4], ["tower", 0.35, .24, .06, 2], ["archbridge", 0.75, .06, .3]],
      "nairobi": [["tower", -0.3, .22, .07, 2], ["roundtemple", 0.3, .14, .1], ["blocks", 0.7, .12, .24]],
    };
    var city = "", cityTops = [];
    function drawCity(b, hz, cx, R) {
      cityTops = [];
      var list = CITIES[city];
      if (!list) return;
      var ex = R * 1.25, left = [band[0], cx - ex], right = [cx + ex, band[1]];
      var body = mix(COL.bg, COL.accent2, 0.3), R2 = rnd(97);
      list.forEach(function (l) {
        var side = l[1] < 0 ? left : right, u = Math.abs(l[1]);
        var x = l[1] < 0 ? side[1] - u * (side[1] - side[0]) : side[0] + u * (side[1] - side[0]);
        var nat = l[0] === "hill" || l[0] === "mesa" || l[0] === "volcano", flat = nat || l[0] === "blocks" || l[0] === "houses";
        var h = (flat ? l[2] * 1.1 : 0.05 + l[2] * 1.2) * H, w = l[3] * H * (flat ? 1 : 1.2);
        b.beginPath();
        LM[l[0]](b, x, hz, w, h, l[4]);
        // a soft glow behind it, so the outline reads against the sky
        b.save(); b.shadowColor = rgba(COL.accent2, 0.55); b.shadowBlur = H / 60;
        if (STROKED[l[0]]) { b.strokeStyle = rgba(mix(body, COL.fg, 0.45), 1); b.lineWidth = Math.max(1.2, H / 420); b.stroke(); b.restore(); }
        else {
          var gr = b.createLinearGradient(0, hz - h, 0, hz);
          gr.addColorStop(0, rgba(mix(body, COL.accent2, 0.35), 1)); gr.addColorStop(1, rgba(mix(body, [0, 0, 0], 0.3), 1));
          b.fillStyle = gr; b.fill("evenodd"); b.restore();
          b.strokeStyle = rgba(mix(COL.accent2, COL.fg, 0.35), 0.75); b.lineWidth = Math.max(1, H / 800); b.stroke();
          // lit windows, only inside the outline
          var nwin = nat ? 0 : flat ? 30 : Math.round(12 + 90 * w * h / (H * H) * 10);
          for (var k = 0; k < nwin; k++) {
            var px = x + (R2() - 0.5) * w, py = hz - R2() * h * 0.9, ws = Math.max(1, H / 500);
            if (b.isPointInPath(px, py, "evenodd")) { b.fillStyle = rgba(R2() < 0.3 ? COL.warn : COL.accent, 0.35 + R2() * 0.45); b.fillRect(px, py, ws, ws * 1.4); }
          }
        }
        if (l[0] !== "blocks" && l[0] !== "houses" && l[0] !== "hill" && l[0] !== "mesa" && l[0] !== "volcano" && l[2] > 0.15) cityTops.push([x, hz - h]);
      });
    }
    // what never moves is painted once: sky, floor, the far ridge and the far city
    var bgc = document.createElement("canvas");
    function paintStatic() {
      if (!COL.bg) return;
      bgc.width = W; bgc.height = H;
      var b = bgc.getContext("2d"), hz = H * 0.745, cx = W / 2, bg = COL.bg, a2 = COL.accent2, i;
      if (wpOn && wpImg) {   // 1.5.1: your own wallpaper under the panels, filling the screen like a wallpaper does
        var sc = Math.max(W / wpImg.naturalWidth, H / wpImg.naturalHeight), iw = wpImg.naturalWidth * sc, ih = wpImg.naturalHeight * sc;
        b.fillStyle = rgba(bg, 1); b.fillRect(0, 0, W, H);
        b.drawImage(wpImg, (W - iw) / 2, (H - ih) / 2, iw, ih);
        if (wpDim > 0) { b.fillStyle = "rgba(0,0,0," + wpDim + ")"; b.fillRect(0, 0, W, H); }
        return;
      }
      var sky = b.createLinearGradient(0, 0, 0, hz);
      // the sky follows the real sun: night, a warm dawn and dusk, a (still dark enough to read) day
      var top = mix(bg, [0, 0, 0], 0.35), midc = mix(bg, a2, 0.10), low = mix(bg, a2, 0.28);
      if (phase === "day") { top = mix(bg, COL.accent, 0.16); midc = mix(bg, COL.accent, 0.24); low = mix(mix(bg, COL.fg, 0.22), a2, 0.2); }
      else if (phase === "dawn" || phase === "dusk") { top = mix(bg, [20, 10, 40], 0.4); midc = mix(bg, a2, 0.22); low = mix(mix(bg, COL.warn, 0.45), a2, 0.35); }
      sky.addColorStop(0, rgba(top, 1));
      sky.addColorStop(0.7, rgba(midc, 1));
      sky.addColorStop(1, rgba(low, 1));
      b.fillStyle = sky; b.fillRect(0, 0, W, hz + 1);
      var fl = b.createLinearGradient(0, hz, 0, H);
      fl.addColorStop(0, rgba(mix(bg, a2, 0.2), 1)); fl.addColorStop(1, rgba(mix(bg, [0, 0, 0], 0.5), 1));
      b.fillStyle = fl; b.fillRect(0, hz, W, H - hz);
      if (kind === "noir") { paintMegacity(b, hz); return; }   // (your own wallpaper, above, comes first)   // 1.5: the neo-noir megacity
      if (plug.on && !plug.city) return;   // a community scene draws its own world
      b.beginPath(); b.moveTo(0, hz);
      for (i = 0; i < ridge.length; i++) b.lineTo(i / (ridge.length - 1) * W, hz - H * (0.025 + 0.085 * ridge[i]) * (0.55 + 0.45 * Math.abs(i / (ridge.length - 1) - 0.5) * 2));
      b.lineTo(W, hz); b.closePath();
      b.fillStyle = rgba(mix(bg, a2, 0.13), 1); b.fill();
      b.strokeStyle = rgba(a2, 0.35); b.lineWidth = Math.max(1, H / 900); b.stroke();
      var R = H * (kind === "sphere" ? 0.18 : 0.15);
      b.fillStyle = rgba(mix(bg, a2, 0.18), 1);
      for (i = 0; i < far.length; i++) { var f = far[i]; if (kind !== "sphere" && f[0] + f[1] > cx - R * 1.1 && f[0] < cx + R * 1.1) continue; b.fillRect(f[0], hz - f[2], f[1], f[2] * (CITIES[city] ? 0.6 : 1)); }
      drawCity(b, hz, cx, H * (kind === "sphere" ? 0.165 : kind === "orbit" ? 0.3 : 0.14));   // your city, in front of the far blocks
    }
    function reset() { resize(); }

    function beat(now) {
      var bpm = heartBpm || 46 + cpu * 0.95;   // your heart, when you connect one; else the CPU
      if ((now - beatAt) / 1000 > 60 / bpm) {
        beatAt = now;
        spikes.push({ born: now, col: null, amp: 0.6 + cpu / 140 });
      }
      beatEnv = Math.exp(-(now - beatAt) / 160);
    }
    function gauss(u, s) { return Math.exp(-(u * u) / (2 * s * s)); }
    function ecg(u) {   // one heartbeat, u in "beat widths" from the spike centre
      return -0.12 * gauss(u + 0.075, 0.018) + 1.0 * gauss(u, 0.011) - 0.32 * gauss(u - 0.024, 0.012) + 0.18 * gauss(u - 0.12, 0.035);
    }

    function avg(a, i, j) { var s = 0, n = 0; for (var k = i; k < j && k < a.length; k++) { s += a[k]; n++; } return n ? s / n / 100 : 0; }
    function pick(list) { return list[(Math.random() * list.length) | 0]; }

    // ---------------- fireworks: rockets from the floor, sparks with gravity, added light
    function launch(x, y, big) {
      var cols = [COL.accent, COL.accent2, COL.ok, COL.warn, COL.fg];
      rockets.push({ x: x, y: H * 0.95, vx: (Math.random() - 0.5) * W * 0.0008, vy: -H * (0.024 + Math.random() * 0.006), ty: y,
        col: pick(cols), col2: pick(cols), big: big });
    }
    function burst(x, y, col, col2, n, spd) {
      flashFx = Math.max(flashFx, 0.5);
      for (var i = 0; i < n; i++) {   // a sphere of sparks: even speeds on a ring, a few faster ones
        var a = Math.random() * Math.PI * 2, v = spd * (Math.random() < 0.8 ? 0.85 + Math.random() * 0.15 : 0.3 + Math.random() * 0.5);
        parts.push({ x: x, y: y, px: x, py: y, ppx: x, ppy: y, vx: Math.cos(a) * v, vy: Math.sin(a) * v, life: 1,
          col: i % 3 ? col : col2, dec: 0.008 + Math.random() * 0.008, tw: Math.random() < 0.3 });
      }
    }
    function fireworks(small, kind2) {
      var hz = H * 0.745, n = kind2 === "burst" ? 0 : small ? 4 : 11;
      if (kind2 === "burst") { burst(W / 2, hz - H * 0.18, COL.ok, COL.fg, 70, H * 0.006); return; }
      for (var i = 0; i < n; i++) (function (i) {
        setTimeout(function () { launch(band[0] + Math.random() * (band[1] - band[0]), H * (0.08 + Math.random() * 0.2), !small || i % 2 === 0); }, i * (small ? 300 : 280));
      })(i);
    }
    function drawFireworks() {
      if (!rockets.length && !parts.length) return;
      g.globalCompositeOperation = "lighter";
      var grav = H * 0.00018, keep = [];
      for (var i = 0; i < rockets.length; i++) {
        var r = rockets[i];
        r.x += r.vx; r.y += r.vy; r.vy += grav * 0.6;
        var rg = g.createLinearGradient(r.x, r.y, r.x - r.vx * 8, r.y - r.vy * 8);   // the rocket and its trail
        rg.addColorStop(0, rgba(COL.fg, 1)); rg.addColorStop(1, rgba(r.col, 0));
        g.strokeStyle = rg; g.lineWidth = Math.max(1.5, H / 350); g.beginPath(); g.moveTo(r.x, r.y); g.lineTo(r.x - r.vx * 8, r.y - r.vy * 8); g.stroke();
        if (r.y <= r.ty || r.vy >= 0) burst(r.x, r.y, r.col, r.col2, r.big ? 140 : 80, H * (r.big ? 0.0105 : 0.0075));
        else keep.push(r);
      }
      rockets = keep; keep = [];
      g.lineWidth = Math.max(1.2, H / 420);
      for (i = 0; i < parts.length; i++) {
        var p = parts[i];
        p.ppx = p.px; p.ppy = p.py; p.px = p.x; p.py = p.y;
        p.x += p.vx; p.y += p.vy; p.vx *= 0.975; p.vy = p.vy * 0.975 + grav; p.life -= p.dec;
        if (p.life <= 0) continue;
        var al = Math.min(1, p.life * 1.4) * (p.tw && p.life < 0.5 ? (Math.random() < 0.5 ? 1 : 0.2) : 1);   // some sparks glitter
        g.strokeStyle = rgba(mix(p.col, COL.fg, p.life > 0.8 ? 0.6 : 0), al);
        g.beginPath(); g.moveTo(p.ppx, p.ppy); g.lineTo(p.x, p.y); g.stroke();
        keep.push(p);
      }
      if (flashFx > 0.02) { g.fillStyle = rgba(COL.fg, flashFx * 0.06); g.fillRect(0, 0, W, H); flashFx *= 0.85; }   // the sky lights up
      parts = keep;
      g.globalCompositeOperation = "source-over";
    }

    // ---------------- the agent's face: an orb with eyes, original and simple
    function mainAgent() {
      var order = { waiting: 5, failed: 4, working: 3, done: 2, idle: 1 }, best = null;
      agents.forEach(function (a) { if (!best || (order[a.state] || 0) > (order[best.state] || 0)) best = a; });
      return best;
    }
    function watching() {   // you move the pointer on an empty desktop: the face looks at you
      return eyesOn && face !== "off" && deskEmpty && cursorXY && performance.now() - cursorAt < 6000 &&
        cursorXY[0] >= 0 && cursorXY[1] >= 0 && cursorXY[0] <= innerWidth && cursorXY[1] <= innerHeight;
    }
    function faceMood(t) {
      var a = mainAgent();
      if (listenSt && listenSt.state === "listening") return "listen";
      if (listenSt && listenSt.state === "thinking") return "think";
      if (speakingOn) return "talk";
      if (away && face !== "off") return "sleep";
      if (face !== "off" && performance.now() - wakeAt < 2600) return "wake";
      if (typing) return "talk";
      if (!a) return face === "always" || watching() ? "idle" : null;
      return { working: "think", waiting: "alert", done: "happy", failed: "sad", idle: "idle" }[a.state] || "idle";
    }
    function drawFace(c, x, y, s, mood, t, col, fixed) {
      var ec = col, eyeW = s * 0.17, eyeH = s * 0.3, gap = s * 0.26, lookX = 0, lookY = 0;
      if (mood === "think") { lookX = Math.sin(t * 1.3) * s * 0.05; lookY = -s * 0.05; }
      if (mood !== "sleep" && mood !== "sad") { lookX += gaze.x * s * 0.15; lookY += gaze.y * s * 0.1; }   // at your pointer
      var blink = mood === "idle" || mood === "think" ? ((t % 4.2) < 0.13 ? 0.12 : 1) : 1;
      if (mood === "wake") blink = Math.min(1, Math.max(0.1, (performance.now() - wakeAt) / 1100));
      if (mood === "alert") { eyeW *= 1.15; eyeH *= 1.2; if (!fixed) ec = COL.warn; }
      if (mood === "listen") { eyeW *= 1.1; eyeH *= 1.12; lookY -= s * 0.03; }
      if (mood === "sad" && !fixed) ec = COL.crit;
      c.fillStyle = rgba(ec, 0.95); c.strokeStyle = rgba(ec, 0.95); c.lineWidth = Math.max(1.5, s * 0.07); c.lineCap = "round";
      [-1, 1].forEach(function (side) {
        var ex = x + side * gap + lookX, ey = y - s * 0.08 + lookY;
        if (mood === "happy") { c.beginPath(); c.arc(ex, ey + s * 0.06, eyeW * 0.9, Math.PI * 1.1, Math.PI * 1.9); c.stroke(); }
        else if (mood === "sleep") { c.beginPath(); c.arc(ex, ey - s * 0.03, eyeW * 0.75, Math.PI * 0.15, Math.PI * 0.85); c.stroke(); }
        else if (mood === "sad") { c.beginPath(); c.moveTo(ex - eyeW * 0.7, ey - eyeW * 0.7); c.lineTo(ex + eyeW * 0.7, ey + eyeW * 0.7); c.moveTo(ex + eyeW * 0.7, ey - eyeW * 0.7); c.lineTo(ex - eyeW * 0.7, ey + eyeW * 0.7); c.stroke(); }
        else {
          var h = eyeH * blink, r = Math.min(eyeW, h) / 2;
          c.beginPath();
          if (c.roundRect) c.roundRect(ex - eyeW / 2, ey - h / 2, eyeW, h, r); else c.rect(ex - eyeW / 2, ey - h / 2, eyeW, h);
          c.fill();
        }
        if (mood === "alert") { c.beginPath(); c.moveTo(ex - eyeW * 0.8, ey - eyeH * 0.85); c.lineTo(ex + eyeW * 0.6, ey - eyeH * 1.0); c.stroke(); }
      });
      var my = y + s * 0.26;
      if (mood === "sleep") {   // asleep: a small mouth, and z z z drifting up
        c.beginPath(); c.arc(x, my, s * 0.04, 0, Math.PI * 2); c.stroke();
        for (var zi = 0; zi < 3; zi++) {
          var zp = ((t * 0.35 + zi / 3) % 1), zs = s * (0.07 + zp * 0.09), zx = x + s * (0.42 + zp * 0.3), zy = y - s * (0.25 + zp * 0.55);
          c.globalAlpha = Math.sin(zp * Math.PI); c.beginPath();
          c.moveTo(zx, zy); c.lineTo(zx + zs, zy); c.lineTo(zx, zy + zs); c.lineTo(zx + zs, zy + zs); c.stroke();
        }
        c.globalAlpha = 1;
        return;
      }
      c.beginPath();
      if (mood === "happy" || mood === "wake") c.arc(x, my - s * 0.08, s * 0.16, Math.PI * 0.15, Math.PI * 0.85);
      else if (mood === "sad") c.arc(x, my + s * 0.08, s * 0.13, Math.PI * 1.2, Math.PI * 1.8);
      else if (mood === "alert") { c.arc(x, my, s * 0.06, 0, Math.PI * 2); }
      else if (mood === "talk") { var o = (0.3 + 0.7 * Math.abs(Math.sin(t * 9))) * s * 0.09; c.ellipse(x, my, s * 0.1, o, 0, 0, Math.PI * 2); c.fill(); return; }
      else if (mood === "listen") {   // listening: a small level meter where the mouth is
        for (var li = -2; li <= 2; li++) { var lh = s * (0.03 + 0.07 * Math.abs(Math.sin(t * 7 + li * 1.3))); c.fillRect(x + li * s * 0.05 - s * 0.015, my - lh / 2, s * 0.03, lh); }
        return;
      }
      else { c.moveTo(x - s * 0.08, my); c.lineTo(x + s * 0.08, my); }
      c.stroke();
      if (mood === "think") {   // thinking dots
        for (var i = 0; i < 3; i++) { var on = ((t * 2.2 - i * 0.33) % 1 + 1) % 1 < 0.5; c.globalAlpha = on ? 1 : 0.3; c.beginPath(); c.arc(x + s * (0.55 + i * 0.13), y - s * 0.45 - i * s * 0.08, s * 0.035, 0, Math.PI * 2); c.fill(); }
        c.globalAlpha = 1;
      }
    }

    // ---------------- the LED sphere: a texture painted every frame, sampled by thousands of LEDs
    var progList = ["clock", "weather", "cpu", "news"], progI = 0, progAt = 0;
    function sphereProgram(t) {
      var fixed = window.PULSE_PROG || Q.get("prog");   // a fixed program (&prog=weather), for trying one out
      if (fixed) return fixed;
      if (alertEnv > 0.25) return "alert";
      if (ringOn && doorImg) return "door";
      var mt = trains[0], mtl = mt ? mt.start - Date.now() / 1000 : -1;
      if (mt && mtl > 0 && mtl <= 60) return "meeting";   // the last minute before a meeting: its countdown
      if (car && t - carAt < 10) return "car";   // the car changed: its battery on the sphere
      if (listenSt || speakingOn) return "face";
      var Lc = space && space.launch, tlc = Lc ? Lc.net - Date.now() / 1000 : -1;
      if (Lc && Lc.status === "Go" && tlc > 0 && tlc < 3600) return "launch";
      if (away && face !== "off") return "face";
      if (focus) return "focus";
      if (faceMood(t)) return "face";
      if (music) return "music";
      var list = camImg ? progList.concat(["camera"]) : progList;
      if (t - progAt > 11) { progAt = t; progI = (progI + 1) % list.length; }
      return list[progI % list.length];
    }
    function paintTex(prog, t, front) {
      var c = tg, ac = COL.accent, a2 = COL.accent2, fg = COL.fg, i;
      c.setTransform(TX, 0, 0, TX, 0, 0);
      // the sphere is a screen: a glowing base that turns with it, content on top
      var base = c.createLinearGradient(0, 0, 0, TH);
      var hot = prog === "alert" ? COL.crit : prog === "face" && faceMood(t) === "alert" ? COL.warn : null;
      base.addColorStop(0, rgba(hot || a2, 0.95)); base.addColorStop(0.5, rgba(hot || mix(a2, ac, 0.5), 0.8)); base.addColorStop(1, rgba(hot || ac, 0.95));
      c.fillStyle = "#000"; c.fillRect(0, 0, TW, TH);
      if (ledStyle) {   // the LED look: a dark screen, only what is drawn lights up
        if (hot) { c.fillStyle = rgba(hot, prog === "alert" ? 0.75 : 0.18); c.fillRect(0, 0, TW, TH); }
        c.fillStyle = rgba(ac, 0.22); c.fillRect(0, TH * 0.06, TW, 1); c.fillRect(0, TH * 0.93, TW, 1);
      } else {
      c.fillStyle = base; c.fillRect(0, 0, TW, TH);
      c.fillStyle = prog === "face" || prog === "alert" ? "rgba(0,0,0,0.08)" : "rgba(0,0,0,0.16)";
      for (i = 0; i < TW; i += 24) c.fillRect(i, 0, 3, TH);          // faint stripes fixed to the sphere: you see it turn
      c.fillStyle = rgba(COL.fg, 0.5); c.fillRect(0, TH * 0.06, TW, 1); c.fillRect(0, TH * 0.93, TW, 1);
      if (prog === "music" || prog === "camera" || prog === "door") { c.fillStyle = "rgba(0,0,0,0.7)"; c.fillRect(0, 0, TW, TH); }
      }
      var fx = front + Math.sin(t * 0.25) * TW * 0.04;   // content gently sways around the front
      function at(fn) { fn(fx); fn(fx - TW); fn(fx + TW); }
      c.textAlign = "center"; c.textBaseline = "middle";
      var font = function (px, w) { return (w || 800) + " " + px + "px 'CaskaydiaMono Nerd Font', 'Cascadia Mono', monospace"; };
      c.lineJoin = "round"; c.strokeStyle = "rgba(0,0,0,0.85)";
      var txt2 = function (str, x, y, px, col, w) { c.font = font(px, w); c.lineWidth = Math.max(2, px / 8); c.strokeText(str, x, y); c.fillStyle = rgba(col, 1); c.fillText(str, x, y); };
      if (prog === "clock") {
        at(function (x) { txt2(clockStr, x, TH * 0.47, 21, fg); txt2(new Date().toDateString().toUpperCase().slice(0, 10), x, TH * 0.74, 9, fg, 700); });
      } else if (prog === "weather") {
        var w = weatherNow, wk = w ? wxKind(w.code) : "clear", night = w && w.day === 0;
        var icon = function (x, y) {   // the real weather: sun or moon, clouds, rain, snow, a storm, fog
          var cloud = function (cx2, cy2, r, col) { c.fillStyle = rgba(col, 1); c.beginPath(); c.arc(cx2 - r * 0.55, cy2, r * 0.6, 0, Math.PI * 2); c.arc(cx2 + r * 0.25, cy2 - r * 0.35, r * 0.75, 0, Math.PI * 2); c.arc(cx2 + r * 0.8, cy2 + r * 0.05, r * 0.55, 0, Math.PI * 2); c.fill(); c.fillRect(cx2 - r * 0.55, cy2, r * 1.35, r * 0.6); };
          var sky = function (cx2, cy2, r) {
            if (night) { c.fillStyle = rgba(fg, 1); c.beginPath(); c.arc(cx2, cy2, r, Math.PI * 0.5, Math.PI * 1.5); c.ellipse(cx2, cy2, r * 0.45, r, 0, Math.PI * 1.5, Math.PI * 0.5, true); c.fill(); }   // a crescent moon
            else { c.fillStyle = rgba(COL.warn, 1); c.beginPath(); c.arc(cx2, cy2, r, 0, Math.PI * 2); c.fill(); }
          };
          if (wk === "clear") sky(x, y, 11);
          else if (wk === "part") { sky(x - 5, y - 5, 8); cloud(x + 2, y + 3, 9, fg); }
          else {
            cloud(x, y - 2, 10, wk === "storm" ? mix(fg, [80, 80, 90], 0.45) : fg);
            c.fillStyle = rgba(wk === "snow" ? fg : COL.accent, 1);
            if (wk === "rain" || wk === "storm") for (var dI = -1; dI <= 1; dI++) c.fillRect(x + dI * 6 - 1, y + 9, 2.4, 6);
            if (wk === "snow") for (var sI = -1; sI <= 1; sI++) { c.beginPath(); c.arc(x + sI * 6, y + 12, 2, 0, Math.PI * 2); c.fill(); }
            if (wk === "storm") { c.fillStyle = rgba(COL.warn, 1); c.beginPath(); c.moveTo(x + 1, y + 6); c.lineTo(x - 4, y + 14); c.lineTo(x, y + 14); c.lineTo(x - 3, y + 21); c.lineTo(x + 5, y + 11); c.lineTo(x + 1, y + 11); c.closePath(); c.fill(); }
            if (wk === "fog") { c.fillStyle = rgba(fg, 0.9); for (var fI = 0; fI < 3; fI++) c.fillRect(x - 12, y + 9 + fI * 4, 24, 2); }
          }
          if (windK >= 0.5) {   // strong wind: three gust lines across the icon
            c.strokeStyle = rgba(fg, 0.95); c.lineWidth = 2; c.beginPath();
            for (var wI = 0; wI < 3; wI++) { var wy = y - 10 + wI * 7, wl = 16 - wI * 3; c.moveTo(x - 16, wy); c.lineTo(x - 16 + wl, wy); c.arc(x - 16 + wl, wy - 3, 3, Math.PI / 2, -Math.PI * 0.9, true); }
            c.stroke();
          }
        };
        var windLine = w && windK >= 0.5 && Math.floor(Date.now() / 3000) % 2 === 1;
        at(function (x) {
          icon(x - 24, TH * 0.45);
          txt2(w ? Math.round(w.temp) + "°" : "--", x + 16, TH * 0.46, 30, fg); txt2(windLine ? "WIND " + Math.round(Math.max(w.wind, w.gust || 0)) + " " + String(w.windUnit || "").toUpperCase() : w ? String(w.city || "").toUpperCase() : "", x, TH * 0.78, 11, fg, 700); });
      } else if (prog === "cpu") {
        var hcpu = (S && S.hist && S.hist.cpu) || [];
        // the last minutes of CPU as bars, scaled to the busiest moment (at least 25%), so a quiet PC is not a black band
        var top = Math.max(25, Math.max.apply(null, hcpu.concat([0])));
        c.fillStyle = "rgba(0,0,0,0.18)"; c.fillRect(0, TH * 0.42, TW, TH * 0.48);
        for (i = 0; i < TW; i++) {
          var v = hcpu.length ? hcpu[Math.floor(i / TW * hcpu.length)] || 0 : 0, hh = Math.max(TH * 0.02, v / top * TH * 0.42);
          c.fillStyle = rgba(v > 80 ? COL.crit : v > 50 ? COL.warn : COL.accent, 0.95); c.fillRect(i, TH * 0.88 - hh, 1, hh);
        }
        at(function (x) { txt2("CPU " + Math.round(cpu) + "%", x, TH * 0.3, 22, fg); });
      } else if (prog === "news") {
        c.textAlign = "left"; c.font = font(24);
        var txt = (newsText || "omarchy pulse") + "   ◆   ", w2 = c.measureText(txt).width || 1, off = (t * 26) % w2;
        for (var x2 = -off; x2 < TW + w2; x2 += w2) txt2(txt, x2, TH * 0.5, 24, fg);
        c.textAlign = "center";
      } else if (prog === "music") {
        var nb = audio.length || 1;
        for (i = 0; i < TW; i += 4) {
          var k = Math.floor(Math.abs(((i / TW) * 2 - 1)) * (nb - 1)), v2 = (audio[k] || 0) / 100;
          var hh2 = v2 * TH * 0.42;
          c.fillStyle = rgba(mix(ac, a2, i / TW), 1); c.fillRect(i, TH / 2 - hh2, 3, hh2 * 2);
        }
      } else if (prog === "alert") {
        var pulse = 0.55 + 0.45 * Math.sin(t * 8);
        c.fillStyle = rgba(COL.crit, 0.55 * pulse + 0.2); c.fillRect(0, 0, TW, TH);
        at(function (x) { txt2("!", x, TH * 0.52, 56, fg); });
      } else if (prog === "launch") {   // the countdown of the next launch
        var L = space.launch, tl = Math.max(0, L.net - Date.now() / 1000), lm = Math.floor(tl / 60), ls = Math.floor(tl % 60);
        at(function (x) { txt2("T-MINUS", x, TH * 0.2, 9, fg, 700);
          txt2((lm < 10 ? "0" : "") + lm + ":" + (ls < 10 ? "0" : "") + ls, x, TH * 0.5, 26, fg);
          txt2(String(L.rocket || "").toUpperCase().slice(0, 12), x, TH * 0.76, 9, fg, 700); });
      } else if (prog === "focus") {
        var left = Math.max(0, focus.until - hubNowG()), tot = Math.max(1, focus.until - focus.start);
        var mm = Math.floor(left / 60), ss = Math.floor(left % 60);
        c.fillStyle = rgba(ac, 0.85); c.fillRect(0, TH * 0.86, TW * (1 - left / tot), TH * 0.06);
        at(function (x) { txt2((mm < 10 ? "0" : "") + mm + ":" + (ss < 10 ? "0" : "") + ss, x, TH * 0.44, 32, fg);
          txt2("FOCUS · " + String(focus.label || "").toUpperCase().slice(0, 14), x, TH * 0.72, 11, fg, 700); });
      } else if (prog === "face") {
        at(function (x) { if (ledStyle) drawFace(c, x, TH * 0.5, TH * 0.6, faceMood(t), t, COL.warn); else drawFace(c, x, TH * 0.5, TH * 0.6, faceMood(t), t, [8, 8, 14], true); });
      } else if (prog === "meeting") {
        var M = trains[0], tm = Math.max(0, M.start - Date.now() / 1000), msec = Math.floor(tm % 60);
        at(function (x) { txt2("MEETING IN", x, TH * 0.2, 9, COL.warn, 700);
          txt2("0:" + (msec < 10 ? "0" : "") + msec, x, TH * 0.5, 26, fg);
          txt2(String(M.title || "").toUpperCase().slice(0, 12), x, TH * 0.76, 9, fg, 700); });
      } else if (prog === "car" && car) {
        var cb = Math.round(car.battery || 0), ccol = cb < 20 ? COL.crit : cb < 40 ? COL.warn : COL.ok;
        c.fillStyle = "rgba(0,0,0,0.5)"; c.fillRect(0, TH * 0.62, TW, TH * 0.1);
        c.fillStyle = rgba(ccol, 1); c.fillRect(0, TH * 0.62, TW * cb / 100, TH * 0.1);
        at(function (x) { txt2(car.state === "Complete" ? "CHARGED" : car.state === "Charging" ? "CHARGING" : "CAR", x, TH * 0.2, 9, ccol, 700);
          txt2(cb + "%", x, TH * 0.45, 26, fg);
          txt2(car.range != null ? Math.round(car.range) + " " + String(car.unit).toUpperCase() : "", x, TH * 0.84, 10, fg, 700); });
      } else if (prog === "door" && doorImg) {   // the doorbell: who is at the door, on the whole sphere
        c.globalCompositeOperation = "lighter";
        at(function (x) { try { c.drawImage(doorImg, x - TH * 0.95, TH * 0.06, TH * 1.9, TH * 0.88); } catch (e) {} });
        c.globalCompositeOperation = "source-over";
        at(function (x) { txt2("DOOR", x, TH * 0.9, 9, COL.warn, 800); });
      } else if (prog === "camera" && camImg) {
        c.globalCompositeOperation = "lighter";   // night cameras are dark: twice as bright on the sphere
        at(function (x) { try { c.drawImage(camImg, x - TH * 0.9, TH * 0.12, TH * 1.8, TH * 0.76); c.drawImage(camImg, x - TH * 0.9, TH * 0.12, TH * 1.8, TH * 0.76); } catch (e) {} });
        c.globalCompositeOperation = "source-over";
      }
      c.setTransform(1, 0, 0, 1, 0, 0);
      try { texData = c.getImageData(0, 0, TW * TX, TH * TX).data; } catch (e) { texData = null; }
    }
    // Every pixel of the sphere looks up its spot on the texture: a continuous image, as from across the street,
    // with the fine LED grid, darker edges (the curve), a halo in the colours on screen, its reflection on the
    // wet plaza and its light on the city. The map is computed once per size; each frame only reads colours.
    var sph = document.createElement("canvas"), sg = sph.getContext("2d"), sImg = null, sMap = null, sD = 0;
    var avgCol = [0, 0, 0];
    function sphereMap(D) {
      sD = D; sph.width = sph.height = D; sImg = sg.createImageData(D, D);
      var R = D / 2, n = D * D, ROWS = Math.max(40, Math.round(D / 2.6));
      sMap = { v: new Int32Array(n), lam: new Float32Array(n), k: new Float32Array(n), cos: new Float32Array(n), a: new Uint8Array(n) };
      for (var y = 0; y < D; y++) for (var x = 0; x < D; x++) {
        var i = y * D + x, nx = (x + 0.5 - R) / R, ny = (y + 0.5 - R) / R, r2 = nx * nx + ny * ny;
        if (r2 >= 1) { sMap.v[i] = -1; continue; }
        var nz = Math.sqrt(1 - r2), phi = Math.asin(-ny);
        var vRow = (0.5 - phi / Math.PI) * TH * TX;
        sMap.v[i] = Math.min(TH * TX - 1, Math.max(0, vRow | 0));
        sMap.lam[i] = Math.atan2(nx, nz);
        sMap.cos[i] = Math.cos(phi);
        var rowF = ((0.5 - phi / Math.PI) * ROWS) % 1;                    // between two rows of LEDs: darker
        var grid = 0.62 + 0.38 * Math.pow(Math.sin(rowF * Math.PI), 0.6);
        var limb = 0.5 + 0.5 * Math.pow(nz, 0.45);                         // the curve: a little dimmer toward the edge
        var rim = 1 + 0.25 * Math.pow(1 - nz, 3);                           // light gathers at the rim, like on glass
        sMap.k[i] = grid * limb * rim;
        var rr = Math.sqrt(r2);                                             // the edge fades into the sky: no outline
        sMap.a[i] = rr > 0.93 ? Math.max(0, Math.min(255, (1 - rr) / 0.07 * 255)) : 255;
      }
    }
    var LED = null;
    function ledGrid() {
      LED = [];
      for (var i = 0, nLat = 46; i < nLat; i++) {
        var phi = -Math.PI / 2 + (i + 0.5) / nLat * Math.PI, n = Math.max(6, Math.round(Math.cos(phi) * 116));
        for (var j = 0; j < n; j++) LED.push([phi, j / n * Math.PI * 2]);
      }
    }
    function drawSphereLeds(cx, cy, R, t, rot) {
      if (!LED) ledGrid();
      var halo = g.createRadialGradient(cx, cy, R * 0.8, cx, cy, R * 1.9);
      halo.addColorStop(0, rgba(alertEnv > 0.25 ? COL.crit : COL.accent2, 0.28 + 0.2 * beatEnv + 0.25 * bass)); halo.addColorStop(1, rgba(COL.accent2, 0));
      g.fillStyle = halo; g.fillRect(cx - R * 2, cy - R * 2, R * 4, R * 4);
      g.beginPath(); g.arc(cx, cy, R, 0, Math.PI * 2); g.fillStyle = rgba(mix(COL.bg, [0, 0, 0], 0.4), 1); g.fill();
      var dot = Math.max(1, R * 0.03), lift = 1 + 0.5 * bass, TWp = TW * TX, td = texData;
      for (var i = 0; i < LED.length; i++) {
        var phi = LED[i][0], lam = LED[i][1], cp = Math.cos(phi);
        var z = cp * Math.cos(lam);
        if (z < 0.02) continue;
        var uu = (lam + rot) / (Math.PI * 2); uu -= Math.floor(uu);
        var sx = cx + cp * Math.sin(lam) * R, sy = cy - Math.sin(phi) * R;
        var o = ((Math.min(TH * TX - 1, ((0.5 - phi / Math.PI) * TH * TX) | 0)) * TWp + ((uu * TWp) | 0)) * 4;
        var r = td ? td[o] : 0, gg = td ? td[o + 1] : 0, b = td ? td[o + 2] : 0;
        var lum2 = (r + gg + b) / 765, shade = 0.35 + 0.65 * z, sz = dot * (0.55 + 0.45 * z);
        g.fillStyle = lum2 < 0.05 ? rgba(COL.dim, 0.22 * shade)
          : "rgba(" + (r * shade * lift | 0) + "," + (gg * shade * lift | 0) + "," + (b * shade * lift | 0) + "," + Math.min(1, 0.4 + lum2) + ")";
        g.fillRect(sx - sz / 2, sy - sz / 2, sz, sz);
      }
      g.beginPath(); g.arc(cx, cy, R, 0, Math.PI * 2); g.strokeStyle = rgba(COL.accent, 0.3); g.lineWidth = Math.max(1, H / 700); g.stroke();
    }
    function drawSphere(cx, cy, R, t) {
      var D = Math.max(16, Math.round(R * 2));
      if (D !== sD) sphereMap(D);
      var rot = t * 0.2 + (music ? bass * 0.35 : 0);
      var front = (((rot % (Math.PI * 2)) + Math.PI * 2) % (Math.PI * 2)) / (Math.PI * 2) * TW;   // what faces you
      var prog = sphereProgram(t);
      paintTex(prog, t, front);
      if (ledStyle) { drawSphereLeds(cx, cy, R, t, rot); return; }
      var d = sImg.data, td = texData, TWp = TW * TX, lift = 1 + 0.45 * bass, sr = 0, sgc = 0, sb = 0, cnt = 0;
      var cols = TWp / 2;   // LED columns around the sphere
      var bgR = COL.bg[0] * 0.6, bgG = COL.bg[1] * 0.6, bgB = COL.bg[2] * 0.6;   // dark LEDs take the night's colour
      for (var i = 0, n = D * D; i < n; i++) {
        var o = i * 4, v = sMap.v[i];
        if (v < 0) { d[o + 3] = 0; continue; }
        var uu = (sMap.lam[i] + rot) / (Math.PI * 2);
        uu = uu - Math.floor(uu);
        var u = (uu * TWp) | 0, ti = (v * TWp + u) * 4;
        var colF = (uu * cols * sMap.cos[i]) % 1;                           // between two columns: darker
        var k = sMap.k[i] * (0.7 + 0.3 * Math.pow(Math.sin(colF * Math.PI), 0.5)) * lift;
        var r = td ? td[ti] : 0, gg = td ? td[ti + 1] : 0, b = td ? td[ti + 2] : 0;
        d[o] = Math.min(255, r * k + bgR); d[o + 1] = Math.min(255, gg * k + bgG); d[o + 2] = Math.min(255, b * k + bgB); d[o + 3] = sMap.a[i];
        if ((i & 15) === 0) { sr += r; sgc += gg; sb += b; cnt++; }
      }
      sg.putImageData(sImg, 0, 0);
      if (cnt) avgCol = [sr / cnt | 0, sgc / cnt | 0, sb / cnt | 0];
      // light on the plaza and the city, in the colour on screen
      var hz = H * 0.745;
      var spill = g.createRadialGradient(cx, hz, R * 0.3, cx, hz, R * 3.2);
      spill.addColorStop(0, rgba(avgCol, 0.38)); spill.addColorStop(1, rgba(avgCol, 0));
      g.globalCompositeOperation = "lighter";
      g.fillStyle = spill; g.fillRect(cx - R * 3.2, hz - R * 3.2, R * 6.4, R * 6.4);
      // the glow it throws into the wet night air, wide and soft: it belongs to the sky, not stuck on it
      var hk = 0.34 + 0.2 * beatEnv + 0.18 * bass;
      var halo = g.createRadialGradient(cx, cy, R * 0.75, cx, cy, R * 2.3);
      halo.addColorStop(0, rgba(avgCol, hk)); halo.addColorStop(0.35, rgba(avgCol, hk * 0.45));
      halo.addColorStop(0.7, rgba(avgCol, hk * 0.12)); halo.addColorStop(1, rgba(avgCol, 0));
      g.fillStyle = halo; g.fillRect(cx - R * 2.3, cy - R * 2.3, R * 4.6, R * 4.6);
      // where it touches the plaza: a pool of its own light
      var pool = g.createRadialGradient(cx, hz, 0, cx, hz, R * 1.1);
      pool.addColorStop(0, rgba(avgCol, 0.5)); pool.addColorStop(1, rgba(avgCol, 0));
      g.save(); g.translate(cx, hz); g.scale(1, 0.16); g.translate(-cx, -hz);
      g.fillStyle = pool; g.fillRect(cx - R * 1.1, hz - R * 1.1, R * 2.2, R * 2.2); g.restore();
      g.globalCompositeOperation = "source-over";
      g.drawImage(sph, cx - R, cy - R, R * 2, R * 2);
      // a thin rim of light on the outline, blending the curve into the halo
      g.globalCompositeOperation = "lighter";
      var rimG = g.createRadialGradient(cx, cy, R * 0.9, cx, cy, R * 1.06);
      rimG.addColorStop(0, rgba(avgCol, 0)); rimG.addColorStop(0.7, rgba(mix(avgCol, COL.fg, 0.3), 0.22)); rimG.addColorStop(1, rgba(avgCol, 0));
      g.fillStyle = rimG; g.fillRect(cx - R * 1.06, cy - R * 1.06, R * 2.12, R * 2.12);
      g.globalCompositeOperation = "source-over";
      // its reflection on the wet floor
      g.save(); g.beginPath(); g.rect(cx - R * 1.2, hz, R * 2.4, R * 1.3); g.clip();
      g.globalAlpha = 0.2; g.translate(0, hz * 2 + R * 0.08); g.scale(1, -1);
      g.drawImage(sph, cx - R, cy - R, R * 2, R * 2); g.restore(); g.globalAlpha = 1;
    }

    // ---------------- drones: one per agent, flying over the city, landing when done
    var dronesEl = null;
    function drawDrones(t, hz) {
      if (simpleMon) { if (dronesEl) dronesEl.innerHTML = "", drones = {}; return; }   // 1.6: [pulse] secondary = "simple"
      if (!dronesEl) { dronesEl = document.createElement("div"); dronesEl.id = "drones"; document.body.appendChild(dronesEl); }
      var seen = {}, sx = innerWidth / W, half = (band[1] - band[0]) / 2, mx = (band[0] + band[1]) / 2, groups = {};
      (agentsAs === "robots" ? [] : agents).forEach(function (a, idx) {   // agents_as = "robots": they walk instead
        var d = drones[a.name];
        if (!d) {
          d = drones[a.name] = { ph: Object.keys(drones).length * 2.4 + Math.random() * 0.6, sp: 0.7 + Math.random() * 0.5, land: 0, el: document.createElement("div") };   // spread out
          d.el.className = "drone"; dronesEl.appendChild(d.el);
        }
        seen[a.name] = 1;
        var down = a.state === "done" || a.state === "failed" || (focusK > 0.5 && a.state !== "waiting");   // 1.6: in a focus they rest on the ground
        d.land = Math.min(1, Math.max(0, d.land + (down ? 0.012 : -0.03)));
        var x = mx + half * 0.82 * Math.sin(t * 0.11 * d.sp + d.ph);
        var y0 = H * (0.5 + 0.035 * Math.sin(t * 0.23 * d.sp + d.ph * 1.7)) + (idx % 3) * H * 0.045;
        var y = y0 + (hz - H * 0.04 - y0) * (d.land * d.land);
        // 1.6: its health (how often its last runs ended well): a healthy agent is bigger, a sick one flickers
        var hk = a.runs >= 3 ? a.health : 1, sick = a.runs >= 3 && a.health < 0.6;
        var tilt = Math.cos(t * 0.11 * d.sp + d.ph) * 0.18, s = H * 0.042 * (0.78 + 0.3 * hk);
        var col = { working: COL.accent, waiting: COL.warn, done: COL.ok, failed: COL.crit, idle: COL.dim }[a.state] || COL.accent;
        var blinkOn = (a.state !== "waiting" || (t * 2.5 % 1) < 0.55) && !(sick && !reduced && ((t * 7.3 + d.ph) % 1.9) < 0.35);
        if (a.group && (a.state === "working" || a.state === "waiting") && d.land < 0.5) (groups[a.group] = groups[a.group] || []).push([x, y, col]);
        // beam: a working drone scans the city
        if ((a.state === "working" || a.state === "waiting") && d.land < 0.5) {
          var sw = Math.sin(t * 1.6 + d.ph) * s * 1.4;
          var bmg = g.createLinearGradient(0, y, 0, hz);
          bmg.addColorStop(0, rgba(col, a.state === "waiting" && !blinkOn ? 0.08 : 0.32)); bmg.addColorStop(1, rgba(col, 0));
          g.fillStyle = bmg; g.beginPath(); g.moveTo(x - s * 0.25, y + s * 0.25); g.lineTo(x + s * 0.25, y + s * 0.25);
          g.lineTo(x + s * 1.9 + sw, hz); g.lineTo(x - s * 1.9 + sw, hz); g.closePath(); g.fill();
        }
        g.save(); g.translate(x, y); g.rotate(tilt);
        // glow around it, in the colour of its state
        g.globalCompositeOperation = "lighter";
        var gl = g.createRadialGradient(0, 0, 0, 0, 0, s * 2.2);
        gl.addColorStop(0, rgba(col, blinkOn ? 0.45 : 0.15)); gl.addColorStop(1, rgba(col, 0));
        g.fillStyle = gl; g.fillRect(-s * 2.2, -s * 2.2, s * 4.4, s * 4.4);
        g.globalCompositeOperation = "source-over";
        // a quadcopter seen slightly from below: four arms, four spinning rotors
        var arms = [[-1.25, -0.42, 0.9], [1.25, -0.42, 0.9], [-1.05, 0.22, 1], [1.05, 0.22, 1]];
        g.lineCap = "round";
        arms.forEach(function (ar) {
          g.strokeStyle = rgba(mix(COL.bg, COL.fg, 0.55), 1); g.lineWidth = Math.max(1.5, s * 0.12);
          g.beginPath(); g.moveTo(0, 0); g.lineTo(ar[0] * s, ar[1] * s); g.stroke();
          var spin = (0.55 + 0.45 * Math.abs(Math.sin(t * 37 + ar[0] * 3))) * s * 0.62 * ar[2];
          g.beginPath(); g.ellipse(ar[0] * s, ar[1] * s - s * 0.08, spin, spin * 0.22, 0, 0, Math.PI * 2);
          g.fillStyle = rgba(COL.fg, 0.12); g.fill(); g.strokeStyle = rgba(COL.fg, 0.6); g.lineWidth = Math.max(1, s * 0.05); g.stroke();
        });
        g.fillStyle = rgba(mix(COL.bg, COL.fg, 0.35), 1);
        g.beginPath(); g.ellipse(0, 0, s * 0.62, s * 0.3, 0, 0, Math.PI * 2); g.fill();
        g.strokeStyle = rgba(a.crew ? COL.accent2 : col, 0.95); g.lineWidth = Math.max(1, s * (a.crew ? 0.12 : 0.07)); g.stroke();
        g.fillStyle = rgba(col, blinkOn ? 1 : 0.3); g.beginPath(); g.arc(0, s * 0.12, s * 0.16, 0, Math.PI * 2); g.fill();   // its eye
        if ((t * 1.3 + d.ph) % 1 < 0.15) {   // navigation lights
          g.fillStyle = rgba(COL.crit, 1); g.fillRect(-s * 1.32, -s * 0.5, s * 0.14, s * 0.14);
          g.fillStyle = rgba(COL.ok, 1); g.fillRect(s * 1.18, -s * 0.5, s * 0.14, s * 0.14);
        }
        g.restore();
        if (d.land > 0.95 && !d.boom) { d.boom = 1; burst(x, y, col, COL.fg, 40, H * 0.004); }
        if (!down) d.boom = 0;
        var mins = Math.max(0, Math.floor((hubNow() - (a.since || a.t)) / 60));   // 1.6: on the hub's clock
        var html = '<i style="background:' + rgba(col, 1) + '"></i>' + (a.crew ? "<u>crew</u> " : "") + esc(a.name) + (a.text && !a.crew ? ' <span>' + esc(a.text) + "</span>" : "") +
          (a.state === "working" && mins ? ' <b>' + mins + "m</b>" : "") + (a.state === "waiting" ? " <b>!</b>" : "") +
          (a.runs >= 3 ? ' <em class="' + (sick ? "sick" : "") + '">ok ' + Math.round(a.health * 100) + "%</em>" : "");
        if (d.el._h !== html) { d.el.innerHTML = html; d.el._h = html; }
        d.el.style.transform = "translate(" + (x * sx | 0) + "px," + ((y + s * 0.9) * sx | 0) + "px) translateX(-50%)";
        d.el.className = "drone " + a.state;
      });
      Object.keys(drones).forEach(function (n) { if (!seen[n]) { drones[n].el.remove(); delete drones[n]; } });
      // 1.6: agents working in the same repo talk: a line of light between them, a packet running along it
      Object.keys(groups).forEach(function (k) {
        var m = groups[k]; if (m.length < 2) return;
        for (var j = 1; j < m.length; j++) {
          var a0 = m[j - 1], a1 = m[j], cxm = (a0[0] + a1[0]) / 2, cym = Math.min(a0[1], a1[1]) - H * 0.04;
          g.globalCompositeOperation = "lighter";
          g.strokeStyle = rgba(mix(a0[2], a1[2], 0.5), 0.35); g.lineWidth = Math.max(1, H / 700); g.setLineDash([H * 0.008, H * 0.006]);
          g.beginPath(); g.moveTo(a0[0], a0[1]); g.quadraticCurveTo(cxm, cym, a1[0], a1[1]); g.stroke(); g.setLineDash([]);
          if (!reduced) for (var q = 0; q < 2; q++) {
            var u = (t * 0.45 + q * 0.5 + j * 0.3) % 1, back = q % 2, uu = back ? 1 - u : u;
            var px = (1 - uu) * (1 - uu) * a0[0] + 2 * (1 - uu) * uu * cxm + uu * uu * a1[0], py = (1 - uu) * (1 - uu) * a0[1] + 2 * (1 - uu) * uu * cym + uu * uu * a1[1];
            var pg = g.createRadialGradient(px, py, 0, px, py, H * 0.009); pg.addColorStop(0, rgba(COL.fg, 0.95)); pg.addColorStop(1, rgba(COL.fg, 0));
            g.fillStyle = pg; g.fillRect(px - H * 0.009, py - H * 0.009, H * 0.018, H * 0.018);
          }
          g.globalCompositeOperation = "source-over";
        }
      });
    }
    // ---------------- your network: every device is a car on the grid (light trails), a new one blinks
    function drawCars(t, hz) {
      if (simpleMon) { return; }   // 1.6: [pulse] secondary = "simple"
      if (!carsOn || !lan.length || kind === "calm") return;
      var cx = W / 2, n = Math.min(lan.length, 14);
      g.globalCompositeOperation = "lighter";
      for (var i = 0; i < n; i++) {
        var d = lan[i], h = ((d.id || i) * 2654435761 % 4294967296) / 4294967296;
        var sign = i % 2 ? 1 : -1, lane = sign * (1.5 + ((i >> 1) % 8) * 1.0 + (h > 0.5 ? 1 : 0));
        var toward = d["new"] || ((d.id || i) % 3) !== 0;
        var sp = 0.03 + h * 0.035 + bass * 0.05;
        var p = (t * sp + h * 7) % 1; if (!toward) p = 1 - p;
        if (d["new"]) {   // a new device drives in from the horizon, toward you, again and again
          if (newAt[d.id] == null) newAt[d.id] = t;
          p = ((t - newAt[d.id]) * 0.11) % 1;
        }
        var pos = function (pp) { var f = Math.pow(pp, 2.2); return [cx + lane * W * (0.012 + 0.098 * f), hz + (H - hz) * f, f]; };
        var a = pos(p), b = pos(toward ? Math.max(0, p - 0.06) : Math.min(1, p + 0.06)), f = a[2];
        var lw = W * (0.012 + 0.098 * f), sz = H * (0.0018 + 0.009 * f);
        var col = d["new"] ? COL.warn : toward ? mix(COL.fg, COL.accent, 0.25) : COL.accent2;
        var on = !d["new"] || (t * 3 % 1) < 0.6, al = Math.min(1, f * 10) * (on ? 1 : 0.35);
        [-0.22, 0.22].forEach(function (o) {
          var x = a[0] + o * lw, x2 = b[0] + o * W * (0.012 + 0.098 * b[2]);
          var tr = g.createLinearGradient(x, a[1], x2, b[1]);
          tr.addColorStop(0, rgba(col, 0.55 * al)); tr.addColorStop(1, rgba(col, 0));
          g.strokeStyle = tr; g.lineWidth = sz * 0.9; g.beginPath(); g.moveTo(x, a[1]); g.lineTo(x2, b[1]); g.stroke();
          var gl = g.createRadialGradient(x, a[1], 0, x, a[1], sz * 3.2);
          gl.addColorStop(0, rgba(mix(col, COL.fg, 0.5), al)); gl.addColorStop(0.3, rgba(col, 0.5 * al)); gl.addColorStop(1, rgba(col, 0));
          g.fillStyle = gl; g.fillRect(x - sz * 3.2, a[1] - sz * 3.2, sz * 6.4, sz * 6.4);
        });
        if (!privacyOn && !shareMode && f > 0.12 && f < 0.92 && (d["new"] || d.name)) {
          g.globalCompositeOperation = "source-over";
          g.font = "600 " + Math.round(H * (0.009 + f * 0.007)) + "px 'CaskaydiaMono Nerd Font', 'Cascadia Mono', monospace";
          g.textAlign = "center"; g.fillStyle = rgba(d["new"] ? COL.warn : COL.fg, (d["new"] ? 0.95 : 0.5) * Math.min(1, (0.92 - f) * 6));
          g.fillText(d["new"] ? "new · " + d.ip : d.name, a[0], a[1] - sz * 3.4);
          g.globalCompositeOperation = "lighter";
        }
      }
      g.globalCompositeOperation = "source-over";
    }
    // ---------------- the Git city: your repositories are towers; this week's commits make them tall
    function drawGit(t, hz, cx, R) {
      if (!gitRepos.length || kind === "orbit" || kind === "calm") return;
      var n = Math.min(gitRepos.length, 8), ex = R * 1.2, sideW = [cx - ex - band[0], band[1] - cx - ex], half = Math.ceil(n / 2);
      var maxW = 1; gitRepos.forEach(function (r) { if (r.week > maxW) maxW = r.week; });
      gitPos = {};
      for (var i = 0; i < n; i++) {
        var r = gitRepos[i], right = i % 2 === 1, k = i >> 1;
        var slotW = sideW[right ? 1 : 0] / half, bw = Math.min(slotW * 0.42, H * 0.05);
        var x = right ? cx + ex + (k + 0.5) * slotW : cx - ex - (k + 0.5) * slotW;
        var hh = H * (0.09 + 0.22 * Math.log(1 + r.week) / Math.log(1 + maxW)) * (CITIES[city] ? 0.85 : 1);
        var fl = Math.max(0, 1 - (t - (gitFlash[r.name] || -99)) / 2.5);
        var gr = g.createLinearGradient(0, hz - hh, 0, hz);
        gr.addColorStop(0, rgba(mix(mix(COL.bg, COL.accent, 0.22), COL.fg, fl * 0.5), 1)); gr.addColorStop(1, rgba(mix(COL.bg, [0, 0, 0], 0.2), 1));
        g.fillStyle = gr; g.fillRect(x - bw / 2, hz - hh, bw, hh);
        g.strokeStyle = rgba(COL.accent, 0.55 + fl * 0.45); g.lineWidth = Math.max(1, H / 700); g.strokeRect(x - bw / 2, hz - hh, bw, hh);
        // windows: one row per day of work; uncommitted changes flicker amber
        var rows = Math.min(14, Math.floor(hh / (H * 0.018))), cols = 3;
        for (var ry = 0; ry < rows; ry++) for (var cxi = 0; cxi < cols; cxi++) {
          var on = ((i * 31 + ry * 7 + cxi * 3) % 5) < 3, dirty = r.dirty && ry < 2 && (t * 2 + cxi) % 2 < 1;
          if (!on && !dirty) continue;
          g.fillStyle = rgba(dirty ? COL.warn : COL.accent, dirty ? 0.9 : 0.35 + 0.4 * fl + 0.2 * ((ry + cxi) % 2));
          g.fillRect(x - bw / 2 + bw * (0.15 + cxi * 0.27), hz - hh + H * 0.012 + ry * H * 0.018, bw * 0.16, H * 0.008);
        }
        // the beacon on top: green CI, red failed (blinking), amber running
        var bc = r.ci === "ok" ? COL.ok : r.ci === "fail" ? COL.crit : r.ci === "run" ? COL.warn : COL.accent2;
        var blink = r.ci === "fail" || r.ci === "run" ? (t * 2 % 1) < 0.5 : true;
        g.fillStyle = rgba(bc, blink ? 1 : 0.25); g.fillRect(x - H / 600, hz - hh - H * 0.022, H / 300, H * 0.022);
        var bg2 = g.createRadialGradient(x, hz - hh - H * 0.022, 0, x, hz - hh - H * 0.022, H * 0.025);
        bg2.addColorStop(0, rgba(bc, blink ? 0.6 : 0.1)); bg2.addColorStop(1, rgba(bc, 0));
        g.fillStyle = bg2; g.fillRect(x - H * 0.025, hz - hh - H * 0.047, H * 0.05, H * 0.05);
        if (r.name && !shareMode) {
          g.font = "600 " + Math.round(H * 0.013) + "px 'CaskaydiaMono Nerd Font', 'Cascadia Mono', monospace"; g.textAlign = "center";
          g.fillStyle = rgba(COL.fg, 0.55 + fl * 0.45); g.fillText(r.name + (r.week ? " · " + r.week : ""), x, hz - hh - H * 0.03);
        }
        gitPos[r.name] = [x, hz - hh];
        buildSite(r, i, x, hz, hh, bw, t, fl);
      }
    }
    // 1.5: the code construction site. A new floor comes down with every commit, scaffolding while there are changes
    // not committed, a crane for every AI agent working on that repo, fire and the fire engine while its CI is red.
    var failAt = {};
    function buildSite(r, i, x, hz, hh, bw, t, fl) {
      var top = hz - hh, key = r.name || "#" + i, yel = mix(COL.warn, [255, 210, 60], 0.5);
      if (fl > 0 && fl < 1) {   // the new floor, lowered onto the roof
        var drop = Math.pow(fl, 2) * H * 0.07;
        g.fillStyle = rgba(mix(COL.bg, COL.accent, 0.4), 0.95); g.fillRect(x - bw / 2, top - H * 0.012 - drop, bw, H * 0.012);
        g.strokeStyle = rgba(yel, 0.8); g.lineWidth = 1; g.beginPath(); g.moveTo(x, top - H * 0.012 - drop); g.lineTo(x, top - H * 0.09); g.stroke();
      }
      if (r.dirty) {   // scaffolding on the top floors
        g.strokeStyle = rgba(yel, 0.5); g.lineWidth = Math.max(1, H / 1100);
        var sh = Math.min(hh, H * 0.06), x0 = x - bw / 2 - bw * 0.12, x1 = x + bw / 2 + bw * 0.12;
        g.strokeRect(x0, top, x1 - x0, sh);
        g.beginPath();
        for (var k = 0; k < 3; k++) { var yy = top + sh * k / 3; g.moveTo(x0, yy); g.lineTo(x1, yy + sh / 3); g.moveTo(x1, yy); g.lineTo(x0, yy + sh / 3); }
        g.stroke();
      }
      var work = agents.filter(function (a) {
        if (a.state !== "working") return false;
        var hay = ((a.name || "") + " " + (a.text || "")).toLowerCase();
        return r.name ? hay.indexOf(r.name.toLowerCase()) >= 0 : false;
      });
      if (!work.length && i === 0) work = agents.filter(function (a) { return a.state === "working" && !gitRepos.some(function (g2) { return g2.name && ((a.name || "") + " " + (a.text || "")).toLowerCase().indexOf(g2.name.toLowerCase()) >= 0; }); });
      work.slice(0, 2).forEach(function (a, j) {   // a tower crane: the mast beside the building, the jib over it
        var side = j ? -1 : 1, mx = x + side * (bw / 2 + bw * 0.35), mtop = top - H * 0.07, jib = bw * 1.3;
        g.strokeStyle = rgba(yel, 0.9); g.lineWidth = Math.max(1.5, H / 650);
        g.beginPath(); g.moveTo(mx, hz); g.lineTo(mx, mtop); g.moveTo(mx + side * bw * 0.3, mtop); g.lineTo(mx - side * jib, mtop); g.stroke();
        g.lineWidth = Math.max(1, H / 1400); g.beginPath();
        for (var y = hz; y > mtop; y -= H * 0.014) { g.moveTo(mx - H * 0.004, y); g.lineTo(mx + H * 0.004, y - H * 0.007); }
        g.stroke();
        var sw = reduced ? 0 : Math.sin(t * 1.3 + j) * bw * 0.25, hx = mx - side * jib * 0.7 + sw, hy = mtop + H * 0.03 + (reduced ? 0 : Math.sin(t * 0.7 + j) * H * 0.01);
        g.beginPath(); g.moveTo(mx - side * jib * 0.7 + sw * 0.2, mtop); g.lineTo(hx, hy); g.stroke();
        g.fillStyle = rgba(yel, 0.95); g.fillRect(hx - H * 0.004, hy, H * 0.008, H * 0.006);
        g.fillStyle = rgba(COL.bg, 0.9); g.fillRect(mx - H * 0.006, mtop + H * 0.002, H * 0.012, H * 0.01);   // the cab
        g.font = "bold " + Math.round(H * 0.008) + "px monospace"; g.textAlign = "center"; g.fillStyle = rgba(yel, 1);
        g.fillText(shareMode ? "" : String(a.name || "?").charAt(0).toUpperCase(), mx, mtop + H * 0.0095); g.textAlign = "start";
      });
      if (r.ci === "fail") {   // fire on the roof, and the fire engine on its way
        if (!failAt[key]) failAt[key] = t;
        for (var f = 0; f < 6; f++) {
          var fh = H * 0.03 * (reduced ? 0.8 : 0.6 + 0.4 * Math.abs(Math.sin(t * 7 + f * 1.7))), fx = x - bw / 2 + bw * (f + 0.5) / 6;
          var fg2 = g.createLinearGradient(0, top - fh, 0, top);
          fg2.addColorStop(0, rgba(COL.warn, 0)); fg2.addColorStop(1, rgba(COL.crit, 0.85));
          g.fillStyle = fg2; g.beginPath(); g.moveTo(fx - bw / 12, top); g.quadraticCurveTo(fx, top - fh * 1.2, fx + bw / 12, top); g.fill();
        }
        var go = Math.min(1, (t - failAt[key]) / 6), side2 = x > W / 2 ? 1 : -1;
        var tx = x + side2 * (W * 0.35 * (1 - go) + bw * 0.9), ty = hz + H * 0.03, tw = H * 0.05;
        g.fillStyle = rgba(COL.crit, 0.95); g.fillRect(tx - tw / 2, ty - H * 0.016, tw, H * 0.016);
        g.fillStyle = rgba(mix(COL.crit, COL.fg, 0.4), 1); g.fillRect(tx - side2 * tw * 0.35 - H * 0.006, ty - H * 0.024, H * 0.012, H * 0.008);
        g.fillStyle = rgba(COL.bg, 1); [-0.3, 0.3].forEach(function (u) { g.beginPath(); g.arc(tx + u * tw, ty, H * 0.005, 0, Math.PI * 2); g.fill(); });
        var bl2 = reduced || (t * 3 % 1) < 0.5; g.fillStyle = rgba(bl2 ? [80, 140, 255] : COL.crit, 1); g.fillRect(tx - H * 0.003, ty - H * 0.02, H * 0.006, H * 0.004);
        if (go >= 1) {   // the water
          g.strokeStyle = rgba([150, 200, 255], 0.55); g.lineWidth = Math.max(1.5, H / 700); g.beginPath();
          g.moveTo(tx - side2 * tw * 0.4, ty - H * 0.02); g.quadraticCurveTo((tx + x) / 2, top - H * 0.08, x + side2 * bw * 0.2, top - H * 0.005); g.stroke();
        }
      } else delete failAt[key];
    }
    // ---------------- the ISS, when it really passes over you (its place in your sky, from its orbit)
    function drawISS(t, hz) {
      var iss = space && space.iss;
      if (!iss) { issPos = null; issTrail = []; return; }
      var tx = W / 2 + Math.max(-1.05, Math.min(1.05, (iss.az - 180) / 90)) * W * 0.44, ty = hz - H * 0.05 - (iss.el / 90) * hz * 0.6;
      issPos = issPos ? [issPos[0] + (tx - issPos[0]) * 0.08, issPos[1] + (ty - issPos[1]) * 0.08] : [tx, ty];
      issTrail.push(issPos.slice()); if (issTrail.length > 60) issTrail.shift();
      g.globalCompositeOperation = "lighter";
      for (var i = 1; i < issTrail.length; i++) { g.strokeStyle = rgba(COL.fg, 0.4 * i / issTrail.length); g.lineWidth = Math.max(1, H / 600); g.beginPath(); g.moveTo(issTrail[i - 1][0], issTrail[i - 1][1]); g.lineTo(issTrail[i][0], issTrail[i][1]); g.stroke(); }
      var lit = iss.lit !== false, gl = g.createRadialGradient(issPos[0], issPos[1], 0, issPos[0], issPos[1], H * 0.03);
      gl.addColorStop(0, rgba(lit ? COL.fg : COL.accent2, 0.95)); gl.addColorStop(0.15, rgba(lit ? COL.fg : COL.accent2, 0.5)); gl.addColorStop(1, rgba(COL.fg, 0));
      g.fillStyle = gl; g.fillRect(issPos[0] - H * 0.03, issPos[1] - H * 0.03, H * 0.06, H * 0.06);
      g.globalCompositeOperation = "source-over";
      g.fillStyle = rgba(COL.fg, 1); g.fillRect(issPos[0] - H / 220, issPos[1] - H / 1400, H / 110, H / 700);   // the solar wings
      g.fillRect(issPos[0] - H / 1400, issPos[1] - H / 450, H / 700, H / 225);
      g.font = "600 " + Math.round(H * 0.014) + "px 'CaskaydiaMono Nerd Font', 'Cascadia Mono', monospace"; g.textAlign = "left";
      g.fillStyle = rgba(COL.fg, 0.85); g.fillText("ISS · " + iss.alt + " km · " + Math.round(iss.el) + "°", issPos[0] + H * 0.02, issPos[1] - H * 0.012);
    }
    // ---------------- the city that grows with you: one building for every focus you finished, lit while the streak lives
    function drawGrowth(t, hz) {
      if (simpleMon) { return; }   // 1.6: [pulse] secondary = "simple"
      if (!growth || !growth.recent || !growth.recent.length || kind === "orbit") return;
      var bs = growth.recent, n = bs.length, lit = growth.lit, y = hz + H * 0.035;
      var unit = Math.min(W * 0.011, (band[1] - band[0]) * 0.22 / 24);
      var newest = Math.max(0, 1 - (t - buildAt) / 6);
      for (var i = 0; i < n; i++) {
        var b = bs[i], right = (n - 1 - i) % 2 === 1, k = Math.floor((n - 1 - i) / 2);   // the newest at the front of the row
        if (k >= 24) continue;
        var x = right ? band[1] - unit * 1.3 * (k + 1) : band[0] + unit * 1.3 * (k + 1);   // from the edges of the free band inward
        var hh = H * (0.03 + Math.min(0.1, b.m / 900)), bw = unit;
        var fresh = i === n - 1 ? newest : 0;
        var rise = i === n - 1 && newest > 0 ? Math.min(1, (t - buildAt) / 1.5) : 1;   // the new one rises from the ground
        hh *= rise;
        g.fillStyle = rgba(mix(mix(COL.bg, COL.accent2, 0.25), COL.fg, fresh * 0.5), 1); g.fillRect(x - bw / 2, y - hh, bw, hh);
        g.strokeStyle = rgba(COL.accent2, 0.5 + fresh * 0.5); g.lineWidth = Math.max(1, H / 900); g.strokeRect(x - bw / 2, y - hh, bw, hh);
        if (lit) for (var wy = y - hh + H * 0.008; wy < y - H * 0.006; wy += H * 0.012) {
          g.fillStyle = rgba(((i * 7 + Math.round(wy)) % 3) ? COL.warn : COL.accent, 0.55 + fresh * 0.4);
          g.fillRect(x - bw * 0.25, wy, bw * 0.18, H * 0.005); g.fillRect(x + bw * 0.08, wy, bw * 0.18, H * 0.005);
        }
      }
      for (var mI = 0; mI < (growth.monuments || 0); mI++) {   // a monument for every 7 days in a row
        var mx = mI % 2 ? band[1] - unit * 1.3 * (Math.ceil(n / 2) + 2 + (mI >> 1) * 2) : band[0] + unit * 1.3 * (Math.ceil(n / 2) + 2 + (mI >> 1) * 2);
        var mh = H * (0.16 + 0.02 * mI);
        g.beginPath(); g.moveTo(mx - unit * 0.8, y); g.lineTo(mx - unit * 0.4, y - mh * 0.85); g.lineTo(mx, y - mh); g.lineTo(mx + unit * 0.4, y - mh * 0.85); g.lineTo(mx + unit * 0.8, y); g.closePath();
        g.fillStyle = rgba(mix(COL.bg, COL.accent2, 0.35), 1); g.fill(); g.strokeStyle = rgba(COL.accent2, lit ? 0.95 : 0.4); g.stroke();
        if (lit) { var mb = g.createRadialGradient(mx, y - mh, 0, mx, y - mh, H * 0.03); mb.addColorStop(0, rgba(COL.accent2, 0.9)); mb.addColorStop(1, rgba(COL.accent2, 0)); g.fillStyle = mb; g.fillRect(mx - H * 0.03, y - mh - H * 0.03, H * 0.06, H * 0.06); }
      }
      if (newest > 0) {
        var nx = band[0] + unit * 1.3;
        var gl = g.createRadialGradient(nx, y - H * 0.05, 0, nx, y - H * 0.05, H * 0.08);
        gl.addColorStop(0, rgba(COL.accent2, 0.5 * newest)); gl.addColorStop(1, rgba(COL.accent2, 0));
        g.fillStyle = gl; g.fillRect(nx - H * 0.08, y - H * 0.13, H * 0.16, H * 0.16);
      }
    }
    // ---------------- the planes over you: where they really are in your sky, with their lights
    // where the panels and the sphere are, in canvas pixels (labels keep away from them)
    var uiB = [], uiBAt = 0;
    function uiBoxes() {
      var now = performance.now();
      if (now - uiBAt < 1000) return uiB;
      uiBAt = now; uiB = [];
      var k = W / Math.max(1, innerWidth);
      document.querySelectorAll(".panel").forEach(function (el) {
        var r = el.getBoundingClientRect(); if (r.width && r.height && el.style.opacity !== "0") uiB.push([r.left * k, r.top * k, r.width * k, r.height * k]);
      });
      var R = H * 0.17; uiB.push([W / 2 - R, H * 0.745 - R * 2, R * 2, R * 2]);   // the sphere
      return uiB;
    }
    // a plane where a panel is would fly unseen under it: it is drawn just beside the panel, on the side of the middle of
    // the screen, at the same height (the sphere is not a panel: the planes fly in front of it)
    function clearOfPanels(x, y, m) {
      var bs = uiBoxes().slice(0, -1);
      for (var n = 0; n < 4; n++) {
        var hit = null;
        for (var j = 0; j < bs.length; j++) { var b = bs[j]; if (x > b[0] - m && x < b[0] + b[2] + m && y > b[1] - m && y < b[1] + b[3] + m) { hit = b; break; } }
        if (!hit) return x;
        x = hit[0] + hit[2] / 2 > W / 2 ? hit[0] - m : hit[0] + hit[2] + m;
      }
      return x;
    }
    function drawPlanes(t, hz) {
      if (simpleMon) { return; }   // 1.6: [pulse] secondary = "simple"
      if (!planes.length) return;
      var seen = {}, boxes = [], shown = 0;
      for (var i = 0; i < planes.length; i++) {
        var p = planes[i];
        // you look south: east on the left, west on the right. A plane behind you (north) is not in this sky, unless it
        // is almost overhead: then it flies near the top, from the side it really is on.
        var front = p.az >= 75 && p.az <= 285, over = p.el >= 62;
        if (!front && !over) continue;
        if (++shown > 6) break;
        seen[p.id] = 1;
        var tx = front ? W / 2 + (p.az - 180) / 90 * W * 0.44
                       : W / 2 - Math.sin(p.az * Math.PI / 180) * W * 0.44 * (90 - p.el) / 28;
        var ty = hz * 0.93 - Math.sqrt(p.el / 90) * hz * 0.85;   // the sky above the towers: most planes are 10-40° up, so
        // the low part of the sky gets more room; straight up is still the top of the screen
        tx = clearOfPanels(tx, ty, H * 0.04);
        var q = planePos[p.id] || (planePos[p.id] = [tx, ty]);
        q[0] += (tx - q[0]) * 0.03; q[1] += (ty - q[1]) * 0.03;
        var s2 = H * (0.009 + p.el / 90 * 0.008), dir = Math.sin(p.track * Math.PI / 180) > 0 ? -1 : 1;   // heading east flies to the left (you look south)
        // where it came from: a contrail that fades behind it
        var tr = q.trail || (q.trail = []);
        if (!tr.length || Math.abs(tr[tr.length - 1][0] - q[0]) + Math.abs(tr[tr.length - 1][1] - q[1]) > 2) { tr.push([q[0], q[1]]); if (tr.length > 70) tr.shift(); }
        for (var k = 1; k < tr.length; k++) {
          g.strokeStyle = rgba(COL.fg, 0.28 * k / tr.length); g.lineWidth = Math.max(1, s2 * 0.35 * k / tr.length);
          g.beginPath(); g.moveTo(tr[k - 1][0], tr[k - 1][1]); g.lineTo(tr[k][0], tr[k][1]); g.stroke();
        }
        // where it really moves on the screen wins over its heading (a turn, a plane almost overhead)
        if (tr.length > 8) { var mdx = q[0] - tr[tr.length - 9][0]; if (Math.abs(mdx) > s2 * 0.6) dir = mdx > 0 ? 1 : -1; }
        // the plane seen from below: fuselage, swept wings, tail, the nose where it goes
        var fx = q[0], fy = q[1], sx = dir;
        g.fillStyle = rgba(mix(COL.fg, COL.bg, 0.25), 0.95); g.beginPath();
        g.moveTo(fx + sx * s2 * 2.6, fy); g.lineTo(fx - sx * s2 * 2.2, fy - s2 * 0.28); g.lineTo(fx - sx * s2 * 2.2, fy + s2 * 0.28); g.closePath(); g.fill();
        g.beginPath(); g.moveTo(fx + sx * s2 * 0.5, fy); g.lineTo(fx - sx * s2 * 0.6, fy - s2 * 2); g.lineTo(fx - sx * s2 * 0.2, fy); g.lineTo(fx - sx * s2 * 0.6, fy + s2 * 2); g.closePath(); g.fill();
        g.beginPath(); g.moveTo(fx - sx * s2 * 1.6, fy); g.lineTo(fx - sx * s2 * 2.2, fy - s2 * 0.9); g.lineTo(fx - sx * s2 * 2.0, fy); g.lineTo(fx - sx * s2 * 2.2, fy + s2 * 0.9); g.closePath(); g.fill();
        var blink = (t + i * 0.37) % 1.2;
        g.globalCompositeOperation = "lighter";
        [[-2, COL.crit], [2, COL.ok]].forEach(function (L) {   // red on its left wing, green on its right, as seen from below
          var lx = q[0] - sx * s2 * 0.6, ly = q[1] + (L[0] < 0 ? 1 : -1) * s2 * 2 * sx, gl = g.createRadialGradient(lx, ly, 0, lx, ly, s2 * 0.9);
          gl.addColorStop(0, rgba(L[1], 1)); gl.addColorStop(1, rgba(L[1], 0)); g.fillStyle = gl; g.fillRect(lx - s2 * 0.9, ly - s2 * 0.9, s2 * 1.8, s2 * 1.8);
        });
        if (blink < 0.08) { var sg = g.createRadialGradient(q[0], q[1], 0, q[0], q[1], s2 * 2); sg.addColorStop(0, rgba(COL.fg, 0.9)); sg.addColorStop(1, rgba(COL.fg, 0)); g.fillStyle = sg; g.fillRect(q[0] - s2 * 2, q[1] - s2 * 2, s2 * 4, s2 * 4); }   // the white strobe, a short flash
        g.globalCompositeOperation = "source-over";
        var lw = H * 0.2, lh = H * 0.034;
        var over = function (bx, list) { return list.some(function (b) { return !(bx[0] > b[0] + b[2] || bx[0] + bx[2] < b[0] || bx[1] > b[1] + b[3] || bx[1] + bx[3] < b[1]); }); };
        var cands = [[q[0] + s2 * 2.4, q[1] - s2 * 2.6 - H * 0.013], [q[0] - s2 * 2.4 - lw, q[1] - s2 * 2.6 - H * 0.013],   // right, left,
                     [q[0] + s2 * 2.4, q[1] + s2 * 2.2], [q[0] - s2 * 2.4 - lw, q[1] + s2 * 2.2]].map(function (c) { return [c[0], c[1], lw, lh]; });   // below
        // the best place: clear of other labels and of the panels; else clear of other labels only (a panel just hides a part)
        var lb = cands.filter(function (c) { return !over(c, boxes) && !over(c, uiBoxes()); })[0] || cands.filter(function (c) { return !over(c, boxes); })[0];
        var free = !!lb;
        if (!shareMode && p.flight && free) {   // a label only where it does not cover another one, or a panel
          boxes.push(lb);
          g.font = "600 " + Math.round(H * 0.013) + "px 'CaskaydiaMono Nerd Font', 'Cascadia Mono', monospace"; g.textAlign = "left";
          g.fillStyle = rgba(COL.fg, 0.75); var lx2 = lb[0], ly2 = lb[1] + H * 0.013;   // the flight, the aircraft, how high; then where from, where to
          g.fillText(p.flight + (p.type ? " · " + p.type : "") + " · " + (Math.round(p.ft / 100) * 100).toLocaleString("en-US") + " ft", lx2, ly2);
          if (p.from && p.to) {
            g.fillStyle = rgba(COL.accent2, 0.85);
            g.fillText(p.from + " → " + p.to + (p.fromCity && p.toCity ? "  " + p.fromCity + " → " + p.toCity : ""), lx2, ly2 + H * 0.016);
          }
        }
      }
      for (var id in planePos) if (!seen[id]) delete planePos[id];
    }
    // ---------------- your calendar: an elevated line across the city; each event is a train coming into the station
    function drawTrains(t, hz) {
      if (simpleMon) { return; }   // 1.6: [pulse] secondary = "simple"
      if (!trains.length || kind === "calm") return;
      var y = hz - H * 0.006, st = band[0] + (band[1] - band[0]) * 0.16, now = hubNow(), span = 3 * 3600;
      g.strokeStyle = rgba(COL.accent, 0.5); g.lineWidth = Math.max(1, H / 600);
      g.beginPath(); g.moveTo(band[0], y); g.lineTo(band[1], y); g.stroke();
      g.fillStyle = rgba(COL.accent, 0.8); g.fillRect(st - H * 0.03, y - H * 0.028, H * 0.06, H * 0.004);   // the station roof
      g.fillRect(st - H * 0.03, y - H * 0.028, H * 0.003, H * 0.028); g.fillRect(st + H * 0.027, y - H * 0.028, H * 0.003, H * 0.028);
      for (var i = 0; i < trains.length; i++) {
        var e = trains[i], dt = e.start - now, inside = dt <= 0 && e.end > now;
        var x = inside ? st : st + Math.max(0, Math.min(1, dt / span)) * (band[1] - st) * (dt > 0 ? 1 : 0);
        var soon = dt > 0 && dt < 300, col = inside ? COL.ok : soon ? COL.warn : COL.accent2;
        var cw = H * 0.016, ch = H * 0.009;
        for (var k = 0; k < 4; k++) {
          var cx2 = x + k * (cw + H * 0.002);
          g.fillStyle = rgba(mix(COL.bg, col, 0.35), 1); g.fillRect(cx2, y - ch - H * 0.002, cw, ch);
          g.fillStyle = rgba(col, soon ? ((t * 3) % 1 < 0.6 ? 1 : 0.4) : 0.9);
          for (var wv = 0; wv < 3; wv++) g.fillRect(cx2 + cw * (0.12 + wv * 0.3), y - ch, cw * 0.18, ch * 0.4);
        }
        var hl = g.createRadialGradient(x, y - ch / 2, 0, x, y - ch / 2, H * 0.025); hl.addColorStop(0, rgba(col, 0.7)); hl.addColorStop(1, rgba(col, 0));
        g.fillStyle = hl; g.fillRect(x - H * 0.025, y - ch / 2 - H * 0.025, H * 0.05, H * 0.05);
        if (!shareMode && e.title) {
          var mins = Math.round(dt / 60), when = inside ? "now" : mins < 60 ? "in " + mins + " min" : new Date(e.start * 1000).toTimeString().slice(0, 5);
          g.font = "600 " + Math.round(H * 0.012) + "px 'CaskaydiaMono Nerd Font', 'Cascadia Mono', monospace"; g.textAlign = "left";
          g.fillStyle = rgba(col, 0.95); g.fillText(e.title + " · " + when, x, y - ch - H * 0.008);
        }
      }
    }
    // ---------------- your electric car, parked at the foot of the city: a light bar fills while it charges
    // ---------------- your containers: humanoid robots walking on the floor (eyes: green ok, amber restarting, red unhealthy)
    var robots = [], robotPos = {}, agentsAs = "drones";
    function drawRobots(t, hz) {
      if (simpleMon) { return; }   // 1.6: [pulse] secondary = "simple"
      if (!robots.length || kind === "calm" || kind === "orbit") return;
      var u = H / 1080, seen = {};
      for (var i = 0; i < Math.min(12, robots.length); i++) {
        var rb = robots[i]; seen[rb.id] = 1;
        var P = robotPos[rb.id];
        if (!P) {   // a new container: a robot walks in from the side
          var lane = (parseInt(rb.id.slice(0, 4), 16) || i * 997) % 1000 / 1000;
          P = robotPos[rb.id] = { x: band[0] + (0.1 + 0.8 * Math.random()) * (band[1] - band[0]), lane: lane, dir: Math.random() < 0.5 ? 1 : -1, ph: Math.random() * 6, born: t };
        }
        var y = hz + (H - hz) * (0.18 + P.lane * 0.55), sc = (0.7 + P.lane * 0.9) * u;   // nearer is bigger
        var bad = rb.health === "bad", walking = !bad;
        if (walking) { var fast = rb.busy ? 2.2 : 1; P.x += P.dir * sc * (0.55 + P.lane * 0.3) * fast; P.ph += 0.16 * fast; }   // a bot at work hurries
        if (P.x > band[1] - 20) P.dir = -1; if (P.x < band[0] + 20) P.dir = 1;   // they walk where you can see them
        if (Math.random() < 0.001) P.dir = -P.dir;
        var sw = walking ? Math.sin(P.ph) : 0, bob = walking ? Math.abs(Math.cos(P.ph)) * 1.5 * sc : 0;
        var x = P.x, body = rb.bot ? mix(COL.bg, COL.accent2, 0.6) : mix(COL.bg, COL.fg, 0.55), line = rb.bot ? mix(COL.fg, COL.accent2, 0.4) : mix(COL.fg, COL.accent, 0.3);
        var eye = bad ? COL.crit : rb.health === "restart" ? COL.warn : COL.ok;
        g.save(); g.translate(x, y - bob); g.scale(P.dir, 1);
        g.strokeStyle = rgba(line, 0.9); g.lineWidth = 2.2 * sc; g.lineCap = "round";
        g.beginPath(); g.moveTo(-2 * sc, -12 * sc); g.lineTo(-2 * sc + sw * 4 * sc, 0); g.moveTo(2 * sc, -12 * sc); g.lineTo(2 * sc - sw * 4 * sc, 0); g.stroke();   // legs
        g.beginPath(); g.moveTo(-4.5 * sc, -24 * sc); g.lineTo(-4.5 * sc - sw * 3 * sc, -15 * sc); g.moveTo(4.5 * sc, -24 * sc); g.lineTo(4.5 * sc + sw * 3 * sc, -15 * sc); g.stroke();   // arms
        g.fillStyle = rgba(body, 0.95); g.beginPath(); g.rect(-4.5 * sc, -26 * sc, 9 * sc, 14 * sc); g.fill(); g.stroke();   // body
        g.beginPath(); g.rect(-4 * sc, -34 * sc, 8 * sc, 7 * sc); g.fill(); g.stroke();   // head
        g.beginPath(); g.moveTo(0, -34 * sc); g.lineTo(0, -37 * sc); g.stroke();          // antenna
        if (rb.bot) { g.fillStyle = rgba(COL.accent, (t * 2) % 1 < 0.6 ? 1 : 0.4); g.beginPath(); g.arc(0, -38 * sc, 1.6 * sc, 0, Math.PI * 2); g.fill();   // an AI bot: a light on its antenna, its core on the chest
          g.fillStyle = rgba(COL.accent, 0.9); g.beginPath(); g.arc(0, -20 * sc, 1.8 * sc, 0, Math.PI * 2); g.fill(); }
        var blink = bad ? ((t * 3) % 1 < 0.5 ? 1 : 0.2) : 1;
        g.fillStyle = rgba(eye, blink); g.fillRect(-2.6 * sc, -32 * sc, 2 * sc, 2 * sc); g.fillRect(0.8 * sc, -32 * sc, 2 * sc, 2 * sc);
        var eg = g.createRadialGradient(1 * sc, -31 * sc, 0, 1 * sc, -31 * sc, 8 * sc); eg.addColorStop(0, rgba(eye, 0.35 * blink)); eg.addColorStop(1, rgba(eye, 0));
        g.fillStyle = eg; g.fillRect(-8 * sc, -39 * sc, 16 * sc, 16 * sc);
        g.restore();
        g.fillStyle = rgba(COL.fg, 0.12); g.beginPath(); g.ellipse(x, y + 1 * sc, 6 * sc, 1.6 * sc, 0, 0, Math.PI * 2); g.fill();   // its shadow
        if (!shareMode && rb.name) { g.font = "600 " + Math.round(Math.max(9, 10 * sc / u * u)) + "px 'CaskaydiaMono Nerd Font', monospace"; g.textAlign = "center"; g.fillStyle = rgba(eye, 0.75); g.fillText(rb.name, x, y + 11 * sc); }
      }
      for (var id in robotPos) if (!seen[id]) delete robotPos[id];
    }
    // ---------------- a small plane towing a banner: a tribute now and then, and the news when a new Omarchy is out
    var banners = [], bannerText = "", bannerEvery = 45, bannerNext = 0, logo = null, logoTint = null, logoKey = "";
    (function () { var im = new Image(); im.onload = function () { logo = im; }; im.src = "omarchy.png"; })();   // the Omarchy icon (Dashboard Icons, Apache-2.0)
    function tintedLogo(col) {   // the logo in your theme's colour
      if (!logo) return null;
      var key = col.join(",");
      if (logoKey === key && logoTint) return logoTint;
      var c = document.createElement("canvas"); c.width = c.height = 128; var x = c.getContext("2d");
      x.drawImage(logo, 0, 0, 128, 128); x.globalCompositeOperation = "source-in"; x.fillStyle = rgba(col, 1); x.fillRect(0, 0, 128, 128);
      logoKey = key; logoTint = c; return c;
    }
    // a thank-you to Omarchy: a plane tows its icon and your words across the whole sky, left to right, in 16 seconds
    function banner(text) { if (text) banners.push({ t: performance.now(), text: String(text).toUpperCase(), dir: 1, y: 0.07 }); }   // high in the sky, above the clock and the panels' text
    function drawBanners(now, hz) {
      if (simpleMon) { return; }   // 1.6: [pulse] secondary = "simple"
      if (bannerText && bannerEvery > 0 && !away && PRIMARY) {
        if (!bannerNext) bannerNext = now + (1 + Math.random() * 2) * 60000;   // the first one a minute or two after start
        // when it is time, it waits until you can see the sky (no window over the desktop), so you do not miss it
        if (now > bannerNext && deskEmpty && !covered && !banners.length) { banner(bannerText); bannerNext = now + bannerEvery * 60000 * (0.7 + Math.random() * 0.6); }
      }
      var keep = [];
      for (var i = 0; i < banners.length; i++) {
        var b = banners[i], u = (now - b.t) / 16000; if (u > 1) continue; keep.push(b);
        var s = H / 1080, x = b.dir > 0 ? -W * 0.25 + u * W * 1.5 : W * 1.25 - u * W * 1.5, y = hz * b.y + Math.sin(u * 9) * H * 0.006;
        g.font = "800 " + Math.round(H * 0.036) + "px 'CaskaydiaMono Nerd Font', 'Cascadia Mono', monospace";
        var lg = tintedLogo(COL.accent2), bh = H * 0.06, lw = lg ? bh * 0.8 + H * 0.016 : 0;
        var tw = g.measureText(b.text).width + H * 0.03 + lw, bx = x - b.dir * (H * 0.05) - (b.dir > 0 ? tw : 0);
        g.strokeStyle = rgba(COL.fg, 0.6); g.lineWidth = Math.max(1, s); g.beginPath();   // the tow line
        g.moveTo(x - b.dir * H * 0.012, y); g.lineTo(b.dir > 0 ? bx + tw : bx, y + bh * 0.1); g.stroke();
        g.save(); g.beginPath();   // the banner, waving in the wind
        for (var k = 0; k <= 20; k++) { var px = bx + tw * k / 20, wv = Math.sin(now / 180 + k * 0.6) * bh * 0.08; if (k) g.lineTo(px, y - bh * 0.4 + wv); else g.moveTo(px, y - bh * 0.4 + wv); }
        for (k = 20; k >= 0; k--) { px = bx + tw * k / 20; wv = Math.sin(now / 180 + k * 0.6) * bh * 0.08; g.lineTo(px, y + bh * 0.6 + wv); }
        g.closePath(); g.fillStyle = rgba(mix(COL.bg, COL.fg, 0.92), 0.95); g.fill(); g.strokeStyle = rgba(COL.accent, 0.9); g.stroke();
        if (lg) g.drawImage(lg, bx + H * 0.012, y - bh * 0.3, bh * 0.8, bh * 0.8);
        g.fillStyle = rgba(COL.accent2, 1); g.textAlign = "center"; g.textBaseline = "middle"; g.fillText(b.text, bx + lw + (tw - lw) / 2, y + bh * 0.1);
        g.textBaseline = "alphabetic"; g.restore();
        g.save(); g.translate(x, y); g.scale(b.dir * 1.6, 1.6);   // the little propeller plane
        g.fillStyle = rgba(mix(COL.fg, COL.accent, 0.3), 1);
        g.beginPath(); g.ellipse(0, 0, 18 * s, 4.5 * s, 0, 0, Math.PI * 2); g.fill();
        g.fillRect(-4 * s, -11 * s, 6 * s, 22 * s); g.fillRect(-17 * s, -6 * s, 4 * s, 6 * s);
        g.fillStyle = rgba(COL.fg, 0.5); g.fillRect(17 * s, -7 * s * Math.abs(Math.sin(now / 30)), 1.5 * s, 14 * s * Math.abs(Math.sin(now / 30)));
        g.fillStyle = rgba(COL.crit, (now / 500) % 1 < 0.5 ? 1 : 0.3); g.fillRect(-1 * s, -11 * s, 2 * s, 2 * s);
        g.restore();
      }
      banners = keep;
    }
    // ---------------- 1.6: the Hyprland workspaces tower, the ivy of your uptime, the parcels of your updates, a balloon of tips
    var tower = [], towerAt = [], uptimeS = 0, ivyOn = true, parcels = null, tipsList = [], tipsEvery = 40, tipNext = 0, balloons = [], meetingOn = false;
    var wledOn = false, glowAt = 0;
    function hashCol(s) {   // an app always gets the same colour: one of the theme's
      var h = 0; for (var i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
      return [COL.accent, COL.accent2, COL.ok, COL.warn, mix(COL.accent, COL.fg, 0.4)][Math.abs(h) % 5];
    }
    var APP_ICONS = [[/chrom|brave|vivaldi/, "\uf268"], [/firefox|zen|librewolf/, "\uf269"], [/ghostty|alacritty|kitty|foot|wezterm|terminal|konsole/, "\uf120"],
                     [/code|cursor|zed|nvim|neovide|jetbrains|idea/, "\uf121"], [/spotify|music|rhythmbox/, "\uf1bc"], [/discord|vesktop/, "\uf392"],
                     [/signal|telegram|whatsapp|slack|teams|chat/, "\uf086"], [/nautilus|thunar|files|dolphin|yazi/, "\uf07b"], [/steam|lutris|game/, "\uf1b6"],
                     [/obsidian|typora|notes|logseq/, "\uf249"], [/mpv|vlc|celluloid|video/, "\uf03d"], [/gimp|krita|inkscape|pinta/, "\uf1fc"],
                     [/mail|thunderbird|hey/, "\uf0e0"], [/1password|bitwarden|keepass/, "\uf084"], [/libreoffice|office|docs/, "\uf15c"]];
    function appIcon(cls) { cls = String(cls || "").toLowerCase(); if (!cls) return ""; for (var i = 0; i < APP_ICONS.length; i++) if (APP_ICONS[i][0].test(cls)) return APP_ICONS[i][1]; return "\uf2d0"; }   // a window
    function drawTower(t, hz) {
      if (simpleMon) { return; }   // 1.6: [pulse] secondary = "simple"
      if (worldOff() || kind === "calm" || kind === "orbit" || kind === "noir") return;
      var floors = tower.length ? tower : [{ id: 1, active: true, apps: [] }];
      var u = H / 1080, fh = Math.min(H * 0.036, H * 0.22 / floors.length), w = H * 0.085, x = band[0] + (band[1] - band[0]) * 0.035, y0 = hz - 1;
      var top = y0 - fh * floors.length;
      towerAt = [x, top, w, y0 - top];
      g.fillStyle = rgba(mix(COL.bg, COL.fg, 0.08), 1); g.fillRect(x, top, w, y0 - top);
      g.strokeStyle = rgba(COL.accent, 0.55); g.lineWidth = Math.max(1, u); g.strokeRect(x + 0.5, top + 0.5, w - 1, y0 - top - 1);
      g.fillStyle = rgba(COL.accent, 0.8); g.fillRect(x + w * 0.45, top - H * 0.02, Math.max(1, u * 1.5), H * 0.02);   // the antenna
      if ((t % 1.7) < 0.22) { g.fillStyle = rgba(COL.crit, 0.95); g.fillRect(x + w * 0.45 - u, top - H * 0.022, 3 * u, 3 * u); }
      g.font = "600 " + Math.round(fh * 0.42) + "px 'CaskaydiaMono Nerd Font', 'Cascadia Mono', monospace"; g.textAlign = "left"; g.textBaseline = "middle";
      var act = 0;
      for (var i = 0; i < floors.length; i++) {
        var f = floors[i], fy = y0 - fh * (i + 1);
        if (f.active) { act = fy; g.fillStyle = rgba(COL.accent, 0.16); g.fillRect(x + 1, fy + 1, w - 2, fh - 2); }
        g.fillStyle = rgba(f.active ? COL.accent : COL.fg, f.active ? 1 : 0.45); g.fillText(String(f.id), x + w * 0.06, fy + fh / 2);
        for (var k = 0; k < Math.min(4, f.apps.length); k++) {   // a lit room for every app on that workspace, with its icon
          var c = f.apps[k] ? hashCol(f.apps[k]) : COL.accent, rw = w * 0.15, wx = x + w * (0.24 + k * 0.17);
          g.fillStyle = rgba(c, f.active ? 0.9 : 0.55); g.fillRect(wx, fy + fh * 0.16, rw, fh * 0.68);
          var ic = appIcon(f.apps[k]);
          if (ic && fh > H * 0.018) { g.save(); g.font = Math.round(Math.min(rw, fh * 0.6) * 0.95) + "px 'CaskaydiaMono Nerd Font', 'Symbols Nerd Font', monospace"; g.textAlign = "center";
            g.fillStyle = rgba(COL.bg, 0.95); g.fillText(ic, wx + rw / 2, fy + fh / 2 + 1); g.restore(); }
        }
        g.fillStyle = rgba(COL.fg, 0.12); g.fillRect(x, fy, w, Math.max(1, u));
      }
      // the lift goes to the workspace you are on
      if (act) { towerLift += (act - towerLift) * (reduced ? 1 : 0.12); if (!towerLift || Math.abs(towerLift - act) > H) towerLift = act; }
      g.fillStyle = rgba(COL.fg, 0.85); g.fillRect(x + w - w * 0.1, towerLift + fh * 0.18, w * 0.07, fh * 0.64);
      g.textBaseline = "alphabetic";
      if (ivyOn) drawIvy(t, x, top, w, y0);
    }
    var towerLift = 0, friends = [], friendsAt = 0, newsSeen = "", hubT = 0, hubAt = 0;
    function hubNow() { return hubT ? hubT + (performance.now() - hubAt) / 1000 : Date.now() / 1000; }
    // 1.6: the friends' neighbourhood: on the far horizon, a building for each friend of your crew who shares it, as tall
    // as their commits of the week; a floor flashes when they commit, the light on top is their CI
    function drawFriends(t, hz) {
      if (simpleMon || !friends.length || worldOff() || kind === "calm" || kind === "orbit" || kind === "noir") return;
      var u = H / 1080, x0 = band[1] - (band[1] - band[0]) * 0.035;
      g.font = "600 " + Math.round(H * 0.0095) + "px 'CaskaydiaMono Nerd Font', 'Cascadia Mono', monospace"; g.textAlign = "center";
      friends.forEach(function (f, i) {
        var w = H * 0.026, x = x0 - i * w * 1.6 - w, hh = H * (0.04 + 0.09 * Math.min(1, Math.log(1 + f.week) / Math.log(80))), top = hz - hh;
        var fl = f.flashAge == null ? 0 : Math.max(0, 1 - (f.flashAge + (performance.now() - friendsAt) / 1000) / 4), floors = Math.max(2, Math.round(hh / (H * 0.008)));
        g.fillStyle = rgba(mix(COL.bg, COL.accent2, 0.16), 0.85); g.fillRect(x, top, w, hh);
        g.strokeStyle = rgba(COL.accent2, 0.45); g.lineWidth = Math.max(1, u); g.strokeRect(x + 0.5, top + 0.5, w - 1, hh - 1);
        for (var k = 0; k < floors; k++) {   // its lit floors: more commits, more light
          var lit = ((k * 7 + i * 13) % 10) / 10 < Math.min(1, f.week / 40);
          if (!lit && !(fl > 0 && k === floors - 1)) continue;
          var fy = hz - (k + 1) * hh / floors + hh / floors * 0.3, hot = fl > 0 && k === floors - 1;
          g.fillStyle = rgba(hot ? COL.fg : COL.accent2, hot ? 0.6 + 0.4 * fl : 0.55); g.fillRect(x + w * 0.15, fy, w * 0.7, Math.max(1, hh / floors * 0.4));
        }
        if (fl > 0 && !reduced) { var gl = g.createRadialGradient(x + w / 2, top, 0, x + w / 2, top, H * 0.05); gl.addColorStop(0, rgba(COL.accent2, 0.5 * fl)); gl.addColorStop(1, rgba(COL.accent2, 0)); g.fillStyle = gl; g.fillRect(x - H * 0.05, top - H * 0.05, w + H * 0.1, H * 0.1); }
        if (f.ci) { g.fillStyle = rgba(f.ci === "fail" ? COL.crit : COL.ok, reduced || (t * 0.8 + i) % 1.6 < 1.2 ? 1 : 0.3); g.fillRect(x + w / 2 - 1.5 * u, top - 4 * u, 3 * u, 3 * u); }
        if (f.name && !shareMode) { g.fillStyle = rgba(COL.accent2, 0.75); g.fillText(f.name, x + w / 2, top - 8 * u); }
      });
      g.textAlign = "left";
    }
    function flower(xx, yy, r, open, t, seed) {   // five petals round a warm heart; open 0..1, breathing a little
      if (open <= 0) return;
      var br = reduced ? 1 : 1 + 0.06 * Math.sin(t * 1.3 + seed), pr = r * 0.48 * (0.45 + 0.55 * open) * br, d = r * 0.52 * open * br;
      var rot = seed * 1.7 + (reduced ? 0 : Math.sin(t * 0.5 + seed) * 0.15);
      g.fillStyle = rgba(mix(COL.accent2, COL.fg, 0.12), 0.95);
      for (var p = 0; p < 5; p++) {
        var a = rot + p * Math.PI * 2 / 5;
        g.beginPath(); g.ellipse(xx + Math.cos(a) * d, yy + Math.sin(a) * d, pr, pr * 0.72, a, 0, Math.PI * 2); g.fill();
      }
      g.fillStyle = rgba(COL.warn, 1); g.beginPath(); g.arc(xx, yy, r * 0.26, 0, Math.PI * 2); g.fill();
    }
    function bloomOpen() {   // a week without a reboot: the flowers open over the next six hours
      var days = uptimeS / 86400;
      return days < 7 ? 0 : reduced ? 1 : Math.min(1, 0.25 + (days - 7) * 4 * 0.75);
    }
    function ivyOn2(t, x, y0, hgt, seed) {   // 1.6: ivy on a building of the city: higher the longer the PC stays on
      if (hgt < H * 0.004) return;
      var u = H / 1080, n = Math.round(hgt / (H * 0.006)), sway = reduced ? 0 : Math.sin(t * 0.8 + seed) * u * 0.6;
      for (var k = 0; k < n; k++) {
        var q = k / Math.max(1, n), yy = y0 - hgt * q, xx = x + Math.sin(q * 11 + seed) * 3 * u + sway * q;
        g.fillStyle = rgba(mix(COL.ok, COL.fg, (k % 4) * 0.07), 0.8);
        g.beginPath(); g.ellipse(xx, yy, 2.2 * u, 1.3 * u, k % 2 ? 0.6 : -0.6, 0, Math.PI * 2); g.fill();
      }
      var op = bloomOpen();   // the city's ivy flowers too, a few on each building
      if (op) for (k = 0; k < 3; k++) { var qf = 0.35 + k * 0.25; flower(x + Math.sin(qf * 11 + seed) * 3 * u + sway * qf, y0 - hgt * qf, 4.5 * u, op, t, seed + k); }
    }
    function drawIvy(t, x, top, w, y0) {
      // the ivy of your uptime: it climbs a little every hour the PC stays on (all the way up after a week), and
      // starts again from the ground after a reboot
      var days = uptimeS / 86400, k = Math.min(1, days / 7), hgt = (y0 - top) * k;
      if (hgt < H * 0.004) return;
      var u = H / 1080, leaves = Math.round(6 + 60 * k), sway = reduced ? 0 : Math.sin(t * 0.8) * u;
      g.strokeStyle = rgba(mix(COL.ok, COL.bg, 0.35), 0.9); g.lineWidth = Math.max(1, 1.4 * u); g.beginPath();
      for (var s = 0; s <= 1.0001; s += 0.05) { var yy = y0 - hgt * s, xx = x + Math.sin(s * 9) * w * 0.12 + w * 0.06; if (s) g.lineTo(xx + sway * s, yy); else g.moveTo(xx, yy); }
      g.stroke();
      for (var i = 0; i < leaves; i++) {
        var q = ((i * 0.618) % 1), yy2 = y0 - hgt * q, xx2 = x + Math.sin(q * 9) * w * 0.12 + w * 0.06 + (i % 2 ? 1 : -1) * 3 * u + sway * q;
        g.fillStyle = rgba(mix(COL.ok, COL.fg, (i % 5) * 0.06), 0.85);
        g.beginPath(); g.ellipse(xx2, yy2, 2.6 * u, 1.5 * u, (i % 2 ? 0.6 : -0.6), 0, Math.PI * 2); g.fill();
      }
      var op = bloomOpen();   // a week without a reboot: it flowers, a dozen real flowers along the vine
      for (i = 0; op && i < 12; i++) {
        var qf = 0.08 + ((i * 0.618 + 0.2) % 1) * 0.9, side = i % 2 ? 1 : -1;
        flower(x + Math.sin(qf * 9) * w * 0.12 + w * 0.06 + side * 5 * u + sway * qf, y0 - hgt * qf, (7 + (i % 3) * 1.5) * u, op, t, i);
      }
    }
    function drawParcels(t, hz) {
      if (simpleMon) { return; }   // 1.6: [pulse] secondary = "simple"
      if (!parcels || worldOff() || kind === "calm" || kind === "orbit" || kind === "noir") return;
      var n = (parcels.n || 0) + (parcels.aur || 0), news = parcels.news || [];
      if (!n && !news.length) return;
      var u = H / 1080, s = H * 0.016 * (1 + Math.min(1, n / 60) * 0.6), x0 = band[1] - (band[1] - band[0]) * 0.13, y0 = hz + H * 0.03, shown = Math.min(14, n);   // more updates, bigger parcels
      for (var i = 0; i < shown; i++) {   // a pile of parcels: one for each update (up to 14), the kernel in red, the AUR ones in the second accent
        var row = i < 5 ? 0 : i < 9 ? 1 : i < 12 ? 2 : 3, inRow = [5, 4, 3, 2][row], idx = i - [0, 5, 9, 12][row];
        var bx = x0 - (inRow * s * 1.08) / 2 + idx * s * 1.08, by = y0 - (row + 1) * s * 0.92;
        var c = i === 0 && parcels.kernel ? COL.crit : i >= shown - Math.min(parcels.aur || 0, shown) ? COL.accent2 : mix(COL.warn, COL.bg, 0.25);
        g.fillStyle = rgba(mix(COL.bg, c, 0.55), 1); g.fillRect(bx, by, s, s * 0.88);
        g.strokeStyle = rgba(c, 0.95); g.lineWidth = Math.max(1, u); g.strokeRect(bx + 0.5, by + 0.5, s - 1, s * 0.88 - 1);
        g.fillStyle = rgba(c, 0.9); g.fillRect(bx + s * 0.45, by, s * 0.1, s * 0.88);   // the tape
      }
      g.font = "600 " + Math.round(H * 0.012) + "px 'CaskaydiaMono Nerd Font', 'Cascadia Mono', monospace"; g.textAlign = "center";
      if (n) { g.fillStyle = rgba(parcels.kernel ? COL.crit : COL.fg, 0.9); g.fillText(n + (n === 1 ? " update" : " updates") + (parcels.kernel ? " · new kernel" : ""), x0, y0 + H * 0.018); }
      if (news.length) {   // Arch news that asks for manual intervention: a road sign with a blinking light
        var sx = x0 + s * 4.2, sy = y0 - s * 3.6, on = reduced || (t % 1.2) < 0.7;
        g.fillStyle = rgba(COL.fg, 0.5); g.fillRect(sx - u, sy, 2 * u, y0 - sy);
        g.beginPath(); g.moveTo(sx, sy - s * 1.3); g.lineTo(sx + s * 0.9, sy + s * 0.2); g.lineTo(sx - s * 0.9, sy + s * 0.2); g.closePath();
        g.fillStyle = rgba(COL.warn, on ? 0.95 : 0.45); g.fill();
        g.fillStyle = rgba(COL.bg, 1); g.font = "800 " + Math.round(s * 0.9) + "px sans-serif"; g.fillText("!", sx, sy - s * 0.05);
        if (!shareMode) { g.font = "600 " + Math.round(H * 0.011) + "px 'CaskaydiaMono Nerd Font', 'Cascadia Mono', monospace"; g.fillStyle = rgba(COL.warn, 0.95);
          g.fillText("news: read before you update", sx, y0 + H * 0.036); }
      }
    }
    function drawBalloons(now, hz) {
      if (simpleMon) { return; }   // 1.6: [pulse] secondary = "simple"
      // an Omarchy tip on a hot-air balloon: now and then, only when you can see the sky, never in a meeting, a
      // focus session or while you share the screen
      if (tipsList.length && tipsEvery > 0 && PRIMARY && !away && !worldOff()) {
        if (!tipNext) tipNext = now + (2 + Math.random() * 3) * 60000;
        if (now > tipNext && deskEmpty && !covered && !balloons.length && !meetingOn && !focus && !privacyOn) {
          balloons.push({ t: now, text: tipsList[Math.floor(Math.random() * tipsList.length)], dir: windX < -0.05 ? -1 : 1 });
          tipNext = now + tipsEvery * 60000 * (0.7 + Math.random() * 0.6);
        }
      }
      var keep = [];
      for (var i = 0; i < balloons.length; i++) {
        var b = balloons[i], dur = window.PULSE_BALLOON_MS || (reduced ? 20000 : 42000), u = (now - b.t) / dur; if (u > 1) continue; keep.push(b);
        var s = H / 1080, r = H * 0.04;
        var x = reduced ? W * 0.5 : (b.dir > 0 ? -W * 0.1 + u * W * 1.2 : W * 1.1 - u * W * 1.2);
        var y = hz * 0.7 + (reduced ? 0 : Math.sin(u * 7) * H * 0.012 - Math.sin(u * Math.PI) * H * 0.04);
        var fade = Math.min(1, Math.min(u, 1 - u) * 12);
        g.save(); g.globalAlpha = fade;
        // the envelope: stripes of the theme's two accents
        for (var k = 0; k < 6; k++) {
          g.beginPath(); g.ellipse(x, y, r * (1 - k / 6), r * 1.15, 0, 0, Math.PI * 2);
          g.fillStyle = rgba(k % 2 ? COL.accent2 : COL.accent, 0.95); g.fill();
        }
        g.beginPath(); g.moveTo(x - r * 0.62, y + r * 0.8); g.quadraticCurveTo(x, y + r * 1.55, x + r * 0.62, y + r * 0.8); g.closePath();
        g.fillStyle = rgba(COL.accent, 0.95); g.fill();
        var gl = g.createRadialGradient(x - r * 0.35, y - r * 0.45, 0, x, y, r * 1.2);   // a soft light on the envelope
        gl.addColorStop(0, "rgba(255,255,255,0.22)"); gl.addColorStop(1, "rgba(255,255,255,0)");
        g.fillStyle = gl; g.beginPath(); g.ellipse(x, y, r, r * 1.15, 0, 0, Math.PI * 2); g.fill();
        // the ropes and the basket, and the burner now and then
        var by = y + r * 1.75, bw = r * 0.42;
        g.strokeStyle = rgba(COL.fg, 0.7); g.lineWidth = Math.max(1, s);
        g.beginPath(); g.moveTo(x - r * 0.3, y + r * 1.1); g.lineTo(x - bw / 2, by); g.moveTo(x + r * 0.3, y + r * 1.1); g.lineTo(x + bw / 2, by); g.stroke();
        g.fillStyle = rgba(mix(COL.warn, COL.bg, 0.45), 1); g.fillRect(x - bw / 2, by, bw, r * 0.32);
        if (!reduced && (now / 1000 % 4) < 0.5) { g.fillStyle = rgba(COL.warn, 0.9); g.beginPath(); g.ellipse(x, y + r * 1.3, r * 0.06, r * 0.16, 0, 0, Math.PI * 2); g.fill(); }
        // the sign hanging under the basket: the tip
        g.font = "700 " + Math.round(H * 0.019) + "px 'CaskaydiaMono Nerd Font', 'Cascadia Mono', monospace";
        var tw = g.measureText(b.text).width + H * 0.03, sy = by + r * 0.32 + H * 0.022, sh = H * 0.034, sw = reduced ? 0 : Math.sin(now / 700) * H * 0.004;
        g.beginPath(); g.moveTo(x - bw * 0.3, by + r * 0.32); g.lineTo(x - tw * 0.35 + sw, sy); g.moveTo(x + bw * 0.3, by + r * 0.32); g.lineTo(x + tw * 0.35 + sw, sy); g.stroke();
        g.fillStyle = rgba(mix(COL.bg, COL.fg, 0.92), 0.96); g.fillRect(x - tw / 2 + sw, sy, tw, sh);
        g.strokeStyle = rgba(COL.accent, 0.9); g.strokeRect(x - tw / 2 + sw + 0.5, sy + 0.5, tw - 1, sh - 1);
        g.fillStyle = rgba(COL.bg, 1); g.textAlign = "center"; g.textBaseline = "middle"; g.fillText(b.text, x + sw, sy + sh / 2 + 1);
        g.font = "700 " + Math.round(H * 0.013) + "px 'CaskaydiaMono Nerd Font', 'Cascadia Mono', monospace";
        g.fillStyle = rgba(COL.bg, 0.85); var lw2 = g.measureText("OMARCHY TIP").width + H * 0.012;
        g.fillRect(x + sw - lw2 / 2, sy - H * 0.021, lw2, H * 0.018);
        g.fillStyle = rgba(b.warn ? COL.warn : COL.accent, 1); g.fillText(b.warn ? "OMARCHY NEWS" : "OMARCHY TIP", x + sw, sy - H * 0.011);
        g.textBaseline = "alphabetic"; g.restore();
      }
      balloons = keep;
    }
    function glowSync(now) {   // WLED: the room in the colour of the sphere (the hub sends it to your LED strips)
      if (!wledOn || !PRIMARY || now - glowAt < 2000) return;
      glowAt = now;
      var c = kind === "sphere" && (avgCol[0] + avgCol[1] + avgCol[2]) > 30 ? avgCol : COL.accent;
      try { fetch("/api/glow?token=" + encodeURIComponent(TOKEN), { method: "POST", body: JSON.stringify({ rgb: [c[0] | 0, c[1] | 0, c[2] | 0] }) }).catch(function () {}); } catch (e) {}
    }
    function drawCar(t, hz) {
      if (simpleMon) { return; }   // 1.6: [pulse] secondary = "simple"
      if (!car || car.driving || kind === "orbit" || kind === "calm") return;
      var u = H / 1080 * 1.4, x = band[0] + (band[1] - band[0]) * 0.3, y = hz + H * 0.08, L = 150 * u, Hc = 26 * u;
      var b = Math.max(0, Math.min(100, car.battery || 0)), ch = car.state === "Charging", full = car.state === "Complete";
      var col = b < 20 ? COL.crit : b < 40 ? COL.warn : COL.ok;
      // the body: a generic low sedan
      g.beginPath();
      g.moveTo(x - L * 0.5, y - Hc * 0.35); g.quadraticCurveTo(x - L * 0.5, y - Hc * 0.75, x - L * 0.36, y - Hc * 0.8);
      g.lineTo(x - L * 0.2, y - Hc * 0.85); g.quadraticCurveTo(x - L * 0.05, y - Hc * 1.45, x + L * 0.14, y - Hc * 1.4);
      g.quadraticCurveTo(x + L * 0.32, y - Hc * 1.3, x + L * 0.44, y - Hc * 0.8); g.quadraticCurveTo(x + L * 0.52, y - Hc * 0.7, x + L * 0.5, y - Hc * 0.3);
      g.lineTo(x + L * 0.5, y - Hc * 0.18); g.lineTo(x - L * 0.5, y - Hc * 0.18); g.closePath();
      g.fillStyle = rgba(mix(COL.bg, COL.accent2, 0.18), 1); g.fill();
      g.strokeStyle = rgba(COL.accent2, 0.85); g.lineWidth = Math.max(1, 1.4 * u); g.stroke();
      g.fillStyle = rgba(COL.accent, 0.35); g.beginPath();   // the glass
      g.moveTo(x - L * 0.14, y - Hc * 0.86); g.quadraticCurveTo(x, y - Hc * 1.32, x + L * 0.14, y - Hc * 1.3); g.quadraticCurveTo(x + L * 0.28, y - Hc * 1.2, x + L * 0.36, y - Hc * 0.86); g.closePath(); g.fill();
      [-0.3, 0.3].forEach(function (k) { g.beginPath(); g.arc(x + L * k, y - Hc * 0.18, Hc * 0.3, 0, Math.PI * 2); g.fillStyle = rgba(COL.bg, 1); g.fill(); g.strokeStyle = rgba(COL.fg, 0.6); g.stroke(); });
      g.fillStyle = rgba(COL.warn, 0.9); g.fillRect(x + L * 0.46, y - Hc * 0.62, L * 0.04, Hc * 0.12);    // lights
      g.fillStyle = rgba(COL.crit, 0.9); g.fillRect(x - L * 0.5, y - Hc * 0.62, L * 0.035, Hc * 0.12);
      // the light bar along the side: the battery
      var bw = L * 0.7, bx = x - bw / 2, by = y - Hc * 0.62;
      g.fillStyle = rgba(COL.bg, 0.8); g.fillRect(bx, by, bw, Hc * 0.14);
      var gl = ch ? 0.75 + 0.25 * Math.sin(t * 4) : 1;
      g.fillStyle = rgba(col, 0.9 * gl); g.fillRect(bx, by, bw * b / 100, Hc * 0.14);
      if (car.limit) { g.fillStyle = rgba(COL.fg, 0.8); g.fillRect(bx + bw * car.limit / 100, by - Hc * 0.06, Math.max(1, u), Hc * 0.26); }
      if (ch || full || car.state === "Stopped") {   // the charger and its cable
        var px = x - L * 0.72;
        g.fillStyle = rgba(mix(COL.bg, COL.fg, 0.2), 1); g.fillRect(px - 7 * u, y - 46 * u, 14 * u, 46 * u);
        g.fillStyle = rgba(ch ? COL.ok : full ? COL.accent : COL.dim || COL.fg, 0.95); g.fillRect(px - 4 * u, y - 40 * u, 8 * u, 6 * u);
        g.strokeStyle = rgba(COL.fg, 0.5); g.lineWidth = 2 * u; g.beginPath();
        g.moveTo(px, y - 28 * u); g.quadraticCurveTo(px + L * 0.12, y + 4 * u, x - L * 0.48, y - Hc * 0.5); g.stroke();
        if (ch) for (var k = 0; k < 4; k++) {   // energy flowing into the car
          var q = ((t * 0.8 + k / 4) % 1), qx = px + (x - L * 0.48 - px) * q, qy = (1 - q) * (1 - q) * (y - 28 * u) + 2 * (1 - q) * q * (y + 4 * u) + q * q * (y - Hc * 0.5);
          var qg = g.createRadialGradient(qx, qy, 0, qx, qy, 6 * u); qg.addColorStop(0, rgba(COL.ok, 0.95)); qg.addColorStop(1, rgba(COL.ok, 0));
          g.fillStyle = qg; g.fillRect(qx - 6 * u, qy - 6 * u, 12 * u, 12 * u);
        }
      }
      var fresh = Math.max(0, 1 - (t - carAt) / 5);
      if (fresh > 0) { var hg = g.createRadialGradient(x, y - Hc, 0, x, y - Hc, L * 0.7); hg.addColorStop(0, rgba(col, 0.35 * fresh)); hg.addColorStop(1, rgba(col, 0)); g.fillStyle = hg; g.fillRect(x - L * 0.7, y - Hc - L * 0.7, L * 1.4, L * 1.4); }
      if (!shareMode) {
        g.font = "600 " + Math.round(H * 0.012) + "px 'CaskaydiaMono Nerd Font', 'Cascadia Mono', monospace"; g.textAlign = "center";
        g.fillStyle = rgba(col, 0.95);
        g.fillText(Math.round(b) + "%" + (car.range != null ? " · " + Math.round(car.range) + " " + car.unit : "") + (ch ? " · ⚡" + (car.power ? " " + Math.round(car.power) + " kW" : "") : full ? " · charged" : ""), x, y + H * 0.022);
      }
    }
    // ---------------- an earthquake: the city shakes, a wave comes from where it happened
    // the wind: from the real weather (speed, gusts, direction); you look south, so a west wind blows to the left
    function windFrom(w) {
      var qw = Q.get("wind"), kmh = w ? (w.windUnit === "mph" ? 1.609 : 1) * Math.max(+w.wind || 0, (+w.gust || 0) * 0.8) : 0;
      if (qw) kmh = +qw;
      var dir = w && w.windDir != null ? +w.windDir : 270;
      windK = Math.min(1, kmh / 60);
      windX = Math.sin(dir * Math.PI / 180) * windK;   // from the west (270) -> negative -> to the left
    }
    function drawWind(t) {
      if (windK < 0.4 || Math.abs(windX) < 0.08) return;   // a breeze is not drawn; strong wind leaves gust lines
      var n = Math.round(windK * 14);
      while (gusts.length < n) gusts.push([Math.random() * W, H * (0.25 + Math.random() * 0.5), 0.5 + Math.random(), Math.random() * 6]);
      g.strokeStyle = rgba(COL.fg, 0.12 * windK); g.lineWidth = Math.max(1, H / 900);
      g.beginPath();
      for (var i = 0; i < n; i++) {
        var gs = gusts[i], len = W * 0.06 * gs[2] * windK;
        gs[0] += windX * H * 0.02 * gs[2];
        if (gs[0] < -len * 2) { gs[0] = W + len; gs[1] = H * (0.25 + Math.random() * 0.5); }
        if (gs[0] > W + len * 2) { gs[0] = -len; gs[1] = H * (0.25 + Math.random() * 0.5); }
        var y = gs[1] + Math.sin(t * 2 + gs[3]) * H * 0.006;
        g.moveTo(gs[0], y); g.quadraticCurveTo(gs[0] - Math.sign(windX) * len * 0.5, y - H * 0.004, gs[0] - Math.sign(windX) * len, y);
      }
      g.stroke();
    }
    // a tsunami message: the sea at your feet swells, a long wave crosses from where the earthquake was
    function drawTsunami(now, hz) {
      var keep = [];
      for (var i = 0; i < tsunamis.length; i++) {
        var q = tsunamis[i], age = (now - q.t) / 1000;
        if (age > 14) continue;
        keep.push(q);
        var env = Math.min(1, age / 2) * Math.min(1, (14 - age) / 3), from = (q.az - 180) / 90 < 0 ? -1 : 1;
        var crest = from < 0 ? -W * 0.2 + age / 10 * W * 1.4 : W * 1.2 - age / 10 * W * 1.4;
        var base = H - (H - hz) * (0.28 + 0.14 * env * (q.near ? 1.5 : 1));
        g.beginPath(); g.moveTo(0, H);
        for (var x = 0; x <= W; x += 8) {
          var d = (x - crest) / (W * 0.12), y = base - Math.exp(-d * d) * (H - hz) * 0.5 * env - Math.sin(x * 0.012 + age * 3) * H * 0.004 * env;
          g.lineTo(x, y);
        }
        g.lineTo(W, H); g.closePath();
        var gr = g.createLinearGradient(0, base - (H - hz) * 0.2, 0, H);
        gr.addColorStop(0, rgba(q.near ? COL.crit : COL.accent, 0.45 * env)); gr.addColorStop(1, rgba(COL.bg, 0.8 * env));
        g.fillStyle = gr; g.fill();
        g.strokeStyle = rgba(COL.fg, 0.55 * env); g.lineWidth = Math.max(1, H / 600); g.stroke();
        if (Math.floor(age * 2) % 2 === 0) {
          g.fillStyle = rgba(q.near ? COL.crit : COL.warn, 0.9 * env); g.font = "700 " + Math.round(H / 34) + "px monospace"; g.textAlign = "center";
          g.fillText("TSUNAMI", W / 2, base - (H - hz) * 0.04);
        }
      }
      tsunamis = keep;
    }
    function quakeShake(now) {
      var dx = 0, dy = 0, keep = [];
      for (var i = 0; i < quakes.length; i++) {
        var q = quakes[i], age = (now - q.t) / 1000, dur = 1.5 + q.mag * 0.5;
        if (age > dur) continue;
        keep.push(q);
        var a = Math.max(0, 1 - age / dur) * H * 0.0025 * Math.pow(1.6, q.mag - 3) * (q.near ? 2 : 0.7);
        dx += Math.sin(now * 0.09 + i) * a; dy += Math.cos(now * 0.13 + i * 2) * a * 0.6;
      }
      quakes = keep;
      return [Math.max(-H * 0.02, Math.min(H * 0.02, dx)), Math.max(-H * 0.012, Math.min(H * 0.012, dy))];
    }
    function drawQuakeWaves(now, hz) {
      for (var i = 0; i < quakes.length; i++) {
        var q = quakes[i], age = (now - q.t) / 1000, x0 = W / 2 + Math.max(-1.05, Math.min(1.05, (q.az - 180) / 90)) * W * 0.44;
        for (var k = 0; k < 3; k++) {
          var r = (age - k * 0.35) * W * 0.25; if (r <= 0) continue;
          g.strokeStyle = rgba(q.near ? COL.crit : COL.warn, Math.max(0, 0.6 - age / 5)); g.lineWidth = Math.max(1, H / 400);
          g.beginPath(); g.ellipse(x0, hz, r, r * 0.16, 0, 0, Math.PI * 2); g.stroke();
        }
      }
    }
    // ---------------- liftoff: a rocket climbs from your horizon, arcs over, leaves its trail
    function drawLiftoffs(t, hz) {
      var keep = [];
      for (var i = 0; i < liftoffs.length; i++) {
        var L = liftoffs[i], age = t - L.t;
        if (age > 16) continue;
        keep.push(L);
        var big = L.big ? 1.6 : 1, x0 = band[0] + (band[1] - band[0]) * 0.14, climb = Math.min(1, age / 15);
        var y = hz - H * 0.9 * Math.pow(climb, 1.6), x = x0 + W * 0.3 * Math.pow(climb, 2.4);
        var ang = Math.atan2(W * 0.3 * 2.4 * Math.pow(climb, 1.4), H * 0.9 * 1.6 * Math.pow(climb, 0.6) + 1e-3);
        L.trail.push([x, y]); if (L.trail.length > 140) L.trail.shift();
        g.globalCompositeOperation = "lighter";
        for (var k = 1; k < L.trail.length; k++) {   // the trail, warm near the engine, then smoke
          var u = k / L.trail.length;
          g.strokeStyle = rgba(u > 0.85 ? COL.warn : mix(COL.fg, COL.accent2, 0.4), (u > 0.85 ? 0.8 : 0.25) * u);
          g.lineWidth = Math.max(1, H / 400) * (1 + (1 - u) * 5) * big;
          g.beginPath(); g.moveTo(L.trail[k - 1][0], L.trail[k - 1][1]); g.lineTo(L.trail[k][0], L.trail[k][1]); g.stroke();
        }
        if (age < 1.2) { var fl = g.createRadialGradient(x0, hz, 0, x0, hz, H * 0.15 * big); fl.addColorStop(0, rgba(COL.warn, 0.7 * (1 - age / 1.2))); fl.addColorStop(1, rgba(COL.warn, 0)); g.fillStyle = fl; g.fillRect(x0 - H * 0.15 * big, hz - H * 0.15 * big, H * 0.3 * big, H * 0.3 * big); }
        var fg2 = g.createRadialGradient(x, y + H * 0.02 * big, 0, x, y + H * 0.02 * big, H * 0.045 * big);   // the flame
        fg2.addColorStop(0, rgba(COL.fg, 0.95)); fg2.addColorStop(0.3, rgba(COL.warn, 0.8)); fg2.addColorStop(1, rgba(COL.warn, 0));
        g.fillStyle = fg2; g.fillRect(x - H * 0.045 * big, y - H * 0.025 * big, H * 0.09 * big, H * 0.09 * big);
        g.globalCompositeOperation = "source-over";
        g.save(); g.translate(x, y); g.rotate(ang);
        var rw = H * 0.006 * big, rh = H * 0.05 * big;
        g.fillStyle = rgba(mix(COL.fg, COL.bg, 0.1), 1); g.fillRect(-rw / 2, -rh, rw, rh);
        g.beginPath(); g.moveTo(-rw / 2, -rh); g.lineTo(0, -rh - rw * 1.6); g.lineTo(rw / 2, -rh); g.fill();
        g.restore();
        if (age < 6) { g.font = "600 " + Math.round(H * 0.016) + "px 'CaskaydiaMono Nerd Font', 'Cascadia Mono', monospace"; g.textAlign = "left"; g.fillStyle = rgba(COL.fg, 0.9 * (1 - age / 6)); g.fillText(L.name, x + H * 0.03, y); }
      }
      liftoffs = keep;
    }
    // ================= 1.4: the real sky (Moon, planets, stars, constellations, meteor showers), Starlink, clouds,
    // storms, the air, the soundscape. Every position comes from the hub, computed for where you are, right now.
    var night = null, starlink = { sats: [] }, storms = null, air = null, soundCfg = null, clouds = [], pollen = [];
    var bolts = [], nextBolt = 0, lastMeteor = 0;
    // where a point of your sky is on the screen: you look south, east on the left; the low sky gets more room
    function skyXY(az, el, hz) {
      if (el < -1) return null;
      var front = az >= 75 && az <= 285;
      if (!front && el < 62) return null;
      var x = front ? W / 2 + (az - 180) / 90 * W * 0.44 : W / 2 - Math.sin(az * Math.PI / 180) * W * 0.44 * (90 - el) / 28;
      return [x, hz * 0.93 - Math.sqrt(Math.max(0, el) / 90) * hz * 0.85];
    }
    // how dark your sky is: 0 by day, 1 in the night (the Sun more than 12° under the horizon), less with clouds
    // 1.5: the holidays. Christmas lights from 8 December to 6 January, a tree, snow when it is really cold;
    // the New Year's countdown on your clock and fireworks at midnight
    // ---------------- 1.5 beta: Pulse Net, your network as part of the city (read-only)
    var pnet = null, netBolts = [], term = [], noc = null, netPos = {}, wifiPos = {}, truckX = {};
    function azX(az) { return W / 2 + Math.max(-1.08, Math.min(1.08, ((az - 180 + 540) % 360 - 180) / 90)) * W * 0.44; }
    function drawNet(now, t, hz) {
      if (simpleMon) { return; }   // 1.6: [pulse] secondary = "simple"
      if (!pnet || worldOff()) return;
      var floor = H - hz, nowS = Date.now() / 1000;
      netPos = {};
      // the firewall: a wall along the horizon; blocked attacks are arrows from where they come from, IPS events meteors
      var fw = pnet.fw || {};
      if (fw.events && (fw.events.length || fw.vpn != null)) {
        var wh = H * 0.012;
        g.fillStyle = rgba(mix(COL.bg, COL.fg, 0.22), 0.9); g.fillRect(0, hz - wh, W, wh);
        g.fillStyle = rgba(mix(COL.bg, COL.fg, 0.3), 0.9);
        for (var x = 0; x < W; x += H * 0.02) g.fillRect(x, hz - wh - H * 0.006, H * 0.01, H * 0.006);
        if (fw.vpn) {   // the gate: VPN users coming in
          var gx = W * 0.5, gw = H * 0.03;
          g.fillStyle = rgba(COL.ok, 0.25 + 0.15 * Math.sin(t * 2)); g.fillRect(gx - gw / 2, hz - wh - H * 0.02, gw, wh + H * 0.02);
          g.font = Math.round(H * 0.011) + "px monospace"; g.textAlign = "center"; g.fillStyle = rgba(COL.ok, 0.9);
          g.fillText("vpn " + fw.vpn, gx, hz - wh - H * 0.026); g.textAlign = "start";
        }
        fw.events.forEach(function (e) {
          var age = nowS - e.t, dur = e.src === "ips" ? 2.5 : 1.6; if (age < 0 || age > dur + 0.6) return;
          var k = Math.min(1, age / dur), tx = azX(e.az), sx = tx + (e.az < 180 ? -1 : 1) * W * 0.05, sy = hz - H * (e.src === "ips" ? 0.6 : 0.3);
          var px = sx + (tx - sx) * k, py = sy + (hz - wh - sy) * k;
          if (k < 1) {
            var col = e.src === "ips" ? COL.crit : e.level === "warn" ? COL.warn : COL.fg;
            g.strokeStyle = rgba(col, e.src === "ips" ? 0.9 : 0.6); g.lineWidth = Math.max(1, H / (e.src === "ips" ? 400 : 900));
            g.beginPath(); g.moveTo(px - (tx - sx) * 0.08, py - (hz - sy) * 0.08); g.lineTo(px, py); g.stroke();
            if (e.src === "ips") { var mg = g.createRadialGradient(px, py, 0, px, py, H * 0.02); mg.addColorStop(0, rgba(COL.warn, 0.9)); mg.addColorStop(1, rgba(COL.crit, 0)); g.fillStyle = mg; g.fillRect(px - H * 0.02, py - H * 0.02, H * 0.04, H * 0.04); }
          } else {   // it breaks on the wall
            var sp = (age - dur) / 0.6; g.fillStyle = rgba(COL.fg, 0.6 * (1 - sp));
            for (var j = 0; j < 4; j++) g.fillRect(tx + Math.cos(j * 1.6) * sp * H * 0.015, hz - wh - Math.abs(Math.sin(j * 1.6)) * sp * H * 0.015, 2, 2);
          }
        });
      }
      // the network district: a building for each switch, a window for each port
      var sws = pnet.switches || [], rowY = hz + floor * 0.2;
      if (sws.length) {
        var dz = window.PULSE_NETZOOM || 1;   // the demo video looks closer (1 = the wallpaper)
        var span = W * 0.25 * dz, bwid = Math.min(W * 0.085 * dz, span / sws.length * 0.82), x0 = dz > 1 ? W / 2 - span / 2 : W * 0.26;
        sws.forEach(function (s, i) {
          var x = x0 + (i + 0.5) * span / sws.length, cols = 12, rows = Math.max(1, Math.ceil(s.ports.length / cols)), cw = bwid / cols;
          var bh = rows * cw * 2 + H * 0.016, top = rowY - bh;
          g.fillStyle = rgba(mix(COL.bg, COL.accent, 0.14), 0.95); g.fillRect(x - bwid / 2, top, bwid, bh);
          g.strokeStyle = rgba(s.up ? COL.accent : COL.crit, s.up ? 0.6 : (t * 2 % 1 < 0.5 ? 1 : 0.3)); g.lineWidth = Math.max(1, H / 800); g.strokeRect(x - bwid / 2, top, bwid, bh);
          s.ports.forEach(function (p, k) {
            var px = x - bwid / 2 + (k % cols) * cw + cw * 0.2, py = top + H * 0.008 + Math.floor(k / cols) * cw * 2;
            g.fillStyle = p.err ? rgba(COL.crit, reduced || t * 3 % 1 < 0.6 ? 1 : 0.3) : p.up ? rgba(p.jam ? COL.warn : COL.accent, 0.35 + 0.65 * (p.util || 0)) : rgba(COL.bg, 0.9);
            g.fillRect(px, py, cw * 0.6, cw * 1.3);
          });
          if (s.name && !shareMode) { g.font = Math.round(H * 0.011 * dz) + "px monospace"; g.textAlign = "center"; g.fillStyle = rgba(COL.fg, 0.7); g.fillText(s.name, x, top - H * 0.006); g.textAlign = "start"; }
          netPos[s.name || "#" + i] = [x, top, bwid, rowY];
        });
        sws.forEach(function (s, i) {   // the roads between neighbours (LLDP/CDP); a busy uplink is a traffic jam
          var a = netPos[s.name || "#" + i]; if (!a) return;
          s.ports.forEach(function (p) {
            var b = p.peer && netPos[p.peer]; if (!b || b === a) return;
            var y = rowY + H * 0.012, jam = p.jam;
            g.strokeStyle = rgba(jam ? COL.warn : COL.accent, 0.35); g.lineWidth = Math.max(1, H / 600);
            g.beginPath(); g.moveTo(a[0], y); g.lineTo(b[0], y); g.stroke();
            var n = jam ? 10 : 3, sp = jam ? 0.05 : 0.4 + (p.util || 0);
            for (var k = 0; k < n; k++) { var u = reduced ? k / n : ((t * sp + k / n) % 1); g.fillStyle = rgba(jam ? COL.warn : COL.fg, 0.85); g.fillRect(a[0] + (b[0] - a[0]) * u, y - 1.5, 3, 3); }
          });
        });
      }
      // the datacenter: a tower for each host, a floor for each VM; a migration is a lift; maintenance has scaffolding
      var hosts = (pnet.dc && pnet.dc.hosts) || [];
      if (hosts.length) {
        var dspan = W * 0.2, tw = Math.min(W * 0.03, dspan / hosts.length * 0.6), fh = H * 0.007;
        hosts.forEach(function (h, i) {
          var x = W * 0.54 + (i + 0.5) * dspan / hosts.length, n = Math.min(24, h.vms.length), top = rowY - n * fh - H * 0.01;
          g.fillStyle = rgba(mix(COL.bg, COL.accent2, 0.15), 0.95); g.fillRect(x - tw / 2, top, tw, rowY - top);
          h.vms.slice(0, n).forEach(function (v, k) { g.fillStyle = rgba(v.up ? COL.accent2 : mix(COL.bg, COL.fg, 0.15), v.up ? 0.75 : 0.6); g.fillRect(x - tw * 0.4, rowY - (k + 1) * fh - H * 0.004, tw * 0.8, fh * 0.7); });
          g.strokeStyle = rgba(h.up ? COL.accent2 : COL.crit, 0.7); g.lineWidth = Math.max(1, H / 900); g.strokeRect(x - tw / 2, top, tw, rowY - top);
          if (h.maint) { g.strokeStyle = rgba(COL.warn, 0.6); g.beginPath(); for (var k2 = 0; k2 < 4; k2++) { var yy = top + (rowY - top) * k2 / 4; g.moveTo(x - tw * 0.7, yy); g.lineTo(x + tw * 0.7, yy + (rowY - top) / 4); } g.stroke(); }
          if (h.name && !shareMode) { g.font = Math.round(H * 0.01) + "px monospace"; g.textAlign = "center"; g.fillStyle = rgba(COL.fg, 0.65); g.fillText(h.name, x, top - H * 0.005); g.textAlign = "start"; }
          netPos[h.name || "h" + i] = [x, top, tw, rowY];
        });
        (pnet.dc.moves || []).forEach(function (m) {   // the lift: a lit floor travelling from one tower to the other
          var a = netPos[m.from], b = netPos[m.to], age = nowS - m.t; if (!a || !b || age > 6) return;
          var u = Math.min(1, age / 4), x = a[0] + (b[0] - a[0]) * u, y = Math.min(a[1], b[1]) - H * 0.03 * Math.sin(u * Math.PI);
          g.fillStyle = rgba(COL.warn, 0.95); g.fillRect(x - tw * 0.4, y, tw * 0.8, fh);
        });
      }
      // Wi-Fi: an antenna for each access point, a person for each client, nearer with a stronger signal
      var wifi = pnet.wifi || {}, aps = wifi.aps || [];
      if (aps.length) {
        var wy = hz + floor * 0.52, apX = {};
        aps.forEach(function (a, i) {
          var x = W * 0.32 + (i + 0.5) * W * 0.36 / aps.length; apX[a] = x;
          g.strokeStyle = rgba(COL.accent, 0.8); g.lineWidth = Math.max(1.5, H / 700); g.beginPath(); g.moveTo(x, wy); g.lineTo(x, wy - H * 0.035); g.stroke();
          for (var r = 0; r < 3; r++) { var rr = ((reduced ? r / 3 : (t * 0.5 + r / 3) % 1)) * H * 0.05; g.strokeStyle = rgba(COL.accent, 0.4 * (1 - rr / (H * 0.05))); g.beginPath(); g.arc(x, wy - H * 0.035, rr, Math.PI * 1.15, Math.PI * 1.85); g.stroke(); }
          if (a && !shareMode) { g.font = Math.round(H * 0.01) + "px monospace"; g.textAlign = "center"; g.fillStyle = rgba(COL.fg, 0.6); g.fillText(a, x, wy + H * 0.016); g.textAlign = "start"; }
        });
        (wifi.clients || []).forEach(function (c) {
          var ax = apX[c.ap]; if (ax == null) return;
          var hsh = parseInt(c.id.slice(0, 6), 16) || 0, ang = (hsh % 360) * Math.PI / 180, sig = c.signal == null ? -60 : c.signal;
          var d = W * (0.012 + 0.05 * Math.max(0, Math.min(1, (-sig - 35) / 55)));
          var tx = ax + Math.cos(ang) * d, ty = wy - H * 0.005 + Math.sin(ang) * d * 0.25, p = wifiPos[c.id] || (wifiPos[c.id] = [tx, ty]);
          p[0] += (tx - p[0]) * (reduced ? 1 : 0.03); p[1] += (ty - p[1]) * (reduced ? 1 : 0.03);   // roaming: it walks to its new antenna
          g.fillStyle = rgba(COL.fg, 0.75); g.fillRect(p[0] - 1, p[1] - H * 0.012, 2, H * 0.01); g.beginPath(); g.arc(p[0], p[1] - H * 0.015, H * 0.003, 0, Math.PI * 2); g.fill();
        });
      }
      // backups (1.6: bigger, with a depot and a vault): each job is a truck. Running: it drives from the city to the
      // vault. Done: it drives back home and parks with a green light. Failed or late: it stops halfway, hazard
      // lights and smoke, until the next good run.
      var bks = (pnet.backups || []).slice(0, 4), by = hz + floor * 0.6;
      if (bks.length) {
        var x0 = band[0] + (band[1] - band[0]) * 0.12, x1 = band[1] - (band[1] - band[0]) * 0.12, u2 = H / 1080;
        g.fillStyle = rgba(mix(COL.bg, COL.fg, 0.12), 0.9); g.fillRect(x0 - H * 0.05, by + H * 0.004, x1 - x0 + H * 0.1, H * 0.01 + bks.length * H * 0.022);   // the road
        [[x0, "BACKUP"], [x1, "VAULT"]].forEach(function (d, k) {   // the depot and the vault
          var dw = H * 0.05, dh = H * 0.035, dx = d[0] + (k ? H * 0.012 : -H * 0.062);
          g.fillStyle = rgba(mix(COL.bg, COL.fg, 0.2), 1); g.fillRect(dx, by - dh, dw, dh);
          g.strokeStyle = rgba(k ? COL.accent2 : COL.accent, 0.8); g.lineWidth = Math.max(1, u2); g.strokeRect(dx + 0.5, by - dh + 0.5, dw - 1, dh - 1);
          if (!shareMode) { g.font = "600 " + Math.round(H * 0.009) + "px monospace"; g.textAlign = "center"; g.fillStyle = rgba(k ? COL.accent2 : COL.accent, 0.9); g.fillText(d[1], dx + dw / 2, by - dh - H * 0.004); }
        });
        bks.forEach(function (b, i) {
          var lane = by + H * 0.016 + i * H * 0.022, L2 = H * 0.05, x, dir = 1, ago = b.t ? nowS - b.t : 1e9;
          if (b.state === "running") { var tk = truckX[b.name] = (truckX[b.name] == null ? 0 : truckX[b.name]) + (reduced ? 0 : 0.0012); if (tk > 1) truckX[b.name] = 0; x = x0 + (x1 - x0) * (reduced ? 0.5 : truckX[b.name]); }
          else if (b.state === "ok") { var back = Math.min(1, ago / 900); x = x1 + (x0 - x1) * (reduced ? 1 : back); dir = back < 1 ? -1 : 1; truckX[b.name] = null; }
          else if (b.state === "failed" || b.state === "late") x = x0 + (x1 - x0) * 0.5;
          else x = x0;
          var col = b.state === "failed" || b.state === "late" ? COL.crit : b.state === "ok" ? COL.ok : COL.accent;
          g.save(); g.translate(x, lane); g.scale(dir, 1);
          g.fillStyle = rgba(mix(COL.bg, COL.fg, 0.55), 1); g.fillRect(-L2 * 0.5, -L2 * 0.42, L2 * 0.68, L2 * 0.36);   // the box
          g.fillStyle = rgba(col, 0.95); g.fillRect(-L2 * 0.46, -L2 * 0.3, L2 * 0.6, L2 * 0.08);                      // its stripe: the state
          g.fillStyle = rgba(mix(COL.bg, COL.fg, 0.4), 1); g.fillRect(L2 * 0.2, -L2 * 0.3, L2 * 0.26, L2 * 0.24);     // the cab
          g.fillStyle = rgba(COL.accent, 0.5); g.fillRect(L2 * 0.32, -L2 * 0.27, L2 * 0.11, L2 * 0.1);                 // its window
          g.fillStyle = rgba(COL.bg, 1); [-0.35, 0.05, 0.34].forEach(function (k) { g.beginPath(); g.arc(L2 * k, -L2 * 0.05, L2 * 0.07, 0, Math.PI * 2); g.fill(); });
          if (b.state === "running" || dir < 0) { g.fillStyle = rgba(COL.warn, 0.9); g.fillRect(L2 * 0.45, -L2 * 0.12, L2 * 0.03, L2 * 0.04); }   // headlights
          if (b.state === "failed" || b.state === "late") {
            var on = reduced || t * 2 % 1 < 0.5;
            g.fillStyle = rgba(COL.warn, on ? 1 : 0.15); g.fillRect(-L2 * 0.52, -L2 * 0.16, L2 * 0.04, L2 * 0.04); g.fillRect(L2 * 0.44, -L2 * 0.16, L2 * 0.04, L2 * 0.04);
          }
          g.restore();
          if ((b.state === "failed" || b.state === "late") && !reduced) for (var sm = 0; sm < 5; sm++) {   // smoke from the engine
            var q = (t * 0.25 + sm / 5) % 1, px = x + L2 * 0.3 + Math.sin(q * 6 + sm) * L2 * 0.12 + q * L2 * 0.3 * (windX || 0.3), py = lane - L2 * 0.35 - q * H * 0.05;
            g.fillStyle = rgba(mix(COL.fg, COL.bg, 0.4), 0.28 * (1 - q)); g.beginPath(); g.arc(px, py, L2 * (0.08 + q * 0.18), 0, Math.PI * 2); g.fill();
          }
          if (!shareMode && b.name) { g.font = Math.round(H * 0.01) + "px monospace"; g.textAlign = "left"; g.fillStyle = rgba(col, 0.85); g.fillText(b.name + " · " + b.state, x + L2 * 0.55, lane - L2 * 0.12); }
        });
        g.textAlign = "left";
      }
      // intruders: robots with red eyes walking to the server they hit, bigger when more serious
      (pnet.intruders || []).forEach(function (a, i) {
        var tgt = netPos[a.target] || [W * 0.5, 0, 0, hz + floor * 0.2], age = Math.max(0, nowS - a.t), u = Math.min(1, age / 40);
        var sx = i % 2 ? W * 0.24 : W * 0.76, x = sx + (tgt[0] - sx) * (reduced ? 1 : u), y = tgt[3] + H * 0.02, s2 = H * (a.level === "crit" ? 0.03 : 0.02);
        g.fillStyle = rgba(mix(COL.bg, COL.fg, 0.4), 0.95); g.fillRect(x - s2 * 0.3, y - s2, s2 * 0.6, s2 * 0.7); g.fillRect(x - s2 * 0.22, y - s2 * 1.35, s2 * 0.44, s2 * 0.33);
        g.fillStyle = rgba(COL.crit, 1); g.fillRect(x - s2 * 0.15, y - s2 * 1.25, s2 * 0.1, s2 * 0.08); g.fillRect(x + s2 * 0.05, y - s2 * 1.25, s2 * 0.1, s2 * 0.08);
        g.fillStyle = rgba(mix(COL.bg, COL.fg, 0.4), 0.95); var st = reduced ? 0 : Math.sin(t * 8 + i) * s2 * 0.1; g.fillRect(x - s2 * 0.2 + st, y - s2 * 0.3, s2 * 0.12, s2 * 0.3); g.fillRect(x + s2 * 0.08 - st, y - s2 * 0.3, s2 * 0.12, s2 * 0.3);
      });
    }
    function drawBoltsNet(t, hz) {   // syslog and SNMP traps: a bolt on the building of that device, right now
      var nowS = Date.now() / 1000;
      netBolts.forEach(function (b) {
        var age = nowS - b.t; if (age > 1.2 || age < 0) return;
        var p = netPos[b.target] || [W * (0.3 + 0.4 * ((b.t * 7) % 1)), hz, 0, hz];
        g.strokeStyle = rgba(b.sev <= 2 ? COL.crit : COL.fg, 1 - age / 1.2); g.lineWidth = Math.max(2, H / 400); g.beginPath();
        var x = p[0], y = 0; g.moveTo(x, y);
        for (var k = 1; k <= 8; k++) { y = p[1] * k / 8; x = p[0] + (k < 8 ? Math.sin(b.t * 13 + k * 7) * H * 0.02 : 0); g.lineTo(x, y); }
        g.stroke(); if (age < 0.15) { g.fillStyle = rgba(COL.fg, 0.08); g.fillRect(0, 0, W, H); }
      });
    }
    function drawTerm(hz) {   // what you type in omarchy-pulse term
      if (!term.length || shareMode) return;
      var lines = term.slice(-8), lh = H * 0.016, bw = W * 0.34, bh = lines.length * lh + H * 0.016, x = W / 2 - bw / 2, y = H * 0.94 - bh;
      g.fillStyle = rgba([0, 10, 4], 0.78); g.fillRect(x, y, bw, bh); g.strokeStyle = rgba([60, 255, 140], 0.5); g.lineWidth = 1; g.strokeRect(x, y, bw, bh);
      g.font = Math.round(H * 0.012) + "px monospace"; g.fillStyle = rgba([90, 255, 150], 0.95);
      lines.forEach(function (l, i) { g.fillText(String(l.text).slice(0, 70), x + H * 0.01, y + H * 0.01 + (i + 0.8) * lh); });
    }
    // the hacker scene: the city in phosphor green, a radar sweeping your network, every device a blip
    var hackerStyle = "phosphor", flyers = [];
    // 1.5: scene = "noir", a futuristic megacity at night: towers in layers with thousands of windows, searchlights in
    // the clouds, a hologram billboard, neon signs, cars in the air, rain that never stops, a wet street. Original art.
    var noirTops = [], noirBoard = null, noirSigns = [];
    function paintMegacity(b, hz) {
      var r = rnd(4242), sky = b.createLinearGradient(0, 0, 0, hz);
      sky.addColorStop(0, "rgb(6,9,18)"); sky.addColorStop(0.55, "rgb(22,30,48)"); sky.addColorStop(1, "rgb(92,64,48)");
      b.fillStyle = sky; b.fillRect(0, 0, W, hz + 1);
      var fl = b.createLinearGradient(0, hz, 0, H); fl.addColorStop(0, "rgb(24,22,30)"); fl.addColorStop(1, "rgb(6,6,10)");
      b.fillStyle = fl; b.fillRect(0, hz, W, H - hz);
      noirTops = []; noirSigns = [];
      [[70, 0.008, 0.026, 0.12, 0.42, [44, 52, 70], 0.06], [40, 0.018, 0.05, 0.2, 0.58, [26, 30, 44], 0.2], [15, 0.035, 0.085, 0.32, 0.74, [12, 13, 20], 0.12]].forEach(function (L, li) {
        for (var i = 0; i < L[0]; i++) {
          var w = W * (L[1] + r() * (L[2] - L[1])), x = r() * (W + w) - w, h = H * (L[3] + r() * (L[4] - L[3])) * (0.75 + 0.25 * Math.sin(x / W * Math.PI)), top = hz - h;
          b.fillStyle = "rgb(" + L[5].join(",") + ")"; b.fillRect(x, top, w, h);
          if (li && r() < 0.5) { b.fillRect(x + w * 0.2, top - h * 0.08, w * 0.6, h * 0.08); top -= h * 0.08; }   // a setback
          if (r() < 0.4) { b.fillRect(x + w / 2 - 1, top - H * 0.03, 2, H * 0.03); noirTops.push([x + w / 2, top - H * 0.03]); }   // an antenna
          var cw = Math.max(2, w / 9), ch = Math.max(2, H / 260);
          for (var yy = top + ch * 3; yy < hz - ch; yy += ch * 2.6) for (var xx = x + cw; xx < x + w - cw; xx += cw * 1.8) {
            if (r() < L[6]) { b.fillStyle = r() < 0.7 ? "rgba(255,190,110," + (0.35 + r() * 0.5) + ")" : "rgba(120,220,255," + (0.3 + r() * 0.5) + ")"; b.fillRect(xx, yy, cw * 0.8, ch); }
          }
          if (li === 2 && r() < 0.6) { b.fillStyle = "rgba(255,70,140,0.35)"; b.fillRect(x + w * 0.1, top + h * 0.1, 2, h * 0.8); }   // a light strip
          if (li === 1 && noirSigns.length < 5 && x > W * 0.24 && x < W * 0.74 && Math.abs(x + w * 0.5 - W / 2) > H * 0.24 && r() < 0.35) noirSigns.push([x + w * 0.5, Math.max(top + h * 0.15, hz - H * 0.3)]);
        }
      });
      // the central tower that carries the big screen: where the sphere is in the other scenes
      noirBoard = { x: W / 2, y: hz - H * 0.27, w: H * 0.31, h: H * 0.22 };   // under the centre panels
      b.fillStyle = "rgb(10,11,17)"; b.fillRect(W / 2 - H * 0.12, hz - H * 0.58, H * 0.24, H * 0.58);
      b.fillRect(W / 2 - H * 0.03, hz - H * 0.66, H * 0.06, H * 0.08); noirTops.push([W / 2, hz - H * 0.66]);
    }
    function drawNoirCity(t, hz) {
      // searchlights sweeping the clouds
      g.globalCompositeOperation = "lighter";
      [0.3, 0.55, 0.78].forEach(function (u, i) {
        var a = -Math.PI / 2 + (reduced ? (i - 1) * 0.3 : Math.sin(t * (0.13 + i * 0.05) + i * 2) * 0.55), x = W * u, len = H * 0.9;
        var gb = g.createLinearGradient(x, hz, x + Math.cos(a) * len, hz + Math.sin(a) * len);
        gb.addColorStop(0, "rgba(200,220,255,0.10)"); gb.addColorStop(1, "rgba(200,220,255,0)");
        g.fillStyle = gb; g.beginPath(); g.moveTo(x, hz);
        g.lineTo(x + Math.cos(a - 0.04) * len, hz + Math.sin(a - 0.04) * len); g.lineTo(x + Math.cos(a + 0.04) * len, hz + Math.sin(a + 0.04) * len); g.closePath(); g.fill();
      });
      g.globalCompositeOperation = "source-over";
      noirTops.forEach(function (p, i) { if (reduced || ((t + i * 0.37) % 2) < 0.25) { g.fillStyle = "rgba(255,40,40,0.95)"; g.fillRect(p[0] - 1.5, p[1] - 1.5, 3, 3); } });
      // the big screen on the central tower: it shows what the sphere shows in the other scenes (the clock, the
      // weather, the agent's face when it works, listens or talks, the door camera, alerts, countdowns), as a hologram
      var bd = noirBoard, fl2 = reduced ? 0.9 : 0.8 + 0.2 * Math.abs(Math.sin(t * 7)) * (((t * 0.5) % 3) < 2.9 ? 1 : 0.3);
      if (bd) {
        var prog = sphereProgram(t);
        paintTex(prog, t, TW / 2);
        g.globalAlpha = 0.88 * fl2;
        try { g.drawImage(tex, (TW / 2 - TW * 0.3) * TX, 0, TW * 0.6 * TX, TH * TX, bd.x - bd.w / 2, bd.y, bd.w, bd.h * 0.86); } catch (e) {}
        g.globalAlpha = 1;
        g.strokeStyle = "rgba(120,230,255," + 0.6 * fl2 + ")"; g.lineWidth = Math.max(1, H / 700); g.strokeRect(bd.x - bd.w / 2, bd.y, bd.w, bd.h * 0.86);
        var lg = tintedLogo([255, 120, 200]); if (lg) { var ls = bd.h * 0.1; g.drawImage(lg, bd.x - bd.w / 2 + ls * 0.3, bd.y + bd.h * 0.885, ls, ls); }
        g.font = "bold " + Math.round(bd.h * 0.08) + "px monospace"; g.textAlign = "center"; g.fillStyle = "rgba(255,120,200," + 0.9 * fl2 + ")";
        g.fillText("OMARCHY", bd.x, bd.y + bd.h * 0.97); g.textAlign = "start";
        g.fillStyle = "rgba(0,0,0,0.18)"; for (var sy = bd.y; sy < bd.y + bd.h; sy += 3) g.fillRect(bd.x - bd.w / 2, sy, bd.w, 1);
        var rf = g.createLinearGradient(0, hz, 0, hz + H * 0.2); rf.addColorStop(0, "rgba(120,200,255," + 0.12 * fl2 + ")"); rf.addColorStop(1, "rgba(120,200,255,0)");
        g.fillStyle = rf; g.fillRect(bd.x - bd.w / 2, hz, bd.w, H * 0.2);   // its reflection on the wet street
      }
      drawNoir(t, hz, true);
      // the wet street: the city's lights stretched on the asphalt
      for (var k = 0; k < 26; k++) {
        var x = (k * 211.7) % W, c = k % 3 ? "255,170,90" : k % 2 ? "120,220,255" : "255,80,160";
        var sg = g.createLinearGradient(0, hz, 0, hz + H * 0.16); sg.addColorStop(0, "rgba(" + c + ",0.14)"); sg.addColorStop(1, "rgba(" + c + ",0)");
        g.fillStyle = sg; g.fillRect(x, hz, 2 + (k % 4), H * 0.16);
      }
    }
    function drawNoir(t, hz, scene) {   // the neo-noir variant: neon rain, an amber haze, signs on the towers, cars in the air
      if (!scene) { g.globalCompositeOperation = "multiply"; g.fillStyle = "rgb(120,130,175)"; g.fillRect(0, 0, W, H); g.globalCompositeOperation = "source-over"; }
      var hz2 = g.createLinearGradient(0, hz - H * 0.25, 0, hz + H * 0.05);
      hz2.addColorStop(0, "rgba(255,150,60,0)"); hz2.addColorStop(0.7, "rgba(255,150,60,0.16)"); hz2.addColorStop(1, "rgba(255,150,60,0)");
      g.fillStyle = hz2; g.fillRect(0, hz - H * 0.25, W, H * 0.3);
      var signs = [["OMARCHY", COL.accent2], ["PULSE", [80, 230, 255]], ["ARCH", [255, 90, 160]], ["NIGHT SHIFT", [255, 190, 80]]];
      signs.forEach(function (sg, i) {   // vertical neon signs, our own words
        var x = scene && noirSigns[i] ? noirSigns[i][0] : W * (0.29 + i * 0.14), y = scene && noirSigns[i] ? noirSigns[i][1] : hz - H * (0.33 - (i % 2) * 0.06), on = reduced || ((t * 1.3 + i * 0.7) % 4) > 0.15;
        g.font = "bold " + Math.round(H * 0.017) + "px monospace"; g.textAlign = "center";
        g.shadowColor = rgba(sg[1], 0.9); g.shadowBlur = on ? H * 0.015 : 0; g.fillStyle = rgba(sg[1], on ? 0.9 : 0.25);
        sg[0].split("").forEach(function (ch, k) { g.fillText(ch, x, y + k * H * 0.02); });
        g.shadowBlur = 0; g.textAlign = "start";
      });
      if (!flyers.length) for (var f = 0; f < 4; f++) flyers.push({ x: Math.random() * W, y: hz - H * (0.2 + Math.random() * 0.25), v: (Math.random() < 0.5 ? -1 : 1) * W * (0.0006 + Math.random() * 0.0008) });
      flyers.forEach(function (fl) {
        if (!reduced) { fl.x += fl.v; if (fl.x < -W * 0.05) fl.x = W * 1.05; if (fl.x > W * 1.05) fl.x = -W * 0.05; }
        var d = fl.v > 0 ? 1 : -1, s2 = H * 0.012;
        g.fillStyle = "rgba(20,22,30,0.95)"; g.beginPath(); g.ellipse(fl.x, fl.y, s2 * 1.6, s2 * 0.45, 0, 0, Math.PI * 2); g.fill();
        var lb = g.createLinearGradient(fl.x + d * s2 * 1.5, 0, fl.x + d * s2 * 9, 0);
        lb.addColorStop(0, "rgba(255,240,200,0.5)"); lb.addColorStop(1, "rgba(255,240,200,0)");
        g.fillStyle = lb; g.beginPath(); g.moveTo(fl.x + d * s2 * 1.5, fl.y); g.lineTo(fl.x + d * s2 * 9, fl.y - s2); g.lineTo(fl.x + d * s2 * 9, fl.y + s2 * 1.2); g.closePath(); g.fill();
        g.fillStyle = rgba(COL.crit, 0.9); g.fillRect(fl.x - d * s2 * 1.5, fl.y - 1, 2, 2);
      });
      g.strokeStyle = "rgba(170,210,255,0.22)"; g.lineWidth = 1; g.beginPath();   // the rain that never stops
      for (var k = 0; k < 220; k++) { var rx = (k * 97.3 + (reduced ? 0 : t * W * 0.02 * (1 + (k % 3)))) % W, ry = (k * 53.7 + (reduced ? 0 : t * H * 0.9 * (1 + (k % 4) * 0.2))) % H; g.moveTo(rx, ry); g.lineTo(rx - H * 0.003, ry + H * 0.025); }
      g.stroke();
    }
    function drawHacker(t, hz, cx) {
      var noir = hackerStyle === "noir";
      if (noir) drawNoir(t, hz);
      else { g.globalCompositeOperation = "color"; g.fillStyle = "rgb(40,255,140)"; g.fillRect(0, 0, W, H); g.globalCompositeOperation = "source-over";
        g.fillStyle = "rgba(0,20,8,0.35)"; g.fillRect(0, 0, W, H); }
      var R = H * 0.23, cy = hz - R * 0.95, sw = reduced ? 0.8 : t * 0.9, green = noir ? [90, 220, 255] : [60, 255, 140];
      if (noir) g.globalAlpha = 0.75;   // the radar as a hologram over the city
      g.strokeStyle = rgba(green, 0.35); g.lineWidth = 1;
      for (var k = 1; k <= 4; k++) { g.beginPath(); g.arc(cx, cy, R * k / 4, 0, Math.PI * 2); g.stroke(); }
      g.beginPath(); g.moveTo(cx - R, cy); g.lineTo(cx + R, cy); g.moveTo(cx, cy - R); g.lineTo(cx, cy + R); g.stroke();
      for (k = 0; k < 24; k++) { var a = sw - k * 0.03; g.strokeStyle = rgba(green, 0.5 * (1 - k / 24)); g.beginPath(); g.moveTo(cx, cy); g.lineTo(cx + Math.cos(a) * R, cy + Math.sin(a) * R); g.stroke(); }
      var dev = [];
      lan.forEach(function (d) { dev.push({ n: d.name || "", r: 0.5 + 0.4 * ((d.name || d.ip || "").length % 7) / 7, isNew: !!d["new"], key: String(d.id || d.ip || "") }); });
      if (pnet) { (pnet.switches || []).forEach(function (s) { dev.push({ n: s.name, r: 0.3, sw: 1, bad: !s.up }); }); ((pnet.wifi || {}).aps || []).forEach(function (a) { dev.push({ n: a, r: 0.45 }); }); ((pnet.dc || {}).hosts || []).forEach(function (h) { dev.push({ n: h.name, r: 0.38, bad: !h.up }); }); }
      g.font = Math.round(H * 0.01) + "px monospace";
      dev.forEach(function (d, i) {
        var hs = 0; for (var c = 0; c < d.n.length; c++) hs = (hs * 31 + d.n.charCodeAt(c)) >>> 0;
        var ang = ((hs || i * 977) % 628) / 100, x = cx + Math.cos(ang) * R * d.r, y = cy + Math.sin(ang) * R * d.r;
        var since = ((sw - ang) % (Math.PI * 2) + Math.PI * 2) % (Math.PI * 2), lum = reduced ? 0.8 : Math.max(0.15, 1 - since / 4);
        var col = d.bad ? COL.crit : green;
        g.fillStyle = rgba(col, lum); g.beginPath(); g.arc(x, y, d.sw ? 4 : 2.5, 0, Math.PI * 2); g.fill();
        if (d.isNew) {   // 1.6: a new device: a blip that keeps pinging, and NEW next to it
          var pq = reduced ? 0.5 : (t * 0.8 + i * 0.13) % 1;
          g.strokeStyle = rgba(COL.warn, 0.9 * (1 - pq)); g.lineWidth = Math.max(1, H / 700);
          g.beginPath(); g.arc(x, y, 4 + pq * R * 0.12, 0, Math.PI * 2); g.stroke();
          g.fillStyle = rgba(COL.warn, 1); g.beginPath(); g.arc(x, y, 3.5, 0, Math.PI * 2); g.fill();
          if (!shareMode) { g.fillStyle = rgba(COL.warn, 0.95); g.fillText("NEW" + (d.n ? " " + d.n.slice(0, 12) : ""), x + 7, y - 6); }
        }
        if (d.n && !shareMode && lum > 0.5) { g.fillStyle = rgba(col, lum * 0.8); g.fillText(d.n.slice(0, 16), x + 6, y + 3); }
      });
      g.globalAlpha = 1;
    }
    // 1.5: the weather of your infrastructure: a panel in trouble brings a cloud over the city, a storm when critical
    function drawInfraWeather(now, t, hz) {
      infra.forEach(function (w, i) {
        var hsh = 0; for (var k = 0; k < w.id.length; k++) hsh = (hsh * 31 + w.id.charCodeAt(k)) >>> 0;
        var x = W * (0.3 + 0.4 * ((hsh % 1000) / 1000)), y = hz - H * (0.2 + 0.035 * (i % 2)), crit = w.level === "crit";   // under the centre panels, over the city
        var cc = crit ? mix(COL.bg, [0, 0, 0], 0.55) : mix(COL.bg, COL.fg, 0.18), a = crit ? 0.92 : 0.75, s2 = H * (crit ? 0.06 : 0.045);
        infraPos[w.id] = [x, y, s2 * 1.6, w.title || w.id];
        g.fillStyle = rgba(cc, a);
        [[-1.1, 0.25, 0.75], [0, 0, 1], [1.1, 0.2, 0.8], [0.5, 0.45, 0.7], [-0.5, 0.45, 0.7]].forEach(function (c) {
          g.beginPath(); g.ellipse(x + c[0] * s2, y + c[1] * s2, s2 * c[2] * 1.2, s2 * c[2] * 0.7, 0, 0, Math.PI * 2); g.fill();
        });
        if (crit) {   // rain under it, and now and then a bolt down to the city
          g.strokeStyle = rgba(COL.fg, 0.25); g.lineWidth = 1; g.beginPath();
          for (k = 0; k < 14; k++) { var rx = x + (k / 13 - 0.5) * s2 * 2.4, ry = y + s2 * 0.6 + ((t * 0.9 + k * 0.37) % 1) * H * 0.12; g.moveTo(rx, ry); g.lineTo(rx - H * 0.004, ry + H * 0.014); }
          g.stroke();
          var b = infraBolt[w.id] || (infraBolt[w.id] = { at: now + 2000 + Math.random() * 4000, path: null });
          if (now > b.at && !reduced) {
            b.path = [[x + (Math.random() - 0.5) * s2, y + s2 * 0.5]];
            for (k = 0; k < 7; k++) { var lp = b.path[b.path.length - 1]; b.path.push([lp[0] + (Math.random() - 0.5) * H * 0.04, lp[1] + (hz - y) / 7]); }
            b.shown = now; b.at = now + 3000 + Math.random() * 6000;
          }
          if (b.path && now - b.shown < 220) {
            g.strokeStyle = rgba(COL.fg, 0.95); g.lineWidth = Math.max(1.5, H / 500); g.beginPath();
            b.path.forEach(function (pt, j) { if (j) g.lineTo(pt[0], pt[1]); else g.moveTo(pt[0], pt[1]); }); g.stroke();
            g.fillStyle = rgba(COL.fg, 0.05); g.fillRect(0, 0, W, hz);
          }
        }
        if (!shareMode) {
          g.font = Math.round(H * 0.012) + "px monospace"; g.textAlign = "center"; g.fillStyle = rgba(crit ? COL.crit : COL.warn, 0.9);
          g.fillText(String(w.title || w.id).slice(0, 24), x, y + s2 * 1.05); g.textAlign = "start";
        }
      });
    }
    // 1.5: what expires: a candle for each certificate, domain or date, shorter as the day comes, red at the end
    function drawCandles(t, hz) {
      if (simpleMon) { return; }   // 1.6: [pulse] secondary = "simple"
      if (!expiry.length || worldOff()) return;
      var n = Math.min(6, expiry.length), base = hz + (H - hz) * 0.66, step = W * 0.04, x0 = W * 0.64;
      g.font = Math.round(H * 0.0105) + "px monospace"; g.textAlign = "center";
      for (var i = 0; i < n; i++) {
        var e = expiry[i], x = x0 + i * step, d = e.days == null ? 0 : Math.max(0, e.days);
        var hh = H * (0.01 + 0.05 * Math.min(1, d / 90)), w = H * 0.011;
        var wax = e.level === "crit" ? COL.crit : e.level === "warn" ? COL.warn : mix(COL.fg, COL.bg, 0.25);
        g.fillStyle = rgba(wax, 0.92); g.fillRect(x - w / 2, base - hh, w, hh);
        var fk = reduced ? 1 : 0.8 + 0.2 * Math.sin(t * (e.level === "crit" ? 23 : 9) + i * 3), fy = base - hh - H * 0.008;
        var gl = g.createRadialGradient(x, fy, 0, x, fy, H * 0.03);
        gl.addColorStop(0, rgba(COL.warn, 0.4 * fk)); gl.addColorStop(1, rgba(COL.warn, 0));
        g.fillStyle = gl; g.fillRect(x - H * 0.03, fy - H * 0.03, H * 0.06, H * 0.06);
        g.fillStyle = rgba(mix(COL.warn, [255, 255, 220], 0.4), fk);
        g.beginPath(); g.ellipse(x, fy, w * 0.3, H * 0.008 * fk, 0, 0, Math.PI * 2); g.fill();
        if (!shareMode && e.name) {
          g.fillStyle = rgba(e.level === "ok" ? COL.fg : wax, 0.75);
          g.fillText(String(e.name).slice(0, 14), x, base + H * 0.016);
          g.fillText(e.days == null ? "?" : (e.days < 0 ? "expired" : Math.floor(e.days) + "d"), x, base + H * 0.03);
        }
      }
      g.textAlign = "start";
    }
    // 1.5: the energy of your home as a river across the floor: golden from the sun, blue from the grid, wider with more load
    var riverT = 0;
    function drawRiver(t, hz) {
      if (simpleMon) { return; }   // 1.6: [pulse] secondary = "simple"
      if (!energy || worldOff() || energy.load == null) return;
      var load = Math.max(0, energy.load), solar = Math.max(0, energy.solar || 0), grid = energy.grid || 0;
      var f = load > 0 ? Math.min(1, solar / load) : solar > 0 ? 1 : 0, col = mix([80, 160, 255], [255, 200, 80], f);
      var wide = H * (0.008 + 0.03 * Math.min(1, load / (energy.peak || 6000))), yc = hz + (H - hz) * 0.42;
      var ys = function (x, k) { return yc + Math.sin(x / W * 6 + 1) * H * 0.012 + k * wide; };
      g.fillStyle = rgba(col, 0.28); g.beginPath();
      for (var x = 0; x <= W; x += 16) { if (x) g.lineTo(x, ys(x, -0.5)); else g.moveTo(x, ys(x, -0.5)); }
      for (x = W; x >= 0; x -= 16) g.lineTo(x, ys(x, 0.5));
      g.closePath(); g.fill();
      if (!reduced) riverT += (0.0004 + 0.002 * Math.min(1, load / (energy.peak || 6000))) * (grid < 0 ? -1 : 1);
      g.fillStyle = rgba(mix(col, COL.fg, 0.4), 0.8);
      for (var k = 0; k < 40; k++) {
        var u = ((k / 40 + riverT) % 1 + 1) % 1, px = u * W, off = (((k * 0.618) % 1) - 0.5) * 0.8;
        g.fillRect(px, ys(px, off), Math.max(2, H / 300), Math.max(1, H / 700));
      }
      if (!shareMode) {
        var kw = function (w) { return (w / 1000).toFixed(1) + " kW"; };
        g.font = Math.round(H * 0.011) + "px monospace"; g.fillStyle = rgba(col, 0.9);
        g.fillText("☀ " + kw(solar) + " · home " + kw(load) + " · grid " + (grid < 0 ? "−" : "") + kw(Math.abs(grid)), W * 0.4, ys(W * 0.4, -0.5) - H * 0.008);
      }
    }
    function drawGarland(t, hz) {
      var cols = [COL.crit, COL.ok, COL.warn, COL.accent, COL.accent2], spans = 4, y0 = hz - H * 0.19;
      for (var sp = 0; sp < spans; sp++) {
        var x0 = W * sp / spans, x1 = W * (sp + 1) / spans, n = 14;
        g.strokeStyle = rgba(mix(COL.bg, COL.fg, 0.25), 0.5); g.lineWidth = Math.max(1, H / 1000); g.beginPath();
        for (var i = 0; i <= n; i++) {
          var u = i / n, x = x0 + (x1 - x0) * u, y = y0 + H * 0.035 * (1 - Math.pow(2 * u - 1, 2));
          if (i) g.lineTo(x, y); else g.moveTo(x, y);
        }
        g.stroke();
        for (i = 1; i < n; i++) {
          u = i / n; x = x0 + (x1 - x0) * u; y = y0 + H * 0.035 * (1 - Math.pow(2 * u - 1, 2));
          var on = reduced ? 1 : 0.35 + 0.65 * (((Math.floor(t * 1.2) + i + sp) % 2) ? 1 : 0.4), c = cols[(i + sp) % cols.length];
          var gl = g.createRadialGradient(x, y + H * 0.006, 0, x, y + H * 0.006, H * 0.016);
          gl.addColorStop(0, rgba(c, 0.55 * on)); gl.addColorStop(1, rgba(c, 0));
          g.fillStyle = gl; g.fillRect(x - H * 0.016, y - H * 0.01, H * 0.032, H * 0.032);
          g.fillStyle = rgba(c, 0.95 * on); g.beginPath(); g.arc(x, y + H * 0.006, Math.max(1.5, H / 330), 0, Math.PI * 2); g.fill();
        }
      }
    }
    function drawSeason(now, t, hz, cx, cy) {
      var xm = season.xmas, ny = season.newYear;
      if (xm && !worldOff()) {   // the tree, on the floor to the left of the core
        var tx = W * 0.3, ty = hz + H * 0.1, th = H * 0.17, green = mix(COL.bg, COL.ok, 0.45);
        for (var k = 0; k < 3; k++) {
          var w = th * (0.5 - k * 0.12), top = ty - th * (0.42 + k * 0.27), bot = ty - th * (k * 0.27);
          g.fillStyle = rgba(mix(green, [0, 0, 0], k * 0.08), 1);
          g.beginPath(); g.moveTo(tx, top); g.lineTo(tx + w, bot); g.lineTo(tx - w, bot); g.closePath(); g.fill();
        }
        g.fillStyle = rgba(mix(COL.bg, [90, 50, 30], 0.7), 1); g.fillRect(tx - th * 0.04, ty, th * 0.08, th * 0.08);
        var bc = [COL.crit, COL.warn, COL.accent, COL.accent2];
        for (k = 0; k < 14; k++) {
          var by = ty - th * (0.08 + (k * 0.61) % 0.85), half = th * 0.5 * (1 - (ty - by) / (th * 1.05)) * 0.8;
          var bx = tx + (((k * 0.37) % 1) * 2 - 1) * half, on2 = reduced ? 1 : 0.4 + 0.6 * Math.abs(Math.sin(t * 1.5 + k));
          g.fillStyle = rgba(bc[k % 4], on2); g.beginPath(); g.arc(bx, by, Math.max(1.5, H / 380), 0, Math.PI * 2); g.fill();
        }
        var sy = ty - th * 0.97, st2 = g.createRadialGradient(tx, sy, 0, tx, sy, H * 0.03);
        st2.addColorStop(0, rgba(COL.warn, 0.9)); st2.addColorStop(1, rgba(COL.warn, 0));
        g.fillStyle = st2; g.fillRect(tx - H * 0.03, sy - H * 0.03, H * 0.06, H * 0.06);
      }
      if (xm && xm.snow && wx !== "rain" && wx !== "storm" && wx !== "snow") {   // it is really cold: a few flakes
        if (!flakes.length) for (var f = 0; f < 70; f++) flakes.push([Math.random() * W, Math.random() * H, 0.4 + Math.random()]);
        g.fillStyle = rgba(COL.fg, 0.55);
        flakes.forEach(function (fl, i) {
          if (!reduced) { fl[1] += H * 0.0009 * fl[2]; fl[0] += Math.sin(t + i) * 0.25; if (fl[1] > H) { fl[1] = -4; fl[0] = Math.random() * W; } }
          g.fillRect(fl[0], fl[1], 2, 2);
        });
      }
      if (!ny) return;
      var font = getComputedStyle(document.body).fontFamily || "monospace";
      g.textAlign = "center"; g.textBaseline = "middle";
      if (ny.party) {   // midnight: fireworks for a quarter of an hour, fewer with reduced motion
        if (fxOn && now - nyFx > (reduced ? 4000 : 700)) { nyFx = now; fireworks(Math.random() < 0.5, "burst"); }
        g.font = "bold " + Math.round(H * 0.07) + "px " + font; g.fillStyle = rgba(COL.fg, 0.9);
        g.shadowColor = rgba(COL.accent2, 0.9); g.shadowBlur = H * 0.03;
        g.fillText("HAPPY " + ny.year, cx, cy); g.shadowBlur = 0;
      } else if (ny.left <= 60) {   // the last minute, big, second by second
        var sec = Math.ceil(ny.left), fr = ny.left - Math.floor(ny.left), sc = reduced ? 1 : 1 + 0.25 * fr;
        g.font = "bold " + Math.round(H * 0.16 * sc) + "px " + font; g.fillStyle = rgba(COL.fg, 0.5 + 0.5 * fr);
        g.shadowColor = rgba(COL.accent, 0.9); g.shadowBlur = H * 0.04;
        g.fillText(String(sec), cx, cy); g.shadowBlur = 0;   // on the sphere
      } else if (ny.left <= 3600) {   // the last hour, quietly
        var mm = Math.floor(ny.left / 60), ss = Math.floor(ny.left % 60);
        g.font = Math.round(H * 0.024) + "px " + font; g.fillStyle = rgba(COL.fg, 0.75);
        g.fillText(ny.year + " in " + mm + ":" + (ss < 10 ? "0" : "") + ss, cx, hz + H * 0.03);
      }
      g.textAlign = "start"; g.textBaseline = "alphabetic";
    }
    function darkness() {
      var se = night && night.sun ? night.sun.el : (phase === "night" ? -20 : phase === "day" ? 20 : -3);
      var d = se > 0 ? 0 : se > -6 ? 0.25 * (-se / 6) : se > -12 ? 0.25 + 0.75 * ((-se - 6) / 6) : 1;
      var ec = season.eclipse;   // 1.5: under a total eclipse the day turns to night and the stars come out
      if (ec && ec.kind === "solar" && se > -1) d = Math.max(d, Math.pow(Math.max(0, ec.obsc), 8));
      var cl = weatherNow && weatherNow.cloud != null && !Q.get("wx") ? weatherNow.cloud / 100 : (wx === "clear" ? 0 : wx === "part" ? 0.4 : wx === "fog" ? 0.7 : 0.85);
      return { d: d, sky: d * (1 - 0.85 * cl), cloud: cl };
    }
    function drawSun(t, hz, dk) {
      if (!night || !night.sun || night.sun.el < -1) return;
      var p = skyXY(night.sun.az, night.sun.el, hz); if (!p) return;
      var r = H * 0.03, a = Math.pow(1 - dk.cloud, 1.5), low = Math.max(0, 1 - night.sun.el / 12);   // redder near the horizon; behind the clouds, only a glow
      if (a < 0.04) return;
      var col = mix(COL.warn, COL.crit, low * 0.5);
      var gl = g.createRadialGradient(p[0], p[1], 0, p[0], p[1], r * 6);
      gl.addColorStop(0, rgba(col, 0.45 * a)); gl.addColorStop(1, rgba(col, 0));
      g.fillStyle = gl; g.fillRect(p[0] - r * 6, p[1] - r * 6, r * 12, r * 12);
      g.fillStyle = rgba(mix(COL.fg, col, 0.4), 0.95 * a); g.beginPath(); g.arc(p[0], p[1], r, 0, Math.PI * 2); g.fill();
      var ec = season.eclipse;
      if (ec && ec.kind === "solar" && ec.obsc > 0) {   // 1.5: the Moon's disc where it really is on the Sun
        if (ec.obsc > 0.995) {   // totality: the corona
          var co = g.createRadialGradient(p[0], p[1], r, p[0], p[1], r * 4.5);
          co.addColorStop(0, rgba([235, 240, 255], 0.75 * a)); co.addColorStop(0.3, rgba([200, 215, 255], 0.22 * a)); co.addColorStop(1, rgba([200, 215, 255], 0));
          g.fillStyle = co; g.beginPath(); g.arc(p[0], p[1], r * 4.5, 0, Math.PI * 2); g.fill();
        }
        g.fillStyle = rgba(mix(COL.bg, [0, 0, 0], 0.6), 1);
        g.beginPath(); g.arc(p[0] + ec.off[0] * r, p[1] - ec.off[1] * r, r * (ec.rm || 1.02), 0, Math.PI * 2); g.fill();
        g.font = Math.round(H / 62) + "px " + (getComputedStyle(document.body).fontFamily || "monospace");
        g.textAlign = "center"; g.fillStyle = rgba(COL.fg, 0.8);
        g.fillText("eclipse · " + Math.round(ec.obsc * 100) + "%", p[0], p[1] + r * 2.6); g.textAlign = "start";
      }
    }
    function drawMoon(t, hz, dk) {
      var m = night && night.moon; if (!m || m.el < -1) return;
      var p = skyXY(m.az, m.el, hz); if (!p) return;
      var r = H * 0.034, a = (0.35 + 0.65 * Math.max(dk.d, 0.3)) * (1 - 0.75 * dk.cloud);
      var sp = night.sun ? skyXY(night.sun.az, Math.max(night.sun.el, 0.1), hz) : null;   // the lit side looks at the Sun
      var ang = sp ? Math.atan2(sp[1] - p[1], sp[0] - p[0]) : (m.waxing ? 0 : Math.PI);
      if (night.sun && night.sun.el < 0) ang = Math.atan2(r * 0.3, (m.waxing ? 1 : -1) * r);   // under the horizon: its side
      var k = m.lit, lit = mix(COL.fg, [255, 244, 214], 0.5);
      var lec = season.eclipse;
      if (lec && lec.kind === "lunar" && lec.pmag > 0) {   // 1.5: a lunar eclipse: dimmer in the penumbra, copper red in the umbra
        lit = mix(lit, [178, 64, 36], Math.max(0, Math.min(1, lec.umag * 1.2 + 0.1)));
        a *= lec.umag > 0 ? 1 - 0.25 * Math.min(1, lec.umag) : 1 - 0.3 * Math.min(1, lec.pmag);
        k = Math.max(k, 0.97);
      }
      g.save(); g.translate(p[0], p[1]); g.rotate(ang);
      var halo = g.createRadialGradient(0, 0, r, 0, 0, r * 4);
      halo.addColorStop(0, rgba(lit, 0.18 * a * k)); halo.addColorStop(1, rgba(lit, 0));
      g.fillStyle = halo; g.fillRect(-r * 4, -r * 4, r * 8, r * 8);
      g.fillStyle = rgba(COL.bg, 0.55 * a); g.beginPath(); g.arc(0, 0, r, 0, Math.PI * 2); g.fill();   // the dark part (earthshine)
      g.fillStyle = rgba(lit, 0.95 * a); g.beginPath();
      g.arc(0, 0, r, -Math.PI / 2, Math.PI / 2, false);   // the lit half, on the Sun's side
      var rx = r * Math.abs(1 - 2 * k);
      if (k < 0.5) g.ellipse(0, 0, Math.max(0.01, rx), r, 0, Math.PI / 2, -Math.PI / 2, true);   // a crescent
      else g.ellipse(0, 0, Math.max(0.01, rx), r, 0, Math.PI / 2, Math.PI * 1.5, false);         // gibbous
      g.fill();
      g.restore();
    }
    function drawRealStars(t, hz, dk) {
      if (!night || dk.sky < 0.03) return;
      var pts = night.stars.map(function (s) { return skyXY(s.az, s.el, hz); });
      g.strokeStyle = rgba(COL.accent2, 0.34 * dk.sky); g.lineWidth = Math.max(1, H / 1100); g.beginPath();   // the constellations
      for (var i = 0; i < night.lines.length; i++) {
        var a = pts[night.lines[i][0]], b = pts[night.lines[i][1]];
        if (a && b && Math.abs(a[0] - b[0]) < W * 0.4) { g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); }
      }
      g.stroke();
      for (i = 0; i < night.stars.length; i++) {
        var p = pts[i]; if (!p) continue;
        var s = night.stars[i], mag = s.m, tw = 0.75 + 0.25 * Math.sin(t * (2 + i % 5) + i);
        var sz = H / 1080 * Math.max(2.4, 5.6 - mag * 1.1), al = Math.min(1, (1.3 - mag * 0.18)) * dk.sky * tw;
        if (mag < 2.6) { var gl = g.createRadialGradient(p[0], p[1], 0, p[0], p[1], sz * 3); gl.addColorStop(0, rgba(COL.fg, 0.45 * al)); gl.addColorStop(1, rgba(COL.fg, 0)); g.fillStyle = gl; g.fillRect(p[0] - sz * 3, p[1] - sz * 3, sz * 6, sz * 6); }
        g.fillStyle = rgba(COL.fg, al); g.beginPath(); g.arc(p[0], p[1], sz / 2, 0, Math.PI * 2); g.fill();
      }
      if (dk.sky > 0.5 && !shareMode) {   // the names, faint
        g.font = "600 " + Math.round(H * 0.0105) + "px 'CaskaydiaMono Nerd Font', 'Cascadia Mono', monospace"; g.textAlign = "center";
        g.fillStyle = rgba(COL.accent2, 0.5 * dk.sky);
        for (i = 0; i < night.names.length; i++) {
          var nm = night.names[i], xs = 0, ys = 0, c = 0;
          for (var j = 0; j < nm.i.length; j++) { var q = pts[nm.i[j]]; if (q) { xs += q[0]; ys += q[1]; c++; } }
          if (c >= Math.min(2, nm.i.length)) g.fillText(nm.n, xs / c, ys / c + H * 0.03);
        }
      }
      var PC = { venus: [255, 250, 235], mars: [255, 140, 100], jupiter: [255, 236, 200], saturn: [240, 215, 160] };
      for (i = 0; i < night.planets.length; i++) {   // the planets: bright, steady, a little coloured
        var pl = night.planets[i], pp = skyXY(pl.az, pl.el, hz); if (!pp) continue;
        var pa = Math.max(dk.sky, pl.n === "venus" ? 0.6 * (1 - dk.cloud) : 0), ps = H / 1080 * Math.max(4.2, 6.5 - pl.m * 0.8);
        var pg = g.createRadialGradient(pp[0], pp[1], 0, pp[0], pp[1], ps * 4); pg.addColorStop(0, rgba(PC[pl.n], 0.5 * pa)); pg.addColorStop(1, rgba(PC[pl.n], 0));
        g.fillStyle = pg; g.fillRect(pp[0] - ps * 4, pp[1] - ps * 4, ps * 8, ps * 8);
        g.fillStyle = rgba(PC[pl.n], pa); g.beginPath(); g.arc(pp[0], pp[1], ps / 2, 0, Math.PI * 2); g.fill();
        if (!shareMode && pa > 0.3) { g.font = "600 " + Math.round(H * 0.01) + "px 'CaskaydiaMono Nerd Font', monospace"; g.textAlign = "left"; g.fillStyle = rgba(PC[pl.n], 0.55 * pa); g.fillText(pl.n.toUpperCase(), pp[0] + ps * 2, pp[1] - ps); }
      }
    }
    function meteors(now, hz, dk) {   // a meteor shower: more shooting stars, all from its radiant
      var sh = night && night.shower; if (!sh || dk.sky < 0.3) return;
      var perMin = Math.min(40, sh.zhr * sh.strength / 20 + 0.5);   // a storm of meteors (the Leonids of 1966) caps it
      if (now - lastMeteor < 60000 / perMin * (0.5 + Math.random())) return;
      lastMeteor = now;
      var rp = sh.el > 0 ? skyXY(sh.az, sh.el, hz) : null;
      var x0 = W * (0.15 + Math.random() * 0.7), y0 = hz * (0.1 + Math.random() * 0.45), dx, dy;
      if (rp) { dx = x0 - rp[0]; dy = y0 - rp[1]; } else { dx = (Math.random() - 0.5) * W; dy = H * 0.2; }
      var l = Math.max(1, Math.hypot(dx, dy)), sp = W * (0.008 + Math.random() * 0.008);
      shooting.push({ x: x0, y: y0, vx: dx / l * sp, vy: dy / l * sp, life: 1 });
    }
    // Starlink: the satellites really in your sky and lit by the Sun. Only those above 10°: lower ones are near the
    // horizon, hardly visible to the eye and almost still on screen, so the "train" looked parked behind the city.
    // The label goes over the highest satellite whose label is clear of the panels and the sphere (else no label).
    var STARLINK_MIN_EL = 10;
    function drawStarlink(t, hz, dk) {
      var ss = starlink && starlink.sats; if (!ss || !ss.length) return;
      var pts = [];
      for (var i = 0; i < ss.length; i++) {
        if (!(ss[i].el >= STARLINK_MIN_EL)) continue;
        var p = skyXY(ss[i].az, ss[i].el, hz); if (!p) continue;
        var a = Math.max(0.35, dk.sky) * (0.7 + 0.3 * Math.sin(t * 3 + i)), s = H / 1080 * 2.2;
        var gl = g.createRadialGradient(p[0], p[1], 0, p[0], p[1], s * 3); gl.addColorStop(0, rgba(COL.fg, 0.4 * a)); gl.addColorStop(1, rgba(COL.fg, 0));
        g.fillStyle = gl; g.fillRect(p[0] - s * 3, p[1] - s * 3, s * 6, s * 6);
        g.fillStyle = rgba(COL.fg, a); g.beginPath(); g.arc(p[0], p[1], s / 2, 0, Math.PI * 2); g.fill();
        pts.push(p);
      }
      if (!pts.length || shareMode) return;
      g.font = "600 " + Math.round(H * 0.012) + "px 'CaskaydiaMono Nerd Font', monospace"; g.textAlign = "left";
      var txt = "STARLINK · " + pts.length, tw = g.measureText(txt).width, th = H * 0.014;
      pts.sort(function (a, b) { return a[1] - b[1]; });
      var at = starlinkLabelAt(pts, tw, th, uiBoxes());
      if (at) { g.fillStyle = rgba(COL.accent, 0.8); g.fillText(txt, at[0], at[1]); }
    }
    function starlinkLabelAt(pts, tw, th, boxes) {   // [x, baseline] of a label box clear of every box, or null
      for (var i = 0; i < pts.length; i++) {
        var x = pts[i][0] + H * 0.01, y = pts[i][1] - H * 0.012, hit = false;
        for (var j = 0; j < boxes.length && !hit; j++) {
          var b = boxes[j];
          hit = x < b[0] + b[2] && x + tw > b[0] && y > b[1] && y - th < b[1] + b[3];
        }
        if (!hit) return [x, y];
      }
      return null;
    }
    // clouds: as many as the real cloud cover, drifting with the real wind; darker in a storm
    function drawClouds(t, hz, dk) {
      var cov = dk.cloud; if (cov < 0.08) return;
      var n = Math.round(4 + cov * 14);
      while (clouds.length < n) clouds.push([Math.random() * W * 1.2 - W * 0.1, H * (0.06 + Math.random() * 0.42), 0.6 + Math.random() * 0.9, Math.random() * 10]);
      var storm = wx === "storm", base = storm ? mix(COL.bg, [40, 40, 52], 0.5) : mix(COL.bg, COL.fg, dk.d > 0.5 ? 0.12 : 0.55);
      for (var i = 0; i < n; i++) {
        var c = clouds[i], w = W * 0.14 * c[2], h = H * 0.045 * c[2];
        c[0] += (windX || -0.05) * H * 0.0012 * c[2] + 0.02;
        if (c[0] > W + w) c[0] = -w; if (c[0] < -w * 1.2) c[0] = W + w;
        var a = (storm ? 0.55 : 0.22 + 0.25 * cov) * Math.min(1, cov * 1.4);
        g.fillStyle = rgba(base, a);
        g.beginPath();
        g.ellipse(c[0], c[1], w * 0.5, h * 0.55, 0, 0, Math.PI * 2);
        g.ellipse(c[0] - w * 0.28, c[1] + h * 0.12, w * 0.3, h * 0.42, 0, 0, Math.PI * 2);
        g.ellipse(c[0] + w * 0.3, c[1] + h * 0.1, w * 0.32, h * 0.45, 0, 0, Math.PI * 2);
        g.ellipse(c[0] + w * 0.05, c[1] - h * 0.3, w * 0.25, h * 0.4, 0, 0, Math.PI * 2);
        g.fill();
      }
    }
    // thunderstorms: a real one over you throws bolts down on the city (thunder a few seconds later);
    // one around you lights the horizon from where it really is
    function stormTick(now, hz) {
      var cells = (storms && storms.cells) || [], here = wx === "storm" || cells.some(function (c) { return !c.km; });
      if (!here && !cells.length) return;
      if (now < nextBolt) return;
      var lpi = storms && storms.lpi || 0, rate = (here ? 9 : 6) / (1 + Math.min(2, lpi / 40));   // seconds between flashes: more when the air is charged
      nextBolt = now + (rate * (0.4 + Math.random() * 1.2)) * 1000;
      var c = here ? null : cells[Math.floor(Math.random() * cells.length)];
      var x = c ? (c.az >= 75 && c.az <= 285 ? W / 2 + (c.az - 180) / 90 * W * 0.44 : (Math.sin(c.az * Math.PI / 180) > 0 ? W * 0.04 : W * 0.96)) : band[0] + (band[1] - band[0]) * (0.08 + Math.random() * 0.84);   // over you: where you can see it
      var pts = [[x + (Math.random() - 0.5) * W * 0.05, H * 0.12]], y = H * 0.12;
      while (y < (here ? hz : hz - H * 0.02)) { y += H * (0.03 + Math.random() * 0.05); pts.push([pts[pts.length - 1][0] + (Math.random() - 0.5) * W * 0.03, Math.min(y, hz)]); }
      bolts.push({ t: now, pts: here ? pts : null, x: x, far: !here });
      if (here) { var km = 1 + Math.random() * 6; sound.thunder(km / 0.343, Math.max(0.2, 1 - km / 8)); }   // sound: 3 s per km
      flash = here ? 1 : 0.35;
    }
    function zapNow() {   // 1.6: a news rule ("CVE" -> lightning): one bolt over the city, now
      var hz = H * 0.745, x = band[0] + (band[1] - band[0]) * (0.2 + Math.random() * 0.6), pts = [[x, H * 0.12]], y = H * 0.12;
      while (y < hz) { y += H * (0.03 + Math.random() * 0.05); pts.push([pts[pts.length - 1][0] + (Math.random() - 0.5) * W * 0.03, Math.min(y, hz)]); }
      bolts.push({ t: performance.now(), pts: pts, x: x, far: false }); flash = reduced ? 0.3 : 1;
      try { sound.thunder(1.5, 0.5); } catch (e) {}
    }
    function drawBolts(now, hz) {
      var keep = [];
      for (var i = 0; i < bolts.length; i++) {
        var b = bolts[i], age = (now - b.t) / 1000; if (age > 0.6) continue; keep.push(b);
        var a = age < 0.08 || (age > 0.16 && age < 0.22) ? 1 : 0.25;
        if (b.pts) {
          g.globalCompositeOperation = "lighter";
          [[12, 0.12], [5, 0.3], [2.4, 1]].forEach(function (ps) {
            g.strokeStyle = rgba(mix(COL.fg, COL.accent, 0.3), ps[1] * a); g.lineWidth = ps[0] * H / 1080; g.beginPath();
            for (var j = 0; j < b.pts.length; j++) { if (j) g.lineTo(b.pts[j][0], b.pts[j][1]); else g.moveTo(b.pts[j][0], b.pts[j][1]); }
            g.stroke();
          });
          g.globalCompositeOperation = "source-over";
        } else {   // far away: the clouds on the horizon light up from inside
          var gl = g.createRadialGradient(b.x, hz - H * 0.08, 0, b.x, hz - H * 0.08, W * 0.18);
          gl.addColorStop(0, rgba(COL.fg, 0.28 * a)); gl.addColorStop(1, rgba(COL.fg, 0));
          g.fillStyle = gl; g.fillRect(b.x - W * 0.18, hz - H * 0.3, W * 0.36, H * 0.35);
        }
      }
      bolts = keep;
    }
    // the air: a haze over the city when it is poor; pollen floating in the light when there is a lot of it
    function drawAir(t, hz) {
      if (!air) return;
      var hz2 = { moderate: 0.07, poor: 0.16, "very poor": 0.24, "extremely poor": 0.32 }[air.level] || 0;
      if (hz2) {
        var gr = g.createLinearGradient(0, hz - H * 0.35, 0, hz + H * 0.05);
        gr.addColorStop(0, rgba([150, 135, 110], 0)); gr.addColorStop(0.7, rgba([150, 135, 110], hz2)); gr.addColorStop(1, rgba([150, 135, 110], hz2 * 0.6));
        g.fillStyle = gr; g.fillRect(0, hz - H * 0.35, W, H * 0.4);
      }
      var pn = { low: 10, moderate: 25, high: 50, "very high": 80 }[air.pollenLevel] || 0;
      while (pollen.length < pn) pollen.push([Math.random() * W, hz * (0.3 + Math.random() * 0.7), Math.random() * 6, 0.5 + Math.random()]);
      g.fillStyle = rgba([255, 228, 140], 0.55);
      for (var i = 0; i < pn; i++) {
        var p = pollen[i]; p[0] += (windX || 0.1) * H * 0.0015 * p[3] + Math.sin(t + p[2]) * 0.3; p[1] += Math.sin(t * 0.7 + p[2]) * 0.25;
        if (p[0] > W) p[0] = 0; if (p[0] < 0) p[0] = W;
        g.beginPath(); g.arc(p[0], p[1], H / 1080 * 1.4 * p[3], 0, Math.PI * 2); g.fill();
      }
    }
    // how hard it really rains or snows: the drops follow the precipitation (mm/h) and the kind of rain
    function precipCount() {
      var w = weatherNow, c = w ? w.code : 61, mm = w && w.rain != null ? +w.rain : 1;
      var base = { 51: 0.15, 53: 0.25, 55: 0.35, 56: 0.25, 57: 0.4, 61: 0.4, 63: 0.65, 65: 1, 66: 0.5, 67: 0.9, 71: 0.35, 73: 0.65, 75: 1, 77: 0.3,
        80: 0.45, 81: 0.75, 82: 1, 85: 0.5, 86: 0.9, 95: 0.8, 96: 0.9, 99: 1 }[c] || 0.5;
      return Math.round(drops.length * Math.min(1, Math.max(base, mm / 6)));
    }

    // ---------------- the soundscape: rain, wind, planes, thunder, the station; off unless [sound] enabled = true
    var sound = (function () {
      var ctx = null, master = null, rainG = null, windG = null, windF = null, planeG = null, noise = null, on = false;
      function start() {
        try {
          var AC = window.AudioContext || window.webkitAudioContext; if (!AC) return false;
          ctx = new AC(); master = ctx.createGain(); master.gain.value = 0; master.connect(ctx.destination);
          var len = ctx.sampleRate * 2, buf = ctx.createBuffer(1, len, ctx.sampleRate), d = buf.getChannelData(0);
          for (var i = 0; i < len; i++) d[i] = Math.random() * 2 - 1;
          noise = buf;
          var src = function () { var s = ctx.createBufferSource(); s.buffer = noise; s.loop = true; s.start(); return s; };
          var hp = ctx.createBiquadFilter(); hp.type = "highpass"; hp.frequency.value = 900;
          var lp = ctx.createBiquadFilter(); lp.type = "lowpass"; lp.frequency.value = 7000;
          rainG = ctx.createGain(); rainG.gain.value = 0; src().connect(hp); hp.connect(lp); lp.connect(rainG); rainG.connect(master);
          windF = ctx.createBiquadFilter(); windF.type = "bandpass"; windF.frequency.value = 400; windF.Q.value = 0.6;
          windG = ctx.createGain(); windG.gain.value = 0; src().connect(windF); windF.connect(windG); windG.connect(master);
          var pl = ctx.createBiquadFilter(); pl.type = "lowpass"; pl.frequency.value = 260;
          planeG = ctx.createGain(); planeG.gain.value = 0; src().connect(pl); pl.connect(planeG); planeG.connect(master);
          return true;
        } catch (e) { return false; }
      }
      function ramp(gn, v, s) { if (gn) { gn.gain.cancelScheduledValues(ctx.currentTime); gn.gain.setTargetAtTime(v, ctx.currentTime, s || 1.2); } }
      return {
        update: function (cfg, quiet) {   // every second, from the state
          var vol = cfg && !quiet ? cfg.volume : 0;
          if (!PRIMARY || (!on && !vol)) return;
          if (!on) { on = start(); if (!on) return; }
          if (ctx.state === "suspended") ctx.resume();
          ramp(master, vol * 0.7, 0.8);
          var wet = (wx === "rain" || wx === "storm" || wx === "snow") && cfg.rain ? (wx === "snow" ? 0.05 : 0.08 + 0.3 * precipCount() / Math.max(1, drops.length)) : 0;
          ramp(rainG, wet);
          ramp(windG, cfg.wind ? windK * windK * 0.45 : 0); if (windF) windF.frequency.setTargetAtTime(300 + windK * 500, ctx.currentTime, 2);
          var pg = 0;
          if (cfg.planes) for (var i = 0; i < planes.length; i++) if (planes[i].el > 25) pg = Math.max(pg, (planes[i].el - 25) / 65 * 0.5 * Math.min(1, 30000 / Math.max(5000, planes[i].ft || 30000)));
          ramp(planeG, pg, 3);
        },
        thunder: function (delay, strength) {
          if (!on || !soundCfg || !soundCfg.thunder || !ctx) return;
          var t0 = ctx.currentTime + delay, s = ctx.createBufferSource(); s.buffer = noise;
          var f = ctx.createBiquadFilter(); f.type = "lowpass"; f.frequency.value = 160 + 200 * strength;
          var gn = ctx.createGain(); gn.gain.setValueAtTime(0, t0); gn.gain.linearRampToValueAtTime(0.9 * strength, t0 + 0.08);
          gn.gain.exponentialRampToValueAtTime(0.001, t0 + 3.5); s.connect(f); f.connect(gn); gn.connect(master); s.start(t0); s.stop(t0 + 3.6);
        },
        chime: function (notes) {
          if (!on || !soundCfg || !soundCfg.events || !ctx) return;
          (notes || [659, 523]).forEach(function (hz, i) {
            var o = ctx.createOscillator(), gn = ctx.createGain(), t1 = ctx.currentTime + i * 0.35;
            o.frequency.value = hz; gn.gain.setValueAtTime(0, t1); gn.gain.linearRampToValueAtTime(0.25, t1 + 0.02); gn.gain.exponentialRampToValueAtTime(0.001, t1 + 1.2);
            o.connect(gn); gn.connect(master); o.start(t1); o.stop(t1 + 1.3);
          });
        },
        rumble: function (strength) {
          if (!on || !soundCfg || !soundCfg.events || !ctx) return;
          var t1 = ctx.currentTime, s = ctx.createBufferSource(); s.buffer = noise;
          var f = ctx.createBiquadFilter(); f.type = "lowpass"; f.frequency.value = 90;
          var gn = ctx.createGain(); gn.gain.setValueAtTime(0, t1); gn.gain.linearRampToValueAtTime(strength, t1 + 0.6); gn.gain.exponentialRampToValueAtTime(0.001, t1 + 5);
          s.connect(f); f.connect(gn); gn.connect(master); s.start(t1); s.stop(t1 + 5.1);
        }
      };
    })();

    function drawShooting() {
      var keep = [];
      for (var i = 0; i < shooting.length; i++) {
        var sh = shooting[i];
        sh.x += sh.vx; sh.y += sh.vy; sh.life -= 0.02;
        if (sh.life <= 0) continue;
        var gr = g.createLinearGradient(sh.x, sh.y, sh.x - sh.vx * 12, sh.y - sh.vy * 12);
        gr.addColorStop(0, rgba(COL.fg, sh.life)); gr.addColorStop(1, rgba(COL.fg, 0));
        g.strokeStyle = gr; g.lineWidth = Math.max(1, H / 600); g.beginPath(); g.moveTo(sh.x, sh.y); g.lineTo(sh.x - sh.vx * 12, sh.y - sh.vy * 12); g.stroke();
        keep.push(sh);
      }
      shooting = keep;
    }

    function draw(now) {
      var t = (now - t0) / 1000;
      var hz = H * 0.745, cx = W / 2;
      var bg = COL.bg, ac = COL.accent, a2 = COL.accent2;
      music = audio.length > 0 && now - audioT < 800;
      bass = music ? bass * 0.6 + avg(audio, 0, 4) * 0.4 : bass * 0.9;
      mid = music ? avg(audio, 6, 16) : 0;
      if (music && bass > 0.55) beatEnv = Math.max(beatEnv, bass);
      var shk = quakes.length && !reduced ? quakeShake(now) : null;
      if (shk) g.setTransform(1, 0, 0, 1, shk[0], shk[1]);   // an earthquake shakes the city (only then)
      g.drawImage(bgc, 0, 0);
      if (wpOn && wpMode === "panels") { if (shk) g.setTransform(1, 0, 0, 1, 0, 0); return; }
      // the eyes: towards the pointer while it moves, then slowly back to the middle
      var gx = 0, gy = 0;
      if (eyesOn && cursorXY && now - cursorAt < 15000) {
        var fyy = kind === "skyline" || kind === "calm" ? hz - H * 0.09 : hz - H * 0.16;
        gx = (cursorXY[0] * W / innerWidth - cx) / (H * 0.45); gy = (cursorXY[1] * H / innerHeight - fyy) / (H * 0.45);
        var gl2 = Math.sqrt(gx * gx + gy * gy); if (gl2 > 1) { gx /= gl2; gy /= gl2; }
      }
      gaze.x += (gx - gaze.x) * 0.18; gaze.y += (gy - gaze.y) * 0.18;
      // the red lights on top of your city's landmarks
      if (!worldOff() && (t % 1.7) < 0.22) { g.fillStyle = rgba(COL.crit, 0.95); for (var ci = 0; ci < cityTops.length; ci++) g.fillRect(cityTops[ci][0] - H / 700, cityTops[ci][1] - H / 300, H / 350, H / 350); }
      // stars (fewer when it is cloudy, none by day)
      var dk = darkness();
      var starA = night ? dk.sky * 0.35 : phase === "day" ? 0.1 : phase !== "night" ? 0.45 : wx === "clear" || wx === "part" ? 1 : 0.35;   // with the real sky, the random ones are only a faint background
      for (var i = 0; i < (wpOn ? 0 : stars.length); i++) {
        var st = stars[i], tw = 0.5 + 0.5 * Math.sin(t * st[3] * 2 + st[2] * 40);
        g.fillStyle = rgba(COL.fg, (0.15 + 0.6 * tw * st[2]) * starA);
        var ss = Math.max(1, H / 1080 * (st[2] > 0.9 ? 2.4 : 1.3));
        g.fillRect(st[0], st[1], ss, ss);
      }
      if (!wpOn) {   // over your own wallpaper Pulse does not draw a sky of its own
      drawSun(t, hz, dk);       // 1.4: the real Sun, Moon, planets, stars, constellations, meteors, Starlink, clouds
      drawRealStars(t, hz, dk);
      drawMoon(t, hz, dk);
      drawStarlink(t, hz, dk);
      meteors(now, hz, dk);
      drawClouds(t, hz, dk);
      }
      stormTick(now, hz);
      drawBolts(now, hz);
      if (season.xmas) drawGarland(t, hz);
      if (infraOn && infra.length) drawInfraWeather(now, t, hz);
      // aurora = network traffic
      var kp = space && space.kp || 0, kpA = kp >= 4 ? Math.min(1, (kp - 3) / 5) : 0;   // a geomagnetic storm: a real aurora
      var na = Math.max(Math.min(1, Math.log10(1 + net) / 7.5), mid * 0.9, kpA);
      g.globalCompositeOperation = "lighter";
      for (var k = 0; k < (wpOn ? 0 : kpA ? 3 : 2); k++) {
        var col = k === 2 ? COL.ok : k ? a2 : ac, base = H * (0.16 + k * 0.09) - (k === 2 ? H * 0.12 : 0);
        g.beginPath();
        for (var x = 0; x <= W; x += 14) {
          var y = base + Math.sin(x * 0.004 + t * (0.25 + k * 0.1) + k * 2) * H * 0.04 + Math.sin(x * 0.011 - t * 0.4) * H * 0.015;
          if (x) g.lineTo(x, y); else g.moveTo(x, y);
        }
        for (x = W; x >= 0; x -= 14) {
          var y2 = base + H * (0.07 + 0.05 * na) + Math.sin(x * 0.003 + t * 0.2 + k) * H * 0.03;
          g.lineTo(x, y2);
        }
        g.closePath();
        var ag = g.createLinearGradient(0, base - H * 0.05, 0, base + H * 0.14);
        ag.addColorStop(0, rgba(col, 0)); ag.addColorStop(0.45, rgba(col, 0.05 + 0.13 * na)); ag.addColorStop(1, rgba(col, 0));
        g.fillStyle = ag; g.fill();
      }
      g.globalCompositeOperation = "source-over";

      if (!wpOn) { drawShooting(); drawISS(t, hz); }
      if (now - flareAt < 2500) { g.fillStyle = rgba(COL.warn, 0.18 * (1 - (now - flareAt) / 2500)); g.fillRect(0, 0, W, hz); }   // a solar flare
      // the core: a wireframe sphere rising on the horizon, beating with the load (or the LED sphere)
      var R = H * (kind === "orbit" ? 0.15 : kind === "sphere" ? 0.165 : 0.135) * (1 + (kind === "sphere" ? 0.012 : 0.035) * beatEnv);
      var cy = kind === "orbit" ? hz - R * 1.02 : kind === "sphere" ? hz - R * 0.97 : hz;
      if (!worldOff()) {   // 1.5: with a community scene (or your own wallpaper), Pulse keeps the sky, the weather and the effects; the scene draws the world
      if (kind !== "sphere" && kind !== "noir") {
      var glow = g.createRadialGradient(cx, cy, R * 0.2, cx, cy, R * 2.4);
      glow.addColorStop(0, rgba(alertEnv > 0.05 && alertCol ? alertCol : a2, 0.30 + 0.25 * beatEnv));
      glow.addColorStop(1, rgba(a2, 0));
      g.fillStyle = glow; g.fillRect(cx - R * 2.5, cy - R * 2.5, R * 5, R * 2.5 + (kind === "orbit" ? R * 2.5 : 1));
      g.save();
      g.beginPath(); g.rect(0, 0, W, kind === "orbit" ? H : hz); g.clip();
      var body = g.createLinearGradient(0, cy - R, 0, cy + R);
      body.addColorStop(0, rgba(mix(bg, a2, 0.55), 1)); body.addColorStop(1, rgba(mix(bg, ac, 0.25), 1));
      g.beginPath(); g.arc(cx, cy, R, 0, Math.PI * 2); g.fillStyle = body; g.fill();
      g.lineWidth = Math.max(1, H / 700);
      g.strokeStyle = rgba(ac, 0.55);
      for (var la = -3; la <= 3; la++) {   // latitudes
        var yy = cy + (la / 4) * R, rx = Math.sqrt(Math.max(0, R * R - (yy - cy) * (yy - cy)));
        g.beginPath(); g.ellipse(cx, yy, rx, rx * 0.08, 0, 0, Math.PI * 2); g.stroke();
      }
      for (var mer = 0; mer < 6; mer++) {   // turning meridians
        var ph = (mer / 6) * Math.PI + t * (0.12 + cpu / 600);
        var ex = Math.abs(Math.cos(ph)) * R;
        g.beginPath(); g.ellipse(cx, cy, ex, R, 0, 0, Math.PI * 2); g.strokeStyle = rgba(ac, 0.18 + 0.4 * Math.abs(Math.sin(ph))); g.stroke();
      }
      g.beginPath(); g.arc(cx, cy, R, 0, Math.PI * 2); g.strokeStyle = rgba(ac, 0.9); g.lineWidth = Math.max(1.5, H / 420); g.stroke();
      var mood = faceMood(t);   // the agent lives in the core
      if (mood) drawFace(g, cx, kind === "orbit" ? cy : cy - R * 0.45, R * (kind === "orbit" ? 0.9 : 0.55), mood, t, COL.fg);
      g.restore();
      }

      if (kind !== "sphere" && focus) {   // focus: the time left as a ring around the core
        var leftF = Math.max(0, focus.until - hubNowG()) / Math.max(1, focus.until - focus.start);
        g.beginPath(); g.arc(cx, cy, R * 1.12, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * leftF); g.strokeStyle = rgba(a2, 0.9); g.lineWidth = Math.max(2, H / 250); g.stroke();
      }
      if (kind === "orbit") {
        // every core is a moon on its ring
        var n = Math.min(cores.length, 16);
        for (i = 0; i < n; i++) {
          var rr = R * (1.25 + i * 0.09), sp = 0.1 + (cores[i] || 0) / 160;
          rings[i] = (rings[i] || i * 1.7) + sp / Math.max(1, fps);
          g.beginPath(); g.ellipse(cx, cy, rr, rr * 0.28, -0.25, 0, Math.PI * 2); g.strokeStyle = rgba(ac, 0.12 + (cores[i] || 0) / 400); g.lineWidth = Math.max(1, H / 1080); g.stroke();
          var ang = rings[i], px = cx + Math.cos(ang) * rr * Math.cos(-0.25) - Math.sin(ang) * rr * 0.28 * Math.sin(-0.25),
            py = cy + Math.cos(ang) * rr * Math.sin(-0.25) + Math.sin(ang) * rr * 0.28 * Math.cos(-0.25);
          var front = Math.sin(ang) > 0;
          g.fillStyle = rgba((cores[i] || 0) > 85 ? COL.crit : front ? ac : a2, front ? 0.95 : 0.4);
          g.beginPath(); g.arc(px, py, Math.max(1.5, H / 300) * (front ? 1.4 : 0.9), 0, Math.PI * 2); g.fill();
        }
      } else {
        drawGit(t, hz, cx, R);
        // far city (still), then the skyline: one tower per CPU core, its lit windows follow the RAM
        var nc = kind === "noir" ? 0 : Math.min(Math.max(cores.length, 8), 32), side = Math.max(1, Math.ceil(nc / 2));
        var inner = R * (kind === "sphere" ? 1.08 : 1.18), outer = Math.min(cx - band[0], band[1] - cx);
        var slot = Math.max(4, (outer - inner) / side), bw = slot * 0.74;
        focusK += ((focus ? 1 : 0) - focusK) * (reduced ? 1 : 0.02);   // 1.6: a focus slowly brings the city down
        for (i = 0; i < nc; i++) {
          var load = cores.length ? cores[i % cores.length] || 0 : cpu;
          if (music) load = Math.min(100, load * 0.4 + (audio[Math.min(audio.length - 1, (i / 2) | 0)] || 0) * 0.9);   // the city dances
          heights[i] = (heights[i] == null ? load : heights[i] + (load - heights[i]) * (music ? 0.35 : 0.06));
          var hh = (H * 0.035 + (heights[i] / 100) * H * 0.2 * (1 - focusK * 0.7) + ((i * 37) % 11) / 11 * H * 0.03) * (CITIES[city] ? 0.62 : 1);   // lower in front of a real city; 1.6: lower in a focus
          var s2 = i % 2 ? 1 : -1, idx = (i / 2) | 0;
          var bx = s2 > 0 ? cx + inner + idx * slot : cx - inner - idx * slot - bw;
          var tg = g.createLinearGradient(0, hz - hh, 0, hz);
          tg.addColorStop(0, rgba(mix(bg, ac, 0.16), 1)); tg.addColorStop(1, rgba(mix(bg, [0, 0, 0], 0.3), 1));
          g.fillStyle = tg; g.fillRect(bx, hz - hh, bw, hh);
          g.fillStyle = rgba(heights[i] > 90 ? COL.crit : ac, 0.85); g.fillRect(bx, hz - hh, bw, Math.max(1, H / 600));
          // windows
          var cw = Math.max(2, bw / 5), chh = Math.max(2, H / 140), cols = Math.max(1, Math.floor((bw - cw) / (cw * 1.7))), rows = Math.floor((hh - chh * 2) / (chh * 2.2));
          for (var ry = 0; ry < rows; ry++) for (var cxi = 0; cxi < cols; cxi++) {
            var hsh = ((i * 7919 + ry * 104729 + cxi * 1299709) % 1000) / 1000;
            if (hsh < ram / 100 * 0.75) {
              var flick = ((t * 0.07 + hsh * 13) % 1) > 0.985 ? 0.2 : 1;
              g.fillStyle = rgba(hsh < 0.12 ? a2 : hsh < 0.5 ? ac : COL.warn, (0.35 + hsh * 0.5) * flick);
              g.fillRect(bx + cw * 0.7 + cxi * cw * 1.7, hz - hh + chh * 2 + ry * chh * 2.2, cw, chh);
            }
          }
          if (heights[i] > 90 && (t * 2 % 1) < 0.5) { g.fillStyle = rgba(COL.crit, 1); g.fillRect(bx + bw / 2 - 1, hz - hh - H / 90, 2, 2); }
          if (ivyOn && i % 3 === 0 && uptimeS > 3600) ivyOn2(t, bx + (s2 > 0 ? 0 : bw), hz, hh * Math.min(0.85, uptimeS / 86400 / 7 * (0.6 + (i % 5) * 0.1)), i);   // 1.6: the ivy of your uptime
        }
        drawFriends(t, hz);   // 1.6: your friends' cities, in front of the far towers
      }

      if (kind === "sphere") drawSphere(cx, cy, R, t);
      drawDrones(t, hz);
      // floor: the grid runs toward you as fast as the CPU works (and with the bass)
      var speed = paused || kind === "calm" || reduced ? 0 : 0.08 + cpu / 160 + bass * 0.9;
      var off = (t * speed) % 1;
      g.lineWidth = Math.max(1, H / 1080);
      for (k = 0; k < (kind === "noir" ? 0 : 18); k++) {
        var p = (k + off) / 18, yh = hz + (H - hz) * Math.pow(p, 2.2);
        g.strokeStyle = rgba(ac, 0.05 + 0.45 * p); g.beginPath(); g.moveTo(0, yh); g.lineTo(W, yh); g.stroke();
      }
      for (k = kind === "noir" ? 25 : -24; k <= 24; k++) {
        g.strokeStyle = rgba(ac, 0.18 - Math.abs(k) * 0.005);
        g.beginPath(); g.moveTo(cx + k * W * 0.012, hz); g.lineTo(cx + k * W * 0.11, H); g.stroke();
      }
      drawRiver(t, hz);
      drawNet(now, t, hz);
      drawCars(t, hz);
      drawGrowth(t, hz);    // 1.3: in front, on the floor: your district, the train line, the quake waves
      drawTrains(t, hz);
      drawCar(t, hz);
      drawTower(t, hz);
      drawParcels(t, hz);
      drawRobots(t, hz);
      drawCandles(t, hz);
      drawPlanes(t, hz);   // in front of the sphere, so a plane is never hidden behind it
      drawBanners(now, hz);
      drawBalloons(now, hz);
      drawQuakeWaves(now, hz);
      drawTsunami(now, hz);
      // reflection of the core on the floor
      var rf = g.createRadialGradient(cx, hz, 0, cx, hz, R * 1.6);
      rf.addColorStop(0, rgba(a2, 0.22 + 0.2 * beatEnv)); rf.addColorStop(1, rgba(a2, 0));
      g.fillStyle = rf; g.fillRect(cx - R * 1.6, hz, R * 3.2, R * 1.2);

      // the horizon is your pulse: every beat leaves the core and runs to both edges
      var alive = [];
      var path = [];
      for (x = 0; x <= W; x += 3) {
        var dx = Math.abs(x - cx) / W, yv = 0;
        for (var si = 0; si < spikes.length; si++) {
          var sp2 = spikes[si], age = (now - sp2.born) / 1000, d = age * 0.32;
          var fade = Math.max(0, 1 - age / 1.8);
          if (fade <= 0) continue;
          yv += ecg(dx - d) * sp2.amp * fade;
        }
        if (music) { var bi = Math.min(audio.length - 1, (dx * 2 * audio.length) | 0); yv += (audio[bi] || 0) / 100 * 0.5 * (1 - dx); }
        path.push(hz - 1 - yv * H * 0.06);
      }
      for (i = 0; i < spikes.length; i++) if ((now - spikes[i].born) / 1000 < 1.8) alive.push(spikes[i]);
      spikes = alive;
      var lc = alertEnv > 0.05 && alertCol ? mix(ac, alertCol, Math.min(1, alertEnv * 1.5)) : ac;
      [[7, 0.06], [3.5, 0.16], [1.6, 0.95]].forEach(function (pass) {
        g.beginPath();
        for (var j = 0; j < path.length; j++) { if (j) g.lineTo(j * 3, path[j]); else g.moveTo(0, path[j]); }
        g.lineWidth = pass[0] * H / 1080 * 1.6; g.strokeStyle = rgba(lc, pass[1]); g.stroke();
      });
      } else { spikes = []; drawPlanes(t, hz); drawBanners(now, hz); drawBalloons(now, hz); }

      // weather from the real sky
      drawAir(t, hz);
      var nDrops = precipCount(), hail = weatherNow && (weatherNow.code === 96 || weatherNow.code === 99);
      if (wx === "rain" || wx === "storm") {
        g.strokeStyle = rgba(COL.fg, 0.22); g.lineWidth = 1; g.beginPath();
        for (i = 0; i < nDrops; i++) {
          var dr = drops[i], dxw = H * (0.004 + 0.02 * windX) * dr[2]; dr[1] += H * 0.022 * dr[2]; dr[0] += windX ? dxw - H * 0.004 * dr[2] : -H * 0.004 * dr[2];
          if (dr[1] > H || dr[0] < -W * 0.1 || dr[0] > W * 1.1) { dr[1] = -10; dr[0] = Math.random() * W * 1.2 - W * 0.1; }
          var sl = windX ? -windX * 0.9 : 0.22; g.moveTo(dr[0], dr[1]); g.lineTo(dr[0] + H * 0.018 * dr[2] * sl, dr[1] - H * 0.018 * dr[2]);
        }
        g.stroke();
      } else if (wx === "snow") {
        g.fillStyle = rgba(COL.fg, 0.6);
        for (i = 0; i < nDrops; i++) {
          var sn = drops[i]; sn[1] += H * 0.0018 * sn[2]; sn[0] += Math.sin(t + i) * 0.3 + windX * H * 0.005 * sn[2];
          if (sn[1] > H || sn[0] < -5 || sn[0] > W + 5) { sn[1] = sn[1] > H ? -5 : Math.random() * H; sn[0] = sn[0] < -5 ? W : sn[0] > W + 5 ? 0 : Math.random() * W; }
          g.fillRect(sn[0], sn[1], 2, 2);
        }
      } else if (wx === "fog") {
        var fgz = g.createLinearGradient(0, hz - H * 0.15, 0, hz + H * 0.1);
        fgz.addColorStop(0, rgba(COL.fg, 0)); fgz.addColorStop(0.6, rgba(COL.fg, 0.08)); fgz.addColorStop(1, rgba(COL.fg, 0));
        g.fillStyle = fgz; g.fillRect(0, hz - H * 0.15, W, H * 0.25);
      }
      if (hail) {   // hail: white pellets that bounce on the ground
        g.fillStyle = rgba(COL.fg, 0.85);
        for (i = 0; i < 40; i++) { var hh = drops[i]; var hy = (hh[1] * 3 + t * H * 0.9 * hh[2]) % (H - hz + H * 0.2); var yy = hy < H * 0.2 ? hy * 4 : hz + (hy - H * 0.2) * 0.2; g.fillRect((hh[0] * 1.3) % W, Math.min(H - 2, yy), 2.5, 2.5); }
      }
      drawWind(t);
      if (flash > 0.02) { g.fillStyle = rgba(COL.fg, flash * 0.16); g.fillRect(0, 0, W, H); flash *= 0.8; }
      drawFireworks();
      drawLiftoffs(t, hz);
      if (kind === "noir" && noirBoard && !wpOn) drawSeason(now, t, hz, noirBoard.x, noirBoard.y + noirBoard.h * 0.43); else drawSeason(now, t, hz, cx, cy);
      drawBoltsNet(t, hz);
      if (kind === "noir" && !wpOn) drawNoirCity(t, hz);
      if (kind === "hacker" && !wpOn) drawHacker(t, hz, cx);
      if (noc && noc.incident && !shareMode) { g.fillStyle = rgba(COL.crit, 0.07 + (reduced ? 0 : 0.05 * Math.sin(now / 400))); g.fillRect(0, 0, W, H); }
      drawTerm(hz);
      // an alert: a shockwave from the core
      if (alertEnv > 0.02 && alertCol) {
        var ar = R * (1 + (1 - alertEnv) * 6);
        g.beginPath(); g.ellipse(cx, hz, ar, ar * 0.3, 0, 0, Math.PI * 2); g.strokeStyle = rgba(alertCol, alertEnv * 0.8); g.lineWidth = 2; g.stroke();
        alertEnv *= 0.975;
      }
      if (shk) g.setTransform(1, 0, 0, 1, 0, 0);
    }

    // a PC that cannot keep up (no GPU, software rendering) gets fewer frames instead of a busy CPU
    var cap = 60, slow = 0, gap = 0, frameId = null;
    // frame pacing: how long a frame takes to reach the screen vs how long it takes to draw (omarchy-pulse perf)
    var pf = { n: 0, cost: 0, gap: 0, max: 0, t: 0, prev: 0 }, pbound = false, pbAt = 0, probe = false, probeAt = 0;
    function perfReport(now) {
      var n = Math.max(1, pf.n), avgCost = pf.cost / n, avgGap = pf.n > 1 ? pf.gap / (pf.n - 1) : 0;
      var want = probe ? 60 : Math.min(Math.max(fps, 1), cap);
      // most of the time is spent after draw(): copying and presenting the frame, not drawing it
      pbound = pf.n > 3 && avgGap > 80 && avgGap > 1.4 * 1000 / want && avgCost < avgGap / 8;
      if (probe) { /* measuring: no limits */ }
      else if (pbound) { cap = Math.max(2, Math.min(cap, Math.round(450 / avgGap))); pbAt = now; }   // fewer frames: the CPU calms down
      else if (pbAt && now - pbAt > 60000) { cap = 60; pbAt = 0; }   // try full speed again now and then
      var body = { mon: MONKEY, scene: kind, fps: +(pf.n / ((now - pf.t) / 1000)).toFixed(1), want: want, cap: cap,
        draw: +avgCost.toFixed(1), gap: +avgGap.toFixed(0), maxGap: +pf.max.toFixed(0), bound: pbound, probe: probe && pf.t > probeAt + 1500,
        w: Math.round(innerWidth * (window.devicePixelRatio || 1)), h: Math.round(innerHeight * (window.devicePixelRatio || 1)),
        canvas: W + "x" + H, scenePlugin: plug.on ? { name: plug.name, draw: plug.perf && plug.perf.draw, worst: plug.perf && plug.perf.worst, error: plug.err } : null };
      pf = { n: 0, cost: 0, gap: 0, max: 0, t: now, prev: 0 };
      try { fetch("/api/perf?token=" + encodeURIComponent(TOKEN), { method: "POST", body: JSON.stringify(body) }).catch(function () {}); } catch (e) {}
    }
    function frame(now) {
      frameId = null;
      if (covered) return;
      frameId = requestAnimationFrame(frame);
      if (paused && last) return;
      var want = probe ? 60 : Math.min(Math.max(fps, (parts.length || rockets.length || liftoffs.length || music) && !paused ? 24 : 0), cap);
      if (simpleMon && !probe) want = Math.min(want, 12);   // 1.6: a calm, light second screen
      if (now - last < 1000 / want - 2) return;
      if (last) gap = gap * 0.9 + (now - last) * 0.1;
      last = now;
      beat(now);
      var a = performance.now();
      try { draw(now); } catch (e) { if (window.PULSE_DEBUG && !frame._err) { frame._err = 1; console.error("draw: " + e + " " + (e.stack || "")); } }   // never stop the wallpaper for one bad frame
      var cost = performance.now() - a;
      glowSync(now);
      if (!probe && (cost > 22 || gap > 1.7 * 1000 / want) && want > 6) {
        if (++slow > 60) {
          if (quality === "auto" && qAuto > 0.5) { qAuto = 0.5; qSlowAt = now; resize(); }   // first, fewer pixels
          else cap = Math.max(6, Math.round(want * 0.7));
          slow = 0; gap = 0;
        }
      }
      else slow = Math.max(0, slow - 1);
      if (!pf.t) pf.t = now;
      pf.n++; pf.cost += cost;
      if (pf.prev) { var fg = now - pf.prev; pf.gap += fg; if (fg > pf.max) pf.max = fg; }
      pf.prev = now;
      if (now - pf.t > 5000) perfReport(now);
      snapshot(now);
    }

    return {
      start: function () { if (!COL.bg) applyTheme(null); resize(); addEventListener("resize", resize); frameId = requestAnimationFrame(frame); },
      reset: reset,
      audio: function (a) { audio = a || []; audioT = performance.now(); },
      cursor: function (x, y) {
        if (cursorXY && Math.abs(cursorXY[0] - x) < 1 && Math.abs(cursorXY[1] - y) < 1) return;
        cursorXY = [x, y]; cursorAt = performance.now();
      },
      fx: function (f) {
        if (f.kind === "shooting") { shooting.push({ x: Math.random() * W * 0.6, y: H * 0.05, vx: W * 0.012, vy: H * 0.006, life: 1 }); return; }
        var tnow = (performance.now() - t0) / 1000;
        if (f.kind === "push" || f.kind === "commit") {   // a tower of the Git city lights up
          var nm = String(f.text || "").split(" · ")[0]; gitFlash[nm] = tnow;
          if (f.kind === "push") { var gp = gitPos[nm]; if (gp && fxOn) burst(gp[0], gp[1], COL.accent, COL.fg, 70, H * 0.006); fxText(f.text); }
          return;
        }
        if (f.kind === "iss") { shooting.push({ x: W * 0.1, y: H * 0.08, vx: W * 0.01, vy: H * 0.002, life: 1 }); fxText(f.text); return; }
        if (f.kind === "flare") { flareAt = performance.now(); fxText(f.text); return; }
        if (f.kind === "build") { buildAt = tnow; fxText(f.text); return; }
        if (f.kind === "train") { sound.chime([659, 523, 440]); fxText(f.text); return; }
        if (f.kind === "banner") { banner(f.text); return; }
        if (f.kind === "lightning") { zapNow(); fxText(f.text); return; }
        if (f.kind === "tip") { balloons.push({ t: performance.now(), text: f.text || tipsList[0] || "SUPER + K · Show key bindings", dir: 1 }); return; }
        if (f.kind === "summary" || f.kind === "starlink") { if (f.kind === "starlink") sound.chime([880, 1175]); fxText(f.text); return; }
        if (f.kind === "car") { carAt = tnow; carText = f.text; if (!f.small) fxText(f.text); return; }
        if (f.kind === "doorbell") { ringAt = performance.now(); fxText(f.text); return; }
        if (f.kind === "quake") { quakes.push({ t: performance.now(), mag: f.mag || 5, az: f.az == null ? 90 : f.az, near: !!f.near });
          sound.rumble(Math.min(1, (f.mag || 5) / 8));
          if (f.tsunami) tsunamis.push({ t: performance.now() + 1500, az: f.az == null ? 90 : f.az, near: !!f.near });
          fxText(f.text); return; }
        if (f.kind === "launch") { var nmL = String(f.text || "").split(" · liftoff")[0]; liftoffs.push({ t: tnow, name: nmL, big: /starship/i.test(nmL), trail: [] }); fxText(f.text); return; }
        if (fxOn || f.kind !== "burst") fireworks(!!f.small, f.kind);
        if (f.text) fxText(f.text);
      },
      banner: function (txt) { banner(txt || bannerText || "OMARCHY"); },
      shoot: function () { shooting.push({ x: W * (0.1 + Math.random() * 0.5), y: H * (0.04 + Math.random() * 0.12), vx: W * (0.008 + Math.random() * 0.006), vy: H * 0.005, life: 1 }); },
      setCovered: function (value) {
        if (covered === value) return;
        covered = value;
        document.body.classList.toggle("suspended", covered);
        if (plug.frame && plug.frame.contentWindow) plug.frame.contentWindow.postMessage({ pulse: "pause", on: covered }, "*");
        if (covered && frameId !== null) { cancelAnimationFrame(frameId); frameId = null; }
        else if (!covered && frameId === null) { last = 0; gap = 0; frameId = requestAnimationFrame(frame); }
      },
      mode: function (m, st) {
        var ui = st.ui || {};
        kind = window.PULSE_SCENE || Q.get("scene") || ui.scene || "sphere";
        pluginSync(kind, st);
        wallpaperSync(ui.wallpaper);
        musicSync(ui.musicCover);
        reduced = ui.motion === "reduced";
        var sys = st.sys || {};
        cpu = sys.cpu || 0; cores = sys.cores || []; ram = sys.ram || 0; net = (sys.rx || 0) + (sys.tx || 0);
        var w = st.weather;
        wx = Q.get("wx") || (w && !w.error ? wxKind(w.code) : "clear"); day = w ? w.day : 0;
        typing = Object.keys(typed).some(function (id) { return typed[id].shown < typed[id].full.length; });
        ledStyle = (window.PULSE_SPHERE || Q.get("sphere") || ui.sphere) === "led";
        agents = st.agents || []; face = ui.face || "agents"; fxOn = ui.fireworks !== false; focus = st.focus || null;
        if (away && !st.away) wakeAt = performance.now();
        away = !!st.away; lan = st.lan || []; carsOn = ui.cars !== false; eyesOn = ui.eyes !== false; privacyOn = !!st.privacy;
        if (!!st.perfProbe !== probe) {   // omarchy-pulse perf --probe: measure with no limits, from a clean start
          probe = !!st.perfProbe; probeAt = performance.now(); cap = 60; slow = 0; pbAt = 0;
          pf = { n: 0, cost: 0, gap: 0, max: 0, t: 0, prev: 0 };
        }
        growth = st.growth || null; planes = st.sky || []; trains = st.calendar || [];
        if (st.ring && !ringOn) ringAt = performance.now();
        ringOn = !!st.ring; car = st.car || null;
        robots = st.robots || []; bannerText = ui.banner != null ? ui.banner : ""; bannerEvery = +ui.bannerEvery || 0;
        agentsAs = ui.agentsAs || "drones";
        if (agentsAs !== "drones") {   // [pulse] agents_as = "robots" or "both": your agents walk on the floor too
          robots = agents.filter(function (a) { return a.state !== "gone"; }).map(function (a) {
            return { id: "a" + a.name, name: a.name, bot: true, busy: a.state === "working",
                     health: a.state === "failed" ? "bad" : a.state === "waiting" ? "restart" : "ok" };
          }).concat(robots).slice(0, 12);
        }
        hackerStyle = ui.hackerStyle === "noir" ? "noir" : "phosphor";
        var nw16 = (st.updates && st.updates.news) || [];
        if (nw16.length && nw16[0] !== newsSeen) { newsSeen = nw16[0]; if (!privacyOn && PRIMARY) balloons.push({ t: performance.now(), text: "read the news before you update", dir: 1, warn: true }); }   // 1.6: the warning in the sky, once
        hubT = +st.t || 0; hubAt = performance.now();
        tower = st.tower || []; friends = st.friends || []; friendsAt = performance.now(); uptimeS = +(sys.uptime || 0); ivyOn = ui.ivy !== false; parcels = st.updates || null;
        simpleMon = !PRIMARY && ui.secondary === "simple"; document.body.classList.toggle("simplemon", simpleMon);
        tipsList = st.tips || []; tipsEvery = ui.tipsEvery != null ? +ui.tipsEvery : 40; meetingOn = !!st.meeting; wledOn = !!ui.wled;
        pnet = st.net || null; netBolts = st.bolts || []; term = st.term || []; noc = st.noc || null;
        document.body.classList.toggle("noc", !!(noc && noc.on));
        nocView(noc, ui);
        season = st.season || {}; expiry = st.expiry || []; energy = st.energy || null;
        infra = (st.widgets || []).filter(function (w) { return (w.level === "crit" || w.level === "warn") && w.id !== "weather"; }).slice(0, 4);
        infraOn = ui.infraWeather !== false;
        night = st.night || null; starlink = st.starlink || { sats: [] }; storms = st.storms || null; air = st.air || null;
        soundCfg = ui.sound || null; sound.update(soundCfg, !!(m && m.fullscreen) || away);
        var door = (st.widgets || []).filter(function (x) { return x.id === "doorbell" && x.data && x.data.frame; })[0];
        if (door && ringOn && door.data.frame !== (doorImg && doorImg._f)) { var di = new Image(); di._f = door.data.frame; di.onload = function () { doorImg = di; }; di.src = door.data.src + "?token=" + encodeURIComponent(TOKEN) + "&f=" + door.data.frame; }
        if (!ringOn) doorImg = null;
        tlEvery = st.timelapse ? (st.timelapseEvery || 180) * 1000 : 0; skyAuto = !!ui.skylineAuto;
        gitRepos = st.git || []; space = st.space || {}; heartBpm = st.heart || null; listenSt = st.listen || null; speakingOn = !!st.speaking;
        deskEmpty = !(m && m.windows > 0);
        weatherNow = w && !w.error ? w : null; windFrom(weatherNow); sunOn = ui.sun !== false;
        var bat = st.sys && st.sys.bat, onBat = !!(bat && bat.status === "Discharging");
        var bigScreen = innerWidth * innerHeight * Math.pow(window.devicePixelRatio || 1, 2) > 2560 * 1440 * 1.05;
        var nq = ui.quality || "auto", nAuto = (onBat || bigScreen || (qSlowAt && performance.now() - qSlowAt < 600000)) ? 0.5 : 1;
        if (nq !== quality || (nq === "auto" && nAuto !== qAuto)) { quality = nq; qAuto = nAuto; resize(); }   // 10 minutes after slow frames, try sharp again
        var ph = sunOn ? sunPhase(w) : "night";
        city = window.PULSE_CITY != null ? window.PULSE_CITY : Q.get("city") || ui.skyline || "";
        if (ph + kind + city !== phaseKey) { phaseKey = ph + kind + city; phase = ph; paintStatic(); }
        var nw = (st.widgets || []).filter(function (x) { return x.id === "news" || x.id === "ai"; })[0];
        newsText = nw && nw.data ? (nw.data.items && nw.data.items[0] ? nw.data.items[0].title : String(nw.data.text || "").split("\n")[0]) : "";
        var cam = (st.widgets || []).filter(function (x) { return x.type === "image" && x.data && x.data.frame; })[0];
        if (cam && !st.privacy && cam.data.frame !== (camImg && camImg._f)) { var im = new Image(); im._f = cam.data.frame; im.onload = function () { camImg = im; }; im.src = cam.data.src + "?token=" + encodeURIComponent(TOKEN) + "&f=" + cam.data.frame; }
        if (st.privacy) camImg = null;
        var dd = new Date(); clockStr = (dd.getHours() < 10 ? "0" : "") + dd.getHours() + ":" + (dd.getMinutes() < 10 ? "0" : "") + dd.getMinutes();
        var full = !!(m && m.fullscreen), busy = !!(m && m.windows > 0 && ui.dim_with_windows !== false);
        var bat = sys.bat && sys.bat.status === "Discharging";
        fps = full ? 1 : busy ? 8 : Math.min(+Q.get("fps") || +ui.fps || 20, bat ? 12 : 60);
        if (kind === "calm") fps = Math.min(fps, 10);
        if (away) fps = Math.min(fps, 8);   // nobody is looking: save power
        if (wpOn && wpMode === "panels") fps = Math.min(fps, 2);   // a still picture: almost no work
        paused = full;
        document.body.classList.toggle("busy", busy);
        document.body.classList.toggle("calm", kind === "calm");
      },
      alert: function (level) { alertEnv = 1; alertCol = level === "crit" ? COL.crit : COL.warn; spikes.push({ born: performance.now(), amp: 1.6, col: alertCol }); }
    };
  })();

  // ------------------------------------------------------------------ data loop
  var fails = 0;
  function getJSON(path) {
    return fetch(path + "?token=" + encodeURIComponent(TOKEN), { cache: "no-store" }).then(function (r) {
      if (!r.ok) throw new Error("Pulse hub unavailable");
      return r.json();
    });
  }
  function isCovered(data, enabled) {
    var m = (data.mons || {})[MONKEY];
    return enabled !== false && !!(m && m.covered);
  }
  function poll() {
    var request = covered ? getJSON("/api/visibility").then(function (v) {
      if (isCovered(v, v.pause_when_covered)) return null;
      return getJSON("/api/state");
    }) : getJSON("/api/state");
    request.then(function (j) {
      fails = 0;
      if (!j) return;
      if (isCovered(j, (j.ui || {}).pause_when_covered)) { Scene.setCovered(true); return; }
      S = j; stateAt = performance.now();
      try { render(); Scene.setCovered(false); } catch (e) { console.error(e); }
    }).catch(function () { fails++; }).then(function () { setTimeout(poll, fails ? Math.min(10000, 1000 * fails) : 1000); });
  }
  // music bars and effects as they happen
  function live() {
    if (!window.EventSource) return;
    var es = new EventSource("/api/live?fx=-1&token=" + encodeURIComponent(TOKEN));
    es.onmessage = function (e) {
      try {
        var d = JSON.parse(e.data);
        if (d.a) Scene.audio(d.a);
        if (d.c) Scene.cursor(d.c[0] - MX, d.c[1] - MY);   // the pointer, in this monitor's pixels
        (d.fx || []).forEach(function (f) { if (!covered) Scene.fx(f); });
      } catch (err) { /* ignore one bad message */ }
    };
  }
  addEventListener("mousemove", function (e) { Scene.cursor(e.clientX, e.clientY); }, { passive: true });
  if (Q.get("only") === "scene") document.body.classList.add("sceneonly");
  if (Q.get("dbg")) document.body.classList.add("dbg-" + Q.get("dbg"));
  applyTheme(null);
  Scene.start();
  clock();
  poll();
  live();
})();
