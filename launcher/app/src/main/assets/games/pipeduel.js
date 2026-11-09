/* PIPE DUEL (OmarchyTV Arcade, 2.15): two processes in one terminal, each one leaving a pipe behind it. Hit a wall,
 * a pipe, or the other head and your process exits. Alone you play against a CPU process that gets smarter every
 * round (three lives, the score grows with every round you survive); scan the arcade's QR with two phones and it's a
 * couch duel, first to five. Player 2 is the CPU until a second phone (or pad 2) presses a key: the next round is
 * a new match between the two of you. Keys arrive as key(k, player): the remote and phone 1 are player 1. ES5. */
(function (root) {
  "use strict";
  var A = root.OmarchyArcade;
  if (!A) return;
  var C = A.C, W = 80, X0 = 1, Y0 = 2, AW = 78, AH = 21, WIN_TO = 5, LIVES = 3;
  var DIRS = { up: [0, -1], down: [0, 1], left: [-1, 0], right: [1, 0] };
  var BACK = { up: "down", down: "up", left: "right", right: "left" };
  // the pipe's piece for (the way it came, the way it goes)
  function piece(from, to) {
    if (from === to) return from === "up" || from === "down" ? "┃" : "━";
    var k = from + ">" + to;
    return { "up>right": "┏", "left>down": "┓", "up>left": "┓", "right>down": "┏", "down>right": "┗", "left>up": "┛",
      "down>left": "┛", "right>up": "┗" }[k] || "╋";
  }

  function create(api) {
    var t = api.t, sfx = api.sfx, R = Math.random;
    var s = { over: false, score: 0, clock: 0, round: 0, phase: "ready", phaseT: 1.6, cells: null, p: [], wins: [0, 0],
      lives: LIVES, versus: false, joined: false, msg: "", msgC: C.FG, cpuSkill: 0.55 };

    function idx(x, y) { return (y - Y0) * AW + (x - X0); }
    function inside(x, y) { return x >= X0 && x < X0 + AW && y >= Y0 && y < Y0 + AH; }
    function free(x, y) { return inside(x, y) && !s.cells[idx(x, y)]; }
    function newRound() {
      if (s.joined && !s.versus) { s.versus = true; s.wins = [0, 0]; s.score = 0; s.round = 0; }   // a second player: a new match
      s.round++;
      s.cells = [];
      for (var i = 0; i < AW * AH; i++) s.cells.push(0);
      var my = Y0 + Math.floor(AH / 2);
      s.p = [mk(1, X0 + 10, my, "right", C.ACC), mk(2, X0 + AW - 11, my, "left", s.versus ? C.YEL : C.RED)];
      s.p.forEach(function (q) { s.cells[idx(q.x, q.y)] = { c: q.color, ch: "●" }; });
      s.phase = "ready"; s.phaseT = 1.6;
      s.step = Math.max(0.045, 0.085 - s.round * 0.002);   // a little faster every round
      s.cpuSkill = Math.min(0.97, 0.55 + s.round * 0.06);
    }
    function mk(n, x, y, dir, color) { return { n: n, x: x, y: y, dir: dir, from: dir, queue: [], acc: 0, alive: true, color: color }; }
    function turn(q, k) {
      var last = q.queue.length ? q.queue[q.queue.length - 1] : q.dir;
      if (k === last || k === BACK[last] || q.queue.length >= 2) return;
      q.queue.push(k);
    }
    // the CPU: of the three ways it can go, the one with the most room behind it (a flood fill), mostly
    function room(x, y, cap) {
      if (!free(x, y)) return 0;
      var seen = {}, q = [[x, y]], n = 0;
      seen[x + "," + y] = 1;
      while (q.length && n < cap) {
        var c = q.shift(); n++;
        for (var d in DIRS) {
          var nx = c[0] + DIRS[d][0], ny = c[1] + DIRS[d][1], key = nx + "," + ny;
          if (!seen[key] && free(nx, ny)) { seen[key] = 1; q.push([nx, ny]); }
        }
      }
      return n;
    }
    function think(q) {
      var cur = q.queue.length ? q.queue[q.queue.length - 1] : q.dir, best = null, bestV = -1;
      if (R() > s.cpuSkill && free(q.x + DIRS[cur][0], q.y + DIRS[cur][1])) return;   // a slower process sometimes just goes on
      for (var d in DIRS) {
        if (d === BACK[cur]) continue;
        var v = room(q.x + DIRS[d][0], q.y + DIRS[d][1], 160) + (d === cur ? 3 : 0) + R() * 2;
        if (v > bestV) { bestV = v; best = d; }
      }
      if (best && best !== cur) turn(q, best);
    }
    function move(q) {
      if (q.queue.length) q.dir = q.queue.shift();
      var d = DIRS[q.dir];
      s.cells[idx(q.x, q.y)] = { c: q.color, ch: piece(q.from, q.dir) };
      q.from = q.dir;
      q.x += d[0]; q.y += d[1];
    }
    function endRound(w) {   // w: 1, 2, or 0 for both out
      s.phase = "end"; s.phaseT = 1.8;
      if (w) s.wins[w - 1]++;
      if (s.versus) {
        s.msg = w ? (w === 1 ? "P1 " : "P2 ") + t("duel_wins") : t("duel_draw");
        s.msgC = w === 1 ? C.ACC : w === 2 ? C.YEL : C.FG;
        s.score = 100 * Math.max(s.wins[0], s.wins[1]);
        if (s.wins[0] >= WIN_TO || s.wins[1] >= WIN_TO) s.final = true;
        sfx.play(w ? "hit" : "hurt");
      } else {
        if (w === 1) { s.score += 100 * s.round; s.msg = t("duel_cpu_out"); s.msgC = C.GREEN; sfx.play("hit"); }
        else { s.lives--; s.msg = w === 2 ? t("duel_you_out") : t("duel_draw"); s.msgC = C.RED; sfx.play("hurt"); api.shake(0.3); }
        if (s.lives <= 0) s.final = true;
      }
    }

    newRound();
    var h = {
      over: false, score: 0,
      _state: function () { return s; },
      update: function (dt) {
        s.clock += dt;
        if (s.phase === "ready") { s.phaseT -= dt; if (s.phaseT <= 0) { s.phase = "run"; sfx.play("start"); } return; }
        if (s.phase === "end") {
          s.phaseT -= dt;
          if (s.phaseT <= 0) { if (s.final) { h.over = true; return; } newRound(); }
          return;
        }
        // horizontal cells are about half as tall as they are wide: going up and down takes twice the time
        var dead = [false, false], moved = false;
        for (var i = 0; i < 2; i++) {
          var q = s.p[i], stepT = q.dir === "up" || q.dir === "down" ? s.step * 2 : s.step;
          q.acc += dt;
          if (q.acc < stepT) continue;
          q.acc -= stepT;
          if (q.acc > stepT) q.acc = 0;
          if (i === 1 && !s.versus) think(q);
          move(q);
          moved = true;
          if (!free(q.x, q.y)) dead[i] = true;
        }
        if (!moved) return;
        if (s.p[0].x === s.p[1].x && s.p[0].y === s.p[1].y) dead = [true, true];   // head on
        for (var j = 0; j < 2; j++) {
          var p = s.p[j];
          if (!dead[j] && inside(p.x, p.y)) s.cells[idx(p.x, p.y)] = { c: p.color, ch: "●" };
          p.alive = !dead[j];
        }
        if (!s.versus) s.score += dt * 10;   // alive is worth something too
        if (dead[0] || dead[1]) endRound(dead[0] && dead[1] ? 0 : dead[0] ? 2 : 1);
        h.score = Math.floor(s.score);
      },
      key: function (k, who) {
        var n = who === 2 ? 2 : 1;
        if (n === 2 && !s.joined) { s.joined = true; sfx.play("coin"); }
        if (!DIRS[k]) return;
        if (n === 2 && !s.versus) return;   // P2 takes over from the CPU at the next round
        if (s.phase !== "end") turn(s.p[n - 1], k);
      },
      draw: function (g) {
        var pad = api.pad, blink = Math.floor(s.clock * 4) % 2;
        var p1 = "P1 " + (s.versus ? s.wins[0] + "/" + WIN_TO : new Array(s.lives + 1).join("♥") + new Array(LIVES - s.lives + 1).join("·"));
        var p2 = s.versus ? "P2 " + s.wins[1] + "/" + WIN_TO : "CPU";
        g.row(1, 0, [["PIPE DUEL", C.ACC], [p1, C.ACC], [p2, s.versus ? C.YEL : C.RED],
          [s.versus ? t("duel_first_to").replace("{n}", WIN_TO) : t("score") + " " + pad(h.score, 6), C.BRIGHT], [t("level") + " " + s.round, C.DIM]]);
        g.box(X0 - 1, Y0 - 1, AW + 2, AH + 2, C.DIM, "round");
        for (var y = 0; y < AH; y++) for (var x = 0; x < AW; x++) {
          var c = s.cells[y * AW + x];
          if (c) g.put(X0 + x, Y0 + y, c.ch, c.c);
        }
        for (var i = 0; i < 2; i++) {
          var q = s.p[i];
          if (inside(q.x, q.y)) g.put(q.x, q.y, q.alive ? (blink ? "◆" : "●") : "✕", q.alive ? (blink ? C.BRIGHT : q.color) : C.RED);
        }
        if (s.phase === "ready") {
          g.bigCenter(8, s.phaseT > 0.5 ? "READY" : "GO!", s.phaseT > 0.5 ? C.YEL : C.GREEN);
        }
        if (s.phase === "end") g.center(13, "  " + s.msg + "  ", s.msgC);
        if (s.joined && !s.versus) g.center(24, t("duel_p2_joined"), blink ? C.YEL : C.BRIGHT);
        else g.put(2, 24, "◀ ▲ ▼ ▶ " + t("duel_keys") + "  ·  " + (s.versus ? t("duel_two") : t("duel_scan")), C.DIM);
      }
    };
    return h;
  }

  A.register({
    id: "pipeduel", title: "PIPE DUEL", color: C.YEL,
    art: ["━━━━━━┓   ┏━━●", "      ┃   ┃   ", "  ●━━━┛   ┗━━━", "  P1   vs   P2 "],
    desc: ["duel_1", "duel_2"], create: create
  });
})(typeof window !== "undefined" ? window : this);
