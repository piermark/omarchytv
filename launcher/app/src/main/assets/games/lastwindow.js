/* LAST WINDOW (OmarchyTV Arcade, 2.15): a card game for one or two. Close all your windows first. The deck: four
 * colours of your theme, workspaces 1-9 in each, and Omarchy's own moves: SUPER+W (the other player loses a turn),
 * FORK +2 (the other one draws two), THEME (play it on anything and pick the colour) and OOM +4 (pick the colour, the
 * other one draws four). Play a card of the same colour or the same workspace/move; with nothing to play, draw one.
 * One card left: LAST WINDOW! Alone against the CPU; with a second phone, two on one TV: when the turn passes the hand
 * hides until its player presses OK (look away!). Score: the points left in the other hand. ES5. */
(function (root) {
  "use strict";
  var A = root.OmarchyArcade;
  if (!A) return;
  var C = A.C, W = 80;
  var COLS = [C.ACC, C.RED, C.GREEN, C.YEL], CNAME = ["▲", "♥", "♣", "★"];
  // kinds: 1..9 workspaces; "skip" SUPER+W; "fork" +2; "theme" wild; "oom" wild +4
  function deck(R) {
    var d = [], c, n;
    for (c = 0; c < 4; c++) {
      for (n = 1; n <= 9; n++) { d.push({ c: c, k: n }); if (n > 1) d.push({ c: c, k: n }); }
      d.push({ c: c, k: "skip" }, { c: c, k: "skip" }, { c: c, k: "fork" }, { c: c, k: "fork" });
    }
    for (n = 0; n < 4; n++) d.push({ c: -1, k: "theme" }, { c: -1, k: "oom" });
    for (var i = d.length - 1; i > 0; i--) { var j = Math.floor(R() * (i + 1)), x = d[i]; d[i] = d[j]; d[j] = x; }
    return d;
  }
  function label(cd) { return typeof cd.k === "number" ? String(cd.k) : { skip: "W", fork: "+2", theme: "◐", oom: "+4" }[cd.k]; }
  function value(cd) { return typeof cd.k === "number" ? cd.k : cd.c < 0 ? 50 : 20; }

  function create(api) {
    var t = api.t, sfx = api.sfx, R = Math.random;
    var s = { over: false, clock: 0, pile: [], hands: [[], []], top: null, colour: 0, turn: 0, sel: 0, versus: false, joined: false,
      hidden: false, picking: false, pts: [0, 0], msg: "", msgT: 0, cpuT: 0, endT: 0, winner: -1, score: 0, drew: false };
    function draw(n) { if (!s.pile.length) refill(); return s.pile.length ? s.pile.pop() : null; }
    function refill() { s.pile = deck(R); }   // a fresh shuffled deck when the pile runs out
    function deal() {
      s.pile = deck(R); s.hands = [[], []];
      for (var i = 0; i < 7; i++) { s.hands[0].push(draw()); s.hands[1].push(draw()); }
      do { s.top = draw(); if (s.top.c < 0) s.pile.unshift(s.top); } while (s.top.c < 0);   // never a wild one first: it goes back under the pile
      s.colour = s.top.c; s.turn = 0; s.sel = 0; s.picking = false; s.winner = -1; s.drew = false;
      s.hidden = s.versus;   // two people: P1 looks first
      sort(0); sort(1);
    }
    function sort(p) { s.hands[p].sort(function (a, b) { return (a.c - b.c) || (String(a.k) < String(b.k) ? -1 : 1); }); }
    function ok(cd) { return cd.c < 0 || cd.c === s.colour || cd.k === s.top.k; }
    function say(m, c) { s.msg = m; s.msgC = c; s.msgT = 1.6; }
    function give(p, n) { for (var i = 0; i < n; i++) { var cd = draw(); if (cd) s.hands[p].push(cd); } sort(p); }
    function play(p, i, colour) {
      var cd = s.hands[p].splice(i, 1)[0], o = 1 - p;
      s.top = cd; s.colour = cd.c < 0 ? colour : cd.c; s.drew = false;
      sfx.play(cd.c < 0 ? "switch" : "tile");
      if (!s.hands[p].length) { end(p); return; }
      if (s.hands[p].length === 1) { say("LAST WINDOW!", C.YEL); sfx.play("coin"); }
      var again = false;
      if (cd.k === "skip") { say("SUPER+W", COLS[s.colour]); again = true; }
      else if (cd.k === "fork") { give(o, 2); say("FORK +2", C.RED); again = true; api.shake(0.15); }
      else if (cd.k === "oom") { give(o, 4); say("OOM +4", C.RED); again = true; api.shake(0.3); }
      else if (cd.k === "theme") say(t("card_theme") + " " + CNAME[s.colour], COLS[s.colour]);
      next(again ? p : o);
    }
    function next(p) {
      var changed = p !== s.turn;
      s.turn = p; s.sel = 0; s.drew = false;
      if (changed && s.versus) s.hidden = true;   // pass the TV: the hand waits for its player
      s.cpuT = 0.9;
    }
    function end(p) {
      s.winner = p;
      var pts = 0; s.hands[1 - p].forEach(function (cd) { pts += value(cd); });
      s.pts[p] += pts;
      s.score = s.versus ? Math.max(s.pts[0], s.pts[1]) : s.pts[0];
      say(s.versus ? "P" + (p + 1) + " " + t("duel_wins") : p === 0 ? t("card_you_win") : t("card_cpu_wins"), p === 0 ? C.GREEN : C.RED);
      sfx.play(p === 0 || s.versus ? "record" : "gameover");
      s.endT = 3;
    }
    // the CPU: the card that hurts most when you are close to the end, a colour it holds most of for the wild ones
    function cpu() {
      var h = s.hands[1], best = -1, bestV = -1;
      for (var i = 0; i < h.length; i++) {
        if (!ok(h[i])) continue;
        var v = h[i].c < 0 ? 1 : 10 + (typeof h[i].k === "number" ? h[i].k : 5);
        if (s.hands[0].length <= 2 && (h[i].k === "fork" || h[i].k === "oom" || h[i].k === "skip")) v += 50;
        if (v > bestV) { bestV = v; best = i; }
      }
      if (best < 0) {
        if (s.drew) { next(0); return; }
        give(1, 1); s.drew = true; s.cpuT = 0.6; sfx.play("blip"); return;
      }
      var cnt = [0, 0, 0, 0]; h.forEach(function (cd) { if (cd.c >= 0) cnt[cd.c]++; });
      var col = cnt.indexOf(Math.max.apply(null, cnt));
      play(1, best, col);
    }
    deal();
    var h = {
      over: false, score: 0,
      _state: function () { return s; },
      update: function (dt) {
        s.clock += dt;
        if (s.msgT > 0) s.msgT -= dt;
        if (s.endT > 0) {
          s.endT -= dt;
          if (s.endT <= 0) {
            if (!s.versus && s.winner === 1) { h.over = true; return; }   // the CPU won: game over
            if (s.versus && s.score >= 200) { h.over = true; return; }   // a match between two: to 200 points
            if (s.joined && !s.versus) { s.versus = true; s.score = 0; s.pts = [0, 0]; }
            deal();
          }
          return;
        }
        if (!s.versus && s.turn === 1) { s.cpuT -= dt; if (s.cpuT <= 0) cpu(); }
        h.score = s.score;
      },
      key: function (k, who) {
        if (who === 2 && !s.joined) { s.joined = true; sfx.play("coin"); return; }
        if (s.endT > 0) return;
        var p = s.turn;
        if (!s.versus && p === 1) return;           // the CPU is thinking
        if (who && s.versus && who !== p + 1) return;   // a phone plays only its own turn (the remote plays any)
        if (s.hidden) { if (k === "ok") { s.hidden = false; sfx.play("blip"); } return; }
        var hand = s.hands[p];
        if (s.picking) {
          if (k === "left") s.pick = (s.pick + 3) % 4; else if (k === "right") s.pick = (s.pick + 1) % 4;
          else if (k === "ok") { s.picking = false; play(p, s.sel, s.pick); }
          else if (k === "back" || k === "down") s.picking = false;
          return;
        }
        var n = hand.length + 1;   // the last slot is the deck: draw (or pass, after drawing)
        if (k === "left") { s.sel = (s.sel + n - 1) % n; sfx.play("blip"); }
        else if (k === "right") { s.sel = (s.sel + 1) % n; sfx.play("blip"); }
        else if (k === "down") s.sel = hand.length;
        else if (k === "ok") {
          if (s.sel === hand.length) {
            if (s.drew) { next(1 - p); return; }
            var before = hand.slice();
            give(p, 1); s.drew = true; sfx.play("blip");
            for (var q = 0; q < s.hands[p].length; q++) if (before.indexOf(s.hands[p][q]) < 0) s.sel = q;   // the card just drawn
            return;
          }
          var cd = hand[s.sel];
          if (!ok(cd)) { sfx.play("hurt"); say(t("card_no"), C.RED); return; }
          if (cd.c < 0) { s.picking = true; s.pick = s.colour; return; }
          play(p, s.sel);
        }
      },
      draw: function (g) {
        var pad = api.pad, blink = Math.floor(s.clock * 3) % 2, p = s.turn, other = s.versus ? "P" + (2 - p) : "CPU";
        var me = s.versus ? p : 0, them = 1 - me;
        g.row(1, 0, [["LAST WINDOW", C.ACC], [s.versus ? "P1 " + s.pts[0] + " · P2 " + s.pts[1] + " /200" : t("score") + " " + pad(s.score, 4), C.BRIGHT],
          [s.versus ? "P1 " + s.hands[0].length + " · P2 " + s.hands[1].length : "CPU " + s.hands[1].length, C.FG],
          [s.versus ? t("card_turn").replace("{p}", "P" + (p + 1)) : p === 0 ? t("card_your_turn") : "CPU…", p === 0 ? C.ACC : C.YEL]]);
        // the other hand, face down
        var oh = s.hands[them], ox = Math.max(1, Math.floor((W - Math.min(oh.length, 18) * 4) / 2));
        for (var i = 0; i < Math.min(oh.length, 18); i++) g.box(ox + i * 4, 2, 4, 4, C.DIM, "round");
        if (oh.length > 18) g.put(ox + 18 * 4 + 1, 3, "+" + (oh.length - 18), C.DIM);
        g.put(2, 3, s.versus ? "P" + (them + 1) : "CPU", C.DIM);
        // the table: the deck and the top card
        g.box(30, 8, 7, 6, C.DIM, "double"); g.put(32, 10, "▒▒▒", C.DIM); g.put(32, 11, "▒▒▒", C.DIM);
        card(g, 41, 7, s.top, s.colour, true);
        g.put(50, 9, CNAME[s.colour], COLS[s.colour]); g.put(50, 10, t("card_colour"), COLS[s.colour]);
        if (s.msgT > 0) g.center(15, "  " + s.msg + "  ", s.msgC || C.YEL);
        // the hand of the player whose turn it is (P1's always against the CPU)
        var hp = s.versus ? p : 0, hand = s.hands[hp];
        if (s.hidden) {
          g.box(14, 17, 52, 6, C.YEL, "heavy");
          g.center(18, t("card_look").replace("{p}", "P" + (p + 1)), blink ? C.YEL : C.BRIGHT);
          g.center(20, "OK ▶ " + t("card_show"), C.FG);
        } else {
          var per = 6, maxShow = 12, start = Math.max(0, Math.min(s.sel - 6, hand.length - maxShow));
          var shown = hand.slice(start, start + maxShow), hx = Math.floor((W - (shown.length + 1) * per) / 2);
          for (var j = 0; j < shown.length; j++) {
            var idx = start + j, focus = idx === s.sel && (s.versus || p === 0);
            card(g, hx + j * per, focus ? 16 : 17, shown[j], shown[j].c, false, focus, !ok(shown[j]) && (s.versus || p === 0));
          }
          var dx = hx + shown.length * per, df = s.sel === hand.length && (s.versus || p === 0);
          g.box(dx, df ? 16 : 17, 5, 5, df ? C.BRIGHT : C.DIM, df ? "heavy" : "round");
          g.put(dx + 1, (df ? 16 : 17) + 2, s.drew ? t("card_pass").slice(0, 3) : "+1", df ? C.BRIGHT : C.DIM);
          if (start > 0) g.put(hx - 2, 19, "◀", C.DIM);
          if (start + maxShow < hand.length) g.put(dx + 6, 19, "▶", C.DIM);
        }
        if (s.picking) {
          g.box(24, 9, 32, 5, C.BRIGHT, "heavy");
          g.center(10, t("card_pick"), C.BRIGHT);
          for (var c = 0; c < 4; c++) g.put(29 + c * 6, 12, (c === s.pick ? "[" : " ") + CNAME[c] + (c === s.pick ? "]" : " "), COLS[c]);
        }
        if (s.joined && !s.versus) g.center(24, t("duel_p2_joined"), blink ? C.YEL : C.BRIGHT);
        else g.put(2, 24, "◀ ▶ " + t("choose") + "  ·  OK " + t("card_play") + "  ·  ▼ " + t("card_draw"), C.DIM);
      }
    };
    function card(g, x, y, cd, colour, big, focus, dim) {
      var col = cd.c < 0 ? (big ? COLS[colour] : C.BRIGHT) : COLS[cd.c], w = big ? 7 : 5, hh = big ? 7 : 5, lb = label(cd);
      if (dim) col = C.DIM;
      g.box(x, y, w, hh, focus ? C.BRIGHT : col, focus ? "heavy" : "round");
      g.put(x + 1, y + 1, cd.c < 0 ? "◐" : CNAME[cd.c], col);
      g.put(x + Math.floor((w - lb.length) / 2), y + Math.floor(hh / 2), lb, focus ? C.BRIGHT : col);
      if (big && typeof cd.k !== "number") g.put(x + 1, y + hh - 2, { skip: "SUPER", fork: "FORK", theme: "THEME", oom: "OOM" }[cd.k], col);
    }
    return h;
  }

  A.register({
    id: "lastwindow", title: "LAST WINDOW", color: C.GREEN,
    art: ["╭───╮╭───╮╭───╮", "│▲ 7││♥+2││ ◐ │", "│   ││   ││   │", "╰───╯╰───╯╰───╯"],
    desc: ["card_1", "card_2"], create: create
  });
})(typeof window !== "undefined" ? window : this);
