package com.omarchytv.home;

/**
 * v2.20 Radiomarchy: what the music is doing, for the fractals that dance to it. Fed the decoded PCM that
 * ExoPlayer is about to play (a tee in the audio sink, so no microphone and no RECORD_AUDIO), it keeps, for
 * each ~12 ms of audio: the level of the bass, the mids and the highs (0..1, auto-levelled so a quiet podcast
 * and a loud synthwave track both fill the range), 16 log-spaced spectrum bars and the beats (a bass onset well
 * above the recent average). Every frame is stamped with its media time, so the picture can ask for the frame
 * that is being HEARD now ({@link #at(long, Frame)}) and not the one the decoder is half a second ahead on.
 * Pure Java: the same maths runs in the JVM unit tests (AudioBandsTest).
 */
final class AudioBands {
    static final int N = 1024, HOP = 512, BARS = 16, RING = 512;
    /** Readers (UI / GL) only wait for this short publish, never for the FFT itself. */
    private final Object pub = new Object();

    /** One analysed slice of the music, as the picture reads it. */
    static final class Frame {
        long timeUs;            // media time of this slice
        float bass, mid, high, level;
        long beatUs = Long.MIN_VALUE / 4;   // media time of the last beat at or before timeUs
        int beats;              // beats since the stream started (the picture changes scenes on them)
        final float[] bars = new float[BARS];

        void copyFrom(Frame o) {
            timeUs = o.timeUs;
            bass = o.bass;
            mid = o.mid;
            high = o.high;
            level = o.level;
            beatUs = o.beatUs;
            beats = o.beats;
            System.arraycopy(o.bars, 0, bars, 0, BARS);
        }
    }

    private final float[] win = new float[N], re = new float[N], im = new float[N], hann = new float[N];
    private int fill;
    private int sampleRate = 44100;
    private long samples;            // mono samples since the last flush
    private volatile long baseUs;    // media time of the first sample after a flush (the seek target)
    private final Frame[] ring = new Frame[RING];
    private volatile int head = -1;  // index of the newest frame (written by the audio thread only)
    private int written;             // frames since the last flush
    // envelopes and auto-levelling
    private float eBass, eMid, eHigh, eLevel, pBass = 1e-4f, pMid = 1e-4f, pHigh = 1e-4f, pLevel = 1e-4f;
    private final float[] eBars = new float[BARS], pBars = new float[BARS];
    private float fluxAvg, fluxPeak = 1e-4f;
    private final float[] prevLow = new float[32];   // the bass bins of the last window (under 160 Hz: < 32 bins)
    private long lastBeatUs = Long.MIN_VALUE / 4;
    private int beats;
    private final int[] barLo = new int[BARS], barHi = new int[BARS];

    AudioBands() {
        for (int i = 0; i < N; i++) hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1)));
        for (int i = 0; i < RING; i++) ring[i] = new Frame();
        for (int i = 0; i < BARS; i++) pBars[i] = 1e-4f;
        setRate(44100);
    }

    private void setRate(int sr) {
        sampleRate = sr > 0 ? sr : 44100;
        // 16 bars from 40 Hz to 16 kHz, log-spaced, at least one FFT bin each
        double lo = 40, hi = Math.min(16000, sampleRate / 2.0 - 1);
        for (int b = 0; b < BARS; b++) {
            double f0 = lo * Math.pow(hi / lo, b / (double) BARS), f1 = lo * Math.pow(hi / lo, (b + 1) / (double) BARS);
            barLo[b] = Math.max(1, (int) Math.floor(f0 * N / sampleRate));
            barHi[b] = Math.max(barLo[b] + 1, (int) Math.ceil(f1 * N / sampleRate));
        }
    }

    /** Where the next stream starts, in media time (the player says so before it seeks or starts a track). */
    void startAt(long mediaUs) {
        baseUs = Math.max(0, mediaUs);
    }

    /** The audio sink restarted (a new track, a seek): forget the window, keep the levels. */
    void flush(int sr) {
        if (sr != sampleRate) setRate(sr);
        fill = 0;
        samples = 0;
        java.util.Arrays.fill(prevLow, 0);
        synchronized (pub) {
            head = -1;
            written = 0;
            lastBeatUs = Long.MIN_VALUE / 4;
        }
    }

    /** Interleaved 16-bit PCM. */
    void pcm16(short[] data, int frames, int channels) {
        channels = Math.max(1, channels);
        for (int f = 0; f < frames; f++) {
            float m = 0;
            for (int c = 0; c < channels; c++) m += data[f * channels + c];
            push(m / (channels * 32768f));
        }
    }

    /** Interleaved float PCM (-1..1). */
    void pcmFloat(float[] data, int frames, int channels) {
        channels = Math.max(1, channels);
        for (int f = 0; f < frames; f++) {
            float m = 0;
            for (int c = 0; c < channels; c++) m += data[f * channels + c];
            push(m / channels);
        }
    }

    private void push(float s) {
        win[fill++] = s;
        samples++;
        if (fill < N) return;
        analyse();
        System.arraycopy(win, HOP, win, 0, N - HOP);
        fill = N - HOP;
    }

    private void analyse() {
        for (int i = 0; i < N; i++) {
            re[i] = win[i] * hann[i];
            im[i] = 0;
        }
        fft(re, im);
        float hz = sampleRate / (float) N, bass = 0, mid = 0, high = 0, all = 0, flux = 0;
        int bassBins = 0;
        for (int k = 1; k < N / 2; k++) {
            float f = k * hz, m = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]);
            if (f < 160) {
                bass += m;
                if (bassBins < prevLow.length) {
                    flux += Math.max(0, m - prevLow[bassBins]);   // onset: how much the bass bins rose
                    prevLow[bassBins] = m;
                }
                bassBins++;
            }
            else if (f < 2000) mid += m;
            else if (f < 12000) high += m * 1.6f;   // the highs carry less energy: lift them a little
            all += m;
        }
        float dt = HOP / (float) sampleRate;
        // frame time = the middle of the window
        long t = baseUs + (long) ((samples - N / 2.0) * 1e6 / sampleRate);

        // beat: a bass onset (spectral flux of the bins under 160 Hz) well above its recent average, at most 4 a
        // second, and only when the bass stands out of the spectrum (hiss and noise are flat: no beat)
        fluxPeak = Math.max(fluxPeak * (1 - dt / 8f), flux);
        boolean beat = written > 0 && flux > fluxAvg * 3f + 1e-4f && flux > fluxPeak * 0.2f && t - lastBeatUs > 250_000
                && bass / Math.max(1, bassBins) > 2f * all / (N / 2f - 1);
        fluxAvg += (flux - fluxAvg) * Math.min(1, dt / 0.5f);
        if (beat) {
            lastBeatUs = t;
            beats++;
        }

        eBass = env(eBass, level(bass, 0), dt);
        eMid = env(eMid, level(mid, 1), dt);
        eHigh = env(eHigh, level(high, 2), dt);
        eLevel = env(eLevel, level(all, 3), dt);
        int h = (head + 1) % RING;
        Frame fr = ring[h];
        fr.timeUs = t;
        fr.bass = eBass;
        fr.mid = eMid;
        fr.high = eHigh;
        fr.level = eLevel;
        fr.beatUs = lastBeatUs;
        fr.beats = beats;
        for (int b = 0; b < BARS; b++) {
            float m = 0;
            for (int k = barLo[b]; k < barHi[b] && k < N / 2; k++) m = Math.max(m, (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]));
            pBars[b] = Math.max(pBars[b] * (1 - dt / 8f), m);
            float v = (float) Math.sqrt(m / Math.max(1e-4f, pBars[b]));
            eBars[b] = env(eBars[b], v, dt);
            fr.bars[b] = eBars[b];
        }
        synchronized (pub) {
            head = h;
            written++;
        }
    }

    /** Auto-levelled 0..1: against a peak that falls slowly (8 s), with a square root so the quiet parts show. */
    private float level(float v, int which) {
        float p;
        switch (which) {
            case 0: p = pBass = Math.max(pBass * (1 - HOP / (sampleRate * 8f)), v); break;
            case 1: p = pMid = Math.max(pMid * (1 - HOP / (sampleRate * 8f)), v); break;
            case 2: p = pHigh = Math.max(pHigh * (1 - HOP / (sampleRate * 8f)), v); break;
            default: p = pLevel = Math.max(pLevel * (1 - HOP / (sampleRate * 8f)), v); break;
        }
        return (float) Math.sqrt(Math.min(1, v / Math.max(1e-4f, p)));
    }

    /** Fast up (~15 ms), slower down (~180 ms): punches on the beat, no flicker. */
    private static float env(float e, float v, float dt) {
        float tau = v > e ? 0.015f : 0.18f;
        return e + (v - e) * Math.min(1, dt / tau);
    }

    /**
     * The frame being heard at media time {@code nowUs}: the newest frame not after it (or the oldest kept).
     * False when nothing has been analysed since the last flush.
     */
    boolean at(long nowUs, Frame out) {
        synchronized (pub) {
            int h = head;
            if (h < 0) return false;
            Frame best = ring[h];
            int n = Math.min(written, RING);
            for (int i = 0; i < n; i++) {
                Frame f = ring[(h - i + RING) % RING];
                best = f;
                if (f.timeUs <= nowUs) break;
            }
            out.copyFrom(best);
            return true;
        }
    }

    /** In-place radix-2 FFT (n a power of two). */
    static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float tr = re[i]; re[i] = re[j]; re[j] = tr;
                float ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    float xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    float nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
                }
            }
        }
    }

    /**
     * No music playing: a slow, gentle make-believe beat (~100 bpm), so the fractals still breathe on the
     * screensaver and before the first song starts.
     */
    static void idle(long nowUs, Frame out) {
        double s = nowUs / 1e6;
        out.timeUs = nowUs;
        out.bass = (float) (0.35 + 0.2 * Math.sin(s * 0.7));
        out.mid = (float) (0.3 + 0.15 * Math.sin(s * 0.43 + 1));
        out.high = (float) (0.2 + 0.1 * Math.sin(s * 1.1 + 2));
        out.level = 0.3f;
        long beat = (long) (60e6 / 100);
        out.beatUs = nowUs - nowUs % beat;
        out.beats = (int) (nowUs / beat);
        for (int b = 0; b < BARS; b++) out.bars[b] = (float) (0.25 + 0.15 * Math.sin(s * (0.5 + b * 0.07) + b));
    }
}
