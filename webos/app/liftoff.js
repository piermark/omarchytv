/* OmarchyTV v2.14 Liftoff: a build or an update on the big screen (LG webOS / Samsung Tizen).
 * Same words and numbers as Android's LiftoffScript.java (webos/tests/liftoff.test.js checks both).
 * State from the PC bridge (/v1/status "liftoff") or the TV's own "Update via PC":
 * {kind build|update, name, state running|ok|failed, stage, elapsedS, expectedS?, pct?, code?, tail[]}. ES5. */
(function (root) {
  "use strict";
  var ROCKET = [
    "    /\\    ",
    "   /  \\   ",
    "  |    |  ",
    "  | OT |  ",
    "  |    |  ",
    " /|____|\\ ",
    "/_/ /\\ \\_\\"
  ];
  var FLAME = [["   .  .   ", "    ..    "], ["   )()(   ", "  ( )( )  "]];

  function pad2(n) { return (n < 10 ? "0" : "") + n; }
  function mmss(s) {
    s = Math.max(0, Math.floor(s));
    return s >= 3600 ? Math.floor(s / 3600) + ":" + pad2(Math.floor(s / 60) % 60) + ":" + pad2(s % 60)
      : pad2(Math.floor(s / 60)) + ":" + pad2(s % 60);
  }
  function num(v) { return typeof v === "number" && isFinite(v) ? v : 0; }
  function elapsed(s, since) {
    var e = Math.max(0, Math.floor(num(s.elapsedS)));
    return s.state === "running" ? e + Math.max(0, Math.floor(num(since))) : e;
  }
  function clock(s, since) {
    if (s.state === "failed") return "ABORT";
    var e = elapsed(s, since), exp = Math.floor(num(s.expectedS));
    if (s.state === "running" && exp > 0 && e < exp) return "T-" + mmss(exp - e);
    return "T+" + mmss(e);
  }
  function progress(s, since) {
    if (s.state === "ok") return 100;
    if (typeof s.pct === "number") return Math.max(0, Math.min(99, Math.floor(s.pct)));
    var exp = Math.floor(num(s.expectedS));
    if (exp <= 0) return -1;
    return Math.min(99, Math.floor(elapsed(s, since) * 100 / exp));
  }
  function bar(pct, width) {
    if (pct < 0) return "";
    var n = Math.round(pct * width / 100), out = "[";
    for (var i = 0; i < width; i++) out += i < n ? "█" : "░";
    return out + "] " + pct + "%";
  }
  function finalCount(s, since) {
    var exp = Math.floor(num(s.expectedS)), left = exp - elapsed(s, since);
    return s.state === "running" && exp > 0 && left > 0 && left <= 10;
  }
  /** A short alarm: three falling square-wave beeps (WebAudio; silent where the TV has none). */
  function alarm() {
    try {
      var AC = root.AudioContext || root.webkitAudioContext;
      if (!AC) return;
      var ctx = new AC(), t = ctx.currentTime;
      for (var i = 0; i < 3; i++) {
        var o = ctx.createOscillator(), g = ctx.createGain();
        o.type = "square";
        o.frequency.setValueAtTime(880, t + i * 0.5);
        o.frequency.linearRampToValueAtTime(440, t + i * 0.5 + 0.35);
        g.gain.setValueAtTime(0.12, t + i * 0.5);
        g.gain.linearRampToValueAtTime(0, t + i * 0.5 + 0.4);
        o.connect(g); g.connect(ctx.destination);
        o.start(t + i * 0.5); o.stop(t + i * 0.5 + 0.42);
      }
      setTimeout(function () { try { ctx.close(); } catch (e) {} }, 2200);
    } catch (e) {}
  }
  var api = { ROCKET: ROCKET, FLAME: FLAME, mmss: mmss, elapsed: elapsed, clock: clock, progress: progress, bar: bar,
    finalCount: finalCount, alarm: alarm };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  root.OmarchyLiftoff = api;
})(typeof window !== "undefined" ? window : this);
