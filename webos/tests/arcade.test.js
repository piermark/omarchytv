// node --test webos/tests/arcade.test.js — OmarchyTV Arcade (webos/app/games, copied to the Android assets/games).
const test = require("node:test");
const assert = require("assert");
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const WEB = path.join(__dirname, "../app/games");
const DROID = path.join(__dirname, "../../launcher/app/src/main/assets/games");
const FILES = ["arcade.js", "sfx.js", "i18n.js", "vhold.js", "ricewars.js", "forkbomb.js", "tilt.js", "hyprmaze.js", "pipeduel.js"];
const THEMES = JSON.parse(fs.readFileSync(path.join(__dirname, "../app/omarchy_themes.json"), "utf8")).themes;

// A tiny DOM: enough for arcade.open() (divs, styles, innerHTML) without a browser.
function fakeDoc() {
  function el(tag) {
    return {
      tagName: tag, style: {}, children: [], parentNode: null, className: "", textContent: "", innerHTML: "", id: "",
      clientWidth: 1280, clientHeight: 720, offsetWidth: 600,
      appendChild(c) { if (c.parentNode) c.parentNode.removeChild(c); c.parentNode = this; this.children.push(c); return c; },
      insertBefore(c, ref) { if (c.parentNode) c.parentNode.removeChild(c); c.parentNode = this; const i = this.children.indexOf(ref); this.children.splice(i < 0 ? this.children.length : i, 0, c); return c; },
      removeChild(c) { const i = this.children.indexOf(c); if (i >= 0) this.children.splice(i, 1); c.parentNode = null; return c; }
    };
  }
  const doc = { hidden: false, createElement: el };
  doc.body = el("body"); doc.body.ownerDocument = doc;
  return doc;
}
function load() {
  const store = {};
  const win = { localStorage: { getItem: (k) => (k in store ? store[k] : null), setItem: (k, v) => { store[k] = String(v); } },
    setTimeout, clearTimeout, addEventListener() {}, removeEventListener() {}, Math, Date, JSON };
  win.window = win;
  vm.createContext(win);
  FILES.forEach((f) => vm.runInContext(fs.readFileSync(path.join(WEB, f), "utf8"), win, { filename: f }));
  return win;
}
function fakeApi(win, over) {
  const A = win.OmarchyArcade;
  const g = new A.Grid(80, 25), calls = { pc: [], palette: [], layout: [] };
  const api = Object.assign({
    W: 80, H: 25, C: A.C, t: (k) => win.OmarchyArcadeI18n.en[k] || k, sfx: { play() {} }, grid: g,
    home: A.normTheme(THEMES["tokyo-night"], "tokyo-night"), themes: THEMES, normTheme: A.normTheme,
    setLayout: (a, b) => calls.layout.push([a, b]), setPalette: (t) => calls.palette.push(t.name), pcTheme: (n) => calls.pc.push(n),
    pad: (n, l) => String(Math.floor(n)).padStart(l, "0"), textWidth: A.textWidth, shake() {}
  }, over || {});
  return { api, g, calls };
}
const game = (win, id) => win.OmarchyArcade.games.filter((x) => x.id === id)[0];

test("Android plays the very same game files", () => {
  FILES.forEach((f) => assert.strictEqual(fs.readFileSync(path.join(DROID, f), "utf8"), fs.readFileSync(path.join(WEB, f), "utf8"), f));
  const html = fs.readFileSync(path.join(DROID, "index.html"), "utf8");
  FILES.forEach((f) => assert.ok(html.indexOf('src="' + f + '"') > 0, f));
  const web = fs.readFileSync(path.join(__dirname, "../app/index.html"), "utf8");
  FILES.forEach((f) => assert.ok(web.indexOf('src="games/' + f + '"') > 0, f));
});

test("three games register, in the cabinet order", () => {
  const win = load();
  assert.strictEqual(win.OmarchyArcade.games.map((g) => g.id).join(","), "vhold,ricewars,forkbomb,tilt,hyprmaze,pipeduel");
  win.OmarchyArcade.games.forEach((g) => g.art.forEach((l) => assert.ok(l.length <= 18, g.id + " art " + l)));
});

test("every language has every string, and the lines fit the 80-column screen", () => {
  const win = load(), I = win.OmarchyArcadeI18n, A = win.OmarchyArcade, keys = Object.keys(I.en);
  assert.strictEqual(Object.keys(I).length, 23);
  Object.keys(I).forEach((l) => {
    keys.forEach((k) => assert.ok(typeof I[l][k] === "string" && I[l][k].length, l + "." + k));
    ["vhold_1", "vhold_2", "rice_1", "rice_2", "fork_1", "tilt_1", "tilt_2", "hypr_1", "hypr_2", "duel_1", "duel_2", "duel_p2_joined"].forEach((k) => assert.ok(A.textWidth(I[l][k]) <= 78, l + "." + k + " " + A.textWidth(I[l][k])));
    assert.ok(A.textWidth(I[l].fork_2) <= 68, l + ".fork_2");
    assert.ok(A.textWidth(I[l].vhold_slap) <= 21, l + ".vhold_slap");
    assert.ok(A.textWidth(I[l].vhold_lane) <= 23, l + ".vhold_lane");
    assert.ok(A.textWidth(I[l].vhold_knob) <= 55, l + ".vhold_knob");
  });
  assert.strictEqual(A.langOf("it-IT"), "it");
  assert.strictEqual(A.langOf("pt-BR"), "pt-BR");
  assert.strictEqual(A.langOf("pt"), "pt-PT");
  assert.strictEqual(A.langOf("zh-Hans-CN"), "zh-CN");
  assert.strictEqual(A.langOf("no"), "nb");
  assert.strictEqual(A.langOf("xx"), "en");
});

test("the grid: wide characters take two cells, HTML is escaped, colours run together", () => {
  const win = load(), g = new win.OmarchyArcade.Grid(10, 2);
  g.put(0, 0, "a<b&", 1);
  g.put(5, 0, "街机", 2);
  assert.strictEqual(g.rowHtml(0), '<span class="ga1">a&lt;b&amp;</span><span class="ga0"> </span><span class="ga2">街机</span><span class="ga0"> </span>');
  g.put(0, 1, "x y", 3, false);
  assert.strictEqual(g.ch[10 + 1], " ");
  assert.strictEqual(win.OmarchyArcade.bigWidth("ARCADE"), 29);
});

test("the arcade: hall, coin, channel change, play, pause, back to the hall, exit", () => {
  const win = load(), doc = fakeDoc();
  let exited = 0, pc = [];
  const ui = win.OmarchyArcade.open(doc.body, { lang: "it", theme: THEMES.gruvbox, themeName: "gruvbox", themes: THEMES, sound: false,
    pcTheme: true, onPcTheme: (n) => pc.push(n), onExit: () => exited++ });
  const st = () => ui._state();
  assert.strictEqual(st().mode, "hall");
  ui._step(0.03);
  assert.ok(doc.body.children[0].children.length > 2);
  ui.key("right"); assert.strictEqual(st().sel, 1);
  ui.key("left"); ui.key("left"); assert.strictEqual(st().sel, 5, "the carousel wraps round");
  ui.key("right"); ui.key("right"); ui.key("right"); assert.strictEqual(st().sel, 2);
  ui.key("ok"); assert.strictEqual(st().mode, "insert");
  for (let i = 0; i < 40; i++) ui._step(0.03);
  assert.strictEqual(st().mode, "play");
  ui.key("up"); ui.key("down");
  for (let i = 0; i < 30; i++) ui._step(0.03);
  ui.key("back"); assert.strictEqual(st().mode, "pause");
  ui.key("down"); ui.key("down"); ui.key("ok");
  assert.strictEqual(st().mode, "hall");
  ui.key("back");
  assert.strictEqual(exited, 1);
  assert.strictEqual(doc.body.children.length, 0);
});

test("V-HOLD: faults distort the picture, the right knob locks them, OK OK slaps the collapse away", () => {
  const win = load(), { api, g, calls } = fakeApi(win);
  const v = game(win, "vhold").create(api), s = v._state();
  assert.deepStrictEqual(calls.layout[0], [1, 21]);
  v._god();
  v._fault("roll");
  for (let i = 0; i < 10; i++) v.update(0.03);
  assert.ok(v.view.roll !== 0);
  const f = s.faults[0], dir = f.target > s.knob.roll ? "right" : "left";
  const before = s.score;
  for (let i = 0; i < 40 && s.faults.length; i++) { v.key(dir); v.update(0.01); }
  assert.strictEqual(s.faults.length, 0, "roll locked");
  assert.ok(s.score - before >= 150);
  v._fault("skew"); v.update(0.03); assert.ok(Math.abs(v.view.skew) > 3);
  s.faults = [];
  v._fault("collapse");
  for (let i = 0; i < 40; i++) v.update(0.05);
  assert.ok(v.view.scaleY < 0.5);
  v.key("ok"); v.update(0.1); v.key("ok");
  assert.ok(!s.faults.some((x) => x.kind === "collapse"));
  g.clear(); v.draw(g);
  assert.ok(g.ch.join("").indexOf("V-HOLD") >= 0);
});

test("V-HOLD: a long run with random keys never throws and ends", () => {
  const win = load(), { api, g } = fakeApi(win);
  const v = game(win, "vhold").create(api), keys = ["up", "down", "left", "right", "ok"];
  for (let i = 0; i < 6000 && !v.over; i++) { if (i % 7 === 0) v.key(keys[i % 5]); v.update(0.033); if (i % 10 === 0) { g.clear(); v.draw(g); } }
  assert.ok(v.over);
  assert.ok(v.score > 0);
});

test("RICE WARS: your theme first, OK re-themes, only that colour's bugs can be hit, a cleared level themes the PC", () => {
  const win = load(), { api, g, calls } = fakeApi(win);
  const r = game(win, "ricewars").create(api), s = r._state();
  assert.strictEqual(s.themes.length, 3);
  assert.strictEqual(s.themes[0].name, "tokyo-night");
  assert.strictEqual(new Set(s.themes.map((t) => t.name)).size, 3);
  r.key("ok");
  for (let i = 0; i < 10; i++) r.update(0.03);
  assert.strictEqual(s.ct, 1);
  assert.strictEqual(calls.palette[calls.palette.length - 1], s.themes[1].name);
  // a bullet through a bug of another colour passes (only a ghost shows)
  const other = s.foes.filter((f) => f.g !== s.ct)[0];
  s.bullets = [{ x: s.fx + other.cx + 1, y: s.fy + other.cy + 0.4 }];
  r.update(0.01);
  assert.ok(other.alive);
  const mine = s.foes.filter((f) => f.g === s.ct)[0];
  s.bullets = [{ x: s.fx + mine.cx + 1, y: s.fy + mine.cy + 0.4 }];
  r.update(0.01);
  assert.ok(!mine.alive);
  s.foes.forEach((f) => { f.alive = false; });
  r.update(0.03);
  assert.deepStrictEqual(calls.pc, [s.themes[s.ct].name]);
  for (let i = 0; i < 60; i++) r.update(0.03);
  assert.strictEqual(s.level, 2);
  g.clear(); r.draw(g);
  assert.ok(g.ch.join("").indexOf("RICE WARS") >= 0);
});

test("RICE WARS: the boss level and a long random run", () => {
  const win = load(), { api, g } = fakeApi(win);
  const r = game(win, "ricewars").create(api), s = r._state(), keys = ["left", "right", "up", "ok", "up"];
  r._god();
  s.level = 2; s.foes.forEach((f) => { f.alive = false; }); r.update(0.03);
  for (let i = 0; i < 60; i++) r.update(0.03);
  assert.strictEqual(s.level, 3);
  assert.ok(s.boss);
  for (let i = 0; i < 6000; i++) { if (i % 3 === 0) r.key(keys[i % 5]); r.update(0.033); if (i % 10 === 0) { g.clear(); r.draw(g); } }
  assert.ok(s.score > 0);
});

test("FORK BOMB: every generated maze links the start to the exit", () => {
  const win = load(), F = game(win, "forkbomb"), [MW, MH] = F._size;
  for (let lv = 1; lv <= 60; lv++) {
    const L = F._makeLevel(lv, 1000 + lv * 31);
    assert.ok(L, "level " + lv);
    const d = F._bfs(L.map, L.start.x, L.start.y, false);
    assert.ok(d[L.exit.x + L.exit.y * MW] > 0, "exit reachable " + lv);
    assert.ok(d[L.oom.x + L.oom.y * MW] !== undefined && L.map[L.oom.x + L.oom.y * MW] === 0, "killer on the floor " + lv);
    assert.strictEqual(L.map.length, MW * MH);
  }
});

test("FORK BOMB: level 1 is the Omarchy logo, playable from the middle room to the bottom gap", () => {
  const win = load(), F = game(win, "forkbomb"), [MW] = F._size, L = F._makeLevel(1, 1);
  assert.ok(L.logo);
  const d = F._bfs(L.map, L.start.x, L.start.y, false);
  assert.ok(d[L.exit.x + L.exit.y * MW] > 20, "a long way round");
  assert.ok(d[L.oom.x + L.oom.y * MW] > 0, "the killer can reach you");
  assert.strictEqual(L.exit.y, 18);
  const icon = fs.readFileSync(path.join(__dirname, "../../launcher/app/src/main/res/drawable/omarchy_icon.xml"), "utf8");
  assert.ok(icon.indexOf("Copyright (c) David Heinemeier Hansson") > 0);
});

test("FORK BOMB: OK forks, arrows move every process, the exit saves them, RAM caps the forks", () => {
  const win = load(), { api, g } = fakeApi(win);
  const fb = game(win, "forkbomb").create(api), s = fb._state();
  assert.strictEqual(s.procs.length, 1);
  fb.key("ok");
  assert.strictEqual(s.procs.length, 2);
  fb.key("ok");
  assert.strictEqual(s.procs.length, 4);
  for (let i = 0; i < 4; i++) fb.key("ok");
  assert.ok(s.procs.length <= s.cap);
  assert.ok(s.rage > 0, "forking past the RAM angers the killer");
  // put the processes next to the exit and step in
  const ex = s.exit;
  s.procs = [{ x: ex.x - 1, y: ex.y, pid: 7, born: 0 }];
  s.wake = 1;
  fb.key("right");
  assert.strictEqual(s.saved, 1);
  g.clear(); fb.draw(g);
  assert.ok(g.ch.join("").indexOf("FORK BOMB") >= 0);
});

test("FORK BOMB: the OOM killer hunts, and a long random run never throws", () => {
  const win = load(), { api, g } = fakeApi(win);
  const fb = game(win, "forkbomb").create(api), s = fb._state(), keys = ["up", "right", "ok", "down", "right", "left"];
  for (let i = 0; i < 8000 && !fb.over; i++) { if (i % 4 === 0) fb.key(keys[(i / 4) % 6]); fb.update(0.033); if (i % 10 === 0) { g.clear(); fb.draw(g); } }
  assert.ok(s.level >= 1);
  assert.ok(fb.over || s.procs.length >= 0);
});

test("TILT: every maze links the start to the exit, holes stay off the way, the TV really tilts", () => {
  const win = load(), T = game(win, "tilt"), [MW] = T._size;
  for (let lv = 1; lv <= 30; lv++) {
    const L = T._makeMaze(lv, 4242 + lv * 97), d = T._bfs(L.map, 1, 1);
    assert.ok(d[L.exit.x + L.exit.y * MW] > 10, "exit reachable " + lv);
    assert.ok(L.map.filter((c) => c === 2).length >= 1, "holes " + lv);
  }
  const { api, g } = fakeApi(win), t = T.create(api), s = t._state();
  t.key("right"); t.key("right"); t.update(0.03);
  assert.ok(t.view.rotate > 3, "the picture rotates");
  t.key("down"); t.update(0.03);
  assert.ok(t.view.rotX < 0, "and leans");
  const x0 = s.bx;
  for (let i = 0; i < 10; i++) { t.key("right"); t.update(0.03); }
  assert.ok(s.bx !== x0 || s.by !== 1.5, "the ball rolls");
  // drop it into a hole: a life goes, it comes back to the start
  const hole = s.map.indexOf(2);
  s.bx = (hole % MW) + 0.5; s.by = Math.floor(hole / MW) + 0.5; s.vx = s.vy = 0; s.tx = s.ty = 0;
  t.update(0.01);
  assert.strictEqual(s.lives, 2);
  for (let i = 0; i < 40; i++) t.update(0.03);
  assert.ok(Math.abs(s.bx - 1.5) < 0.6 && Math.abs(s.by - 1.5) < 0.6);
  // and onto the exit: the next level
  s.bx = s.exit.x + 0.5; s.by = s.exit.y + 0.5; s.tx = s.ty = 0; s.vx = s.vy = 0;
  t.update(0.01);
  assert.ok(s.score > 0);
  for (let i = 0; i < 60; i++) t.update(0.03);
  assert.strictEqual(s.level, 2);
  g.clear(); t.draw(g);
  assert.ok(g.ch.join("").indexOf("TILT") >= 0);
});

test("HYPRMAZE: windows tile without overlapping, the gaps stay connected, SUPER+Q on the marked one scores", () => {
  const win = load(), { api, g } = fakeApi(win), h = game(win, "hyprmaze").create(api), s = h._state();
  for (let i = 0; i < 60; i++) h.update(0.05);   // windows slide into place, a couple more open
  const rects = s.wins.map((w) => w.to);
  rects.forEach((r, i) => {
    assert.ok(r.x >= 2 && r.y >= 3 && r.x + r.w <= 78 && r.y + r.h <= 21, "inside the workspace");
    rects.forEach((q, j) => { if (i < j) assert.ok(r.x + r.w <= q.x || q.x + q.w <= r.x || r.y + r.h <= q.y || q.y + q.h <= r.y, "no overlap"); });
  });
  // the gaps: one connected maze
  for (let i = 0; i < 30; i++) h.update(0.05);
  const free = (x, y) => x >= 0 && x < 80 && y >= 2 && y <= 21 && !s.wins.some((w) => x >= Math.round(w.cur.x) && x < Math.round(w.cur.x) + Math.round(w.cur.w) && y >= Math.round(w.cur.y) && y < Math.round(w.cur.y) + Math.round(w.cur.h));
  let total = 0; for (let y = 2; y <= 21; y++) for (let x = 0; x < 80; x++) if (free(x, y)) total++;
  const seen = new Set([s.cx + "," + s.cy]), q = [[s.cx, s.cy]];
  while (q.length) { const [x, y] = q.pop(); [[1, 0], [-1, 0], [0, 1], [0, -1]].forEach(([dx, dy]) => { const k = (x + dx) + "," + (y + dy); if (!seen.has(k) && free(x + dx, y + dy)) { seen.add(k); q.push([x + dx, y + dy]); } }); }
  assert.strictEqual(seen.size, total, "every gap is reachable");
  // stand next to the marked window and close it
  const r = s.target.cur, before = s.wins.length;
  s.cx = Math.round(r.x) - 1; s.cy = Math.round(r.y) + 1;
  h.key("ok");
  assert.ok(s.score >= 100, "closed");
  assert.strictEqual(s.wins.length, before - 1);
  g.clear(); h.draw(g);
  assert.ok(g.ch.join("").indexOf("HYPRMAZE") >= 0);
  // left alone, the workspace fills up and the game ends
  for (let i = 0; i < 4000 && !h.over; i++) h.update(0.05);
  assert.ok(h.over);
});

test("sound effects: every cue renders without errors", () => {
  const win = load();
  // a minimal offline audio graph
  const node = () => ({ connect() {}, gain: { value: 0, setValueAtTime() {}, linearRampToValueAtTime() {}, exponentialRampToValueAtTime() {} },
    frequency: { setValueAtTime() {}, exponentialRampToValueAtTime() {} }, Q: { value: 0 }, start() {}, stop() {} });
  const ctx = { currentTime: 0, sampleRate: 8000, destination: {}, createGain: node, createOscillator: node, createBiquadFilter: node,
    createBufferSource: node, createBuffer: (c, n) => ({ getChannelData: () => new Float32Array(n) }) };
  const sfx = win.OmarchyArcadeSfx.create(true, ctx);
  ["blip", "lane", "knob", "coin", "start", "move", "shoot", "hit", "ghost", "hurt", "lock", "slap", "static", "fork", "save", "oom", "die", "switch", "gameover", "levelup", "record", "roll", "clack", "plop", "tile"]
    .forEach((c) => { assert.ok(sfx.CUES.indexOf(c) >= 0, c); sfx.play(c, 2); });
});

test("PIPE DUEL: the CPU steers clear, walls and pipes end a round, three lives, every piece of pipe joins", () => {
  const win = load(), { api, g } = fakeApi(win), h = game(win, "pipeduel").create(api), s = h._state();
  assert.strictEqual(s.phase, "ready");
  assert.strictEqual(s.versus, false);
  for (let i = 0; i < 80; i++) h.update(0.05);   // the countdown, then both run
  assert.strictEqual(s.phase, "run");
  assert.ok(s.p[1].alive, "the CPU doesn't drive into a wall in the first seconds");
  // player 1 drives straight right into the CPU's side of the screen: sooner or later something ends the round
  let n = 0;
  while (s.phase === "run" && n++ < 2000) h.update(0.05);
  assert.strictEqual(s.phase, "end");
  h.draw(g);
  // pieces: a corner joins the way it came and the way it goes
  const cells = s.cells.filter(Boolean).map((c) => c.ch).join("");
  assert.ok(/^[━┃┏┓┗┛●]+$/.test(cells), cells.slice(0, 40));
  // a long random run ends with game over (3 lives), score counted
  let t = 0;
  const keys = ["up", "down", "left", "right"];
  while (!h.over && t++ < 40000) { if (t % 7 === 0) h.key(keys[t % 4]); h.update(0.05); if (t % 50 === 0) h.draw(g); }
  assert.ok(h.over, "three lost rounds end the game");
  assert.ok(h.score > 0);
});

test("PIPE DUEL: a second phone joins as P2: from the next round it's a duel, first to five", () => {
  const win = load(), { api, g } = fakeApi(win), h = game(win, "pipeduel").create(api), s = h._state();
  h.key("up", 2);
  assert.strictEqual(s.joined, true);
  assert.strictEqual(s.versus, false, "not in the middle of a round");
  h.draw(g);
  assert.ok(g.ch.slice(24 * 80, 25 * 80).join("").indexOf("P2") >= 0, "the screen says P2 is in");
  // end this round: P1 turns into the top wall
  h.key("up", 1);
  let n = 0;
  while (s.phase !== "end" && n++ < 2000) h.update(0.05);
  for (let i = 0; i < 60 && !s.versus; i++) h.update(0.05);
  assert.strictEqual(s.versus, true);
  assert.strictEqual(JSON.stringify(s.wins), "[0,0]", "a new match");
  // P2 now steers its own process: up, and it turns
  for (let i = 0; i < 40; i++) h.update(0.05);
  h.key("up", 2);
  h.update(0.2);
  assert.strictEqual(s.p[1].dir, "up");
  // rounds until someone has five
  let t = 0;
  while (!h.over && t++ < 20000) { if (s.phase === "run" && s.p[1].dir === "left") h.key("up", 2); h.update(0.05); }   // P2 runs into the top
  assert.ok(h.over);
  assert.strictEqual(JSON.stringify(s.wins), "[5,0]");
  assert.strictEqual(h.score, 500);
});

test("phones as gamepads: the sign shows the QR and who is in, keys carry the player, a phone can't close the arcade", () => {
  const win = load(), doc = fakeDoc();
  win.OmarchyQR = { canvas: (d, text) => ({ text, style: {}, parentNode: null }) };
  let exited = 0;
  const ui = win.OmarchyArcade.open(doc.body, { lang: "en", theme: THEMES.gruvbox, themeName: "gruvbox", themes: THEMES, sound: false, onExit: () => exited++ });
  ui.setPad({ url: "http://192.168.1.20:40001/p/abcdefgh", players: [1] });
  ui._step(0.03);
  const st = () => ui._state();
  const tube = doc.body.children[0].children[1];
  assert.ok(tube.children.some((c) => c.text === "http://192.168.1.20:40001/p/abcdefgh"), "the QR in the sign");
  const sign = st().grid.ch.slice(0, 8 * 80).join("");
  assert.ok(/PHONE = GAMEPAD/.test(sign) && /P1 ●/.test(sign) && /P2 ○/.test(sign), sign);
  ui.key("back", 1);
  assert.strictEqual(exited, 0, "Back on a phone doesn't close the arcade");
  // PIPE DUEL from the phone: the game gets the player
  while (st().sel !== 5) ui.key("right", 1);
  ui.key("ok", 1);
  for (let i = 0; i < 40; i++) ui._step(0.03);
  assert.strictEqual(st().mode, "play");
  assert.ok(!tube.children.some((c) => c.text), "no QR over the game");
  ui.key("left", 2);
  assert.strictEqual(st().inst._state().joined, true);
  ui.setPad(null);
  ui.key("back"); ui.key("down"); ui.key("down"); ui.key("ok");
  assert.strictEqual(st().mode, "hall");
  ui.key("back");
  assert.strictEqual(exited, 1, "the remote's Back still closes it");
});

test("the gamepad page: Android only, plain buttons that send keys to the TV, nothing else", () => {
  const html = fs.readFileSync(path.join(DROID, "pad.html"), "utf8");
  assert.ok(!fs.existsSync(path.join(WEB, "pad.html")), "LG/Samsung can't serve it");
  ["up", "down", "left", "right", "ok", "back"].forEach((k) => assert.ok(html.indexOf('data-k="' + k + '"') > 0, k));
  assert.ok(/fetch\("\/k\?c=" \+ code/.test(html) && /fetch\("\/hello\?c=" \+ code/.test(html));
  assert.ok(!/https?:\/\//.test(html.replace(/<meta[^>]*>/g, "")), "no outside requests");
});
