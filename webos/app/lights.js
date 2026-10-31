/* OmarchyTV (webOS / Tizen) · lights hub leave policy, v2.14. The Hue / Home Assistant lights carry the theme
 * colour only while OmarchyTV is on screen. Leaving (another app, standby) turns them off (default), restores
 * the state found before OmarchyTV painted them, or keeps them. Pure functions, tested by webos/tests/lights.test.js;
 * the network calls live in app.js. Same rules as the Android launcher's LightsLeave. */
(function (root) {
  "use strict";
  var POLICIES = ["off", "restore", "keep"];

  function policy(v) { return POLICIES.indexOf(v) >= 0 ? v : "off"; }
  function next(v) { return POLICIES[(POLICIES.indexOf(policy(v)) + 1) % POLICIES.length]; }
  /** "none", "restore" or "off": restore without a snapshot falls back to off. */
  function action(p, haveSnap) {
    p = policy(p);
    if (p === "keep") return "none";
    if (p === "restore") return haveSnap ? "restore" : "off";
    return "off";
  }
  function takeSnapshot(p, haveSnap) { return policy(p) === "restore" && !haveSnap; }

  /** Hue: GET /groups/<id> + GET /lights → {id: {on, bri, xy|ct|hue+sat}} for the group's lights. */
  function hueSnapshot(group, lights) {
    var out = {}, ids = (group && group.lights) || [];
    ids.forEach(function (id) {
      var st = lights && lights[id] && lights[id].state;
      if (!st) return;
      var s = { on: !!st.on };
      if (typeof st.bri === "number") s.bri = st.bri;
      if (st.colormode === "xy" && st.xy) s.xy = st.xy;
      else if (st.colormode === "ct" && typeof st.ct === "number") s.ct = st.ct;
      else if (st.colormode === "hs" && typeof st.hue === "number") { s.hue = st.hue; s.sat = st.sat; }
      out[id] = s;
    });
    return out;
  }
  function hueRestoreBody(s) {
    var b = {};
    if (s.on) ["bri", "xy", "ct", "hue", "sat"].forEach(function (k) { if (s[k] !== undefined) b[k] = s[k]; });
    b.on = !!s.on;
    b.transitiontime = 8;
    return b;
  }

  /** Home Assistant: one GET /api/states/<entity> → {on, brightness, rgb_color|color_temp_kelvin}. */
  function haState(o) {
    var a = (o && o.attributes) || {}, s = { on: !!o && o.state === "on" };
    if (typeof a.brightness === "number") s.brightness = a.brightness;
    if ((a.color_mode === "color_temp" || !a.rgb_color) && typeof a.color_temp_kelvin === "number") s.color_temp_kelvin = a.color_temp_kelvin;
    else if (a.rgb_color) s.rgb_color = a.rgb_color;
    return s;
  }
  function haRestoreCall(entity, s) {
    var b = { entity_id: entity };
    if (!s.on) return { service: "turn_off", body: b };
    ["brightness", "rgb_color", "color_temp_kelvin"].forEach(function (k) { if (s[k] !== undefined) b[k] = s[k]; });
    b.transition = 1;
    return { service: "turn_on", body: b };
  }

  var api = { POLICIES: POLICIES, policy: policy, next: next, action: action, takeSnapshot: takeSnapshot,
    hueSnapshot: hueSnapshot, hueRestoreBody: hueRestoreBody, haState: haState, haRestoreCall: haRestoreCall };
  root.OmarchyLights = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof window !== "undefined" ? window : this);
