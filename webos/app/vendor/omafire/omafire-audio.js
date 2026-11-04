/* OmarchyTV v2.14: OmaFire's sound (Wes Grimes, MIT) for the animated wallpaper, as his ui/fire/FireAudio.qml plays
 * it: three loops always running (embers, crackle, roar) whose levels follow the fire's heat and boost through his
 * sound.js (master 0.5, each level easing over 0.6 s), a log or a stoke as one-shots at 0.2, and the night around
 * the lake (crickets, a peep, a croak) scheduled by his night.js every 100 ms. Fades in and out over 0.3 s.
 * WebAudio; silent where a TV has none. Off unless the "OmaFire sound" setting is on. ES5. */
(function (root) {
  "use strict";
  var MASTER = 0.5, EVENT = 0.2, EASE = 0.6, FADE = 0.3;
  var FILES = ["fire-embers", "fire-crackle", "fire-roar", "fire-log", "fire-stoke", "cricket", "peep", "croak"];

  function create(base) {
    var AC = root.AudioContext || root.webkitAudioContext, S = root.OmaFireSound, N = root.OmaFireNight;
    if (!AC || !S || !N) return null;
    var ctx, master, buf = {}, loops = {}, ready = false, on = false, timer = null, ambience = N.createAmbience(5919);
    try { ctx = new AC(); } catch (e) { return null; }
    master = ctx.createGain();
    master.gain.value = 0;
    master.connect(ctx.destination);

    function load(name, done) {
      var x = new XMLHttpRequest();
      x.open("GET", base + name + ".wav", true);
      x.responseType = "arraybuffer";
      x.onload = function () {
        try {
          var p = ctx.decodeAudioData(x.response, function (b) { buf[name] = b; done(); }, function () { done(); });
          if (p && p.catch) p.catch(function () {});
        } catch (e) { done(); }
      };
      x.onerror = function () { done(); };
      x.send();
    }
    var left = FILES.length;
    FILES.forEach(function (f) { load(f, function () { if (--left === 0) { ready = true; if (on) startLoops(); } }); });

    function startLoops() {
      ["fire-embers", "fire-crackle", "fire-roar"].forEach(function (f) {
        if (loops[f] || !buf[f]) return;
        var src = ctx.createBufferSource(), g = ctx.createGain();
        src.buffer = buf[f]; src.loop = true; g.gain.value = 0;
        src.connect(g); g.connect(master);
        (src.start || src.noteOn).call(src, 0);
        loops[f] = { src: src, gain: g };
      });
    }
    function stopLoops() {
      for (var f in loops) { try { (loops[f].src.stop || loops[f].src.noteOff).call(loops[f].src, 0); } catch (e) {} }
      loops = {};
    }
    function ramp(param, v, secs) {
      var t = ctx.currentTime;
      try { param.cancelScheduledValues(t); param.setValueAtTime(param.value, t); param.linearRampToValueAtTime(v, t + secs); }
      catch (e) { param.value = v; }
    }
    function oneShot(name, vol) {
      if (!on || !ready || !buf[name]) return;
      var src = ctx.createBufferSource(), g = ctx.createGain();
      src.buffer = buf[name]; g.gain.value = Math.max(0, Math.min(1, vol));
      src.connect(g); g.connect(master);
      (src.start || src.noteOn).call(src, 0);
    }
    function night() {
      timer = setTimeout(night, 100);
      if (!on) return;
      var ev = N.advanceAmbience(ambience, 0.1);
      for (var i = 0; i < ev.length; i++) {   // FireAudio.playNight: far off in the trees, under the fire
        var e = ev[i], b = e.kind === "cricket" ? 0.055 : e.kind === "croak" ? 0.048 : 0.042;
        oneShot(e.kind === "cricket" ? "cricket" : e.kind === "croak" ? "croak" : "peep", Math.min(0.12, b * e.gain));
      }
    }
    night();
    // TVs start audio only after the first key press (autoplay rules): try again on every key until it runs.
    function resume() { if (ctx.state === "suspended" && ctx.resume) { try { ctx.resume(); } catch (e) {} } }
    try { root.addEventListener("keydown", resume, true); } catch (e) {}

    return {
      /** The fire's state each frame: heat 0..1 and boost (fire.js sim.heat / sim.boost). */
      setFire: function (heat, boost) {
        if (!on || !ready) return;
        if (loops["fire-embers"]) ramp(loops["fire-embers"].gain.gain, MASTER * S.embersGain(heat), EASE);
        if (loops["fire-crackle"]) ramp(loops["fire-crackle"].gain.gain, MASTER * S.crackleGain(heat), EASE);
        if (loops["fire-roar"]) ramp(loops["fire-roar"].gain.gain, MASTER * S.roarGain(heat, boost), EASE);
      },
      playLog: function () { oneShot("fire-log", EVENT); },
      playStoke: function () { oneShot("fire-stoke", EVENT); },
      setOn: function (v) {
        v = !!v;
        if (v === on) return;
        on = v;
        resume();
        if (on) { ambience = N.createAmbience(5919); if (ready) startLoops(); ramp(master.gain, 1, FADE); }
        else { ramp(master.gain, 0, FADE); setTimeout(function () { if (!on) stopLoops(); }, FADE * 1000 + 50); }
      },
      destroy: function () {
        on = false; clearTimeout(timer); stopLoops();
        try { root.removeEventListener("keydown", resume, true); } catch (e) {}
        try { ctx.close(); } catch (e) {}
      }
    };
  }
  root.OmaFireAudio = { create: create };
})(typeof window !== "undefined" ? window : this);
