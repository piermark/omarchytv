/* TILT (OmarchyTV Arcade): the old wooden marble maze, but it is the TV that tilts. The arrows tilt the whole
 * picture (a real CSS rotation of the screen, and a perspective lean forward/back), a ball rolls by gravity between
 * the ASCII walls; the holes are /dev/null. Each press tilts a bit more, letting go levels it slowly, so a slow
 * remote is fine. Reach the exit fast: the clock is the score. A new maze per level (seeded), more holes, more
 * gravity. ES5. */
(function (root) {
  "use strict";
  var A = root.OmarchyArcade;
  if (!A) return;
  var C = A.C, RW = 14, RH = 9, MW = RW * 2 + 1, MH = RH * 2 + 1, X0 = 11, Y0 = 2, R = 0.3;
  var WALL = 1, HOLE = 2, EXIT = 3;

  function rng(seed) { var x = seed % 2147483647; if (x <= 0) x += 2147483646; return function () { x = x * 16807 % 2147483647; return (x - 1) / 2147483646; }; }
  /** A perfect maze (one way between any two rooms), its exit in the farthest room, holes in dead ends. */
  function makeMaze(lv, seed) {
    var rnd = rng(seed), map = [], i;
    for (i = 0; i < MW * MH; i++) map.push(WALL);
    var seen = [], stack = [[0, 0]];
    for (i = 0; i < RW * RH; i++) seen.push(false);
    seen[0] = true; map[1 + 1 * MW] = 0;
    while (stack.length) {   // depth-first carving
      var cur = stack[stack.length - 1], cx = cur[0], cy = cur[1], nb = [];
      [[1, 0], [-1, 0], [0, 1], [0, -1]].forEach(function (d) {
        var nx = cx + d[0], ny = cy + d[1];
        if (nx >= 0 && ny >= 0 && nx < RW && ny < RH && !seen[nx + ny * RW]) nb.push([nx, ny, d]);
      });
      if (!nb.length) { stack.pop(); continue; }
      var n = nb[Math.floor(rnd() * nb.length)];
      seen[n[0] + n[1] * RW] = true;
      map[(cx * 2 + 1 + n[2][0]) + (cy * 2 + 1 + n[2][1]) * MW] = 0;
      map[(n[0] * 2 + 1) + (n[1] * 2 + 1) * MW] = 0;
      stack.push([n[0], n[1]]);
    }
    // a few extra openings so it is a labyrinth, not a single pipe
    for (var e = 0; e < 6 + lv; e++) {
      var wx = 1 + Math.floor(rnd() * (MW - 2)), wy = 1 + Math.floor(rnd() * (MH - 2));
      if (map[wx + wy * MW] === WALL && ((wx % 2 === 0 && wy % 2 === 1) || (wx % 2 === 1 && wy % 2 === 0))) map[wx + wy * MW] = 0;
    }
    var dist = bfs(map, 1, 1), far = 0, fi = 1 + MW;
    for (i = 0; i < dist.length; i++) if (dist[i] > far) { far = dist[i]; fi = i; }
    map[fi] = EXIT;
    // holes: dead-end rooms off the way (never the start, the exit or the way between them)
    var path = {}, p = fi;
    while (p !== 1 + MW && dist[p] > 0) {
      path[p] = true;
      var best = -1;
      [1, -1, MW, -MW].forEach(function (o) { if (dist[p + o] === dist[p] - 1) best = p + o; });
      if (best < 0) break;
      p = best;
    }
    var holes = 0, want = Math.min(14, 3 + lv * 2);
    for (var t = 0; t < 400 && holes < want; t++) {
      var hx = 1 + 2 * Math.floor(rnd() * RW), hy = 1 + 2 * Math.floor(rnd() * RH), hi = hx + hy * MW;
      if (map[hi] !== 0 || path[hi] || hi === 1 + MW || dist[hi] < 3) continue;
      var open = 0;
      [1, -1, MW, -MW].forEach(function (o) { if (map[hi + o] !== WALL) open++; });
      if (open <= 1 || rnd() < 0.25) { map[hi] = HOLE; holes++; }
    }
    return { map: map, exit: { x: fi % MW, y: Math.floor(fi / MW) }, start: { x: 1, y: 1 } };
  }
  function bfs(map, sx, sy) {
    var dist = [], q = [sx + sy * MW];
    for (var i = 0; i < MW * MH; i++) dist.push(-1);
    dist[q[0]] = 0;
    for (var h = 0; h < q.length; h++) {
      var c = q[h];
      [1, -1, MW, -MW].forEach(function (o) { var n = c + o; if (n >= 0 && n < map.length && dist[n] < 0 && map[n] !== WALL && map[n] !== HOLE) { dist[n] = dist[c] + 1; q.push(n); } });
    }
    return dist;
  }

  function create(api) {
    var t = api.t, sfx = api.sfx;
    api.setLayout(Y0 - 1, Y0 + MH + 1);
    var s = { over: false, score: 0, lives: 3, level: 0, clock: 0, lt: 0, tx: 0, ty: 0, held: { x: 0, y: 0 },
      bx: 1.5, by: 1.5, vx: 0, vy: 0, map: null, exit: null, fall: 0, win: 0, pops: [], bump: 0, roll: 0,
      view: { rotate: 0, rotX: 0 } };
    function level() {
      s.level++;
      var L = makeMaze(s.level, 4242 + s.level * 97);
      s.map = L.map; s.exit = L.exit; s.lt = 0; s.win = 0;
      spawn();
    }
    function spawn() { s.bx = 1.5; s.by = 1.5; s.vx = s.vy = 0; s.tx = s.ty = 0; s.fall = 0; }
    function cell(x, y) { x = Math.floor(x); y = Math.floor(y); return x < 0 || y < 0 || x >= MW || y >= MH ? WALL : s.map[x + y * MW]; }
    function hitsWall(x, y) {
      return cell(x - R, y - R) === WALL || cell(x + R, y - R) === WALL || cell(x - R, y + R) === WALL || cell(x + R, y + R) === WALL;
    }
    level();

    return {
      get over() { return s.over; }, get score() { return s.score; }, view: s.view,
      _state: function () { return s; }, _god: function () { s.lives = 99; },
      update: function (dt) {
        s.clock += dt;
        for (var p = s.pops.length - 1; p >= 0; p--) { s.pops[p].t -= dt; if (s.pops[p].t <= 0) s.pops.splice(p, 1); }
        // the tilt eases back to level when the keys stop
        ["x", "y"].forEach(function (k) {
          s.held[k] -= dt;
          var key = k === "x" ? "tx" : "ty";
          if (s.held[k] <= 0) s[key] -= Math.max(-dt * 1.2, Math.min(dt * 1.2, s[key]));
        });
        s.view.rotate = s.tx * 6;
        s.view.rotX = -s.ty * 10;
        if (s.win > 0) { s.win -= dt; if (s.win <= 0) level(); return; }
        if (s.fall > 0) {   // the ball drops into /dev/null, then comes back to the start
          s.fall -= dt;
          if (s.fall <= 0) { if (s.lives <= 0) s.over = true; else spawn(); }
          return;
        }
        s.lt += dt;
        var g = 22 + s.level * 2.5, fr = Math.pow(0.35, dt);
        s.vx = (s.vx + s.tx * g * dt) * fr;
        s.vy = (s.vy + s.ty * g * dt) * fr;
        var max = 14;
        s.vx = Math.max(-max, Math.min(max, s.vx)); s.vy = Math.max(-max, Math.min(max, s.vy));
        // move in small steps so it never jumps through a wall
        var steps = Math.ceil(Math.max(Math.abs(s.vx), Math.abs(s.vy)) * dt / 0.2) || 1;
        for (var i = 0; i < steps; i++) {
          var nx = s.bx + s.vx * dt / steps;
          if (hitsWall(nx, s.by)) { if (Math.abs(s.vx) > 3) { sfx.play("clack"); s.bump = 0.08; } s.vx = -s.vx * 0.3; } else s.bx = nx;
          var ny = s.by + s.vy * dt / steps;
          if (hitsWall(s.bx, ny)) { if (Math.abs(s.vy) > 3) { sfx.play("clack"); s.bump = 0.08; } s.vy = -s.vy * 0.3; } else s.by = ny;
        }
        if (s.bump > 0) s.bump -= dt;
        var speed = Math.abs(s.vx) + Math.abs(s.vy);
        s.roll += dt * speed;
        if (s.roll > 1.2) { s.roll = 0; sfx.play("roll"); }   // the marble rumbling on the wood
        var c = cell(s.bx, s.by), fx = s.bx - Math.floor(s.bx), fy = s.by - Math.floor(s.by), centred = Math.abs(fx - 0.5) < 0.38 && Math.abs(fy - 0.5) < 0.38;
        if (c === HOLE && centred) {
          s.lives--; s.fall = 1; sfx.play("plop"); api.shake(0.25);
          s.pops.push({ text: "> /dev/null", c: C.RED, t: 1.2 });
        } else if (c === EXIT && centred) {
          var bonus = Math.max(100, Math.round(1500 - s.lt * 15)) + s.level * 100;
          s.score += bonus; s.win = 1.6; sfx.play("levelup");
          s.pops.push({ text: t("level") + " " + s.level + "  +" + bonus, c: C.GREEN, t: 1.6 });
        }
      },
      key: function (k) {
        var step = 0.34;
        if (k === "left" || k === "right") { s.tx = Math.max(-1, Math.min(1, s.tx + (k === "left" ? -step : step))); s.held.x = 0.45; sfx.play("lane"); }
        else if (k === "up" || k === "down") { s.ty = Math.max(-1, Math.min(1, s.ty + (k === "up" ? -step : step))); s.held.y = 0.45; sfx.play("lane"); }
        else if (k === "ok") { s.tx = s.ty = 0; s.held.x = s.held.y = 0; }   // level the board
      },
      draw: function (g) {
        var pad = api.pad, blink = Math.floor(s.clock * 4) % 2;
        g.row(1, 0, [["TILT", C.ACC], [t("level") + " " + s.level, C.FG], [t("score") + " " + pad(s.score, 6), C.BRIGHT],
          ["⏱ " + s.lt.toFixed(1) + "s", C.YEL]]);
        var hearts = ""; for (var i = 0; i < 3; i++) hearts += i < s.lives ? "● " : "○ ";
        g.put(73, 0, hearts, C.ACC);
        for (var y = 0; y < MH; y++) for (var x = 0; x < MW; x++) {
          var c = s.map[x + y * MW], gx = X0 + x * 2, gy = Y0 + y;
          if (c === WALL) g.put(gx, gy, "██", C.ACC2);
          else if (c === HOLE) g.put(gx, gy, "()", C.RED);
          else if (c === EXIT) g.put(gx, gy, blink ? "▒▒" : "██", C.GREEN);
          else if ((x + y) % 2 === 0) g.put(gx, gy, " ·", C.DIM);
        }
        if (!(s.fall > 0 && blink)) {
          var bc = s.fall > 0 ? C.RED : s.bump > 0 ? C.YEL : C.BRIGHT;
          g.put(X0 + Math.floor(s.bx * 2 - 0.5), Y0 + Math.floor(s.by), s.fall > 0 ? "∘" : "●", bc);
        }
        // the spirit level: how far the board leans
        var lvl = ""; for (var k = -5; k <= 5; k++) lvl += k === Math.round(s.tx * 5) ? "◆" : k === 0 ? "┼" : "─";
        g.put(1, 23, "[" + lvl + "]", C.FG);
        var lv2 = Math.round(s.ty * 2);
        g.put(15, 23, lv2 < 0 ? "▲" : lv2 > 0 ? "▼" : "◆", C.FG);
        g.put(18, 23, "◀ ▲ ▼ ▶ " + t("tilt_keys") + "  ·  OK " + t("tilt_level"), C.DIM);
        for (var p = 0; p < s.pops.length; p++) g.center(10 + p * 2, "  " + s.pops[p].text + "  ", C.INV);
      }
    };
  }

  A.register({
    id: "tilt", title: "TILT", color: C.ACC2,
    art: ["██████████████", "█ ● ░░█  () █", "█ ██ ░░█ ███ █", "██████████▒▒█"],
    desc: ["tilt_1", "tilt_2"], create: create, _makeMaze: makeMaze, _bfs: bfs, _size: [MW, MH]
  });
})(typeof window !== "undefined" ? window : this);
