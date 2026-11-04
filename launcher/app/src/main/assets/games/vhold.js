/* V-HOLD (OmarchyTV Arcade): two games in one. On the picture a cursor runs through a terminal (▲ ▼ change lane,
 * dodge the bad sectors, grab the packages); meanwhile the old TV breaks down and you fix it from its front panel:
 * the picture rolls (V-HOLD), leans (H-HOLD), loses its colour (COLOR) — ◀ ▶ turn the broken knob until it locks —
 * or collapses into one line: OK OK slaps the side of the set. The more is broken, the more each metre is worth.
 * The faults are CSS transforms of the picture rows (arcade.js), nothing heavier. ES5. */
(function (root) {
  "use strict";
  var A = root.OmarchyArcade;
  if (!A) return;
  var C = A.C, W = 80, P0 = 1, P1 = 21, LANES = [4, 7, 10, 13, 16], PX = 10;
  var KNOBS = ["roll", "skew", "color"], KX = { roll: 3, skew: 19, color: 35 };
  var LOG = "  kernel: vsync lost  ·  crt0: degauss  ·  hsync drift +3  ·  tube: 27kV ok  ·  omarchy: hyprland up  ·  " +
    "audit: knob turned  ·  sda: bad sector  ·  pacman: 3 packages  ·  crt0: picture lost  ·  ";

  function create(api) {
    var t = api.t, sfx = api.sfx, R = Math.random;
    api.setLayout(P0, P1);
    var s = {
      over: false, score: 0, view: { roll: 0, skew: 0, scaleY: 1, mono: false },
      lane: 2, lives: 3, inv: 0, clock: 0, dist: 0, speed: 16, obst: [], gap: 8, acc: 0,
      knob: { roll: 0, skew: 0, color: 0 }, faults: [], nextFault: 7, lastOk: -9, slapCd: 0, flash: 0, pops: [], settle: 0
    };
    function active(kind) { for (var i = 0; i < s.faults.length; i++) if (s.faults[i].kind === kind) return s.faults[i]; return null; }
    function knobFault() { for (var i = 0; i < s.faults.length; i++) if (s.faults[i].kind !== "collapse") return s.faults[i]; return null; }
    function pop(text, c) { s.pops.push({ text: text, c: c, y: 6, t: 1.2 }); }
    function maxFaults() { return s.clock < 35 ? 1 : s.clock < 75 ? 2 : s.clock < 120 ? 3 : 4; }
    function startFault(kind) {
      if (!kind) {
        var pool = ["roll", "skew", "color", "collapse"].filter(function (k) { return !active(k); });
        if (!pool.length) return;
        kind = pool[Math.floor(R() * pool.length)];
      } else if (active(kind)) return;
      var f = { kind: kind, age: 0 };
      if (kind !== "collapse") {   // a target the knob has to reach; the player only sees the picture get better
        var v = s.knob[kind], d = 0.5 + R() * 0.4, tg = v + (R() < 0.5 ? -d : d);
        if (tg > 1 || tg < -1) tg = v - (tg - v);
        f.target = Math.max(-1, Math.min(1, tg));
        f.drift = (R() - 0.5) * 0.08;
        f.sign = R() < 0.5 ? -1 : 1;
      }
      s.faults.push(f);
      sfx.play("static");
    }
    function err(f) { return Math.abs(s.knob[f.kind] - f.target); }
    function amount(f) { return Math.max(0, Math.min(1, (err(f) - 0.07) / 0.55)); }
    function mult() { return 1 + s.faults.length; }
    function hurt() {
      if (s.inv > 0) return;
      s.lives--; s.inv = 1.6; s.flash = 0.3; api.shake(0.25); sfx.play("hurt");
      if (s.lives <= 0) s.over = true;
    }
    function slap() {
      sfx.play("slap"); api.shake(0.4);
      var col = active("collapse");
      if (col) { s.faults.splice(s.faults.indexOf(col), 1); s.score += 120 * mult(); pop("BONK! +" + 120 * mult(), C.YEL); }
      else pop("BONK!", C.YEL);
      if (s.slapCd > 0) return;
      s.slapCd = 1.5;
      var kf = knobFault();   // sometimes a good slap fixes a knob too... and sometimes it breaks something else
      if (kf && R() < 0.35) { s.knob[kf.kind] = kf.target; }
      else if (R() < (col ? 0.12 : 0.3)) setTimeout(function () { if (!s.over) startFault(); }, 900);
    }
    function spawn() {
      var free = [0, 1, 2, 3, 4], n = 1 + (s.clock > 30 ? 1 : 0) + (s.clock > 70 && R() < 0.5 ? 1 : 0);
      for (var i = 0; i < n; i++) {
        var li = free.splice(Math.floor(R() * free.length), 1)[0];
        s.obst.push({ x: W + 1, lane: li, w: 2 + Math.floor(R() * 3), kind: "bad" });
      }
      if (R() < 0.35) s.obst.push({ x: W + 1 + Math.floor(R() * 4), lane: free[Math.floor(R() * free.length)], w: 1, kind: "pkg" });
      s.gap = 7 + R() * 7 - Math.min(3, s.clock / 40);
    }

    return {
      get over() { return s.over; }, get score() { return s.score; }, view: s.view,
      _fault: function (kind) { startFault(kind); }, _god: function () { s.lives = 99; }, _state: function () { return s; },   // demo video and tests
      update: function (dt) {
        s.clock += dt; if (s.inv > 0) s.inv -= dt; if (s.flash > 0) s.flash -= dt; if (s.slapCd > 0) s.slapCd -= dt;
        s.speed = Math.min(42, 16 + s.clock * 0.25);
        var mv = s.speed * dt;
        s.dist += mv; s.acc += mv;
        s.score += mv * mult() * 0.5;
        if (s.acc >= s.gap) { s.acc = 0; spawn(); }
        for (var i = s.obst.length - 1; i >= 0; i--) {
          var o = s.obst[i];
          o.x -= mv;
          if (o.x + o.w < 0) { s.obst.splice(i, 1); continue; }
          if (o.lane === s.lane && o.x < PX + 2 && o.x + o.w > PX) {
            if (o.kind === "pkg") { s.obst.splice(i, 1); s.score += 100 * mult(); sfx.play("coin"); pop("◆ +" + 100 * mult(), C.YEL); }
            else hurt();
          }
        }
        // faults come more often and pile up as the run goes on
        if (s.clock >= s.nextFault) {
          if (s.faults.length < maxFaults()) startFault();
          s.nextFault = s.clock + Math.max(3.5, 9 - s.clock / 20) + (R() - 0.5) * 3;
        }
        var v = s.view, roll = active("roll"), skew = active("skew"), color = active("color"), col = active("collapse");
        for (var k = 0; k < s.faults.length; k++) {
          var f = s.faults[k];
          f.age += dt;
          if (f.kind !== "collapse") {
            f.target = Math.max(-1, Math.min(1, f.target + f.drift * dt));
            if (err(f) < 0.08) {   // locked
              s.faults.splice(k, 1); k--;
              s.score += 150 * mult(); sfx.play("lock"); pop(f.kind === "roll" ? "V-HOLD" : f.kind === "skew" ? "H-HOLD" : t("vhold_color"), C.GREEN);
              pop(t("vhold_locked") + " +" + 150 * mult(), C.GREEN);
            }
          }
        }
        if (roll) { var a = amount(roll); v.roll += roll.sign * (a > 0 ? 3 + 22 * a : 0) * dt; s.settle = 1; }
        else if (s.settle && v.roll) {   // the picture catches: it rolls on quickly to the next frame and stops
          var ph = P1 - P0 + 1, m = ((v.roll % ph) + ph) % ph, step = 30 * dt;
          if (m < 0.01 || m + step >= ph) { v.roll = 0; s.settle = 0; } else v.roll += step;
        }
        v.skew = skew ? skew.sign * (4 + 24 * amount(skew)) * (0.85 + 0.15 * Math.sin(s.clock * 7)) : 0;
        v.mono = !!(color && amount(color) > 0.12);
        v.scaleY = col ? Math.max(0.03, 1 - col.age / 3) : 1;
        for (var p = s.pops.length - 1; p >= 0; p--) { s.pops[p].t -= dt; s.pops[p].y -= dt * 2; if (s.pops[p].t <= 0) s.pops.splice(p, 1); }
      },
      key: function (k) {
        if (k === "up" && s.lane > 0) { s.lane--; sfx.play("lane"); }
        else if (k === "down" && s.lane < LANES.length - 1) { s.lane++; sfx.play("lane"); }
        else if (k === "left" || k === "right") {
          var f = knobFault(), kind = f ? f.kind : "roll";
          s.knob[kind] = Math.max(-1, Math.min(1, s.knob[kind] + (k === "left" ? -0.06 : 0.06)));
          sfx.play("knob");
        } else if (k === "ok") {
          if (s.clock - s.lastOk < 0.45) { s.lastOk = -9; slap(); } else s.lastOk = s.clock;
        }
      },
      draw: function (g) {
        var pad = api.pad;
        // the TV's on-screen display (above the picture, never distorted)
        g.row(0, 0, [[" CH 03 ", C.INV], ["V-HOLD", C.ACC], [t("score") + " " + pad(s.score, 6), C.BRIGHT], ["x" + mult(), mult() > 1 ? C.YEL : C.DIM]]);
        var hearts = ""; for (var i = 0; i < 3; i++) hearts += i < s.lives ? "■ " : "□ ";
        g.put(W - 8, 0, hearts, C.RED);
        // the picture: a terminal with five lanes
        g.put(1, P0 + 1, "[user@omarchy ~]$ tail -f /var/log/crt0", C.DIM);
        var off = Math.floor(s.dist) % 4;
        for (var l = 0; l < LANES.length; l++) {
          var dots = ""; for (var x = 0; x < W; x++) dots += (x + off) % 4 === 0 ? "·" : " ";
          g.put(0, LANES[l], dots, C.DIM);
        }
        var lo = Math.floor(s.dist * 0.6) % LOG.length, logLine = (LOG + LOG).substr(lo, W);
        g.put(0, P1 - 2, logLine, C.DIM);
        for (var j = 0; j < s.obst.length; j++) {
          var o = s.obst[j], txt = "";
          for (var q = 0; q < o.w; q++) txt += o.kind === "pkg" ? "◆" : "▓";
          g.put(Math.round(o.x), LANES[o.lane], txt, o.kind === "pkg" ? C.YEL : C.RED);
        }
        if (!(s.inv > 0 && Math.floor(s.clock * 10) % 2)) g.put(PX, LANES[s.lane], "█▶", C.ACC);
        for (var p = 0; p < s.pops.length; p++) g.put(PX + 6, P0 + Math.max(1, Math.round(s.pops[p].y)) + p, s.pops[p].text, s.pops[p].c);
        if (s.flash > 0) {
          var n = "  ░░▒▓";
          for (var y = P0; y < P1; y += 2) { var st = ""; for (var z = 0; z < W; z++) st += n.charAt(Math.floor(Math.random() * n.length)); g.put(0, y, st, C.DIM); }
        }
        // the front panel with the knobs (below the picture, never distorted)
        var bar = ""; for (var b = 0; b < W; b++) bar += "▀";
        g.put(0, P1, bar, C.DIM);
        var kf = knobFault();
        for (var kk = 0; kk < KNOBS.length; kk++) {
          var name = KNOBS[kk], x0 = KX[name], f = active(name), sel = kf && kf.kind === name;
          var pos = Math.round((s.knob[name] + 1) / 2 * 10), gauge = "";
          for (var c = 0; c <= 10; c++) gauge += c === pos ? "◆" : "─";
          g.put(x0, P1 + 1, "[" + gauge + "]", f ? C.RED : C.FG);
          var label = name === "roll" ? "V-HOLD" : name === "skew" ? "H-HOLD" : t("vhold_color");
          var lab = sel && Math.floor(s.clock * 3) % 2 ? "◀ " + label + " ▶" : "  " + label + "  ";
          g.put(x0 + Math.floor((13 - A.textWidth(lab)) / 2), P1 + 2, lab, sel ? C.INV : f ? C.RED : C.DIM);
        }
        var col = active("collapse");
        var slapTxt = "OK OK  " + t("vhold_slap");
        g.put(52, P1 + 1, slapTxt, col && Math.floor(s.clock * 4) % 2 ? C.INV : col ? C.RED : C.DIM);
        g.put(52, P1 + 2, "▲ ▼  " + t("vhold_lane"), C.DIM);
        g.put(W - 2, P1 + 3, "●", C.RED);
        g.put(W - 13, P1 + 3, "OMARCHY TV", C.DIM);
        g.put(3, P1 + 3, "◀ ▶  " + t("vhold_knob"), C.DIM);
      }
    };
  }

  A.register({
    id: "vhold", title: "V-HOLD", color: C.ACC,
    art: ["╭──────────╮", "│ ▓▓  █▶ ◆ │", "│≈≈≈≈≈≈≈≈≈≈│", "╰──o──o──o─╯"],
    desc: ["vhold_1", "vhold_2"], create: create
  });
})(typeof window !== "undefined" ? window : this);
