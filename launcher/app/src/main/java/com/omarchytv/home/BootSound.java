package com.omarchytv.home;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * v2.14 boot sound on Android TV: an old PC and its CRT coming up, synthesised (no audio files), the same cues as
 * webos/app/boot-sound.js: power (relay clack, the tube's thump, a dying degauss hum), beep (POST), tick (memory
 * count), zap (screen clear), disk (a seek per systemd line), key (a keystroke), whoosh (the wordmark, with a soft
 * chord). Quiet on purpose. Each cue is rendered once to 16-bit PCM and played with a static AudioTrack on its own
 * thread; nothing touches the UI thread. Setting: bootSound (default on).
 */
final class BootSound {
    static final int RATE = 22050;
    private final HandlerThread thread = new HandlerThread("boot-sound");
    private final Handler h;
    private final Map<String, short[]> cache = new HashMap<>();
    private final Random rnd = new Random(5919);
    private final float volume;

    BootSound(float volume) {
        this.volume = volume;
        thread.start();
        h = new Handler(thread.getLooper());
    }

    /** From the boot view's draw loop: never blocks. */
    void cue(String name) {
        h.post(() -> play(name));
    }

    void close() {
        h.postDelayed(thread::quitSafely, 2500);
    }

    private void play(String name) {
        short[] pcm = cache.get(name);
        if (pcm == null) {
            pcm = render(name);
            if (pcm == null) return;
            if (!"disk".equals(name) && !"key".equals(name)) cache.put(name, pcm);   // those vary each time
        }
        try {
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length * 2).build();
            t.write(pcm, 0, pcm.length);
            t.play();
            h.postDelayed(t::release, pcm.length * 1000L / RATE + 200);
        } catch (Exception ignored) {   // no audio output: the boot screen just stays silent
        }
    }

    // ---- synthesis: sums of decaying tones and filtered noise ----

    private float[] buf(double secs) {
        return new float[(int) (secs * RATE)];
    }

    private static double env(double t, double a, double d) {
        if (t < 0) return 0;
        if (t < a) return t / a;
        return Math.exp(-(t - a) / (d / 4));
    }

    private void tone(float[] b, double start, double f, double fEnd, double a, double peak, double d, boolean square) {
        double phase = 0;
        int s0 = (int) (start * RATE), n = (int) ((a + d) * RATE);
        for (int i = 0; i < n && s0 + i < b.length; i++) {
            double t = (double) i / RATE, k = t / (a + d), freq = fEnd > 0 ? f * Math.pow(fEnd / f, k) : f;
            phase += 2 * Math.PI * freq / RATE;
            double v = square ? Math.signum(Math.sin(phase)) * 0.6 : Math.sin(phase);
            b[s0 + i] += (float) (v * peak * env(t, a, d));
        }
    }

    /** Noise through a one-pole band (lo..hi Hz, roughly). */
    private void noise(float[] b, double start, double a, double peak, double d, double lo, double hi) {
        int s0 = (int) (start * RATE), n = (int) ((a + d) * RATE);
        double hp = 0, lp = 0, prev = 0;
        double aHi = Math.exp(-2 * Math.PI * hi / RATE), aLo = Math.exp(-2 * Math.PI * lo / RATE);
        for (int i = 0; i < n && s0 + i < b.length; i++) {
            double x = rnd.nextDouble() * 2 - 1;
            lp = (1 - aHi) * x + aHi * lp;           // low-pass at hi
            hp = aLo * (hp + lp - prev);             // high-pass at lo
            prev = lp;
            b[s0 + i] += (float) (hp * 2.2 * peak * env((double) i / RATE, a, d));
        }
    }

    private short[] render(String name) {
        float[] b;
        switch (name) {
            case "power":
                b = buf(1.2);
                noise(b, 0, 0.002, 0.35, 0.05, 1200, 2600);
                tone(b, 0.01, 70, 38, 0.01, 0.55, 0.45, false);
                tone(b, 0.03, 50, 0, 0.05, 0.08, 0.9, true);
                noise(b, 0.05, 0.08, 0.05, 0.5, 40, 900);
                break;
            case "beep":
                b = buf(0.25);
                tone(b, 0, 1000, 0, 0.004, 0.07, 0.17, true);
                break;
            case "tick":
                b = buf(0.7);
                for (int k = 0; k < 6; k++) noise(b, k * 0.1, 0.001, 0.05, 0.012, 4000, 9000);
                break;
            case "zap":
                b = buf(0.15);
                noise(b, 0, 0.002, 0.12, 0.09, 2500, 9000);
                tone(b, 0, 220, 90, 0.002, 0.06, 0.08, false);
                break;
            case "disk":
                b = buf(0.1);
                noise(b, 0, 0.001, 0.09, 0.012, 2400, 4200);
                if (rnd.nextDouble() < 0.6) noise(b, 0.03 + rnd.nextDouble() * 0.03, 0.001, 0.06, 0.01, 1900, 3400);
                break;
            case "key":
                b = buf(0.04);
                noise(b, 0, 0.001, 0.05, 0.02, 1100, 2400);
                break;
            case "whoosh":
                b = buf(2.0);
                noise(b, 0, 0.25, 0.09, 0.6, 300, 2400);
                double[] chord = {261.6, 329.6, 392, 523.3};
                for (int k = 0; k < chord.length; k++) tone(b, 0.25 + k * 0.06, chord[k], 0, 0.03, 0.035, 1.3, false);
                break;
            default:
                return null;
        }
        short[] out = new short[b.length];
        for (int i = 0; i < b.length; i++) {
            float v = Math.max(-1f, Math.min(1f, b[i] * volume));
            out[i] = (short) (v * 32767);
        }
        return out;
    }
}
