/* PANE PONG (OmarchyTV Arcade, 2.15): two tiled windows are the paddles and a cursor is the ball. ▲ ▼ move your
 * window; the ball speeds up at every hit and takes the angle of where it touched. Alone against the CPU, or with a
 * second phone a duel on the couch: first to seven. Every hit is worth points, a point won more. ES5. */
(function (root) {
  "use strict";
  var A = root.OmarchyArcade;
  if (!A) return;
  var C = A.C, W = 80, TOP = 2, BOT = 22, PH = 5, TO = 7;   // the court: rows 2..22, paddles 5 rows tall

  function create(api) {
    var t = api.t, sfx = api.sfx, R = Math.random;
    var s = { over: false, clock: 0, pts: [0, 0], versus: false, joined: false, pause: 1.4, hits: 0, msg: "", msgT: 0,
      p: [{ y: 10, want: 10, x: 3 }, { y: 10, want: 10, x: 76 }], ball: null, score: 0, cpuErr: 0 };
    function serve(dir) {
      var a = (R() - 0.5) * 0.9;
      s.ball = { x: 40, y: 12, vx: dir * 26, vy: Math.sin(a) * 14 };
      s.pause = 1.2; s.hits = 0;
    }
    serve(R() < 0.5 ? -1 : 1);
    function move(n, d) {
      var q = s.p[n];
      q.want = Math.max(TOP, Math.min(BOT - PH + 1, q.want + d * 2));
    }
    function point(w) {   // w: 0 or 1 scored
      s.pts[w]++;
      if (w === 0) s.score += 100;
      s.msg = s.versus ? "P" + (w + 1) + " +1" : w === 0 ? t("pong_point") : t("pong_cpu");
      s.msgT = 1.2;
      sfx.play(w === 0 || s.versus ? "hit" : "hurt");
      if (w === 1 && !s.versus) api.shake(0.25);
      if (s.pts[0] >= TO || s.pts[1] >= TO) { s.endT = 1.6; s.ball = null; return; }
      if (s.joined && !s.versus) { s.versus = true; s.pts = [0, 0]; s.score = 0; s.msg = t("duel_p2_joined").split(":")[0]; }
      serve(w === 0 ? 1 : -1);
    }
    function stepBall(b, dt) {
      b.x += b.vx * dt; b.y += b.vy * dt;
      if (b.y < TOP) { b.y = TOP + (TOP - b.y); b.vy = -b.vy; sfx.play("blip"); }
      if (b.y > BOT) { b.y = BOT - (b.y - BOT); b.vy = -b.vy; sfx.play("blip"); }
      for (var i = 0; i < 2; i++) {
        var q = s.p[i], face = i === 0 ? q.x + 1 : q.x - 1;
        var coming = i === 0 ? b.vx < 0 && b.x <= face && b.x > face - 2 : b.vx > 0 && b.x >= face && b.x < face + 2;
        if (coming && b.y >= q.y - 0.6 && b.y <= q.y + PH - 0.4) {
          var off = (b.y - (q.y + PH / 2 - 0.5)) / (PH / 2);   // -1..1: where it touched
          var speed = Math.min(64, Math.abs(b.vx) * 1.07 + 1);
          b.vx = (i === 0 ? 1 : -1) * speed; b.vy = off * speed * 0.55; b.x = face;
          s.hits++; if (i === 0 && !s.versus) s.score += 10;
          s.cpuErr = (R() - 0.5) * Math.min(5, speed / 12);
          sfx.play("tile");
        }
      }
      if (b.x < 0) point(1); else if (b.x > W - 1) point(0);
    }
    var h = {
      over: false, score: 0,
      _state: function () { return s; },
      update: function (dt) {
        s.clock += dt;
        if (s.msgT > 0) s.msgT -= dt;
        if (s.endT > 0) { s.endT -= dt; if (s.endT <= 0) h.over = true; h.score = s.versus ? 100 * Math.max(s.pts[0], s.pts[1]) : s.score; return; }
        // the windows glide to where they are asked to be
        s.p.forEach(function (q) { var d = q.want - q.y; q.y += Math.max(-1, Math.min(1, d)) * Math.min(Math.abs(d), dt * 30); });
        var b = s.ball;
        if (!b) return;
        if (!s.versus) {   // the CPU follows the ball, a little late, with an error that grows as the ball gets fast
          var cq = s.p[1], aim = b.vx > 0 ? b.y - PH / 2 + s.cpuErr : 10;
          cq.want = Math.max(TOP, Math.min(BOT - PH + 1, aim));
          var sp = 12 + Math.min(6, s.pts[0]);
          var d2 = cq.want - cq.y; cq.y += Math.max(-1, Math.min(1, d2)) * Math.min(Math.abs(d2), dt * sp);
          cq.want = cq.y;
        }
        if (s.pause > 0) { s.pause -= dt; return; }
        var n = Math.max(1, Math.ceil(Math.abs(b.vx * dt) / 0.5));   // small steps: a fast ball never jumps a window
        for (var k = 0; k < n && s.ball === b; k++) stepBall(b, dt / n);
        h.score = s.versus ? 100 * Math.max(s.pts[0], s.pts[1]) : s.score;
      },
      key: function (k, who) {
        var n = who === 2 ? 1 : 0;
        if (who === 2 && !s.joined) { s.joined = true; sfx.play("coin"); }
        if (n === 1 && !s.versus) return;
        if (k === "up") move(n, -1); else if (k === "down") move(n, 1);
      },
      draw: function (g) {
        var pad = api.pad, blink = Math.floor(s.clock * 4) % 2;
        g.row(1, 0, [["PANE PONG", C.ACC], ["P1 " + s.pts[0], C.ACC], [(s.versus ? "P2 " : "CPU ") + s.pts[1], s.versus ? C.YEL : C.RED],
          [s.versus ? t("duel_first_to").replace("{n}", TO) : t("score") + " " + pad(h.score, 6), C.BRIGHT]]);
        g.fill(0, 1, W, 1, "─", C.DIM); g.fill(0, BOT + 1, W, 1, "─", C.DIM);
        for (var y = TOP; y <= BOT; y += 2) g.put(39, y, "┊", C.DIM);
        for (var i = 0; i < 2; i++) {   // a window: title bar, border
          var q = s.p[i], py = Math.round(q.y), col = i === 0 ? C.ACC : s.versus ? C.YEL : C.RED;
          g.box(i === 0 ? q.x - 1 : q.x - 1, py, 3, PH, col, "heavy");
          g.put(q.x, py + Math.floor(PH / 2), i === 0 ? "▌" : "▐", col);
        }
        var b = s.ball;
        if (b) g.put(Math.round(b.x), Math.round(b.y), s.pause > 0 && blink ? " " : "◆", C.BRIGHT);
        if (s.msgT > 0) g.center(12, "  " + s.msg + "  ", C.YEL);
        if (s.joined && !s.versus) g.center(24, t("duel_p2_joined"), blink ? C.YEL : C.BRIGHT);
        else g.put(2, 24, "▲ ▼ " + t("pong_keys") + "  ·  " + (s.versus ? t("duel_two") : t("duel_scan")), C.DIM);
      }
    };
    return h;
  }

  A.register({
    id: "panepong", title: "PANE PONG", color: C.ACC,
    art: ["┃          ", "┃    ◆     ┃", "┃          ┃", "           ┃"],
    desc: ["pong_1", "pong_2"], create: create
  });
})(typeof window !== "undefined" ? window : this);
