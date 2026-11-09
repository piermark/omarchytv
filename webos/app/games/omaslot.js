/* OMASLOT (OmarchyTV Arcade, 2.15): a three-reel slot machine with Omarchy's symbols (the Omarchy mark, Arch,
 * Hyprland, the terminal, Neovim, a coffee, a bug). Points only: no money, nothing to buy, nothing to win but the
 * record. 20 spins and 200 credits each; ▲ ▼ the bet, OK pulls the lever. Three Omarchy marks: the jackpot. With a
 * second phone (player 2) it's turns: P1 and P2 spin one after the other, the most credits after 20 spins wins. ES5. */
(function (root) {
  "use strict";
  var A = root.OmarchyArcade;
  if (!A) return;
  var C = A.C, W = 80, SPINS = 20, START = 200, BETS = [10, 20, 50];
  // each symbol: 4 rows of 10 columns, its colour, and what three of it pay (times the bet)
  var SYM = [
    { id: "omarchy", c: C.ACC, pay: 50, art: ["▄▄▄▄▄▄▄▄  ", "█      █  ", "█  ▄▄  █  ", "█▄▄█  █▄▄▄"] },
    { id: "arch", c: C.CYAN, pay: 20, art: ["    ▲     ", "   ▟█▙    ", "  ▟█▀█▙   ", " ▟▀   ▀▙  "] },
    { id: "hypr", c: C.ACC2, pay: 15, art: ["   ▗▖     ", "  ▟██▙    ", " ▐████▌   ", "  ▀██▀    "] },
    { id: "term", c: C.GREEN, pay: 10, art: ["┌────────┐", "│ >_     │", "│        │", "└────────┘"] },
    { id: "nvim", c: C.GREEN, pay: 8, art: ["█▙    █  ", "█▜▙   █  ", "█ ▜▙  █  ", "█  ▜▙ █  "] },
    { id: "coffee", c: C.YEL, pay: 5, art: ["  ) ) )   ", " ▄▄▄▄▄▄▄  ", " █     █▀▌", " ▀▄▄▄▄▄▀  "] },
    { id: "bug", c: C.RED, pay: 0, art: ["  ╲   ╱   ", " ▗▟███▙▖  ", " ▐█▀█▀█▌  ", "  ╱ ▀ ╲   "] }
  ];
  // a reel's strip: the cheap symbols often, the Omarchy mark once
  var STRIP = [0, 5, 3, 6, 4, 1, 5, 3, 2, 6, 4, 5, 3, 1, 6, 2, 4, 5];
  var RX = [6, 30, 54], RY = 7, RW = 20, RH = 9;

  function create(api) {
    var t = api.t, sfx = api.sfx, R = Math.random;
    var s = { over: false, clock: 0, players: [mk()], turn: 0, bet: 0, reels: [r0(), r0(), r0()], spinning: false, win: null, joined: false, msgT: 0 };
    function mk() { return { credits: START, spins: SPINS, best: START }; }
    function r0() { return { pos: Math.floor(R() * STRIP.length), speed: 0, stopAt: -1, stopped: true }; }
    function sym(reel, off) { return SYM[STRIP[((Math.floor(reel.pos) + off) % STRIP.length + STRIP.length) % STRIP.length]]; }
    function me() { return s.players[s.turn]; }
    function pull() {
      var p = me(), b = BETS[s.bet];
      if (s.spinning || p.spins <= 0) return;
      if (p.credits < b) { s.bet = 0; b = BETS[0]; if (p.credits < b) return; }
      p.credits -= b; p.spins--;
      s.spinning = true; s.win = null; s.paid = b;
      s.reels.forEach(function (r, i) { r.speed = 22 + R() * 4; r.stopped = false; r.stopT = 0.9 + i * 0.55 + R() * 0.2; });
      sfx.play("coin");
    }
    function settle() {
      var a = sym(s.reels[0], 0), b = sym(s.reels[1], 0), c = sym(s.reels[2], 0), bet = s.paid, p = me(), won = 0, kind = "";
      if (a === b && b === c) { won = bet * a.pay; kind = a.id === "omarchy" ? "jackpot" : a.id === "bug" ? "bugs" : "three"; }
      else if (a === b || b === c || a === c) { var two = a === b ? a : c; won = two.id === "bug" ? 0 : bet * 2; kind = won ? "two" : "bugs"; }
      else if ([a, b, c].some(function (x) { return x.id === "omarchy"; })) { won = bet; kind = "mark"; }   // one mark: your bet back
      if (kind === "bugs") { won = 0; api.shake(0.25); sfx.play("hurt"); }
      else if (kind === "jackpot") { sfx.play("record"); api.shake(0.6); }
      else if (won) sfx.play("hit");
      p.credits += won; p.best = Math.max(p.best, p.credits);
      s.win = { won: won, kind: kind }; s.msgT = 2.2;
      function can(q) { return q.spins > 0 && q.credits >= BETS[0]; }
      if (s.players.length > 1 && can(s.players[1 - s.turn])) s.turn = 1 - s.turn;   // turns; a player out of spins or credits is skipped
      if (!s.players.some(can)) s.endT = 2.6;
    }
    var h = {
      over: false, score: 0,
      _state: function () { return s; },
      update: function (dt) {
        s.clock += dt;
        if (s.msgT > 0) s.msgT -= dt;
        if (s.endT > 0) { s.endT -= dt; if (s.endT <= 0) h.over = true; }
        if (s.spinning) {
          var all = true;
          s.reels.forEach(function (r) {
            if (r.stopped) return;
            r.stopT -= dt;
            if (r.stopT > 0) { r.pos = (r.pos + r.speed * dt) % STRIP.length; all = false; return; }
            r.pos = Math.round(r.pos) % STRIP.length; r.stopped = true; sfx.play("blip");
          });
          if (all) { s.spinning = false; settle(); }
        }
        var best = 0; s.players.forEach(function (q) { best = Math.max(best, q.credits); });
        h.score = best;
      },
      key: function (k, who) {
        if (who === 2 && !s.joined) {   // a second player: turns, from the next spin
          s.joined = true; s.players.push(mk()); sfx.play("coin");
          return;
        }
        if (s.players.length > 1 && (who || 1) !== s.turn + 1 && who) return;   // the other phone waits its turn
        if (k === "ok") pull();
        else if (k === "up" && !s.spinning) { s.bet = Math.min(BETS.length - 1, s.bet + 1); sfx.play("blip"); }
        else if (k === "down" && !s.spinning) { s.bet = Math.max(0, s.bet - 1); sfx.play("blip"); }
      },
      draw: function (g) {
        var pad = api.pad, blink = Math.floor(s.clock * 4) % 2, p = me();
        g.row(1, 0, [["OMASLOT", C.YEL], [t("slot_credits") + " " + pad(p.credits, 5), C.BRIGHT], [t("slot_spins") + " " + p.spins + "/" + SPINS, C.FG],
          [t("slot_bet") + " " + BETS[s.bet], C.ACC], [s.players.length > 1 ? "P" + (s.turn + 1) + " ▶" : "", s.turn ? C.YEL : C.ACC]]);
        // the cabinet
        g.box(3, 3, 74, 17, C.DIM, "double");
        for (var x = 5; x < 75; x += 3) { var lit = (x / 3 + Math.floor(s.clock * 6)) % 2 === 0; g.put(x, 3, lit ? "●" : "○", lit ? C.YEL : C.DIM); }
        g.center(4, "★  O M A R C H Y   S L O T  ★", C.YEL);
        for (var i = 0; i < 3; i++) {
          var r = s.reels[i];
          g.box(RX[i] - 1, RY - 1, RW + 2, RH + 2, i === 1 ? C.ACC : C.DIM, "heavy");
          var frac = r.pos - Math.floor(r.pos), shift = r.stopped ? 0 : Math.floor(frac * 5);
          for (var row = -1; row <= 1; row++) {   // the symbol in the window and a sliver of its neighbours
            var sy = sym(r, row), y0 = RY + 2 + row * 5 - shift;
            for (var l = 0; l < 4; l++) {
              var yy = y0 + l;
              if (yy < RY || yy >= RY + RH) continue;
              g.put(RX[i] + 5, yy, sy.art[l], row === 0 && r.stopped ? sy.c : C.DIM, false);
            }
          }
        }
        g.put(4, RY + 3, "▶", C.YEL); g.put(75, RY + 3, "◀", C.YEL);
        // the lever
        var lv = s.spinning ? 4 : 0;
        g.put(77, 6 + lv, "●", C.RED); for (var y = 7 + lv; y < 15; y++) g.put(77, y, "│", C.DIM);
        if (s.win && s.msgT > 0) {
          var w = s.win, txt = w.kind === "jackpot" ? "★ " + t("slot_jackpot") + " +" + w.won + " ★" : w.kind === "bugs" ? "✕ bugs! ✕" : w.won ? "+" + w.won : "—";
          if (w.kind === "jackpot") g.bigCenter(13, "OMARCHY", blink ? C.YEL : C.ACC); else g.center(18, txt, w.kind === "bugs" ? C.RED : w.won ? C.GREEN : C.DIM);
          if (w.kind === "jackpot") g.center(18, txt, C.YEL);
        } else if (!s.spinning) g.center(18, (blink ? "▶ " : "  ") + "OK " + t("slot_pull") + (blink ? " ◀" : "  "), C.BRIGHT);
        if (s.players.length > 1) g.center(21, s.players.map(function (q, n) { return "P" + (n + 1) + " " + q.credits + " (" + q.spins + ")"; }).join("   ·   "), C.FG);
        else g.center(21, t("slot_pays"), C.DIM);
        g.put(2, 23, "▲ ▼ " + t("slot_bet") + "  ·  OK " + t("slot_pull") + "  ·  " + t("slot_fake"), C.DIM);
      }
    };
    return h;
  }

  A.register({
    id: "omaslot", title: "OMASLOT", color: C.YEL,
    art: ["┌───┬───┬───┐", "│ ▲ │>_ │ ▲ │", "└───┴───┴───┘", "  ★ 777 ★  ●"],
    desc: ["slot_1", "slot_2"], create: create, SYM: SYM
  });
})(typeof window !== "undefined" ? window : this);
