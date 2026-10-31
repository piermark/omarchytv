// LG webOS: turn the TV's own launch points (SSAP ssap://com.webos.applicationManager/listLaunchPoints) into
// launcher tiles. Pure functions only, no DOM or network, so node tests can load this file (webos/tests/tvapps.test.js).
(function (root) {
  "use strict";
  var SELF = "com.omarchytv.home";
  var APPID = /^[A-Za-z0-9._-]{1,128}$/;

  // Icons: the TV serves launch point icons over its SSAP server (http(s)://<tv>:3000|3001/resources/...) and
  // some entries also carry local file paths. Anything else (quotes, other hosts, ..) is dropped.
  function iconUrl(p) {
    if (typeof p !== "string" || !p || p.length > 1024 || /[\x00-\x1f"'<>\\\s]|\/\.\.(\/|$)/.test(p)) return undefined;
    if (/^https?:\/\/[A-Za-z0-9.:\[\]-]+:300[01]\/resources\//.test(p)) return p;
    if (/^(file:\/\/)?\/[^\/]/.test(p)) return p;
    return undefined;
  }
  function icons(lp) {
    var out = [];
    [lp.largeIcon, lp.icon, lp.extraLargeIcon, lp.mediumLargeIcon].forEach(function (p) {
      p = iconUrl(p);
      if (p && out.indexOf(p) < 0) out.push(p);
    });
    return out.length ? out : undefined;
  }
  function clean(s) { return String(s).replace(/[<>&"]/g, "").replace(/\s+/g, " ").trim().slice(0, 60); }
  function norm(s) { return String(s || "").toLowerCase().replace(/[^a-z0-9]/g, ""); }

  // launchPoints -> tiles. defaults: the built-in DEFAULT_APPS. A default whose appId (or name) is installed keeps its
  // brand styling as the fallback, takes the TV's real appId and icon; defaults the TV doesn't have are dropped,
  // except "tv" and "settings" which webOS always has. Other launch points follow, in the TV's own order, with their icons.
  // Returns null for an invalid or empty list, so the caller keeps DEFAULT_APPS.
  function merge(defaults, launchPoints, opts) {
    opts = opts || {};
    if (!Array.isArray(launchPoints)) return null;
    var lps = [], seen = {};
    for (var i = 0; i < launchPoints.length; i++) {
      var lp = launchPoints[i];
      if (!lp || typeof lp.id !== "string" || !APPID.test(lp.id) || typeof lp.title !== "string" || !clean(lp.title)) continue;
      if (lp.id === (opts.self || SELF) || lp.visible === false || lp.hidden === true) continue;
      var key = lp.launchPointId || lp.id;
      if (seen[key]) continue;
      seen[key] = true;
      lps.push(lp);
    }
    if (!lps.length) return null;
    var used = {}, out = [];
    (defaults || []).forEach(function (d) {
      var hit = null;
      for (var j = 0; j < lps.length && !hit; j++) if (!used[j] && lps[j].id === d.appId) hit = j;
      for (j = 0; j < lps.length && hit === null; j++) if (!used[j] && norm(lps[j].title) === norm(d.name)) hit = j;
      if (hit !== null) {
        used[hit] = true;
        var c = {}; for (var k in d) c[k] = d[k];
        c.appId = lps[hit].id;
        var ic = icons(lps[hit]);
        if (ic && d.brand !== "tv") { c.iconPaths = ic; c.lgIcon = true; }   // the TV's real icon; the styled tile is its fallback
        out.push(c);
      } else if (d.id === "tv" || d.id === "settings") out.push(d);   // webOS always has these

    });
    var ids = {};
    out.forEach(function (a) { ids[a.id] = true; });
    lps.forEach(function (lp, j) {
      if (used[j]) return;
      var id = "lg." + lp.id + (lp.lptype && lp.lptype !== "default" && lp.launchPointId ? "." + lp.launchPointId : "");
      if (ids[id]) return;
      ids[id] = true;
      var t = { id: id, name: clean(lp.title), appId: lp.id, brand: id, tvApp: true, iconPaths: icons(lp) };
      if (typeof lp.iconColor === "string" && /^#[0-9A-Fa-f]{6}$/.test(lp.iconColor)) t.bg = lp.iconColor;
      if (lp.params && typeof lp.params === "object" && Object.keys(lp.params).length) t.params = lp.params;
      out.push(t);
    });
    return out;
  }

  var api = { merge: merge, iconUrl: iconUrl };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  root.OmarchyTvApps = api;
})(typeof window !== "undefined" ? window : this);
