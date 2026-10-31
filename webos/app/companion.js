// Grok Bot companion for the shared webOS/Tizen app: the pure parts (avatar SVG, links, local app
// launching, per-bot history). The avatar draws Tom Ballard's MIT shapes, palette and eye geometry
// (vendor/grokbot/grokbot-avatar.js, from his Grok Bot Desktop Companion v0.1.0); the rest is mine.
// ES5 for webOS 3 (Chrome 38). Works in the browser (window.OmarchyCompanion) and in node (require).
(function (root, factory) {
  var lib = factory(root);
  if (typeof module === "object" && module.exports) module.exports = lib;
  else root.OmarchyCompanion = lib;
})(typeof window !== "undefined" ? window : this, function (root) {
  "use strict";

  var SHAPES = ["blob", "cloud", "squircle", "sparkle", "clover", "heart", "flower", "teardrop", "tablet",
    "wedge", "gem", "burst", "hex", "pebble"];
  var COLORS = ["black", "brown", "red", "orange", "yellow", "green", "cyan", "blue", "violet", "magenta", "gray"];
  var SIZES = { small: 0.75, medium: 1, large: 1.35 };

  function libs() {
    return { S: root.GrokbotShapes, P: root.GrokbotPalette, F: root.GrokbotFace };
  }

  // Fill and eye colours for a palette name or #hex (the PC roster sends both).
  function colors(color) {
    var L = libs(), c = String(color || "orange").toLowerCase();
    if (/^#[0-9a-f]{6}$/.test(c)) {
      var n = parseInt(c.slice(1), 16), luma = (0.2126 * (n >> 16) + 0.7152 * ((n >> 8) & 255) + 0.0722 * (n & 255)) / 255;
      return { fill: c, eye: luma < 0.16 ? "#F4F4F5" : "#141416" };
    }
    if (L.P && L.P.get) {
      var tone = L.P.get(c);   // the TV is a dark surface: black draws as Grok Bot's dark-mode white
      return { fill: L.P.fillFor(c, true), eye: tone.darkFill ? tone.fill : (L.P.CUTOUT || "#141416") };
    }
    return { fill: "#FF6700", eye: "#141416" };
  }

  // One frame of the avatar. o: {shape, color, px, open (0..1), gx, gy (-1..1 gaze), pose, squash}.
  function avatarSvg(o) {
    o = o || {};
    var L = libs(), px = o.px || 64, rad = px * 0.44, cx = px / 2, cy = px / 2, col = colors(o.color);
    var head = '<svg class="gb-avatar" viewBox="0 0 ' + px + " " + px + '" width="' + px + '" height="' + px + '" aria-hidden="true">';
    if (!L.S || !L.F) {   // vendor file missing: the old round mark
      return head + '<circle cx="' + cx + '" cy="' + cy + '" r="' + rad + '" fill="' + col.fill + '"/></svg>';
    }
    var name = L.S.resolve(o.shape), sh = L.S.get(name), pose = L.F.pose(o.pose), open = Math.min(pose.open, o.open == null ? 1 : o.open);
    var pts = sh.points, eyes = sh.eyes, params = L.S.look ? L.S.look(name) : null;
    if (params && (o.gx || o.gy)) {
      var t = L.F.look(pts, params, o.gx || 0, o.gy || 0);
      pts = L.F.rotatePoints(pts, t.turn, t.centre);
      eyes = t.eyes;
    }
    var sq = o.squash || 1, tr = sq !== 1 ? ' transform="translate(' + cx + " " + (cy + rad) + ") scale(" + (2 - sq).toFixed(3) + " " + sq.toFixed(3) + ") translate(" + (-cx) + " " + (-cy - rad) + ')"' : "";
    var out = [head, "<g" + tr + ">", '<path d="' + L.S.svgPath(pts, rad, cx, cy) + '" fill="' + col.fill + '"/>'];
    if (name === L.S.STEM_SHAPE && L.S.stemPoints && L.S.stemPoints()) {
      out.push('<path d="' + L.S.svgPath(L.S.stemPoints(), rad, cx, cy) + '" fill="' + L.S.STEM_COLOR + '"/>');
    }
    for (var i = 0; i < eyes.length; i++) {
      var e = eyes[i], g = L.F.eye(e, rad, open, pose.height), ex = cx + e.x * rad, ey = cy + e.y * rad;
      out.push('<rect x="' + (ex - g.w / 2).toFixed(2) + '" y="' + (ey - g.h / 2).toFixed(2) + '" width="' + g.w.toFixed(2) +
        '" height="' + g.h.toFixed(2) + '" rx="' + g.r.toFixed(2) + '" fill="' + col.eye + '" transform="rotate(' +
        L.F.rotation(e, pose.tilt).toFixed(1) + " " + ex.toFixed(2) + " " + ey.toFixed(2) + ')"/>');
    }
    out.push("</g></svg>");
    return out.join("");
  }

  // Slow burn-in drift for an always-on overlay: a few pixels on a 10 minute loop.
  function drift(ms) {
    var t = (ms || 0) / 600000 * Math.PI * 2;
    return { x: Math.round(Math.sin(t) * 14), y: Math.round(Math.sin(t * 2 + 1) * 8) };
  }

  // ---- links in a reply (the http(s) ones only) ----
  function youtubeId(url) {
    var m = /^https?:\/\/(?:www\.|m\.|music\.)?(?:youtube\.com\/(?:watch\?(?:[^#]*&)?v=|shorts\/|live\/|embed\/)|youtu\.be\/)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])/.exec(url);
    return m ? m[1] : "";
  }
  function links(text) {
    var out = [], seen = {}, re = /https?:\/\/[^\s<>"')\]]+/g, m;
    while ((m = re.exec(String(text || ""))) && out.length < 6) {
      var url = m[0].replace(/[.,;:!?]+$/, "");
      if (seen[url] || url.length > 2000) continue;
      seen[url] = true;
      var id = youtubeId(url);
      out.push({ url: url, kind: id ? "youtube" : /\.(mp4|m4v|webm|mkv|mov|m3u8)(\?|$)/i.test(url) ? "video" : "url", videoId: id });
    }
    return out;
  }
  // Links the PC bridge already classified ({url, kind, videoId}), checked again here.
  function cleanLinks(list) {
    var out = [];
    (list || []).forEach(function (l) {
      var url = l && String(l.url || "");
      if (!/^https?:\/\//i.test(url)) return;
      var id = youtubeId(url);
      out.push({ url: url, label: String(l.label || "").slice(0, 80), kind: id ? "youtube" : l.kind === "video" ? "video" : "url", videoId: id });
    });
    return out.slice(0, 6);
  }

  // ---- "open Netflix" / "apri Netflix" / "starte Netflix", matched against the installed apps ----
  var LAUNCH_RE = /^\s*(?:hey\s+|ok\s+|ehi\s+)?(?:(?:please|per favore|bitte)\s+)?(?:can you\s+|puoi\s+)?(open|launch|start|run|apri|avvia|lancia|fai partire|metti|öffne|offne|starte)\s+(?:(?:the|l'|la|il|lo|app|application|applicazione|die)\s+)*(.+?)\s*(?:app|application|applicazione)?\s*(?:please|per favore|bitte)?[.!?]*\s*$/i;
  function launchQuery(text) {
    var m = LAUNCH_RE.exec(String(text || "").replace(/\b[lL]['’]\s*/g, ""));
    if (!m) return "";
    var q = m[2].replace(/^(?:l'|app\s+)/i, "").trim();
    return q.length >= 2 && q.length <= 40 && q.split(/\s+/).length <= 4 ? q : "";
  }
  function norm(s) {
    return String(s || "").toLowerCase().replace(/\+/g, "plus").replace(/[^a-z0-9]/g, "");
  }
  function lev(a, b) {
    var prev = [], cur, i, j;
    for (j = 0; j <= b.length; j++) prev.push(j);
    for (i = 1; i <= a.length; i++) {
      cur = [i];
      for (j = 1; j <= b.length; j++) cur.push(Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1)));
      prev = cur;
    }
    return prev[b.length];
  }
  // Best app for a spoken name: exact, then prefix, then contained, then a small typo distance.
  function matchApp(query, apps) {
    var q = norm(query), best = null, bestScore = 1e9;
    if (q.length < 2) return null;
    (apps || []).forEach(function (a) {
      [a.name, a.sub, a.id].forEach(function (cand) {
        var n = norm(cand);
        if (!n) return;
        var s;
        if (n === q) s = 0;
        else if (n.indexOf(q) === 0 || (q.indexOf(n) === 0 && n.length >= 3)) s = 1 + Math.abs(n.length - q.length) / 100;
        else if (n.indexOf(q) > 0 && q.length >= 3) s = 2;
        else {
          var d = lev(q, n);
          s = d <= Math.max(1, Math.floor(Math.min(q.length, n.length) / 4)) ? 3 + d : 1e9;
        }
        if (s < bestScore) { bestScore = s; best = a; }
      });
    });
    return best;
  }

  // ---- per-bot chat history and unread dots, in localStorage ----
  var KEY = "omarchyGrokbotChat";
  function loadAll(store) {
    try { var d = JSON.parse(store.getItem(KEY) || "{}"); return d && typeof d === "object" ? d : {}; } catch (e) { return {}; }
  }
  function saveAll(store, d) { try { store.setItem(KEY, JSON.stringify(d)); } catch (e) {} }
  function history(store, bot) { var d = loadAll(store)[bot || "default"]; return d && d.msgs ? d.msgs : []; }
  function push(store, bot, role, text, extra) {
    var all = loadAll(store), k = bot || "default", d = all[k] || { msgs: [], unread: 0 };
    var m = { role: role, text: String(text || "").slice(0, 4000), at: Date.now() };
    if (extra && extra.links && extra.links.length) m.links = extra.links;
    if (extra && extra.mode) m.mode = extra.mode;
    d.msgs.push(m);
    if (d.msgs.length > 30) d.msgs = d.msgs.slice(-30);
    if (role === "bot" && extra && extra.unread) d.unread = (d.unread || 0) + 1;
    all[k] = d;
    saveAll(store, all);
    return m;
  }
  function unread(store, bot) {
    var all = loadAll(store);
    if (bot) return (all[bot] && all[bot].unread) || 0;
    var n = 0;
    for (var k in all) if (Object.prototype.hasOwnProperty.call(all, k)) n += all[k].unread || 0;
    return n;
  }
  function markRead(store, bot) {
    var all = loadAll(store);
    if (all[bot || "default"]) { all[bot || "default"].unread = 0; saveAll(store, all); }
  }
  function clear(store, bot) {
    var all = loadAll(store);
    if (bot) delete all[bot]; else all = {};
    saveAll(store, all);
  }

  return {
    SHAPES: SHAPES, COLORS: COLORS, SIZES: SIZES, colors: colors, avatarSvg: avatarSvg, drift: drift,
    youtubeId: youtubeId, links: links, cleanLinks: cleanLinks, launchQuery: launchQuery, matchApp: matchApp,
    history: history, push: push, unread: unread, markRead: markRead, clear: clear
  };
});
