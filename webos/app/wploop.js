// OmarchyTV "Always-animated wallpaper" rule, shared by webOS/Tizen (app.js) and node tests. MIT, Marco Porro.
(function (root) {
  /** Loop only when the setting is on and nothing else is on screen (another app/video, Cinemarchy, screensaver). */
  function shouldPlay(s) {
    return !!(s && s.loop && !s.hidden && !s.saver && !s.cinemarchy);
  }
  var api = { shouldPlay: shouldPlay };
  root.OmarchyWpLoop = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof window !== "undefined" ? window : this);
