/* OmarchyTV host wrapper around the vendored Pulse page (2.20).
 * The Pulse engine (pulse-tv.js, pulse.js, vendor/pulse/*) is left unchanged.
 * This script:
 *   - exposes the real devicePixelRatio to Pulse so the scene canvas (and plane
 *     labels drawn on it) render at full device resolution while the TV keeps up;
 *   - CSS-upscales when the frame-time watchdog steps down, never below a 720p floor;
 *   - caps requestAnimationFrame (30 fps at high, lower after stutter) so the host
 *     does not add a second rAF loop on top of the engine;
 *   - sets 2d imageSmoothing for the upscale.
 * ES5. Loaded by pulse-host.html (LG/Samsung) or injected by TteSaver (Android).
 */
(function (root) {
  "use strict";
  var FLOOR = 720;
  var TAG = "[OmarchyPulse]";
  var FPS_HIGH = 30;
  var FPS_FLOOR = 20;
  var FPS_MIN = 15;

  function scaleFloor(cssH) {
    var h = cssH > 0 ? cssH : 1080;
    return Math.min(1, FLOOR / h);
  }

  /** Best known device pixel ratio: the page's, a host hint, or physical pixels / CSS size. */
  function effectiveDpr(win) {
    win = win || root;
    var reported = 1;
    try { reported = (win.__omarchyNativeDpr > 0 ? win.__omarchyNativeDpr : (win.devicePixelRatio || 1)); } catch (e) {}
    var hint = 0;
    try { hint = win.__omarchyDpr || 0; } catch (e) {}
    var fromPx = 0;
    try {
      var px = win.__omarchyDevicePixels;
      if (px && px.w && px.h) {
        var iw = win.innerWidth || px.w, ih = win.innerHeight || px.h;
        if (iw > 0 && ih > 0) fromPx = Math.max(px.w / iw, px.h / ih);
      }
    } catch (e) {}
    var d = Math.max(reported, hint, fromPx, 1);
    try {
      if (win.visualViewport && win.visualViewport.scale) d *= win.visualViewport.scale;
    } catch (e) {}
    return d;
  }

  /**
   * Backing store for the scene canvas. policyScale 1 = high look (CSS × dpr, full device
   * pixels). Below that, 720p is the floor. The engine's request is never shaved down to 1× CSS
   * while high — that made the scene and plane labels soft while the DOM panels stayed sharp.
   */
  function clampBacking(reqW, reqH, cssH, policyScale, dpr, cssW) {
    var hCss = cssH > 0 ? cssH : (reqH > 0 ? reqH : 1080);
    var wCss = cssW > 0 ? cssW : (reqW > 0 && reqH > 0 ? reqW * (hCss / reqH) : hCss * 16 / 9);
    var d = dpr > 0 ? dpr : 1;
    var highH = Math.max(1, Math.round(hCss * d));
    var floorH = Math.min(highH, Math.max(1, FLOOR));
    var high = policyScale == null || policyScale >= 0.999;
    var wantH = high ? highH : floorH;
    var wantW = Math.max(1, Math.round(wCss * (wantH / hCss)));
    return { w: wantW, h: wantH, scale: wantH / hCss, floor: floorH / highH, dpr: d, high: high };
  }

  function stepPolicy(s, frameMs) {
    s = s || { scale: 1, slow: 0, fast: 0, floor: scaleFloor(1080), fps: FPS_HIGH };
    var floor = s.floor != null ? s.floor : scaleFloor(1080);
    var out = { scale: s.scale == null ? 1 : s.scale, slow: 0, fast: 0, floor: floor, fps: s.fps || FPS_HIGH, changed: "" };
    if (frameMs > 21) { out.slow = (s.slow || 0) + 1; out.fast = 0; }
    else if (frameMs < 11) { out.fast = (s.fast || 0) + 1; out.slow = 0; }
    else { return out; }
    if (out.slow >= 3) {
      out.slow = 0;
      if (out.scale > floor + 0.001) { out.scale = floor; out.fps = FPS_FLOOR; out.changed = "down"; }
      else if (out.fps > FPS_MIN) { out.fps = FPS_MIN; out.changed = "fps"; }
    } else if (out.fast >= 6) {
      out.fast = 0;
      if (out.fps < FPS_FLOOR && out.scale <= floor + 0.001) { out.fps = FPS_FLOOR; out.changed = "fps"; }
      else if (out.scale < 1) { out.scale = 1; out.fps = FPS_HIGH; out.changed = "up"; }
    }
    return out;
  }

  function log() {
    try {
      if (root.console && root.console.info) root.console.info(TAG + " " + Array.prototype.join.call(arguments, " "));
    } catch (e) { /* no console */ }
  }

  function install(win) {
    win = win || root;
    if (!win || !win.HTMLCanvasElement) return;
    if (win.__omarchyPulseAdapt) return win.__omarchyPulseAdapt;
    var proto = win.HTMLCanvasElement.prototype;
    var dW = Object.getOwnPropertyDescriptor(proto, "width");
    var dH = Object.getOwnPropertyDescriptor(proto, "height");
    var cssH0 = win.innerHeight || 1080;
    var policy = { scale: 1, slow: 0, fast: 0, floor: scaleFloor(cssH0), fps: FPS_HIGH };
    var late = 0, accMs = 0, accN = 0, lastT = 0, lastLog = 0, lastRun = 0;

    try { if (win.__omarchyNativeDpr == null) win.__omarchyNativeDpr = win.devicePixelRatio || 1; } catch (e) {}

    function realDpr() { return effectiveDpr(win); }

    function policyDpr() {
      var cssH = win.innerHeight || 1080;
      if (policy.scale < 0.999) return Math.max(FLOOR / cssH, 0.25);
      return realDpr();
    }

    try {
      Object.defineProperty(win, "devicePixelRatio", {
        configurable: true, enumerable: true,
        get: function () { return policyDpr(); }
      });
    } catch (e) { /* some TVs freeze dpr */ }

    function fireResize() {
      try { win.dispatchEvent(new win.Event("resize")); } catch (e) {
        try { var ev = win.document.createEvent("Event"); ev.initEvent("resize", true, true); win.dispatchEvent(ev); } catch (e2) {}
      }
    }

    function applyPolicy() {
      try {
        var hub = win.__pulseTV;
        if (hub && hub.setQuality) hub.setQuality("full");
      } catch (e) {}
      fireResize();
    }

    function hookQuality() {
      try {
        var hub = win.__pulseTV;
        if (!hub || !hub.setQuality || hub.setQuality.__omarchyPulse) return;
        var orig = hub.setQuality.bind(hub);
        hub.setQuality = function () { return orig("full"); };
        hub.setQuality.__omarchyPulse = true;
        orig("full");
      } catch (e) {}
    }

    function enforce(cv) {
      if (!cv || cv.__pulseAdaptLock) return;
      if (cv.id && cv.id !== "scene" && cv.id !== "c") return;
      var cssW = cv.clientWidth || cv.offsetWidth || win.innerWidth || 1920;
      var cssH = cv.clientHeight || cv.offsetHeight || win.innerHeight || 1080;
      var cl = clampBacking(cv.width, cv.height, cssH, policy.scale, policyDpr(), cssW);
      if (cl.w === cv.width && cl.h === cv.height) return;
      /* Don't assign a new backing store here if Pulse already stored W/H from the previous
         set: changing only the attribute would desync drawing (labels in a corner). Nudge a
         resize so Pulse recomputes W/H from the (possibly policy-adjusted) devicePixelRatio. */
      if (Math.abs(cv.height - cl.h) > 2 || Math.abs(cv.width - cl.w) > 2) fireResize();
    }

    function scan() {
      try {
        var list = win.document && win.document.getElementsByTagName("canvas");
        if (!list) return;
        for (var i = 0; i < list.length; i++) enforce(list[i]);
      } catch (e) {}
    }

    function smoothCtx(ctx) {
      if (!ctx) return;
      try {
        ctx.imageSmoothingEnabled = true;
        if ("imageSmoothingQuality" in ctx) ctx.imageSmoothingQuality = "high";
      } catch (e) {}
    }

    if (proto.getContext && !proto.getContext.__omarchyPulse) {
      var origGC = proto.getContext;
      proto.getContext = function (type, attrs) {
        var ctx = origGC.call(this, type, attrs);
        if (ctx && String(type).indexOf("2d") === 0) smoothCtx(ctx);
        return ctx;
      };
      proto.getContext.__omarchyPulse = true;
    }

    if (win.requestAnimationFrame && !win.requestAnimationFrame.__omarchyPulse) {
      var origRAF = win.requestAnimationFrame.bind(win);
      var origCAF = win.cancelAnimationFrame ? win.cancelAnimationFrame.bind(win) : function () {};
      var timers = {};
      win.requestAnimationFrame = function (cb) {
        var cancelled = false, rafId = 0, toId = 0;
        function deliver(now) {
          if (cancelled) return;
          try { if (win.document && win.document.hidden) { lastT = 0; lastRun = now; cb(now); return; } } catch (e) {}
          var gap = 1000 / (policy.fps || FPS_HIGH);
          if (lastRun && now - lastRun < gap - 2) {
            var wait = Math.max(4, gap - (now - lastRun));
            toId = win.setTimeout(function () { rafId = origRAF(deliver); }, wait);
            return;
          }
          if (lastT) {
            var d = now - lastT;
            if (d < 200) {
              accMs += d; accN++;
              if (d > 24) late++; else late = 0;
              if (late >= 8) {
                var rushed = stepPolicy({ scale: policy.scale, slow: 2, fast: 0, floor: policy.floor, fps: policy.fps }, 30);
                if (rushed.changed) { policy = rushed; late = 0; accMs = 0; accN = 0; applyPolicy(); log("stutter scale=" + policy.scale.toFixed(3) + " fps=" + policy.fps); }
              }
            }
          }
          lastT = now;
          lastRun = now;
          if (accN >= 90) {
            var avg = accMs / accN;
            accMs = 0; accN = 0; late = 0;
            var next = stepPolicy(policy, avg);
            if (next.changed) { policy = next; applyPolicy(); }
            else { policy.slow = next.slow; policy.fast = next.fast; }
            if (!lastLog || now - lastLog > 2000) {
              lastLog = now;
              var cv0 = win.document && (win.document.getElementById("scene") || win.document.getElementById("c"));
              log("fps=" + (1000 / avg).toFixed(1) + " frame=" + avg.toFixed(1) + "ms cap=" + policy.fps
                + " scale=" + policy.scale.toFixed(3) + " dpr=" + policyDpr().toFixed(2)
                + (cv0 ? " backing=" + cv0.width + "x" + cv0.height : ""));
            }
          }
          try { hookQuality(); } catch (e) {}
          cb(now);
        }
        rafId = origRAF(deliver);
        var id = rafId || (0 - (toId + 1));
        timers[id] = function () { cancelled = true; if (rafId) origCAF(rafId); if (toId) win.clearTimeout(toId); };
        return id;
      };
      win.requestAnimationFrame.__omarchyPulse = true;
      win.cancelAnimationFrame = function (id) {
        if (timers[id]) { timers[id](); delete timers[id]; return; }
        origCAF(id);
      };
    }

    try {
      if (win.document && win.document.documentElement) {
        var st = win.document.createElement("style");
        st.setAttribute("data-omarchy-pulse-adapt", "1");
        st.appendChild(win.document.createTextNode("#scene,canvas#c{image-rendering:auto;image-rendering:high-quality}"));
        (win.document.head || win.document.documentElement).appendChild(st);
      }
    } catch (e) {}

    function hookScenes() {
      try {
        if (!win.HTMLIFrameElement) return;
        var protoIf = win.HTMLIFrameElement.prototype;
        var ifd = Object.getOwnPropertyDescriptor(protoIf, "src");
        if (ifd && ifd.set && ifd.configurable) {
          Object.defineProperty(protoIf, "src", {
            configurable: true, enumerable: ifd.enumerable, get: ifd.get,
            set: function (v) {
              v = String(v || "");
              var here = "";
              try { here = (win.location && (win.location.pathname || win.location.href)) || ""; } catch (e) {}
              if (/pulse-host\.html/.test(here)) {
                var m = /(?:^|\/)(scene-(?:mars|monolith)\.html)(\?.*)?$/.exec(v);
                if (m && v.indexOf("vendor/pulse/") < 0) v = "vendor/pulse/" + m[1] + (m[2] || "");
              }
              ifd.set.call(this, v);
            }
          });
        }
        function onFrame(fr) {
          if (!fr || fr.__pulseAdaptHooked) return;
          fr.__pulseAdaptHooked = true;
          function go() {
            try { var w = fr.contentWindow; if (w && w !== win) install(w); } catch (e) {}
          }
          try { fr.addEventListener("load", go); } catch (e) {}
          go();
        }
        if (win.document) {
          var ifr = win.document.getElementsByTagName("iframe");
          for (var i = 0; i < ifr.length; i++) onFrame(ifr[i]);
          if (win.MutationObserver && win.document.documentElement) {
            new win.MutationObserver(function (recs) {
              for (var r = 0; r < recs.length; r++) {
                var nodes = recs[r].addedNodes || [];
                for (var n = 0; n < nodes.length; n++) {
                  if (nodes[n].tagName === "IFRAME") onFrame(nodes[n]);
                }
              }
            }).observe(win.document.documentElement, { childList: true, subtree: true });
          }
        }
      } catch (e) {}
    }

    var api = {
      FLOOR: FLOOR, FPS_HIGH: FPS_HIGH, FPS_FLOOR: FPS_FLOOR,
      scaleFloor: scaleFloor, clampBacking: clampBacking, stepPolicy: stepPolicy, effectiveDpr: effectiveDpr,
      enforce: enforce, scan: scan, hookScenes: hookScenes, hookQuality: hookQuality,
      policy: function () { return policy; }, applyPolicy: applyPolicy
    };
    win.__omarchyPulseAdapt = api;
    win.OmarchyPulseAdapt = api;
    hookScenes();
    try { hookQuality(); } catch (e) {}
    return api;
  }

  var api = {
    FLOOR: FLOOR, FPS_HIGH: FPS_HIGH, FPS_FLOOR: FPS_FLOOR,
    scaleFloor: scaleFloor, clampBacking: clampBacking, stepPolicy: stepPolicy,
    effectiveDpr: effectiveDpr, install: install
  };
  root.OmarchyPulseAdapt = api;
  if (typeof document !== "undefined") install(root);
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof window !== "undefined" ? window : this);
