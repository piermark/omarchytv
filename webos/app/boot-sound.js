/* OmarchyTV v2.14 boot sound: an old PC and its CRT coming up, synthesised live (no audio files).
 * power: the tube's thump and degauss hum · beep: the POST beep · tick: memory count · zap: the screen clearing
 * disk: a hard-disk seek per systemd line · key: a keystroke · whoosh: the wordmark arriving, with a soft chord.
 * Quiet on purpose (it starts with the TV). Cues come from boot.js cues(); Android plays the same set
 * (BootSound.java). WebAudio; silent where a TV has none or blocks audio before the first key press. ES5. */
(function (root) {
  "use strict";
  function create(volume, ctxIn) {   // ctxIn: an (Offline)AudioContext to render into, for tests and previews
    var AC = root.AudioContext || root.webkitAudioContext;
    if (!AC && !ctxIn) return null;
    var ctx = ctxIn;
    if (!ctx) { try { ctx = new AC(); } catch (e) { return null; } }
    var out = ctx.createGain();
    out.gain.value = typeof volume === "number" ? volume : 0.5;
    out.connect(ctx.destination);
    var noiseBuf = ctx.createBuffer(1, ctx.sampleRate, ctx.sampleRate), nd = noiseBuf.getChannelData(0);
    for (var i = 0; i < nd.length; i++) nd[i] = Math.random() * 2 - 1;

    function env(g, t, a, peak, d) {
      g.gain.setValueAtTime(0.0001, t);
      g.gain.linearRampToValueAtTime(peak, t + a);
      g.gain.exponentialRampToValueAtTime(0.0001, t + a + d);
    }
    function tone(type, f, t, a, peak, d, fEnd) {
      var o = ctx.createOscillator(), g = ctx.createGain();
      o.type = type; o.frequency.setValueAtTime(f, t);
      if (fEnd) o.frequency.exponentialRampToValueAtTime(fEnd, t + a + d);
      env(g, t, a, peak, d);
      o.connect(g); g.connect(out);
      (o.start || o.noteOn).call(o, t); (o.stop || o.noteOff).call(o, t + a + d + 0.05);
    }
    function noise(t, a, peak, d, ftype, f, q, fEnd) {
      var s = ctx.createBufferSource(), fl = ctx.createBiquadFilter(), g = ctx.createGain();
      s.buffer = noiseBuf;
      fl.type = ftype; fl.frequency.setValueAtTime(f, t); if (q) fl.Q.value = q;
      if (fEnd) fl.frequency.exponentialRampToValueAtTime(fEnd, t + a + d);
      env(g, t, a, peak, d);
      s.connect(fl); fl.connect(g); g.connect(out);
      (s.start || s.noteOn).call(s, t, Math.random() * 0.5); (s.stop || s.noteOff).call(s, t + a + d + 0.05);
    }
    var cues = {
      power: function (t) {   // relay clack, the tube's low thump, then the degauss hum dying away
        noise(t, 0.002, 0.35, 0.05, "bandpass", 1800, 1.2);
        tone("sine", 70, t + 0.01, 0.01, 0.55, 0.45, 38);
        tone("sawtooth", 50, t + 0.03, 0.05, 0.08, 0.9);
        noise(t + 0.05, 0.08, 0.05, 0.5, "lowpass", 900);
      },
      beep: function (t) { tone("square", 1000, t, 0.004, 0.07, 0.17); },
      tick: function (t) { for (var k = 0; k < 6; k++) noise(t + k * 0.1, 0.001, 0.05, 0.012, "highpass", 4000); },
      zap: function (t) { noise(t, 0.002, 0.12, 0.09, "highpass", 2500); tone("sine", 220, t, 0.002, 0.06, 0.08, 90); },
      disk: function (t) {
        noise(t, 0.001, 0.09, 0.012, "bandpass", 3200, 2);
        if (Math.random() < 0.6) noise(t + 0.03 + Math.random() * 0.03, 0.001, 0.06, 0.01, "bandpass", 2600, 2);
      },
      key: function (t) { noise(t, 0.001, 0.05, 0.02, "bandpass", 1500 + Math.random() * 700, 1.5); },
      whoosh: function (t) {
        noise(t, 0.25, 0.09, 0.6, "bandpass", 300, 1.4, 2400);
        [261.6, 329.6, 392, 523.3].forEach(function (f, k) { tone("sine", f, t + 0.25 + k * 0.06, 0.03, 0.035, 1.3); });
      }
    };
    return {
      cue: function (name, at) {
        if (!ctxIn && ctx.state === "suspended" && ctx.resume) { try { ctx.resume(); } catch (e) {} }
        var f = cues[name];
        if (f) { try { f(typeof at === "number" ? at : ctx.currentTime + 0.01); } catch (e) {} }
      },
      close: function () { setTimeout(function () { try { ctx.close(); } catch (e) {} }, 2500); }
    };
  }
  root.OmarchyBootSound = { create: create, CUES: ["power", "beep", "tick", "zap", "disk", "key", "whoosh"] };
})(typeof window !== "undefined" ? window : this);
