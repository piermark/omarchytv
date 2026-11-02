/* FORK BOMB (OmarchyTV Arcade): you are a process. OK forks every process at once; the arrows move all of them
 * together (walls split them up). Lead enough of them to the exit before the OOM killer eats them; the more of you
 * there are, the faster it hunts, and forking past the RAM makes it furious. Holes are /dev/null.
 * Each arrow press is one step, so a slow TV remote is never a handicap. Mazes are generated per level. ES5. */
(function (root) {
  "use strict";
  var A = root.OmarchyArcade;
  if (!A) return;
  var C = A.C, MW = 30, MH = 19, X0 = 10, Y0 = 3;
  var WALL = 1, HOLE = 2, EXIT = 3;
  var DIRS = { up: [0, -1], down: [0, 1], left: [-1, 0], right: [1, 0] };

  function rng(seed) { var x = seed % 2147483647; if (x <= 0) x += 2147483646; return function () { x = x * 16807 % 2147483647; return (x - 1) / 2147483646; }; }
  function bfs(map, sx, sy, passHoles) {
    var dist = [], q = [sx + sy * MW];
    for (var i = 0; i < MW * MH; i++) dist.push(-1);
    dist[q[0]] = 0;
    for (var h = 0; h < q.length; h++) {
      var c = q[h], x = c % MW, y = Math.floor(c / MW);
      for (var d in DIRS) {
        var nx = x + DIRS[d][0], ny = y + DIRS[d][1], n = nx + ny * MW;
        if (nx < 0 || ny < 0 || nx >= MW || ny >= MH || dist[n] >= 0) continue;
        if (map[n] === WALL || (map[n] === HOLE && !passHoles)) continue;
        dist[n] = dist[c] + 1; q.push(n);
      }
    }
    return dist;
  }
  /** A level's maze: border, wall segments, /dev/null holes, start on the left, exit on the right, all connected. */
  function makeLevel(lv, seed) {
    for (var tries = 0; tries < 40; tries++) {
      var R = rng(seed + tries * 7919), map = [], i;
      for (i = 0; i < MW * MH; i++) { var x = i % MW, y = Math.floor(i / MW); map.push(x === 0 || y === 0 || x === MW - 1 || y === MH - 1 ? WALL : 0); }
      var segs = 6 + Math.min(lv, 9) * 2;
      for (var s = 0; s < segs; s++) {
        var vert = R() < 0.5, len = 3 + Math.floor(R() * 6), sx = 2 + Math.floor(R() * (MW - 4)), sy = 2 + Math.floor(R() * (MH - 4));
        for (var k = 0; k < len; k++) { var wx = sx + (vert ? 0 : k), wy = sy + (vert ? k : 0); if (wx > 0 && wy > 0 && wx < MW - 1 && wy < MH - 1) map[wx + wy * MW] = WALL; }
      }
      for (var hct = 0; hct < Math.min(10, lv + 1); hct++) map[(3 + Math.floor(R() * (MW - 6))) + (1 + Math.floor(R() * (MH - 2))) * MW] = HOLE;
      var st = { x: 2, y: Math.floor(MH / 2) }, ex = { x: MW - 3, y: 2 + Math.floor(R() * (MH - 4)) };
      for (var dy = -1; dy <= 1; dy++) for (var dx = -1; dx <= 1; dx++) { map[st.x + dx + (st.y + dy) * MW] = 0; map[ex.x + dx + (ex.y + dy) * MW] = 0; }
      map[ex.x + ex.y * MW] = EXIT;
      var dist = bfs(map, st.x, st.y, false);
      if (dist[ex.x + ex.y * MW] < 0) continue;
      var reach = 0; for (i = 0; i < dist.length; i++) if (dist[i] >= 0) reach++;
      if (reach < MW * MH * 0.45) continue;
      var far = -1, fi = 0;   // the OOM killer wakes up in the far corner from you
      for (i = 0; i < dist.length; i++) if (dist[i] > far && map[i] === 0 && Math.abs(i % MW - ex.x) + Math.abs(Math.floor(i / MW) - ex.y) > 4) { far = dist[i]; fi = i; }
      return { map: map, start: st, exit: ex, oom: { x: fi % MW, y: Math.floor(fi / MW) } };
    }
    return null;
  }

  function create(api) {
    var t = api.t, sfx = api.sfx;
    var s = { over: false, score: 0, lives: 3, level: 0, clock: 0, procs: [], pid: 100, saved: 0, target: 3, cap: 6,
      map: null, exit: null, ooms: [], oomT: 0, wake: 3, rage: 0, pops: [], end: 0, endKind: "", last: "right" };
    function cell(x, y) { return x < 0 || y < 0 || x >= MW || y >= MH ? WALL : s.map[x + y * MW]; }
    function procAt(x, y) { for (var i = 0; i < s.procs.length; i++) if (s.procs[i].x === x && s.procs[i].y === y) return s.procs[i]; return null; }
    function pop(text, c) { s.pops.push({ text: text, c: c, t: 1.4 }); if (s.pops.length > 3) s.pops.shift(); }
    function level(again) {
      if (!again) s.level++;
      var L = makeLevel(s.level, 1000 + s.level * 31) || makeLevel(1, 7);
      s.map = L.map; s.exit = L.exit;
      s.procs = [{ x: L.start.x, y: L.start.y, pid: s.pid++, born: 0 }];
      s.ooms = [{ x: L.oom.x, y: L.oom.y }];
      if (s.level >= 4) s.ooms.push({ x: L.oom.x, y: MH - 1 - L.oom.y > 0 && cell(L.oom.x, MH - 1 - L.oom.y) === 0 ? MH - 1 - L.oom.y : L.oom.y });
      s.saved = 0; s.target = Math.min(10, 2 + s.level); s.cap = s.target + 3;
      s.wake = 3; s.rage = 0; s.end = 0;
    }
    level();
    function kill(p, why) {
      s.procs.splice(s.procs.indexOf(p), 1);
      sfx.play("die"); pop("PID " + p.pid + " " + why, C.RED);
    }
    function settle() {   // after a move: exits, holes, killers
      for (var i = s.procs.length - 1; i >= 0; i--) {
        var p = s.procs[i], c = cell(p.x, p.y);
        if (c === EXIT) { s.procs.splice(i, 1); s.saved++; s.score += 100; sfx.play("save"); pop("PID " + p.pid + " → exit  +100", C.GREEN); }
        else if (c === HOLE) kill(p, "> /dev/null");
        else for (var k = 0; k < s.ooms.length; k++) if (s.wake <= 0 && s.ooms[k].x === p.x && s.ooms[k].y === p.y) { kill(p, t("fork_killed")); break; }
      }
      check();
    }
    function check() {
      if (s.end) return;
      if (s.saved >= s.target) {
        s.end = 1.6; s.endKind = "clear"; s.score += s.procs.length * 50 + s.level * 100; sfx.play("levelup");
      } else if (!s.procs.length) {
        s.lives--; s.end = 1.8; s.endKind = s.lives <= 0 ? "over" : "dead"; sfx.play("hurt"); api.shake(0.3);
      }
    }
    function move(dir) {
      var d = DIRS[dir];
      s.last = dir;
      var order = s.procs.slice().sort(function (a, b) { return (b.x * d[0] + b.y * d[1]) - (a.x * d[0] + a.y * d[1]); });
      var moved = false;
      for (var i = 0; i < order.length; i++) {   // the front ones move first, so a line of processes moves as one
        var p = order[i], nx = p.x + d[0], ny = p.y + d[1];
        if (cell(nx, ny) === WALL || procAt(nx, ny)) continue;
        p.x = nx; p.y = ny; moved = true;
      }
      if (moved) sfx.play("move");
      settle();
    }
    function fork() {
      var back = { up: "down", down: "up", left: "right", right: "left" }[s.last];
      var prefs = [back, s.last === "up" || s.last === "down" ? "left" : "up", s.last === "up" || s.last === "down" ? "right" : "down", s.last];
      var parents = s.procs.slice(), made = 0, full = false;
      for (var i = 0; i < parents.length; i++) {
        if (s.procs.length >= s.cap) { full = true; break; }
        for (var k = 0; k < prefs.length; k++) {
          var d = DIRS[prefs[k]], nx = parents[i].x + d[0], ny = parents[i].y + d[1], c = cell(nx, ny);
          if (c === WALL || c === HOLE || procAt(nx, ny)) continue;
          s.procs.push({ x: nx, y: ny, pid: s.pid++, born: 0.4 }); made++; break;
        }
      }
      if (made) sfx.play("fork");
      if (full) { s.rage = 3; sfx.play("oom"); pop(t("fork_oom"), C.RED); api.shake(0.2); }
      settle();
    }
    function hunt(o) {   // one step along the shortest path to the nearest process
      var dist = bfs(s.map, o.x, o.y, true), best = null, bd = 1e9;
      for (var i = 0; i < s.procs.length; i++) { var dd = dist[s.procs[i].x + s.procs[i].y * MW]; if (dd >= 0 && dd < bd) { bd = dd; best = s.procs[i]; } }
      if (!best) return;
      var back = bfs(s.map, best.x, best.y, true), cur = back[o.x + o.y * MW];
      for (var d in DIRS) {
        var nx = o.x + DIRS[d][0], ny = o.y + DIRS[d][1], n = nx + ny * MW;
        if (nx >= 0 && ny >= 0 && nx < MW && ny < MH && back[n] >= 0 && back[n] < cur) { o.x = nx; o.y = ny; break; }
      }
      var p = procAt(o.x, o.y);
      if (p) { kill(p, t("fork_killed")); check(); }
    }

    return {
      get over() { return s.over; }, get score() { return s.score; }, view: null,
      _state: function () { return s; },
      update: function (dt) {
        s.clock += dt;
        for (var i = s.pops.length - 1; i >= 0; i--) { s.pops[i].t -= dt; if (s.pops[i].t <= 0) s.pops.splice(i, 1); }
        for (var b = 0; b < s.procs.length; b++) if (s.procs[b].born > 0) s.procs[b].born -= dt;
        if (s.end) {
          s.end -= dt;
          if (s.end <= 0) { s.end = 0; if (s.endKind === "over") s.over = true; else level(s.endKind === "dead"); }
          return;
        }
        if (s.wake > 0) { s.wake -= dt; if (s.wake <= 0) sfx.play("oom"); return; }
        if (s.rage > 0) s.rage -= dt;
        s.oomT -= dt;
        if (s.oomT <= 0) {   // more processes = more memory pressure = a faster killer
          s.oomT = Math.max(0.18, 0.9 - s.level * 0.04 - s.procs.length * 0.04) * (s.rage > 0 ? 0.5 : 1);
          for (var k = 0; k < s.ooms.length; k++) hunt(s.ooms[k]);
        }
      },
      key: function (k) {
        if (s.end) return;
        if (DIRS[k]) move(k);
        else if (k === "ok") fork();
      },
      draw: function (g) {
        var pad = api.pad, blink = Math.floor(s.clock * 4) % 2;
        var ram = Math.round(s.procs.length * 100 / s.cap), bar = "";
        for (var i = 0; i < 10; i++) bar += i < Math.round(ram / 10) ? "█" : "░";
        g.row(1, 0, [["FORK BOMB", C.ACC], [t("level") + " " + s.level, C.FG], [t("fork_saved") + " " + s.saved + "/" + s.target, s.saved ? C.GREEN : C.FG],
          ["RAM [" + bar + "] " + ram + "%", ram >= 100 ? C.RED : ram > 60 ? C.YEL : C.GREEN], [pad(s.score, 6), C.BRIGHT]], 2);
        var hearts = ""; for (var h = 0; h < 3; h++) hearts += h < s.lives ? "■" : "□";
        g.put(76, 0, hearts, C.RED);
        g.put(1, 1, "$ :(){ :|:& };:", C.DIM);
        for (var y = 0; y < MH; y++) {
          for (var x = 0; x < MW; x++) {
            var c = s.map[x + y * MW], gx = X0 + x * 2, gy = Y0 + y;
            if (c === WALL) g.put(gx, gy, "▓▓", C.DIM);
            else if (c === HOLE) g.put(gx, gy, "░░", C.RED);
            else if (c === EXIT) g.put(gx, gy, blink ? "▒▒" : "██", C.GREEN);
            else if ((x + y) % 2 === 0) g.put(gx, gy, " ·", C.DIM);
          }
        }
        g.put(X0 + s.exit.x * 2 - 1, Y0 + s.exit.y - 1, "EXIT", C.GREEN);
        for (var p = 0; p < s.procs.length; p++) {
          var pr = s.procs[p];
          g.put(X0 + pr.x * 2, Y0 + pr.y, "@" + (pr.pid % 10), pr.born > 0 ? C.BRIGHT : C.ACC);
        }
        for (var o = 0; o < s.ooms.length; o++) {
          var om = s.ooms[o], ox = X0 + om.x * 2, oy = Y0 + om.y;
          if (s.wake > 0) { g.put(ox, oy, "zz", C.DIM); continue; }
          g.put(ox, oy, s.rage > 0 || blink ? "▓▓" : "▒▒", C.RED);
          if (oy - 1 >= Y0) g.put(ox - 1, oy - 1, "OOM", C.RED);
        }
        if (s.wake > 0) g.center(Y0 + MH + 0, "  " + t("fork_wake") + " " + Math.ceil(s.wake) + "  ", C.INV);
        var py = 22;
        for (var r = 0; r < s.pops.length; r++) g.put(X0, py + r, s.pops[r].text, s.pops[r].c);
        if (!s.pops.length) g.put(X0, 22, t("fork_2"), C.DIM);
        g.put(X0, 24, "OK fork  ·  ◀ ▲ ▼ ▶  " + t("fork_move"), C.DIM);
        if (s.end && s.endKind === "clear") g.bigCenter(9, "CLEAR!", C.GREEN);
        else if (s.end) { g.center(11, "  kill -9  ", C.INV); g.center(13, t("fork_lost"), C.RED); }
      }
    };
  }

  A.register({
    id: "forkbomb", title: "FORK BOMB", color: C.RED,
    art: [":(){ :|:& };:", "  @1 @2 @3   ", "    ▓▓▓▓     ", "   OOM ▒▒    "],
    desc: ["fork_1", "fork_2"], create: create, _makeLevel: makeLevel, _bfs: bfs, _size: [MW, MH]
  });
})(typeof window !== "undefined" ? window : this);
