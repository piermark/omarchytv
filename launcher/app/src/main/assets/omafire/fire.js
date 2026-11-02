/* OmaFire fire.js by Wes Grimes, MIT (see LICENSE next to this file), from
 * https://github.com/wesleygrimes/omafire commit 7b9a627d5c28680f43fc5fad8c87c9b9b0506bfe ui/fire/fire.js.
 * Unchanged except: the QML ".pragma library" line is dropped and the file is wrapped so its functions
 * stay private, exposing them as window.OmaFire for OmarchyTV's animated wallpaper (omafire-wall.js). */
(function (root) {
// OmaFire core: simulation and a software framebuffer renderer ported from the
// approved browser reference (reference/animation.js in the handoff). Plain
// JavaScript with no QML or DOM dependencies, so the shell loads it as a
// library and node loads it in tests (tests/lib/load-fire.js).
//
// Fire colours, arithmetic, and random draw order match the reference.
// Native additions are marked "native:".

var W = 360, H = 240, BASE_X = 181, BASE_Y = 187;
var SEED = 5919;
var DT_CAP = 0.08;
var FUEL_DECAY = 0.00125, BOOST_DECAY = 0.45, HEAT_RATE = 0.75, FUEL_FLOOR = 0.015;
var LOG_FUEL = 0.25, FLIGHT_SECONDS = 0.7, TOAST_SECONDS = 2.2;
// native: bounds for rapid input; not part of the reference.
var SPARK_CAP = 160, SMOKE_CAP = 40;
// native: a wall-clock gap longer than this is applied analytically once.
var GAP_SECONDS = 1.0;
// native: absences longer than this restore straight to Resting.
var LONG_ABSENCE_SECONDS = 6 * 3600;

var MESSAGES = {
    addLog: "Fresh wood. Give it a moment.",
    addLogFull: "A good, roaring fire.",
    drop: "That’ll keep it going.",
    stoke: "The coals come alive.",
    poke: "A little shower of sparks.",
    stokeEmpty: "Add a log to get it going.",
    soundUnavailable: "Sound unavailable."
};
var HINT = "Drag wood onto the fire · Tap coals for sparks";

function hex(c) {
    return [parseInt(c.substr(1, 2), 16), parseInt(c.substr(3, 2), 16), parseInt(c.substr(5, 2), 16)];
}
var C = {
    star: hex("#a6c3de"), smoke: hex("#b6aeb3"),
    f0: hex("#fff0a7"), f1: hex("#ffd36e"), f2: hex("#ffae3c"), f3: hex("#f37828"), f4: hex("#d5471d"), f5: hex("#91341f"),
    e0: hex("#ffc05b"), e1: hex("#db6328"), e2: hex("#733021"),
    sparkHot: hex("#ffe39a"), sparkCool: hex("#d67c32"),
    drop: hex("#e6b568"), light: [255, 118, 24], empty: hex("#0b1522"),
    log0: hex("#211a13"), log1: hex("#74472b"), log2: hex("#a46e3b"), log3: hex("#3e2c21"),
    log4: hex("#503323"), log5: hex("#bc8a52"), log6: hex("#5e3c27")
};
// drawLog's rectangles, in order: [x, y, w, h, colour]
var LOG_RECTS = [
    [-19, -5, 36, 11, C.log0], [-19, -5, 34, 3, C.log1], [-18, -5, 33, 1, C.log2], [-17, 0, 30, 2, C.log3],
    [-15, 4, 28, 2, C.log4], [14, -4, 6, 9, C.log5], [16, -2, 2, 5, C.log6]
];

// ---------------------------------------------------------------- randomness

function rand(s) {
    s.seed = (s.seed * 1664525 + 1013904223) >>> 0;
    return s.seed / 4294967296;
}

function hash(x, y) {
    var n = Math.sin(x * 127.1 + y * 311.7) * 43758.5453;
    return n - Math.floor(n);
}

function noise(x, y) {
    var ix = Math.floor(x), iy = Math.floor(y), fx = x - ix, fy = y - iy;
    fx = fx * fx * (3 - 2 * fx);
    fy = fy * fy * (3 - 2 * fy);
    var a = hash(ix, iy), b = hash(ix + 1, iy), c = hash(ix, iy + 1), d = hash(ix + 1, iy + 1);
    return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
}

// ---------------------------------------------------------------- state

function createState() {
    var s = {
        seed: SEED, fuel: 0.68, heat: 0.68, boost: 0, elapsed: 0,
        stars: [], embers: [], sparks: [], smoke: [],
        flight: null, drag: null,
        toastText: "", toastUntil: 0, toastShown: false
    };
    var i;
    for (i = 0; i < 16; i++) {
        var sx = 50 + rand(s) * 260, sy = 5 + rand(s) * 45, sp = rand(s) * 9;
        s.stars.push({ x: sx, y: sy, p: sp });
    }
    for (i = 0; i < 75; i++) {
        var ex = BASE_X + (rand(s) - 0.5) * 52, ey = BASE_Y - 2 + rand(s) * 7, ep = rand(s) * 7, es = rand(s) > 0.8 ? 2 : 1;
        s.embers.push({ x: ex, y: ey, p: ep, s: es });
    }
    return s;
}

function toast(s, text) {
    s.toastText = text;
    s.toastUntil = s.elapsed + TOAST_SECONDS;
    s.toastShown = true;
}

function capList(list, cap) {
    if (list.length > cap) list.splice(0, list.length - cap);
}

function burst(s, n, power) {
    if (power === undefined) power = 1;
    for (var i = 0; i < n; i++) {
        var x = BASE_X + (rand(s) - 0.5) * 35, y = BASE_Y - 5 - rand(s) * 9;
        var vx = (rand(s) - 0.5) * 21 * power, vy = -(17 + rand(s) * 41) * power;
        var life = 1 + rand(s) * 2, size = rand(s) > 0.8 ? 2 : 1;
        s.sparks.push({ x: x, y: y, vx: vx, vy: vy, life: life, age: 0, s: size });
    }
    capList(s.sparks, SPARK_CAP);
}

function addLog(s) {
    s.fuel = Math.min(1, s.fuel + LOG_FUEL);
    s.boost = Math.max(s.boost, 0.35);
    s.flight = { age: 0, fromX: 36, fromY: 177 };
    burst(s, 24);
    var text = s.fuel > 0.95 ? MESSAGES.addLogFull : MESSAGES.addLog;
    toast(s, text);
    return text;
}

function stoke(s) {
    if (s.fuel < FUEL_FLOOR) {
        toast(s, MESSAGES.stokeEmpty);
        return MESSAGES.stokeEmpty;
    }
    s.boost = 1.1;
    s.heat = Math.min(1, s.heat + 0.15);
    burst(s, 48, 1.5);
    toast(s, MESSAGES.stoke);
    return MESSAGES.stoke;
}

function poke(s) {
    burst(s, 32, 1.2);
    s.boost = Math.max(s.boost, 0.22);
    toast(s, MESSAGES.poke);
    return MESSAGES.poke;
}

// ---------------------------------------------------------------- pointer

function inWoodpile(x, y) { return x < 88 && y > 142; }
function onCoals(x, y) { return Math.hypot(x - BASE_X, y - (BASE_Y - 6)) < 59; }
function inHearth(x, y) { return Math.hypot(x - BASE_X, y - (BASE_Y - 6)) < 65; }

// Returns "drag", "poke", or "" like the reference pointerdown handler.
function pointerDown(s, x, y) {
    if (inWoodpile(x, y)) { s.drag = { x: x, y: y }; return "drag"; }
    if (onCoals(x, y)) { poke(s); return "poke"; }
    return "";
}

function pointerMove(s, x, y) {
    if (!s.drag) return false;
    s.drag.x = x; s.drag.y = y;
    return true;
}

// Returns true when the log landed in the hearth.
function pointerUp(s, x, y) {
    if (!s.drag) return false;
    var landed = inHearth(x, y);
    if (landed) {
        s.fuel = Math.min(1, s.fuel + LOG_FUEL);
        s.boost = 0.45;
        burst(s, 40);
        toast(s, MESSAGES.drop);
        s.flight = { age: 0.48, fromX: x, fromY: y };
    }
    s.drag = null;
    return landed;
}

function pointerCancel(s) { s.drag = null; }

function cursorFor(s, x, y) {
    if (s.drag) return "grabbing";
    return inWoodpile(x, y) ? "grab" : "default";
}

// ---------------------------------------------------------------- time

// The reference update(dt), minus the accelerated preview mode.
function update(s, dt) {
    s.elapsed += dt;
    s.boost = Math.max(0, s.boost - dt * BOOST_DECAY);
    s.fuel = Math.max(0, s.fuel - dt * FUEL_DECAY);
    s.heat += (Math.max(0, s.fuel - FUEL_FLOOR) - s.heat) * Math.min(1, dt * HEAT_RATE);
    if (s.heat > 0.03 && rand(s) < dt * 14 * s.heat) burst(s, 1);
    if (s.heat > 0.03 && rand(s) < dt * 4) {
        var x = BASE_X + (rand(s) - 0.5) * 14, life = 4 + rand(s) * 2, p = rand(s) * 5;
        s.smoke.push({ x: x, y: BASE_Y - 18, age: 0, life: life, p: p });
        capList(s.smoke, SMOKE_CAP);
    }
    // Expired particles are dropped in place (same order, no new arrays).
    var i, p2, list = s.sparks, n = 0;
    for (i = 0; i < list.length; i++) {
        p2 = list[i];
        p2.age += dt;
        p2.x += p2.vx * dt + Math.sin(p2.age * 4) * dt * 5;
        p2.y += p2.vy * dt;
        p2.vy += dt * 3;
        if (p2.age < p2.life) list[n++] = p2;
    }
    list.length = n;
    list = s.smoke; n = 0;
    for (i = 0; i < list.length; i++) {
        p2 = list[i];
        p2.age += dt;
        p2.y -= dt * 14;
        p2.x += Math.sin(p2.age * 2 + p2.p) * dt * 7;
        if (p2.age < p2.life) list[n++] = p2;
    }
    list.length = n;
    if (s.flight) { s.flight.age += dt; if (s.flight.age > FLIGHT_SECONDS) s.flight = null; }
    if (s.elapsed > s.toastUntil) s.toastShown = false;
}

// native: closed-form fuel/heat/boost over `seconds` with no particles. The
// heat step heat += (target - heat) * dt * 0.75 is Euler for
// h' = 0.75 (target - h); with target falling linearly this solves exactly.
function advanceAnalytic(s, seconds, keepClock) {
    if (!(seconds > 0)) return;
    var k = FUEL_DECAY, c = HEAT_RATE;
    var f0 = s.fuel, h = s.heat;
    var t1 = f0 > FUEL_FLOOR ? Math.min(seconds, (f0 - FUEL_FLOOR) / k) : 0;
    if (t1 > 0) {
        var g0 = f0 - FUEL_FLOOR, g1 = g0 - k * t1;
        h = g1 + k / c + (h - g0 - k / c) * Math.exp(-c * t1);
    }
    var t2 = seconds - t1;
    if (t2 > 0) h = h * Math.exp(-c * t2);
    s.fuel = Math.max(0, f0 - k * seconds);
    s.heat = Math.min(1, Math.max(0, h));
    s.boost = Math.max(0, s.boost - seconds * BOOST_DECAY);
    if (!keepClock) s.elapsed += seconds;
    // Particles do not catch up; anything older than its life is gone.
    var i, keep = [];
    for (i = 0; i < s.sparks.length; i++) if (s.sparks[i].age + seconds < s.sparks[i].life) keep.push(s.sparks[i]);
    if (seconds > 0.25) keep = [];
    s.sparks = keep;
    if (seconds > 0.25) s.smoke = [];
    if (s.flight && s.flight.age + seconds > FLIGHT_SECONDS) s.flight = null;
    if (s.elapsed > s.toastUntil) s.toastShown = false;
}

// native: advance by a measured wall-clock delta. Particles step with the
// reference cap of 0.08 s; any remainder (a slow frame, a closed popup, a
// suspend) is applied analytically so fuel follows the wall clock.
function tick(s, wallSeconds, particles) {
    if (!(wallSeconds > 0)) return;
    if (!particles || wallSeconds > GAP_SECONDS) {
        advanceAnalytic(s, wallSeconds);
        return;
    }
    var dt = Math.min(DT_CAP, wallSeconds);
    update(s, dt);
    if (wallSeconds > dt) advanceAnalytic(s, wallSeconds - dt);
}

// ---------------------------------------------------------------- status

var STATUS = [
    { key: "stoking", label: "Stoking the fire" },
    { key: "roaring", label: "Roaring warmly" },
    { key: "steady", label: "Burning steadily" },
    { key: "settling", label: "Settling into coals" },
    { key: "embers", label: "Glowing embers" },
    { key: "resting", label: "Resting" }
];

function statusIndex(s) {
    return s.boost > 0.5 ? 0 : s.heat > 0.78 ? 1 : s.heat > 0.3 ? 2 : s.heat > 0.08 ? 3 : s.fuel > 0.01 ? 4 : 5;
}

function status(s) {
    var st = STATUS[statusIndex(s)];
    var filled = st.key !== "resting";
    return { key: st.key, label: st.label, filled: filled, text: (filled ? "● " : "○ ") + st.label };
}

// ---------------------------------------------------------------- persistence

function serialize(s, nowMs) {
    return JSON.stringify({ version: 1, fuel: s.fuel, heat: s.heat, savedAt: Math.round(nowMs) });
}

function unit(v) { return typeof v === "number" && isFinite(v) && v >= 0 && v <= 1; }

// Returns { state, restored, reason, away }. Anything unreadable starts the
// default fire; a clock that ran backwards counts as no time away.
function restore(text, nowMs) {
    var s = createState();
    var o = null;
    try { o = JSON.parse(text); } catch (e) { return { state: s, restored: false, reason: "corrupt", away: 0 }; }
    if (!o || typeof o !== "object" || !unit(o.fuel) || !unit(o.heat)
        || typeof o.savedAt !== "number" || !isFinite(o.savedAt))
        return { state: s, restored: false, reason: "invalid", away: 0 };
    var away = Math.max(0, (nowMs - o.savedAt) / 1000);
    s.fuel = o.fuel;
    s.heat = o.heat;
    if (away > LONG_ABSENCE_SECONDS) {
        s.fuel = 0; s.heat = 0;
    } else {
        advanceAnalytic(s, away, true);
    }
    return { state: s, restored: true, reason: "", away: away };
}

// ---------------------------------------------------------------- framebuffer
//
// RGBA, 360 x 240, opaque. Blending follows canvas 2D: source-over with the
// fill alpha times globalAlpha, and the separable 'screen' blend for the light
// pool.

function createFrame() {
    var fb = new Uint8ClampedArray(W * H * 4);
    for (var a = 3; a < fb.length; a += 4) fb[a] = 255;
    return fb;
}

// Nearest sampling of an RGBA source onto 360 x 240, like drawImage with
// imageSmoothingEnabled = false. Destination pixel centres map into the
// source; a centre landing exactly on a source pixel boundary takes the lower
// pixel, as Chromium does (checked against reference/approved-scene.png: the
// 720 x 480 art is sampled at even coordinates).
function nearestIndex(i, srcSize, dstSize) {
    return Math.max(0, Math.min(srcSize - 1, Math.ceil((i + 0.5) * srcSize / dstSize - 1)));
}

function prepareBackground(src, sw, sh) {
    var out = new Uint8ClampedArray(W * H * 4);
    for (var y = 0; y < H; y++) {
        var sy = nearestIndex(y, sh, H);
        for (var x = 0; x < W; x++) {
            var sx = nearestIndex(x, sw, W);
            var si = (sy * sw + sx) * 4, di = (y * W + x) * 4;
            out[di] = src[si]; out[di + 1] = src[si + 1]; out[di + 2] = src[si + 2]; out[di + 3] = 255;
        }
    }
    return out;
}

// native: the prepared art, kept by the library (one instance per engine) so
// a popup rebuilt on plugin reload doesn't decode it again.
var _background = null;
function cachedBackground() { return _background; }
function cacheBackground(bg) { _background = bg; return bg; }

// native: moonlight follows the reflection painted into the lake. Cache a
// water-only mask from the art so ripples disappear behind the island and
// foreground pines. The two stretches are separated by the wooded island.
// None of this uses the simulation's random stream.
var _lakeOf = null, _lakeRows = [], _lakeMask = null;
var MOON_SILVER = [230, 234, 218], MOON_BLUE = [151, 194, 224];
function prepareLake(bg) {
    _lakeOf = bg;
    _lakeRows = [];
    _lakeMask = new Uint8Array(W * H);
    for (var y = 90; y < 143; y++) {
        if (y >= 100 && y < 109) continue;
        var row = { y: y, pixels: [], ripples: [] };
        var spread = 16 + (y - 90) * 0.27;
        for (var x = 218; x < 309; x++) {
            var i = (y * W + x) * 4, r = bg[i], g = bg[i + 1], b = bg[i + 2];
            var water = b >= 56 && b > r * 1.55 && b > g * 1.18 && g >= 25;
            var reflection = r >= 65 && g >= 65 && b >= 50 && g > r * 0.85 && r > b * 0.95;
            var path = Math.max(0, 1 - Math.abs(x - 275) / spread);
            if ((!water && !reflection) || path === 0) continue;
            _lakeMask[y * W + x] = 1;
            row.pixels.push({ i: i, path: path, reflection: Math.min(1, Math.max(0, (r - 45) / 150)) });
        }
        if (!row.pixels.length) continue;
        // Short, staggered horizontal glints keep the original pixel style.
        for (var k = 0; k < 3; k++) {
            var h = hash(y, k + 73);
            row.ripples.push({
                x: 275 + (k - 1) * spread * 0.52 + (h - 0.5) * 7,
                w: 1 + Math.floor(hash(y, k + 91) * 5),
                phase: h * Math.PI * 2, speed: 1.2 + hash(y, k + 107) * 1.1,
                color: k === 1 ? MOON_SILVER : MOON_BLUE
            });
        }
        _lakeRows.push(row);
    }
}

function drawMoonlight(fb, bg, t) {
    if (!bg) return;
    if (_lakeOf !== bg) prepareLake(bg);
    for (var n = 0; n < _lakeRows.length; n++) {
        var row = _lakeRows[n], y = row.y;
        // Slow waves brighten and dim the existing reflection; a faint blue
        // sheen joins it to the darker water on either side.
        var wave = 0.9 + 0.13 * Math.sin(t * 1.6 - y * 0.78) + 0.07 * Math.sin(t * 2.7 + y * 0.41);
        var crest = Math.max(0, Math.sin(t * 1.1 - y * 0.71));
        var sheen = 0.015 + 0.035 * crest * crest * crest * crest;
        for (var p = 0; p < row.pixels.length; p++) {
            var pixel = row.pixels[p], i = pixel.i;
            var light = 1 + (wave - 1) * pixel.reflection;
            fb[i] = bg[i] * light;
            fb[i + 1] = bg[i + 1] * light;
            fb[i + 2] = bg[i + 2] * light;
            blend(fb, i, MOON_BLUE, sheen * pixel.path);
        }
        for (var k = 0; k < row.ripples.length; k++) {
            var ripple = row.ripples[k];
            var pulse = 0.5 + 0.5 * Math.sin(t * ripple.speed + ripple.phase);
            var alpha = 0.65 * Math.pow(pulse, 6);
            if (alpha < 0.015) continue;
            var x0 = Math.round(ripple.x + 2 * Math.sin(t * 0.7 + ripple.phase));
            for (var dx = 0; dx < ripple.w; dx++) {
                var x = x0 + dx;
                if (!_lakeMask[y * W + x]) continue;
                var edge = ripple.w > 2 && (dx === 0 || dx === ripple.w - 1) ? 0.5 : 1;
                blend(fb, (y * W + x) * 4, ripple.color, alpha * edge);
            }
        }
        markDirty(218, y, 309, y + 1);
    }
}

// ToUint8Clamp for 0 <= x <= 255: round half to even. x - (x | 0) is exact.
function roundByte(x) {
    var f = x | 0, d = x - f;
    return d > 0.5 || (d === 0.5 && (f & 1)) ? f + 1 : f;
}

function blend(fb, i, c, a) {
    if (a >= 1) { fb[i] = c[0]; fb[i + 1] = c[1]; fb[i + 2] = c[2]; return; }
    var ia = 1 - a;
    fb[i] = c[0] * a + fb[i] * ia;
    fb[i + 1] = c[1] * a + fb[i + 1] * ia;
    fb[i + 2] = c[2] * a + fb[i + 2] * ia;
}

function fillRect(fb, x, y, w, h, c, a) {
    if (a <= 0) return;
    var x0 = Math.max(0, x), y0 = Math.max(0, y), x1 = Math.min(W, x + w), y1 = Math.min(H, y + h);
    for (var yy = y0; yy < y1; yy++) for (var xx = x0; xx < x1; xx++) blend(fb, (yy * W + xx) * 4, c, a);
}

// Fractional axis-aligned rectangle with per-pixel area coverage.
function fillRectAA(fb, x0, y0, x1, y1, c, a) {
    var px0 = Math.max(0, Math.floor(x0)), py0 = Math.max(0, Math.floor(y0));
    var px1 = Math.min(W, Math.ceil(x1)), py1 = Math.min(H, Math.ceil(y1));
    for (var y = py0; y < py1; y++) {
        var cy = Math.min(y + 1, y1) - Math.max(y, y0);
        if (cy <= 0) continue;
        for (var x = px0; x < px1; x++) {
            var cx = Math.min(x + 1, x1) - Math.max(x, x0);
            if (cx > 0) blend(fb, (y * W + x) * 4, c, a * cx * cy);
        }
    }
}

// Rectangle under a rotation about (tx, ty), with 4 x 4 supersampled coverage
// standing in for the canvas's antialiased edges.
function fillRectRotated(fb, tx, ty, cos, sin, rx, ry, rw, rh, c, a) {
    var xs = [rx, rx + rw, rx + rw, rx], ys = [ry, ry, ry + rh, ry + rh];
    var minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9, i;
    for (i = 0; i < 4; i++) {
        var px = tx + xs[i] * cos - ys[i] * sin, py = ty + xs[i] * sin + ys[i] * cos;
        if (px < minX) minX = px; if (px > maxX) maxX = px;
        if (py < minY) minY = py; if (py > maxY) maxY = py;
    }
    var x0 = Math.max(0, Math.floor(minX)), x1 = Math.min(W, Math.ceil(maxX));
    var y0 = Math.max(0, Math.floor(minY)), y1 = Math.min(H, Math.ceil(maxY));
    for (var y = y0; y < y1; y++) for (var x = x0; x < x1; x++) {
        var hits = 0;
        for (var sy = 0; sy < 4; sy++) for (var sx = 0; sx < 4; sx++) {
            var dx = x + (sx + 0.5) / 4 - tx, dy = y + (sy + 0.5) / 4 - ty;
            var lx = dx * cos + dy * sin, ly = -dx * sin + dy * cos;
            if (lx >= rx && lx < rx + rw && ly >= ry && ly < ry + rh) hits++;
        }
        if (hits) blend(fb, (y * W + x) * 4, c, a * hits / 16);
    }
}

function drawLog(fb, x, y, angle) {
    var tx = Math.round(x), ty = Math.round(y);
    var cos = Math.cos(angle || 0), sin = Math.sin(angle || 0);
    for (var i = 0; i < LOG_RECTS.length; i++) {
        var r = LOG_RECTS[i];
        if (!angle) fillRect(fb, tx + r[0], ty + r[1], r[2], r[3], r[4], 1);
        else fillRectRotated(fb, tx, ty, cos, sin, r[0], r[1], r[2], r[3], r[4], 1);
    }
}

// strokeRect with lineWidth 1, setLineDash([3, 3]), butt caps: the dash
// pattern runs continuously around the perimeter from the top-left corner.
function strokeDashedRect(fb, x, y, w, h, c, a) {
    var edges = [[x, y, 1, 0, w], [x + w, y, 0, 1, h], [x + w, y + h, -1, 0, w], [x, y + h, 0, -1, h]];
    var offset = 0;
    for (var e = 0; e < 4; e++) {
        var ed = edges[e], len = ed[4];
        for (var d = 0; d < len; d++) {
            var phase = (offset + d) % 6;
            if (phase >= 3) continue;
            var sx = ed[0] + ed[2] * d, sy = ed[1] + ed[3] * d;
            var ex = sx + ed[2], ey = sy + ed[3];
            var ax0 = Math.min(sx, ex), ax1 = Math.max(sx, ex), ay0 = Math.min(sy, ey), ay1 = Math.max(sy, ey);
            if (ed[3] === 0) fillRectAA(fb, ax0, ay0 - 0.5, ax1, ay0 + 0.5, c, a);
            else fillRectAA(fb, ax0 - 0.5, ay0, ax0 + 0.5, ay1, c, a);
        }
        offset += len;
    }
}

function drawFire(fb, s, t) {
    var heat = s.heat, boost = s.boost;
    var alive = Math.min(1, heat * 1.6);
    var flameH = (24 + heat * 46 + boost * 20) * alive, flameW = (17 + heat * 12 + boost * 5) * alive;
    var flick = 0.86 + Math.sin(t * 9) * 0.07 + Math.sin(t * 16.2) * 0.04;
    var x, y, i;
    // Warm light pool, 'screen' composite. Each 3 x 3 block has one alpha;
    // a * L[c] is the leftmost product of the reference expression
    // fb + a * L[c] * (255 - fb) / 255, so hoisting it keeps every value.
    var L0 = C.light[0], L1 = C.light[1], L2 = C.light[2], u = view32(fb);
    for (y = BASE_Y - 22; y < BASE_Y + 34; y += 3) {
        // Dirty span of this band of blocks (the pool is an ellipse).
        var lx0 = W, lx1 = -1;
        var dy = (y - BASE_Y - 1) / 30, dy2 = dy * dy, yEnd = Math.min(y + 3, H);
        for (x = BASE_X - 73; x < BASE_X + 74; x += 3) {
            var dx = (x - BASE_X) / 76;
            var d = dx * dx + dy2;
            var m = Math.max(0, 1 - d);
            var a = m * m * 0.19 * flick * alive;
            if (!(a > 0.004)) continue;
            var a0 = a * L0, a1 = a * L1, a2 = a * L2, xEnd = Math.min(x + 3, W), e4;
            // A pixel is read and written as one word; each channel's result
            // is rounded the way a Uint8ClampedArray store rounds (half to
            // even; the value is already within 0..255) and reused while its
            // input repeats (pixel art has runs).
            var pr = -1, pg = -1, pb = -1, or = 0, og = 0, ob = 0, q;
            for (var yy = y; yy < yEnd; yy++) {
                for (var p = yy * W + x, pe = yy * W + xEnd; p < pe; p++) {
                    var v = u[p], r = v & 255, g = (v >>> 8) & 255, b = (v >>> 16) & 255;
                    // roundByte(), inlined: calls are dear in the QML engine.
                    if (r !== pr) { pr = r; q = r + a0 * (255 - r) / 255; or = q | 0; q -= or; if (q > 0.5 || (q === 0.5 && (or & 1))) or++; }
                    if (g !== pg) { pg = g; q = g + a1 * (255 - g) / 255; og = q | 0; q -= og; if (q > 0.5 || (q === 0.5 && (og & 1))) og++; }
                    if (b !== pb) { pb = b; q = b + a2 * (255 - b) / 255; ob = q | 0; q -= ob; if (q > 0.5 || (q === 0.5 && (ob & 1))) ob++; }
                    u[p] = (v & 0xff000000) | (ob << 16) | (og << 8) | or;
                }
            }
            if (x < lx0) lx0 = x;
            lx1 = xEnd;
        }
        if (lx1 > 0) markDirty(lx0, y, lx1, yEnd);
    }
    // Embers.
    var hotness = Math.min(1, heat * 3);
    var ex0 = W, ey0 = H, ex1 = -1, ey1 = -1;
    for (i = 0; i < s.embers.length; i++) {
        var e = s.embers[i];
        var glow = (0.4 + 0.6 * Math.max(0, Math.sin(t * 1.4 + e.p))) * hotness;
        var col = glow > 0.6 ? C.e0 : glow > 0.3 ? C.e1 : C.e2;
        var fx = Math.floor(e.x), fy = Math.floor(e.y);
        fillRect(fb, fx, fy, e.s, e.s, col, Math.min(1, 0.3 + glow));
        if (fx < ex0) ex0 = fx; if (fx + e.s > ex1) ex1 = fx + e.s;
        if (fy < ey0) ey0 = fy; if (fy + e.s > ey1) ey1 = fy + e.s;
    }
    if (ex1 > 0) markDirty(ex0, ey0, ex1, ey1);
    if (flameH < 3) return;
    drawFlame(fb, t, flameH, flameW);
    if (s.drag) {
        strokeDashedRect(fb, BASE_X - 39, BASE_Y - 38, 78, 54, C.drop, 0.75);
        markDirty(BASE_X - 40, BASE_Y - 39, BASE_X + 41, BASE_Y + 18);
    }
}

// The reference flame loop. Same arithmetic, reorganized for speed:
// - noise() is inlined; its lattice coordinates depend only on the column (x)
//   or only on the row (y), so they and the hash() rows are computed once per
//   column or row instead of four times per cell. Values are bit-identical.
// - Cells that cannot pass the edge test are skipped before evaluating noise:
//   n < 1, so edge < taper - |nx| + 0.425.
// Flame colours hot to cool, as [r, g, b] and as opaque little-endian RGBA
// words for a Uint32Array view of the frame.
var FLAME_RGB = [C.f0, C.f1, C.f2, C.f3, C.f4, C.f5];
var FLAME_U32 = FLAME_RGB.map(function (c) { return (0xff000000 | (c[2] << 16) | (c[1] << 8) | c[0]) >>> 0; });

function hashRow(row, min, n, iy) {
    for (var j = 0; j < n; j++) row[j] = hash(min + j, iy);
    return row;
}

// Scratch reused every frame (no garbage at 20 fps). Plain arrays on
// purpose: in QV4 an index read back out of a Float64Array is a double, and
// indexing with doubles fell off the fast path (1.4 s a frame).
var _ix1 = [], _fx1 = [], _ix2 = [], _fx2 = [];
var _h1 = [[], []], _h2 = [[], []];

function drawFlame(fb, t, flameH, flameW) {
    var x, y, k;
    var yStart = Math.floor(BASE_Y - flameH);
    var xStart = Math.floor(BASE_X - flameW - 11), xEnd = BASE_X + flameW + 11;
    var cols = Math.floor(xEnd) - xStart + 1;
    var t03 = t * 0.3, t33 = t * 3.3, t05 = t * 0.5, t58 = t * 5.8, t31 = t * 3.1, t2 = t * 2;
    var ix1 = _ix1, fx1 = _fx1, ix2 = _ix2, fx2 = _fx2;
    var min1 = 1e9, max1 = -1e9, min2 = 1e9, max2 = -1e9;
    for (k = 0; k < cols; k++) {
        x = xStart + k;
        var X1 = x * 0.19 + t03, X2 = x * 0.43 - t05;
        var i1 = Math.floor(X1), i2 = Math.floor(X2), f1 = X1 - i1, f2 = X2 - i2;
        ix1[k] = i1; fx1[k] = f1 * f1 * (3 - 2 * f1);
        ix2[k] = i2; fx2[k] = f2 * f2 * (3 - 2 * f2);
        if (i1 < min1) min1 = i1; if (i1 > max1) max1 = i1;
        if (i2 < min2) min2 = i2; if (i2 > max2) max2 = i2;
    }
    var n1 = max1 - min1 + 2, n2 = max2 - min2 + 2;
    // hash() rows along each lattice row. Neighbouring flame rows mostly
    // share a lattice row (or the next one), so rows are computed once.
    var hA1 = _h1[0], hB1 = _h1[1], hA2 = _h2[0], hB2 = _h2[1], row1 = NaN, row2 = NaN, swap;
    var top = H, bottom = -1, left = W, right = -1;
    var u = view32(fb), F = FLAME_U32, FC = FLAME_RGB;
    for (y = yStart; y < BASE_Y + 1; y++) {
        var v = (BASE_Y - y) / flameH;
        if (v > 1 || y < 0 || y >= H) continue;
        var sway = Math.sin(v * 7 - t31) * v * 7 + Math.sin(v * 12 + t2) * v * 3;
        var taper = Math.pow(Math.max(0, 1 - v), 0.8);
        var Y1 = y * 0.12 + t33, Y2 = y * 0.31 + t58;
        var iy1 = Math.floor(Y1), iy2 = Math.floor(Y2), g1 = Y1 - iy1, g2 = Y2 - iy2;
        var fy1 = g1 * g1 * (3 - 2 * g1), fy2 = g2 * g2 * (3 - 2 * g2);
        var rowReady = false;
        var shade = (1 - v) * 0.28, rowBase = y * W, rowLeft = -1, rowRight = -1;
        // Only columns with |nx| < taper + 0.405 can pass the first test
        // below; one cell of slack each side, and the test still decides.
        var reachPx = (taper + 0.405) * flameW, centre = BASE_X + sway;
        var kFrom = Math.max(0, Math.max(0, Math.floor(centre - reachPx) - 1) - xStart);
        var kTo = Math.min(cols, Math.min(W, Math.ceil(centre + reachPx) + 2) - xStart);
        for (k = kFrom; k < kTo; k++) {
            x = xStart + k;
            var nx = (x - BASE_X - sway) / flameW;
            var reach = taper - (nx < 0 ? -nx : nx);
            if (reach + 0.43 <= 0.025) continue;
            if (!rowReady) {
                if (iy1 !== row1) {
                    if (iy1 === row1 + 1) { swap = hA1; hA1 = hB1; hB1 = swap; }
                    else hashRow(hA1, min1, n1, iy1);
                    hashRow(hB1, min1, n1, iy1 + 1);
                    row1 = iy1;
                }
                if (iy2 !== row2) {
                    if (iy2 === row2 + 1) { swap = hA2; hA2 = hB2; hB2 = swap; }
                    else hashRow(hA2, min2, n2, iy2);
                    hashRow(hB2, min2, n2, iy2 + 1);
                    row2 = iy2;
                }
                rowReady = true;
            }
            var o1 = ix1[k] - min1, o2 = ix2[k] - min2, fa = fx1[k], fb2 = fx2[k];
            var a = hA1[o1], b = hA1[o1 + 1], c = hB1[o1], d = hB1[o1 + 1];
            var na = a + (b - a) * fa + (c - a) * fy1 + (a - b - c + d) * fa * fy1;
            a = hA2[o2]; b = hA2[o2 + 1]; c = hB2[o2]; d = hB2[o2 + 1];
            var nb = a + (b - a) * fb2 + (c - a) * fy2 + (a - b - c + d) * fb2 * fy2;
            var n = na * 0.65 + nb * 0.35;
            var edge = reach + (n - 0.5) * 0.85;
            if (edge <= 0.025) continue;
            var core = edge * 0.85 + shade;
            var ci = core > 0.98 ? 0 : core > 0.78 ? 1 : core > 0.53 ? 2 : core > 0.31 ? 3 : core > 0.14 ? 4 : 5;
            if (edge < 0.09) {
                var i = (rowBase + x) * 4, fc = FC[ci];
                fb[i] = fc[0] * 0.75 + fb[i] * 0.25;
                fb[i + 1] = fc[1] * 0.75 + fb[i + 1] * 0.25;
                fb[i + 2] = fc[2] * 0.75 + fb[i + 2] * 0.25;
            } else {
                u[rowBase + x] = F[ci];
            }
            if (rowLeft < 0) rowLeft = x;
            rowRight = x;
        }
        if (rowLeft >= 0) {
            if (y < top) top = y; if (y > bottom) bottom = y;
            if (rowLeft < left) left = rowLeft; if (rowRight > right) right = rowRight;
        }
    }
    if (bottom >= 0) markDirty(left, top, right + 1, bottom + 1);
}

// native: the rectangles this frame drew into over the background, so the
// canvas copies only those (and last frame's, to restore the art under
// them). A list is { n, r: Int16Array of x0, y0, x1, y1 }, clipped to the
// frame, half-open. Past RECT_MAX the last rectangle grows to cover the rest.
var RECT_MAX = 512;
function createRects() { return { n: 0, r: new Int16Array(RECT_MAX * 4) }; }
var _rects = null;
// A Uint32Array over the frame's bytes (RGBA, little-endian), for whole-pixel
// writes.
var _u32 = null, _u32of = null;
function view32(fb) {
    if (_u32of !== fb) { _u32 = new Uint32Array(fb.buffer, fb.byteOffset, fb.length >> 2); _u32of = fb; }
    return _u32;
}
function markDirty(x0, y0, x1, y1) {
    var list = _rects;
    if (!list) return;
    x0 = Math.max(0, Math.floor(x0)); y0 = Math.max(0, Math.floor(y0));
    x1 = Math.min(W, Math.ceil(x1)); y1 = Math.min(H, Math.ceil(y1));
    if (!(x1 > x0 && y1 > y0)) return;
    var r = list.r, k;
    if (list.n >= RECT_MAX) {
        k = (RECT_MAX - 1) * 4;
        if (x0 < r[k]) r[k] = x0; if (y0 < r[k + 1]) r[k + 1] = y0;
        if (x1 > r[k + 2]) r[k + 2] = x1; if (y1 > r[k + 3]) r[k + 3] = y1;
        return;
    }
    k = list.n++ * 4;
    r[k] = x0; r[k + 1] = y0; r[k + 2] = x1; r[k + 3] = y1;
}

// The reference draw(), into fb. bg is a prepared 360 x 240 RGBA buffer or
// null for the reference's fallback fill. If `rects` (from createRects) is
// given it receives the rectangles drawn over the background.
function render(fb, s, bg, rects) {
    var i, p;
    _rects = rects || null;
    if (rects) rects.n = 0;
    if (bg) fb.set(bg);
    else fillRect(fb, 0, 0, W, H, C.empty, 1);
    var t = s.elapsed;
    drawMoonlight(fb, bg, t);
    for (i = 0; i < s.stars.length; i++) {
        var st = s.stars[i], sn = Math.sin(t * 0.7 + st.p);
        fillRect(fb, Math.floor(st.x), Math.floor(st.y), 1, 1, C.star, 0.25 + 0.4 * sn * sn);
        markDirty(Math.floor(st.x), Math.floor(st.y), Math.floor(st.x) + 1, Math.floor(st.y) + 1);
    }
    for (i = 0; i < s.smoke.length; i++) {
        p = s.smoke[i];
        var r = 2 + p.age * 1.3, sx = Math.floor(p.x), sy = Math.floor(p.y), sw = Math.round(r), sh = Math.round(r * 1.8);
        fillRect(fb, sx, sy, sw, sh, C.smoke, 0.04 * Math.sin(Math.PI * p.age / p.life));
        markDirty(sx, sy, sx + sw, sy + sh);
    }
    drawFire(fb, s, t);
    for (i = 0; i < s.sparks.length; i++) {
        p = s.sparks[i];
        var px = Math.round(p.x), py = Math.round(p.y);
        fillRect(fb, px, py, p.s, p.s, p.age < 0.5 ? C.sparkHot : C.sparkCool, Math.max(0, 1 - p.age / p.life));
        markDirty(px, py, px + p.s, py + p.s);
    }
    var lx, ly;
    if (s.flight) {
        var pr = Math.min(1, s.flight.age / FLIGHT_SECONDS), ease = 1 - (1 - pr) * (1 - pr);
        lx = s.flight.fromX + (BASE_X - s.flight.fromX) * ease;
        ly = s.flight.fromY + (BASE_Y - s.flight.fromY) * ease - Math.sin(pr * Math.PI) * 45;
        drawLog(fb, lx, ly, pr * 0.4);
        markDirty(Math.round(lx) - 23, Math.round(ly) - 23, Math.round(lx) + 24, Math.round(ly) + 24);
    }
    if (s.drag) {
        drawLog(fb, s.drag.x, s.drag.y, -0.22);
        markDirty(Math.round(s.drag.x) - 23, Math.round(s.drag.y) - 23, Math.round(s.drag.x) + 24, Math.round(s.drag.y) + 24);
    }
    _rects = null;
    return rects;
}

// ---------------------------------------------------------------- bar icon
//
// native: a 12 x 12 pixel flame for the bar, using the same palette, the same
// noise, and the same status thresholds. Returns 144 entries of a palette
// index (0 = empty, 1..6 flame hot to cool, 7..9 embers, 10 log).
var ICON_SIZE = 12;
var ICON_COLORS = [null, "#fff0a7", "#ffd36e", "#ffae3c", "#f37828", "#d5471d", "#91341f", "#ffc05b", "#db6328", "#733021", "#74472b"];

//
// Layout: crossed logs on rows 10-11 spanning columns 0-11, coals between the
// log ends on row 10, and a flame rising from row 10 that reaches row 2 at a
// steady burn and the top row when stoked, so a lit icon fills the same
// 12 x 12 box as the bar's 12 px glyphs instead of sitting low in it.
function iconCells(heat, boost, fuel, t) {
    var N = ICON_SIZE, out = [], x, y;
    for (x = 0; x < N * N; x++) out.push(0);
    // Two crossed logs: a full-width bottom row, raised ends on row 10.
    for (x = 0; x < N; x++) out[11 * N + x] = 10;
    out[10 * N + 0] = out[10 * N + 1] = out[10 * N + 10] = out[10 * N + 11] = 10;
    var hot = Math.min(1, heat * 3);
    // Coals between the log ends.
    for (x = 2; x < 10; x++) {
        var g = (0.4 + 0.6 * Math.max(0, Math.sin(t * 1.4 + x * 1.7))) * hot;
        if (fuel > 0.01 || heat > 0.02) out[10 * N + x] = g > 0.6 ? 7 : g > 0.3 ? 8 : 9;
    }
    var alive = Math.min(1, heat * 1.6);
    var fh = (6 + heat * 4.5 + boost * 1.5) * alive, fw = (3 + heat * 1.6 + boost * 0.5) * alive;
    if (fh < 1.2) return out;
    var cx = 5.5, base = 10;
    for (y = 0; y < base; y++) {
        var v = (base - y) / fh;
        if (v > 1) continue;
        var sway = Math.sin(v * 5 - t * 3.1) * v * 0.9;
        for (x = 0; x < N; x++) {
            var nx = (x - cx - sway) / fw;
            var n = noise(x * 0.9 + t * 0.6, y * 0.7 + t * 3.3);
            var edge = Math.pow(Math.max(0, 1 - v), 0.8) - Math.abs(nx) + (n - 0.5) * 0.6;
            if (edge <= 0.05) continue;
            var core = edge * 0.85 + (1 - v) * 0.28;
            out[y * N + x] = core > 0.98 ? 1 : core > 0.78 ? 2 : core > 0.53 ? 3 : core > 0.31 ? 4 : core > 0.14 ? 5 : 6;
        }
    }
    return out;
}

// node: expose for tests (QML ignores this).
if (typeof module !== "undefined" && module.exports) {
    module.exports = {
        W: W, H: H, BASE_X: BASE_X, BASE_Y: BASE_Y, SEED: SEED, DT_CAP: DT_CAP, SPARK_CAP: SPARK_CAP, SMOKE_CAP: SMOKE_CAP,
        GAP_SECONDS: GAP_SECONDS, LONG_ABSENCE_SECONDS: LONG_ABSENCE_SECONDS, MESSAGES: MESSAGES, HINT: HINT,
        rand: rand, hash: hash, noise: noise, createState: createState, burst: burst, addLog: addLog, stoke: stoke, poke: poke,
        inWoodpile: inWoodpile, onCoals: onCoals, inHearth: inHearth,
        pointerDown: pointerDown, pointerMove: pointerMove, pointerUp: pointerUp, pointerCancel: pointerCancel, cursorFor: cursorFor,
        update: update, advanceAnalytic: advanceAnalytic, tick: tick, status: status, statusIndex: statusIndex,
        serialize: serialize, restore: restore,
        roundByte: roundByte, createFrame: createFrame, prepareBackground: prepareBackground, render: render, drawLog: drawLog,
        cachedBackground: cachedBackground, cacheBackground: cacheBackground, drawMoonlight: drawMoonlight,
        createRects: createRects, RECT_MAX: RECT_MAX,
        iconCells: iconCells, ICON_SIZE: ICON_SIZE, ICON_COLORS: ICON_COLORS
    };
}

root.OmaFire = { W: W, H: H, createState: createState, tick: tick, render: render, createFrame: createFrame,
  createRects: createRects, prepareBackground: prepareBackground, cacheBackground: cacheBackground, addLog: addLog,
  stoke: stoke, poke: poke, status: status };
})(typeof window !== "undefined" ? window : this);
