/* RICE WARS (OmarchyTV Arcade): the bugs come in three colours and you only see the ones of the theme you are
 * "ricing". OK switches theme and the whole screen re-themes at once (Omarchy's real themes, each with its own chord);
 * ◀ ▶ move, ▲ fires. Shooting a bug you can't see shows its ghost for a moment. Level 1 starts in your own theme;
 * every third level the BLOATWARE boss changes theme while you fight it. If the host allows it, a cleared level
 * re-themes the Omarchy PC too, and the PC gets its own theme back when you leave. ES5. */
(function (root) {
  "use strict";
  var A = root.OmarchyArcade;
  if (!A) return;
  var C = A.C, W = 80, PY = 21, TOP = 2;
  var GLYPHS = [["/o\\", "\\o/"], ["{*}", "}*{"], ["<#>", ">#<"]];
  var BOSS = [" ▄██████▄ ", "██▀▓▓▓▓▀██", "▀██▄▄▄▄██▀"];

  function create(api) {
    var t = api.t, sfx = api.sfx, R = Math.random;
    var names = Object.keys(api.themes || {});
    function pickThemes() {   // your theme plus two others that look different
      var out = [api.home], used = {}; used[api.home.name] = 1;
      for (var n = 0; n < 40 && out.length < 3 && names.length; n++) {
        var nm = names[Math.floor(R() * names.length)];
        if (used[nm]) continue;
        var th = api.normTheme(api.themes[nm], nm);
        if (th.accent === out[0].accent || th.bg === out[out.length - 1].bg) continue;
        used[nm] = 1; out.push(th);
      }
      var fb = [["gruvbox", "#282828", "#d4be98", "#d79921"], ["nord", "#2e3440", "#d8dee9", "#88c0d0"], ["rose-pine", "#191724", "#e0def4", "#ebbcba"]];
      for (var f = 0; out.length < 3; f++) { var x = fb[f % fb.length]; if (!used[x[0]]) out.push(api.normTheme({ background: x[1], foreground: x[2], accent: x[3] }, x[0])); used[x[0]] = 1; }
      return out;
    }
    var s = {
      over: false, score: 0, lives: 3, level: 0, clock: 0, px: 38, inv: 0, ct: 0, themes: [], sweep: 0, banner: 0,
      foes: [], fx: 0, fy: 0, dir: 1, stepT: 0, total: 1, bullets: [], bombs: [], boss: null, bombT: 1.5,
      ghosts: [], ghostT: 1.5, booms: [], clear: 0, cool: 0
    };
    function th() { return s.themes[s.ct]; }
    function level() {
      s.level++; s.themes = pickThemes(); s.ct = 0; api.setPalette(th());
      s.foes = []; s.bullets = []; s.bombs = []; s.boss = null; s.fx = 4; s.fy = TOP + 1; s.dir = 1; s.clear = 0;
      if (s.level % 3 === 0) {
        s.boss = { x: 30, dir: 1, hp: 15 + s.level * 3, max: 15 + s.level * 3, g: 1, gT: 2.5, hitT: 0 };
      } else {
        var rows = Math.min(5, 3 + Math.floor(s.level / 2)), cols = 8;
        for (var r = 0; r < rows; r++) for (var c = 0; c < cols; c++) s.foes.push({ cx: c * 7, cy: r * 2, g: Math.floor(R() * 3), alive: true });
        for (var g = 0; g < 3; g++) s.foes[Math.floor(R() * s.foes.length)].g = g;   // every colour is there
        s.total = s.foes.length;
      }
      s.banner = 1.4;
    }
    level();
    function alive() { var n = 0; for (var i = 0; i < s.foes.length; i++) if (s.foes[i].alive) n++; return n; }
    function hurt() {
      if (s.inv > 0) return;
      s.lives--; s.inv = 1.6; api.shake(0.3); sfx.play("hurt");
      if (s.lives <= 0) s.over = true;
    }
    function rice() {
      if (s.cool > 0) return;
      s.cool = 0.3; s.sweep = 0.22;
      s.ct = (s.ct + 1) % 3;
      sfx.play("switch", s.ct + s.level);
    }
    function boom(x, y) { s.booms.push({ x: x, y: y, t: 0.25 }); }
    function cleared() {
      s.clear = 1.4; s.score += 100 * s.level; sfx.play("levelup");
      api.pcTheme(th().name);
    }

    return {
      get over() { return s.over; }, get score() { return s.score; }, view: null,
      _god: function () { s.lives = 99; }, _state: function () { return s; },
      update: function (dt) {
        s.clock += dt;
        if (s.inv > 0) s.inv -= dt; if (s.cool > 0) s.cool -= dt; if (s.banner > 0) s.banner -= dt;
        if (s.sweep > 0) { s.sweep -= dt; if (s.sweep <= 0) api.setPalette(th()); }
        if (s.clear > 0) { s.clear -= dt; if (s.clear <= 0) level(); return; }
        // bullets and bombs
        for (var b = s.bullets.length - 1; b >= 0; b--) {
          var u = s.bullets[b]; u.y -= 35 * dt;
          if (u.y < TOP) { s.bullets.splice(b, 1); continue; }
          var yy = Math.round(u.y), hit = false;
          for (var i = 0; i < s.foes.length && !hit; i++) {
            var f = s.foes[i]; if (!f.alive) continue;
            var fx = s.fx + f.cx, fy = s.fy + f.cy;
            if (yy === fy && u.x >= fx && u.x <= fx + 2) {
              if (f.g === s.ct) { f.alive = false; hit = true; s.score += 25 * s.level; sfx.play("hit"); boom(fx, fy); }
              else if (!f.seen) { f.seen = 0.35; s.ghosts.push({ x: fx, y: fy, g: f.g, t: 0.35 }); sfx.play("ghost"); }
            }
          }
          if (s.boss && !hit) {
            var bo = s.boss;
            if (yy >= TOP + 1 && yy <= TOP + 3 && u.x >= bo.x && u.x < bo.x + 10) {
              if (bo.g === s.ct) { bo.hp--; bo.hitT = 0.1; hit = true; s.score += 10; sfx.play("hit"); if (bo.hp <= 0) { s.score += 500; boom(bo.x + 4, TOP + 2); s.boss = null; cleared(); } }
              else { hit = true; sfx.play("ghost"); }
            }
          }
          if (hit) s.bullets.splice(b, 1);
        }
        for (var m = s.bombs.length - 1; m >= 0; m--) {
          var bm = s.bombs[m]; bm.y += 12 * dt;
          if (bm.y >= PY + 1) { s.bombs.splice(m, 1); continue; }
          if (Math.round(bm.y) === PY && bm.x >= s.px && bm.x <= s.px + 2) { s.bombs.splice(m, 1); hurt(); }
        }
        for (var k = s.foes.length - 1; k >= 0; k--) if (s.foes[k].seen) { s.foes[k].seen -= dt; if (s.foes[k].seen <= 0) s.foes[k].seen = 0; }
        for (var gh = s.ghosts.length - 1; gh >= 0; gh--) { s.ghosts[gh].t -= dt; if (s.ghosts[gh].t <= 0) s.ghosts.splice(gh, 1); }
        for (var e = s.booms.length - 1; e >= 0; e--) { s.booms[e].t -= dt; if (s.booms[e].t <= 0) s.booms.splice(e, 1); }
        if (s.over) return;
        // the formation steps sideways, faster as it thins out, and drops a row at the edges
        var n = alive();
        if (!s.boss && s.foes.length && n === 0) { cleared(); return; }
        if (!s.boss) {
          s.stepT -= dt;
          if (s.stepT <= 0) {
            s.stepT = Math.max(0.07, 0.55 * n / s.total + 0.06 - s.level * 0.02);
            var minX = 99, maxX = -1, maxY = 0;
            for (var q = 0; q < s.foes.length; q++) if (s.foes[q].alive) { minX = Math.min(minX, s.foes[q].cx); maxX = Math.max(maxX, s.foes[q].cx); maxY = Math.max(maxY, s.foes[q].cy); }
            if ((s.dir > 0 && s.fx + maxX + 3 >= W - 1) || (s.dir < 0 && s.fx + minX <= 1)) { s.dir = -s.dir; s.fy++; }
            else s.fx += s.dir;
            if (s.fy + maxY >= PY - 1) { hurt(); s.fy = Math.max(TOP + 1, s.fy - 5); }
          }
        } else {
          var bo2 = s.boss;
          bo2.x += bo2.dir * (8 + s.level) * dt;
          if (bo2.x < 1 || bo2.x > W - 11) bo2.dir = -bo2.dir;
          bo2.gT -= dt; if (bo2.gT <= 0) { bo2.g = (bo2.g + 1 + Math.floor(R() * 2)) % 3; bo2.gT = 2.5; sfx.play("ghost"); }
          if (bo2.hitT > 0) bo2.hitT -= dt;
        }
        // bugs (seen or not) drop segfaults; the bombs are always visible
        s.bombT -= dt;
        if (s.bombT <= 0) {
          s.bombT = Math.max(0.25, 1.4 - s.level * 0.12) * (0.5 + R());
          if (s.boss) s.bombs.push({ x: Math.round(s.boss.x + 2 + R() * 6), y: TOP + 4 });
          else {
            var live = s.foes.filter(function (f2) { return f2.alive; });
            if (live.length) { var sh = live[Math.floor(R() * live.length)]; s.bombs.push({ x: s.fx + sh.cx + 1, y: s.fy + sh.cy + 1 }); }
          }
        }
        // now and then an unseen bug flickers
        s.ghostT -= dt;
        if (s.ghostT <= 0 && !s.boss) {
          s.ghostT = 1 + R() * 1.5;
          var hid = s.foes.filter(function (f3) { return f3.alive && f3.g !== s.ct; });
          if (hid.length) { var hf = hid[Math.floor(R() * hid.length)]; s.ghosts.push({ x: s.fx + hf.cx, y: s.fy + hf.cy, g: hf.g, t: 0.12 }); }
        }
      },
      key: function (k) {
        if (s.clear > 0) return;
        if (k === "left") s.px = Math.max(1, s.px - 2);
        else if (k === "right") s.px = Math.min(W - 4, s.px + 2);
        else if (k === "up") { if (s.bullets.length < 2) { s.bullets.push({ x: s.px + 1, y: PY - 1 }); sfx.play("shoot"); } }
        else if (k === "ok") rice();
      },
      draw: function (g) {
        var pad = api.pad, frame = Math.floor(s.clock * 2) % 2;
        g.row(1, 0, [["RICE WARS", C.ACC], [t("level") + " " + s.level, C.FG], [t("score") + " " + pad(s.score, 6), C.BRIGHT]]);
        var hearts = ""; for (var i = 0; i < 3; i++) hearts += i < s.lives ? "▲ " : "△ ";
        g.put(W - 8, 0, hearts, C.ACC);
        g.put(0, 1, new Array(W + 1).join("─"), C.DIM);
        for (var f = 0; f < s.foes.length; f++) {
          var e = s.foes[f];
          if (!e.alive || e.g !== s.ct) continue;
          g.put(s.fx + e.cx, s.fy + e.cy, GLYPHS[e.g][frame], e.g === 0 ? C.ACC : e.g === 1 ? C.ACC2 : C.CYAN);
        }
        for (var h = 0; h < s.ghosts.length; h++) g.put(s.ghosts[h].x, s.ghosts[h].y, GLYPHS[s.ghosts[h].g][0], C.DIM);
        if (s.boss) {
          var bo = s.boss, see = bo.g === s.ct;
          for (var r = 0; r < 3; r++) g.put(Math.round(bo.x), TOP + 1 + r, see ? BOSS[r] : BOSS[r].replace(/[^ ]/g, "░"), bo.hitT > 0 ? C.BRIGHT : see ? C.RED : C.DIM, false);
          var hpw = 30, fill = Math.round(hpw * bo.hp / bo.max), hp = "";
          for (var z = 0; z < hpw; z++) hp += z < fill ? "█" : "░";
          g.put(44, 0, "BLOATWARE " + hp.substr(0, 20), C.RED);
          if (!see) g.put(Math.round(bo.x) - 2, TOP + 5, t("rice_needs") + ": " + s.themes[bo.g].name, C.DIM);
        }
        for (var b = 0; b < s.bullets.length; b++) g.put(s.bullets[b].x, Math.round(s.bullets[b].y), "│", C.BRIGHT);
        for (var m = 0; m < s.bombs.length; m++) g.put(s.bombs[m].x, Math.round(s.bombs[m].y), "¦", C.RED);
        for (var x = 0; x < s.booms.length; x++) g.put(s.booms[x].x, s.booms[x].y, s.booms[x].t > 0.12 ? "\\|/" : "*·*", C.YEL);
        if (!(s.inv > 0 && Math.floor(s.clock * 10) % 2)) g.put(s.px, PY, "▄█▄", C.BRIGHT);
        g.put(0, PY + 1, new Array(W + 1).join("▔"), C.DIM);
        // the three themes of this level: your rice is highlighted
        var x0 = 1;
        for (var q = 0; q < 3; q++) {
          var lab = " " + GLYPHS[q][0] + " " + s.themes[q].name + " ";
          g.put(x0, 23, lab, q === s.ct ? C.INV : C.DIM);
          x0 += A.textWidth(lab) + 2;
        }
        g.put(0, 24, "◀ ▶  ▲ " + t("rice_fire") + "  ·  OK " + t("rice_switch"), C.DIM);
        if (s.sweep > 0) {   // the new rice wipes down the screen
          var line = Math.round((1 - s.sweep / 0.22) * 22);
          g.put(0, line, new Array(W + 1).join("▀"), C.ACC2);
        }
        if (s.banner > 0) { g.center(10, "  " + t("level") + " " + s.level + "  ", C.INV); g.center(12, t("ready"), C.BRIGHT); }
        else if (s.sweep > 0 || s.cool > 0) g.center(11, "  ▓ " + th().name.toUpperCase() + " ▓  ", C.INV);
        if (s.clear > 0) g.bigCenter(8, "CLEAR!", C.GREEN);
      }
    };
  }

  A.register({
    id: "ricewars", title: "RICE WARS", color: C.ACC2,
    art: ["/o\\ {*} <#>", "             ", "     │       ", "    ▄█▄      "],
    desc: ["rice_1", "rice_2"], create: create
  });
})(typeof window !== "undefined" ? window : this);
