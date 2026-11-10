/*
 * OmarchyTV 2.15: the bridge between Pulse's own page (pulse.js, unchanged) and the TV. pulse.js asks its hub for
 * /api/state every second and listens to /api/live for effects (liftoff, the ISS, fireworks); here both are answered by
 * pulse-tv-hub.js, running in this page. Settings come from the host: Android's OmarchySaver.pulse(), or the
 * #cfg=<json> of the URL (LG and Samsung open this page in a frame). Nothing here talks to the PC directly: a paired
 * PC's state comes through the host (OmarchySaver.pcPulse()), already fetched by OmarchyTV's link.
 * ES5, loaded before pulse.js. MIT, Marco Porro.
 */
(function (root) {
  "use strict";
  var host = root.OmarchySaver || null;
  function log(m) { try { if (host && host.log) host.log("pulse: " + m); } catch (e) {} }
  function readCfg() {
    var c = {};
    try { if (host && host.pulse) c = JSON.parse(host.pulse()) || {}; } catch (e) { log("cfg " + e); }
    try {
      var h = /[#&]cfg=([^&]+)/.exec(location.hash || "");
      if (h) { var j = JSON.parse(decodeURIComponent(h[1])), k; for (k in j) if (j.hasOwnProperty(k)) c[k] = j[k]; }
    } catch (e) { log("hash " + e); }
    return c;
  }
  var cfg = readCfg();

  // Old TV browsers (webOS 3/4): what pulse.js needs, and nothing more.
  if (!root.URLSearchParams) {
    root.URLSearchParams = function (s) {
      var m = {};
      String(s || "").replace(/^\?/, "").split("&").forEach(function (p) { if (!p) return; var i = p.indexOf("="); m[decodeURIComponent(i < 0 ? p : p.slice(0, i))] = i < 0 ? "" : decodeURIComponent(p.slice(i + 1)); });
      this.get = function (k) { return k in m ? m[k] : null; };
    };
  }

  // The network, for the hub: XHR (works on every TV; Android's saver page routes these through the launcher).
  function net(url) {
    return new Promise(function (ok, fail) {
      var x = new XMLHttpRequest();
      x.open("GET", url, true);
      x.timeout = 20000;
      x.onload = function () {
        if (x.status < 200 || x.status >= 300) { fail(new Error("HTTP " + x.status)); return; }
        try { ok(JSON.parse(x.responseText)); } catch (e) { fail(e); }
      };
      x.onerror = x.ontimeout = function () { fail(new Error("network")); };
      x.send();
    });
  }
  function tv() {
    try { return host && host.tvStats ? JSON.parse(host.tvStats()) : null; } catch (e) { return null; }
  }
  // The paired PC's Pulse: Android hands it over (OmarchySaver.pcPulse); LG and Samsung post it into this frame.
  var fromParent = null, fromParentAt = 0;
  root.addEventListener("message", function (ev) {
    var d = ev && ev.data;
    if (d && d.pulseTV === "pc" && d.state && d.state.v) { fromParent = d.state; fromParentAt = Date.now(); }
  });
  function pc() {
    if (host && host.pcPulse) {
      try { var s = host.pcPulse(); return Promise.resolve(s ? JSON.parse(s) : null); } catch (e) { return Promise.resolve(null); }
    }
    return Promise.resolve(fromParent && Date.now() - fromParentAt < 30000 ? fromParent : null);
  }
  // 2.15.1 how rich Pulse draws on this TV (screensaver.pulseGraphics): "low" is the plain look at half resolution,
  // "medium" Pulse 1.8's look at half resolution, "high" the 1.8 look at full resolution. "auto" (default): the 1.8
  // look unless the TV is a small one (the launcher says so, or its browser is an old one), the resolution chosen by
  // Pulse itself; and if frames still take too long, the plain look comes back by itself (see /api/perf below).
  var chrome = /Chrome\/(\d+)/.exec(navigator.userAgent || ""), oldEngine = !chrome || Number(chrome[1]) < 70;
  var gfx = { low: 1, medium: 1, high: 1 }[cfg.graphics] ? cfg.graphics : "auto";
  var polish = gfx === "auto" ? !(cfg.lowEnd || oldEngine) : gfx !== "low";
  var quality = gfx === "low" || gfx === "medium" ? "half" : gfx === "high" ? "full" : cfg.quality || "auto";
  // Pulse 1.8's scenes in code (mars, monolith) run in a sandboxed frame that pulse.js asks its hub for (/scene/NAME);
  // on the TV that frame is a page beside this one. Where the browser can't redirect it, the sphere stays.
  var scenesOk = false;
  if (cfg.scenes !== false) try {   // rewrite /scene/NAME to the page beside this one (or the frame can navigate the app)
    // LG/Samsung only: pulse.js sets sandbox="allow-scripts" and not allow-same-origin. On file:// that makes an
    // opaque origin ("://") and webOS Chromium's renderer dies (Inspector.detached, target_closed). Keep
    // allow-scripts and add allow-same-origin so the scene stays file:// like this page; do not add
    // allow-top-navigation. Android has OmarchySaver (host) and is left alone.
    if (!host && HTMLIFrameElement && HTMLIFrameElement.prototype.setAttribute) {
      var setAttr = HTMLIFrameElement.prototype.setAttribute;
      HTMLIFrameElement.prototype.setAttribute = function (name, value) {
        if (String(name).toLowerCase() === "sandbox") value = "allow-scripts allow-same-origin";
        return setAttr.call(this, name, value);
      };
    }
    var ifd = Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype, "src");
    if (ifd && ifd.set && ifd.configurable) {
      Object.defineProperty(HTMLIFrameElement.prototype, "src", { configurable: true, enumerable: ifd.enumerable, get: ifd.get,
        set: function (v) { var m = /^\/scene\/(mars|monolith)\?/.exec(String(v)); ifd.set.call(this, m ? "scene-" + m[1] + ".html" : v); } });
      scenesOk = true;
    }
  } catch (e) { log("scenes " + e); }
  var hub = root.OmarchyPulseTV.create({
    lat: cfg.lat, lon: cfg.lon, city: cfg.city || "", imperial: !!cfg.imperial, theme: cfg.theme, quality: quality, polish: polish, scenes: scenesOk,
    motion: cfg.motion, scene: cfg.scene || "sphere", clock: cfg.clock, launches: cfg.launches, planes: cfg.planes, starlink: cfg.starlink,
    planesNm: cfg.planesNm, earthquakes: cfg.earthquakes, opensky: !!cfg.opensky, openskyAuth: !!cfg.openskyAuth, racing: cfg.racing,
    tv: tv, pc: pc
  }, net);
  root.__pulseTV = hub;

  // /api/* answered here. Anything else goes to the real fetch (none of Pulse's own requests do).
  var realFetch = root.fetch, slowRuns = 0;
  function resp(obj) {
    return { ok: true, status: 200, json: function () { return Promise.resolve(obj); }, text: function () { return Promise.resolve(JSON.stringify(obj)); } };
  }
  root.fetch = function (url, opts) {
    var u = String(url || "");
    if (u.indexOf("/api/state") === 0) return Promise.resolve(resp(hub.state()));
    if (u.indexOf("/api/perf") === 0) {   // pulse.js reports how it runs: three slow reports in a row, and the plain look is back
      try {
        var pf = JSON.parse((opts && opts.body) || "{}"), slow = pf.draw > 16 || (pf.want && pf.fps < pf.want * 0.6);
        slowRuns = slow ? slowRuns + 1 : 0;
        if (gfx === "auto" && slowRuns >= 3 && hub.polish()) { hub.setPolish(false); log("slow (" + pf.fps + " fps, " + pf.draw + " ms): the plain look"); }
      } catch (e) {}
      return Promise.resolve(resp({ ok: true }));
    }
    if (u.indexOf("/api/visibility") === 0) return Promise.resolve(resp({ mons: {}, pause_when_covered: false }));
    if (u.indexOf("/api/") === 0) return Promise.resolve(resp({ ok: true }));
    return realFetch ? realFetch.apply(root, arguments) : Promise.reject(new Error("no fetch"));
  };

  // /api/live: the effects (liftoff, the ISS overhead, a Starlink train...) as the hub's server-sent events.
  var streams = [];
  root.EventSource = function () { this.onmessage = null; this.close = function () {}; streams.push(this); };
  hub.onFx(function (f) {
    streams.forEach(function (es) { try { if (es.onmessage) es.onmessage({ data: JSON.stringify({ fx: [f] }) }); } catch (e) {} });
    try { if (host && host.fx) host.fx(JSON.stringify(f)); } catch (e) {}   // the launcher can wake the TV for it (2.15 "Look up")
    try { if (root.parent && root.parent !== root) root.parent.postMessage({ pulseTV: "fx", fx: f }, "*"); } catch (e) {}
  });

  hub.start();
  // Tell the host the first frame is up (Android reveals the page only then; LG/Samsung hide their placeholder).
  setTimeout(function () {
    try { if (host && host.drawn) host.drawn("pulse", 1, "pulse"); } catch (e) {}
    try { if (root.parent && root.parent !== root) root.parent.postMessage({ pulseTV: "drawn" }, "*"); } catch (e) {}
  }, 1200);
  log("started at " + (cfg.city || "?") + ", " + (cfg.lat != null ? Number(cfg.lat).toFixed(2) + "," + Number(cfg.lon).toFixed(2) : "no location"));
})(window);
