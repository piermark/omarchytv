/* OmarchyTV (webOS / Tizen) · hooks for other "browser on a TV" platforms, v2.14 (issue #66, Vestel/Novatek
 * report by ekollof1). A platform script loaded before app.js may set:
 *   window.OMARCHY_LAUNCH(app)        open an app tile its own way (return false to fall back to webOS/Tizen)
 *   window.OMARCHY_PREFERS_24H        true/false: what Clock "auto" means when the browser language misleads
 *   window.OMARCHY_FIT_SCREEN         true: size the stage from screen / devicePixelRatio (for browsers whose
 *                                     innerWidth ignores the real viewport)
 * None is set on webOS or Tizen, so nothing changes there. Pure functions, tested by webos/tests/hooks.test.js. */
(function (root) {
  "use strict";

  /** Clock: "24", "12", or "auto" (24 h unless English, or the platform's own preference when it gives one). */
  function clock24(format, lang, prefers24) {
    if (format === "24") return true;
    if (format === "12") return false;
    if (prefers24 === true || prefers24 === false) return prefers24;
    return lang !== "en";
  }

  /** Scale of the 1920×1080 stage. With fitScreen, the smaller of the window and screen / devicePixelRatio. */
  function stageScale(innerW, innerH, fitScreen, screenW, screenH, dpr) {
    var w = innerW, h = innerH;
    if (fitScreen && screenW > 0 && screenH > 0) {
      var d = dpr > 0 ? dpr : 1;
      w = Math.min(w, screenW / d);
      h = Math.min(h, screenH / d);
    }
    var s = Math.min(w / 1920, h / 1080);
    return s > 0 && isFinite(s) ? s : 1;
  }

  var api = { clock24: clock24, stageScale: stageScale };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.OmarchyHooks = api;
})(typeof window !== "undefined" ? window : this);
