package com.omarchytv.home;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * v2.20 Radiomarchy: the fractals that made the 90s demoscene, drawn live on the TV's GPU (OpenGL ES 2.0) and
 * danced by the music that is playing. Nothing is a video or a picture; every scene is a few lines of maths:
 * <ol>
 * <li>Mandelbrot deep zoom, into a point picked at random on the set's edge each time (one that stays full of
 *     detail all the way down), smooth escape-time colours with palette cycling; every beat kicks the dive.</li>
 * <li>Julia: a random c on the Mandelbrot edge, wandering with the mids.</li>
 * <li>Plasma: the classic sum of sines, with random frequencies, swelled by the bass.</li>
 * <li>Tunnel: a demo tunnel with a random pattern and twist, the bass drives you down it, a ring on each beat.</li>
 * <li>Kaliset: the kaleidoscopic fold z = |z|/|z|^2 + c from a random c, a nebula the highs light up.</li>
 * </ol>
 * Always random: each new scene (every 24-48 beats, with a cross-fade) is a different fractal, with new random
 * geometry and, by default, a new random palette (harmonious hues; the Omarchy theme's colours, a Fractint
 * rainbow and fire are the other choices).
 * <p>
 * Adapts to the TV's hardware ({@link Quality}): the most it can draw smoothly (50+ fps). It starts from what the
 * GPU is (highp floats or not, the GPU's name) and then measures itself: ultra = the screen's full resolution with
 * 2x2 supersampling and 500 iterations; high = full resolution, 320 iterations; medium = full resolution, 180;
 * low = 3/4 resolution, 110 (old sticks only). It steps down when frames run late and back up when there is room.
 * GPUs without highp floats (Mali-400/450) draw the three scenes that look right in half floats.
 */
final class FractalView extends GLSurfaceView {
    static final String[] SCENES = {"mandelbrot", "julia", "plasma", "tunnel", "kaliset"};
    static final String[] PALETTES = {"random", "theme", "rainbow", "fire"};
    static final String[] QUALITIES = {"auto", "ultra", "high", "medium", "low"};

    /** What each quality level draws. */
    static final class Quality {
        final String name;
        final int aa;          // 1 or 2 (2x2 supersampling)
        final float iter;      // Mandelbrot / Julia iterations
        final float scale;     // of the screen's resolution

        Quality(String name, int aa, float iter, float scale) {
            this.name = name;
            this.aa = aa;
            this.iter = iter;
            this.scale = scale;
        }
    }

    static final Quality[] LEVELS = {
            new Quality("ultra", 2, 500, 1f),
            new Quality("high", 1, 320, 1f),
            new Quality("medium", 1, 180, 1f),
            new Quality("low", 1, 110, 1f),   // scale comes from scaleFor(): 720p floor
    };

    private final R3 r = new R3();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile int forced = -1;
    private volatile int palette;
    private volatile int pinnedQuality = -1;   // -1 auto, else an index into LEVELS
    private volatile float dim = 1f;
    private float appliedScale = 1f;
    private volatile Runnable onFail;

    FractalView(Context c) {
        super(c);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        setRenderer(r);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    void setScene(int s) {
        forced = s;
        r.jump = true;
    }

    int scene() {
        return r.cur;
    }

    /** The quality level being drawn now ("ultra", "high"...). */
    String quality() {
        return LEVELS[r.level].name;
    }

    /** Another fractal now (◀ ▶ in the demo), with new random geometry and colours. */
    void nextScene(int dir) {
        int n = ((forced >= 0 ? forced : r.cur) + dir + SCENES.length) % SCENES.length;
        if (r.lowp) while (n == 0 || n == 1) n = (n + dir + SCENES.length) % SCENES.length;
        forced = n;
        r.jump = true;
    }

    /** 0 random (a new palette with every scene), 1 the Omarchy theme, 2 rainbow, 3 fire. */
    void setPalette(int p) {
        palette = Math.max(0, Math.min(PALETTES.length - 1, p));
        r.newPalette = true;
    }

    /** 0 auto (adapts to this TV), else 1..4 = ultra, high, medium, low. */
    void setQuality(int q) {
        pinnedQuality = q <= 0 ? -1 : Math.min(LEVELS.length - 1, q - 1);
        r.qualityChanged = true;
    }

    /** 0..1: how bright the picture is. */
    void setDim(float d) {
        dim = d;
    }

    /** If the shader or the EGL surface cannot be created, run this on the UI thread (Home wallpaper fallback). */
    void setOnFail(Runnable r) {
        onFail = r;
    }

    /** Live palette (four RGB triples). Called on the UI thread, throttled, for the adaptive Home theme. */
    interface PaletteListener {
        void onPalette(float[] pal, boolean frozen);
    }

    private volatile PaletteListener onPalette;
    private long lastPaletteEmit;

    void setOnPalette(PaletteListener l) {
        onPalette = l;
    }

    /** 720p is the lower bound of adaptive scale. Below it, only iterations and fps drop. */
    static float scaleFor(int level, int viewH) {
        if (level < 3) return 1f;
        return viewH > 0 ? Math.min(1f, 720f / viewH) : 720f / 1080f;
    }

    /** WCAG-ish: push {@code color} away from {@code bg} until contrast is about 4.5:1. */
    static int ensureContrast(int color, int bg) {
        if (contrastRatio(color, bg) >= 4.5f) return color;
        int towardWhite = mix(color, 0xFFFFFFFF, 0.12f);
        int towardBlack = mix(color, 0xFF000000, 0.12f);
        for (int i = 0; i < 16; i++) {
            if (contrastRatio(towardWhite, bg) >= 4.5f) return towardWhite;
            if (contrastRatio(towardBlack, bg) >= 4.5f) return towardBlack;
            towardWhite = mix(towardWhite, 0xFFFFFFFF, 0.12f);
            towardBlack = mix(towardBlack, 0xFF000000, 0.12f);
        }
        return contrastRatio(towardWhite, bg) >= contrastRatio(towardBlack, bg) ? towardWhite : towardBlack;
    }

    static float contrastRatio(int a, int b) {
        float l1 = lum(a), l2 = lum(b), hi = Math.max(l1, l2), lo = Math.min(l1, l2);
        return (hi + 0.05f) / (lo + 0.05f);
    }

    static int rgb(float r, float g, float b) {
        int R = Math.max(0, Math.min(255, Math.round(r * 255)));
        int G = Math.max(0, Math.min(255, Math.round(g * 255)));
        int B = Math.max(0, Math.min(255, Math.round(b * 255)));
        return 0xFF000000 | (R << 16) | (G << 8) | B;
    }

    private static float lum(int c) {
        return 0.2126f * lin(((c >> 16) & 255) / 255f) + 0.7152f * lin(((c >> 8) & 255) / 255f) + 0.0722f * lin((c & 255) / 255f);
    }

    private static float lin(float s) {
        return s <= 0.04045f ? s / 12.92f : (float) Math.pow((s + 0.055) / 1.055, 2.4);
    }

    private static int mix(int a, int b, float k) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000
                | (Math.round(ar + (br - ar) * k) << 16)
                | (Math.round(ag + (bg - ag) * k) << 8)
                | Math.round(ab + (bb - ab) * k);
    }


    private void failed() {
        Runnable r = onFail;
        if (r != null) ui.post(r);
    }

    private void applyScale(float s) {
        final float requested = s;
        ui.post(() -> {
            int vh = getHeight();
            float scale = requested;
            if (vh > 0) scale = Math.max(scale, scaleFor(3, vh));
            if (Math.abs(scale - appliedScale) < 0.01f) return;
            appliedScale = scale;
            if (scale >= 0.99f) getHolder().setSizeFromLayout();
            else if (getWidth() > 0 && vh > 0) getHolder().setFixedSize(Math.round(getWidth() * scale), Math.round(vh * scale));
        });
    }

    private void emitPalette(boolean frozen) {
        PaletteListener l = onPalette;
        if (l == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (!frozen && now - lastPaletteEmit < 200) return;
        lastPaletteEmit = now;
        final float[] copy = new float[12];
        System.arraycopy(r.pal, 0, copy, 0, 12);
        final boolean fr = frozen;
        ui.post(() -> l.onPalette(copy, fr));
    }

    // ---- random palettes and geometry ----------------------------------------------------------------

    /** Four colours that go together: a random hue and a random harmony (analogous, triadic, split, tetradic). */
    static float[] randomPalette(Random rnd) {
        float h = rnd.nextFloat();
        float[][] harmonies = {{0, 0.08f, 0.16f, 0.5f}, {0, 1 / 3f, 2 / 3f, 0.08f}, {0, 0.42f, 0.58f, 0.04f}, {0, 0.25f, 0.5f, 0.75f}, {0, 0.06f, 0.5f, 0.56f}};
        float[] hm = harmonies[rnd.nextInt(harmonies.length)];
        float[] p = new float[12];
        for (int i = 0; i < 4; i++) {
            float s = 0.55f + 0.45f * rnd.nextFloat(), v = i == 0 ? 0.55f + 0.2f * rnd.nextFloat() : 0.75f + 0.25f * rnd.nextFloat();
            hsv((h + hm[i]) % 1f, s, v, p, i * 3);
        }
        return p;
    }

    private static void hsv(float h, float s, float v, float[] out, int o) {
        float c = v * s, x = c * (1 - Math.abs((h * 6) % 2 - 1)), m = v - c, r, g, b;
        int k = (int) (h * 6) % 6;
        r = k == 0 || k == 5 ? c : k == 1 || k == 4 ? x : 0;
        g = k == 1 || k == 2 ? c : k == 0 || k == 3 ? x : 0;
        b = k == 3 || k == 4 ? c : k == 2 || k == 5 ? x : 0;
        out[o] = r + m;
        out[o + 1] = g + m;
        out[o + 2] = b + m;
    }

    private static float[] fixedPalette(int which) {
        Theme t = Theme.cur();
        int[] c;
        switch (which) {
            case 2: c = new int[]{0xFFFF2A6D, 0xFFFFD23F, 0xFF3BCEAC, 0xFF5B5BFF}; break;    // a Fractint rainbow
            case 3: c = new int[]{0xFF2A0800, 0xFFB21F00, 0xFFFF8A00, 0xFFFFF0A0}; break;    // fire
            default: c = new int[]{t.accent, t.accent2 != 0 ? t.accent2 : t.magenta, t.cyan, t.magenta != 0 ? t.magenta : t.yellow};
        }
        float[] p = new float[12];
        for (int i = 0; i < 4; i++) {
            p[i * 3] = ((c[i] >> 16) & 255) / 255f;
            p[i * 3 + 1] = ((c[i] >> 8) & 255) / 255f;
            p[i * 3 + 2] = (c[i] & 255) / 255f;
        }
        return p;
    }

    /**
     * A random point right on the Mandelbrot set's edge: one that escapes, but only after many iterations, so a zoom
     * into it shows detail all the way down. Doubles on the CPU; a few milliseconds.
     */
    static double[] edgePoint(Random rnd, int minIter, int maxIter) {
        double[] best = {-0.743643887, 0.131825904};
        int bestN = 0;
        for (int tries = 0; tries < 6000; tries++) {
            // near the boundary: around the main cardioid and the period-2 bulb, out to the needle
            double a = rnd.nextDouble() * 2 * Math.PI, cx, cy;
            if (rnd.nextInt(5) == 0) {
                cx = -1 + 0.25 * Math.cos(a);
                cy = 0.25 * Math.sin(a);
            } else {
                cx = 0.5 * Math.cos(a) - 0.25 * Math.cos(2 * a);
                cy = 0.5 * Math.sin(a) - 0.25 * Math.sin(2 * a);
            }
            double rr = 0.002 + 0.06 * rnd.nextDouble() * rnd.nextDouble();
            cx += (rnd.nextDouble() - 0.5) * rr;
            cy += (rnd.nextDouble() - 0.5) * rr;
            int n = escape(cx, cy, maxIter);
            if (n >= minIter && n < maxIter) return new double[]{cx, cy};
            if (n < maxIter && n > bestN) {
                bestN = n;
                best = new double[]{cx, cy};
            }
        }
        return best;
    }

    static int escape(double cx, double cy, int max) {
        double x = 0, y = 0;
        for (int i = 0; i < max; i++) {
            double xx = x * x - y * y + cx;
            y = 2 * x * y + cy;
            x = xx;
            if (x * x + y * y > 16) return i;
        }
        return max;
    }

    // ---- the renderer (GL thread) ----------------------------------------------------------------------

    private final class R3 implements Renderer {
        final Random rnd = new Random();
        boolean lowp;
        int prog, aPos, uRes, uTime, uBass, uMid, uHigh, uLevel, uPulse, uScene, uScene2, uMix, uPal, uCycle, uZoom, uCenter, uIter,
                uRot, uDim, uAA, uP;
        FloatBuffer quad;
        int w = 1, h = 1;
        final AudioBands.Frame f = new AudioBands.Frame();
        final long t0 = SystemClock.elapsedRealtime();
        int cur = 4, next = -1, lastBeats = -1, beatCount, sceneBeats = 32;
        float mix, cycle, dive, zoomKick, rotDir = 1, diveSpeed = 1;
        long sceneSince, mixStart;
        volatile boolean jump, newPalette = true, qualityChanged;
        float[] pal = new float[12], palFrom = new float[12], palTo = new float[12];
        long palStart;
        double[] center = {-0.743643887, 0.131825904};
        final float[] params = new float[SCENES.length * 4];   // vec4 per scene: its random geometry
        // quality
        int level = 1;
        long frames, frameNs, lastNs, goodSince, late, lastDrawNs;
        boolean capFps = true, freezePalette;
        float iterMul = 1f;
        int fpsCap = 30;

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            int[] range = new int[2], precision = new int[1];
            GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER, GLES20.GL_HIGH_FLOAT, range, 0, precision, 0);
            lowp = precision[0] < 16;
            String gpu = String.valueOf(GLES20.glGetString(GLES20.GL_RENDERER));
            level = firstLevel(gpu, lowp);
            if (lowp && cur < 2) cur = 2;
            prog = link(VERT, (lowp ? "precision mediump float;\n#define LOWP 1\n" : "precision highp float;\n") + FRAG);
            if (prog == 0) { failed(); return; }
            aPos = GLES20.glGetAttribLocation(prog, "aPos");
            uRes = loc("uRes");
            uTime = loc("uTime");
            uBass = loc("uBass");
            uMid = loc("uMid");
            uHigh = loc("uHigh");
            uLevel = loc("uLevel");
            uPulse = loc("uPulse");
            uScene = loc("uScene");
            uScene2 = loc("uScene2");
            uMix = loc("uMix");
            uPal = loc("uPal");
            uCycle = loc("uCycle");
            uZoom = loc("uZoom");
            uCenter = loc("uCenter");
            uIter = loc("uIter");
            uRot = loc("uRot");
            uDim = loc("uDim");
            uAA = loc("uAA");
            uP = loc("uP");
            float[] v = {-1, -1, 1, -1, -1, 1, 1, 1};
            quad = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(v).position(0);
            sceneSince = SystemClock.elapsedRealtime();
            for (int s = 0; s < SCENES.length; s++) randomise(s);
            cur = lowp ? 2 + rnd.nextInt(3) : rnd.nextInt(SCENES.length);
            applyScale(scaleFor(level, h > 0 ? h : getHeight()));
        }

        private int loc(String n) {
            return GLES20.glGetUniformLocation(prog, n);
        }

        /** Where to start: half-float GPUs and old Mali/Adreno 3xx at "low"/"medium", the rest at "high". */
        private int firstLevel(String gpu, boolean lowp) {
            if (pinnedQuality >= 0) return pinnedQuality;
            String g = gpu.toLowerCase(java.util.Locale.ROOT);
            if (lowp || g.contains("mali-4") || g.contains("mali-3") || g.contains("videocore iv")) return 3;
            if (g.contains("mali-t7") || g.contains("mali-g31") || g.contains("mali-g51") || g.contains("adreno (tm) 3") || g.contains("powervr ge8")) return 2;
            return 1;   // measured from here: ultra once it has room
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            w = Math.max(1, width);
            h = Math.max(1, height);
            GLES20.glViewport(0, 0, w, h);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            if (prog == 0) return;
            long now = SystemClock.elapsedRealtime();
            long ns = System.nanoTime();
            if (capFps && fpsCap > 0 && lastDrawNs != 0 && ns - lastDrawNs < 1_000_000_000L / fpsCap) return;
            lastDrawNs = ns;
            float dt = Math.min(0.1f, lastNs == 0 ? 0.016f : (ns - lastNs) / 1e9f);
            measure(ns, now);
            lastNs = ns;
            RadioPlayer rp = RadioPlayer.peek();
            boolean music = rp != null && rp.frame(f);
            if (!music) AudioBands.idle(now * 1000L, f);
            float pulse = (float) Math.exp(-Math.max(0, f.timeUs - f.beatUs) / 160000.0);
            if (f.beats != lastBeats) {
                if (lastBeats >= 0 && f.beats > lastBeats) {
                    beatCount += f.beats - lastBeats;
                    zoomKick = Math.min(1.5f, zoomKick + 0.6f);
                }
                lastBeats = f.beats;
            }
            pickScene(now, music);
            if (!freezePalette) updatePalette(now);
            emitPalette(freezePalette);
            cycle += dt * (0.03f + 0.22f * f.level + 0.25f * f.high) + pulse * dt * 0.6f;
            // the Mandelbrot dive: in by e^-t, faster with the bass and on beats; then a new random edge point
            zoomKick *= (float) Math.exp(-dt * 3);
            dive += dt * diveSpeed * (0.09f + 0.22f * f.bass + 0.25f * zoomKick);
            // as deep as 32-bit floats stay sharp at this resolution: >= 4 float steps per pixel (1080p: e^-9.9)
            float maxDive = lowp ? 4f : (float) Math.log(2.6 / (2.4e-7 * h));
            if (dive > maxDive) {
                dive = 0.8f + 1.7f * rnd.nextFloat();
                newCenter();
            }
            float zoom = (float) (2.6 * Math.exp(-dive));
            float t = (now - t0) / 1000f;
            Quality q = LEVELS[level];

            GLES20.glUseProgram(prog);
            GLES20.glUniform2f(uRes, w, h);
            GLES20.glUniform1f(uTime, t);
            GLES20.glUniform1f(uBass, f.bass);
            GLES20.glUniform1f(uMid, f.mid);
            GLES20.glUniform1f(uHigh, f.high);
            GLES20.glUniform1f(uLevel, f.level);
            GLES20.glUniform1f(uPulse, pulse);
            GLES20.glUniform1i(uScene, cur);
            GLES20.glUniform1i(uScene2, next < 0 ? cur : next);
            GLES20.glUniform1f(uMix, next < 0 ? 0f : mix);
            GLES20.glUniform3fv(uPal, 4, pal, 0);
            GLES20.glUniform1f(uCycle, cycle);
            GLES20.glUniform1f(uZoom, zoom);
            GLES20.glUniform2f(uCenter, (float) center[0], (float) center[1]);
            GLES20.glUniform1f(uIter, lowp ? 64 : Math.min(q.iter, q.iter * 0.55f + dive * 30) * iterMul);
            GLES20.glUniform1f(uRot, rotDir * (t * 0.03f + dive * 0.15f));
            GLES20.glUniform1f(uDim, dim);
            GLES20.glUniform1f(uAA, q.aa);
            GLES20.glUniform4fv(uP, SCENES.length, params, 0);
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        private void newCenter() {
            double[] c = edgePoint(rnd, 400, 4000);
            center = c;
            rotDir = rnd.nextBoolean() ? 1 : -1;
            diveSpeed = 0.8f + 0.5f * rnd.nextFloat();
        }

        /** New random geometry for scene {@code s}. */
        private void randomise(int s) {
            int o = s * 4;
            switch (s) {
                case 0:
                    newCenter();
                    dive = 0.8f + 1.7f * rnd.nextFloat();   // start already inside, a random way down
                    break;
                case 1: {   // Julia: c on the Mandelbrot edge (connected, lacy sets), and how far it wanders
                    double[] c = edgePoint(rnd, 30, 200);
                    params[o] = (float) c[0];
                    params[o + 1] = (float) c[1];
                    params[o + 2] = 0.01f + 0.03f * rnd.nextFloat();
                    params[o + 3] = 1.1f + 0.4f * rnd.nextFloat();   // zoom
                    break;
                }
                case 2:   // plasma: four frequencies
                    for (int i = 0; i < 4; i++) params[o + i] = 1.5f + 4f * rnd.nextFloat();
                    break;
                case 3:   // tunnel: rings, segments, twist, speed
                    params[o] = 2 + rnd.nextInt(5);
                    params[o + 1] = 2 + rnd.nextInt(7);
                    params[o + 2] = (rnd.nextFloat() - 0.5f) * 3f;
                    params[o + 3] = 0.6f + 0.8f * rnd.nextFloat();
                    break;
                default:  // kaliset: c, zoom, folds
                    params[o] = -0.9f + 0.45f * rnd.nextFloat();
                    params[o + 1] = -0.8f + 0.5f * rnd.nextFloat();
                    params[o + 2] = 0.6f + 0.7f * rnd.nextFloat();
                    params[o + 3] = 10 + rnd.nextInt(7);
            }
        }

        private void pickScene(long now, boolean music) {
            int pin = forced;
            if (jump) {
                jump = false;
                if (pin >= 0) startMix(pin, now);
            }
            if (next >= 0) {
                mix = Math.min(1f, (now - mixStart) / 1800f);
                if (mix >= 1f) {
                    cur = next;
                    next = -1;
                    mix = 0;
                    sceneSince = now;
                    beatCount = 0;
                    sceneBeats = 24 + rnd.nextInt(25);
                }
                return;
            }
            if (pin >= 0) {   // a pinned fractal still changes its geometry and colours now and then
                if (music ? beatCount >= sceneBeats : now - sceneSince > 40000) startMix(pin, now);
                return;
            }
            boolean due = music ? beatCount >= sceneBeats || now - sceneSince > 75000 : now - sceneSince > 35000;
            if (due) {
                int n;
                do n = rnd.nextInt(SCENES.length); while (n == cur || (lowp && n < 2));
                startMix(n, now);
            }
        }

        private void startMix(int n, long now) {
            if (n == cur) {   // the same fractal: a fresh one of it, without a cross-fade between two of the same
                randomise(n);
                newPalette = true;
                sceneSince = now;
                beatCount = 0;
                return;
            }
            randomise(n);
            next = n;
            mix = 0;
            mixStart = now;
            newPalette = true;
        }

        /** A new palette blends in over the cross-fade (random each scene, or the chosen fixed one). */
        private void updatePalette(long now) {
            if (newPalette) {
                newPalette = false;
                System.arraycopy(pal, 0, palFrom, 0, 12);
                palTo = palette == 0 ? randomPalette(rnd) : fixedPalette(palette);
                palStart = now;
                if (palFrom[0] + palFrom[1] + palFrom[2] + palFrom[3] == 0) System.arraycopy(palTo, 0, palFrom, 0, 12);
            }
            float k = Math.min(1f, (now - palStart) / 1800f);
            k = k * k * (3 - 2 * k);
            for (int i = 0; i < 12; i++) pal[i] = palFrom[i] + (palTo[i] - palFrom[i]) * k;
        }

        /**
         * Adapts to the TV: start at the current level (high on a typical TV). Every ~2 s, or sooner after a
         * streak of late frames, if the average is over 21 ms, one level down; the last step is the 720p
         * floor. Still stuttering there, cap fps (~30) and freeze the palette (never drop resolution further).
         * After 20 s under 11 ms, step back up. A pinned level stays as chosen, but "low" still freezes.
         */
        private void measure(long ns, long now) {
            if (qualityChanged) {
                qualityChanged = false;
                if (pinnedQuality >= 0) level = pinnedQuality;
                applyLowPolicy();
                applyScale(scaleFor(level, h > 0 ? h : getHeight()));
                frames = 0;
                frameNs = 0;
                late = 0;
                goodSince = now;
            }
            if (lastNs != 0) {
                long d = ns - lastNs;
                if (d < 200_000_000L) {   // a pause (the app in the background) is not a slow frame
                    frameNs += d;
                    frames++;
                    if (d > 24_000_000L) late++;
                    else late = 0;
                }
            }
            boolean rush = late >= 8 && pinnedQuality < 0;
            if (frames < 120 && !rush) return;
            double avg = frames > 0 ? frameNs / (double) frames / 1e6 : 30;
            if (frames >= 60) {
                android.util.Log.i("Radiomarchy", "fps=" + String.format(java.util.Locale.US, "%.1f", 1000.0 / avg)
                        + " frame=" + String.format(java.util.Locale.US, "%.1f", avg) + "ms level=" + LEVELS[level].name
                        + " scale=" + scaleFor(level, h) + (capFps ? " cap=" + fpsCap : "") + (freezePalette ? " pal=static" : ""));
            }
            frames = 0;
            frameNs = 0;
            late = 0;
            if (pinnedQuality >= 0) { applyLowPolicy(); return; }
            if (avg > 21) {
                if (level < LEVELS.length - 1) {
                    level++;
                    goodSince = now;
                    applyLowPolicy();
                    applyScale(scaleFor(level, h > 0 ? h : getHeight()));
                } else {
                    capFps = true;
                    freezePalette = true;
                    if (fpsCap > 15) fpsCap = 15;
                    if (iterMul > 0.55f) iterMul = 0.7f;
                    applyLowPolicy();
                }
            } else if (avg < 11) {
                if (goodSince == 0) goodSince = now;
                if (now - goodSince > 20000) {
                    if (capFps || freezePalette || iterMul < 0.99f) {
                        capFps = false;
                        freezePalette = false;
                        iterMul = 1f;
                    } else if (level > 0) {
                        level--;
                    }
                    goodSince = now;
                    applyLowPolicy();
                    applyScale(scaleFor(level, h > 0 ? h : getHeight()));
                }
            } else {
                goodSince = now;
            }
        }

        private void applyLowPolicy() {
            capFps = true;
            if (level >= 3) { freezePalette = true; if (fpsCap > 20) fpsCap = 20; }
            else if (pinnedQuality >= 0) { freezePalette = false; iterMul = 1f; fpsCap = 30; }
            else if (fpsCap < 20) { /* kept by the watchdog after the 720p floor */ }
            else fpsCap = 30;
        }

        private int link(String vs, String fs) {
            int v = shader(GLES20.GL_VERTEX_SHADER, vs), f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, v);
            GLES20.glAttachShader(p, f);
            GLES20.glLinkProgram(p);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) {
                android.util.Log.w("Radiomarchy", "link: " + GLES20.glGetProgramInfoLog(p));
                return 0;
            }
            return p;
        }

        private int shader(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) android.util.Log.w("Radiomarchy", "shader: " + GLES20.glGetShaderInfoLog(s));
            return s;
        }
    }

    static final String VERT = "attribute vec2 aPos;\nvarying vec2 vUv;\nvoid main(){ vUv = aPos; gl_Position = vec4(aPos, 0.0, 1.0); }\n";

    /**
     * One fragment shader, five scenes; uScene2 + uMix cross-fade two of them; uP[s] holds scene s's random geometry;
     * uAA 2 = 2x2 supersampling. Shared word for word with the webOS / Tizen app (webos/app/fractals.js).
     */
    static final String FRAG = ""
            + "varying vec2 vUv;\n"
            + "uniform vec2 uRes; uniform float uTime, uBass, uMid, uHigh, uLevel, uPulse, uMix, uCycle, uZoom, uIter, uRot, uDim, uAA;\n"
            + "uniform int uScene, uScene2; uniform vec3 uPal[4]; uniform vec2 uCenter; uniform vec4 uP[5];\n"
            + "vec3 pal(float t){ t = fract(t) * 4.0; float k = fract(t); k = k * k * (3.0 - 2.0 * k);\n"
            + "  if (t < 1.0) return mix(uPal[0], uPal[1], k); if (t < 2.0) return mix(uPal[1], uPal[2], k);\n"
            + "  if (t < 3.0) return mix(uPal[2], uPal[3], k); return mix(uPal[3], uPal[0], k); }\n"
            + "vec2 rot(vec2 p, float a){ float c = cos(a), s = sin(a); return vec2(c * p.x - s * p.y, s * p.x + c * p.y); }\n"
            + "vec3 shade(float n, float m2, float de, float px, float glow){\n"
            + "  float sn = max(0.0, n - log2(max(1.0001, log2(m2))) + 4.0);\n"
            + "  float t = log2(sn + 1.0) * 0.38 + uCycle;\n"
            + "  float fade = clamp(log2(sn + 1.0) / 6.0, 0.0, 1.0);\n"
            + "  float edge = exp(-de / (px * 2.5));\n"                          // filaments and the set's edge light up
            + "  return pal(t) * (0.16 + 0.6 * fade) * (0.85 + 0.5 * uLevel) + pal(t + 0.25) * edge * (0.8 + glow); }\n"
            + "vec3 mandel(vec2 p){\n"
            + "  vec2 c = uCenter + rot(p, uRot) * uZoom; vec2 z = vec2(0.0), dz = vec2(0.0); float n = 0.0, m2 = 0.0, trap = 1e9;\n"
            + "  for (int i = 0; i < 500; i++) { if (float(i) >= uIter) break;\n"
            + "    dz = 2.0 * vec2(z.x * dz.x - z.y * dz.y, z.x * dz.y + z.y * dz.x) + vec2(1.0, 0.0);\n"
            + "    z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c;\n"
            + "    m2 = dot(z, z); trap = min(trap, abs(z.y)); if (m2 > 256.0) break; n += 1.0; }\n"
            + "  if (m2 <= 256.0) return pal(uCycle * 2.0 + trap * 3.0) * (0.05 + 0.22 * uPulse);\n"
            + "  float de = 0.25 * sqrt(m2) * log(m2) / max(length(dz), 1e-20);\n"
            + "  return shade(n, m2, de, 2.0 * uZoom / uRes.y, uPulse); }\n"
            + "vec3 julia(vec2 p){\n"
            + "  vec4 q = uP[1]; float a = uTime * 0.21 + uMid * 1.5;\n"
            + "  vec2 c = q.xy + q.z * vec2(cos(a), sin(a * 1.3)) * (1.0 + uBass);\n"
            + "  float sc = q.w - 0.1 * uPulse; vec2 z = rot(p, uTime * 0.05) * sc, dz = vec2(1.0, 0.0); float n = 0.0, m2 = 0.0, trap = 1e9;\n"
            + "  for (int i = 0; i < 500; i++) { if (float(i) >= uIter * 0.7) break;\n"
            + "    dz = 2.0 * vec2(z.x * dz.x - z.y * dz.y, z.x * dz.y + z.y * dz.x);\n"
            + "    z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c;\n"
            + "    m2 = dot(z, z); trap = min(trap, length(z - vec2(0.0, 0.3 * sin(uTime * 0.3)))); if (m2 > 256.0) break; n += 1.0; }\n"
            + "  if (m2 <= 256.0) return pal(uCycle + trap) * (0.08 + 0.2 * uPulse) + pal(uCycle + 0.5) * exp(-trap * 5.0) * (0.3 + 0.7 * uHigh);\n"
            + "  float de = 0.25 * sqrt(m2) * log(m2) / max(length(dz), 1e-20);\n"
            + "  return shade(n, m2, de, 2.0 * sc / uRes.y, uPulse) + pal(uCycle + 0.5) * exp(-trap * 6.0) * 0.4 * uHigh; }\n"
            + "vec3 plasma(vec2 p){\n"
            + "  vec4 q = uP[2]; float t = uTime * (0.6 + 0.5 * uMid); p *= 1.3 + 0.5 * uBass;\n"
            + "  float v = sin(p.x * q.x + t) + sin((p.y * q.y + t) * 0.8) + sin((p.x + p.y) * q.z * 0.7 + t * 1.3)\n"
            + "    + sin(length(p * q.w + vec2(sin(t * 0.3), cos(t * 0.5)) * 3.0) - t * 2.0);\n"
            + "  return pal(v * 0.125 + uCycle) * (0.7 + 0.5 * uBass + 0.4 * uPulse); }\n"
            + "vec3 tunnel(vec2 p){\n"
            + "  vec4 q = uP[3];\n"
            + "  p = rot(p, uTime * 0.2 * q.z + 0.3 * sin(uTime * 0.37)); p += 0.15 * vec2(sin(uTime * 0.5), cos(uTime * 0.4));\n"
            + "  float r = max(length(p), 0.001), a = atan(p.y, p.x);\n"
            + "  float u = 0.35 / r + uTime * q.w * (0.5 + 0.9 * uBass), v = a / 3.14159265 * q.y + q.z * 0.2 / r + uTime * 0.07;\n"
            + "  float ch = mod(floor(u * q.x) + floor(v), 2.0);\n"
            + "  vec3 c = pal(u * 0.07 + uCycle) * (0.45 + 0.55 * ch);\n"
            + "  float ring = exp(-abs(fract(u * 0.5) - 0.5) * 18.0) * uPulse;\n"
            + "  return c * smoothstep(0.0, 0.5, r) * (0.8 + 0.4 * uLevel) + ring * pal(uCycle + 0.5) * 1.2; }\n"
            + "vec3 kali(vec2 p){\n"
            + "  vec4 q = uP[4]; vec2 z = rot(p, uTime * 0.04) * q.z * (0.9 + 0.1 * sin(uTime * 0.13));\n"
            + "  vec2 c = q.xy + vec2(0.1 * sin(uTime * 0.071), 0.1 * cos(uTime * 0.053)) + vec2(uMid, -uBass) * 0.035;\n"
            + "  float acc = 0.0, lo = 1e9;\n"
            + "  for (int i = 0; i < 16; i++) { if (float(i) >= q.w) break; z = abs(z) / max(dot(z, z), 0.0005) + c; float l = length(z); lo = min(lo, l); acc += exp(-l * 1.6); }\n"
            + "  vec3 col = pal(acc * 0.08 + uCycle) * acc * 0.17;\n"
            + "  return col * (0.7 + 0.6 * uHigh + 0.4 * uPulse) + pal(uCycle + 0.3) * exp(-lo * 9.0) * (0.3 + uHigh); }\n"
            + "vec3 scene(int s, vec2 p){\n"
            + "#ifndef LOWP\n"
            + "  if (s == 0) return mandel(p); if (s == 1) return julia(p);\n"
            + "#endif\n"
            + "  if (s == 3) return tunnel(p); if (s == 4) return kali(p); return plasma(p); }\n"
            + "vec3 frame(vec2 uv){\n"
            + "  vec2 p = uv * vec2(uRes.x / uRes.y, 1.0);\n"
            + "  vec3 c = scene(uScene, p);\n"
            + "  if (uMix > 0.0) c = mix(c, scene(uScene2, p), smoothstep(0.0, 1.0, uMix));\n"
            + "  return c; }\n"
            + "void main(){\n"
            + "  vec3 c = frame(vUv);\n"
            + "  if (uAA > 1.5) {\n"                                                // 2x2 supersampling (ultra)
            + "    vec2 px = 1.0 / uRes;\n"
            + "    c += frame(vUv + vec2(px.x, 0.0)) + frame(vUv + vec2(0.0, px.y)) + frame(vUv + px);\n"
            + "    c *= 0.25; }\n"
            + "  c = c / (1.0 + c * 0.35);\n"                                     // soft shoulder: bright, never clipped flat
            + "  float vig = 1.0 - 0.28 * dot(vUv * 0.75, vUv * 0.75);\n"
            + "  float n = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233)) + uTime) * 43758.5453);\n"
            + "  c = c * vig * uDim + (n - 0.5) / 255.0;\n"                       // dither: no banding in the dark gradients
            + "  gl_FragColor = vec4(max(c, 0.0), 1.0); }\n";
}
