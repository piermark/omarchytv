// OmarchyTV "Always-animated wallpaper" rule, shared by webOS/Tizen (app.js) and node tests. MIT, Marco Porro.
(function (root) {
  /** Loop only when the setting is on and nothing else is on screen (another app/video, Cinemarchy, screensaver). */
  function shouldPlay(s) {
    return !!(s && s.loop && !s.hidden && !s.saver && !s.cinemarchy);
  }
  /**
   * v2.21 a looping wallpaper the TV cannot keep up with: given the video's cumulative decoded and dropped frame
   * counts sampled once a second, after a 3 s warm-up, a 10 s window that shows fewer than half the clip's frames
   * (or drops more than half) says stop. g = {} kept by the caller; returns true once.
   */
  function stutter(g, nowMs, decoded, dropped, fps) {
    if (g.tripped) return false;
    if (g.start == null) g.start = nowMs;
    if (nowMs - g.start < 3000) return false;
    if (g.w == null) { g.w = nowMs; g.d = decoded; g.x = dropped; return false; }
    var span = nowMs - g.w;
    if (span < 10000) return false;
    var shown = (decoded - g.d) - (dropped - g.x), drop = dropped - g.x, total = decoded - g.d;
    g.w = nowMs; g.d = decoded; g.x = dropped;
    if (total <= 0) return false;   // paused (not on Home): nothing to judge
    var slow = shown < (fps || 24) * (span / 1000) * 0.5 || drop > total * 0.5;
    if (slow) g.tripped = true;
    return slow;
  }
  var api = { shouldPlay: shouldPlay, stutter: stutter };
  root.OmarchyWpLoop = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof window !== "undefined" ? window : this);
