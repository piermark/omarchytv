/* Radiomarchy fractals on LG webOS and Samsung Tizen (v2.20): the same five 90s demoscene fractals as the Android
 * app (FractalView.java), the same fragment shader word for word, drawn with WebGL and danced by the music.
 * Mandelbrot deep zoom into a random point on the set's edge, Julia from a random c, plasma, tunnel and kaliset,
 * each with random geometry and (by default) a random palette at every scene change.
 * It adapts to the TV: it starts at "high" (full resolution) on a typical TV, measures frame time, and steps
 * down only when present time (rAF start-to-start, including the swap) shows backpressure, to a 720p internal
 * floor (the backing store is never larger than 1080p, even with devicePixelRatio 2); below that it drops
 * the palette, never resolution. It steps back up when there is room.
 * ES5. Host: OmarchyFractals.create(canvas, { bands: fn(out) -> bool, palette, quality, theme: [hex x4] })
 *   -> { start(), stop(), next(dir), setPalette(i), setQuality(i), scene() -> name, quality() -> name, destroy() }. */
(function (root) {
  "use strict";
  var SCENES = ["mandelbrot", "julia", "plasma", "tunnel", "kaliset"];
  var PALETTES = ["random", "theme", "rainbow", "fire"];
  var QUALITIES = ["auto", "ultra", "high", "medium", "low"];
  var LEVELS = [
    { name: "ultra", aa: 2, iter: 500, scale: 1 },
    { name: "high", aa: 1, iter: 320, scale: 1 },
    { name: "medium", aa: 1, iter: 180, scale: 1 },
    { name: "low", aa: 1, iter: 110, scale: 1 },
    { name: "light", aa: 1, iter: 24, scale: 1 }
  ];
  var LIGHT_LEVEL = 4;
  var VERT = "attribute vec2 aPos;\nvarying vec2 vUv;\nvoid main(){ vUv = aPos; gl_Position = vec4(aPos, 0.0, 1.0); }\n";
  var LIGHT_VERT = "attribute vec2 aPos;\nvarying vec2 vUv;\nvoid main(){ vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }\n";
  var LIGHT_FRAG = "precision mediump float;\nvarying vec2 vUv;\nuniform sampler2D uTex;\nuniform float uTime, uPulse, uBass, uCycle, uDim, uLevel;\nuniform vec3 uPal[4];\nuniform int uHave;\n"
    + "vec3 pal(float t){ t = fract(t) * 4.0; float k = fract(t); k = k * k * (3.0 - 2.0 * k);\n"
    + "  if (t < 1.0) return mix(uPal[0], uPal[1], k); if (t < 2.0) return mix(uPal[1], uPal[2], k);\n"
    + "  if (t < 3.0) return mix(uPal[2], uPal[3], k); return mix(uPal[3], uPal[0], k); }\n"
    + "void main(){\n"
    + "  float z = 1.0 + 0.14 * uPulse + 0.08 * uBass;\n"
    + "  vec2 pan = 0.045 * vec2(sin(uTime * 0.07), cos(uTime * 0.053));\n"
    + "  vec2 uv = (vUv - 0.5) / z + 0.5 + pan;\n"
    + "  uv = clamp(uv, 0.001, 0.999);\n"
    + "  float idx;\n"
    + "  if (uHave > 0) idx = texture2D(uTex, uv).r;\n"
    + "  else {\n"
    + "    vec2 p = uv * 2.0 - 1.0;\n"
    + "    idx = 0.5 + 0.25 * sin(p.x * 6.0 + uTime * 0.3) + 0.25 * sin(p.y * 5.0 - uTime * 0.2)\n"
    + "        + 0.2 * sin((p.x + p.y) * 4.0 + uTime * 0.15);\n"
    + "  }\n"
    + "  vec3 c = pal(idx + uCycle) * (0.85 + 0.4 * uLevel);\n"
    + "  c = c / (1.0 + c * 0.35);\n"
    + "  gl_FragColor = vec4(c * uDim, 1.0);\n"
    + "}\n";
  var FRAG = "varying vec2 vUv;\nuniform vec2 uRes; uniform float uTime, uBass, uMid, uHigh, uLevel, uPulse, uMix, uCycle, uZoom, uIter, uRot, uDim, uAA;\nuniform int uScene, uScene2; uniform vec3 uPal[4]; uniform vec2 uCenter; uniform vec4 uP[5];\nvec3 pal(float t){ t = fract(t) * 4.0; float k = fract(t); k = k * k * (3.0 - 2.0 * k);\n  if (t < 1.0) return mix(uPal[0], uPal[1], k); if (t < 2.0) return mix(uPal[1], uPal[2], k);\n  if (t < 3.0) return mix(uPal[2], uPal[3], k); return mix(uPal[3], uPal[0], k); }\nvec2 rot(vec2 p, float a){ float c = cos(a), s = sin(a); return vec2(c * p.x - s * p.y, s * p.x + c * p.y); }\nvec3 shade(float n, float m2, float de, float px, float glow){\n  float sn = max(0.0, n - log2(max(1.0001, log2(m2))) + 4.0);\n  float t = log2(sn + 1.0) * 0.38 + uCycle;\n  float fade = clamp(log2(sn + 1.0) / 6.0, 0.0, 1.0);\n  float edge = exp(-de / (px * 2.5));\n  return pal(t) * (0.16 + 0.6 * fade) * (0.85 + 0.5 * uLevel) + pal(t + 0.25) * edge * (0.8 + glow); }\nvec3 mandel(vec2 p){\n  vec2 c = uCenter + rot(p, uRot) * uZoom; vec2 z = vec2(0.0), dz = vec2(0.0); float n = 0.0, m2 = 0.0, trap = 1e9;\n  for (int i = 0; i < 500; i++) { if (float(i) >= uIter) break;\n    dz = 2.0 * vec2(z.x * dz.x - z.y * dz.y, z.x * dz.y + z.y * dz.x) + vec2(1.0, 0.0);\n    z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c;\n    m2 = dot(z, z); trap = min(trap, abs(z.y)); if (m2 > 256.0) break; n += 1.0; }\n  if (m2 <= 256.0) return pal(uCycle * 2.0 + trap * 3.0) * (0.05 + 0.22 * uPulse);\n  float de = 0.25 * sqrt(m2) * log(m2) / max(length(dz), 1e-20);\n  return shade(n, m2, de, 2.0 * uZoom / uRes.y, uPulse); }\nvec3 julia(vec2 p){\n  vec4 q = uP[1]; float a = uTime * 0.21 + uMid * 1.5;\n  vec2 c = q.xy + q.z * vec2(cos(a), sin(a * 1.3)) * (1.0 + uBass);\n  float sc = q.w - 0.1 * uPulse; vec2 z = rot(p, uTime * 0.05) * sc, dz = vec2(1.0, 0.0); float n = 0.0, m2 = 0.0, trap = 1e9;\n  for (int i = 0; i < 500; i++) { if (float(i) >= uIter * 0.7) break;\n    dz = 2.0 * vec2(z.x * dz.x - z.y * dz.y, z.x * dz.y + z.y * dz.x);\n    z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c;\n    m2 = dot(z, z); trap = min(trap, length(z - vec2(0.0, 0.3 * sin(uTime * 0.3)))); if (m2 > 256.0) break; n += 1.0; }\n  if (m2 <= 256.0) return pal(uCycle + trap) * (0.08 + 0.2 * uPulse) + pal(uCycle + 0.5) * exp(-trap * 5.0) * (0.3 + 0.7 * uHigh);\n  float de = 0.25 * sqrt(m2) * log(m2) / max(length(dz), 1e-20);\n  return shade(n, m2, de, 2.0 * sc / uRes.y, uPulse) + pal(uCycle + 0.5) * exp(-trap * 6.0) * 0.4 * uHigh; }\nvec3 plasma(vec2 p){\n  vec4 q = uP[2]; float t = uTime * (0.6 + 0.5 * uMid); p *= 1.3 + 0.5 * uBass;\n  float v = sin(p.x * q.x + t) + sin((p.y * q.y + t) * 0.8) + sin((p.x + p.y) * q.z * 0.7 + t * 1.3)\n    + sin(length(p * q.w + vec2(sin(t * 0.3), cos(t * 0.5)) * 3.0) - t * 2.0);\n  return pal(v * 0.125 + uCycle) * (0.7 + 0.5 * uBass + 0.4 * uPulse); }\nvec3 tunnel(vec2 p){\n  vec4 q = uP[3];\n  p = rot(p, uTime * 0.2 * q.z + 0.3 * sin(uTime * 0.37)); p += 0.15 * vec2(sin(uTime * 0.5), cos(uTime * 0.4));\n  float r = max(length(p), 0.001), a = atan(p.y, p.x);\n  float u = 0.35 / r + uTime * q.w * (0.5 + 0.9 * uBass), v = a / 3.14159265 * q.y + q.z * 0.2 / r + uTime * 0.07;\n  float ch = mod(floor(u * q.x) + floor(v), 2.0);\n  vec3 c = pal(u * 0.07 + uCycle) * (0.45 + 0.55 * ch);\n  float ring = exp(-abs(fract(u * 0.5) - 0.5) * 18.0) * uPulse;\n  return c * smoothstep(0.0, 0.5, r) * (0.8 + 0.4 * uLevel) + ring * pal(uCycle + 0.5) * 1.2; }\nvec3 kali(vec2 p){\n  vec4 q = uP[4]; vec2 z = rot(p, uTime * 0.04) * q.z * (0.9 + 0.1 * sin(uTime * 0.13));\n  vec2 c = q.xy + vec2(0.1 * sin(uTime * 0.071), 0.1 * cos(uTime * 0.053)) + vec2(uMid, -uBass) * 0.035;\n  float acc = 0.0, lo = 1e9;\n  for (int i = 0; i < 16; i++) { if (float(i) >= q.w) break; z = abs(z) / max(dot(z, z), 0.0005) + c; float l = length(z); lo = min(lo, l); acc += exp(-l * 1.6); }\n  vec3 col = pal(acc * 0.08 + uCycle) * acc * 0.17;\n  return col * (0.7 + 0.6 * uHigh + 0.4 * uPulse) + pal(uCycle + 0.3) * exp(-lo * 9.0) * (0.3 + uHigh); }\nvec3 scene(int s, vec2 p){\n#ifndef LOWP\n  if (s == 0) return mandel(p); if (s == 1) return julia(p);\n#endif\n  if (s == 3) return tunnel(p); if (s == 4) return kali(p); return plasma(p); }\nvec3 frame(vec2 uv){\n  vec2 p = uv * vec2(uRes.x / uRes.y, 1.0);\n  vec3 c = scene(uScene, p);\n  if (uMix > 0.0) c = mix(c, scene(uScene2, p), smoothstep(0.0, 1.0, uMix));\n  return c; }\nvoid main(){\n  vec3 c = frame(vUv);\n  if (uAA > 1.5) {\n    vec2 px = 1.0 / uRes;\n    c += frame(vUv + vec2(px.x, 0.0)) + frame(vUv + vec2(0.0, px.y)) + frame(vUv + px);\n    c *= 0.25; }\n  c = c / (1.0 + c * 0.35);\n  float vig = 1.0 - 0.28 * dot(vUv * 0.75, vUv * 0.75);\n  float n = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233)) + uTime) * 43758.5453);\n  c = c * vig * uDim + (n - 0.5) / 255.0;\n  gl_FragColor = vec4(max(c, 0.0), 1.0); }\n";

  function hsv(h, s, v, out, o) {
    var c = v * s, x = c * (1 - Math.abs((h * 6) % 2 - 1)), m = v - c, k = Math.floor(h * 6) % 6;
    out[o] = (k === 0 || k === 5 ? c : k === 1 || k === 4 ? x : 0) + m;
    out[o + 1] = (k === 1 || k === 2 ? c : k === 0 || k === 3 ? x : 0) + m;
    out[o + 2] = (k === 3 || k === 4 ? c : k === 2 || k === 5 ? x : 0) + m;
  }
  /** Four colours that go together: a random hue, a random harmony. */
  function randomPalette(rnd) {
    rnd = rnd || Math.random;
    var h = rnd(), H = [[0, 0.08, 0.16, 0.5], [0, 1 / 3, 2 / 3, 0.08], [0, 0.42, 0.58, 0.04], [0, 0.25, 0.5, 0.75], [0, 0.06, 0.5, 0.56]];
    var hm = H[Math.floor(rnd() * H.length)], p = [];
    for (var i = 0; i < 4; i++) hsv((h + hm[i]) % 1, 0.55 + 0.45 * rnd(), i === 0 ? 0.55 + 0.2 * rnd() : 0.75 + 0.25 * rnd(), p, i * 3);
    return p;
  }
  function hexes(list) {
    var p = [];
    for (var i = 0; i < 4; i++) {
      var hx = /^#?([0-9a-f]{6})$/i.exec(list[i] || "") ? list[i].replace("#", "") : "7aa2f7";
      p.push(parseInt(hx.substr(0, 2), 16) / 255, parseInt(hx.substr(2, 2), 16) / 255, parseInt(hx.substr(4, 2), 16) / 255);
    }
    return p;
  }
  function fixedPalette(which, theme) {
    if (which === 2) return hexes(["#ff2a6d", "#ffd23f", "#3bceac", "#5b5bff"]);
    if (which === 3) return hexes(["#2a0800", "#b21f00", "#ff8a00", "#fff0a0"]);
    return hexes(theme || []);
  }
  function escape(cx, cy, max) {
    var x = 0, y = 0;
    for (var i = 0; i < max; i++) {
      var xx = x * x - y * y + cx;
      y = 2 * x * y + cy;
      x = xx;
      if (x * x + y * y > 16) return i;
    }
    return max;
  }
  /** A random point right on the Mandelbrot set's edge (escapes, but late): detail all the way down a zoom. */
  function edgePoint(rnd, minIter, maxIter) {
    rnd = rnd || Math.random;
    var best = [-0.743643887, 0.131825904], bestN = 0;
    for (var tries = 0; tries < 6000; tries++) {
      var a = rnd() * 2 * Math.PI, cx, cy;
      if (Math.floor(rnd() * 5) === 0) { cx = -1 + 0.25 * Math.cos(a); cy = 0.25 * Math.sin(a); }
      else { cx = 0.5 * Math.cos(a) - 0.25 * Math.cos(2 * a); cy = 0.5 * Math.sin(a) - 0.25 * Math.sin(2 * a); }
      var rr = 0.002 + 0.06 * rnd() * rnd();
      cx += (rnd() - 0.5) * rr;
      cy += (rnd() - 0.5) * rr;
      var n = escape(cx, cy, maxIter);
      if (n >= minIter && n < maxIter) return [cx, cy];
      if (n < maxIter && n > bestN) { bestN = n; best = [cx, cy]; }
    }
    return best;
  }
  var MAX_BUFFER_H = 1080, FLOOR_BUFFER_H = 720, HOME_FPS = 24, SAVER_FPS = 30, WATCH_FRAMES = 16, LATE_RUSH = 4;
  var GRACE_FRAMES = 8, GRACE_MS = 5000, GRACE_MAX_MS = 8000, STALL_MS = 500, STALL_WINDOW_MS = 20000, STALLS_TO_KILL = 3;
  var FATAL_STALL_MS = 2000, SLOW_PRESENT_MS = 1000, SLOW_PRESENTS_TO_KILL = 3;
  var STALL_DEGRADE = 1, STALL_KILL = 2;
  var CHEAP_FPS = 12, FBO_SCALE_FLOOR = 2, FBO_SCALE_CHEAP = 3;
  var BAKE_H = 160, BAKE_ITERS = 20;

  /** Where to start for this GPU (then it measures itself). ramGb <= 2 or Mali-T8xx starts at medium/720p. */
  function sceneFps(gpu, ramGb) {
    if (ramGb > 0 && ramGb <= 2) return HOME_FPS;
    var g = String(gpu || "").toLowerCase();
    if (/mali-t8|mali-t7|mali-g31|mali-g51|adreno \(tm\) 3|powervr ge8/.test(g)) return HOME_FPS;
    return SAVER_FPS;
  }

  function firstLevel(gpu, lowp, ramGb, preferLight) {
    if (preferLight) return LIGHT_LEVEL;
    var g = String(gpu || "").toLowerCase();
    if (lowp || /mali-[34]|videocore iv/.test(g)) return LIGHT_LEVEL;
    var tight = ramGb > 0 && ramGb <= 2;
    if (tight || /mali-t8|mali-t7|mali-g31|mali-g51|adreno \(tm\) 3|powervr ge8/.test(g)) return LIGHT_LEVEL;
    return 1;   // capable TVs: start at high (1080p cap), step down only on backpressure
  }

  function isLight(level) { return level >= LIGHT_LEVEL; }
  function isCheapest(level) { return level >= 3; }

  /** Backing-store height: never above 1080p; medium/low use the 720p floor. */
  function bufferHeight(level, viewH, maxH) {
    if (!maxH) maxH = MAX_BUFFER_H;
    if (!viewH || viewH < 1) viewH = MAX_BUFFER_H;
    var cap = Math.min(viewH, maxH);
    if (level <= 1) return Math.max(1, Math.round(cap));
    return Math.max(1, Math.round(Math.min(cap, FLOOR_BUFFER_H)));
  }

  function bufferSize(level, cssW, cssH, dpr, maxH) {
    if (!cssW) cssW = 1920;
    if (!cssH) cssH = 1080;
    dpr = dpr || 1;
    var viewW = cssW * dpr, viewH = cssH * dpr;
    var h = bufferHeight(level, viewH, maxH || MAX_BUFFER_H);
    var w = Math.max(1, Math.round(viewW * (h / viewH)));
    return { w: w, h: h };
  }

  /** Ratio of the backing-store height to the CSS height (dpr=1). Never above 1080p. */
  function scaleFor(level, viewH) {
    if (!viewH || viewH < 1) viewH = 1080;
    return bufferHeight(level, viewH, MAX_BUFFER_H) / viewH;
  }

  function isBackpressured(intervalMs, fpsCap) {
    var period = 1000 / Math.max(1, fpsCap);
    return intervalMs > period + 8;
  }

  function shouldDisableAtFloor(level, slowWindows) {
    return isCheapest(level) && slowWindows >= 2;
  }

  /** Cold start / first layout / resume: a 600 ms rAF gap is normal, not a fatal stall.
   *  After GRACE_MAX_MS grace is over even if almost no frames were presented. */
  function inGracePeriod(laidOut, presentedFrames, elapsedMs) {
    if (elapsedMs >= GRACE_MAX_MS) return false;
    if (!laidOut) return true;
    return presentedFrames < GRACE_FRAMES || elapsedMs < GRACE_MS;
  }

  function isLongStall(gapMs) { return gapMs >= FATAL_STALL_MS; }
  function isSlowPresent(intervalMs) { return intervalMs >= SLOW_PRESENT_MS; }

  function afterGraceAction(level, gapMs, consecutiveSlow, stallsInWindow) {
    if (isCheapest(level) && isLongStall(gapMs)) return STALL_KILL;
    if (isCheapest(level) && consecutiveSlow >= SLOW_PRESENTS_TO_KILL) return STALL_KILL;
    return stallAction(level, stallsInWindow);
  }

  function isUiStall(intervalMs) {
    return intervalMs > STALL_MS;
  }

  /** stallsInWindow includes this stall. Persistent stalls at the floor kill, even if iters/fps were reset.
   *  Two or three args: stallAction(level, count) or stallAction(level, reducedItersIgnored, count). */
  function stallAction(level, a, b) {
    var n = b == null ? a : b;
    if (level >= 3 && n >= STALLS_TO_KILL) return STALL_KILL;
    return STALL_DEGRADE;
  }

  function internalHeight(surfaceH, cheapScale) {
    if (!surfaceH) surfaceH = FLOOR_BUFFER_H;
    if (cheapScale <= 1) return surfaceH;
    return Math.max(1, Math.round(surfaceH / cheapScale));
  }

  function cheapScaleFor(level, already) {
    if (level < 3 || isLight(level)) return 1;
    return Math.max(FBO_SCALE_FLOOR, Math.min(FBO_SCALE_CHEAP, already <= 1 ? FBO_SCALE_FLOOR : already));
  }

  function countStallsInWindow(times, nowMs, windowMs) {
    var c = 1, i;
    for (i = 0; i < times.length; i++) if (nowMs - times[i] <= windowMs) c++;
    return c;
  }

  function rgbCss(r, g, b) {
    function h(v) { v = Math.max(0, Math.min(255, Math.round(v * 255))); return (v < 16 ? "0" : "") + v.toString(16); }
    return "#" + h(r) + h(g) + h(b);
  }
  function lin(s) { return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4); }
  function lum(c) {
    var m = /^#?([0-9a-f]{6})$/i.exec(c), n = m ? parseInt(m[1], 16) : 0;
    return 0.2126 * lin(((n >> 16) & 255) / 255) + 0.7152 * lin(((n >> 8) & 255) / 255) + 0.0722 * lin((n & 255) / 255);
  }
  function contrastRatio(a, b) {
    var l1 = lum(a), l2 = lum(b), hi = Math.max(l1, l2), lo = Math.min(l1, l2);
    return (hi + 0.05) / (lo + 0.05);
  }
  function mixHex(a, b, k) {
    function ch(h, i) { return parseInt(h.substr(i, 2), 16); }
    a = (a || "#000000").replace("#", ""); b = (b || "#ffffff").replace("#", "");
    var r = Math.round(ch(a, 0) + (ch(b, 0) - ch(a, 0)) * k);
    var g = Math.round(ch(a, 2) + (ch(b, 2) - ch(a, 2)) * k);
    var bl = Math.round(ch(a, 4) + (ch(b, 4) - ch(a, 4)) * k);
    function h(v) { v = Math.max(0, Math.min(255, v)); return (v < 16 ? "0" : "") + v.toString(16); }
    return "#" + h(r) + h(g) + h(bl);
  }
  function ensureContrast(color, bg) {
    if (contrastRatio(color, bg) >= 4.5) return color;
    var tw = mixHex(color, "#ffffff", 0.12), tb = mixHex(color, "#000000", 0.12), i;
    for (i = 0; i < 16; i++) {
      if (contrastRatio(tw, bg) >= 4.5) return tw;
      if (contrastRatio(tb, bg) >= 4.5) return tb;
      tw = mixHex(tw, "#ffffff", 0.12);
      tb = mixHex(tb, "#000000", 0.12);
    }
    return contrastRatio(tw, bg) >= contrastRatio(tb, bg) ? tw : tb;
  }

  /** The no-music pulse (~100 bpm), as AudioBands.idle on Android. */
  function idle(nowMs, out) {
    var s = nowMs / 1000, beat = 600;
    out.bass = 0.35 + 0.2 * Math.sin(s * 0.7);
    out.mid = 0.3 + 0.15 * Math.sin(s * 0.43 + 1);
    out.high = 0.2 + 0.1 * Math.sin(s * 1.1 + 2);
    out.level = 0.3;
    out.beatAgeMs = nowMs % beat;
    out.beats = Math.floor(nowMs / beat);
    return out;
  }

  function create(canvas, opts) {
    opts = opts || {};
    var gl = canvas.getContext("webgl", { antialias: false, alpha: false, preserveDrawingBuffer: false }) || canvas.getContext("experimental-webgl");
    if (!gl) return null;
    var rnd = Math.random, raf = 0, running = false;
    var hp = gl.getShaderPrecisionFormat(gl.FRAGMENT_SHADER, gl.HIGH_FLOAT), lowp = !hp || hp.precision < 16;
    var dbg = gl.getExtension("WEBGL_debug_renderer_info");
    var gpu = dbg ? gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER);
    var ramGb = opts.ramGb || (root.navigator && root.navigator.deviceMemory) || 0;
    var home = !!opts.home;
    var preferLight = !!opts.preferLight;
    try { if (!preferLight && root.localStorage && root.localStorage.getItem("omarchytv.fractals.prefer_light") === "1") preferLight = true; } catch (e) { /* private */ }
    var pinned = opts.quality > 0 ? Math.min(4, opts.quality) - 1 : -1;
    var level = pinned >= 0 ? pinned : firstLevel(gpu, lowp, ramGb, preferLight);
    var palette = opts.palette || 0;
    function sh(type, src) {
      var s = gl.createShader(type);
      gl.shaderSource(s, src);
      gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS) && root.console) root.console.warn("fractals: " + gl.getShaderInfoLog(s));
      return s;
    }
    var prog = gl.createProgram();
    gl.attachShader(prog, sh(gl.VERTEX_SHADER, VERT));
    gl.attachShader(prog, sh(gl.FRAGMENT_SHADER, (lowp ? "precision mediump float;\n#define LOWP 1\n" : "precision highp float;\n") + FRAG));
    gl.linkProgram(prog);
    gl.useProgram(prog);
    var U = {}, names = ["uRes", "uTime", "uBass", "uMid", "uHigh", "uLevel", "uPulse", "uScene", "uScene2", "uMix", "uPal", "uCycle", "uZoom", "uCenter", "uIter", "uRot", "uDim", "uAA", "uP"];
    for (var i = 0; i < names.length; i++) U[names[i]] = gl.getUniformLocation(prog, names[i]);
    var buf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
    var aPos = gl.getAttribLocation(prog, "aPos");
    gl.enableVertexAttribArray(aPos);
    gl.vertexAttribPointer(aPos, 2, gl.FLOAT, false, 0, 0);

    var t0 = Date.now(), cur = lowp ? 2 + Math.floor(rnd() * 3) : Math.floor(rnd() * SCENES.length), next = -1, mix = 0, mixStart = 0;
    var forced = -1, sceneSince = Date.now(), beatCount = 0, sceneBeats = 32, lastBeats = -1;
    var cycle = 0, dive = 0, zoomKick = 0, rotDir = 1, diveSpeed = 1, center = [-0.743643887, 0.131825904];
    var params = new Float32Array(SCENES.length * 4), pal = new Float32Array(12), palFrom = new Float32Array(12), palTo = new Float32Array(12), palStart = 0, newPal = true;
    var f = { bass: 0, mid: 0, high: 0, level: 0, beatAgeMs: 1e9, beats: 0 };
    var frames = 0, frameMs = 0, lastT = 0, goodSince = 0, late = 0, lastDraw = 0, lastPalEmit = 0, lastFpsLog = 0;
    var capFps = true, freezePalette = false, iterMul = 1, fpsCap = home ? HOME_FPS : sceneFps(gpu, ramGb);
    var lateInWindow = 0, slowWindows = 0, sessionOff = false;
    var graceStart = 0, presented = 0, lastRaf = 0, stallTimes = [], consecutiveSlow = 0;
    var bakeDirty = true, bakeFb = null, bakeTex = null, bakeW = 0, bakeH = 0, lightProg = null, lightProcedural = false;
    var BAKE_PAL = [0, 0, 0, 0.33, 0.33, 0.33, 0.66, 0.66, 0.66, 1, 1, 1];
    for (i = 0; i < 12; i++) { pal[i] = 0; palFrom[i] = 0; }

    function newCenter() {
      center = edgePoint(rnd, 400, 4000);
      rotDir = rnd() < 0.5 ? 1 : -1;
      diveSpeed = 0.8 + 0.5 * rnd();
    }
    function randomise(s) {
      var o = s * 4, c;
      if (s === 0) { newCenter(); dive = 0.8 + 1.7 * rnd(); }
      else if (s === 1) { c = edgePoint(rnd, 30, 200); params[o] = c[0]; params[o + 1] = c[1]; params[o + 2] = 0.01 + 0.03 * rnd(); params[o + 3] = 1.1 + 0.4 * rnd(); }
      else if (s === 2) { for (var k = 0; k < 4; k++) params[o + k] = 1.5 + 4 * rnd(); }
      else if (s === 3) { params[o] = 2 + Math.floor(rnd() * 5); params[o + 1] = 2 + Math.floor(rnd() * 7); params[o + 2] = (rnd() - 0.5) * 3; params[o + 3] = 0.6 + 0.8 * rnd(); }
      else { params[o] = -0.9 + 0.45 * rnd(); params[o + 1] = -0.8 + 0.5 * rnd(); params[o + 2] = 0.6 + 0.7 * rnd(); params[o + 3] = 10 + Math.floor(rnd() * 7); }
    }
    for (i = 0; i < SCENES.length; i++) randomise(i);
    function startMix(n, now) {
      randomise(n);
      newPal = true;
      bakeDirty = true;
      if (n === cur) { sceneSince = now; beatCount = 0; return; }
      if (isLight(level)) { cur = n; next = -1; mix = 0; sceneSince = now; beatCount = 0; return; }
      next = n; mix = 0; mixStart = now;
    }
    function pickScene(now, music) {
      if (next >= 0) {
        mix = Math.min(1, (now - mixStart) / 1800);
        if (mix >= 1) { cur = next; next = -1; mix = 0; sceneSince = now; beatCount = 0; sceneBeats = 24 + Math.floor(rnd() * 25); }
        return;
      }
      if (forced >= 0) {
        if (music ? beatCount >= sceneBeats : now - sceneSince > 40000) startMix(forced, now);
        return;
      }
      if (music ? beatCount >= sceneBeats || now - sceneSince > 75000 : now - sceneSince > 35000) {
        var n;
        do n = Math.floor(rnd() * SCENES.length); while (n === cur || (lowp && n < 2));
        startMix(n, now);
      }
    }
    function updatePalette(now) {
      if (newPal) {
        newPal = false;
        palFrom.set(pal);
        var nxt = palette === 0 ? randomPalette(rnd) : fixedPalette(palette, opts.theme);
        for (var pi = 0; pi < 12; pi++) palTo[pi] = nxt[pi];
        palStart = now;
        if (palFrom[0] + palFrom[1] + palFrom[2] + palFrom[3] === 0) palFrom.set(palTo);
      }
      var k = Math.min(1, (now - palStart) / 1800);
      k = k * k * (3 - 2 * k);
      for (var j = 0; j < 12; j++) pal[j] = palFrom[j] + (palTo[j] - palFrom[j]) * k;
    }
    var cheapScale = cheapScaleFor(level, 1);
    function resize() {
      var dpr = root.devicePixelRatio || 1;
      var b = bufferSize(level, canvas.clientWidth || 1920, canvas.clientHeight || 1080, dpr, MAX_BUFFER_H);
      // Output/CSS stays at least 720p; the drawing buffer can be 1/2 or 1/3 and is linearly upscaled.
      var ih = internalHeight(b.h, cheapScale), iw = Math.max(1, Math.round(b.w * (ih / b.h)));
      if (canvas.width !== iw || canvas.height !== ih) { canvas.width = iw; canvas.height = ih; }
      gl.viewport(0, 0, canvas.width, canvas.height);
    }
    function applyLowPolicy() {
      capFps = true;
      var base = home ? HOME_FPS : sceneFps(gpu, ramGb);
      if (isLight(level)) {
        freezePalette = false; cheapScale = 1; iterMul = 1; fpsCap = base;
      } else if (level >= 3) {
        freezePalette = true;
        cheapScale = cheapScaleFor(level, cheapScale);
        if (fpsCap > CHEAP_FPS) fpsCap = CHEAP_FPS;
        if (iterMul > 0.55) iterMul = 0.55;
      } else if (pinned >= 0) {
        freezePalette = false; iterMul = 1; cheapScale = cheapScaleFor(level, 1); fpsCap = base;
      } else if (fpsCap < CHEAP_FPS) { /* kept by the watchdog after the 720p floor */ }
      else { fpsCap = base; if (level < 3) cheapScale = 1; }
    }
    var LU = null;
    function ensureLightProg() {
      if (lightProg) return true;
      lightProg = gl.createProgram();
      gl.attachShader(lightProg, sh(gl.VERTEX_SHADER, LIGHT_VERT));
      gl.attachShader(lightProg, sh(gl.FRAGMENT_SHADER, LIGHT_FRAG));
      gl.linkProgram(lightProg);
      if (!gl.getProgramParameter(lightProg, gl.LINK_STATUS)) { lightProg = null; return false; }
      LU = {
        uTex: gl.getUniformLocation(lightProg, "uTex"),
        uTime: gl.getUniformLocation(lightProg, "uTime"),
        uPulse: gl.getUniformLocation(lightProg, "uPulse"),
        uBass: gl.getUniformLocation(lightProg, "uBass"),
        uCycle: gl.getUniformLocation(lightProg, "uCycle"),
        uDim: gl.getUniformLocation(lightProg, "uDim"),
        uLevel: gl.getUniformLocation(lightProg, "uLevel"),
        uPal: gl.getUniformLocation(lightProg, "uPal"),
        uHave: gl.getUniformLocation(lightProg, "uHave"),
        aPos: gl.getAttribLocation(lightProg, "aPos")
      };
      return true;
    }
    function dropBake() {
      if (bakeFb) gl.deleteFramebuffer(bakeFb);
      if (bakeTex) gl.deleteTexture(bakeTex);
      bakeFb = bakeTex = null; bakeW = bakeH = 0; bakeDirty = true;
    }
    function bakeOnce() {
      var cssW = canvas.clientWidth || 1920, cssH = canvas.clientHeight || 1080;
      var bh = BAKE_H, bw = Math.max(1, Math.round(bh * (cssW / cssH)));
      if (!bakeTex || bakeW !== bw || bakeH !== bh) {
        dropBake();
        bakeTex = gl.createTexture();
        gl.bindTexture(gl.TEXTURE_2D, bakeTex);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGB, bw, bh, 0, gl.RGB, gl.UNSIGNED_BYTE, null);
        bakeFb = gl.createFramebuffer();
        gl.bindFramebuffer(gl.FRAMEBUFFER, bakeFb);
        gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, bakeTex, 0);
        if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) {
          gl.bindFramebuffer(gl.FRAMEBUFFER, null);
          dropBake();
          return false;
        }
        bakeW = bw; bakeH = bh;
      } else gl.bindFramebuffer(gl.FRAMEBUFFER, bakeFb);
      gl.viewport(0, 0, bakeW, bakeH);
      gl.useProgram(prog);
      gl.uniform2f(U.uRes, bakeW, bakeH);
      gl.uniform1f(U.uTime, 0);
      gl.uniform1f(U.uBass, 0); gl.uniform1f(U.uMid, 0); gl.uniform1f(U.uHigh, 0);
      gl.uniform1f(U.uLevel, 0.4); gl.uniform1f(U.uPulse, 0);
      gl.uniform1i(U.uScene, cur); gl.uniform1i(U.uScene2, cur); gl.uniform1f(U.uMix, 0);
      gl.uniform3fv(U.uPal, BAKE_PAL);
      gl.uniform1f(U.uCycle, 0);
      gl.uniform1f(U.uZoom, 2.6 * Math.exp(-dive));
      gl.uniform2f(U.uCenter, center[0], center[1]);
      gl.uniform1f(U.uIter, BAKE_ITERS);
      gl.uniform1f(U.uRot, 0); gl.uniform1f(U.uDim, 1); gl.uniform1f(U.uAA, 1);
      gl.uniform4fv(U.uP, params);
      gl.bindBuffer(gl.ARRAY_BUFFER, buf);
      gl.enableVertexAttribArray(aPos);
      gl.vertexAttribPointer(aPos, 2, gl.FLOAT, false, 0, 0);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
      bakeDirty = false;
      if (root.console) root.console.info("[Radiomarchy] light bake " + bakeW + "x" + bakeH + " scene=" + SCENES[cur]);
      return true;
    }
    function drawLight(now, pulse, t) {
      if (!lightProcedural && (bakeDirty || !bakeTex)) {
        if (!bakeOnce()) lightProcedural = true;
      }
      if (!ensureLightProg()) return;
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
      gl.viewport(0, 0, canvas.width, canvas.height);
      gl.useProgram(lightProg);
      gl.uniform1f(LU.uTime, t);
      gl.uniform1f(LU.uPulse, pulse);
      gl.uniform1f(LU.uBass, f.bass);
      gl.uniform1f(LU.uCycle, cycle);
      gl.uniform1f(LU.uDim, opts.dim == null ? 1 : opts.dim);
      gl.uniform1f(LU.uLevel, f.level);
      gl.uniform3fv(LU.uPal, pal);
      gl.uniform1i(LU.uHave, (bakeTex && !lightProcedural) ? 1 : 0);
      if (bakeTex) { gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, bakeTex); gl.uniform1i(LU.uTex, 0); }
      gl.bindBuffer(gl.ARRAY_BUFFER, buf);
      gl.enableVertexAttribArray(LU.aPos);
      gl.vertexAttribPointer(LU.aPos, 2, gl.FLOAT, false, 0, 0);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      gl.useProgram(prog);
    }
    function resetGrace() {
      graceStart = Date.now();
      presented = 0;
      lastRaf = 0;
      // keep stallTimes: focus/resume must not wipe the Sony persistent-stall history
    }
    function onUiStall(gap, now) {
      var elapsed = now - graceStart;
      var laid = (canvas.clientWidth || 0) > 0 && (canvas.clientHeight || 0) > 0;
      if (inGracePeriod(laid, presented, elapsed)) {
        if (root.console) root.console.info("[Radiomarchy] grace: ignore UI stall " + gap + "ms presented=" + presented + " elapsed=" + elapsed + "ms");
        return;
      }
      stallTimes = stallTimes.filter(function (t) { return now - t <= STALL_WINDOW_MS; });
      stallTimes.push(now);
      var action = afterGraceAction(level, gap, consecutiveSlow, stallTimes.length);
      if (action === STALL_KILL) {
        tripSafety("UI stall " + gap + "ms (" + stallTimes.length + " in 20s at " + LEVELS[level].name + ")");
        return;
      }
      if (isLight(level)) {
        if (root.console) root.console.info("[Radiomarchy] UI stall " + gap + "ms: light cap=" + fpsCap);
        return;
      }
      if (pinned < 0 && level < LEVELS.length - 1) {
        level++;
        applyLowPolicy();
        resize();
        if (root.console) root.console.info("[Radiomarchy] UI stall " + gap + "ms: drop to " + LEVELS[level].name + " cheap=" + cheapScale + " cap=" + fpsCap);
      } else {
        capFps = true;
        freezePalette = true;
        if (cheapScale < FBO_SCALE_CHEAP) cheapScale++;
        if (iterMul > 0.4) iterMul = 0.4;
        applyLowPolicy();
        resize();
        if (root.console) root.console.info("[Radiomarchy] UI stall " + gap + "ms: floor, reduce iters/fps cap=" + fpsCap + " cheap=" + cheapScale);
      }
    }
    function tripSafety(why) {
      if (sessionOff) return;
      sessionOff = true;
      running = false;
      if (raf) root.cancelAnimationFrame(raf);
      raf = 0;
      if (root.console) root.console.warn("[Radiomarchy] wallpaper off for this session: " + why);
      try { if (root.localStorage) root.localStorage.setItem("omarchytv.fractals.prefer_light", "1"); } catch (e) { /* private */ }
      if (opts.onFail) try { opts.onFail(why); } catch (e) { /* host */ }
    }
    function emitPalette(now) {
      if (!opts.onPalette) return;
      if (!freezePalette && lastPalEmit && now - lastPalEmit < 200) return;
      lastPalEmit = now;
      try { opts.onPalette(pal, freezePalette); } catch (e) { /* host */ }
    }
    function measure(now) {
      if (lastT) {
        var d = now - lastT;
        if (d < 200) {
          frameMs += d; frames++;
          if (isBackpressured(d, fpsCap)) { late++; lateInWindow++; } else late = 0;
        }
      }
      var rush = late >= LATE_RUSH && pinned < 0;
      if (frames < WATCH_FRAMES && !rush) return;
      var avg = frames ? frameMs / frames : 30;
      var pressure = rush || (frames > 0 && lateInWindow * 2 >= frames);
      if (frames >= 8 && (!lastFpsLog || now - lastFpsLog > 2000) && root.console) {
        lastFpsLog = now;
        root.console.info("[Radiomarchy] fps=" + (1000 / avg).toFixed(1) + " frame=" + avg.toFixed(1) + "ms level=" + LEVELS[level].name
          + " scale=" + scaleFor(level, canvas.clientHeight || 1080) + " buf=" + canvas.width + "x" + canvas.height
          + (capFps ? " cap=" + fpsCap : "") + (freezePalette ? " pal=static" : "") + (pressure ? " backpressure" : ""));
      }
      frames = 0; frameMs = 0; lateInWindow = 0;
      if (!rush) late = 0;
      if (pinned >= 0) { applyLowPolicy(); return; }
      if (pressure) {
        if (!isLight(level) && level < LIGHT_LEVEL) { level++; goodSince = now; slowWindows = 0; bakeDirty = true; applyLowPolicy(); resize(); }
        else {
          capFps = true;
          if (!isLight(level)) {
            freezePalette = true;
            if (cheapScale < FBO_SCALE_CHEAP) cheapScale++;
            if (iterMul > 0.4) iterMul = 0.4;
          }
          applyLowPolicy();
          resize();
          slowWindows++;
          if (shouldDisableAtFloor(level, slowWindows) || stallAction(level, slowWindows) === STALL_KILL) {
            tripSafety("GL frame " + avg.toFixed(1) + "ms at " + LEVELS[level].name);
          }
        }
      } else {
        slowWindows = 0;
        if (!goodSince) goodSince = now;
        if (now - goodSince > 20000) {
          var want = home ? HOME_FPS : sceneFps(gpu, ramGb);
          if (isLight(level)) { goodSince = now; }
          else if (cheapScale > 1) cheapScale--;
          else if (fpsCap < want || freezePalette || iterMul < 0.99) { freezePalette = false; iterMul = 1; }
          else if (level > 0) level--;
          goodSince = now; applyLowPolicy(); resize();
        }
      }
    }
    function frame() {
      if (!running || sessionOff) return;
      raf = root.requestAnimationFrame(frame);
      var now = Date.now();
      if (lastRaf && isUiStall(now - lastRaf)) onUiStall(now - lastRaf, now);
      lastRaf = now;
      if (sessionOff) return;
      if (capFps && fpsCap > 0 && lastDraw && now - lastDraw < 1000 / fpsCap) return;
      if (lastDraw) {
        var presentMs = now - lastDraw;
        var elapsed = now - graceStart;
        var laid = (canvas.clientWidth || 0) > 0 && (canvas.clientHeight || 0) > 0;
        if (!inGracePeriod(laid, presented, elapsed)) {
          if (isSlowPresent(presentMs)) consecutiveSlow++; else consecutiveSlow = 0;
          if (afterGraceAction(level, presentMs, consecutiveSlow, 0) === STALL_KILL) {
            tripSafety("present " + presentMs + "ms x" + consecutiveSlow + " at " + LEVELS[level].name);
            return;
          }
        }
      }
      lastDraw = now;
      presented++;
      var dt = lastT ? Math.min(0.1, (now - lastT) / 1000) : 0.016;
      measure(now);
      lastT = now;
      var music = opts.bands ? !!opts.bands(f) : false;
      if (!music) idle(now, f);
      var pulse = Math.exp(-Math.max(0, f.beatAgeMs) / 160);
      if (f.beats !== lastBeats) {
        if (lastBeats >= 0 && f.beats > lastBeats) { beatCount += f.beats - lastBeats; zoomKick = Math.min(1.5, zoomKick + 0.6); }
        lastBeats = f.beats;
      }
      pickScene(now, music);
      if (!freezePalette) updatePalette(now);
      emitPalette(now);
      cycle += dt * (0.03 + 0.22 * f.level + 0.25 * f.high) + pulse * dt * 0.6;
      zoomKick *= Math.exp(-dt * 3);
      dive += dt * diveSpeed * (0.09 + 0.22 * f.bass + 0.25 * zoomKick);
      if (dive > (lowp ? 4 : Math.log(2.6 / (2.4e-7 * canvas.height)))) { dive = 0.8 + 1.7 * rnd(); newCenter(); }
      var q = LEVELS[level], t = (now - t0) / 1000;
      resize();
      if (isLight(level)) { drawLight(now, pulse, t); return; }
      gl.uniform2f(U.uRes, canvas.width, canvas.height);
      gl.uniform1f(U.uTime, t);
      gl.uniform1f(U.uBass, f.bass);
      gl.uniform1f(U.uMid, f.mid);
      gl.uniform1f(U.uHigh, f.high);
      gl.uniform1f(U.uLevel, f.level);
      gl.uniform1f(U.uPulse, pulse);
      gl.uniform1i(U.uScene, cur);
      gl.uniform1i(U.uScene2, next < 0 ? cur : next);
      gl.uniform1f(U.uMix, next < 0 ? 0 : mix);
      gl.uniform3fv(U.uPal, pal);
      gl.uniform1f(U.uCycle, cycle);
      gl.uniform1f(U.uZoom, 2.6 * Math.exp(-dive));
      gl.uniform2f(U.uCenter, center[0], center[1]);
      gl.uniform1f(U.uIter, lowp ? 64 : Math.min(q.iter, q.iter * 0.55 + dive * 30) * iterMul);
      gl.uniform1f(U.uRot, rotDir * (t * 0.03 + dive * 0.15));
      gl.uniform1f(U.uDim, opts.dim == null ? 1 : opts.dim);
      gl.uniform1f(U.uAA, q.aa);
      gl.uniform4fv(U.uP, params);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    }
    return {
      start: function () { if (running || sessionOff) return; running = true; lastT = 0; frames = 0; frameMs = 0; resetGrace(); applyLowPolicy(); resize(); raf = root.requestAnimationFrame(frame); },
      stop: function () { running = false; if (raf) root.cancelAnimationFrame(raf); raf = 0; },
      /** One frame now and no loop (previews, tests). */
      draw: function () { var was = running; running = true; frame(); if (!was) this.stop(); },
      next: function (dir) {
        var n = ((forced >= 0 ? forced : cur) + (dir || 1) + SCENES.length) % SCENES.length;
        if (lowp) while (n < 2) n = (n + (dir || 1) + SCENES.length) % SCENES.length;
        forced = n;
        startMix(n, Date.now());
      },
      pin: function (s) { forced = s; if (s >= 0) startMix(s, Date.now()); },
      setPalette: function (p) { palette = Math.max(0, Math.min(PALETTES.length - 1, p | 0)); newPal = true; },
      setQuality: function (qq) { pinned = qq > 0 ? Math.min(4, qq) - 1 : -1; if (pinned >= 0) level = pinned; resize(); },
      setDim: function (d) { opts.dim = d; },
      scene: function () { return SCENES[cur]; },
      quality: function () { return LEVELS[level].name; },
      destroy: function () {
        this.stop();
        try { dropBake(); } catch (e) { /* gone */ }
        try { var lose = gl.getExtension("WEBGL_lose_context"); if (lose) lose.loseContext(); } catch (e) { /* gone already */ }
      }
    };
  }

  root.OmarchyFractals = {
    SCENES: SCENES, PALETTES: PALETTES, QUALITIES: QUALITIES, LEVELS: LEVELS, FRAG: FRAG,
    create: create, randomPalette: randomPalette, edgePoint: edgePoint, escape: escape, idle: idle, firstLevel: firstLevel,
    scaleFor: scaleFor, bufferHeight: bufferHeight, bufferSize: bufferSize, isBackpressured: isBackpressured,
    sceneFps: sceneFps, internalHeight: internalHeight, cheapScaleFor: cheapScaleFor,
    shouldDisableAtFloor: shouldDisableAtFloor, inGracePeriod: inGracePeriod, isUiStall: isUiStall,
    stallAction: stallAction, afterGraceAction: afterGraceAction, isLight: isLight, isCheapest: isCheapest,
    isLongStall: isLongStall, isSlowPresent: isSlowPresent, countStallsInWindow: countStallsInWindow,
    GRACE_FRAMES: GRACE_FRAMES, GRACE_MS: GRACE_MS, GRACE_MAX_MS: GRACE_MAX_MS, STALL_MS: STALL_MS, STALL_WINDOW_MS: STALL_WINDOW_MS,
    STALLS_TO_KILL: STALLS_TO_KILL, STALL_DEGRADE: STALL_DEGRADE, STALL_KILL: STALL_KILL,
    FATAL_STALL_MS: FATAL_STALL_MS, SLOW_PRESENT_MS: SLOW_PRESENT_MS, SLOW_PRESENTS_TO_KILL: SLOW_PRESENTS_TO_KILL,
    LIGHT_LEVEL: LIGHT_LEVEL, BAKE_H: BAKE_H, BAKE_ITERS: BAKE_ITERS,
    CHEAP_FPS: CHEAP_FPS, FBO_SCALE_FLOOR: FBO_SCALE_FLOOR, FBO_SCALE_CHEAP: FBO_SCALE_CHEAP,
    HOME_FPS: HOME_FPS, SAVER_FPS: SAVER_FPS,
    MAX_BUFFER_H: MAX_BUFFER_H, FLOOR_BUFFER_H: FLOOR_BUFFER_H,
    ensureContrast: ensureContrast, contrastRatio: contrastRatio, rgbCss: rgbCss
  };
})(typeof window !== "undefined" ? window : this);
