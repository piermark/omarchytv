/*
 * OmarchyTV 2.15 (#131, idea and first version by @XanderHalloran): on an unrooted LG TV OmarchyTV runs in Developer
 * Mode, whose session lasts 1000 hours; when it runs out the TV removes the app. A sandboxed app can't read LG's
 * timer, so the "dev mode" widget counts to the date you last confirmed (Widgets › "dev mode · I just pressed
 * EXTEND"). Days left; hours under 48 h; yellow under 14 days, red under 7. No network, no permissions. ES5.
 */
(function (root) {
  "use strict";
  var SESSION_MS = 1000 * 3600 * 1000;
  /** {text, level: "ok"|"warn"|"crit"|"unset"|"over"} for an expiry (epoch ms, 0 = never confirmed) at now. */
  function left(until, now) {
    if (!until) return { text: "press EXTEND in the Developer Mode app, then confirm it here", level: "unset" };
    var ms = until - now;
    if (ms <= 0) return { text: "expired: open Developer Mode and press EXTEND", level: "over" };
    var h = Math.floor(ms / 3600000), d = Math.floor(h / 24);
    var date = new Date(until), dd = date.getDate(), mm = date.getMonth() + 1;
    var on = (dd < 10 ? "0" : "") + dd + "/" + (mm < 10 ? "0" : "") + mm;
    return { text: (h < 48 ? h + " h" : d + " days") + " left · until " + on, level: d < 7 ? "crit" : d < 14 ? "warn" : "ok" };
  }
  function extended(now) { return now + SESSION_MS; }
  root.OmarchyDevMode = { SESSION_MS: SESSION_MS, left: left, extended: extended };
  if (typeof module !== "undefined" && module.exports) module.exports = root.OmarchyDevMode;
})(typeof window !== "undefined" ? window : this);
