/* OmarchyTV Arcade (v2.14): three games of our own in an Omarchy-style arcade cabinet, on Android TV, LG and Samsung.
 * Everything is text: an 80x25 grid (the old PC text screen) drawn as 25 rows of coloured spans, in the active theme's
 * colours. No canvas, no images, no CSS filters or variables: only rows whose text changed are rewritten, at most 30
 * times a second, so it runs on old TVs too. The CRT faults of V-HOLD are plain CSS transforms on the picture rows.
 * Keys: arrows and OK only (they never leave the launcher on any TV), Back pauses / goes back.
 * Host: OmarchyArcade.open(el, opts) -> { key(name), destroy() }; keyName(event) turns a key event into a name.
 * Games register themselves with OmarchyArcade.register({ id, title, color, art, desc, create(api) }). ES5. */
(function (root) {
  "use strict";
  var NAME = "OmarchyTV Arcade";   // the arcade's name, the same in every language
  var W = 80, H = 25, FPS_MS = 32, STORE = "omarchytv.arcade.v1";
  // colour slots: 0 fg, 1 accent, 2 accent2, 3 dim, 4 red, 5 green, 6 yellow, 7 cyan, 8 bright, 9 blue, 10 inverse, 11 bar
  var C = { FG: 0, ACC: 1, ACC2: 2, DIM: 3, RED: 4, GREEN: 5, YEL: 6, CYAN: 7, BRIGHT: 8, BLUE: 9, INV: 10, BAR: 11 };
  var games = [], byId = {};
  function register(g) { if (!byId[g.id]) { games.push(g); byId[g.id] = g; } }

  // ---- text helpers ----
  function wide(c) {   // East Asian wide characters take two cells in a monospace grid
    var n = c.charCodeAt(0);
    return (n >= 0x1100 && n <= 0x115f) || (n >= 0x2e80 && n <= 0xa4cf) || (n >= 0xac00 && n <= 0xd7a3) ||
      (n >= 0xf900 && n <= 0xfaff) || (n >= 0xfe30 && n <= 0xfe4f) || (n >= 0xff00 && n <= 0xff60) || (n >= 0xffe0 && n <= 0xffe6);
  }
  function textWidth(s) { var w = 0; for (var i = 0; i < s.length; i++) w += wide(s.charAt(i)) ? 2 : 1; return w; }
  function pad(n, len) { var s = String(Math.max(0, Math.floor(n))); while (s.length < len) s = "0" + s; return s; }
  function esc(c) { return c === "&" ? "&amp;" : c === "<" ? "&lt;" : c === ">" ? "&gt;" : c; }

  // ---- block font for titles (5 rows) ----
  var FONT = {
    A: [" ## ", "#  #", "####", "#  #", "#  #"], B: ["### ", "#  #", "### ", "#  #", "### "],
    C: [" ###", "#   ", "#   ", "#   ", " ###"], D: ["### ", "#  #", "#  #", "#  #", "### "],
    E: ["####", "#   ", "### ", "#   ", "####"], F: ["####", "#   ", "### ", "#   ", "#   "],
    G: [" ###", "#   ", "# ##", "#  #", " ###"], H: ["#  #", "#  #", "####", "#  #", "#  #"],
    I: ["###", " # ", " # ", " # ", "###"], K: ["#  #", "# # ", "##  ", "# # ", "#  #"],
    L: ["#   ", "#   ", "#   ", "#   ", "####"], M: ["#   #", "## ##", "# # #", "#   #", "#   #"],
    N: ["#  #", "## #", "# ##", "#  #", "#  #"], O: [" ## ", "#  #", "#  #", "#  #", " ## "],
    P: ["### ", "#  #", "### ", "#   ", "#   "], R: ["### ", "#  #", "### ", "# # ", "#  #"],
    S: [" ###", "#   ", " ## ", "   #", "### "], T: ["###", " # ", " # ", " # ", " # "],
    U: ["#  #", "#  #", "#  #", "#  #", " ## "], V: ["#   #", "#   #", " # # ", " # # ", "  #  "],
    W: ["#   #", "#   #", "# # #", "## ##", "#   #"], Y: ["#   #", " # # ", "  #  ", "  #  ", "  #  "],
    "-": ["   ", "   ", "###", "   ", "   "], " ": ["  ", "  ", "  ", "  ", "  "], "!": ["#", "#", "#", " ", "#"]
  };
  function bigWidth(text) {
    var w = 0;
    for (var i = 0; i < text.length; i++) { var g = FONT[text.charAt(i)] || FONT[" "]; w += g[0].length + (i ? 1 : 0); }
    return w;
  }

  // ---- the grid ----
  function Grid(w, h) { this.w = w; this.h = h; this.ch = []; this.co = []; this.clear(); }
  Grid.prototype.clear = function () {
    for (var i = 0; i < this.w * this.h; i++) { this.ch[i] = " "; this.co[i] = 0; }
  };
  /** Writes s at x,y in colour c; with clear=false spaces in s leave what is under them. */
  Grid.prototype.put = function (x, y, s, c, clear) {
    x = Math.floor(x); y = Math.floor(y);
    if (y < 0 || y >= this.h || !s) return;
    for (var k = 0; k < s.length; k++) {
      var ch = s.charAt(k);
      if (wide(ch)) {
        if (x >= 0 && x + 1 < this.w) { this.ch[y * this.w + x] = ch; this.co[y * this.w + x] = c || 0; this.ch[y * this.w + x + 1] = ""; }
        x += 2; continue;
      }
      if (x >= 0 && x < this.w && (ch !== " " || clear !== false)) { this.ch[y * this.w + x] = ch; this.co[y * this.w + x] = c || 0; }
      x++;
    }
  };
  Grid.prototype.center = function (y, s, c) { this.put(Math.floor((this.w - textWidth(s)) / 2), y, s, c); };
  /** Puts [text, colour] parts one after another from x, gap cells apart; returns where it stopped. */
  Grid.prototype.row = function (x, y, parts, gap) {
    for (var i = 0; i < parts.length; i++) { if (!parts[i]) continue; this.put(x, y, parts[i][0], parts[i][1]); x += textWidth(parts[i][0]) + (gap || 3); }
    return x;
  };
  Grid.prototype.fill = function (x, y, w, h, ch, c) {
    var line = ""; for (var i = 0; i < w; i++) line += ch;
    for (var r = 0; r < h; r++) this.put(x, y + r, line, c);
  };
  Grid.prototype.box = function (x, y, w, h, c, style) {
    var b = style === "double" ? "╔═╗║╚╝" : style === "round" ? "╭─╮│╰╯" : style === "heavy" ? "┏━┓┃┗┛" : "┌─┐│└┘";
    var mid = ""; for (var i = 0; i < w - 2; i++) mid += b.charAt(1);
    this.put(x, y, b.charAt(0) + mid + b.charAt(2), c);
    this.put(x, y + h - 1, b.charAt(4) + mid + b.charAt(5), c);
    for (var r = 1; r < h - 1; r++) { this.put(x, y + r, b.charAt(3), c); this.put(x + w - 1, y + r, b.charAt(3), c); }
  };
  Grid.prototype.big = function (x, y, text, c) {
    for (var i = 0; i < text.length; i++) {
      var g = FONT[text.charAt(i)] || FONT[" "];
      for (var r = 0; r < 5; r++) this.put(x, y + r, g[r].replace(/#/g, "█"), c, false);
      x += g[0].length + 1;
    }
  };
  Grid.prototype.bigCenter = function (y, text, c) { this.big(Math.floor((this.w - bigWidth(text)) / 2), y, text, c); };
  Grid.prototype.rowHtml = function (y) {
    var out = "", cur = -1, run = "";
    for (var x = 0; x < this.w; x++) {
      var ch = this.ch[y * this.w + x];
      if (ch === "") continue;   // second half of a wide character
      var c = this.co[y * this.w + x];
      if (c !== cur) { if (run) out += '<span class="ga' + cur + '">' + run + "</span>"; run = ""; cur = c; }
      run += esc(ch);
    }
    if (run) out += '<span class="ga' + cur + '">' + run + "</span>";
    return out;
  };

  // ---- themes ----
  /** Accepts an omarchy_themes.json entry ({background, foreground, accent, red, ...}) or OmarchyTV's theme object. */
  function normTheme(t, name) {
    t = t || {};
    var bg = t.background || t.bg || "#1a1b26", fg = t.foreground || t.fg || "#a9b1d6";
    return {
      name: name || t.name || "tokyo-night", bg: bg, fg: fg,
      accent: t.accent || t.blue || "#7aa2f7", accent2: t.magenta || t.accent2 || t.cyan || "#bb9af7",
      dim: mix(fg, bg, 0.5), red: t.red || "#f7768e", green: t.green || "#9ece6a",
      yellow: t.yellow || "#e0af68", cyan: t.cyan || "#7dcfff", blue: t.blue || t.accent || "#7aa2f7",
      bright: t.bright_foreground || t.fgBright || fg
    };
  }
  function mix(a, b, k) {   // a..b by k, for "#rrggbb" colours
    function c(h, i) { return parseInt(h.substr(1 + i * 2, 2), 16) || 0; }
    if (!/^#[0-9a-f]{6}$/i.test(a) || !/^#[0-9a-f]{6}$/i.test(b)) return a;
    var o = "#";
    for (var i = 0; i < 3; i++) { var v = Math.round(c(a, i) * (1 - k) + c(b, i) * k); o += (v < 16 ? "0" : "") + v.toString(16); }
    return o;
  }
  function css(id, t) {
    var s = "#" + id + "{background:" + t.bg + ";color:" + t.fg + "}";
    var cols = [t.fg, t.accent, t.accent2, t.dim, t.red, t.green, t.yellow, t.cyan, t.bright, t.blue];
    for (var i = 0; i < cols.length; i++) s += "#" + id + " .ga" + i + "{color:" + cols[i] + "}";
    s += "#" + id + " .ga10{background:" + t.accent + ";color:" + t.bg + "}";
    s += "#" + id + " .ga11{background:#000;color:#000}";
    s += "#" + id + " .ga-mono span{color:" + t.dim + "!important;background:transparent!important}";
    return s;
  }

  // ---- strings ----
  function langOf(l) {
    l = String(l || "en");
    var I = root.OmarchyArcadeI18n || {};
    if (I[l]) return l;
    var base = l.split(/[-_]/)[0].toLowerCase();
    if (base === "pt") return /br/i.test(l) ? "pt-BR" : "pt-PT";
    if (base === "zh") return "zh-CN";
    if (base === "no" || base === "nn") return "nb";
    return I[base] ? base : "en";
  }

  function keyName(e) {
    var k = e.key, c = e.keyCode;
    if (k === "ArrowUp" || c === 38) return "up";
    if (k === "ArrowDown" || c === 40) return "down";
    if (k === "ArrowLeft" || c === 37) return "left";
    if (k === "ArrowRight" || c === 39) return "right";
    if (k === "Enter" || k === " " || c === 13 || c === 32) return "ok";
    if (k === "Escape" || k === "Backspace" || k === "GoBack" || c === 27 || c === 8 || c === 461 || c === 10009) return "back";
    return "";
  }

  // ---- the arcade ----
  var seq = 0;
  function open(host, opts) {
    opts = opts || {};
    var I = root.OmarchyArcadeI18n || {}, lang = langOf(opts.lang), S = I[lang] || I.en || {}, EN = I.en || {};
    function t(k) { return S[k] || EN[k] || k; }
    var id = "ga-" + (++seq), doc = host.ownerDocument || document;
    var themes = opts.themes || {};
    var home = normTheme(opts.theme, opts.themeName);
    var store = opts.storage || (function () { try { return root.localStorage; } catch (e) { return null; } })();
    var sfx = root.OmarchyArcadeSfx ? root.OmarchyArcadeSfx.create(opts.sound !== false) : null;
    function play(n, a) { if (sfx) sfx.play(n, a); }

    // DOM: root > tube > head / pic / foot rows, plus a static scanline veil
    var el = doc.createElement("div");
    el.id = id; el.className = "ga-root";
    el.style.cssText = "position:absolute;left:0;top:0;right:0;bottom:0;overflow:hidden;z-index:60;font-family:CaskaydiaMono,'Caskaydia Mono',monospace;";
    var style = doc.createElement("style");
    el.appendChild(style);
    var tube = doc.createElement("div");
    tube.style.cssText = "position:absolute;left:50%;top:50%;white-space:pre;";
    el.appendChild(tube);
    var parts = {}, rows = [], last = [];
    ["head", "pic", "foot"].forEach(function (p) {
      var d = doc.createElement("div"); d.style.cssText = "position:relative;overflow:visible;"; tube.appendChild(d); parts[p] = d;
    });
    var picWrap = doc.createElement("div");   // clips the rolling picture to its own rows
    picWrap.style.cssText = "position:relative;overflow:hidden;";
    tube.insertBefore(picWrap, parts.foot);
    picWrap.appendChild(parts.pic);
    for (var r = 0; r < H; r++) { var d = doc.createElement("div"); d.style.cssText = "height:1.2em;line-height:1.2em;"; rows.push(d); last.push(null); }
    var veil = doc.createElement("div");
    veil.style.cssText = "position:absolute;left:0;top:0;right:0;bottom:0;pointer-events:none;opacity:.18;" +
      "background:repeating-linear-gradient(to bottom,rgba(0,0,0,0) 0,rgba(0,0,0,0) 2px,rgba(0,0,0,.9) 3px,rgba(0,0,0,0) 4px);";
    el.appendChild(veil);
    host.appendChild(el);

    var palette = home;
    function setPalette(th) { palette = th; style.textContent = css(id, th); }
    setPalette(home);

    var layout = [0, H], fs = 16, charRatio = 0.6;
    function setLayout(y0, y1) {
      layout = [y0, y1];
      for (var i = 0; i < H; i++) { var p = i < y0 ? parts.head : i < y1 ? parts.pic : parts.foot; p.appendChild(rows[i]); last[i] = null; }
      picWrap.style.height = (y1 - y0) * 1.2 + "em";
    }
    setLayout(0, H);
    function fit() {
      var w = el.clientWidth || host.clientWidth || 1280, h = el.clientHeight || host.clientHeight || 720;
      var probe = doc.createElement("span");
      probe.style.cssText = "font-size:100px;visibility:hidden;position:absolute;white-space:pre;";
      probe.textContent = "MMMMMMMMMM";
      el.appendChild(probe);
      var ratio = (probe.offsetWidth || 600) / 1000;
      charRatio = ratio;
      el.removeChild(probe);
      fs = Math.max(6, Math.min(w * 0.97 / (W * ratio), h * 0.97 / (H * 1.2)));
      tube.style.fontSize = fs.toFixed(2) + "px";
      tube.style.marginLeft = (-W * ratio * fs / 2).toFixed(1) + "px";
      tube.style.marginTop = (-H * 1.2 * fs / 2).toFixed(1) + "px";
    }
    fit();
    var refit = setTimeout(fit, 600);   // the web font may arrive late
    function onResize() { fit(); }
    try { root.addEventListener("resize", onResize); } catch (e) {}

    // records: { gameId: [ {s: score, d: "dd/mm"} x5 ] }
    function records() { try { return JSON.parse(store.getItem(STORE)) || {}; } catch (e) { return {}; } }
    function hi(gid) { var l = records()[gid]; return l && l[0] ? l[0].s : 0; }
    function addRecord(gid, s) {
      var all = records(), l = all[gid] || [], d = new Date(), best = !l[0] || s > l[0].s;
      l.push({ s: Math.floor(s), d: pad(d.getDate(), 2) + "/" + pad(d.getMonth() + 1, 2) });
      l.sort(function (a, b) { return b.s - a.s; });
      all[gid] = l.slice(0, 5);
      try { store.setItem(STORE, JSON.stringify(all)); } catch (e) {}
      return best && s > 0;
    }

    // PC theme follows RICE WARS only when the host allows it; the PC's own theme comes back on exit.
    var pcTouched = false;
    function pcTheme(name) {
      if (!opts.pcTheme || typeof opts.onPcTheme !== "function") return;
      pcTouched = true;
      try { opts.onPcTheme(name); } catch (e) {}
    }

    var g = new Grid(W, H);
    var mode = "hall", sel = 0, clock = 0, modeT = 0, idle = 0, inst = null, game = null, pauseIdx = 0, newRec = false, finalScore = 0;
    var view = null, shakeT = 0;
    function api() {
      return {
        W: W, H: H, C: C, t: t, sfx: { play: play }, grid: g, home: home, themes: themes, normTheme: normTheme,
        setLayout: setLayout, setPalette: setPalette, pcTheme: pcTheme, pad: pad, textWidth: textWidth,
        shake: function (s) { shakeT = Math.max(shakeT, s || 0.3); }
      };
    }
    function go(m) { mode = m; modeT = 0; }
    function startGame() {
      showQr(false);
      game = games[sel];
      setPalette(home);
      setLayout(0, H);
      inst = game.create(api());
      go("play");
      play("start");
    }
    function toHall() {
      showQr(false);
      inst = null; view = null; setPalette(home); setLayout(0, H); go("hall"); idle = 0;
      if (pcTouched) { pcTouched = false; try { opts.onPcTheme(null); } catch (e) {} }
    }

    // ---- screens ----
    function drawMarquee() {
      var on = Math.floor(clock * 4) % 2;
      g.box(0, 0, W, 8, C.ACC, "double");
      for (var x = 2; x < W - 2; x += 4) {   // the light bulbs chase round the sign
        var lit = (x / 4 + (on ? 1 : 0)) % 2 === 0;
        g.put(x, 0, lit ? "●" : "○", lit ? C.YEL : C.DIM);
        g.put(W - 1 - x, 7, lit ? "●" : "○", lit ? C.YEL : C.DIM);
      }
      for (var y = 2; y < 7; y += 2) { var l2 = (y / 2 + (on ? 1 : 0)) % 2 === 0; g.put(0, y, l2 ? "●" : "○", l2 ? C.YEL : C.DIM); g.put(W - 1, y, l2 ? "○" : "●", l2 ? C.DIM : C.YEL); }
      g.center(1, "O M A R C H Y   T V", C.BRIGHT);
      g.bigCenter(2, "ARCADE", C.ACC);
    }
    function drawCart(x, y, gm, focused) {
      var c = focused ? C.ACC : C.DIM, w = 22;
      g.box(x, y, w, 9, c, focused ? "heavy" : "single");
      g.fill(x + 1, y + 1, w - 2, 1, "▓", focused ? gm.color || C.ACC : C.DIM);
      var title = gm.title;
      g.put(x + Math.floor((w - title.length) / 2), y + 2, title, focused ? C.BRIGHT : C.FG);
      for (var i = 0; i < gm.art.length; i++) g.put(x + Math.floor((w - gm.art[i].length) / 2), y + 3 + i, gm.art[i], focused ? gm.color || C.ACC2 : C.DIM, false);
      g.put(x + 2, y + 7, "HI " + pad(hi(gm.id), 6), focused ? C.YEL : C.DIM);
      var pins = ""; for (var p = 0; p < w - 4; p++) pins += p % 2 ? "┴" : "─";
      g.put(x + 2, y + 9, pins, c);
    }
    function drawHall() {
      drawMarquee();
      // a carousel: three cartridges in view, the chosen one in the middle; dots show where you are
      var n = games.length, show = Math.min(3, n), x0 = Math.floor((W - (show * 22 + (show - 1) * 3)) / 2);
      for (var k = 0; k < show; k++) {
        var i = show < 3 ? k : (sel - 1 + k + n) % n, mid = show < 3 ? i === sel : k === 1;
        var dy = mode === "insert" && mid ? Math.min(6, Math.floor(modeT * 14)) : 0;
        drawCart(x0 + k * 25, 9 + (mid ? 0 : 1) + dy, games[i], mid);
      }
      if (n > 3) {
        var blinkA = Math.floor(clock * 2) % 2;
        g.put(x0 - 3, 13, "◀", blinkA ? C.ACC : C.DIM);
        g.put(x0 + show * 25 - 1, 13, "▶", blinkA ? C.ACC : C.DIM);
        var dots = ""; for (var d = 0; d < n; d++) dots += (d === sel ? "●" : "○") + (d < n - 1 ? " " : "");
        if (mode === "hall") g.center(19, dots, C.DIM);
      }
      var gm = games[sel];
      if (gm && mode === "hall") {
        g.center(20, t(gm.desc[0]), C.FG);
        g.center(21, t(gm.desc[1]), C.ACC2);
      }
      var blink = Math.floor(clock * 1.6) % 2;
      g.center(22, blink ? "▶ " + t("insert_coin") + " ◀" : "▶ " + t("press_ok") + " ◀", blink ? C.YEL : C.BRIGHT);
      g.center(24, "◀ ▶ " + t("choose") + "  ·  OK " + t("play") + "  ·  ▼ " + t("hi_scores") + "  ·  " + t("back_key") + " " + t("exit"), C.DIM);
    }
    function drawScores() {
      drawMarquee();
      g.center(9, "★ " + t("hi_scores") + " ★", C.YEL);
      var all = records(), n = games.length, bw = Math.min(22, Math.floor((W - 2 - (n - 1)) / n)), x0 = Math.floor((W - (n * bw + (n - 1))) / 2);
      for (var i = 0; i < n; i++) {
        var x = x0 + i * (bw + 1), l = all[games[i].id] || [];
        g.box(x, 11, bw, 9, i === sel ? C.ACC : C.DIM, "round");
        var title = games[i].title.substr(0, bw - 2);
        g.put(x + Math.floor((bw - title.length) / 2), 11, title, games[i].color || C.ACC);
        if (!l.length) g.put(x + 2, 15, "—", C.DIM);
        for (var k = 0; k < l.length; k++) g.put(x + 2, 13 + k, (bw >= 20 ? (k + 1) + ". " : "") + pad(l[k].s, 6) + (bw >= 20 ? "  " + l[k].d : ""), k === 0 ? C.BRIGHT : C.FG);
      }
      g.center(24, t("back_key") + " / OK  ·  " + NAME, C.DIM);
    }
    function drawStatic(y0, y1, c) {
      var n = "  ░░▒▓";
      for (var y = y0; y < y1; y++) { var s = ""; for (var x = 0; x < W; x++) s += n.charAt(Math.floor(Math.random() * n.length)); g.put(0, y, s, c); }
    }
    function drawTune() {   // the channel change after the cartridge goes in
      drawStatic(0, H, C.DIM);
      g.center(12, "  CH 0" + (sel + 3) + "  ", C.INV);
    }
    function drawPause() {
      var opt = [t("resume"), t("restart"), t("quit")], w = 36, x = (W - w) / 2;
      g.fill(x, 7, w, 11, " ", C.FG);
      g.box(x, 7, w, 11, C.ACC, "double");
      g.bigCenter(8, "PAUSE", C.ACC);
      for (var i = 0; i < opt.length; i++) g.center(14 + i, (i === pauseIdx ? "▶ " : "  ") + opt[i] + (i === pauseIdx ? " ◀" : "  "), i === pauseIdx ? C.BRIGHT : C.DIM);
    }
    // Game over: the score, and next to it the record as a QR code, always there when there are points: a phone
    // pointed at it opens X with the post already written ("can you beat me?"); the player sends it, or not.
    var qrEl = null;
    function shareText() {
      return t("share_post").replace("{score}", pad(finalScore, 6)).replace("{game}", game.title);
    }
    function showQr(on) {
      if (qrEl && qrEl.parentNode) qrEl.parentNode.removeChild(qrEl);
      qrEl = null;
      if (!on || !root.OmarchyQR || finalScore <= 0) return;
      var em = 13 * 1.2;   // 13 rows tall
      qrEl = root.OmarchyQR.canvas(doc, root.OmarchyQR.shareUrl(shareText()), Math.round(em * fs * 2), "L", { light: palette.fg, dark: palette.bg });
      if (!qrEl) return;
      qrEl.style.position = "absolute";
      qrEl.style.left = ((W - 4) * charRatio - em).toFixed(2) + "em";
      qrEl.style.top = (2 * 1.2).toFixed(2) + "em";
      qrEl.style.width = qrEl.style.height = em.toFixed(2) + "em";
      qrEl.style.borderRadius = ".3em";
      qrEl.style.zIndex = "2";   // above the scanline veil, so phones read clean modules
      tube.appendChild(qrEl);
    }
    function wrap(text, width, x, y, c) {
      var words = text.split(" "), line = "";
      for (var i = 0; i < words.length; i++) {
        if (line && textWidth(line + " " + words[i]) > width) { g.put(x, y++, line, c); line = words[i]; }
        else line = line ? line + " " + words[i] : words[i];
      }
      if (line) g.put(x, y++, line, c);
      return y;
    }
    function drawOver() {
      if (finalScore <= 0 || !qrEl) {
        g.bigCenter(4, "GAME OVER", C.RED);
        g.center(11, t("score") + "  " + pad(finalScore, 6), C.BRIGHT);
        g.center(13, "HI  " + pad(hi(game.id), 6), C.YEL);
        g.center(19, "OK " + t("retry") + "  ·  " + t("back_key") + " " + t("arcade"), C.DIM);
        return;
      }
      var qx = W - 4 - Math.ceil(13 * 1.2 / charRatio);   // first column under the QR
      g.big(3, 2, "GAME", C.RED);
      g.big(3, 8, "OVER", C.RED);
      g.put(3, 15, game.title, game.color || C.ACC);
      g.put(3, 16, t("score") + "  " + pad(finalScore, 6), C.BRIGHT);
      g.put(3, 17, "HI     " + pad(hi(game.id), 6), C.YEL);
      if (newRec && Math.floor(clock * 3) % 2) g.put(3, 19, "★ " + t("new_record") + " ★", C.ACC);
      g.put(3, 22, "OK " + t("retry") + "  ·  " + t("back_key") + " " + t("arcade"), C.DIM);
      var y = wrap(t("share_x"), W - 3 - qx, qx, 16, Math.floor(clock * 2) % 2 ? C.ACC : C.BRIGHT);
      y = wrap(t("share_scan"), W - 3 - qx, qx, y + 1, C.FG);
      wrap(t("share_done"), W - 3 - qx, qx, y, C.DIM);
    }

    // ---- loop ----
    var stopped = false, lastT = 0, raf = root.requestAnimationFrame || function (f) { return setTimeout(function () { f(Date.now()); }, 16); };
    var caf = root.cancelAnimationFrame || clearTimeout, rafId = null;
    function frame(now) {
      if (stopped) return;
      rafId = raf(frame);
      now = typeof now === "number" && now > 1e6 ? now : Date.now();   // old WebKit passes nothing useful
      if (!lastT) lastT = now;
      if (now - lastT < FPS_MS) return;
      var dt = Math.min(0.1, (now - lastT) / 1000);
      lastT = now;
      if (doc.hidden) return;
      step(dt);
      render();
    }
    function step(dt) {
      clock += dt; modeT += dt;
      if (shakeT > 0) shakeT -= dt;
      if (mode === "hall") { idle += dt; if (idle > 25) { go("scores"); idle = 0; } }
      else if (mode === "scores") { if (modeT > 8) go("hall"); }
      else if (mode === "insert") { if (modeT > 0.5) { go("tune"); play("static"); } }
      else if (mode === "tune") { if (modeT > 0.45) startGame(); }
      else if (mode === "play" && inst) {
        inst.update(dt);
        if (inst.over) {
          finalScore = Math.floor(inst.score || 0);
          newRec = addRecord(game.id, finalScore);
          view = null; setLayout(0, H); setPalette(home);
          go("over");
          showQr(true);
          play(newRec ? "record" : "gameover");
        }
      }
    }
    function render() {
      g.clear();
      if (mode === "hall" || mode === "insert") drawHall();
      else if (mode === "scores") drawScores();
      else if (mode === "tune") drawTune();
      else if (mode === "play" || mode === "pause") { inst.draw(g); if (mode === "pause") drawPause(); }
      else if (mode === "over") drawOver();
      view = mode === "play" || mode === "pause" ? inst.view || null : null;
      var v = view || {}, ph = layout[1] - layout[0], roll = v.roll || 0;
      var ri = Math.floor(roll) % (ph + 1); if (ri < 0) ri += ph + 1;
      for (var y = 0; y < H; y++) {
        var html;
        if (y >= layout[0] && y < layout[1] && roll) {   // the picture rolls: rows wrap with a black bar between frames
          var src = (y - layout[0] + ri) % (ph + 1);
          html = src === ph ? '<span class="ga11">' + new Array(W + 1).join(" ") + "</span>" : g.rowHtml(layout[0] + src);
        } else html = g.rowHtml(y);
        if (html !== last[y]) { rows[y].innerHTML = html; last[y] = html; }
      }
      var tf = "";
      if (roll) tf += "translateY(" + (-(roll - Math.floor(roll)) * 1.2).toFixed(3) + "em) ";
      if (v.skew) tf += "skewX(" + v.skew.toFixed(2) + "deg) ";
      if (typeof v.scaleY === "number" && v.scaleY < 1) tf += "scaleY(" + Math.max(0.02, v.scaleY).toFixed(3) + ") ";
      if (v.rotX) tf = "perspective(60em) rotateX(" + v.rotX.toFixed(2) + "deg) " + tf;   // TILT: the board leans
      if (v.rotate) tf += "rotate(" + v.rotate.toFixed(2) + "deg) ";
      var ov = v.rotate || v.rotX ? "visible" : "hidden";
      if (picWrap._ov !== ov) { picWrap.style.overflow = ov; picWrap._ov = ov; }
      if (parts.pic._tf !== tf) { parts.pic.style.transform = parts.pic.style.webkitTransform = tf; parts.pic._tf = tf; }
      var mono = !!v.mono;
      if (parts.pic._mono !== mono) { parts.pic.className = mono ? "ga-mono" : ""; parts.pic._mono = mono; }
      var sh = shakeT > 0 ? "translate(" + ((Math.random() - 0.5) * 1.6).toFixed(2) + "em," + ((Math.random() - 0.5) * 0.8).toFixed(2) + "em)" : "";
      if (tube._sh !== sh) { tube.style.transform = tube.style.webkitTransform = sh; tube._sh = sh; }
    }
    rafId = raf(frame);
    render();

    function key(k) {
      if (!k) return false;
      idle = 0;
      if (mode === "hall") {
        if (k === "left") { sel = (sel - 1 + games.length) % games.length; play("blip"); }
        else if (k === "right") { sel = (sel + 1) % games.length; play("blip"); }
        else if (k === "down") { go("scores"); play("blip"); }
        else if (k === "ok" && games.length) { go("insert"); play("coin"); }
        else if (k === "back") { close(); }
        return true;
      }
      if (mode === "scores") { if (k === "back" || k === "ok" || k === "up") { go("hall"); play("blip"); } return true; }
      if (mode === "insert" || mode === "tune") { if (k === "back") toHall(); return true; }
      if (mode === "play") {
        if (k === "back") { go("pause"); pauseIdx = 0; play("blip"); return true; }
        inst.key(k);
        return true;
      }
      if (mode === "pause") {
        if (k === "up") pauseIdx = (pauseIdx + 2) % 3;
        else if (k === "down") pauseIdx = (pauseIdx + 1) % 3;
        else if (k === "back") { go("play"); }
        else if (k === "ok") {
          if (pauseIdx === 0) go("play");
          else if (pauseIdx === 1) startGame();
          else { if (inst && inst.score > 0) addRecord(game.id, inst.score); toHall(); }   // a run left from the pause still counts
        }
        play("blip");
        return true;
      }
      if (mode === "over") {
        if (k === "ok" && modeT > 0.8) startGame();
        else if (k === "back") toHall();
        return true;
      }
      return false;
    }
    function destroy() {
      if (stopped) return;
      stopped = true;
      try { caf(rafId); } catch (e) {}
      clearTimeout(refit);
      try { root.removeEventListener("resize", onResize); } catch (e) {}
      if (pcTouched) { pcTouched = false; try { opts.onPcTheme(null); } catch (e) {} }
      if (sfx) sfx.close();
      if (el.parentNode) el.parentNode.removeChild(el);
    }
    function close() { destroy(); if (typeof opts.onExit === "function") opts.onExit(); }

    return {
      key: key, destroy: destroy,
      pause: function () { if (mode === "play") { go("pause"); pauseIdx = 0; } },
      setSound: function (on) { if (sfx) sfx.setOn(on); },
      // for tests and the demo video
      _state: function () { return { mode: mode, sel: sel, inst: inst, grid: g, palette: palette, layout: layout }; },
      _step: function (dt) { step(dt); render(); }
    };
  }

  root.OmarchyArcade = {
    NAME: NAME, W: W, H: H, C: C, register: register, games: games, open: open, keyName: keyName,
    Grid: Grid, normTheme: normTheme, textWidth: textWidth, bigWidth: bigWidth, langOf: langOf
  };
})(typeof window !== "undefined" ? window : this);
