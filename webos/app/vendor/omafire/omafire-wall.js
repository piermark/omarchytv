/* OmarchyTV v2.14: OmaFire (Wes Grimes, MIT) as an animated wallpaper. fire.js draws the campsite exactly as in the
 * Omarchy bar plugin; this only runs it full screen: 360x240 frame scaled up without smoothing, 30 fps, paused while
 * the page is hidden. Modes: "campfire" (a log goes on before it burns low: always a good fire) and "embers"
 * (kept low: glowing coals and sparks). Shared by LG/Samsung (app.js) and Android (assets/omafire/index.html). ES5. */
(function (root) {
  "use strict";
  function mount(host, opts) {
    opts = opts || {};
    var F = root.OmaFire, doc = host.ownerDocument || document;
    if (!F) return null;
    var cv = doc.createElement("canvas");
    cv.width = F.W; cv.height = F.H;
    var s = cv.style;
    s.position = "absolute"; s.left = "0"; s.top = "0"; s.width = "100%"; s.height = "100%";
    s.objectFit = "cover"; s.imageRendering = "pixelated"; s.pointerEvents = "none";
    host.appendChild(cv);
    var ctx = cv.getContext("2d"), img = ctx.createImageData(F.W, F.H);
    var sim = F.createState(), fb = F.createFrame(), rects = F.createRects(), bg = null;
    var mode = opts.mode === "embers" ? "embers" : "campfire", last = 0, timer = null, stopped = false, fed = 0, n = 0;
    // v2.14 OmaFire sound (his FireAudio: loops follow the heat, a log and a stoke, the night); off unless asked.
    var audio = root.OmaFireAudio ? root.OmaFireAudio.create(opts.soundBase || "vendor/omafire/") : null, soundOn = !!opts.sound;
    var art = new Image();
    art.onload = function () {
      var c = doc.createElement("canvas");
      c.width = 720; c.height = 480;
      var x = c.getContext("2d");
      x.drawImage(art, 0, 0, 720, 480);
      bg = F.prepareBackground(x.getImageData(0, 0, 720, 480).data, 720, 480);
    };
    art.src = opts.art || "vendor/omafire/campsite-approved.webp";
    function feed(now) {
      var low = mode === "embers" ? 0.06 : 0.42;
      if (sim.fuel < low && now - fed > 8000) { F.addLog(sim); fed = now; if (audio) audio.playLog(); }
      if (mode === "campfire" && Math.random() < 0.002 && F.stoke(sim) === "The coals come alive." && audio) audio.playStoke();
    }
    function frame() {
      if (stopped) return;
      timer = setTimeout(frame, 33);
      if (audio) audio.setOn(soundOn && !doc.hidden);
      if (doc.hidden || !bg) return;
      var now = Date.now(), dt = last ? Math.min(0.25, (now - last) / 1000) : 1 / 30;
      last = now;
      feed(now);
      F.tick(sim, dt, true);
      F.render(fb, sim, bg, rects);
      if (audio && soundOn && ++n % 6 === 0) audio.setFire(sim.heat, sim.boost);
      img.data.set(fb);
      ctx.putImageData(img, 0, 0);
    }
    if (mode === "embers") { sim.fuel = 0.12; sim.heat = 0.2; }
    frame();
    return {
      setMode: function (m) { mode = m === "embers" ? "embers" : "campfire"; },
      setSound: function (v) { soundOn = !!v; if (audio) audio.setOn(soundOn && !doc.hidden); },
      destroy: function () { stopped = true; clearTimeout(timer); if (audio) audio.destroy(); if (cv.parentNode) cv.parentNode.removeChild(cv); }
    };
  }
  root.OmaFireWall = { mount: mount, MODES: ["campfire", "embers"] };
})(typeof window !== "undefined" ? window : this);
