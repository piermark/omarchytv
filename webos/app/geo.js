/* OmarchyTV (webOS / Tizen) · "Automatic (from IP)" weather place, v2.14.
 * Same services as the Android launcher (Weather.java): https://ipapi.co/json/, fallback https://ipwho.is/, no key.
 * Only the city and its coordinates are kept. If both fail, the caller keeps the current city.
 * Pure functions, also loaded by webos/tests/geo.test.js under Node. */
(function (root) {
  "use strict";
  var SOURCES = ["https://ipapi.co/json/", "https://ipwho.is/"];

  function num(v) {
    var n = typeof v === "string" ? parseFloat(v) : v;
    return typeof n === "number" && isFinite(n) ? n : NaN;
  }

  /** {city, lat, lon} from an ipapi.co or ipwho.is answer, or null. */
  function parse(j) {
    if (!j || typeof j !== "object" || j.error === true || j.success === false) return null;
    var lat = num(j.latitude), lon = num(j.longitude);
    if (isNaN(lat) || isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180 || (lat === 0 && lon === 0)) return null;
    var city = typeof j.city === "string" ? j.city.replace(/[\u0000-\u001f<>]/g, "").trim().slice(0, 60) : "";
    return { city: city, lat: Math.round(lat * 1000) / 1000, lon: Math.round(lon * 1000) / 1000 };
  }

  /** Tries each source in turn. getJson(url) returns a Promise of the parsed JSON. Resolves to a place or null. */
  function locate(getJson) {
    var i = 0;
    function next() {
      if (i >= SOURCES.length) return Promise.resolve(null);
      var url = SOURCES[i++];
      return Promise.resolve().then(function () { return getJson(url); })
        .then(function (j) { return parse(j) || next(); }, next);
    }
    return next();
  }

  var api = { SOURCES: SOURCES, parse: parse, locate: locate };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.OmarchyGeo = api;
})(typeof window !== "undefined" ? window : this);
