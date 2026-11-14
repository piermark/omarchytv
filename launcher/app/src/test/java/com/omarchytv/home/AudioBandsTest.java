package com.omarchytv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** The music analysis behind Radiomarchy's fractals: FFT, the three bands, beats, and media-time lookup. */
public class AudioBandsTest {
    private static final int SR = 44100;

    @Test
    public void fftFindsASine() {
        int n = 1024;
        float[] re = new float[n], im = new float[n];
        for (int i = 0; i < n; i++) re[i] = (float) Math.sin(2 * Math.PI * 64 * i / n);
        AudioBands.fft(re, im);
        int best = 0;
        double bestM = 0;
        for (int k = 0; k < n / 2; k++) {
            double m = Math.hypot(re[k], im[k]);
            if (m > bestM) {
                bestM = m;
                best = k;
            }
        }
        assertEquals(64, best);
        assertEquals(n / 2.0, bestM, 1);
    }

    /** seconds of 16-bit stereo: a 55 Hz kick every {@code beatS} seconds over a quiet 3 kHz hiss. */
    private static short[] music(double seconds, double beatS, boolean kicks, boolean hiss) {
        int frames = (int) (seconds * SR);
        short[] s = new short[frames * 2];
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < frames; i++) {
            double t = i / (double) SR, v = 0;
            if (kicks) {
                double since = t % beatS;
                v += 0.8 * Math.exp(-since * 18) * Math.sin(2 * Math.PI * 55 * since);
            }
            if (hiss) v += 0.05 * Math.sin(2 * Math.PI * 3000 * t) + 0.02 * (r.nextDouble() - 0.5);
            short x = (short) Math.max(-32767, Math.min(32767, v * 32767));
            s[i * 2] = x;
            s[i * 2 + 1] = x;
        }
        return s;
    }

    @Test
    public void kicksAreBeatsAndBass() {
        AudioBands b = new AudioBands();
        b.flush(SR);
        b.startAt(0);
        short[] pcm = music(8, 0.5, true, true);   // 120 bpm for 8 s
        b.pcm16(pcm, pcm.length / 2, 2);
        AudioBands.Frame f = new AudioBands.Frame();
        assertTrue(b.at(7_990_000, f));
        assertTrue("about 16 beats in 8 s, got " + f.beats, f.beats >= 13 && f.beats <= 17);
        // right on a kick the bass is up; between kicks it has fallen
        AudioBands.Frame on = new AudioBands.Frame(), off = new AudioBands.Frame();
        b.at(7_030_000, on);
        b.at(7_450_000, off);
        assertTrue("bass on the kick " + on.bass + " > between " + off.bass, on.bass > off.bass + 0.2f);
        assertTrue(on.beatUs <= 7_030_000 && on.beatUs > 6_900_000);
        // the low bars are louder than the top ones on a kick
        assertTrue(on.bars[1] > on.bars[15]);
    }

    @Test
    public void hissAloneHasNoBeats() {
        AudioBands b = new AudioBands();
        b.flush(SR);
        short[] pcm = music(5, 0.5, false, true);
        b.pcm16(pcm, pcm.length / 2, 2);
        AudioBands.Frame f = new AudioBands.Frame();
        assertTrue(b.at(4_900_000, f));
        assertEquals(0, f.beats);
        assertTrue(f.high > 0.3f);
    }

    @Test
    public void framesAreStampedWithMediaTimeFromWhereTheTrackStarted() {
        AudioBands b = new AudioBands();
        b.flush(SR);
        b.startAt(60_000_000);   // a podcast resumed at 1:00
        short[] pcm = music(2, 0.5, true, false);
        b.pcm16(pcm, pcm.length / 2, 2);
        AudioBands.Frame f = new AudioBands.Frame();
        assertTrue(b.at(61_000_000, f));
        assertEquals(61_000_000, f.timeUs, 15_000);
        assertTrue(b.at(Long.MAX_VALUE, f));
        assertTrue(f.timeUs > 61_900_000);
        // asked for a time before the oldest kept frame: the oldest one
        assertTrue(b.at(0, f));
        assertTrue(f.timeUs >= 60_000_000);
        b.flush(SR);
        assertFalse(b.at(61_000_000, f));
    }

    @Test
    public void floatPcmToo() {
        AudioBands b = new AudioBands();
        b.flush(48000);
        float[] pcm = new float[48000];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (float) (0.5 * Math.sin(2 * Math.PI * 80 * i / 48000.0));
        b.pcmFloat(pcm, pcm.length, 1);
        AudioBands.Frame f = new AudioBands.Frame();
        assertTrue(b.at(Long.MAX_VALUE, f));
        assertTrue(f.bass > 0.8f);
        assertTrue(f.bass > f.high);
    }

    @Test
    public void idleBreathes() {
        AudioBands.Frame a = new AudioBands.Frame(), c = new AudioBands.Frame();
        AudioBands.idle(1_000_000, a);
        AudioBands.idle(3_000_000, c);
        assertTrue(a.bass > 0 && a.bass < 1);
        assertTrue(c.beats > a.beats);
        assertTrue(c.beatUs <= 3_000_000);
    }

    @Test
    public void readersDoNotWaitForTheFft() throws Exception {
        AudioBands b = new AudioBands();
        b.flush(SR);
        short[] pcm = music(2, 0.5, true, false);
        AtomicBoolean done = new AtomicBoolean();
        AtomicLong maxNs = new AtomicLong();
        Thread reader = new Thread(() -> {
            AudioBands.Frame f = new AudioBands.Frame();
            while (!done.get()) {
                long t0 = System.nanoTime();
                b.at(Long.MAX_VALUE, f);
                long dt = System.nanoTime() - t0;
                if (dt > maxNs.get()) maxNs.set(dt);
            }
        }, "bands-reader");
        reader.start();
        b.pcm16(pcm, pcm.length / 2, 2);
        done.set(true);
        reader.join(2000);
        assertTrue("at() blocked " + (maxNs.get() / 1_000_000L) + " ms while the FFT ran",
                maxNs.get() < 20_000_000L);
        AudioBands.Frame f = new AudioBands.Frame();
        assertTrue(b.at(Long.MAX_VALUE, f));
        assertTrue(f.bass > 0);
    }
}
