/* OmarchyTV v2.14 Living screensaver: weather, GitHub, the PC's notifications and X told in ASCII boxes,
 * Omarchy terminal style, next to the screensaver art. One file for LG webOS / Samsung Tizen (app page) and
 * Android TV (saver WebView, a copy in launcher assets/saver, kept identical by webos/tests/living.test.js).
 * Data: {weather:{t, unit, code, desc, min, max, place}, github:[{repo, what, who, text, at}],
 *        notes:[{app, title, body, at}], x:[{kind, user, text, at}], xMode, xError}. ES5. */
(function (root) {
  "use strict";
  // The fonts the apps bundle: 'CaskaydiaMono' (@font-face in the LG/Samsung index.html) and 'mono' (the Android saver
  // page). The names used before matched neither, so the Live screen fell back to the TV's own monospace, whose box
  // and block glyphs can be wider than 0.6em: lines overflowed past the screen edge whatever the margins (#119).
  var FONT_STACK = "CaskaydiaMono, mono, 'CaskaydiaMono Nerd Font', 'Caskaydia Mono', monospace";
  var ART = {
    sun:    ["    \\   /   ", "     .-.    ", "  - (   ) - ", "     `-'    ", "    /   \\   "],
    partly: ["   \\  /     ", " _ /\"\".-.   ", "   \\_(   ). ", "   /(___(__)", "            "],
    cloud:  ["            ", "     .--.   ", "  .-(    ). ", " (___.__)__)", "            "],
    rain:   ["     .-.    ", "    (   ).  ", "   (___(__) ", "    ' ' ' ' ", "   ' ' ' '  "],
    snow:   ["     .-.    ", "    (   ).  ", "   (___(__) ", "    *  *  * ", "   *  *  *  "],
    storm:  ["     .-.    ", "    (   ).  ", "   (___(__) ", "    /_ /_   ", "     /  /   "],
    fog:    ["            ", " _ - _ - _ -", "  _ - _ - _ ", " _ - _ - _ -", "            "]
  };
  function artFor(code) {
    code = Number(code) || 0;
    if (code === 0) return "sun";
    if (code <= 2) return "partly";
    if (code === 45 || code === 48) return "fog";
    if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return "rain";
    if ((code >= 71 && code <= 77) || code === 85 || code === 86) return "snow";
    if (code >= 95) return "storm";
    return "cloud";
  }
  function ago(iso, now) {
    var t = Date.parse(iso || "");
    if (!isFinite(t)) return "";
    var s = Math.max(0, Math.floor(((now || Date.now()) - t) / 1000));
    if (s < 60) return "now";
    if (s < 3600) return Math.floor(s / 60) + "m";
    if (s < 86400) return Math.floor(s / 3600) + "h";
    return s < 86400 * 365 ? Math.floor(s / 86400) + "d" : "";
  }
  function str(v) { return String(v === undefined || v === null ? "" : v).replace(/[\u0000-\u001f\u007f]/g, " "); }
  function fit(s, w) {
    s = str(s);
    if (s.length > w) return s.slice(0, Math.max(0, w - 1)) + "…";
    while (s.length < w) s += " ";
    return s;
  }
  function wrap(text, width, max) {
    var words = str(text).split(/\s+/), out = [], cur = "";
    for (var i = 0; i < words.length; i++) {
      var w = words[i];
      if (!w) continue;
      while (w.length > width) { if (cur) { out.push(cur); cur = ""; } out.push(w.slice(0, width)); w = w.slice(width); }
      if (!cur) cur = w;
      else if (cur.length + 1 + w.length <= width) cur += " " + w;
      else { out.push(cur); cur = w; }
    }
    if (cur) out.push(cur);
    if (out.length > max) { out = out.slice(0, max); out[max - 1] = fit(out[max - 1], width - 1) + "…"; }
    return out;
  }
  /** ┌─ title ──┐ / │ line │ / └──┘ at width (outer). */
  function box(title, lines, width) {
    var inner = width - 4, top = "┌─ " + fit(title, Math.min(str(title).length, width - 6)) + " ";
    while (top.length < width - 1) top += "─";
    var out = [top + "┐"];
    for (var i = 0; i < lines.length; i++) out.push("│ " + fit(lines[i], inner) + " │");
    var bot = "└";
    while (bot.length < width - 1) bot += "─";
    out.push(bot + "┘");
    return out;
  }
  function right(a, b, w) {   // "a ……… b" on one line
    a = str(a); b = str(b);
    var room = w - b.length - 1;
    return fit(a, Math.max(1, room)) + " " + b;
  }
  /** The scene as lines (pure: the tests and both TVs call it). opts: {width, labels, now, maxItems}. */
  function render(d, opts) {
    d = d || {}; opts = opts || {};
    var W = opts.width || 44, L = opts.labels || {}, now = opts.now || Date.now(), n = opts.maxItems || 3;
    var inner = W - 4, out = [];
    var w = d.weather;
    if (w && w.t !== undefined && w.t !== null) {
      var a = ART[artFor(w.code)], info = [str(w.t) + "°" + str(w.unit || ""), str(w.desc), ""];
      if (w.min !== undefined && w.max !== undefined) info[2] = "↓" + w.min + "° ↑" + w.max + "°";
      var lines = [];
      for (var i = 0; i < a.length; i++) lines.push(a[i] + " " + (i >= 1 && i <= 3 ? info[i - 1] : ""));
      out = out.concat(box((L.weather || "weather") + (w.place ? " · " + w.place : ""), lines, W));
    }
    function section(title, items, fmt) {
      if (!items || !items.length) return;
      var lines = [];
      for (var i = 0; i < items.length && i < n; i++) {
        lines = lines.concat(fmt(items[i]));
        if (i < Math.min(items.length, n) - 1) lines.push("");
      }
      out = out.concat(box(title, lines, W));
    }
    section(L.notes || "notifications · pc", d.notes, function (x) {
      return [right("▸ " + str(x.app) + (x.title ? " · " + str(x.title) : ""), ago(x.at, now), inner)]
        .concat(wrap(x.body, inner - 2, 2).map(function (l) { return "  " + l; }));
    });
    var xTitle = L.x || "x · mentions";
    if (d.xMode === "api" && d.xError) {
      out = out.concat(box(xTitle, wrap(L[d.xError.split(":")[0]] || d.xError, inner, 2), W));
    } else {
      section(xTitle, d.x, function (x) {
        return [right(str(x.user), ago(x.at, now), inner)].concat(wrap(x.text, inner - 2, 3).map(function (l) { return "  " + l; }));
      });
    }
    section(L.github || "github", d.github, function (g) {
      var head = right("● " + str(g.what) + "  " + str(g.repo).replace(/^[^\/]+\//, ""), ago(g.at, now), inner);
      return [head].concat(g.text ? wrap(g.text, inner - 2, 1).map(function (l) { return "  " + l; }) : []);
    });
    return out;
  }
  /** The newest item's key: a change means "something just arrived" (the panel blinks it). */
  function newest(d) {
    d = d || {};
    var a = (d.notes || [])[0], b = (d.x || [])[0];
    return (a ? "n" + a.at + a.title + a.body : "") + "|" + (b ? "x" + b.at + b.user + b.text : "");
  }
  var GLYPHS = "\u2588\u2593\u2592\u2591/\\|<>*+#=-_";
  /** tte-style "decrypt": the first p (0..1) of the non-space characters are real, the rest random glyphs. */
  function scramble(line, p, rnd) {
    rnd = rnd || Math.random;
    var out = "", n = 0, total = line.replace(/\s/g, "").length, keep = Math.floor(total * Math.max(0, Math.min(1, p)));
    for (var i = 0; i < line.length; i++) {
      var ch = line.charAt(i);
      if (/\s/.test(ch) || /[\u2500-\u257f]/.test(ch)) { out += ch; continue; }
      out += n < keep ? ch : GLYPHS.charAt(Math.floor(rnd() * GLYPHS.length));
      n++;
    }
    return out;
  }
  /**
   * Shows the scene on the right of host; update(data) as often as you like. Omarchy-style motion: the boxes are
   * typed in line by line the first time, and every line that changes (a notification, a mention, a new push)
   * "decrypts" from random glyphs like the tte screensaver effects, then glows for a few seconds.
   */
  function mount(host, opts) {
    opts = opts || {};
    var doc = host.ownerDocument || document;
    var pre = doc.createElement("pre");
    pre.className = "otv-living";
    var s = pre.style;
    // Sized from the host's own box, never from vw/vh: on webOS the whole UI is a 1920x1080 stage scaled with
    // transform, so viewport units come out at the wrong scale (the widget looked huge and misplaced on the TV).
    var hw = host.clientWidth || 1920, hh = host.clientHeight || 1080;
    s.position = "absolute"; s.right = Math.round(hw * 0.025) + "px"; s.top = Math.round(hh * 0.04) + "px"; s.margin = "0"; s.zIndex = "5";
    s.fontFamily = opts.font || FONT_STACK;
    s.fontSize = opts.fontSize || Math.max(10, Math.round(hw * 0.0125)) + "px"; s.lineHeight = "1.22"; s.whiteSpace = "pre";
    s.color = opts.color || "#c0caf5"; s.background = "rgba(0,0,0,.55)"; s.padding = ".6em .8em";
    s.textShadow = "0 0 6px " + (opts.accent || "#7aa2f7");
    s.transition = "color .4s";
    host.appendChild(pre);
    var data = null, lines = [], prevText = {}, born = {}, t0 = 0, last = null, blinkUntil = 0, timer = null;
    var TYPE_MS = 45, DECRYPT_MS = 900;
    function same(l) { return l.replace(/(now|\d+[mhd]) *\u2502$/, "\u2502").replace(/ +\u2502$/, "\u2502"); }   // "3m" -> "4m" is not news
    function frame() {
      timer = setTimeout(frame, busy() ? 60 : 350);
      if (!data) return;
      var now = Date.now(), shown = Math.floor((now - t0) / TYPE_MS), out = [];
      for (var i = 0; i < lines.length && i <= shown; i++) {
        var b = born[i];
        out.push(b && now - b < DECRYPT_MS ? scramble(lines[i], (now - b) / DECRYPT_MS) : lines[i]);
      }
      pre.textContent = out.join("\n");
      pre.style.display = lines.length ? "block" : "none";
      var hot = now < blinkUntil && Math.floor(now / 350) % 2 === 0;
      pre.style.color = hot ? (opts.accent || "#7aa2f7") : (opts.color || "#c0caf5");
    }
    function busy() {
      var now = Date.now();
      if (now - t0 < lines.length * TYPE_MS) return true;
      for (var k in born) if (now - born[k] < DECRYPT_MS) return true;
      return now < blinkUntil;
    }
    frame();
    return {
      update: function (d) {
        var k = newest(d), first = data === null, now = Date.now();
        if (last !== null && k !== last && k !== "|") blinkUntil = now + 4000;
        last = k; data = d;
        var next = render(d, { width: opts.width || 44, labels: opts.labels, maxItems: opts.maxItems || 3 });
        if (first) t0 = now;
        else {
          for (var i = 0; i < next.length; i++) {   // a line that is new or different decrypts in
            if (!prevText[same(next[i])] && /[^\s\u2500-\u257f]/.test(next[i]) && !/^\u2502 *\u2502$/.test(next[i])) born[i] = now;
          }
        }
        prevText = {};
        for (var j = 0; j < next.length; j++) prevText[same(next[j])] = true;
        lines = next;
      },
      destroy: function () { clearTimeout(timer); timer = null; data = null; if (pre.parentNode) pre.parentNode.removeChild(pre); }
    };
  }
  // ---- v2.14 Live screensaver: a whole screen of its own, Omarchy terminal style ------------------------------
  // Big block clock, an animated ASCII sky, the PC's notifications, X, GitHub and a ticker; nothing else under it.
  var DIGITS = {
    "0": ["███", "█ █", "█ █", "█ █", "███"],
    "1": [" █ ", "██ ", " █ ", " █ ", "███"],
    "2": ["███", "  █", "███", "█  ", "███"],
    "3": ["███", "  █", " ██", "  █", "███"],
    "4": ["█ █", "█ █", "███", "  █", "  █"],
    "5": ["███", "█  ", "███", "  █", "███"],
    "6": ["███", "█  ", "███", "█ █", "███"],
    "7": ["███", "  █", "  █", " █ ", " █ "],
    "8": ["███", "█ █", "███", "█ █", "███"],
    "9": ["███", "█ █", "███", "  █", "███"],
    ":": [" ", "▄", " ", "▀", " "]
  };
  /** "21:07" as 5 rows of block digits (each digit 3 cells wide, doubled to 6 so it reads from the sofa). */
  function bigClock(hhmm) {
    var rows = ["", "", "", "", ""];
    for (var i = 0; i < hhmm.length; i++) {
      var g = DIGITS[hhmm.charAt(i)] || DIGITS["0"];
      for (var r = 0; r < 5; r++) rows[r] += g[r].replace(/./g, function (c) { return c + c; }) + "  ";
    }
    return rows;
  }
  /** The animated sky, 24 x 8, frame t (0, 1, 2 …): rain and snow fall, the sun's rays turn, clouds drift. */
  function sky(code, t) {
    var k = artFor(code), W = 24, rows = [], i, r;
    function blank() { var a = []; for (var x = 0; x < 8; x++) a.push(fit("", W)); return a; }
    function put(a, y, x, str) { if (y < 0 || y >= a.length) return; var l = a[y]; a[y] = (l.slice(0, x) + str + l.slice(x + str.length)).slice(0, W); }
    rows = blank();
    var drift = (t >> 2) % 4 < 2 ? 0 : 1;
    if (k === "sun" || k === "partly") {
      var rays = t % 2 ? ["  \\  |  /  ", "    .-.    ", " --(   )-- ", "    `-'    ", "  /  |  \\  "]
                       : ["   \\   /   ", "    .-.    ", "  -(   )-  ", "    `-'    ", "   /   \\   "];
      for (r = 0; r < 5; r++) put(rows, r + 1, k === "sun" ? 6 : 1, rays[r]);
      if (k === "partly") { put(rows, 4, 9 + drift, "  .--.     "); put(rows, 5, 9 + drift, ".-(    ).  "); put(rows, 6, 9 + drift, "(___.__)__)"); }
      return rows;
    }
    if (k === "fog") {
      for (r = 1; r < 7; r++) put(rows, r, ((r + t) % 3), r % 2 ? "_ - _ - _ - _ - _ - _" : " - _ - _ - _ - _ - _ -");
      return rows;
    }
    put(rows, 0, 5 + drift, "   .--.      ");
    put(rows, 1, 5 + drift, ".-(    ).    ");
    put(rows, 2, 5 + drift, "(___.__)__)  ");
    if (k === "cloud") { put(rows, 4, 1 - drift + 1, "  .-.      .--.  "); put(rows, 5, 2 - drift, " (   ).  (    ). "); return rows; }
    var drop = k === "snow" ? "*" : "'";
    for (r = 3; r < 8; r++) for (i = 0; i < 6; i++) {
      var x = 5 + i * 3 + ((r + i) % 2), y = r;
      if ((y + i + t) % 3 === 0) put(rows, y, x, k === "rain" || k === "storm" ? (r % 2 ? "'" : ",") : drop);
    }
    if (k === "storm" && t % 12 < 2) { put(rows, 3, 10, "_/"); put(rows, 4, 9, "/_ "); put(rows, 5, 10, "/"); }
    return rows;
  }
  /** A 20 x 9 ASCII radar: two rings, a sweep that turns with t, blips that light up just behind it. */
  function radar(t) {
    var W = 20, H = 9, cx = 9.5, cy = 4, rows = [], a = (t * 0.35) % (Math.PI * 2);
    var blips = [[3, 2], [14, 6], [16, 2], [6, 6]];
    for (var y = 0; y < H; y++) {
      var r = "";
      for (var x = 0; x < W; x++) {
        var dx = (x - cx) / 2.1, dy = y - cy, dist = Math.sqrt(dx * dx + dy * dy), ang = Math.atan2(dy, dx);
        var diff = (a - ang + Math.PI * 4) % (Math.PI * 2), ch = " ";
        if (dist <= 4.3 && (Math.abs(dist - 4) < 0.35 || Math.abs(dist - 2) < 0.3)) ch = "·";
        if (dist <= 4.3 && diff < 0.2) ch = "▓";
        else if (dist <= 4.3 && diff < 0.5) ch = "░";
        for (var k = 0; k < blips.length; k++) if (blips[k][0] === x && blips[k][1] === y) {
          var ba = Math.atan2(y - cy, (x - cx) / 2.1), bd = (a - ba + Math.PI * 4) % (Math.PI * 2);
          if (bd < 1.8) ch = bd < 0.6 ? "◉" : "○";
        }
        if (x === 9 && y === 4) ch = "+";
        r += ch;
      }
      rows.push(r);
    }
    return rows;
  }
  function pad(lines, h) { var o = lines.slice(0, h); while (o.length < h) o.push(""); return o; }
  /**
   * The Live screen as lines (pure): cols x rows characters. o: {cols, rows, t (frame), now (ms), clock "21:07",
   * date "lun 2 nov", user, host, labels}. Left: block clock, sky + weather, GitHub. Right: notifications, X.
   * Last two rows: a ticker of the newest items and a Waybar-like status line.
   */
  function scene(d, o) {
    d = d || {}; o = o || {};
    var C = o.cols || 112, R = o.rows || 32, L = o.labels || {}, now = o.now || Date.now(), t = o.t || 0;
    var LW = 42, GAP = 3, RW = C - LW - GAP, inner = RW - 4;
    var head = (o.user || "user") + "@" + (o.host || "omarchytv") + ":~$ omarchy-live";
    var out = [fit(head + (t % 4 < 2 ? "█" : " "), C - (o.date || "").length) + (o.date || ""), fit("", C)];
    var colL = [], colR = [];
    var clk = bigClock(o.clock || "00:00");
    for (var i = 0; i < clk.length; i++) colL.push("  " + clk[i]);
    colL.push("");
    var w = d.weather;
    var radarOn = !!(d.radar && d.radar.img);
    if (w && w.t !== undefined && w.t !== null) {
      var art = radarOn ? pad([], 8).map(function () { return fit("", 24); }) : sky(w.code, t), info = ["", str(w.t) + "°" + str(w.unit || ""), str(w.desc), "", w.min !== undefined ? "↓" + w.min + "° ↑" + w.max + "°" : "", "", "", ""];
      var wl = [];
      for (i = 0; i < art.length; i++) wl.push(art[i] + " " + (info[i] || ""));
      colL = colL.concat(box((L.weather || "weather") + (w.place ? " · " + w.place : "") + (radarOn ? " · omastorm" : ""), wl, LW));
    }
    function items(list, n, fmt) {
      var lines = [];
      for (var i = 0; list && i < list.length && i < n; i++) { lines = lines.concat(fmt(list[i])); if (i < Math.min(list.length, n) - 1) lines.push(""); }
      return lines;
    }
    var gh = items(d.github, 3, function (g) {
      return [right("● " + str(g.what) + "  " + str(g.repo).replace(/^[^\/]+\//, ""), ago(g.at, now), LW - 4)]
        .concat(g.text ? wrap(g.text, LW - 6, 1).map(function (l) { return "  " + l; }) : []);
    });
    if (gh.length) colL = colL.concat(box(L.github || "github", gh, LW));
    var notes = items(d.notes, 4, function (x) {
      return [right("▸ " + str(x.app) + (x.title ? " · " + str(x.title) : ""), ago(x.at, now), inner)]
        .concat(wrap(x.body, inner - 2, 2).map(function (l) { return "  " + l; }));
    });
    colR = colR.concat(box(L.notes || "notifications · pc", notes.length ? notes : [L.waiting || "…"], RW));
    var xs;
    if (d.xMode === "api" && d.xError) xs = wrap(L[d.xError.split(":")[0]] || d.xError, inner, 2);
    else xs = items(d.x, 3, function (x) {
      return [right(str(x.user), ago(x.at, now), inner)].concat(wrap(x.text, inner - 2, 3).map(function (l) { return "  " + l; }));
    });
    if (d.xMode && d.xMode !== "off") colR = colR.concat(box(L.x || "x · mentions", xs.length ? xs : [L.waiting || "…"], RW));
    // The PC, live: CPU and RAM bars, plus an ASCII radar that never stops turning (the screen is alive).
    var pc = d.pc, bw = Math.max(10, RW - 46), rad = radar(t), pcl = [];
    function meter(label, v) {
      var n = Math.round(Math.max(0, Math.min(100, v)) * bw / 100), b = "";
      for (var q = 0; q < bw; q++) b += q < n ? "█" : "░";
      return fit(label, 5) + b + " " + fit(String(Math.round(v)) + "%", 4);
    }
    pcl.push(pc && typeof pc.cpu === "number" ? meter("cpu", pc.cpu) : fit("cpu", 5) + "…");
    pcl.push(pc && typeof pc.ram === "number" ? meter("ram", pc.ram) : fit("ram", 5) + "…");
    pcl.push("");
    pcl.push((pc && pc.host ? "● " + str(pc.host) : "○ pc") + "  ·  live " + "▂▄▆█".slice(0, 1 + t % 4));
    var radLines = [];
    for (i = 0; i < rad.length; i++) radLines.push(fit(pcl[i] || "", RW - 4 - 22) + "  " + rad[i]);
    colR = colR.concat(box(L.pc || "pc", radLines, RW));
    var body = R - 4;
    colL = pad(colL, body); colR = pad(colR, body);
    for (i = 0; i < body; i++) out.push(fit(colL[i], LW) + fit("", GAP) + fit(colR[i], RW));
    // Ticker: the newest notification / mention / push, scrolling.
    var news = [];
    (d.notes || []).slice(0, 3).forEach(function (x) { news.push("▸ " + str(x.app) + ": " + str(x.title) + " " + str(x.body)); });
    (d.x || []).slice(0, 2).forEach(function (x) { news.push("@ " + str(x.user) + ": " + str(x.text)); });
    (d.github || []).slice(0, 2).forEach(function (g) { news.push("● " + str(g.what) + " " + str(g.repo) + " " + str(g.text)); });
    var tape = news.length ? news.join("   ·   ") + "   ·   " : "";
    var tick = "";
    if (tape) { var off = t % tape.length; tick = (tape + tape).slice(off, off + C); }
    out.push(fit(tick, C));
    var stat = "● live  ·  " + (d.notes ? d.notes.length : 0) + " ▸  ·  " + (d.x ? d.x.length : 0) + " @  ·  x " + (d.xMode || "off");
    out.push(fit(stat, C));
    return out;
  }
  /** One Live line as HTML: box titles, bullets and @handles in the theme accent; clock blocks glow; meters lit. */
  function htmlLine(l, clockRow) {
    var e = l.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    if (clockRow) return e.replace(/([▀-▟]+)/g, "<b class=\"c\">$1</b>");
    e = e.replace(/(┌─ )([^─]+?)( ─)/, "$1<b class=\"a\">$2</b>$3");
    e = e.replace(/(█+)(░+)/g, "<b class=\"a\">$1</b><b class=\"d\">$2</b>");
    e = e.replace(/(▸|●|★|◉|▓)/g, "<b class=\"a\">$1</b>").replace(/(@[A-Za-z0-9_]{1,15})/g, "<b class=\"a2\">$1</b>");
    return e;
  }
  /**
   * The Live screensaver, full screen in host: update(data) with the feed, destroy() to leave. Over the top on
   * purpose, readable first: the boxes sit on dark glass panels; behind them, theme-coloured glyph rain, a
   * synthwave grid rolling toward you at the bottom and a refresh sweep; a glowing block clock; a turning radar;
   * and every new notification or mention crashes in as a full-width INCOMING banner (shake + decrypt) before it
   * settles in its box.
   */
  function fullscreen(host, opts) {
    opts = opts || {};
    var doc = host.ownerDocument || document;
    var COLS = 112, ROWS = 32, LW = 42, GAP = 3, acc = opts.accent || "#7aa2f7", acc2 = opts.accent2 || acc, fg = opts.color || "#c0caf5";
    var fs = "24px";
    var font = opts.font || FONT_STACK;
    var wrapEl = doc.createElement("div"), term = doc.createElement("div"), pre = doc.createElement("pre"), st = doc.createElement("style");
    var cv = doc.createElement("canvas"), sweep = doc.createElement("div"), ban = doc.createElement("div");
    var panL = doc.createElement("i"), panR = doc.createElement("i");
    st.textContent = ".otv-live{position:absolute;left:0;top:0;right:0;bottom:0;background:" + (opts.bg || "#000") + ";overflow:hidden;z-index:6}" +
      ".otv-live canvas{position:absolute;left:0;top:0;width:100%;height:100%}" +
      ".otv-live .sw{position:absolute;left:0;right:0;height:12%;background:linear-gradient(to bottom,rgba(255,255,255,0),rgba(255,255,255,.03),rgba(255,255,255,0));pointer-events:none}" +
      ".otv-live .term{position:absolute;left:5%;top:5.5%;font-family:" + font + ";font-size:" + fs + ";line-height:1.18;transition:transform 2s}" +
      ".otv-live .term i{position:absolute;display:block;background:rgba(0,0,0,.74);border-radius:.6em;box-shadow:0 0 2.2em rgba(0,0,0,.9)}" +
      ".otv-live pre{position:relative;margin:0;white-space:pre;font:inherit;line-height:inherit;color:" + fg + ";text-shadow:0 0 .35em " + acc + "44}" +
      ".otv-live b{font-weight:normal}.otv-live .a{color:" + acc + "}.otv-live .a2{color:" + acc2 + "}.otv-live .d{opacity:.3}" +
      ".otv-live .c{color:" + acc + ";text-shadow:0 0 .5em " + acc + ",0 0 1.4em " + acc + "88}" +
      ".otv-live .ban{position:absolute;left:0;right:0;top:75.5%;padding:.4em 2.8em;background:rgba(0,0,0,.9);border-top:.19em solid " + acc +
      ";border-bottom:.19em solid " + acc + ";box-shadow:0 0 2.6em " + acc + "99;display:none;white-space:pre-wrap;color:" + fg + ";font-family:" + font + ";font-size:" + fs + "}" +
      ".otv-live .ban .h{font-size:1.66em;color:" + acc + ";letter-spacing:.2em;text-shadow:0 0 1.1em " + acc + ";white-space:nowrap;overflow:hidden}" +
      ".otv-live .ban .t{letter-spacing:0;color:" + fg + ";margin-left:1.2em;text-shadow:none}" +
      ".otv-live .ban .m{font-size:1.2em;margin-top:.15em;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}";
    wrapEl.className = "otv-live"; term.className = "term"; sweep.className = "sw"; ban.className = "ban";
    // The font size comes from the host's own box (not the window): 112 columns must fit 95% of the usable width and
    // 32 rows 92% of the usable height, so the scene lands in the same place on a scaled 1920x1080 stage and on any
    // panel. "Usable" leaves SAFE_X/SAFE_Y of the panel free on every side: a TV with overscan (the 32" LG cuts the
    // outer edge of the picture) then clips only empty margin, never a column of the clock or a box border.
    var SAFE_X = 0.05, SAFE_Y = 0.055;
    var boxW = 0, boxH = 0, fitK = 1, lastW = 0, lastH = 0;
    // After a frame is drawn: if the text is wider or taller than its box (a font whose glyphs are not 0.6em, a TV
    // engine that rounds differently), shrink the font by the measured ratio. Only shrinks; a resize starts again.
    function fitMeasured() {
      var pw = pre.scrollWidth, ph = pre.scrollHeight;
      if (!(pw > 0 && ph > 0 && boxW > 0)) return;
      var r = Math.min(boxW / pw, boxH / ph);
      if (r < 0.995) { fitK *= r; fitFont(); }
      centre();
    }
    // v2.15: the scene sits in the middle of the screen (it hugged the left edge once the font had shrunk to fit),
    // never closer to an edge than the safe margins.
    function centre() {
      var w = wrapEl.clientWidth || host.clientWidth || 1920, h = wrapEl.clientHeight || host.clientHeight || 1080;
      var pw = pre.scrollWidth, ph = pre.scrollHeight;
      if (!(pw > 0 && ph > 0)) return;
      term.style.left = Math.max(SAFE_X * w, (w - pw) / 2).toFixed(1) + "px";
      term.style.top = Math.max(SAFE_Y * h, (h - ph) / 2).toFixed(1) + "px";
    }
    function fitFont() {
      var w = wrapEl.clientWidth || host.clientWidth || 1920, h = wrapEl.clientHeight || host.clientHeight || 1080;
      var iw = w * (1 - 2 * SAFE_X), ih = h * (1 - 2 * SAFE_Y);
      var f = Math.min(95 / COLS / 0.6 * iw / 100, 92 / ROWS / 1.18 * ih / 100);
      if (!(f > 0)) return;
      // A new box (resize) or a newly loaded font starts the measurement again.
      if (w !== lastW || h !== lastH) { lastW = w; lastH = h; fitK = 1; lastHtml = ""; }
      boxW = iw * 0.95; boxH = ih * 0.92; f *= fitK;
      term.style.fontSize = f.toFixed(2) + "px";
      term.style.left = (SAFE_X * 100).toFixed(2) + "%";
      term.style.top = (SAFE_Y * 100).toFixed(2) + "%";
      ban.style.fontSize = f.toFixed(2) + "px";
    }
    // Dark glass under the two columns (in character units, so they match the boxes at any size).
    panL.style.cssText = "left:-.6ch;top:6.6em;width:" + (LW + 1.2) + "ch;height:" + (ROWS - 10.2) + "em";
    panR.style.cssText = "left:" + (LW + GAP - 0.6) + "ch;top:1.6em;width:" + (COLS - LW - GAP + 1.2) + "ch;height:" + (ROWS - 5.2) + "em";
    // Omastorm's real radar (a snapshot of its window from the PC) sits in the weather box, where the sky is drawn.
    var radarImg = doc.createElement("img");
    radarImg.style.cssText = "position:absolute;display:none;left:2ch;top:" + (9 * 1.18).toFixed(2) + "em;width:24ch;height:" + (8 * 1.18).toFixed(2) +
      "em;object-fit:cover;border-radius:.3em;box-shadow:0 0 .8em " + acc + "66;z-index:1";
    term.appendChild(panL); term.appendChild(panR); term.appendChild(pre); term.appendChild(radarImg);
    // v2.14 "QR code of OmarchyTV" (setting, Live by default): small, in the theme's own colours, in the gap between
    // the clock and the right column, so it covers nothing; a phone pointed at it opens the project's page.
    if (opts.qr && root.OmarchyQR) {
      var qrc = root.OmarchyQR.canvas(doc, opts.qr, 140, "M", { light: fg, dark: opts.bg || "#000" });
      if (qrc) {
        qrc.style.cssText = "position:absolute;left:37.3ch;top:2.4em;width:6.4ch;height:6.4ch;border-radius:.25em;opacity:.88;" +
          "box-shadow:0 0 .8em " + acc + "55;image-rendering:pixelated";
        // Position the label box in the term's own ch/em (same as the QR). Put the small type on an
        // inner span: otherwise font-size:.5em halves ch/em on the box itself and the old *2 override
        // drifted once the bundled mono replaced the TV fallback (#119).
        var qrl = doc.createElement("div");
        qrl.style.cssText = "position:absolute;left:37.3ch;width:6.4ch;top:calc(2.6em + 6.4ch);text-align:center;opacity:.55;color:" + fg;
        var qrlT = doc.createElement("span");
        qrlT.textContent = "OmarchyTV";
        qrlT.style.cssText = "font-size:.62em;letter-spacing:.04em";   // v2.15: readable at 720p too
        qrl.appendChild(qrlT);
        term.appendChild(qrc); term.appendChild(qrl);
      }
    }
    wrapEl.appendChild(st); wrapEl.appendChild(cv); wrapEl.appendChild(sweep); wrapEl.appendChild(term); wrapEl.appendChild(ban);
    host.appendChild(wrapEl);
    fitFont();
    if (typeof root.addEventListener === "function") {
      root.addEventListener("resize", fitFont);
      if (typeof root.ResizeObserver === "function") {
        try { new root.ResizeObserver(fitFont).observe(wrapEl); } catch (e) {}
      }
      // The bundled font may arrive after the first frame: measure again once it is loaded (also on TVs without
      // ResizeObserver, such as webOS 4.x).
      try { if (doc.fonts && doc.fonts.ready) doc.fonts.ready.then(function () { lastW = 0; fitFont(); }); } catch (e) {}
    }

    // Behind the glass: glyph rain everywhere, a synthwave grid rolling in at the bottom. 12 fps, small cost.
    var ctx = cv.getContext && cv.getContext("2d"), ncol = 0, drops = [], GL = "▓▒░/\\|<>*+#=01", gt = 0;
    function backdrop() {
      if (!ctx) return;
      // Half resolution (CSS scales it up): a quarter of the pixels, the same look.
      var w = Math.round((wrapEl.clientWidth || 1920) / 2), h = Math.round((wrapEl.clientHeight || 1080) / 2), cw = Math.max(8, Math.round(w / 90));
      if (cv.width !== w || cv.height !== h) { cv.width = w; cv.height = h; ncol = Math.ceil(w / cw); drops = []; for (var i = 0; i < ncol; i++) drops.push(Math.random() * -40); }
      ctx.globalAlpha = 1;
      ctx.fillStyle = "rgba(0,0,0,0.18)"; ctx.fillRect(0, 0, w, h);
      ctx.font = Math.round(cw * 0.85) + "px monospace";
      for (var c = 0; c < ncol && !lite; c++) {
        var y = drops[c] * cw;
        ctx.globalAlpha = Math.random() < 0.05 ? 0.5 : 0.2;
        ctx.fillStyle = Math.random() < 0.05 ? fg : acc;
        ctx.fillText(GL.charAt(Math.floor(Math.random() * GL.length)), c * cw, y);
        drops[c] += 0.45 + (c % 3) * 0.22;
        if (y > h * 0.78 && Math.random() > 0.93) drops[c] = Math.random() * -20;
      }
      // Synthwave floor: horizon at 80%, lines rush toward the viewer.
      var hz = h * 0.8, cx = w / 2;
      ctx.clearRect(0, hz, w, h - hz);
      var g = ctx.createLinearGradient(0, hz, 0, h);
      g.addColorStop(0, "rgba(0,0,0,0.9)"); g.addColorStop(1, "rgba(0,0,0,0.6)");
      ctx.fillStyle = g; ctx.fillRect(0, hz, w, h - hz);
      ctx.strokeStyle = acc; ctx.lineWidth = 0.8;
      gt = (gt + 0.04) % 1;
      for (var k = 0; k < 9; k++) {
        var f = (k + gt) / 9, yy = hz + (h - hz) * f * f;
        ctx.globalAlpha = 0.08 + 0.32 * f;
        ctx.beginPath(); ctx.moveTo(0, yy); ctx.lineTo(w, yy); ctx.stroke();
      }
      for (var v = -12; v <= 12; v++) {
        ctx.globalAlpha = 0.22;
        ctx.beginPath(); ctx.moveTo(cx + v * w * 0.012, hz); ctx.lineTo(cx + v * w * 0.11, h); ctx.stroke();
      }
      ctx.globalAlpha = 0.6; ctx.fillStyle = acc; ctx.fillRect(0, hz - 1, w, 2);
      ctx.globalAlpha = 1;
    }

    var data = {}, prev = {}, born = {}, t = 0, timer = null, rt = null, start = Date.now(), lastHtml = "";
    var lastKey = null, lastNote = "", banItem = null, banStart = 0, banUntil = 0;
    // "New text" = letters only: times, percentages, meters, the radar and the signal bars move on their own.
    function same(l) { return l.replace(/(now|\d+[mhd]) *│/g, "│").replace(/[0-9%·\u2580-\u259f○◉+▂▄▆]/g, ""); }
    function incoming(d) {
      var key = newest(d), n = (d.notes || [])[0], x = (d.x || [])[0];
      if (lastKey === null) { lastKey = key; lastNote = n ? n.at + n.title + n.body : ""; return; }
      if (key === lastKey || key === "|") return;
      lastKey = key;
      var nk = n ? n.at + n.title + n.body : "";
      if (n && nk !== lastNote) banItem = { h: "▸ INCOMING", t: str(n.app) + (n.title ? " · " + str(n.title) : ""), m: str(n.body) };
      else if (x) banItem = { h: "@ INCOMING", t: str(x.user), m: str(x.text) };
      lastNote = nk;
      if (banItem) { banStart = Date.now(); banUntil = banStart + 5500; }
    }
    function banner() {
      var ms = Date.now();
      if (!banItem || ms > banUntil) { ban.style.display = "none"; wrapEl.style.transform = ""; return; }
      var p = Math.min(1, (ms - banStart) / 700);
      ban.style.display = "block";
      // Over the grid at the bottom: it never covers the boxes, the notification stays readable in its box too.
      ban.innerHTML = "<div class=\"h\">" + htmlLine(scramble(banItem.h, p)) + "<span class=\"t\">" + htmlLine(scramble(banItem.t, p)) +
        "</span></div><div class=\"m\">" + htmlLine(scramble(banItem.m, Math.max(0, p * 1.4 - 0.4))) + "</div>";
      var k = ms - banStart < 600 ? 1 : 0;
      wrapEl.style.transform = k ? "translate(" + (Math.random() * 14 - 7) + "px," + (Math.random() * 10 - 5) + "px)" : "";
    }
    function frame() {
      timer = setTimeout(frame, 250);
      if (doc.hidden) return;
      t++;
      var now = new Date(), hh = now.getHours(), mm = now.getMinutes();
      var lines = scene(data, { cols: COLS, rows: ROWS, t: t, now: now.getTime(), labels: opts.labels, user: opts.user, host: opts.host,
        clock: (hh < 10 ? "0" : "") + hh + ":" + (mm < 10 ? "0" : "") + mm, date: opts.dateText ? opts.dateText(now) : now.toDateString() });
      var ms = Date.now(), typed = Math.floor((ms - start) / 30), html = [], seen = {};
      for (var i = 0; i < lines.length; i++) {
        // Each column decrypts only for its own news (a new notification on the right leaves GitHub alone).
        var whole = i < 2 || i >= lines.length - 2, parts = whole ? [lines[i]] : [lines[i].slice(0, LW + GAP), lines[i].slice(LW + GAP)], outl = "";
        for (var k = 0; k < parts.length; k++) {
          var part = parts[k], key = i + "|" + k + "|" + same(part);
          seen[key] = true;
          if (t > 4 && !whole && i > 7 && !prev[key] && !born[key] && /[A-Za-z\u00c0-\uffff]{3}/.test(same(part).replace(/[\u2500-\u257f]/g, ""))) born[key] = ms;
          var b = born[key];
          outl += b && ms - b < 900 ? scramble(part, (ms - b) / 900) : part;
        }
        html.push(htmlLine(i > typed ? "" : outl, i >= 2 && i <= 6));
      }
      prev = seen;
      var out = html.join("\n");
      if (out !== lastHtml) { pre.innerHTML = out; lastHtml = out; fitMeasured(); }   // no layout when nothing changed
      var m = Math.floor(ms / 60000) % 4;   // burn-in care: drift a few pixels every minute
      term.style.transform = "translate(" + [0, 6, 6, 0][m] + "px," + [0, 0, 6, 6][m] + "px)";
      sweep.style.top = (((ms / 40) % 130) - 15) + "%";
    }
    // Light on old TVs: if drawing the backdrop is slow, drop the rain and keep the rest (checked over 40 frames).
    var lite = !!opts.lite, slow = 0, frames = 0, vis = function () { return !doc.hidden; };
    function anim() {
      rt = setTimeout(anim, 100);
      if (!vis()) return;
      var t0 = Date.now();
      backdrop(); banner();
      if (!lite && ++frames <= 40) { slow += Date.now() - t0; if (frames === 40 && slow / 40 > 14) lite = true; }
    }
    frame(); anim();
    return {
      update: function (d) {
        data = d || {}; incoming(data);
        var img = data.radar && data.radar.img && data.weather ? String(data.radar.img) : "";
        if (img.indexOf("data:image/jpeg;base64,") !== 0) img = "";
        if (img !== radarImg.getAttribute("src")) { if (img) radarImg.src = img; else radarImg.removeAttribute("src"); }
        radarImg.style.display = img ? "block" : "none";
      },
      destroy: function () { clearTimeout(timer); clearTimeout(rt); if (wrapEl.parentNode) wrapEl.parentNode.removeChild(wrapEl); }
    };
  }
  var api = { ART: ART, artFor: artFor, ago: ago, wrap: wrap, box: box, render: render, newest: newest, scramble: scramble, mount: mount,
    bigClock: bigClock, sky: sky, radar: radar, scene: scene, htmlLine: htmlLine, fullscreen: fullscreen };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  root.OmarchyLiving = api;
})(typeof window !== "undefined" ? window : this);
