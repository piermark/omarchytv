/* GrokBot Office on LG webOS and Samsung Tizen (v2.20, optional, off by default): your Grok Bot bots at their
 * desks in a small 2.5D office, drawn live on a canvas (our own art: no images, no logos copied).
 * The bots and the weekly usage are only what the PC or Grok Bot sent (omarchy-tv office push, or the "office"
 * field of a Grok Bot diretto reply): nothing is made up. No bots: an empty office and how to connect Grok Bot.
 * No usage numbers: no usage card. "Ask GrokBot" and "Hire" go through Grok Bot diretto (grokdirect.js: the user's
 * own webhook routine, the reply through ntfy); this file only adds an "office" object to the same request.
 * 2.20 multi-provider: every bot has a provider tag (grok by default; claude, chatgpt, ...). The Ask box picks who
 * answers: Grok Bot (the webhook) or an AI on the PC (omarchy-link's assistant: POST /v1/ai/ask on the bridge, the
 * key stays on the PC). Hire for Claude / ChatGPT goes to the PC (POST /v1/office/hire), which checks the key and
 * saves the bot before saying yes. "providers_usage" shows each provider's real spend (Admin API keys on the PC).
 * Light on old TVs: at most 30 frames a second, the room is drawn once into an offscreen canvas, and nothing runs
 * while the page is hidden or the office is closed. Same rules as the Android launcher's Office.java.
 * ES5. Host:
 *   var ui = OmarchyOffice.open(el, { lang, theme, data, store, speak(text, lang), ask(q, office, cb(err, reply, officePayload)),
 *     listen(cb(err, text)) | null, onExit, providers() -> [{id, name, provider}], pcAsk(id, q, cb(err, text)),
 *     pcHire({name, provider, role, description}, cb(err, res {bot, office})) }) -> { key(name, e) -> handled, setData(d), destroy() } */
(function (root) {
  "use strict";
  var MAX_BOTS = 24, SHOWN = 16, FRAME_MS = 1000 / 30, PENDING_KEY = "omarchytv.office.pending", PENDING_TTL = 24 * 3600 * 1000;
  var PALETTE = ["#f7768e", "#ff9e64", "#e0af68", "#9ece6a", "#73daca", "#7dcfff", "#7aa2f7", "#bb9af7", "#c0caf5", "#ff79c6", "#50fa7b"];
  var NAMED = { red: "#f7768e", orange: "#ff9e64", yellow: "#e0af68", green: "#9ece6a", teal: "#73daca", cyan: "#7dcfff", blue: "#7aa2f7",
    purple: "#bb9af7", violet: "#bb9af7", pink: "#ff79c6", white: "#c0caf5", lime: "#50fa7b", grey: "#a9b1d6", gray: "#a9b1d6" };
  var STATUS = ["working", "idle", "done", "error"];

  // ---- data (the same rules as Office.java) --------------------------------------------------------

  function clean(s, max) { return String(s == null ? "" : s).replace(/[\u0000-\u001F\u007F<>]/g, "").replace(/\s+/g, " ").replace(/^\s+|\s+$/g, "").slice(0, max); }
  function slug(s) { return String(s || "").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 40); }
  function hash(s) { var h = 0; s = String(s || ""); for (var i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0; return Math.abs(h); }
  /** "#rrggbb" or a colour name; anything else gets a palette colour picked from the name (stable). */
  function colorOf(c, name) {
    if (typeof c === "string") {
      var v = c.trim().toLowerCase();
      if (/^#[0-9a-f]{6}$/.test(v)) return v;
      if (/^#[0-9a-f]{3}$/.test(v)) return "#" + v[1] + v[1] + v[2] + v[2] + v[3] + v[3];
      if (NAMED[v]) return NAMED[v];
    }
    return PALETTE[hash(name) % PALETTE.length];
  }
  /** Which AI the bot runs on: a short lowercase word ("grok", "claude", "chatgpt", anything else); default "grok".
      Only a label on the nameplate: Ask and Hire always go to Grok Bot. */
  function provider(v) {
    var p = String(v == null ? "" : v).toLowerCase().replace(/[^a-z0-9._-]+/g, "").slice(0, 16);
    return p || "grok";
  }
  var PROVIDER_COLORS = { grok: "#7aa2f7", claude: "#ff9e64", anthropic: "#ff9e64", chatgpt: "#9ece6a", openai: "#9ece6a", gemini: "#bb9af7", google: "#bb9af7" };
  function providerColor(p) { return PROVIDER_COLORS[p] || "#a9b1d6"; }
  function bot(b) {
    if (!b || typeof b !== "object") return null;
    var name = clean(b.name || b.shortName, 24);
    if (!name) return null;
    var st = String(b.status || "").toLowerCase();
    return { id: slug(b.id || name) || slug(name) || "bot", name: name, role: clean(b.role, 40), color: colorOf(b.color || b.fill, name),
      status: STATUS.indexOf(st) >= 0 ? st : "idle", task: clean(b.task, 60), provider: provider(b.provider),
      assistant: String(b.assistant || "").toLowerCase().replace(/[^a-z0-9._-]/g, "").slice(0, 32) };
  }
  function pct(u) {
    if (!u || typeof u !== "object") return null;
    var v = u.weekly_pct != null ? u.weekly_pct : u.weeklyPct != null ? u.weeklyPct : u.pct;
    if (typeof v === "string" && /^\s*\d+(\.\d+)?\s*%?\s*$/.test(v)) v = parseFloat(v);
    return typeof v === "number" && isFinite(v) && v >= 0 && v <= 100 ? Math.round(v * 10) / 10 : null;
  }
  function usage(u) {
    var p = pct(u);
    return p == null ? null : { pct: p, label: clean(u.label, 24), resets: clean(u.resets || u.resets_at, 32) };
  }
  /** One providers_usage row: real numbers only (spent >= 0; pct only with a budget), else null. */
  function spend(u) {
    if (!u || typeof u !== "object" || typeof u.spent !== "number" || !isFinite(u.spent) || u.spent < 0) return null;
    var r = { provider: provider(u.provider), spent: Math.round(u.spent * 100) / 100, unit: clean(u.unit || "$", 4) || "$", label: clean(u.label, 24),
      budget: typeof u.budget === "number" && isFinite(u.budget) && u.budget > 0 ? Math.round(u.budget * 100) / 100 : null, pct: null, resets: clean(u.resets, 32) };
    if (r.budget) r.pct = Math.max(0, Math.min(100, Math.round(u.spent / r.budget * 1000) / 10));
    return r;
  }
  function spends(a) {
    var out = [];
    if (Object.prototype.toString.call(a) !== "[object Array]") return out;
    for (var i = 0; i < a.length && out.length < 6; i++) { var r = spend(a[i]); if (r) out.push(r); }
    return out;
  }
  /** Untrusted JSON (string or object) -> { title, bots, usage, spend, at }; never throws. */
  function parse(j) {
    if (typeof j === "string") { try { j = JSON.parse(j); } catch (e) { j = null; } }
    var out = { title: "", bots: [], usage: null, spend: [], at: "" };
    if (!j || typeof j !== "object") return out;
    out.title = clean(j.title, 32);
    out.at = clean(j.updated_at || j.at, 32);
    var seen = {}, a = Object.prototype.toString.call(j.bots) === "[object Array]" ? j.bots : [];
    for (var i = 0; i < a.length && out.bots.length < MAX_BOTS; i++) {
      var b = bot(a[i]);
      if (!b || seen[b.id]) continue;
      seen[b.id] = true; out.bots.push(b);
    }
    out.usage = usage(j.usage);
    out.spend = spends(j.providers_usage || j.spend);   // j.spend: already parsed data handed back in
    return out;
  }
  /** A Grok Bot reply's "office" payload on top of the data: "bots" replaces the list, "hired" adds one, "usage" updates. */
  function applyReply(data, office) {
    data = data || parse(null);
    if (!office || typeof office !== "object") return data;
    var out = { title: data.title, bots: data.bots.slice(), usage: data.usage, spend: data.spend || [], at: data.at };
    if (Object.prototype.toString.call(office.bots) === "[object Array]") out.bots = parse({ bots: office.bots }).bots;
    var h = bot(office.hired);
    if (h && !out.bots.some(function (b) { return b.id === h.id; }) && out.bots.length < MAX_BOTS) out.bots.push(h);
    var u = usage(office.usage);
    if (u) out.usage = u;
    if (office.title) out.title = clean(office.title, 32);
    return out;
  }
  function toJson(d) {
    return { title: d.title || undefined, bots: d.bots.map(function (b) { return { id: b.id, name: b.name, role: b.role, color: b.color, status: b.status, task: b.task, provider: b.provider, assistant: b.assistant || undefined }; }),
      usage: d.usage ? { weekly_pct: d.usage.pct, label: d.usage.label, resets: d.usage.resets } : undefined,
      providers_usage: d.spend && d.spend.length ? d.spend.map(function (r) { return { provider: r.provider, spent: r.spent, unit: r.unit, label: r.label || undefined, budget: r.budget || undefined, resets: r.resets || undefined }; }) : undefined,
      updated_at: d.at || undefined };
  }

  // ---- what the user asked -------------------------------------------------------------------------

  var HIRE = /\b(hire|recruit|onboard|assumi|assumere|ingaggia|recluta|einstellen|stell\w*\s+ein|embauche|recrute|contrata|contratar)\b|\b(add|new|aggiungi|nuovo)\s+(a\s+|un\s+)?bot\b/i;
  var OPEN = /\b(office|ufficio|b(ü|u)ro|bureau|oficina|my bots|i miei bot|team)\b/i;
  var NAME = /(?:called|named|name it|chiamat[oa]|di nome|nome|namens|genannt|nommé|appelé|llamad[oa])\s+["“'«]?([A-Za-zÀ-ÿ0-9_-]{2,20})/i;
  /** { type: "hire", name, role } | { type: "open" } | { type: "ask" }. Name and role only from the user's words. */
  function intent(text) {
    var s = clean(text, 240);
    if (!s) return { type: "none" };
    if (HIRE.test(s)) {
      var m = NAME.exec(s), name = m ? m[1] : "";
      var role = s.replace(NAME, " ").replace(HIRE, " ")
        .replace(/^\s*(please|per favore|bitte|s'il te plaît|por favor|grokbot|grok bot|ok|hey)[\s,]+/i, " ")
        .replace(/^[\s,.:;!-]*(a|an|un|una|uno|ein|eine|einen|une|me)?\s*(new|nuovo|nuova|neuen|nouveau|nuevo)?\s*(bot|agent|agente)?\s*(that|who|to|for|che|per|a|der|die|qui|pour|que|para)?\s+/i, "")
        .replace(/[\s.!?]+$/, "");
      var pv = /\b(claude|anthropic)\b/i.test(s) ? "claude" : /\b(chat\s?gpt|openai|gpt)\b/i.test(s) ? "chatgpt" : "grok";
      if (pv !== "grok") role = role.replace(/\b(on|with|su|con|mit|avec|sur)?\s*(claude|anthropic|chat\s?gpt|openai|gpt)\b/ig, " ").replace(/^[\s,.:;!-]*(bot|agent|agente)?\s*(that|who|to|for|che|per|der|die|qui|pour|que|para)?\s+/i, "").replace(/\s+/g, " ").trim();
      return { type: "hire", name: name, role: clean(role, 60), provider: pv };
    }
    if (OPEN.test(s) && s.length < 60 && !/\?\s*$/.test(s)) return { type: "open" };
    return { type: "ask" };
  }
  function names(d) { return (d && d.bots || []).map(function (b) { return b.name; }); }
  /** The question and "office" object of a hire request (the routine creates the bot and answers with office.hired). */
  function hireRequest(draft, d) {
    var q = "Hire a new bot for my GrokBot Office" + (draft.name ? " named " + draft.name : "") + (draft.role ? ": " + draft.role : "") +
      ". Create it, then reply with office.hired = {name, role, color}.";
    return { question: q, office: { action: "hire", name: draft.name || "", role: draft.role || "", description: draft.description || draft.role || "", bots: names(d) } };
  }
  function askRequest(q, d) { return { question: q, office: { action: "ask", bots: names(d) } }; }
  /** Grok Bot diretto's request body (grokdirect.js body()) plus the office object. */
  function requestBody(gbdBody, office) {
    var b = JSON.parse(gbdBody);
    b.office = office;
    return JSON.stringify(b);
  }
  /** The "office" object of the reply with this id in an ntfy poll body, or null. Untrusted. */
  function findOffice(ndjson, id) {
    var lines = String(ndjson || "").split("\n");
    for (var i = 0; i < lines.length; i++) {
      try {
        var ev = JSON.parse(lines[i]);
        if (!ev || ev.event !== "message") continue;
        var m = JSON.parse(ev.message || "");
        if (m && m.id === id) return m.office && typeof m.office === "object" ? m.office : null;
      } catch (e) {}
    }
    return null;
  }
  /** A pending hire is done when the bots now hold its name (or, without a name, when there are more bots). */
  function confirmed(d, p) {
    if (!p) return false;
    var bots = d && d.bots || [];
    if (p.name) return bots.some(function (b) { return b.name.toLowerCase() === p.name.toLowerCase(); });
    return bots.length > (p.before || 0);
  }
  /** Ask Grok Bot diretto with the office object; cb(err, reply, officePayload). Same polling as grokdirect.js ask(). */
  function ask(G, s, q, lang, apps, context, office, cb, deps) {
    deps = deps || {};
    var f = deps.fetch || root.fetch, now = deps.now || Date.now, wait = deps.setTimeout || setTimeout;
    if (!G || !G.enabled(s)) { cb("not_set"); return; }
    if (typeof f !== "function") { cb("This TV browser cannot make web requests."); return; }
    var id = deps.id || ("o" + Math.floor(now()).toString(36) + Math.floor(Math.random() * 1e9).toString(36)).slice(0, 16);
    var since = Math.floor(now() / 1000) - 5, end = now() + G.TIMEOUT_MS;
    f(s.url, { method: "POST", headers: G.headers(s.key), body: requestBody(G.body(q, lang, s.topic, id, apps, context, s.server), office) }).then(function (r) {
      if (!r.ok) { cb(r.status === 401 || r.status === 403 ? "The webhook refused the key (HTTP " + r.status + "): check it in Settings." : "The webhook answered HTTP " + r.status + "."); return; }
      (function poll() {
        if (now() >= end) { cb("No answer from Grok Bot within 90 s. Check the routine and its ntfy step."); return; }
        wait(function () {
          f(G.pollUrl(s.topic, since, s.server), G.validToken(s.ntfyToken) ? { headers: { "Authorization": "Bearer " + s.ntfyToken } } : undefined)
            .then(function (p) { return p.ok ? p.text() : ""; }).then(function (t) {
              var rep = G.find(t, id);
              if (rep) cb(null, rep, findOffice(t, id)); else poll();
            }).catch(poll);
        }, G.POLL_MS);
      })();
    }).catch(function () { cb("Could not reach the webhook."); });
  }

  // ---- the room ------------------------------------------------------------------------------------

  var W = 1280, H = 720, TW = 92, TH = 46, OX = 640, OY = 178, ROOM = 10;
  function iso(x, y) { return { x: OX + (x - y) * TW / 2, y: OY + (x + y) * TH / 2 }; }
  /** Desk spots (room tiles) for n bots: rows of 4, at most 16 shown, at least 6 desks so the office never looks bare. */
  function layout(n) {
    var k = Math.min(SHOWN, Math.max(6, n)), cols = k <= 6 ? 3 : 4, rows = Math.ceil(k / cols), out = [];
    var gx = cols === 3 ? 2.9 : 2.3, gy = rows <= 2 ? 2.9 : 2.3, x0 = ROOM / 2 - (cols - 1) * gx / 2 + 0.2, y0 = ROOM / 2 - (rows - 1) * gy / 2 + 0.2;
    for (var i = 0; i < k; i++) out.push({ x: x0 + (i % cols) * gx, y: y0 + Math.floor(i / cols) * gy });
    return out;
  }
  /** True when a frame is due (30 fps cap); the time a rAF callback got. */
  function due(last, now) { return now - last >= FRAME_MS - 2; }

  function shade(hex, k) {
    var n = parseInt(hex.slice(1), 16), r = n >> 16 & 255, g = n >> 8 & 255, b = n & 255;
    function m(v) { return Math.max(0, Math.min(255, Math.round(k >= 0 ? v + (255 - v) * k : v * (1 + k)))); }
    return "rgb(" + m(r) + "," + m(g) + "," + m(b) + ")";
  }
  function rgba(hex, a) { var n = parseInt(hex.slice(1), 16); return "rgba(" + (n >> 16 & 255) + "," + (n >> 8 & 255) + "," + (n & 255) + "," + a + ")"; }
  function poly(c, pts) { c.beginPath(); c.moveTo(pts[0].x, pts[0].y); for (var i = 1; i < pts.length; i++) c.lineTo(pts[i].x, pts[i].y); c.closePath(); }
  function rrect(c, x, y, w, h, r) {
    c.beginPath(); c.moveTo(x + r, y); c.lineTo(x + w - r, y); c.quadraticCurveTo(x + w, y, x + w, y + r); c.lineTo(x + w, y + h - r);
    c.quadraticCurveTo(x + w, y + h, x + w - r, y + h); c.lineTo(x + r, y + h); c.quadraticCurveTo(x, y + h, x, y + h - r); c.lineTo(x, y + r);
    c.quadraticCurveTo(x, y, x + r, y); c.closePath();
  }
  /** OmarchyTV's mark for the office: a rounded terminal tile with a prompt and an orbit (original, no Grok/xAI logo). */
  function mark(c, x, y, s, acc, fg) {
    c.save(); c.translate(x, y);
    c.lineWidth = s * 0.07; c.strokeStyle = acc; c.fillStyle = "rgba(0,0,0,.35)";
    rrect(c, -s / 2, -s / 2, s, s, s * 0.22); c.fill(); c.stroke();
    c.strokeStyle = fg; c.lineWidth = s * 0.08; c.lineCap = "round"; c.lineJoin = "round";
    c.beginPath(); c.moveTo(-s * 0.24, -s * 0.16); c.lineTo(-s * 0.06, 0); c.lineTo(-s * 0.24, s * 0.16); c.stroke();
    c.beginPath(); c.moveTo(s * 0.02, s * 0.18); c.lineTo(s * 0.24, s * 0.18); c.stroke();
    c.strokeStyle = acc; c.lineWidth = s * 0.035; c.beginPath();
    if (c.ellipse) c.ellipse(0, 0, s * 0.72, s * 0.26, -0.45, 0, Math.PI * 2); c.stroke();
    c.fillStyle = acc; c.beginPath(); c.arc(s * 0.62, -s * 0.33, s * 0.07, 0, Math.PI * 2); c.fill();
    c.restore();
  }
  function room(c, th, sign) {
    var bg = th.bg, acc = th.accent, g;
    g = c.createLinearGradient(0, 0, 0, H); g.addColorStop(0, shade(bg, -0.35)); g.addColorStop(1, shade(bg, -0.6));
    c.fillStyle = g; c.fillRect(0, 0, W, H);
    var a = iso(0, 0), b = iso(ROOM, 0), d = iso(0, ROOM), e = iso(ROOM, ROOM), wall = 250;
    // back walls
    g = c.createLinearGradient(0, a.y - wall, 0, a.y + 120); g.addColorStop(0, shade(bg, 0.1)); g.addColorStop(1, shade(bg, -0.15));
    c.fillStyle = g; poly(c, [{ x: d.x, y: d.y - wall }, { x: a.x, y: a.y - wall }, a, d]); c.fill();
    g = c.createLinearGradient(0, a.y - wall, 0, a.y + 120); g.addColorStop(0, shade(bg, 0.16)); g.addColorStop(1, shade(bg, -0.08));
    c.fillStyle = g; poly(c, [{ x: a.x, y: a.y - wall }, { x: b.x, y: b.y - wall }, b, a]); c.fill();
    c.strokeStyle = rgba(acc, 0.35); c.lineWidth = 2;
    c.beginPath(); c.moveTo(a.x, a.y - wall); c.lineTo(a.x, a.y); c.stroke();
    // windows on the left wall, with a city at night
    for (var w = 0; w < 3; w++) {
      var p0 = iso(0, 1.6 + w * 2.6), p1 = iso(0, 3.6 + w * 2.6), top = 175, bot = 70;
      c.fillStyle = shade(bg, -0.55); poly(c, [{ x: p0.x, y: p0.y - top }, { x: p1.x, y: p1.y - top }, { x: p1.x, y: p1.y - bot }, { x: p0.x, y: p0.y - bot }]); c.fill();
      c.save(); c.clip();
      for (var s = 0; s < 9; s++) {
        var hx = p0.x + (p1.x - p0.x) * (s / 9), hh = 20 + (hash("b" + w + s) % 60);
        c.fillStyle = shade(bg, -0.25); c.fillRect(hx, p0.y + (p1.y - p0.y) * (s / 9) - bot - hh, (p1.x - p0.x) / 9 + 1, hh + 30);
        c.fillStyle = rgba(th.yellow || "#e0af68", 0.55);
        for (var l = 0; l < 3; l++) if (hash("l" + w + s + l) % 3 === 0) c.fillRect(hx + 3 + l * 5, p0.y + (p1.y - p0.y) * (s / 9) - bot - hh + 6 + l * 7, 2, 3);
      }
      c.restore();
      c.strokeStyle = rgba(acc, 0.5); c.lineWidth = 2; poly(c, [{ x: p0.x, y: p0.y - top }, { x: p1.x, y: p1.y - top }, { x: p1.x, y: p1.y - bot }, { x: p0.x, y: p0.y - bot }]); c.stroke();
    }
    // floor tiles
    for (var i = 0; i < ROOM; i++) for (var j = 0; j < ROOM; j++) {
      c.fillStyle = (i + j) % 2 ? shade(bg, -0.05) : shade(bg, 0.03);
      poly(c, [iso(i, j), iso(i + 1, j), iso(i + 1, j + 1), iso(i, j + 1)]); c.fill();
    }
    c.strokeStyle = rgba(acc, 0.12); c.lineWidth = 1;
    for (i = 0; i <= ROOM; i++) { poly(c, [iso(i, 0), iso(i, ROOM)]); c.stroke(); poly(c, [iso(0, i), iso(ROOM, i)]); c.stroke(); }
    c.strokeStyle = rgba(acc, 0.45); c.lineWidth = 2; poly(c, [a, b, e, d]); c.stroke();
    // the door the new bots walk in through (left wall, near the front)
    var d0 = iso(0, 8.3), d1 = iso(0, 9.7);
    c.fillStyle = shade(bg, -0.42); poly(c, [{ x: d0.x, y: d0.y - 150 }, { x: d1.x, y: d1.y - 150 }, d1, d0]); c.fill();
    c.strokeStyle = rgba(acc, 0.6); c.lineWidth = 2; poly(c, [{ x: d0.x, y: d0.y - 150 }, { x: d1.x, y: d1.y - 150 }, d1, d0]); c.stroke();
    c.fillStyle = rgba(th.green || "#9ece6a", 0.9); c.beginPath(); c.arc(d1.x + 8, d1.y - 75, 3, 0, Math.PI * 2); c.fill();
    // a rug and a plant
    c.fillStyle = rgba(acc, 0.08); poly(c, [iso(1.5, 1.4), iso(9.2, 1.4), iso(9.2, 9.2), iso(1.5, 9.2)]); c.fill();
    var pl = iso(9.3, 0.6);
    c.fillStyle = shade(bg, 0.2); rrect(c, pl.x - 14, pl.y - 26, 28, 26, 5); c.fill();
    c.fillStyle = th.green || "#9ece6a";
    for (var f = 0; f < 6; f++) { c.beginPath(); if (c.ellipse) c.ellipse(pl.x + Math.cos(f) * 12, pl.y - 40 - (f % 3) * 10, 8, 16, f, 0, Math.PI * 2); c.fill(); }
    if (sign) neon(c, th, 1);
  }
  function signBox() { var a = iso(4.3, 0), b = iso(8.6, 0); return { a: a, b: b }; }
  function neon(c, th, alpha) {
    var s = signBox(), col = th.magenta || th.red || "#ff79c6", mx = (s.a.x + s.b.x) / 2, my = (s.a.y + s.b.y) / 2 - 168;
    c.save(); c.translate(mx, my); c.transform(1, 0.5, 0, 1, 0, 0);
    c.globalAlpha = alpha;
    c.font = "italic bold 46px CaskaydiaMono, monospace"; c.textAlign = "center"; c.textBaseline = "middle";
    c.shadowColor = col; c.shadowBlur = 22; c.fillStyle = col; c.fillText("ship it", 0, 0);
    c.shadowBlur = 8; c.fillStyle = "#fff"; c.globalAlpha = alpha * 0.7; c.fillText("ship it", 0, 0);
    c.shadowBlur = 0; c.globalAlpha = alpha * 0.8; c.strokeStyle = col; c.lineWidth = 3; rrect(c, -120, -40, 240, 80, 14); c.stroke();
    c.restore();
  }
  function desk(c, th) {   // one desk sprite (offscreen), origin at its tile centre
    var bg = th.bg, w = 1.25, d = 0.7, h = 32, P = function (x, y) { return { x: (x - y) * TW / 2, y: (x + y) * TH / 2 }; };
    var t0 = P(-w / 2, -d / 2), t1 = P(w / 2, -d / 2), t2 = P(w / 2, d / 2), t3 = P(-w / 2, d / 2);
    c.fillStyle = shade(bg, -0.3); poly(c, [t3, t2, { x: t2.x, y: t2.y + h }, { x: t3.x, y: t3.y + h }]); c.fill();
    c.fillStyle = shade(bg, -0.18); poly(c, [t2, t1, { x: t1.x, y: t1.y + h }, { x: t2.x, y: t2.y + h }]); c.fill();
    c.fillStyle = shade(bg, 0.28); poly(c, [t0, t1, t2, t3]); c.fill();
    c.strokeStyle = rgba(th.accent, 0.4); c.lineWidth = 1.5; poly(c, [t0, t1, t2, t3]); c.stroke();
    // a laptop on the right half: its lid faces the bot, so we see the back, low enough to keep the face in view
    var k = P(0.28, 0.02), lid = 17;
    c.fillStyle = shade(bg, 0.5); poly(c, [{ x: k.x - 20, y: k.y }, { x: k.x + 2, y: k.y - 11 }, { x: k.x + 24, y: k.y }, { x: k.x + 2, y: k.y + 11 }]); c.fill();
    c.fillStyle = shade(bg, -0.4); poly(c, [{ x: k.x - 20, y: k.y }, { x: k.x + 2, y: k.y + 11 }, { x: k.x + 2, y: k.y + 11 - lid }, { x: k.x - 20, y: k.y - lid }]); c.fill();
    c.fillStyle = rgba(th.accent, 0.8); c.beginPath(); c.arc(k.x - 9, k.y - lid / 2 + 3, 2.2, 0, Math.PI * 2); c.fill();
    var m = P(-0.35, -0.1);   // a mug
    c.fillStyle = shade(th.accent2 || th.accent, -0.1); rrect(c, m.x - 5, m.y - 11, 10, 11, 2); c.fill();
  }
  function blob(c, x, y, s, col, t, o) {
    var bob = Math.sin(t * 2.2 + o.phase) * s * 0.03, walk = o.walking ? Math.abs(Math.sin(t * 9)) * s * 0.12 : 0;
    var sq = o.walking ? Math.sin(t * 18) * 0.05 : Math.sin(t * 2.2 + o.phase) * 0.025;
    var rx = s * 0.5 * (1 + sq), ry = s * 0.56 * (1 - sq), cy = y - ry - walk + bob;
    c.save();
    if (o.pending) c.globalAlpha = 0.55 + 0.25 * Math.sin(t * 4);
    c.fillStyle = "rgba(0,0,0,.28)"; c.beginPath(); if (c.ellipse) c.ellipse(x, y, rx * 0.9, rx * 0.32, 0, 0, Math.PI * 2); c.fill();
    var g = c.createRadialGradient(x - rx * 0.35, cy - ry * 0.4, s * 0.05, x, cy, s * 0.7);
    g.addColorStop(0, shade(col, 0.45)); g.addColorStop(0.6, col); g.addColorStop(1, shade(col, -0.35));
    c.fillStyle = g; c.beginPath();
    // a soft blob: a circle pulled down at the sides
    c.moveTo(x - rx, cy + ry * 0.35);
    c.bezierCurveTo(x - rx * 1.05, cy - ry * 1.05, x + rx * 1.05, cy - ry * 1.05, x + rx, cy + ry * 0.35);
    c.bezierCurveTo(x + rx * 0.95, cy + ry * 0.95, x - rx * 0.95, cy + ry * 0.95, x - rx, cy + ry * 0.35);
    c.fill();
    var blink = (Math.floor(t * 10 + o.phase * 7) % 47) === 0 ? 0.15 : 1, look = o.walking ? 0.25 : Math.sin(t * 0.7 + o.phase) * 0.18;
    for (var e = -1; e <= 1; e += 2) {
      c.fillStyle = "#fff"; c.beginPath(); if (c.ellipse) c.ellipse(x + e * rx * 0.33 + look * rx * 0.3, cy - ry * 0.12, s * 0.1, s * 0.13 * blink, 0, 0, Math.PI * 2); c.fill();
      c.fillStyle = "#111"; c.beginPath(); if (c.ellipse) c.ellipse(x + e * rx * 0.33 + look * rx * 0.45, cy - ry * 0.08, s * 0.05, s * 0.07 * blink, 0, 0, Math.PI * 2); c.fill();
    }
    if (o.status === "working" && !o.walking) {   // little hands on the keyboard
      c.fillStyle = shade(col, 0.2);
      for (var hnd = -1; hnd <= 1; hnd += 2) { c.beginPath(); c.arc(x + hnd * rx * 0.55, cy + ry * 0.62 - Math.abs(Math.sin(t * 14 + hnd)) * 4, s * 0.08, 0, Math.PI * 2); c.fill(); }
    } else if (o.status === "done") {
      c.strokeStyle = "#111"; c.lineWidth = 2; c.beginPath(); c.arc(x, cy + ry * 0.2, s * 0.09, 0.15 * Math.PI, 0.85 * Math.PI); c.stroke();
    } else if (o.status === "error") {
      c.fillStyle = "#f7768e"; c.font = "bold " + Math.round(s * 0.35) + "px sans-serif"; c.textAlign = "center"; c.fillText("!", x + rx, cy - ry);
    }
    c.restore();
    return cy - ry;
  }
  function plate(c, x, y, txt, col, th, tag, prov) {
    c.save();
    c.font = "bold 11px CaskaydiaMono, monospace";
    var pl = prov ? prov.slice(0, 10) : "", pw = pl ? c.measureText(pl).width + 10 : 0;
    c.font = "bold 15px CaskaydiaMono, monospace"; c.textAlign = "left"; c.textBaseline = "middle";
    var nw = c.measureText(txt).width, tw = tag ? c.measureText(tag).width + 12 : 0, w = Math.min(270, nw + tw + pw + (pw ? 6 : 0) + 34), h = 24, l = x - w / 2;
    c.fillStyle = rgba(th.bg, 0.86); rrect(c, l, y - h, w, h, 8); c.fill();
    c.strokeStyle = col; c.lineWidth = 1.5;
    if (tag && c.setLineDash) c.setLineDash([4, 3]);
    rrect(c, l, y - h, w, h, 8); c.stroke();
    if (c.setLineDash) c.setLineDash([]);
    c.fillStyle = col; c.beginPath(); c.arc(l + 13, y - h / 2, 4, 0, Math.PI * 2); c.fill();
    c.fillStyle = th.bright || "#c0caf5"; c.fillText(txt, l + 23, y - h / 2 + 1);
    if (pl) {   // the provider: a small generic text tag, no logos
      var pc = providerColor(prov), px = l + 23 + nw + 6;
      c.fillStyle = rgba(pc, 0.2); rrect(c, px, y - h / 2 - 7, pw, 14, 4); c.fill();
      c.font = "bold 11px CaskaydiaMono, monospace"; c.fillStyle = pc; c.fillText(pl, px + 5, y - h / 2 + 1);
      c.font = "bold 15px CaskaydiaMono, monospace";
    }
    if (tag) { c.fillStyle = th.yellow || "#e0af68"; c.fillText(tag, l + w - tw - 2, y - h / 2 + 1); }
    c.restore();
  }

  // ---- the UI --------------------------------------------------------------------------------------

  var T = {
    en: { ask: "Ask GrokBot", listening: "Listening…", thinking: "Thinking…", anything: "Anything else?", bots: "%s bots", one_bot: "1 bot", usage: "Weekly usage", resets: "resets %s",
      opening: "On it. Opening your office…", hire: "Hire", hire_title: "Hire a new bot", name: "Name", role: "Role", picked: "Grok Bot picks it", cancel: "Cancel",
      pending: "pending", sent: "Sent. %s walks in when Grok Bot confirms.", someone: "The new bot", welcome: "%s joined the office.",
      empty: "No bots here yet. Connect Grok Bot: turn on Grok Bot diretto (Companion settings), push your bots from the PC with omarchy-tv office push bots.json, or say: hire a bot called Trimmer to trim my tokens.",
      not_set: "Grok Bot diretto is off: turn it on and set the webhook in Companion settings.", mic: "Mic", enter: "Enter the office", no_mic: "No voice on this TV: type instead.",
      keys: "OK talk · ◀▶ choose · type to ask · BACK exit", heard: "I didn't catch that. Try again or type.", more: "+%s more", ai: "AI", pc_off: "Claude and ChatGPT answer from the PC: pair the PC and set the ask code (Companion settings).", hired_pc: "%s is hired on the PC and joined the office.", hiring: "Asking the PC to hire %s…", month: "this month", spend: "Spend" },
    it: { ask: "Chiedi a GrokBot", listening: "Ti ascolto…", thinking: "Ci penso…", anything: "Altro?", bots: "%s bot", one_bot: "1 bot", usage: "Uso settimanale", resets: "si azzera %s",
      opening: "Ci penso io. Apro il tuo ufficio…", hire: "Assumi", hire_title: "Assumi un nuovo bot", name: "Nome", role: "Ruolo", picked: "lo sceglie Grok Bot", cancel: "Annulla",
      pending: "in arrivo", sent: "Inviato. %s entra quando Grok Bot conferma.", someone: "Il nuovo bot", welcome: "%s è entrato in ufficio.",
      empty: "Ancora nessun bot. Collega Grok Bot: attiva Grok Bot diretto (impostazioni del Companion), manda i tuoi bot dal PC con omarchy-tv office push bots.json, oppure di': assumi un bot chiamato Trimmer per tagliare i token.",
      not_set: "Grok Bot diretto è spento: attivalo e imposta il webhook nelle impostazioni del Companion.", mic: "Microfono", enter: "Entra in ufficio", no_mic: "Niente voce su questa TV: scrivi.",
      keys: "OK parla · ◀▶ scegli · scrivi per chiedere · BACK esci", heard: "Non ho capito. Riprova o scrivi.", more: "+%s altri", ai: "AI", pc_off: "Claude e ChatGPT rispondono dal PC: abbina il PC e imposta il codice (impostazioni del Companion).", hired_pc: "%s è assunto sul PC ed è entrato in ufficio.", hiring: "Chiedo al PC di assumere %s…", month: "questo mese", spend: "Spesa" },
    de: { ask: "Frag GrokBot", listening: "Ich höre…", thinking: "Ich denke…", anything: "Sonst noch etwas?", bots: "%s Bots", one_bot: "1 Bot", usage: "Wöchentliche Nutzung", resets: "zurück am %s",
      opening: "Mach ich. Ich öffne dein Büro…", hire: "Einstellen", hire_title: "Neuen Bot einstellen", name: "Name", role: "Rolle", picked: "Grok Bot wählt", cancel: "Abbrechen",
      pending: "kommt", sent: "Gesendet. %s kommt, sobald Grok Bot bestätigt.", someone: "Der neue Bot", welcome: "%s ist jetzt im Büro.",
      empty: "Noch keine Bots. Verbinde Grok Bot: schalte Grok Bot diretto ein (Companion-Einstellungen), schick deine Bots vom PC mit omarchy-tv office push bots.json, oder sag: stell einen Bot namens Trimmer ein, der Tokens spart.",
      not_set: "Grok Bot diretto ist aus: schalte es ein und setze den Webhook in den Companion-Einstellungen.", mic: "Mikro", enter: "Ins Büro", no_mic: "Keine Sprache auf diesem TV: tippe.",
      keys: "OK sprechen · ◀▶ wählen · tippen zum Fragen · BACK schließen", heard: "Nicht verstanden. Nochmal oder tippen.", more: "+%s weitere", ai: "KI", pc_off: "Claude und ChatGPT antworten vom PC: koppel den PC und setze den Code (Companion-Einstellungen).", hired_pc: "%s ist auf dem PC eingestellt und im Büro.", hiring: "Der PC stellt %s ein…", month: "diesen Monat", spend: "Kosten" },
    fr: { ask: "Demande à GrokBot", listening: "J'écoute…", thinking: "Je réfléchis…", anything: "Autre chose ?", bots: "%s bots", one_bot: "1 bot", usage: "Usage hebdo", resets: "remise à zéro %s",
      opening: "C'est parti. J'ouvre ton bureau…", hire: "Embaucher", hire_title: "Embaucher un bot", name: "Nom", role: "Rôle", picked: "Grok Bot choisit", cancel: "Annuler",
      pending: "en route", sent: "Envoyé. %s arrive quand Grok Bot confirme.", someone: "Le nouveau bot", welcome: "%s a rejoint le bureau.",
      empty: "Pas encore de bots. Connecte Grok Bot : active Grok Bot diretto (réglages du Companion), envoie tes bots depuis le PC avec omarchy-tv office push bots.json, ou dis : embauche un bot appelé Trimmer pour réduire mes tokens.",
      not_set: "Grok Bot diretto est désactivé : active-le et règle le webhook dans les réglages du Companion.", mic: "Micro", enter: "Entrer au bureau", no_mic: "Pas de voix sur cette TV : tape.",
      keys: "OK parler · ◀▶ choisir · tape pour demander · BACK quitter", heard: "Pas compris. Réessaie ou tape.", more: "+%s autres", ai: "IA", pc_off: "Claude et ChatGPT répondent depuis le PC : associe le PC et règle le code (réglages du Companion).", hired_pc: "%s est embauché sur le PC et a rejoint le bureau.", hiring: "Le PC embauche %s…", month: "ce mois-ci", spend: "Dépenses" },
    es: { ask: "Pregunta a GrokBot", listening: "Te escucho…", thinking: "Pensando…", anything: "¿Algo más?", bots: "%s bots", one_bot: "1 bot", usage: "Uso semanal", resets: "se reinicia %s",
      opening: "Hecho. Abro tu oficina…", hire: "Contratar", hire_title: "Contratar un bot", name: "Nombre", role: "Rol", picked: "lo elige Grok Bot", cancel: "Cancelar",
      pending: "llegando", sent: "Enviado. %s entra cuando Grok Bot confirme.", someone: "El nuevo bot", welcome: "%s se unió a la oficina.",
      empty: "Aún no hay bots. Conecta Grok Bot: activa Grok Bot diretto (ajustes del Companion), envía tus bots desde el PC con omarchy-tv office push bots.json, o di: contrata un bot llamado Trimmer para recortar mis tokens.",
      not_set: "Grok Bot diretto está apagado: actívalo y pon el webhook en los ajustes del Companion.", mic: "Micro", enter: "Entrar a la oficina", no_mic: "Sin voz en esta TV: escribe.",
      keys: "OK hablar · ◀▶ elegir · escribe para preguntar · BACK salir", heard: "No te entendí. Repite o escribe.", more: "+%s más", ai: "IA", pc_off: "Claude y ChatGPT responden desde el PC: empareja el PC y pon el código (ajustes del Companion).", hired_pc: "%s está contratado en el PC y se unió a la oficina.", hiring: "El PC contrata a %s…", month: "este mes", spend: "Gasto" }
  };
  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }

  function open(el, api) {
    api = api || {};
    var doc = el.ownerDocument || document, win = doc.defaultView || root;
    var th0 = api.theme || {};
    var th = { bg: th0.bg || "#1a1b26", fg: th0.fg || "#a9b1d6", bright: th0.bright || "#c0caf5", accent: th0.accent || "#7aa2f7", accent2: th0.accent2 || "#bb9af7",
      green: th0.green || "#9ece6a", yellow: th0.yellow || "#e0af68", red: th0.red || "#f7768e", cyan: th0.cyan || "#7dcfff", magenta: th0.magenta || th0.accent2 || "#ff79c6" };
    ["bg", "fg", "bright", "accent", "accent2", "green", "yellow", "red", "cyan", "magenta"].forEach(function (k) { if (!/^#[0-9a-fA-F]{6}$/.test(th[k])) th[k] = { bg: "#1a1b26", fg: "#a9b1d6", bright: "#c0caf5", accent: "#7aa2f7", accent2: "#bb9af7", green: "#9ece6a", yellow: "#e0af68", red: "#f7768e", cyan: "#7dcfff", magenta: "#ff79c6" }[k]; });
    var L = T[String(api.lang || "en").slice(0, 2)] || T.en;
    function t(k, a) { var v = L[k] || T.en[k] || k; return a == null ? v : v.replace("%s", a); }
    var data = parse(api.data);
    var store = api.store || null, pending = loadPending();
    var S = { mode: api.skipStart ? "office" : "start", focus: 0, hire: null, busy: false, reply: "", listening: false, walkers: {}, t0: Date.now(), welcome: "", prov: 0 };
    var HIRE_PROVIDERS = ["grok", "claude", "chatgpt"], PNAME = { grok: "Grok Bot", claude: "Claude", chatgpt: "ChatGPT" };
    /** Who answers the Ask box: Grok Bot (the webhook) first, then the AIs on the PC (omarchy-link), hired bots too. */
    function provs() {
      var out = [{ id: "grok", name: "Grok Bot", provider: "grok" }], l = api.providers ? api.providers() || [] : [];
      for (var i = 0; i < l.length && out.length < 17; i++) if (l[i] && l[i].id) out.push({ id: "pc:" + l[i].id, name: clean(l[i].name || l[i].id, 24), provider: provider(l[i].provider || l[i].id) });
      return out;
    }
    function prov() { var l = provs(); if (S.prov >= l.length) S.prov = 0; return l[S.prov]; }
    var w = doc.createElement("div"), st = doc.createElement("style");
    w.className = "office";
    var A = th.accent, B = th.bg, F = th.fg, WH = th.bright;
    st.textContent = ".office{position:absolute;left:0;top:0;right:0;bottom:0;z-index:57;background:" + B + ";color:" + F + ";font-family:CaskaydiaMono,monospace;font-size:1.25vw;overflow:hidden}" +
      ".office canvas{position:absolute;left:0;top:0;width:100%;height:100%}" +
      ".office .hd{position:absolute;left:2.4vw;top:3vh;color:" + WH + ";font-size:1.6vw;font-weight:bold;letter-spacing:.04em;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:70vw}.office .hd b{color:" + A + "}.office .hd i{font-style:normal;color:" + F + ";font-weight:normal}" +
      ".office .us{position:absolute;left:2.4vw;bottom:14vh;width:22vw;min-width:12em;max-width:32vw;box-sizing:border-box;padding:1.4vh 1vw;border:.14vw solid " + A + "88;border-radius:.8vw;background:" + B + "e6;display:none}" +
      ".office .us .n{font-size:2.6vw;color:" + WH + ";font-weight:bold}.office .us .b{height:1vh;background:" + F + "33;border-radius:1vh;margin-top:.8vh;overflow:hidden}.office .us .b i{display:block;height:100%;background:" + A + "}" +
      ".office .us .s{color:" + F + ";font-size:.85em;margin-top:.6vh}" +
      ".office .us .sp div{margin-top:1vh;font-size:.9em;color:" + WH + "}.office .us .sp em{font-style:normal;font-weight:bold;margin-right:.5vw}.office .us .sp u{text-decoration:none;color:" + F + ";font-size:.85em}" +
      ".office .us .sp .b{height:.6vh;margin-top:.4vh}" +
      ".office .bt.pv{font-size:.9em}.office .bt.pv b{font-weight:normal}" +
      ".office .bar{position:absolute;left:50%;bottom:4vh;width:50vw;margin-left:-25vw;max-width:90vw;box-sizing:border-box;display:flex;align-items:center}" +
      ".office .in{flex:1;min-width:0;font:inherit;font-size:1.25vw;color:" + WH + ";background:" + B + "f0;border:.14vw solid " + F + "55;border-radius:3vw;padding:1.3vh 1.4vw;outline:none}" +
      ".office .bt{margin-left:.8vw;padding:1.3vh 1.2vw;border:.14vw solid " + F + "55;border-radius:3vw;background:" + B + "f0;color:" + WH + ";white-space:nowrap}" +
      ".office .f{border-color:" + A + ";box-shadow:0 0 1.2vw " + A + "66}.office .bt.f{background:" + A + ";color:" + B + "}" +
      ".office .rp{position:absolute;left:50%;bottom:28vh;width:50vw;margin-left:-25vw;max-width:90vw;box-sizing:border-box;padding:1.4vh 1.2vw;border-radius:.8vw;background:" + B + "ee;border:.14vw solid " + A + "66;color:" + WH + ";display:none;max-height:22vh;overflow:hidden}" +
      ".office .hint{position:absolute;left:25vw;right:25vw;top:40vh;box-sizing:border-box;padding:2vh 1.5vw;border-radius:.8vw;background:" + B + "ee;border:.14vw dashed " + A + ";color:" + WH + ";display:none;line-height:1.45;text-align:center;overflow-wrap:break-word;word-wrap:break-word}" +
      ".office .hc{position:absolute;right:4vw;top:22vh;width:28vw;min-width:18em;max-width:48vw;box-sizing:border-box;padding:2.4vh 1.6vw;border-radius:1vw;background:" + B + "f4;border:.16vw solid " + A + ";display:none}" +
      ".office .hc h2{margin:0 0 1.6vh;color:" + WH + ";font-size:1.5vw;word-wrap:break-word}.office .hc .k{color:" + F + ";font-size:.85em;margin-top:1.2vh}.office .hc .v{color:" + WH + ";font-size:1.15em}" +
      ".office .hc .row{display:flex;justify-content:flex-end;margin-top:2.4vh}" +
      ".office .ft{position:absolute;right:2.4vw;bottom:1.2vh;color:" + F + "88;font-size:.75em;max-width:50vw;text-align:right;white-space:normal}" +
      ".office .start{position:absolute;left:0;top:0;right:0;bottom:0;display:flex;flex-direction:column;align-items:center;justify-content:center;background:radial-gradient(circle at 50% 42%," + B + " 0%," + "#000 85%)}" +
      ".office .start .wm{color:" + WH + ";font-size:2.4vw;font-weight:bold;margin:2vh 0 4vh;letter-spacing:.06em;white-space:nowrap;max-width:50vw;overflow:hidden;line-height:1.2}.office .start .wm b{color:" + A + "}" +
      ".office .start .bar{position:static;margin:0;width:50vw;max-width:90vw}.office .start .ls{margin-top:3vh;color:" + th.cyan + ";height:3vh}";
    w.innerHTML = '<canvas></canvas><div class="hd"></div><div class="hint"></div><div class="us"><div class="uw"><div class="n"></div><div class="b"><i></i></div><div class="s"></div></div><div class="sp"></div></div>' +
      '<div class="rp"></div><div class="hc"><h2></h2><div class="k">' + esc(t("name")) + '</div><div class="v hn"></div><div class="k">' + esc(t("role")) + '</div><div class="v hr"></div>' +
      '<div class="k">' + esc(t("ai")) + '</div><div class="row" style="justify-content:flex-start;margin-top:.6vh"><span class="bt hp" style="margin-left:0"></span></div>' +
      '<div class="row"><span class="bt hx">' + esc(t("cancel")) + '</span><span class="bt hy">' + esc(t("hire")) + "</span></div></div>" +
      '<div class="bar ob"><input class="in" type="text" maxlength="240"><span class="bt pv"></span><span class="bt mic">🎤 ' + esc(t("mic")) + "</span></div>" +
      '<div class="ft">' + esc(t("keys")) + "</div>" +
      '<div class="start"><canvas class="mk" width="240" height="240" style="position:static;width:12vw;height:12vw"></canvas><div class="wm">GrokBot <b>Office</b></div>' +
      '<div class="bar sb"><input class="in" type="text" maxlength="240"><span class="bt pv"></span><span class="bt mic">🎤</span><span class="bt go">' + esc(t("enter")) + ' ›</span></div><div class="ls"></div></div>';
    w.insertBefore(st, w.firstChild);
    el.appendChild(w);
    function q(s) { return w.querySelector(s); }
    var cv = q("canvas"), ctx = cv.getContext && cv.getContext("2d");
    var sIn = q(".sb .in"), oIn = q(".ob .in");
    sIn.placeholder = t("ask") + "…"; oIn.placeholder = t("anything");
    var mk = q("canvas.mk"), mc = mk.getContext && mk.getContext("2d");
    if (mc) mark(mc, 120, 120, 120, A, WH);

    // the canvas: 1280x720 drawing units, up to 1920x1080 real pixels (sharp names on FHD TVs, cheap on 720p ones)
    var scale = Math.max(1, Math.min(1.5, (win.innerWidth || W) / W)), bgCv = null, deskCv = null, signCv = null;
    function off(wd, ht) { var c = doc.createElement("canvas"); c.width = Math.round(wd * scale); c.height = Math.round(ht * scale); var x = c.getContext("2d"); x.scale(scale, scale); return { c: c, x: x }; }
    function prerender() {
      if (!ctx) return;
      cv.width = Math.round(W * scale); cv.height = Math.round(H * scale);
      var b = off(W, H); room(b.x, th, false); bgCv = b.c;
      var s = off(W, H); neon(s.x, th, 1); signCv = s.c;
      var d = off(140, 90); d.x.translate(70, 40); desk(d.x, th); deskCv = d.c;
    }
    prerender();

    function loadPending() {
      try {
        var p = store && JSON.parse(store.getItem(PENDING_KEY) || "null");
        if (p && Date.now() - (p.at || 0) < PENDING_TTL && !confirmed(data, p)) return p;
      } catch (e) {}
      return null;
    }
    function savePending() { try { if (store) { if (pending) store.setItem(PENDING_KEY, JSON.stringify(pending)); else store.removeItem(PENDING_KEY); } } catch (e) {} }

    function frame(now) {
      var tt = (now - S.t0) / 1000, desks = layout(data.bots.length + (pending ? 1 : 0)), i, c = ctx;
      c.setTransform(scale, 0, 0, scale, 0, 0);
      c.drawImage(bgCv, 0, 0, W, H);
      var fl = 0.82 + 0.18 * Math.sin(tt * 3.1) * Math.sin(tt * 7.3);   // the neon hums
      if (Math.floor(tt * 10) % 53 === 0) fl = 0.35;
      c.globalAlpha = fl; c.drawImage(signCv, 0, 0, W, H); c.globalAlpha = 1;
      var list = data.bots.slice(0, SHOWN), seats = [];
      for (i = 0; i < desks.length; i++) seats.push({ d: desks[i], b: list[i] || null });
      if (pending) { var free = Math.min(list.length, desks.length - 1); seats[free].b = { name: pending.name || "…", color: pending.color || th.yellow, status: "idle", pending: true, id: "__pending", provider: pending.provider || "grok" }; }
      // back to front, so the nearer desks cover the ones behind
      seats.sort(function (a, b) { return (a.d.x + a.d.y) - (b.d.x + b.d.y); });
      var plates = [];
      for (i = 0; i < seats.length; i++) {
        var p = iso(seats[i].d.x, seats[i].d.y), bt = seats[i].b;
        if (bt) {
          var pos = p, walking = false;
          if (bt.pending || S.walkers[bt.id]) {   // walks in from the door on the left wall
            var wk = S.walkers[bt.id] || (S.walkers[bt.id] = { t0: tt });
            var k = Math.min(1, (tt - wk.t0) / 3.2), door = iso(0.2, 9.2);
            if (k < 1) { walking = true; var e = k * k * (3 - 2 * k); pos = { x: door.x + (p.x - door.x) * e, y: door.y + (p.y - TH * 0.42 - door.y) * e }; }
            else if (!bt.pending) delete S.walkers[bt.id];
          }
          var seatY = walking ? pos.y : p.y - TH * 0.42;
          if (walking) {
            c.drawImage(deskCv, p.x - 70, p.y - 40, 140, 90);
            var top0 = blob(c, pos.x, seatY, 54, bt.color, tt, { phase: hash(bt.id) % 7, status: bt.status, walking: true, pending: bt.pending });
            plates.push([pos.x, top0 - 8, bt.name, bt.color, bt.pending ? t("pending") : "", bt.provider]);
          } else {
            var top = blob(c, pos.x, seatY, 54, bt.color, tt, { phase: hash(bt.id) % 7, status: bt.status, walking: false, pending: bt.pending });
            c.drawImage(deskCv, p.x - 70, p.y - 40, 140, 90);
            plates.push([pos.x, top - 8, bt.name, bt.color, bt.pending ? t("pending") : "", bt.provider]);
          }
        } else {
          c.globalAlpha = 0.75; c.drawImage(deskCv, p.x - 70, p.y - 40, 140, 90); c.globalAlpha = 1;
        }
      }
      for (i = 0; i < plates.length; i++) plate(c, plates[i][0], plates[i][1], plates[i][2], plates[i][3], th, plates[i][4], plates[i][5]);
      if (data.bots.length > SHOWN) { c.fillStyle = th.fg; c.font = "16px CaskaydiaMono, monospace"; c.textAlign = "right"; c.fillText(t("more", data.bots.length - SHOWN), W - 40, 120); }
    }
    var raf = win.requestAnimationFrame ? function (f) { return win.requestAnimationFrame(f); } : function (f) { return setTimeout(function () { f(Date.now()); }, FRAME_MS); };
    var caf = win.cancelAnimationFrame ? function (h) { win.cancelAnimationFrame(h); } : clearTimeout;
    var handle = 0, last = 0, alive = true;
    function loop(ts) {
      handle = 0;
      if (!alive || doc.hidden || S.mode !== "office") return;   // hidden or on the start screen: no frames at all
      handle = raf(loop);
      var now = Date.now();
      if (!due(last, now)) return;
      last = now;
      frame(now);
    }
    function run() { if (alive && !handle && ctx && S.mode === "office" && !doc.hidden) handle = raf(loop); }
    function onVis() { if (doc.hidden) { if (handle) { caf(handle); handle = 0; } } else run(); }
    doc.addEventListener("visibilitychange", onVis);

    function render() {
      q(".start").style.display = S.mode === "start" ? "flex" : "none";
      q(".hd").innerHTML = "GrokBot <b>Office</b> <i>· " + esc(data.bots.length === 1 ? t("one_bot") : t("bots", data.bots.length)) + "</i>";
      var us = q(".us"), sp = data.spend || [];
      us.style.display = data.usage || sp.length ? "block" : "none";
      q(".us .uw").style.display = data.usage ? "block" : "none";
      if (data.usage) {
        q(".us .n").innerHTML = esc(data.usage.pct) + "%";
        q(".us .uw .b i").style.width = Math.min(100, data.usage.pct) + "%";
        q(".us .s").textContent = (data.usage.label || t("usage")) + (data.usage.resets ? " · " + t("resets", data.usage.resets) : "");
      }
      q(".us .sp").innerHTML = sp.map(function (r) {   // real spend per provider (Admin APIs on the PC); a bar only with a budget
        var c = providerColor(r.provider);
        return '<div><em style="color:' + c + '">' + esc(r.provider) + "</em>" + esc(r.unit) + esc(r.spent.toFixed(2)) + (r.budget ? " / " + esc(r.unit) + esc(r.budget) : "") +
          " <u>" + esc(t("month")) + "</u>" + (r.pct != null ? '<div class="b"><i style="display:block;height:100%;width:' + r.pct + "%;background:" + c + '"></i></div>' : "") + "</div>";
      }).join("");
      var pv = prov(), pvHtml = '<b style="color:' + providerColor(pv.provider) + '">◆</b> ' + esc(pv.name);
      q(".sb .pv").innerHTML = pvHtml; q(".ob .pv").innerHTML = pvHtml;
      var hint = q(".hint");
      hint.style.display = S.mode === "office" && !data.bots.length && !pending && !S.hire && !S.reply ? "block" : "none";
      hint.textContent = t("empty");
      var rp = q(".rp");
      rp.style.display = S.reply ? "block" : "none"; rp.textContent = S.reply;
      var hc = q(".hc");
      hc.style.display = S.hire ? "block" : "none";
      if (S.hire) {
        var hp = S.hire.provider || "grok";
        q(".hc h2").textContent = t("hire_title"); q(".hn").textContent = S.hire.name || "(" + t("picked") + ")"; q(".hr").textContent = S.hire.role || "—";
        q(".hp").innerHTML = '<b style="color:' + providerColor(hp) + '">◆</b> ' + esc(PNAME[hp] || hp) + " ▸";
      }
      q(".ls").textContent = S.listening ? "🎤 " + t("listening") : S.busy ? t("thinking") : S.reply && S.mode === "start" ? S.reply : "";
      var fs = focusables();
      [sIn, oIn, q(".sb .pv"), q(".ob .pv"), q(".sb .mic"), q(".sb .go"), q(".ob .mic"), q(".hp"), q(".hx"), q(".hy")].forEach(function (x) { x.className = x.className.replace(/\s*\bf\b/g, ""); });
      if (fs[S.focus]) fs[S.focus].className += " f";
      oIn.placeholder = S.listening ? t("listening") : S.busy ? t("thinking") : t("anything");
      sIn.placeholder = pv.id === "grok" ? t("ask") + "…" : t("ask").replace(/GrokBot|Grok Bot/, pv.name) + "…";
    }
    function focusables() {
      if (S.hire) return [q(".hp"), q(".hx"), q(".hy")];
      return S.mode === "start" ? [sIn, q(".sb .pv"), q(".sb .mic"), q(".sb .go")] : [oIn, q(".ob .pv"), q(".ob .mic")];
    }
    function input() { return S.mode === "start" ? sIn : oIn; }
    function say(text) { S.reply = text; render(); if (api.speak && text) api.speak(text, ""); }
    function enter(spoken) {
      if (spoken) { if (api.speak) api.speak(t("opening"), ""); }
      S.mode = "office"; S.focus = 0; render(); run();
    }
    function submit(text) {
      var s = clean(text, 240);
      if (!s) return;
      input().value = "";
      if (input().blur) input().blur();   // the remote's arrows and OK drive the buttons again
      var it = intent(s);
      if (it.type === "hire") {
        if (S.mode === "start") enter(true);
        var pp = prov().provider;   // "hire a claude bot…" names it; else the AI picked in the Ask box
        S.hire = { name: it.name, role: it.role, description: s, provider: it.provider !== "grok" ? it.provider : HIRE_PROVIDERS.indexOf(pp) >= 0 ? pp : "grok" };
        S.reply = ""; S.focus = 2; render(); return;
      }
      if (it.type === "open") { S.reply = ""; enter(true); return; }
      if (S.mode === "start") enter(false);
      askBot(askRequest(s, data));
    }
    function askBot(rq, then, keep) {
      if (S.busy) return;
      var pv = prov();
      if (pv.id !== "grok" && rq.office && rq.office.action === "ask") {   // an AI on the PC answers (the key stays there)
        if (!api.pcAsk) { say(t("pc_off")); return; }
        S.busy = true; if (!keep) S.reply = t("thinking"); render();
        api.pcAsk(pv.id.slice(3), rq.question, function (err, text) {
          S.busy = false;
          if (!alive) return;
          say(err ? (err === "not_set" ? t("pc_off") : err) : text || "");
        });
        return;
      }
      if (!api.ask) { say(t("not_set")); if (then) then(false); return; }
      S.busy = true; if (!keep) S.reply = t("thinking"); render();
      api.ask(rq.question, rq.office, function (err, rep, office) {
        S.busy = false;
        if (!alive) return;
        if (err) { say(err === "not_set" ? t("not_set") : err); if (then) then(false); return; }
        if (office) setData(applyReply(data, office), true);
        say(rep && rep.text ? rep.text : "");
        if (then) then(true);
      });
    }
    function hireNow() {
      var h = S.hire; S.hire = null;
      if (h.provider && h.provider !== "grok") { hirePc(h); return; }
      pending = { name: h.name, role: h.role, at: Date.now(), before: data.bots.length, color: h.name ? colorOf("", h.name) : th.yellow, provider: "grok" };
      savePending(); S.walkers.__pending = null; delete S.walkers.__pending;
      S.focus = 0;
      say(t("sent", h.name || t("someone")));
      askBot(hireRequest(h, data), function (ok) { if (!ok && pending && !confirmed(data, pending)) { pending = null; savePending(); render(); } }, true);   // not sent: no ghost at the door
    }
    /** Claude / ChatGPT: the PC checks the key with the provider and saves the bot; only its yes brings it in. */
    function hirePc(h) {
      S.focus = 0;
      if (!api.pcHire) { say(t("pc_off")); return; }
      if (S.busy) return;
      pending = { name: h.name, role: h.role, at: Date.now(), before: data.bots.length, color: h.name ? colorOf("", h.name) : th.yellow, provider: h.provider, pc: true };
      S.walkers.__pending = null; delete S.walkers.__pending;
      S.busy = true; S.reply = t("hiring", h.name || t("someone")); render();
      api.pcHire({ name: h.name, provider: h.provider, role: h.role, description: h.description }, function (err, res) {
        S.busy = false;
        if (!alive) return;
        if (err || !res || !res.bot) { pending = null; render(); say(err === "not_set" ? t("pc_off") : err || "?"); return; }   // no yes, no bot
        var name = clean(res.bot.name, 24) || h.name;
        pending.name = name;
        setData(res.office ? res.office : applyReply(data, { hired: res.bot }), true);
        if (pending) { pending = null; }
        say(t("hired_pc", name));
      });
    }
    function setData(d, fromReply) {
      data = parse(d);
      if (pending && confirmed(data, pending)) {
        var nm = pending.name;
        var b = data.bots.filter(function (x) { return nm ? x.name.toLowerCase() === nm.toLowerCase() : false; })[0] || data.bots[data.bots.length - 1];
        var wk = S.walkers.__pending; pending = null; savePending();
        if (b) S.walkers[b.id] = wk || { t0: (Date.now() - S.t0) / 1000 };   // keeps walking from where it was
        if (b && !fromReply) say(t("welcome", b.name)); else if (b) S.welcome = t("welcome", b.name);
      }
      render();
    }
    function listen() {
      if (!api.listen) { say(t("no_mic")); input().focus && input().focus(); return; }
      if (S.listening) return;
      S.listening = true; render();
      api.listen(function (err, text) {
        S.listening = false;
        if (!alive) return;
        if (err || !text) { say(err === "unsupported" ? t("no_mic") : t("heard")); return; }
        submit(text);
      });
    }
    function activate() {
      var f = focusables()[S.focus];
      if (!f) return;
      if (f === sIn || f === oIn) { if (f.value.trim()) submit(f.value); else if (f.focus) f.focus(); }
      else if (/\bmic\b/.test(f.className)) listen();
      else if (/\bgo\b/.test(f.className)) enter(true);
      else if (/\bhy\b/.test(f.className)) hireNow();
      else if (/\bhp\b/.test(f.className)) { S.hire.provider = HIRE_PROVIDERS[(HIRE_PROVIDERS.indexOf(S.hire.provider || "grok") + 1) % HIRE_PROVIDERS.length]; render(); }
      else if (/\bpv\b/.test(f.className)) { S.prov = (S.prov + 1) % provs().length; if (api.onProvider) api.onProvider(prov().id); render(); }
      else if (/\bhx\b/.test(f.className)) { S.hire = null; S.focus = 0; render(); }
    }
    [sIn, oIn].forEach(function (x) { x.addEventListener("keydown", function (e) { if (e.keyCode === 13) { e.preventDefault(); submit(x.value); } }); });
    render(); run();

    return {
      key: function (n, e) {
        var tgt = e && e.target, typing = tgt && /INPUT/.test(tgt.tagName);
        if (n === "back") {
          if (typing && tgt.blur) { tgt.blur(); return true; }
          if (S.hire) { S.hire = null; S.focus = 0; render(); return true; }
          if (S.reply && !S.busy) { S.reply = ""; render(); return true; }
          if (api.onExit) api.onExit();
          return true;
        }
        if (typing && n === "ok") { submit(tgt.value); return true; }   // Enter sends (the host's capture listener stops the event before the field)
        if (typing && n !== "up" && n !== "down") return false;   // the field gets its other keys
        var fs = focusables();
        if (n === "left") { S.focus = Math.max(0, S.focus - 1); render(); return true; }
        if (n === "right") { S.focus = Math.min(fs.length - 1, S.focus + 1); render(); return true; }
        if (n === "up" || n === "down") { if (typing && tgt.blur) tgt.blur(); return true; }
        if (n === "ok") { activate(); return true; }
        if (e && !typing && e.key && e.key.length === 1 && !e.ctrlKey && !e.metaKey && !S.hire) {   // typing starts the question
          var inp = input(); S.focus = 0; render(); if (inp.focus) inp.focus();
          return false;
        }
        return false;
      },
      setData: function (d) { setData(d, false); },
      state: function () { return { mode: S.mode, hire: S.hire, pending: pending, bots: data.bots.length, usage: !!data.usage, spend: (data.spend || []).length, running: !!handle, provider: prov().id, focus: S.focus }; },
      setProvider: function (id) { var l = provs(); for (var i = 0; i < l.length; i++) if (l[i].id === id) S.prov = i; render(); },
      submit: submit,
      enter: function () { enter(false); },
      hire: function () { if (S.hire) hireNow(); },
      destroy: function () {
        alive = false;
        if (handle) caf(handle); handle = 0;
        doc.removeEventListener("visibilitychange", onVis);
        if (w.parentNode) w.parentNode.removeChild(w);
      }
    };
  }

  /** Pixel widths for the Office chrome, the same policy as Office.Chrome on Android: half the panel
   * (at least 560 CSS px at density 1, at most 90%) so the title stays on one line and the ask field is not clipped. */
  function chrome(screenW, density) {
    density = density > 0 ? density : 1;
    screenW = screenW > 0 ? screenW : 1920;
    function dp(v) { return Math.round(v * density); }
    function clamp(v, max) { return Math.max(1, Math.min(max, v)); }
    var col = clamp(Math.max(dp(560), Math.round(0.50 * screenW)), Math.round(0.90 * screenW));
    var hireCap = Math.min(Math.round(0.48 * screenW), Math.max(1, screenW - dp(80)));
    var usageW = clamp(Math.max(dp(240), Math.round(0.20 * screenW)), Math.round(0.32 * screenW));
    var barBottom = dp(36), barHeight = dp(56), gap = dp(12);
    var usageBottom = barBottom + barHeight + gap;   // same stack as Office.Chrome
    var usageRight = dp(36) + usageW;
    var colLeft = Math.round((screenW - col) / 2);
    var replyBottom = usageRight + gap > colLeft ? usageBottom + dp(100) + gap : usageBottom;
    return {
      column: col, hint: col, reply: col,
      hire: clamp(Math.max(dp(360), Math.round(0.28 * screenW)), hireCap),
      usage: usageW,
      keys: clamp(Math.round(0.46 * screenW), Math.round(0.60 * screenW)),
      header: Math.max(1, Math.round(0.70 * screenW)),
      barBottom: dp(36), barHeight: dp(56), gap: dp(12),
      usageBottom: usageBottom, replyBottom: replyBottom
    };
  }

  var api = { parse: parse, applyReply: applyReply, toJson: toJson, colorOf: colorOf, intent: intent, hireRequest: hireRequest, askRequest: askRequest,
    requestBody: requestBody, findOffice: findOffice, spend: spend, confirmed: confirmed, ask: ask, layout: layout, chrome: chrome, due: due, FRAME_MS: FRAME_MS, MAX_BOTS: MAX_BOTS, provider: provider, providerColor: providerColor,
    PENDING_KEY: PENDING_KEY, T: T, open: open };
  root.OmarchyOffice = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof window !== "undefined" ? window : this);
