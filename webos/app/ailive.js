/*
 * OmarchyTV — v2.22 AI LIVE, the night shift and skills on LG webOS and Samsung Tizen.
 *
 * The PC bridge keeps the channel, the shift and the skills (omarchy-tv ai_bridge.py, the same rules as an Android
 * TV); this app reads GET /v1/tvlive with its ask code and shows four screens over the Home:
 *   live    the agents as a TV broadcast: ● LIVE, the studios, the agent on camera with its avatar and the preview of
 *           its work, the lower third, BREAKING when one waits for a yes (the question itself opens as usual), NEWS;
 *   replay  the night shift played back like a VHS: PLAY / PAUSE / ×2 / STOP, the timeline, ◀ ▶ between highlights;
 *   shift   the night's page: tasks, score, the decisions that wait (only here, with the remote, are they answered:
 *           Deny has the focus, Approve waits 1.5 s);
 *   skills  the catalogue; installing shows the permissions in plain words (Cancel first, Install after 1.5 s);
 *   briefing the morning's news an agent prepared, section after section (◀ ▶), with the ticker;
 *   debate  one question, the agents' answers side by side; the owner picks the winner (OK after 1.5 s);
 *   teletext Televideo AI: the agents' pages 101-899 in 40 columns, three digits to jump, ▲ ▼ next / previous,
 *           fastext red / green / yellow (index) / blue (the Guide);
 *   guide   the agents' Guide: their programmes, the night shift and the briefing as a TV schedule.
 * It opens by itself on the morning's wake time, for the briefing, for a new debate and when an agent asks to install
 * a skill. Everything is text
 * (textContent); ES5 for Chrome 38.
 */
(function (root) {
  "use strict";

  var BACK = { 461: 1, 10009: 1, 27: 1, 8: 1 }, OK = 13, LEFT = 37, UP = 38, RIGHT = 39, DOWN = 40;
  var PLAY = { 415: 1, 19: 1, 10252: 1, 179: 1 }, FF = { 417: 1, 228: 1 }, REW = { 412: 1, 227: 1 };
  var RED = "#d7263d", CUT_MS = 12000, HAND_MS = 30000, GUARD_MS = 1500;
  var K_RED = 403, K_GREEN = 404, K_YELLOW = 405, K_BLUE = 406, CH_UP = { 33: 1, 427: 1 }, CH_DOWN = { 34: 1, 428: 1 };   // LG / Samsung
  var TT_COLOR = { white: "#fff", red: "#ff3b3b", green: "#3bff3b", yellow: "#ffff3b", blue: "#4f6bff", magenta: "#ff3bff", cyan: "#3bffff" };
  var PERM = { widgets: "show widgets on a page of its own", notify: "send notes to the TV", "ha.read": "read your Home Assistant entities",
    "ha.control": "run your Home Assistant scenes and switches", shift: "use the night shift", live: "go on AI LIVE" };

  function vh(px) { return (px / 10.8).toFixed(2) + "vh"; }
  function pad2(n) { return (n < 10 ? "0" : "") + n; }
  function hhmm(ms) { var d = new Date(ms); return pad2(d.getHours()) + ":" + pad2(d.getMinutes()); }
  var MON = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"];
  function vcrDate(ms) { var d = new Date(ms); return MON[d.getMonth()] + " " + pad2(d.getDate()) + " " + d.getFullYear() + "  " + hhmm(ms); }
  function mark(k) {
    return k === "ok" ? "✓" : k === "fail" || k === "alert" ? "✗" : k === "ask" || k === "queued" ? "◆" : k === "answer" ? "↩" : k === "talk" ? "🎙" : k === "status" ? "▶" : "·";
  }
  function initials(name) {
    var parts = String(name || "").split(/[\s·_-]+/), s = "";
    for (var i = 0; i < parts.length && s.length < 2; i++) if (parts[i]) s += parts[i].charAt(0);
    return (s || "AI").toUpperCase();
  }
  function hash(s) { var h = 0; s = String(s || ""); for (var i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0; return Math.abs(h); }

  /** The tape for REPLAY: the diary and the queue, in time order. */
  function tapeOf(shift) {
    var out = [], i, log = (shift && shift.log) || [], q = (shift && shift.queue) || [];
    for (i = 0; i < log.length; i++) out.push({ at: log[i].at, agent: log[i].agent, kind: log[i].level, text: log[i].text, detail: log[i].detail || "" });
    for (i = 0; i < q.length; i++) out.push({ at: q[i].at, agent: q[i].agent, kind: "queued", text: q[i].title, detail: q[i].detail || "" });
    out.sort(function (a, b) { return a.at - b.at; });
    return out;
  }

  /** The director: whoever waits for a yes, else the next agent that worked in the last 10 minutes. */
  function director(agents, onCamera, now, cutAt, handUntil) {
    if (!agents || !agents.length) return null;
    var i, cur = null, start = 0;
    for (i = 0; i < agents.length; i++) if (agents[i].agent === onCamera) { cur = agents[i]; start = i; }
    if (now >= handUntil) for (i = 0; i < agents.length; i++) if (agents[i].waiting) return agents[i];
    if (cur && (now - cutAt <= CUT_MS || now < handUntil)) return cur;
    for (var k = 1; k <= agents.length; k++) {
      var x = agents[(start + k) % agents.length];
      if (now - x.at < 600000 || x.waiting) return x;
    }
    return cur || agents[0];
  }

  function create(opts) {
    var doc = opts.doc || root.document, fetchFn = opts.fetch || (root.fetch ? root.fetch.bind(root) : null);
    var data = null, mode = null, layer = null, els = {}, busy = false;
    var onCamera = "", cutAt = 0, handUntil = 0, tape = [], cursor = 0, paused = false, stopped = false, speed = 1;
    var sel = 0, dialog = null, lastAsk = "", timer = 0, tickerX = 0, tickerTimer = 0;
    var section = 0, sectionAt = 0, lastBriefing = 0, lastDebate = "", pick = 0, pickAt = 0, debateId = "";
    var ttPage = 100, ttTyped = "", ttMissingAt = 0;

    function el(tag, style, text) {
      var e = doc.createElement(tag);
      if (style) e.setAttribute("style", style);
      if (text != null) e.textContent = String(text);
      return e;
    }
    function post(path, body) {
      if (!fetchFn) return Promise.reject(new Error("no fetch"));
      return fetchFn(opts.base() + path, { method: "POST", headers: { "X-Omarchy-Token": opts.token(), "Content-Type": "text/plain" },
        body: JSON.stringify(body) }).then(function (r) { return r.json(); });
    }

    // ---- the frame shared by every screen -------------------------------------------------------------------
    function frame(bugText, bugColor, tickerLabel) {
      close(true);
      layer = el("div", "position:fixed;left:0;top:0;right:0;bottom:0;z-index:99965;overflow:hidden;color:var(--fg,#a9b1d6);" +
        "background:linear-gradient(135deg,var(--bgDarker,#0f0f14),var(--bg,#1a1b26) 55%,var(--bgDark,#16161e));font-family:inherit");
      layer.id = "otv-ailive";
      var brand = el("div", "position:absolute;left:" + vh(72) + ";top:" + vh(48) + ";white-space:nowrap");
      els.bug = el("span", "background:" + bugColor + ";color:#fff;font-weight:bold;font-size:" + vh(34) + ";padding:" + vh(8) + " " + vh(24) + ";border-radius:" + vh(6), bugText);
      brand.appendChild(els.bug);
      brand.appendChild(el("span", "background:var(--accent,#7aa2f7);color:var(--bg,#1a1b26);font-weight:bold;font-size:" + vh(38) + ";padding:" + vh(8) + " " + vh(24) + ";border-radius:" + vh(6) + ";margin-left:" + vh(12), "AI LIVE"));
      layer.appendChild(brand);
      var net = el("div", "position:absolute;right:" + vh(72) + ";top:" + vh(44) + ";text-align:right");
      els.clock = el("div", "background:rgba(0,0,0,.4);color:var(--fgBright,#c0caf5);font-weight:bold;font-size:" + vh(44) + ";padding:" + vh(4) + " " + vh(20) + ";border-radius:" + vh(6), hhmm(Date.now()));
      net.appendChild(els.clock);
      net.appendChild(el("div", "color:var(--fgBright,#c0caf5);opacity:.45;font-weight:bold;font-size:" + vh(28), "OmarchyTV"));
      layer.appendChild(net);
      if (tickerLabel) {
        var tk = el("div", "position:absolute;left:0;right:0;bottom:" + vh(56) + ";height:" + vh(72) + ";background:rgba(0,0,0,.93);overflow:hidden;white-space:nowrap");
        els.tickerText = el("div", "position:absolute;left:" + vh(170) + ";top:" + vh(16) + ";color:#fff;font-size:" + vh(36) + ";white-space:nowrap");
        tk.appendChild(els.tickerText);
        tk.appendChild(el("div", "position:absolute;left:0;top:0;bottom:0;background:" + (tickerLabel === "NEWS" ? RED : "var(--blue,#2ac3de)") +
          ";color:#fff;font-weight:bold;font-size:" + vh(34) + ";padding:" + vh(16) + " " + vh(28), tickerLabel));
        layer.appendChild(tk);
      }
      els.hint = el("div", "position:absolute;left:" + vh(72) + ";bottom:" + vh(12) + ";color:var(--muted,#565f89);font-size:" + vh(26), "");
      layer.appendChild(els.hint);
      doc.body.appendChild(layer);
    }

    function setTicker(text) {
      if (!els.tickerText || els.tickerText.textContent === text) return;
      els.tickerText.textContent = text;
      tickerX = 0;
    }
    function scrollTicker() {
      if (!els.tickerText || !layer) return;
      tickerX -= 3;
      var w = els.tickerText.offsetWidth || 1;
      if (tickerX < -w) tickerX = (root.innerWidth || 1920);
      els.tickerText.style.transform = "translateX(" + tickerX + "px)";
      els.tickerText.style.webkitTransform = "translateX(" + tickerX + "px)";
    }

    // ---- LIVE and REPLAY: the stage --------------------------------------------------------------------------
    function stage() {
      els.studios = el("div", "position:absolute;left:" + vh(72) + ";top:" + vh(140) + ";right:" + vh(320) + ";font-size:" + vh(30) + ";white-space:pre;overflow:hidden", "");
      layer.appendChild(els.studios);
      var st = el("div", "position:absolute;left:" + vh(72) + ";right:" + vh(72) + ";top:" + vh(208) + ";bottom:" + vh(352));
      els.stage = st;
      var who = el("div", "position:absolute;left:0;top:0;bottom:0;width:38%");
      els.avatar = el("div", "width:" + vh(256) + ";height:" + vh(256) + ";border-radius:50%;box-sizing:border-box;border:" + vh(20) + " solid var(--accent,#7aa2f7);" +
        "display:table;text-align:center;font-weight:bold;font-size:" + vh(77) + ";color:var(--bg,#1a1b26)");
      els.avatarIn = el("span", "display:table-cell;vertical-align:middle;border-radius:50%");
      els.avatar.appendChild(els.avatarIn);
      who.appendChild(els.avatar);
      els.name = el("div", "color:var(--fgBright,#c0caf5);font-weight:bold;font-size:" + vh(76) + ";padding-top:" + vh(20) + ";white-space:nowrap;overflow:hidden;text-overflow:ellipsis");
      els.line = el("div", "font-size:" + vh(42) + ";max-height:" + vh(110) + ";overflow:hidden");
      var track = el("div", "position:relative;height:" + vh(12) + ";background:var(--bgLight,#292e42);border-radius:" + vh(6) + ";margin:" + vh(24) + " " + vh(48) + " 0 0");
      els.bar = el("div", "position:absolute;left:0;top:0;bottom:0;width:0;background:var(--accent,#7aa2f7);border-radius:" + vh(6));
      track.appendChild(els.bar);
      els.since = el("div", "color:var(--muted,#565f89);font-size:" + vh(28) + ";padding-top:" + vh(12));
      who.appendChild(els.name); who.appendChild(els.line); who.appendChild(track); who.appendChild(els.since);
      st.appendChild(who);
      var mon = el("div", "position:absolute;left:42%;right:0;top:" + vh(36) + ";bottom:0;background:var(--bgDarker,#0f0f14);border:" + vh(6) + " solid var(--muted,#565f89);" +
        "border-radius:" + vh(12) + ";padding:" + vh(28) + ";overflow:hidden");
      els.mlabel = el("span", "position:absolute;left:" + vh(28) + ";top:" + vh(20) + ";background:var(--muted,#565f89);color:var(--bg,#1a1b26);font-weight:bold;font-size:" + vh(24) + ";padding:" + vh(2) + " " + vh(16), "STUDIO");
      els.mtext = el("div", "margin-top:" + vh(52) + ";font-size:" + vh(28) + ";line-height:1.5;white-space:pre;overflow:hidden");
      els.mimg = el("img", "display:none;max-width:100%;max-height:85%;margin-top:" + vh(52));
      mon.appendChild(els.mlabel); mon.appendChild(els.mtext); mon.appendChild(els.mimg);
      st.appendChild(mon);
      layer.appendChild(st);
      els.breaking = el("div", "display:none;position:absolute;left:" + vh(72) + ";right:" + vh(72) + ";bottom:" + vh(260) + ";background:" + RED +
        ";color:#fff;font-weight:bold;font-size:" + vh(40) + ";padding:" + vh(16) + " " + vh(36) + ";white-space:nowrap;overflow:hidden;text-overflow:ellipsis");
      layer.appendChild(els.breaking);
      var lt = el("div", "position:absolute;left:" + vh(72) + ";right:" + vh(240) + ";bottom:" + vh(160) + ";white-space:nowrap;overflow:hidden;display:table;width:auto");
      els.lt = lt;
      els.ltn = el("span", "display:table-cell;background:var(--accent,#7aa2f7);color:var(--bg,#1a1b26);font-weight:bold;font-size:" + vh(40) + ";padding:" + vh(16) + " " + vh(32) + ";white-space:nowrap");
      els.ltt = el("span", "display:table-cell;width:100%;background:var(--fgBright,#c0caf5);color:var(--bg,#1a1b26);font-size:" + vh(38) + ";padding:" + vh(16) + " " + vh(32) + ";white-space:nowrap");
      lt.appendChild(els.ltn); lt.appendChild(els.ltt);
      layer.appendChild(lt);
    }

    function cutTo(name) {
      var lt = els.lt, st = els.stage;
      lt.style.transition = "none"; lt.style.webkitTransition = "none";
      lt.style.transform = lt.style.webkitTransform = "translateX(-110%)";
      st.style.transition = st.style.webkitTransition = "none"; st.style.opacity = "0.2";
      setTimeout(function () {
        lt.style.transition = lt.style.webkitTransition = "transform .45s ease-out, -webkit-transform .45s ease-out";
        lt.style.transform = lt.style.webkitTransform = "translateX(0)";
        st.style.transition = st.style.webkitTransition = "opacity .35s";
        st.style.opacity = "1";
      }, 30);
      var pal = ["var(--accent,#7aa2f7)", "var(--accent2,#bb9af7)", "var(--blue,#2ac3de)", "var(--cyan,#7dcfff)", "var(--green,#9ece6a)", "var(--yellow,#e0af68)"];
      els.avatarIn.style.background = pal[hash(name) % pal.length];
      els.avatarIn.textContent = initials(name);
    }

    function avatarState(working, waiting) {
      var on = (Math.floor(Date.now() / (waiting ? 260 : 700)) % 2) === 0;
      els.avatar.style.borderColor = waiting ? RED : "var(--accent,#7aa2f7)";
      els.avatar.style.opacity = !(working || waiting) ? "0.85" : on ? "1" : "0.75";
    }

    function preview(p) {
      var k = p ? p.kind : "";
      els.mlabel.textContent = k ? "ON SCREEN" + (p.title ? " · " + p.title : "") : "STUDIO";
      els.mlabel.style.background = k ? "var(--accent,#7aa2f7)" : "var(--muted,#565f89)";
      els.mimg.style.display = k === "image" ? "block" : "none";
      if (k === "image") { if (els.mimg.getAttribute("src") !== p.url) els.mimg.setAttribute("src", p.url); els.mtext.textContent = ""; return; }
      els.mtext.textContent = "";
      if (k === "diff" || k === "text") {
        var lines = String(p.text).split("\n");
        for (var i = 0; i < lines.length && i < 40; i++) {
          var c = k === "diff" && lines[i].charAt(0) === "+" ? "var(--green,#9ece6a)" : k === "diff" && lines[i].charAt(0) === "-" ? "var(--red,#f7768e)" : "";
          els.mtext.appendChild(el("div", c ? "color:" + c : "", lines[i] || " "));
        }
      } else if (k === "chart") {
        var pts = p.points || [], lo = Math.min.apply(null, pts), hi = Math.max.apply(null, pts), bars = "▁▂▃▄▅▆▇█", s = (p.label || "") + "\n\n";
        for (var j = 0; j < pts.length; j++) s += bars.charAt(hi > lo ? Math.round((pts[j] - lo) / (hi - lo) * 7) : 3);
        els.mtext.textContent = s;
      }
    }

    function showAgent(x, now) {
      var what = x.text || "…";
      els.name.textContent = x.agent; els.line.textContent = what;
      els.ltn.textContent = String(x.agent).toUpperCase(); els.ltt.textContent = what;
      els.since.textContent = "on air for " + Math.max(0, Math.floor((now - (x.since || now)) / 60000)) + " min";
      els.bar.style.width = (x.progress >= 0 ? Math.min(100, x.progress) : 0) + "%";
      els.breaking.style.display = x.waiting ? "block" : "none";
      if (x.waiting) els.breaking.textContent = "BREAKING  ◆  " + x.agent + " waits for you: " + x.waiting.question;
      avatarState(now - x.at < 600000, !!x.waiting);
      preview(x.preview);
    }

    function renderLive() {
      var now = Date.now(), live = (data && data.live) || { agents: [], events: [] }, a = live.agents || [];
      els.clock.textContent = hhmm(now);
      els.bug.style.opacity = (Math.floor(now / 700) % 2) ? "0.55" : "1";
      if (!a.length) {
        if (onCamera !== "·") cutTo("AI");
        onCamera = "·";
        els.name.textContent = "AI LIVE"; els.line.textContent = "No agent on air yet: agents appear with tv_live_update, a note or a question.";
        els.ltn.textContent = "AI LIVE"; els.ltt.textContent = "waiting for the first agent";
        els.breaking.style.display = "none"; els.since.textContent = ""; els.bar.style.width = "0";
        els.studios.textContent = ""; preview(null); setTicker("");
        return;
      }
      var cur = director(a, onCamera, now, cutAt, handUntil);
      if (cur.agent !== onCamera) { onCamera = cur.agent; cutAt = now; cutTo(cur.agent); }
      showAgent(cur, now);
      var s = "";
      for (var i = 0; i < a.length; i++) {
        s += (a[i].agent === onCamera ? "▶ " : "") + (a[i].waiting ? "◆" : now - a[i].at < 600000 ? "●" : "○") + " " + a[i].agent + "      ";
      }
      els.studios.textContent = s;
      var ev = live.events || [], k = "";
      for (var j = ev.length - 1; j >= 0 && k.length < 700; j--) {
        k += hhmm(ev[j].at) + "  " + mark(ev[j].kind) + " " + String(ev[j].agent).toUpperCase() + " · " + ev[j].text + "        ▪        ";
      }
      setTicker(k);
    }

    function renderTape() {
      if (!tape.length) {
        els.name.textContent = "Replay of the night"; els.line.textContent = "No shift tonight.";
        els.ltn.textContent = "REPLAY"; els.ltt.textContent = "nothing recorded"; els.osd.textContent = "STOP ■";
        return;
      }
      var e = tape[Math.max(0, Math.min(cursor, tape.length - 1))];
      if (e.agent !== onCamera) { onCamera = e.agent; cutTo(e.agent); }
      els.clock.textContent = hhmm(e.at);
      if (!stopped) els.osd.textContent = paused ? "PAUSE ‖" : speed > 1 ? "PLAY ▶▶ ×" + speed : "PLAY ▶";
      els.osd.style.opacity = paused && !stopped && (Math.floor(Date.now() / 700) % 2) ? "0.3" : "1";
      els.osdDate.textContent = vcrDate(e.at);
      var text = mark(e.kind) + " " + e.text;
      els.name.textContent = e.agent; els.line.textContent = text;
      els.ltn.textContent = String(e.agent).toUpperCase(); els.ltt.textContent = text;
      els.since.textContent = (cursor + 1) + " / " + tape.length;
      els.bar.style.width = Math.round((cursor + 1) * 100 / tape.length) + "%";
      var q = e.kind === "queued";
      els.breaking.style.display = q ? "block" : "none";
      if (q) els.breaking.textContent = "◆  " + e.agent + " waits for you: " + e.text;
      avatarState(true, q || e.kind === "fail");
      preview(e.detail ? { kind: /\n[+-]/.test(e.detail) ? "diff" : "text", text: e.detail } : null);
      els.timeline.textContent = "";
      for (var i = 0; i < tape.length; i++) {
        var k = tape[i].kind;
        if (k !== "fail" && k !== "queued") continue;
        els.timeline.appendChild(el("span", "position:absolute;top:0;bottom:0;width:" + vh(6) + ";left:" + (tape.length < 2 ? 0 : i * 100 / (tape.length - 1)) + "%;background:" + (k === "fail" ? "var(--red,#f7768e)" : "var(--yellow,#e0af68)")));
      }
      els.timeline.appendChild(el("span", "position:absolute;left:0;top:40%;bottom:40%;background:var(--accent,#7aa2f7);width:" + (tape.length < 2 ? 100 : cursor * 100 / (tape.length - 1)) + "%"));
      var t = "";
      for (var j = cursor + 1; j < tape.length && t.length < 500; j++) t += hhmm(tape[j].at) + "  " + mark(tape[j].kind) + " " + String(tape[j].agent).toUpperCase() + " · " + tape[j].text + "        ▪        ";
      setTicker(t);
    }

    function step() {
      if (!tape.length || paused) { renderTape(); return; }
      renderTape();
      if (cursor < tape.length - 1) cursor++;
      else { paused = true; stopped = true; els.osd.textContent = "STOP ■"; els.hint.textContent = "End of the recording   OK → Night shift"; }
    }

    // ---- SHIFT and SKILLS: lists and the guarded question -----------------------------------------------------
    function listScreen(title, rows, sub) {
      frame("🌙", "var(--blue,#2ac3de)", null);
      if (mode === "skills") els.bug.textContent = "🧩";
      var box = el("div", "position:absolute;left:" + vh(72) + ";right:" + vh(72) + ";top:" + vh(140) + ";bottom:" + vh(80) + ";overflow:hidden");
      box.appendChild(el("div", "color:var(--fgBright,#c0caf5);font-weight:bold;font-size:" + vh(56), title));
      if (sub) box.appendChild(el("div", "font-size:" + vh(30) + ";padding:" + vh(8) + " 0 " + vh(20), sub));
      els.rows = [];
      for (var i = 0; i < rows.length; i++) {
        var r = rows[i];
        var d = el("div", "font-size:" + vh(32) + ";padding:" + vh(10) + " " + vh(20) + ";margin-top:" + vh(4) + ";border:" + vh(3) + " solid transparent;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;" +
          (r.color ? "color:" + r.color : ""), r.text);
        if (r.head) d.setAttribute("style", "color:var(--accent,#7aa2f7);font-weight:bold;font-size:" + vh(34) + ";padding:" + vh(24) + " 0 " + vh(6));
        box.appendChild(d);
        if (r.act) els.rows.push({ el: d, act: r.act });
      }
      layer.appendChild(box);
      if (sel >= els.rows.length) sel = 0;
      paintSel();
    }
    function paintSel() {
      for (var i = 0; els.rows && i < els.rows.length; i++) {
        els.rows[i].el.style.borderColor = i === sel ? "var(--accent,#7aa2f7)" : "transparent";
        els.rows[i].el.style.background = i === sel ? "var(--bgLight,#292e42)" : "";
      }
    }

    function ask(title, text, no, yes, onYes) {
      var box = el("div", "position:absolute;left:18%;right:18%;top:20%;background:var(--bgDark,#16161e);border:" + vh(4) + " solid var(--accent,#7aa2f7);padding:" + vh(36) + ";z-index:2");
      box.appendChild(el("div", "color:var(--fgBright,#c0caf5);font-weight:bold;font-size:" + vh(40) + ";padding-bottom:" + vh(16), title));
      box.appendChild(el("div", "font-size:" + vh(30) + ";white-space:pre-wrap;max-height:" + vh(420) + ";overflow:hidden", text));
      var b = el("div", "padding-top:" + vh(28));
      var bNo = el("span", "font-size:" + vh(34) + ";padding:" + vh(10) + " " + vh(32) + ";margin-right:" + vh(24) + ";border:" + vh(3) + " solid var(--accent,#7aa2f7)", no);
      var bYes = el("span", "font-size:" + vh(34) + ";padding:" + vh(10) + " " + vh(32) + ";border:" + vh(3) + " solid var(--muted,#565f89);opacity:.4", yes);
      b.appendChild(bNo); b.appendChild(bYes); box.appendChild(b);
      layer.appendChild(box);
      dialog = { box: box, no: bNo, yes: bYes, focus: 0, ready: false, onYes: onYes };
      setTimeout(function () { if (dialog && dialog.box === box) { dialog.ready = true; bYes.style.opacity = "1"; } }, GUARD_MS);
    }
    function dialogKey(code) {
      if (BACK[code]) { closeDialog(); return true; }
      if (code === LEFT || code === RIGHT) {
        dialog.focus = code === RIGHT && dialog.ready ? 1 : 0;
        dialog.no.style.borderColor = dialog.focus === 0 ? "var(--accent,#7aa2f7)" : "var(--muted,#565f89)";
        dialog.yes.style.borderColor = dialog.focus === 1 ? "var(--accent,#7aa2f7)" : "var(--muted,#565f89)";
        return true;
      }
      if (code === OK) {
        var d = dialog;
        closeDialog();
        if (d.focus === 1 && d.ready) d.onYes();
        return true;
      }
      return true;
    }
    function closeDialog() { if (dialog && dialog.box.parentNode) dialog.box.parentNode.removeChild(dialog.box); dialog = null; }

    function renderShift() {
      var s = (data && data.shift) || { state: "none" }, rows = [], i;
      if (s.state === "none") { listScreen("Night shift", [], "No shift tonight. Ask your agent to start one (tv_shift_start)."); return; }
      var c = s.counts || {};
      rows.push({ text: "▶ Replay of the night", color: "var(--accent,#7aa2f7)", act: function () { open("replay"); } });
      rows.push({ head: true, text: "Waiting for you (" + (c.waiting || 0) + ")" });
      var q = s.queue || [];
      for (i = 0; i < q.length; i++) (function (e) {
        var st = e.state;
        rows.push({ text: (st === "pending" ? "◆ " : st === "approved" ? "✓ " : "✗ ") + e.title + "  · " + e.agent + (e.risk ? " · " + e.risk : ""),
          color: st === "pending" ? "var(--fgBright,#c0caf5)" : "", act: st !== "pending" ? null : function () {
            ask(e.agent + ": " + e.title, (e.risk ? "⚠ " + e.risk + "\n\n" : "") + (e.detail || ""), "Deny", "Approve", function () {
              post("/v1/shift/answer", { id: e.id, yes: true }).then(refreshNow, refreshNow);
            });
            dialog.onNo = function () { post("/v1/shift/answer", { id: e.id, yes: false }).then(refreshNow, refreshNow); };
          } });
      })(q[i]);
      rows.push({ head: true, text: "Tasks" });
      for (i = 0; i < (s.tasks || []).length; i++) rows.push({ text: (i + 1) + ". " + s.tasks[i].text });
      rows.push({ head: true, text: "Diary" });
      var log = s.log || [];
      for (i = log.length - 1; i >= 0 && rows.length < 60; i--) {
        rows.push({ text: hhmm(log[i].at) + "  " + mark(log[i].level) + " " + log[i].text + "  · " + log[i].agent,
          color: log[i].level === "ok" ? "var(--green,#9ece6a)" : log[i].level === "fail" ? "var(--red,#f7768e)" : "" });
      }
      listScreen("🌙 Night shift", rows, (s.state === "running" ? "Running until " + hhmm(s.until) : "Ended") +
        "   ✓ " + (c.ok || 0) + "   ✗ " + (c.fail || 0) + "   · " + (c.info || 0));
    }

    function skillQuestion(sk) {
      var perms = "";
      for (var i = 0; i < (sk.permissions || []).length; i++) perms += "• " + (PERM[sk.permissions[i]] || sk.permissions[i]) + "\n";
      ask("🧩 " + sk.name + " · " + sk.author, sk.description + "\n\nIt asks to:\n" + perms + "\nA skill is data: it cannot run code on the TV.", "Cancel", "Install", function () {
        post(lastAsk === sk.id ? "/v1/skills/answer" : "/v1/tvskills/install", lastAsk === sk.id ? { id: sk.id, yes: true } : { id: sk.id }).then(refreshNow, refreshNow);
      });
    }

    function renderSkills(focusId) {
      var sk = (data && data.skills) || [], inst = (data && data.installed) || [], rows = [];
      for (var i = 0; i < sk.length; i++) (function (s) {
        var on = inst.indexOf(s.id) >= 0;
        rows.push({ text: (on ? "✓ " : "＋ ") + s.name + "  · " + s.author + " · v" + s.version + "   " + s.description,
          color: on ? "var(--fgBright,#c0caf5)" : "", act: function () {
            if (on) ask("🧩 " + s.name, "Remove this skill with its widgets and its page?", "Cancel", "Remove", function () {
              post("/v1/tvskills/remove", { id: s.id }).then(refreshNow, refreshNow);
            });
            else skillQuestion(s);
          } });
      })(sk[i]);
      listScreen("🧩 Skills", rows, "Capabilities from the community: widgets, pages and instructions for your agents. Data only, never code.");
      if (focusId) for (var j = 0; j < sk.length; j++) if (sk[j].id === focusId) { sel = j; paintSel(); skillQuestion(sk[j]); }
    }

    // ---- BRIEFING and DEBATE: the studio's shows ---------------------------------------------------------------
    function renderBriefing() {
      var b = data && data.briefing, now = Date.now();
      els.clock.textContent = hhmm(now);
      if (!b || !b.sections || !b.sections.length) { els.name.textContent = "AI LIVE"; els.line.textContent = "No briefing."; return; }
      var secs = b.sections;
      if (now - sectionAt > 9000) { section = (section + 1) % secs.length; sectionAt = now; cutTo(b.by || "AI"); }
      section = Math.max(0, Math.min(section, secs.length - 1));
      var s = secs[section], txt = "", st = "", k = "", i, j;
      els.name.textContent = b.title; els.line.textContent = b.by || "";
      els.ltn.textContent = String(s.title).toUpperCase(); els.ltt.textContent = s.lines[0] || "";
      for (i = 0; i < s.lines.length; i++) txt += "▪ " + s.lines[i] + "\n\n";
      preview({ kind: "text", title: s.title, text: txt.replace(/\s+$/, "") });
      for (i = 0; i < secs.length; i++) {
        st += (i === section ? "▶ " : "") + secs[i].title + "      ";
        for (j = 0; j < secs[i].lines.length; j++) k += String(secs[i].title).toUpperCase() + " · " + secs[i].lines[j] + "        ▪        ";
      }
      els.studios.textContent = st;
      els.bar.style.width = Math.round((section + 1) * 100 / secs.length) + "%";
      els.since.textContent = (section + 1) + " / " + secs.length;
      els.breaking.style.display = "none";
      avatarState(true, false);
      setTicker(k);
    }

    function debateNow() {
      var d = data && data.debate;
      return d && (!debateId || d.id === debateId) ? d : null;
    }
    function renderDebate() {
      var d = debateNow();
      els.clock.textContent = hhmm(Date.now());
      while (els.panels.firstChild) els.panels.removeChild(els.panels.firstChild);
      if (!d) { els.ltn.textContent = "DEBATE"; els.ltt.textContent = "—"; return; }
      var en = d.entries || [], n = en.length, winner = d.winner || "";
      pick = Math.max(0, Math.min(pick, Math.max(0, n - 1)));
      els.ltn.textContent = "THE QUESTION"; els.ltt.textContent = d.topic;
      els.breaking.style.display = "block";
      els.breaking.style.background = winner ? "var(--green,#9ece6a)" : RED;
      els.breaking.textContent = winner ? "🏆 " + winner : n ? "◆ YOU DECIDE · OK → " + en[pick].agent : "◆ waiting for the agents' answers";
      for (var i = 0; i < n; i++) {
        var on = i === pick, won = winner === en[i].agent;
        var col = el("div", "display:table-cell;vertical-align:top;width:" + Math.floor(100 / n) + "%;padding:" + vh(20) + ";background:var(--bgDarker,#0f0f14);" +
          "border:" + vh(on || won ? 6 : 3) + " solid " + (won ? "var(--green,#9ece6a)" : on ? "var(--accent,#7aa2f7)" : "var(--muted,#565f89)"));
        col.appendChild(el("div", "color:var(--fgBright,#c0caf5);font-weight:bold;font-size:" + vh(40), (won ? "🏆 " : "") + en[i].agent));
        col.appendChild(el("div", "font-size:" + vh(30) + ";white-space:pre-wrap;padding-top:" + vh(12) + ";max-height:" + vh(330) + ";overflow:hidden", en[i].text));
        var pv = en[i].preview;
        if (pv && (pv.kind === "text" || pv.kind === "diff")) {
          col.appendChild(el("div", "font-family:monospace;font-size:" + vh(24) + ";white-space:pre;padding-top:" + vh(12) + ";max-height:" + vh(200) + ";overflow:hidden", pv.text));
        }
        els.panels.appendChild(col);
      }
    }
    function debateKey(code) {
      var d = debateNow(), en = (d && d.entries) || [], n = en.length;
      if (!n) return true;
      if (code === LEFT || code === RIGHT) { pick = (pick + (code === RIGHT ? 1 : -1) + n) % n; pickAt = Date.now(); renderDebate(); }
      else if (code === OK && !d.winner && Date.now() - pickAt > GUARD_MS) {   // a reflex OK never picks
        post("/v1/tvstudio/debate", { id: d.id, index: pick }).then(function () { return refreshNow(); }, function () { return null; });
      }
      return true;
    }

    // ---- TELETEXT and GUIDE --------------------------------------------------------------------------------------
    function pad(s, n) { s = String(s); while (s.length < n) s += " "; return s.slice(0, n); }
    function ttFrame() {
      close(true);
      layer = el("div", "position:fixed;left:0;top:0;right:0;bottom:0;z-index:99965;background:#000;color:#fff;font-family:monospace;font-weight:bold;" +
        "font-size:" + vh(36) + ";line-height:1.12;padding:" + vh(40) + " 0 0 " + vh(200) + ";white-space:pre;overflow:hidden");
      layer.id = "otv-ailive";
      els.ttHead = el("div", "", ""); els.ttTitle = el("div", "color:#ffff3b;background:#1530a8", "");
      layer.appendChild(els.ttHead); layer.appendChild(els.ttTitle);
      els.ttRows = [];
      for (var i = 0; i < 22; i++) { var r = el("div", "", " "); els.ttRows.push(r); layer.appendChild(r); }
      var f = el("div", "padding-top:" + vh(8), "");
      var lab = [["◀", "#ff3b3b"], ["▶", "#3bff3b"], ["100", "#ffff3b"], [(opts.tr && opts.tr("guide_title")) || "Guide", "#3bffff"]];
      for (var k = 0; k < 4; k++) f.appendChild(el("span", "color:" + lab[k][1], pad("  " + lab[k][0], 10)));
      layer.appendChild(f);
      doc.body.appendChild(layer);
    }
    function renderTeletext() {
      var pages = (data && data.teletext) || {}, now = Date.now();
      var d = new Date(now), clock = pad2(d.getDate()) + " " + MON[d.getMonth()] + "  " + hhmm(now) + ":" + pad2(d.getSeconds());
      var num = ttTyped ? "P" + (ttTyped + "---").slice(0, 3) : "P" + ttPage;
      els.ttHead.textContent = pad(num + "  OmarchyTV", 40 - clock.length) + clock;
      var p = ttPage === 100 ? ttIndex(pages) : pages[ttPage], i;
      if (!p) {
        els.ttTitle.textContent = pad(" P" + ttPage + "  page not found", 40);
        for (i = 0; i < 22; i++) els.ttRows[i].textContent = " ";
        if (!ttMissingAt) ttMissingAt = now;
        else if (now - ttMissingAt > 2500) { ttMissingAt = 0; ttPage = 100; renderTeletext(); }
        return;
      }
      ttMissingAt = 0;
      var by = ttPage === 100 ? "Televideo AI" : String(p.by || "");
      els.ttTitle.textContent = pad(" " + String(p.title).toUpperCase(), 39 - by.length) + by + " ";
      for (i = 0; i < 22; i++) {
        var l = p.lines && p.lines[i];
        els.ttRows[i].textContent = l ? pad(l.text, 40) : " ";
        els.ttRows[i].style.color = l ? TT_COLOR[l.color] || "#fff" : "#fff";
      }
    }
    function ttGo(n) { ttPage = n; ttTyped = ""; ttMissingAt = 0; renderTeletext(); }
    function teletextKey(code) {
      var pages = (data && data.teletext) || {};
      if (code >= 48 && code <= 57) {
        var dg = code - 48;
        if (!ttTyped && (dg === 0 || dg === 9)) return true;
        ttTyped += dg;
        if (ttTyped.length === 3) ttGo(parseInt(ttTyped, 10)); else renderTeletext();
      } else if (code === UP || code === RIGHT || CH_UP[code] || code === K_GREEN) ttGo(ttStep(pages, ttPage, 1));
      else if (code === DOWN || code === LEFT || CH_DOWN[code] || code === K_RED) ttGo(ttStep(pages, ttPage, -1));
      else if (code === OK || code === K_YELLOW) ttGo(100);
      else if (code === K_BLUE) open("guide");
      return true;
    }

    function renderGuide() {
      var g = (data && data.guide) || [], now = Date.now(), rows = [];
      for (var i = 0; i < g.length; i++) (function (p) {
        var mark = p.now === "on_air" ? "● ON AIR" : p.now === "next" ? "NEXT" : p.state === "failed" ? "✗" : p.state === "cancelled" ? "–" : "✓";
        var icon = p.kind === "shift" ? "🌙 " : p.kind === "briefing" ? "◆ " : "";
        rows.push({ text: hhmm(p.start) + "–" + hhmm(p.end) + "   " + p.agent + "   " + icon + p.title + "      " + mark,
          color: p.now === "on_air" ? "var(--fgBright,#c0caf5)" : p.now === "past" ? "var(--muted,#565f89)" : "",
          act: function () {
            if (p.kind === "shift") open(p.now === "on_air" ? "live" : "shift");
            else if (p.kind === "briefing") open("briefing");
            else if (p.now !== "past") open("live");
          } });
      })(g[i]);
      var keep = sel;
      listScreen("📺 " + ((opts.tr && opts.tr("guide_title")) || "Agents' Guide"), rows,
        g.length ? hhmm(now) + "   OK ›   🔴 AI LIVE   🔵 Televideo" : "Nothing on the Guide yet: agents add programmes with tv_guide_add.");
      els.bug.textContent = "📺";
      sel = keep; paintSel();
    }

    // ---- open / close / keys / polling --------------------------------------------------------------------------
    function open(m, arg) {
      mode = m; sel = 0; onCamera = ""; dialog = null;
      if (m === "live") { frame("● LIVE", RED, "NEWS"); stage(); els.hint.textContent = "◀ ▶ camera   BACK"; renderLive(); }
      else if (m === "replay") {
        frame("▶ REPLAY", "var(--blue,#2ac3de)", "TAPE"); stage();
        els.osd = el("div", "position:absolute;right:" + vh(80) + ";top:" + vh(184) + ";color:#fff;font-weight:bold;font-size:" + vh(60) + ";text-shadow:" + vh(4) + " " + vh(4) + " 0 rgba(0,0,0,.8)", "PLAY ▶");
        els.osdDate = el("div", "position:absolute;right:" + vh(80) + ";bottom:" + vh(332) + ";color:#fff;font-weight:bold;font-size:" + vh(40) + ";text-shadow:" + vh(4) + " " + vh(4) + " 0 rgba(0,0,0,.8)", "");
        els.timeline = el("div", "position:absolute;left:" + vh(72) + ";right:" + vh(72) + ";bottom:" + vh(280) + ";height:" + vh(20) + ";background:rgba(0,0,0,.5)");
        layer.appendChild(els.osd); layer.appendChild(els.osdDate); layer.appendChild(els.timeline);
        els.hint.textContent = "OK ⏯   ▲ ×2   ◀ ▶ highlights   BACK";
        tape = tapeOf(data && data.shift); cursor = 0; paused = false; stopped = false; speed = 1;
        renderTape();
      } else if (m === "briefing") {
        frame("◆ MORNING", "var(--yellow,#e0af68)", "NEWS"); stage();
        els.hint.textContent = "◀ ▶ news   OK / BACK";
        section = 0; sectionAt = Date.now();
        var b = data && data.briefing;
        cutTo((b && b.by) || "AI");
        if (b) { lastBriefing = b.at; post("/v1/tvstudio/briefing-shown", {}).then(null, function () { return null; }); }
        if (b && b.say && opts.speak) opts.speak(b.say);
        renderBriefing();
      } else if (m === "debate") {
        frame("◆ DEBATE", "var(--accent2,#bb9af7)", null); stage();
        els.stage.style.display = "none";
        els.panels = el("div", "position:absolute;left:" + vh(72) + ";right:" + vh(72) + ";top:" + vh(200) + ";bottom:" + vh(340) + ";display:table;table-layout:fixed;border-spacing:" + vh(16) + " 0");
        layer.appendChild(els.panels);
        els.hint.textContent = "◀ ▶ answer   OK pick   BACK";
        debateId = arg || (data && data.debate && data.debate.id) || ""; pick = 0; pickAt = Date.now();
        if (debateId) lastDebate = debateId;
        renderDebate();
      } else if (m === "teletext") { ttFrame(); ttPage = arg || 100; ttTyped = ""; renderTeletext(); }
      else if (m === "guide") renderGuide();
      else if (m === "shift") renderShift();
      else if (m === "skills") renderSkills(arg);
      clearInterval(timer);
      timer = setInterval(function () {
        if (!layer) return;
        if (mode === "replay") step();
        else if (mode === "live") renderLive();
        else if (mode === "briefing") renderBriefing();
        else if (mode === "debate") renderDebate();   // the answers as they come (the app polls every 2 s while open)
        else if (mode === "teletext") renderTeletext();
      }, mode === "replay" ? 2500 : 1000);
      clearInterval(tickerTimer);
      tickerTimer = setInterval(scrollTicker, 33);
      if (m === "live" || m === "shift" || m === "skills" || m === "teletext" || m === "guide") refreshNow();
    }

    function close(keepMode) {
      clearInterval(timer); clearInterval(tickerTimer);
      if (layer && layer.parentNode) layer.parentNode.removeChild(layer);
      layer = null; dialog = null; els = {};
      if (!keepMode) mode = null;
    }

    function refreshNow() {
      return tick(true).then(function () {
        if (!layer) return;
        if (mode === "shift") renderShift();
        else if (mode === "skills") renderSkills();
        else if (mode === "debate") renderDebate();
        else if (mode === "teletext") renderTeletext();
        else if (mode === "guide") renderGuide();
      });
    }

    function key(code) {
      if (!layer) return false;
      if (dialog) {
        if (code === OK && dialog.focus === 0 && dialog.onNo) { var d = dialog; closeDialog(); d.onNo(); return true; }
        return dialogKey(code);
      }
      if (BACK[code]) { close(); return true; }
      if (mode === "debate") return debateKey(code);
      if (mode === "teletext") return teletextKey(code);
      if (mode === "guide") {
        if (code === K_RED) { open("live"); return true; }
        if (code === K_BLUE) { open("teletext"); return true; }
      }
      if (mode === "briefing") {
        var secs = (data && data.briefing && data.briefing.sections) || [];
        if ((code === LEFT || code === RIGHT) && secs.length) { section = (section + (code === RIGHT ? 1 : -1) + secs.length) % secs.length; sectionAt = Date.now(); renderBriefing(); }
        else if (code === OK) close();
        return true;
      }
      if (mode === "live") {
        var a = (data && data.live && data.live.agents) || [];
        if ((code === LEFT || code === RIGHT) && a.length) {
          var i = 0;
          for (var k = 0; k < a.length; k++) if (a[k].agent === onCamera) i = k;
          var nx = a[(i + (code === RIGHT ? 1 : -1) + a.length) % a.length];
          handUntil = Date.now() + HAND_MS; cutAt = Date.now(); onCamera = nx.agent; cutTo(nx.agent); showAgent(nx, Date.now());
        }
        return true;
      }
      if (mode === "replay") {
        if (code === OK || PLAY[code]) {
          if (stopped && cursor >= tape.length - 1 && tape.length) { open("shift"); return true; }
          paused = !paused; renderTape();
        } else if (code === UP || FF[code]) { speed = speed >= 4 ? 1 : speed * 2; renderTape(); }
        else if (code === LEFT || code === RIGHT || REW[code]) {
          var dir = code === RIGHT ? 1 : -1, j;
          stopped = false;
          for (j = cursor + dir; j >= 0 && j < tape.length; j += dir) if (tape[j].kind === "fail" || tape[j].kind === "queued") break;
          cursor = Math.max(0, Math.min(tape.length - 1, j));
          renderTape();
        }
        return true;
      }
      if (code === UP || code === DOWN) { sel = Math.max(0, Math.min((els.rows || []).length - 1, sel + (code === DOWN ? 1 : -1))); paintSel(); return true; }
      if (code === OK && els.rows && els.rows[sel]) { els.rows[sel].act(); return true; }
      return true;
    }

    /** Polls the bridge; opens the morning page and an agent's skill question by itself when the Home is idle. */
    function tick(force) {
      if (busy && !force) return Promise.resolve(null);
      if (!fetchFn || !opts.base() || !opts.token()) return Promise.resolve(null);
      busy = true;
      return fetchFn(opts.base() + "/v1/tvlive", { headers: { "X-Omarchy-Token": opts.token() } })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (v) {
          busy = false;
          if (!v) return null;
          var guideWas = data && JSON.stringify(data.guide || []);
          data = v;
          if (mode === "guide" && layer && guideWas !== JSON.stringify(v.guide || [])) renderGuide();   // the schedule as it changes
          var idle = !layer && (!opts.idle || opts.idle());
          if (v.skillAsk && v.skillAsk.id && lastAsk !== v.skillAsk.id && (idle || mode === "skills")) { lastAsk = v.skillAsk.id; open("skills", v.skillAsk.id); }
          if (v.briefing && v.briefing.at && lastBriefing !== v.briefing.at && idle) { open("briefing"); idle = false; }
          if (v.debate && v.debate.state === "open" && lastDebate !== v.debate.id && (idle || mode === "live")) { open("debate", v.debate.id); idle = false; }
          var s = v.shift || {};
          if (s.wake && Date.now() >= s.wake && Date.now() - s.wake < 18 * 3600000 && opts.wakeShown && opts.wakeShown() !== s.wake && idle) {
            opts.markWake(s.wake);
            open("shift");
          }
          return v;
        }, function () { busy = false; return null; });
    }

    return { open: open, close: close, key: key, tick: tick, isOpen: function () { return !!layer; }, mode: function () { return mode; },
      _state: function () { return { onCamera: onCamera, cursor: cursor, paused: paused, stopped: stopped, speed: speed, dialog: !!dialog, sel: sel }; } };
  }

  /** Televideo: the pages that exist (the index 100 first), and the next / previous one from any number. */
  function ttNumbers(pages) {
    var ns = [100];
    for (var k in pages) if (Object.prototype.hasOwnProperty.call(pages, k)) ns.push(parseInt(k, 10));
    ns.sort(function (a, b) { return a - b; });
    return ns;
  }
  function ttStep(pages, from, dir) {
    var ns = ttNumbers(pages), i = ns.indexOf(from);
    if (i >= 0) return ns[(i + dir + ns.length) % ns.length];
    for (i = 0; i < ns.length && ns[i] < from; i++) { /* the first page after */ }
    if (dir < 0) i--;
    else if (i >= ns.length) i = 0;
    return ns[(i + ns.length) % ns.length];
  }
  function ttIndex(pages) {
    var ns = ttNumbers(pages), lines = [];
    if (ns.length === 1) return { page: 100, title: "INDEX", by: "OmarchyTV", lines: [{ text: "No pages yet.", color: "white" }, { text: "", color: "white" },
      { text: "Agents publish them with", color: "white" }, { text: "tv_teletext_publish {page: 101-899}", color: "cyan" }] };
    for (var i = 1; i < ns.length && lines.length < 22; i++) {
      var t = pages[ns[i]].title;
      while (t.length < 36) t += ".";
      lines.push({ text: t.slice(0, 36) + ns[i], color: Math.floor(ns[i] / 100) % 2 ? "yellow" : "cyan" });
    }
    return { page: 100, title: "INDEX", by: "OmarchyTV", lines: lines };
  }

  var api = { create: create, director: director, ttStep: ttStep, ttIndex: ttIndex, tapeOf: tapeOf, initials: initials, vcrDate: vcrDate };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.OmarchyAiLive = api;
})(typeof window !== "undefined" ? window : this);
