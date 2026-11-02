/* OmarchyTV v2.14 Living screensaver: weather, GitHub, the PC's notifications and X told in ASCII boxes,
 * Omarchy terminal style, next to the screensaver art. One file for LG webOS / Samsung Tizen (app page) and
 * Android TV (saver WebView, a copy in launcher assets/saver, kept identical by webos/tests/living.test.js).
 * Data: {weather:{t, unit, code, desc, min, max, place}, github:[{repo, what, who, text, at}],
 *        notes:[{app, title, body, at}], x:[{kind, user, text, at}], xMode, xError}. ES5. */
(function (root) {
  "use strict";
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
    s.position = "absolute"; s.right = "2.5vw"; s.top = "4vh"; s.margin = "0"; s.zIndex = "5";
    s.fontFamily = opts.font || "'CaskaydiaMono Nerd Font', 'Caskaydia Mono', monospace";
    s.fontSize = opts.fontSize || "1.25vw"; s.lineHeight = "1.22"; s.whiteSpace = "pre";
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
  var api = { ART: ART, artFor: artFor, ago: ago, wrap: wrap, box: box, render: render, newest: newest, scramble: scramble, mount: mount };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  root.OmarchyLiving = api;
})(typeof window !== "undefined" ? window : this);
