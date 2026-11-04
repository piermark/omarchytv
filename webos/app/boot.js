// OmarchyTV boot screen (v2.14), shared by LG webOS and Samsung Tizen (app.js) and node tests. MIT, Marco Porro.
// Same script and timing as the Android launcher (launcher/.../BootScript.java): CRT power-on, a 90s BIOS POST with
// the OTV block badge, systemd start-job spinners turning into [  OK  ] lines as Arch prints them (network and
// omarchy-link really wait for the TV's state), an auto-login
// whose prompt types `omarchy-tv`, the OMARCHY TV block wordmark (as on Home) drawn row by row, then a fade to Home.
// About 7 s, any key skips. ES5 only: old webOS/Tizen engines (see compat.js).
(function (root) {
  "use strict";

  var COLUMNS = 72, CRT_MS = 520, FLICKER_MS = 140, HOLD_MS = 900, FADE_MS = 400;
  var WORDMARK = "OMARCHY TV", BADGE = "OTV";

  // Same 5-row block font as launcher/.../BlockFont.java (a test compares them).
  var GLYPHS = {
    "O": [" #### ", "##  ##", "##  ##", "##  ##", " #### "],
    "M": ["##   ##", "### ###", "## # ##", "##   ##", "##   ##"],
    "A": [" #### ", "##  ##", "######", "##  ##", "##  ##"],
    "R": ["##### ", "##  ##", "##### ", "## ## ", "##  ##"],
    "C": [" #####", "##    ", "##    ", "##    ", " #####"],
    "H": ["##  ##", "##  ##", "######", "##  ##", "##  ##"],
    "Y": ["##  ##", "##  ##", " #### ", "  ##  ", "  ##  "],
    "T": ["######", "  ##  ", "  ##  ", "  ##  ", "  ##  "],
    "V": ["##  ##", "##  ##", "##  ##", " #### ", "  ##  "],
    " ": ["  ", "  ", "  ", "  ", "  "]
  };

  function blockRows(text) {
    var rows = ["", "", "", "", ""];
    for (var i = 0; i < text.length; i++) {
      var g = GLYPHS[text.charAt(i).toUpperCase()] || GLYPHS[" "];
      for (var r = 0; r < 5; r++) rows[r] += (i > 0 ? " " : "") + g[r];
    }
    for (var k = 0; k < 5; k++) rows[k] = rows[k].replace(/#/g, "█");
    return rows;
  }

  /** Column where the second word starts, as BlockFont.splitColumn. */
  function splitColumn(text) {
    var sp = text.indexOf(" ");
    return sp < 0 ? -1 : blockRows(text.substring(0, sp + 1))[0].length + 1;
  }

  function clean(s, def) {
    if (s === undefined || s === null) return def;
    var out = "", str = String(s);
    for (var i = 0; i < str.length; i++) {
      var c = str.charCodeAt(i);
      if (c >= 0x20 && c !== 0x7f) out += str.charAt(i);   // no control characters from config or the model name
    }
    out = out.replace(/^\s+|\s+$/g, "");
    return out ? out : def;
  }

  function fit(s) {
    return s.length <= 64 ? s : s.substring(0, 63) + "…";
  }

  var MAX_WAIT_MS = 2500, SPIN_MS = 420;

  /**
   * The script: lines [{kind, text, detail, at, anim, key, maxWait, result}], badge rows, biosEnd, logoSplit,
   * fadeAt, endAt (ms), plus the clock state. o: {version, theme, model, ramMb, display, apps, user, host, live}.
   * live(key, timedOut) returns null while unknown, else {ok, text}; with timedOut it must answer.
   */
  function build(o) {
    o = o || {};
    var version = clean(o.version, "dev"), theme = clean(o.theme, "tokyo-night"), model = clean(o.model, "Smart TV");
    var display = clean(o.display, ""), user = clean(o.user, "user"), host = clean(o.host, "omarchytv");
    var ramMb = Math.max(0, Math.floor(Number(o.ramMb) || 0));
    var apps = typeof o.apps === "number" && o.apps >= 0 ? Math.floor(o.apps) : -1;
    var lines = [], t = CRT_MS;
    function add(kind, text, detail, anim, next, key) {
      lines.push({ kind: kind, text: text, detail: detail || null, at: t, anim: anim || 0,
                   key: key || null, maxWait: key ? MAX_WAIT_MS : 0, result: null });
      t += next;
    }
    function ok(text, job, key) {
      var spin = job ? SPIN_MS : 0;
      add("ok", fit(text), job, spin, spin + 110, key);
    }

    // BIOS POST: real version, model, RAM and display mode; the ads line is the joke.
    add("post", fit("OmarchyBIOS v" + version + "  Release 2026, MIT licensed"), null, 0, 90);
    add("post", fit("Copyright (C) Omarchy TV contributors"), null, 0, 200);
    add("blank", "", null, 0, 0);
    add("post", fit("System Model   : " + model), null, 0, 160);
    if (ramMb > 0) add("memtest", fit("Memory Testing : " + ramMb + " MB OK"), null, 650, 700);
    if (display) add("post", fit("Display Mode   : " + display), null, 0, 180);
    add("post", fit("Detecting ads ............... none"), null, 0, 300);
    add("blank", "", null, 0, 0);
    add("post", fit("Press any key to skip"), null, 0, 380);
    var biosEnd = t;
    add("clear", "", null, 0, FLICKER_MS);

    // systemd, Arch style. Network and omarchy-link spin until the TV really knows (or give up).
    add("title", "Welcome to Omarchy TV!", null, 0, 220);
    add("blank", "", null, 0, 0);
    ok("Reached target Local File Systems.");
    ok("Started Network Manager.", "Network Manager", "net");
    ok("Loaded theme " + theme + ".");
    ok("Started omarchy-link.service.", "omarchy-link.service", "pc");
    ok("Started Hyprland-style workspaces 1-5.");
    if (apps >= 0) ok("Indexed " + apps + (apps === 1 ? " app." : " apps."));
    ok("Mounted /media/cinemarchy.");
    ok("Masked ads.service.", "ads.service");
    ok("Reached target Couch.");
    add("blank", "", null, 0, 100);

    // Auto-login, the prompt types omarchy-tv, the wordmark draws itself.
    add("post", fit(host + " login: " + user + " (automatic login)"), null, 0, 200);
    var cmd = "omarchy-tv", typing = cmd.length * 55;
    add("prompt", fit(user + "@" + host + ":~$ "), cmd, typing, typing + 160);
    add("blank", "", null, 0, 0);
    var rows = blockRows(WORDMARK);
    for (var r = 0; r < rows.length; r++) add("logo", rows[r], null, 0, 85);
    add("blank", "", null, 0, 0);
    add("info", fit("theme " + theme + "  \u00b7  v" + version), null, 0, 0);

    return { lines: lines, badge: blockRows(BADGE), biosEnd: biosEnd, logoSplit: splitColumn(WORDMARK),
             fadeAt: t + HOLD_MS, endAt: t + HOLD_MS + FADE_MS, live: o.live || null, held: 0, waitStart: -1 };
  }

  /**
   * Script time for realMs since the start. When a live line's spinner ends before its answer is known, the
   * script holds there (the spinner keeps turning) until live() answers or maxWait passes.
   */
  function clock(script, realMs) {
    var s = realMs - script.held;
    for (var i = 0; i < script.lines.length; i++) {
      var l = script.lines[i];
      if (!l.key || l.result) continue;
      var end = l.at + l.anim;
      if (s < end) break;
      if (script.waitStart < 0) script.waitStart = realMs;
      var timedOut = realMs - script.waitStart >= l.maxWait, st = null;
      try {
        st = script.live ? script.live(l.key, timedOut) : { ok: true, text: l.text };
      } catch (e) {   // a failing probe must never stop the boot screen
        timedOut = true;
      }
      if (!st && !timedOut) {
        script.held = realMs - end;
        return end;
      }
      l.result = st ? { ok: !!st.ok, text: fit(clean(st.text, l.text)) } : { ok: false, text: l.text };
      if (realMs - script.waitStart > 0) script.held = realMs - end;
      script.waitStart = -1;
      s = realMs - script.held;
    }
    return s;
  }

  /** Lines visible at ms ("clear" wipes everything before it). */
  function visibleAt(script, ms) {
    var shown = [];
    for (var i = 0; i < script.lines.length; i++) {
      var l = script.lines[i];
      if (l.at > ms) break;
      if (l.kind === "clear") shown = [];
      else shown.push(l);
    }
    return shown;
  }

  function crt(ms) { return Math.max(0, Math.min(1, ms / CRT_MS)); }

  /** True during the dark half of the flicker right after a "clear". */
  /**
   * v2.14 boot sound: the cues between two clock times (from exclusive, to inclusive; from < 0 = the start).
   * power (the tube switching on), beep (the POST beep with the first BIOS line), tick (the memory count),
   * zap (the screen clearing), disk (each systemd line), key (each typed character), whoosh (the wordmark).
   * Same cues as BootScript.cues on Android.
   */
  function cues(script, from, to) {
    var out = [], firstPost = true, firstLogo = true;
    if (from < 0 && to >= 0) out.push("power");
    for (var i = 0; i < script.lines.length; i++) {
      var l = script.lines[i], hit = l.at > from && l.at <= to;
      if (l.kind === "post") { if (firstPost && hit) out.push("beep"); firstPost = false; }
      else if (l.kind === "memtest" && hit) out.push("tick");
      else if (l.kind === "clear" && hit) out.push("zap");
      else if (l.kind === "ok" && hit) out.push("disk");
      else if (l.kind === "prompt" && l.detail) {
        for (var c = 0; c < l.detail.length; c++) { var at = l.at + c * 55; if (at > from && at <= to) out.push("key"); }
      } else if (l.kind === "logo") { if (firstLogo && hit) out.push("whoosh"); firstLogo = false; }
    }
    return out;
  }

  function flickering(script, ms) {
    for (var i = 0; i < script.lines.length; i++) {
      var l = script.lines[i];
      if (l.kind === "clear" && ms >= l.at && ms < l.at + FLICKER_MS) return Math.floor((ms - l.at) / 35) % 2 === 0;
    }
    return false;
  }

  function progress(l, ms) {
    if (l.anim <= 0) return 1;
    return Math.max(0, Math.min(1, (ms - l.at) / l.anim));
  }

  function typedChars(l, ms) {
    if (l.kind !== "prompt" || !l.detail) return 0;
    return Math.round(progress(l, ms) * l.detail.length);
  }

  function spinning(l, ms) { return l.kind === "ok" && !!l.detail && (progress(l, ms) < 1 || (!!l.key && !l.result)); }
  function doneText(l) { return l.result ? l.result.text : l.text; }
  function doneOk(l) { return !l.result || l.result.ok; }

  var SPIN = ["*     ", "**    ", "***   ", " ***  ", "  *** ", "   ***", "    **", "     *",
              "    **", "   ***", "  *** ", " ***  ", "***   ", "**    "];
  function spinner(ms) { return SPIN[Math.floor(Math.max(0, ms) / 60) % SPIN.length]; }

  /** The memtest line at ms: the number counts up in 64 MB steps to its final value. */
  function memText(l, ms) {
    if (l.kind !== "memtest") return l.text;
    var p = progress(l, ms);
    if (p >= 1) return l.text;
    var m = /^(.*: )(\d+)( MB)/.exec(l.text);
    if (!m) return l.text;
    return m[1] + (Math.floor(Math.floor(Number(m[2]) * p) / 64) * 64) + m[3];
  }

  /** "boot" (default): when the app starts. "wake": also when it comes back after a minute away. "off": never. */
  function shouldPlay(mode, reason) {
    if (mode === "off") return false;
    if (reason === "start") return true;
    return mode === "wake" && reason === "wake";
  }

  // ---- DOM player (browser only) ----

  var playing = null;

  function esc(s) {
    return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }
  function span(color, s, b) {
    return '<span style="color:' + color + (b ? ";font-weight:bold" : "") + '">' + esc(s) + "</span>";
  }
  /** Block text as solid runs (background, not glyphs): no seams between U+2588 cells on any engine. */
  function blocks(color, s, fx) {
    var out = "", re = /(\u2588+)|([^\u2588]+)/g, m;
    while ((m = re.exec(s))) {
      if (m[1]) out += '<span style="background:' + color + ";color:transparent;text-shadow:none" +
        (fx ? ";box-shadow:0 0 0.45em " + color : "") + '">' + new Array(m[1].length + 1).join(" ") + "</span>";
      else out += esc(m[2]);
    }
    return out;
  }
  function row(h, inner) {
    return '<div style="height:' + h + "em;line-height:" + h + 'em">' + (inner || "&nbsp;") + "</div>";
  }

  function renderText(script, ms, real, t, fx) {
    var html = "", vis = visibleAt(script, ms), last = vis[vis.length - 1];
    for (var i = 0; i < vis.length; i++) {
      var l = vis[i];
      if (l.kind === "blank") html += row(1.35);
      else if (l.kind === "ok") {
        if (spinning(l, ms))
          html += row(1.35, span(t.fg, "[") + span(t.red, spinner(real - l.at), true) + span(t.fg, "] A start job is running for " + l.detail));
        else if (doneOk(l)) html += row(1.35, span(t.fg, "[  ") + span(t.green, "OK", true) + span(t.fg, "  ] " + doneText(l)));
        else html += row(1.35, span(t.fg, "[ ") + span(t.yellow, "WARN", true) + span(t.fg, " ] " + doneText(l)));
      } else if (l.kind === "title") html += row(1.35, span(t.accent, l.text, true));
      else if (l.kind === "prompt") {
        var n = typedChars(l, ms), cursor = n < l.detail.length || (l === last && Math.floor(ms / 400) % 2 === 0);
        html += row(1.35, span(t.green, l.text) + span(t.fgBright, l.detail.substring(0, n)) +
          (cursor ? '<span style="background:' + t.fg + '">&nbsp;</span>' : ""));
      } else if (l.kind === "logo") {
        var s = Math.min(script.logoSplit, l.text.length);
        html += row(1.2, blocks(t.accent, l.text.substring(0, s), fx) + blocks(t.accent2, l.text.substring(s), fx));
      } else if (l.kind === "info") html += row(1.35, span(t.fg, l.text));
      else if (l.kind === "memtest") html += row(1.35, span(t.fg, memText(l, ms)));
      else html += row(1.35, span(t.fgDim, l.text));
    }
    if (ms < script.biosEnd) {   // OTV badge top right, as the old BIOS logos
      var b = "";
      for (var k = 0; k < script.badge.length; k++) b += row(1.2, blocks(t.accent, script.badge[k], fx));
      html += '<div style="position:absolute;top:0;right:0">' + b + "</div>";
    }
    return html;
  }

  function setTransform(el, v) {
    el.style.webkitTransform = v;
    el.style.transform = v;
  }

  /**
   * Plays the boot screen over everything. o: build() options + fx (scanlines and glow, default true) + theme
   * colours {bg, fg, fgBright, fgDim, accent, accent2, green, red}. done() runs once, when it ends or is skipped.
   * Any key skips; keys never reach the app underneath.
   */
  function play(o, done) {
    if (playing || typeof document === "undefined") { if (done) done(); return; }
    var c = o.colors || {};
    var t = { bg: c.bg || "#1a1b26", fg: c.fg || "#a9b1d6", fgBright: c.fgBright || "#c0caf5", fgDim: c.fgDim || "#565f89",
              accent: c.accent || "#7aa2f7", accent2: c.accent2 || "#bb9af7", green: c.green || "#9ece6a", red: c.red || "#f7768e",
              yellow: c.yellow || "#e0af68" };
    var fx = o.fx !== false;
    var script = build(o);
    var w = window.innerWidth || 1920, h = window.innerHeight || 1080;
    // COLUMNS over 86% of the width (a monospace cell is ~0.6 em), never taller than 30 lines.
    var size = Math.min(w * 0.86 / (COLUMNS * 0.6), h / 30 / 1.35);

    var el = document.createElement("div");
    el.id = "otv-boot";
    el.style.cssText = "position:fixed;left:0;top:0;right:0;bottom:0;z-index:2147483000;margin:0;overflow:hidden;" +
      "background:#000;transition:opacity " + FADE_MS + "ms ease-out;-webkit-transition:opacity " + FADE_MS + "ms ease-out;opacity:1";
    var screen = document.createElement("div");   // the CRT tube: opens from a line, then holds the text
    screen.style.cssText = "position:absolute;left:0;top:0;right:0;bottom:0;background:" + t.bg + ";" +
      "-webkit-transform-origin:50% 50%;transform-origin:50% 50%";
    var flash = document.createElement("div");
    flash.style.cssText = "position:absolute;left:0;top:0;right:0;bottom:0;background:#fff;opacity:0";
    var text = document.createElement("div");
    text.style.cssText = "position:absolute;left:7%;right:7%;top:7%;bottom:0;color:" + t.fg + ";" +
      "font-family:'CaskaydiaMono',monospace;font-size:" + Math.round(size) + "px;white-space:pre;" +
      (fx ? "text-shadow:0 0 " + Math.round(size * 0.4) + "px;" : "");
    screen.appendChild(text);
    screen.appendChild(flash);
    el.appendChild(screen);
    if (fx) {
      var scan = document.createElement("div");
      scan.style.cssText = "position:absolute;left:0;top:0;right:0;bottom:0;pointer-events:none;" +
        "background-image:-webkit-repeating-linear-gradient(top,rgba(0,0,0,0) 0,rgba(0,0,0,0) 2px,rgba(0,0,0,0.22) 2px,rgba(0,0,0,0.22) 3px);" +
        "background-image:repeating-linear-gradient(to bottom,rgba(0,0,0,0) 0,rgba(0,0,0,0) 2px,rgba(0,0,0,0.22) 2px,rgba(0,0,0,0.22) 3px)";
      el.appendChild(scan);
    }
    // v2.14 QR code of OmarchyTV (setting "boot + Live", off by default): small, bottom right, with the wordmark; the
    // boot then holds 3 s longer so a phone can read it (any key still skips).
    var qrEl = null, qrHold = 0;
    if (o.qr && root.OmarchyQR) {
      qrEl = root.OmarchyQR.canvas(document, o.qr, Math.round(h * 0.16), "M", { light: t.fg, dark: t.bg });
      if (qrEl) {
        qrHold = 3000;
        qrEl.style.cssText = "position:absolute;right:6%;bottom:8%;width:" + Math.round(h * 0.16) + "px;height:" + Math.round(h * 0.16) + "px;" +
          "border-radius:6px;opacity:0;image-rendering:pixelated;-webkit-transition:opacity .4s;transition:opacity .4s";
        screen.appendChild(qrEl);
      }
    }
    document.body.appendChild(el);

    var start = Date.now(), finished = false, fading = false, timer = null;
    playing = el;

    function finish() {
      if (finished) return;
      finished = true;
      clearInterval(timer);
      window.removeEventListener("keydown", onKey, true);
      window.removeEventListener("keyup", onKey, true);
      if (el.parentNode) el.parentNode.removeChild(el);
      if (sound) sound.close();
      playing = null;
      if (done) done();
    }
    function fade() {
      if (fading) return;
      fading = true;
      el.style.opacity = "0";
      setTimeout(finish, FADE_MS + 30);
    }
    function onKey(e) {
      e.preventDefault();
      e.stopPropagation();
      if (e.stopImmediatePropagation) e.stopImmediatePropagation();
      if (e.type === "keydown") fade();
    }
    window.addEventListener("keydown", onKey, true);
    window.addEventListener("keyup", onKey, true);

    var sound = o.sound !== false && root.OmarchyBootSound ? root.OmarchyBootSound.create() : null, lastMs = -1;
    function tick() {
      var real = Date.now() - start, ms = clock(script, real);   // held while network / omarchy-link really wait
      if (sound && !fading) { var cs = cues(script, lastMs, ms); for (var ci = 0; ci < cs.length; ci++) sound.cue(cs[ci]); }
      lastMs = ms;
      if (ms < CRT_MS) {   // power-on: a bright line grows across, then opens vertically
        var p = crt(ms), line = 3 / h;
        if (p < 0.5) {
          var q = p / 0.5;
          screen.style.background = "#fff";
          setTransform(screen, "scale(" + Math.max(0.001, q * q) + "," + line + ")");
        } else {
          var q2 = (p - 0.5) / 0.5, ease = 1 - (1 - q2) * (1 - q2);
          screen.style.background = t.bg;
          flash.style.opacity = String(0.55 * (1 - q2));
          setTransform(screen, "scale(1," + Math.max(line, ease) + ")");
        }
        text.innerHTML = "";
      } else {
        screen.style.background = t.bg;
        flash.style.opacity = "0";
        setTransform(screen, "none");
        if (!fading) text.innerHTML = flickering(script, ms) ? "" : renderText(script, ms, real, t, fx);
      }
      if (qrEl) qrEl.style.opacity = ms >= script.fadeAt - HOLD_MS && !fading ? "0.9" : "0";
      if (ms >= script.fadeAt + qrHold) fade();
    }
    tick();
    timer = setInterval(tick, 33);   // setInterval, not rAF: some Tizen 2.x/webOS 3 engines throttle rAF at start
  }

  /**
   * Wires the boot screen into the app: plays at start, and with mode "wake" when the app comes back after at
   * least a minute hidden (TV standby, another app). getOpts() is called each time, so theme changes apply.
   */
  function install(getOpts) {
    if (typeof document === "undefined") return;
    var hiddenAt = 0;
    function maybe(reason) {
      var o = getOpts();
      if (shouldPlay(o.mode, reason)) play(o);
    }
    document.addEventListener("visibilitychange", function () {
      if (document.hidden) { hiddenAt = Date.now(); return; }
      if (hiddenAt && Date.now() - hiddenAt >= 60000) maybe("wake");
      hiddenAt = 0;
    });
    maybe("start");
  }

  var api = { build: build, clock: clock, doneText: doneText, doneOk: doneOk, visibleAt: visibleAt, typedChars: typedChars, memText: memText, spinning: spinning,
              spinner: spinner, flickering: flickering, cues: cues, crt: crt, blockRows: blockRows, splitColumn: splitColumn,
              shouldPlay: shouldPlay, play: play, install: install, COLUMNS: COLUMNS, MAX_WAIT_MS: MAX_WAIT_MS };
  root.OmarchyBoot = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof window !== "undefined" ? window : this);
