/* OmarchyTV Arcade sound effects, synthesised live like the boot sound (no audio files, nothing to download):
 * square-wave blips and arpeggios of the old arcade boards, filtered noise for static, slaps and explosions.
 * On by default (a game is something you open on purpose); "Arcade sound" in the settings turns it off.
 * WebAudio; silent where a TV has none, and TVs start audio only after the first key press. ES5. */
(function (root) {
  "use strict";
  var ROOTS = [261.6, 293.7, 329.6, 349.2, 392, 440, 493.9];
  function create(enabled, ctxIn) {
    var AC = root.AudioContext || root.webkitAudioContext;
    if (!AC && !ctxIn) return null;
    var ctx = ctxIn, on = enabled !== false;
    if (!ctx) { try { ctx = new AC(); } catch (e) { return null; } }
    var out = ctx.createGain();
    out.gain.value = 0.32;
    out.connect(ctx.destination);
    var noiseBuf = ctx.createBuffer(1, ctx.sampleRate, ctx.sampleRate), nd = noiseBuf.getChannelData(0);
    for (var i = 0; i < nd.length; i++) nd[i] = Math.random() * 2 - 1;

    function env(gn, t, a, peak, d) {
      gn.gain.setValueAtTime(0.0001, t);
      gn.gain.linearRampToValueAtTime(peak, t + a);
      gn.gain.exponentialRampToValueAtTime(0.0001, t + a + d);
    }
    function tone(type, f, t, a, peak, d, fEnd) {
      var o = ctx.createOscillator(), gn = ctx.createGain();
      o.type = type; o.frequency.setValueAtTime(f, t);
      if (fEnd) o.frequency.exponentialRampToValueAtTime(fEnd, t + a + d);
      env(gn, t, a, peak, d);
      o.connect(gn); gn.connect(out);
      (o.start || o.noteOn).call(o, t); (o.stop || o.noteOff).call(o, t + a + d + 0.05);
    }
    function noise(t, a, peak, d, ftype, f, q, fEnd) {
      var s = ctx.createBufferSource(), fl = ctx.createBiquadFilter(), gn = ctx.createGain();
      s.buffer = noiseBuf;
      fl.type = ftype; fl.frequency.setValueAtTime(f, t); if (q) fl.Q.value = q;
      if (fEnd) fl.frequency.exponentialRampToValueAtTime(fEnd, t + a + d);
      env(gn, t, a, peak, d);
      s.connect(fl); fl.connect(gn); gn.connect(out);
      (s.start || s.noteOn).call(s, t, Math.random() * 0.5); (s.stop || s.noteOff).call(s, t + a + d + 0.05);
    }
    function seq(notes, t, step, type, peak, len) {
      for (var k = 0; k < notes.length; k++) tone(type || "square", notes[k], t + k * step, 0.004, peak || 0.09, len || step * 0.9);
    }
    var cues = {
      blip: function (t) { tone("square", 660, t, 0.003, 0.06, 0.05); },
      lane: function (t) { tone("square", 520, t, 0.002, 0.04, 0.03); },
      knob: function (t) { noise(t, 0.001, 0.08, 0.012, "bandpass", 2600, 3); },
      coin: function (t) { tone("square", 988, t, 0.003, 0.09, 0.07); tone("square", 1319, t + 0.08, 0.003, 0.09, 0.3); },
      start: function (t) { seq([523.3, 659.3, 784, 1046.5], t, 0.07, "square", 0.08); },
      move: function (t) { noise(t, 0.001, 0.05, 0.02, "highpass", 3500); },
      shoot: function (t) { tone("square", 880, t, 0.002, 0.06, 0.12, 220); },
      hit: function (t) { noise(t, 0.002, 0.18, 0.16, "bandpass", 900, 0.8, 200); tone("square", 200, t, 0.002, 0.06, 0.15, 60); },
      ghost: function (t) { tone("sine", 1400, t, 0.002, 0.03, 0.05, 1800); },
      hurt: function (t) { tone("sawtooth", 300, t, 0.005, 0.12, 0.4, 70); noise(t, 0.003, 0.1, 0.3, "lowpass", 1200); },
      lock: function (t) { noise(t, 0.001, 0.2, 0.06, "lowpass", 500); tone("sine", 130, t, 0.002, 0.25, 0.12, 80); tone("square", 1568, t + 0.05, 0.003, 0.04, 0.1); },
      slap: function (t) { tone("sine", 95, t, 0.002, 0.7, 0.3, 38); noise(t, 0.001, 0.4, 0.18, "lowpass", 700); noise(t + 0.02, 0.01, 0.05, 0.4, "bandpass", 300, 2); },
      static: function (t) { noise(t, 0.01, 0.12, 0.4, "bandpass", 3000, 0.4); },
      fork: function (t) { tone("square", 400, t, 0.003, 0.06, 0.12, 1200); },
      save: function (t) { tone("square", 1046.5, t, 0.003, 0.07, 0.08); tone("square", 1568, t + 0.07, 0.003, 0.07, 0.2); },
      oom: function (t) { tone("sawtooth", 72, t, 0.03, 0.18, 0.55, 48); tone("sawtooth", 76, t, 0.03, 0.1, 0.55, 50); },
      die: function (t) { tone("square", 600, t, 0.003, 0.06, 0.25, 90); },
      switch: function (t, idx) {   // every theme has its own chord
        var r0 = ROOTS[(idx || 0) % ROOTS.length];
        [1, 1.26, 1.5, 2].forEach(function (m, k) { tone("triangle", r0 * m, t + k * 0.025, 0.01, 0.06, 0.45); });
        noise(t, 0.05, 0.05, 0.2, "bandpass", 600, 1, 5000);
      },
      gameover: function (t) { seq([392, 329.6, 261.6, 196], t, 0.18, "square", 0.08, 0.17); },
      levelup: function (t) { seq([523.3, 659.3, 784, 1046.5, 1318.5], t, 0.05, "square", 0.07); },
      record: function (t) { seq([523.3, 659.3, 784, 1046.5, 784, 1046.5, 1318.5], t, 0.09, "square", 0.08); }
    };
    function resume() { if (!ctxIn && ctx.state === "suspended" && ctx.resume) { try { ctx.resume(); } catch (e) {} } }
    try { root.addEventListener("keydown", resume, true); } catch (e) {}
    return {
      CUES: Object.keys ? Object.keys(cues) : [],
      play: function (name, arg, at) {
        if (!on) return;
        resume();
        var f = cues[name];
        if (f) { try { f(typeof at === "number" ? at : ctx.currentTime + 0.005, arg); } catch (e) {} }
      },
      setOn: function (v) { on = !!v; },
      close: function () {
        try { root.removeEventListener("keydown", resume, true); } catch (e) {}
        if (!ctxIn) setTimeout(function () { try { ctx.close(); } catch (e) {} }, 1500);
      }
    };
  }
  root.OmarchyArcadeSfx = { create: create };
})(typeof window !== "undefined" ? window : this);
