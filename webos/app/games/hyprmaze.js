/* HYPRMAZE (OmarchyTV Arcade): the maze is a Hyprland desktop. The windows tile themselves (dwindle: every new
 * window splits the biggest one) and the gaps between them are the corridors; every time a window opens, closes or
 * flips its split (SUPER+J) the whole maze slides into a new shape under you. You are the cursor: glide along the
 * gaps (arrows; a turn waits for the next opening, so a slow remote is fine) to the window marked SUPER+Q and close
 * it with OK. Windows keep opening faster; twelve and the workspace is full. Close the wrong one and you pay
 * with a new window. ES5. */
(function (root) {
  "use strict";
  var A = root.OmarchyArcade;
  if (!A) return;
  var C = A.C, W = 80, AY0 = 2, AY1 = 21, IX = 2, IY = 3, IW = 76, IH = 18, MAXW = 12;
  var APPS = [["kitty", "$ _"], ["nvim", "~"], ["btop", "▁▃▅▂▇"], ["chromium", "◐ ─────"], ["lazygit", "● ─┐"], ["spotify", "♪ ▶ ━━━"],
    ["obsidian", "# ·"], ["yazi", "▸ ▸"], ["typora", "¶"], ["signal", "▭ ▭"], ["impala", "≋"], ["omarchy-menu", "▤"]];
  var DIRS = { up: [0, -1], down: [0, 1], left: [-1, 0], right: [1, 0] };

  function create(api) {
    var t = api.t, sfx = api.sfx, R = Math.random;
    var s = { over: false, score: 0, clock: 0, root: null, wins: [], target: null, cx: 1, cy: 11, dir: null, want: null,
      acc: 0, spawnT: 4, flipT: 11, closed: 0, combo: 0, lastClose: -9, pops: [], full: 0, wid: 0 };

    // ---- the dwindle tree ----
    function leaf(win) { return { win: win, rect: null, parent: null }; }
    function wide(r) { return r.w * 0.6 > r.h * 1.2 * 1.15; }   // on screen pixels, like Hyprland's dwindle
    function splitOf(n) { var v = wide(n.rect); return n.flip ? !v : v; }
    function parts(r, vertical) {
      if (vertical) { var wa = Math.floor((r.w - 2) / 2); return [{ x: r.x, y: r.y, w: wa, h: r.h }, { x: r.x + wa + 2, y: r.y, w: r.w - wa - 2, h: r.h }]; }
      var ha = Math.floor((r.h - 1) / 2);
      return [{ x: r.x, y: r.y, w: r.w, h: ha }, { x: r.x, y: r.y + ha + 1, w: r.w, h: r.h - ha - 1 }];
    }
    function fits(r) { return r.w >= 8 && r.h >= 3; }
    function layout(n, r) {
      n.rect = r;
      if (n.win) { n.win.to = r; return; }
      var p = parts(r, n.vertical);
      layout(n.kids[0], p[0]); layout(n.kids[1], p[1]);
    }
    function leaves(n, out) { out = out || []; if (!n) return out; if (n.win) out.push(n); else { leaves(n.kids[0], out); leaves(n.kids[1], out); } return out; }
    function relayout() { if (s.root) layout(s.root, { x: IX, y: IY, w: IW, h: IH }); s.wins = leaves(s.root).map(function (l) { return l.win; }); }
    function openWindow() {
      var app = APPS[s.wid % APPS.length], win = { id: ++s.wid, app: app[0], fill: app[1], cur: null, to: null };
      if (!s.root) { s.root = leaf(win); relayout(); win.cur = copy(win.to); return true; }
      var ls = leaves(s.root).filter(function (l) { var p = parts(l.rect, splitOf(l)); return fits(p[0]) && fits(p[1]); });
      if (!ls.length || s.wins.length >= MAXW) return false;
      // dwindle splits the focused window, here the newest one that has room, so the layout spirals inwards
      ls.sort(function (a, b) { return b.win.id - a.win.id; });
      var old = ls[0], vertical = splitOf(old), a = leaf(old.win), b = leaf(win);
      old.vertical = vertical; old.kids = [a, b]; old.win = null; a.parent = b.parent = old;
      relayout();
      win.cur = { x: old.rect.x + old.rect.w - 1, y: old.rect.y, w: 1, h: old.rect.h };   // it slides in from the edge
      sfx.play("tile");
      return true;
    }
    function closeWindow(win) {
      var ls = leaves(s.root), l = null;
      for (var i = 0; i < ls.length; i++) if (ls[i].win === win) l = ls[i];
      if (!l) return;
      if (!l.parent) s.root = null;
      else {
        var p = l.parent, sib = p.kids[0] === l ? p.kids[1] : p.kids[0];
        p.win = sib.win; p.kids = sib.kids; p.vertical = sib.vertical; p.flip = sib.flip;
        if (p.kids) { p.kids[0].parent = p; p.kids[1].parent = p; }
      }
      relayout();
    }
    function flipOne() {   // SUPER+J on a random split: the maze turns
      var nodes = [];
      (function walk(n) { if (n && !n.win) { nodes.push(n); walk(n.kids[0]); walk(n.kids[1]); } })(s.root);
      for (var tries = 0; tries < nodes.length; tries++) {
        var n = nodes[Math.floor(R() * nodes.length)], p = parts(n.rect, !n.vertical);
        if (!fits(p[0]) || !fits(p[1])) continue;
        n.vertical = !n.vertical;
        relayout();
        var ok = s.wins.every(function (w) { return fits(w.to); });
        if (ok) { sfx.play("switch", 3); pop("SUPER+J", C.ACC2); return; }
        n.vertical = !n.vertical; relayout();
      }
    }
    function copy(r) { return { x: r.x, y: r.y, w: r.w, h: r.h }; }
    function pickTarget() {   // never the window you are standing next to: you always have to travel
      var others = s.wins.filter(function (w) { return w !== s.target && !(w.to && nearRect(w.to)); });
      if (!others.length) others = s.wins.filter(function (w) { return w !== s.target; });
      s.target = others.length ? others[Math.floor(R() * others.length)] : s.wins[0] || null;
    }
    function pop(text, c) { s.pops.push({ text: text, c: c, t: 1.2 }); if (s.pops.length > 2) s.pops.shift(); }

    // ---- the maze: every cell of the desktop not covered by a window ----
    function rnd(r) { return { x: Math.round(r.x), y: Math.round(r.y), w: Math.max(1, Math.round(r.w)), h: Math.max(1, Math.round(r.h)) }; }
    function inside(r, x, y) { return x >= r.x && x < r.x + r.w && y >= r.y && y < r.y + r.h; }
    function free(x, y) {
      if (x < 0 || x >= W || y < AY0 || y > AY1) return false;
      for (var i = 0; i < s.wins.length; i++) if (s.wins[i].cur && inside(rnd(s.wins[i].cur), x, y)) return false;
      return true;
    }
    function rescue() {   // a window slid over the cursor: out to the nearest gap
      if (free(s.cx, s.cy)) return;
      var seen = {}, q = [[s.cx, s.cy]];
      seen[s.cx + "," + s.cy] = 1;
      for (var h = 0; h < q.length && h < 4000; h++) {
        var c = q[h];
        if (free(c[0], c[1])) { s.cx = c[0]; s.cy = c[1]; s.dir = null; return; }
        for (var d in DIRS) { var nx = c[0] + DIRS[d][0], ny = c[1] + DIRS[d][1], k = nx + "," + ny; if (!seen[k] && nx >= 0 && nx < W && ny >= AY0 && ny <= AY1) { seen[k] = 1; q.push([nx, ny]); } }
      }
    }
    function nearRect(r) {
      r = rnd(r);
      return !inside(r, s.cx, s.cy) && s.cx >= r.x - 2 && s.cx <= r.x + r.w + 1 && s.cy >= r.y - 1 && s.cy <= r.y + r.h;
    }
    function near(win) {
      var r = rnd(win.cur);
      return !inside(r, s.cx, s.cy) && s.cx >= r.x - 2 && s.cx <= r.x + r.w + 1 && s.cy >= r.y - 1 && s.cy <= r.y + r.h;
    }

    openWindow(); openWindow(); openWindow();
    pickTarget();

    return {
      get over() { return s.over; }, get score() { return s.score; }, view: null,
      _state: function () { return s; },
      update: function (dt) {
        s.clock += dt;
        for (var p = s.pops.length - 1; p >= 0; p--) { s.pops[p].t -= dt; if (s.pops[p].t <= 0) s.pops.splice(p, 1); }
        if (s.full > 0) { s.full -= dt; if (s.full <= 0) s.over = true; return; }
        // windows slide to their new place (Hyprland's animations)
        var k = Math.min(1, dt * 9);
        for (var i = 0; i < s.wins.length; i++) {
          var w = s.wins[i]; if (!w.cur) w.cur = copy(w.to);
          ["x", "y", "w", "h"].forEach(function (f) { w.cur[f] += (w.to[f] - w.cur[f]) * k; if (Math.abs(w.to[f] - w.cur[f]) < 0.05) w.cur[f] = w.to[f]; });
        }
        rescue();
        // the cursor glides along the gaps; a queued turn is taken at the next opening
        var sp = s.dir === "up" || s.dir === "down" ? 12 : 26;
        s.acc += dt * sp;
        while (s.acc >= 1) {
          s.acc -= 1;
          if (s.want && s.want !== s.dir) { var wd = DIRS[s.want]; if (free(s.cx + wd[0], s.cy + wd[1])) s.dir = s.want; }
          if (!s.dir) { s.acc = 0; break; }
          var d = DIRS[s.dir];
          if (free(s.cx + d[0], s.cy + d[1])) { s.cx += d[0]; s.cy += d[1]; }
          else { s.dir = null; s.acc = 0; break; }
        }
        // more and more windows open
        s.spawnT -= dt;
        if (s.spawnT <= 0) {
          s.spawnT = Math.max(2.2, 6 - s.closed * 0.12) * (0.8 + R() * 0.4);
          if (!openWindow()) { s.full = 1.8; sfx.play("gameover"); api.shake(0.4); pop(t("hypr_full"), C.RED); return; }
          if (!s.target) pickTarget();
        }
        s.flipT -= dt;
        if (s.flipT <= 0) { s.flipT = 8 + R() * 6; flipOne(); }
      },
      key: function (k) {
        if (DIRS[k]) { s.want = k; if (!s.dir) { var d = DIRS[k]; if (free(s.cx + d[0], s.cy + d[1])) s.dir = k; } }
        else if (k === "ok" && s.full <= 0) {
          var hit = null;
          for (var i = 0; i < s.wins.length; i++) if (near(s.wins[i]) && (!hit || s.wins[i] === s.target)) hit = s.wins[i];
          if (!hit) { sfx.play("blip"); return; }
          if (hit === s.target) {
            s.combo = s.clock - s.lastClose < 4 ? Math.min(5, s.combo + 1) : 1;
            s.lastClose = s.clock; s.closed++;
            var pts = 100 * s.combo;
            s.score += pts; sfx.play("hit"); pop("SUPER+Q  " + hit.app + "  +" + pts + (s.combo > 1 ? "  x" + s.combo : ""), C.GREEN);
            closeWindow(hit); sfx.play("tile");
            while (s.wins.length < 3 && openWindow()) {}   // a desktop always has a few windows open
            pickTarget();
          } else {   // the wrong window: it's your editor! and something else opens
            sfx.play("hurt"); api.shake(0.2); s.combo = 0;
            pop(t("hypr_wrong") + " " + hit.app, C.RED);
            if (!openWindow()) { s.full = 1.8; pop(t("hypr_full"), C.RED); }
          }
        }
      },
      draw: function (g) {
        var pad = api.pad, blink = Math.floor(s.clock * 4) % 2, n = s.wins.length;
        g.row(1, 0, [["HYPRMAZE", C.ACC], [t("score") + " " + pad(s.score, 6), C.BRIGHT],
          ["▣ " + n + "/" + MAXW, n >= MAXW - 2 ? C.RED : n >= MAXW - 5 ? C.YEL : C.GREEN], [s.combo > 1 ? "x" + s.combo : "", C.YEL]]);
        // a waybar
        g.put(0, 1, new Array(W + 1).join(" "), C.DIM);
        g.put(1, 1, " 1 ", C.INV); g.put(4, 1, " 2  3  4  5", C.DIM);
        var d = new Date(), hm = pad(d.getHours(), 2) + ":" + pad(d.getMinutes(), 2);
        g.put(W - 1 - hm.length, 1, hm, C.FG);
        g.center(1, "omarchy", C.DIM);
        for (var i = 0; i < n; i++) {
          var w = s.wins[i], r = rnd(w.cur), tg = w === s.target, nr = near(w);
          if (r.w < 2 || r.h < 2) continue;
          var col = tg ? (blink ? C.RED : C.YEL) : nr ? C.ACC : C.DIM;
          g.fill(r.x, r.y, r.w, r.h, " ", C.FG);
          g.box(r.x, r.y, r.w, r.h, col, tg ? "heavy" : "round");
          var title = " " + w.app + " ";
          if (title.length < r.w - 2) g.put(r.x + 2, r.y, title, tg ? col : nr ? C.ACC : C.FG);
          if (tg && r.w >= 11 && r.h >= 4) g.put(r.x + Math.floor((r.w - 9) / 2), r.y + Math.floor(r.h / 2), " SUPER+Q ", blink ? C.INV : C.RED);
          for (var y = r.y + 1; y < r.y + r.h - 1 && y < r.y + 4; y++) {
            var line = w.fill; while (line.length < r.w - 4) line += " " + w.fill;
            g.put(r.x + 2, y, line.substr(0, Math.max(0, r.w - 4)), C.DIM);
          }
        }
        g.put(s.cx, s.cy, blink ? "◆" : "◇", C.BRIGHT);
        for (var p = 0; p < s.pops.length; p++) g.put(2, 22 + p, s.pops[p].text, s.pops[p].c);
        if (!s.pops.length) g.put(2, 22, t("hypr_2"), C.DIM);
        g.put(2, 24, "◀ ▲ ▼ ▶ " + t("hypr_keys") + "  ·  OK SUPER+Q", C.DIM);
        if (s.full > 0) g.bigCenter(8, "FULL!", C.RED);
      }
    };
  }

  A.register({
    id: "hyprmaze", title: "HYPRMAZE", color: C.ACC,
    art: ["╭────╮ ╭──────╮", "│kitty│ │ nvim │", "╰────╯◆╰──────╯", "╭─────────────╮"],
    desc: ["hypr_1", "hypr_2"], create: create
  });
})(typeof window !== "undefined" ? window : this);
