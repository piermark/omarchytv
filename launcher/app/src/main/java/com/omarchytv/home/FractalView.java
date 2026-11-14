package com.omarchytv.home;

import android.app.ActivityManager;
import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;

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
 * Adapts to the TV's hardware ({@link Quality}). The GL surface is always a fixed buffer
 * ({@code SurfaceHolder.setFixedSize}), never larger than 1080p (a 4K panel with a 1080p UI must not get a 4K
 * surface). high/ultra draw at that cap; medium and low use the 720p floor. It measures the real present cost
 * (time between {@code onDrawFrame} starts, which includes {@code eglSwapBuffers}) and steps down within a
 * couple of seconds on backpressure; below 720p only iterations and fps drop. Home wallpaper starts at 24 fps
 * and, on Mali-T8xx or <= 2 GB of RAM, at the medium/720p level. GPUs without highp floats (Mali-400/450)
 * start at low and draw the three scenes that look right in half floats.
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
            new Quality("light", 1, 24, 1f),  // baked texture + cheap palette/zoom blit; auto on weak GPUs
    };
    static final int LIGHT_LEVEL = 4;

    private final R3 r = new R3();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile int forced = -1;
    private volatile int palette;
    private volatile int pinnedQuality = -1;   // -1 auto, else an index into LEVELS
    private volatile float dim = 1f;
    private float appliedScale = 1f;
    private int appliedW, appliedH;
    private volatile Runnable onFail;
    private volatile SafetyListener onSafety;
    private volatile Runnable onFirstPresent;
    private boolean homeWallpaper;
    private boolean preferLight;
    private long ramBytes;
    private boolean pumpOn;
    private int skipFrames;
    private long lastUiFrameNs, lastRequestNs, graceStartMs;
    private volatile int presentedFrames;
    private int stallCount;
    private final long[] stallAt = new long[8];
    private boolean safetyTripped;

    FractalView(Context c) {
        super(c);
        ramBytes = totalRam(c);
        preferLight = c.getApplicationContext()
                .getSharedPreferences("omarchytv", Context.MODE_PRIVATE)
                .getBoolean(PREF_LIGHT, false);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        setRenderer(r);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    /** Home wallpaper: 24 fps, start at the conservative buffer, never starve the UI. */
    void prepareHome() {
        homeWallpaper = true;
        r.fpsCap = HOME_FPS;
        r.level = pinnedQuality >= 0 ? pinnedQuality : firstLevel("", false, ramBytes, preferLight);
        r.applyLowPolicy();
        applyBuffer();
    }

    /**
     * Full-screen Radiomarchy (the music app and its screensaver): the same 1080p cap, 720p floor
     * and present-cost watchdog as Home. Mali-T8xx and <= 2 GB start at the light (baked) level;
     * tight devices also start at 24 fps and only climb to 30 when there is headroom.
     */
    void prepareScene() {
        homeWallpaper = false;
        r.fpsCap = sceneFps("", ramBytes);
        r.level = pinnedQuality >= 0 ? pinnedQuality : firstLevel("", false, ramBytes, preferLight);
        r.applyLowPolicy();
        applyBuffer();
    }

    void yieldForUi() {
        skipFrames = 2;
    }

    interface SafetyListener {
        void onSafety(String why);
    }

    void setOnSafety(SafetyListener l) {
        onSafety = l;
    }

    void setOnFirstPresent(Runnable r) {
        onFirstPresent = r;
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

    /** 0 auto (adapts to this TV), else 1..4 = ultra, high, medium, low. Light is auto-only. */
    void setQuality(int q) {
        pinnedQuality = q <= 0 ? -1 : Math.min(3, q - 1);
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

    static final int MAX_BUFFER_H = 1080, FLOOR_BUFFER_H = 720, HOME_FPS = 24, SAVER_FPS = 30;
    static final int WATCH_FRAMES = 16, LATE_RUSH = 4;
    static final long TWO_GB = 2L * 1024 * 1024 * 1024;
    /** Ignore short Choreographer stalls until first layout, N presented frames, and this long after attach/resume/focus.
     *  Grace always ends after {@link #GRACE_MAX_MS}, even if few frames have been presented
     *  (Sony fix6: 0.15 fps never reached 8 frames, so grace never ended). */
    static final int GRACE_FRAMES = 8;
    static final long GRACE_MS = 5000L, GRACE_MAX_MS = 8000L;
    static final long STALL_MS = 500L, STALL_NS = 500_000_000L, STALL_WINDOW_MS = 20_000L;
    static final long FATAL_STALL_MS = 2000L, SLOW_PRESENT_MS = 1000L, SLOW_PRESENT_NS = 1_000_000_000L;
    static final int STALLS_TO_KILL = 3, SLOW_PRESENTS_TO_KILL = 3, STALL_DEGRADE = 1, STALL_KILL = 2;
    /** Home wallpaper at the full-shader floor: 12 fps, shader drawn into a smaller FBO and linearly upscaled to 720p. */
    static final int CHEAP_FPS = 12, FBO_SCALE_FLOOR = 2, FBO_SCALE_CHEAP = 3;
    /** Light bake: a small still of the fractal; per-frame cost is a palette/zoom blit at 720p. */
    static final int BAKE_H = 160, BAKE_ITERS = 20;
    static final String PREF_LIGHT = "fractals.prefer_light";
    static final String PREF_GPU = "fractals.gpu";
    /** 2 GB-class devices often report a little over 2 GiB. */
    static final long TIGHT_RAM = TWO_GB + 512L * 1024 * 1024;

    /** Backing-store height: never above {@link #MAX_BUFFER_H}; medium/low use the 720p floor. */
    static int bufferHeight(int level, int viewH, int maxH) {
        if (maxH <= 0) maxH = MAX_BUFFER_H;
        if (viewH <= 0) viewH = MAX_BUFFER_H;
        int cap = Math.min(viewH, maxH);
        if (level <= 1) return Math.max(1, cap);
        return Math.max(1, Math.min(cap, FLOOR_BUFFER_H));
    }

    static int[] bufferSize(int level, int viewW, int viewH, int maxH) {
        if (viewW <= 0) viewW = 1920;
        if (viewH <= 0) viewH = 1080;
        int h = bufferHeight(level, viewH, maxH);
        int w = Math.max(1, Math.round(viewW * (h / (float) viewH)));
        return new int[] { w, h };
    }

    /** Ratio of the backing-store height to the view. 720p is the lower bound. Never above 1080p. */
    static float scaleFor(int level, int viewH) {
        if (viewH <= 0) viewH = 1080;
        return bufferHeight(level, viewH, MAX_BUFFER_H) / (float) viewH;
    }

    /**
     * Where to start: half-float and very old GPUs at low; Mali-T8xx/T7xx, similar modest GPUs and
     * devices with at most 2 GB of RAM at medium (720p); the rest at high (1080p cap).
     */
    /** Full-screen Radiomarchy / screensaver fps: 24 on Mali-T8xx or <= 2 GB, otherwise 30. */
    static int sceneFps(String gpu, long ramBytes) {
        if (ramBytes > 0 && ramBytes <= TWO_GB) return HOME_FPS;
        String g = gpu == null ? "" : gpu.toLowerCase(java.util.Locale.ROOT);
        if (g.contains("mali-t8") || g.contains("mali-t7") || g.contains("mali-g31")
                || g.contains("mali-g51") || g.contains("adreno (tm) 3") || g.contains("powervr ge8"))
            return HOME_FPS;
        return SAVER_FPS;
    }

    static int firstLevel(String gpu, boolean lowp, long ramBytes) {
        return firstLevel(gpu, lowp, ramBytes, false);
    }

    /** Mali-T8xx, <= 2 GB, very old GPUs, or a previous session that had to kill the full shader: start at light. */
    static int firstLevel(String gpu, boolean lowp, long ramBytes, boolean preferLight) {
        if (preferLight) return LIGHT_LEVEL;
        String g = gpu == null ? "" : gpu.toLowerCase(java.util.Locale.ROOT);
        if (lowp || g.contains("mali-4") || g.contains("mali-3") || g.contains("videocore iv")) return LIGHT_LEVEL;
        boolean tightRam = ramBytes > 0 && ramBytes <= TWO_GB;
        if (tightRam || g.contains("mali-t8") || g.contains("mali-t7") || g.contains("mali-g31")
                || g.contains("mali-g51") || g.contains("adreno (tm) 3") || g.contains("powervr ge8"))
            return LIGHT_LEVEL;
        return 1;
    }

    /** 2 GB-class RAM, including a little over 2 GiB as some TVs report. */
    static boolean tightRam(long ramBytes) {
        return ramBytes > 0 && ramBytes <= TIGHT_RAM;
    }

    static boolean weakGpu(String gpu) {
        String g = gpu == null ? "" : gpu.toLowerCase(java.util.Locale.ROOT);
        return g.contains("mali-t8") || g.contains("mali-t7") || g.contains("mali-g31")
                || g.contains("mali-g51") || g.contains("adreno (tm) 3") || g.contains("powervr ge8")
                || g.contains("mali-4") || g.contains("mali-3") || g.contains("videocore iv");
    }

    /**
     * Radiomarchy full-screen and the fractals screensaver: on a weak TV (Mali-T8xx / T7xx,
     * Mali-400/450, &le; 2 GB, a low-RAM flag, or after a kill) do not create a GL surface at all.
     * A plain static theme background opens instantly, like Cinemarchy. Live GL stays on capable GPUs.
     */
    static boolean preferPlain(String gpu, long ramBytes, boolean preferLight, boolean lowRamDevice) {
        return preferLight || lowRamDevice || tightRam(ramBytes) || weakGpu(gpu);
    }

    static boolean preferPlain(Context c) {
        if (c == null) return false;
        boolean prefer = false, low = false;
        try {
            prefer = c.getApplicationContext()
                    .getSharedPreferences("omarchytv", Context.MODE_PRIVATE)
                    .getBoolean(PREF_LIGHT, false);
        } catch (Throwable ignored) {
        }
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) low = am.isLowRamDevice();
        } catch (Throwable ignored) {
        }
        return preferPlain(cachedGpu(c), totalRam(c), prefer, low);
    }

    static String cachedGpu(Context c) {
        if (c == null) return "";
        try {
            return c.getApplicationContext()
                    .getSharedPreferences("omarchytv", Context.MODE_PRIVATE)
                    .getString(PREF_GPU, "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    static void rememberGpu(Context c, String gpu) {
        if (c == null || gpu == null || gpu.isEmpty()) return;
        try {
            c.getApplicationContext()
                    .getSharedPreferences("omarchytv", Context.MODE_PRIVATE)
                    .edit().putString(PREF_GPU, gpu).apply();
        } catch (Throwable ignored) {
        }
    }

    static boolean isLight(int level) {
        return level >= LIGHT_LEVEL;
    }

    static boolean isCheapest(int level) {
        return level >= 3;
    }

    /** The interval overruns the fps-cap period: swap or the GPU held the GL thread. */
    static boolean isBackpressured(long intervalNs, int fpsCap) {
        long period = 1_000_000_000L / Math.max(1, fpsCap);
        return intervalNs > period + 8_000_000L;
    }

    static boolean shouldDisableAtFloor(int level, int slowWindows) {
        return isCheapest(level) && slowWindows >= 2;
    }

    /** Cold-start / first-layout / resume: a 600 ms Choreographer gap is normal, not an ANR.
     *  After {@link #GRACE_MAX_MS} grace is over even if almost no frames were presented. */
    static boolean inGracePeriod(boolean laidOut, int presentedFrames, long elapsedMs) {
        if (elapsedMs >= GRACE_MAX_MS) return false;
        if (!laidOut) return true;
        return presentedFrames < GRACE_FRAMES || elapsedMs < GRACE_MS;
    }

    static boolean isLongStall(long gapMs) {
        return gapMs >= FATAL_STALL_MS;
    }

    static boolean isSlowPresent(long intervalNs) {
        return intervalNs >= SLOW_PRESENT_NS;
    }

    /**
     * After grace, at the cheapest level: a stall over 2 s, or 3 consecutive presents over 1 s,
     * turns the wallpaper off. Otherwise the usual stall-count rule.
     */
    static int afterGraceAction(int level, long gapMs, int consecutiveSlow, int stallsInWindow) {
        return afterGraceAction(level, gapMs, consecutiveSlow, stallsInWindow, level < 3);
    }

    /**
     * {@code canDrop} is false when quality is pinned or we already took the "floor" path.
     * Sony Radiomarchy-open log (fix7/integ): "UI stall 6xx-1416ms: floor, reduce iters/fps cap=24 cheap=1"
     * repeated and never killed, because a pinned mid-level is not {@link #isCheapest}.
     */
    static int afterGraceAction(int level, long gapMs, int consecutiveSlow, int stallsInWindow, boolean canDrop) {
        if ((isCheapest(level) || !canDrop) && isLongStall(gapMs)) return STALL_KILL;
        if ((isCheapest(level) || !canDrop) && consecutiveSlow >= SLOW_PRESENTS_TO_KILL) return STALL_KILL;
        return stallAction(level, stallsInWindow, canDrop);
    }

    static boolean isUiStall(long intervalNs) {
        return intervalNs > STALL_NS;
    }

    /**
     * After the grace period: one stall drops a level (or cheapens the floor). Persistent stalls
     * already at the floor turn the wallpaper off, even if iterations/fps were reset to the
     * default (Sony log: "UI stall 6xx-7xxms: floor, reduce iters/fps cap=24" every ~750 ms).
     * {@code stallsInWindow} includes this stall.
     */
    static int stallAction(int level, boolean reducedIters, int stallsInWindow) {
        return stallAction(level, stallsInWindow);
    }

    static int stallAction(int level, int stallsInWindow) {
        return stallAction(level, stallsInWindow, level < 3);
    }

    static int stallAction(int level, int stallsInWindow, boolean canDrop) {
        if ((!canDrop || level >= 3) && stallsInWindow >= STALLS_TO_KILL) return STALL_KILL;
        return STALL_DEGRADE;
    }

    /** Shader backing height inside the 720p+ surface. cheapScale 1 = full surface, 2 = 1/2, 3 = 1/3. */
    static int internalHeight(int surfaceH, int cheapScale) {
        if (surfaceH <= 0) surfaceH = FLOOR_BUFFER_H;
        if (cheapScale <= 1) return surfaceH;
        return Math.max(1, surfaceH / cheapScale);
    }

    static int cheapScaleFor(int level, int alreadyCheap) {
        if (level < 3 || isLight(level)) return 1;
        return Math.max(FBO_SCALE_FLOOR, Math.min(FBO_SCALE_CHEAP, alreadyCheap <= 1 ? FBO_SCALE_FLOOR : alreadyCheap));
    }

    /** This stall plus earlier ones still inside the window. */
    static int countStallsInWindow(long[] times, int n, long nowMs, long windowMs) {
        int c = 1;
        for (int i = 0; i < n; i++) if (nowMs - times[i] <= windowMs) c++;
        return c;
    }

    static long totalRam(Context c) {
        if (c == null) return 0;
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return 0;
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.totalMem;
        } catch (Throwable ignored) {
            return 0;
        }
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

    private void applyBuffer() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post(this::applyBuffer);
            return;
        }
        int[] b = bufferSize(r.level, getWidth(), getHeight(), MAX_BUFFER_H);
        if (b[0] == appliedW && b[1] == appliedH) return;
        appliedW = b[0];
        appliedH = b[1];
        int vh = getHeight();
        appliedScale = vh > 0 ? b[1] / (float) vh : scaleFor(r.level, 1080);
        getHolder().setFixedSize(b[0], b[1]);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyBuffer();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        applyBuffer();
        startPump();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopPump();
        super.onDetachedFromWindow();
    }

    @Override
    public void onResume() {
        super.onResume();
        startPump();
    }

    @Override
    public void onPause() {
        stopPump();
        super.onPause();
    }

    private final Choreographer.FrameCallback pump = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!pumpOn) return;
            if (lastUiFrameNs != 0 && isUiStall(frameTimeNanos - lastUiFrameNs)) {
                long gapMs = (frameTimeNanos - lastUiFrameNs) / 1_000_000L;
                long elapsed = SystemClock.elapsedRealtime() - graceStartMs;
                boolean laid = getWidth() > 0 && getHeight() > 0;
                if (inGracePeriod(laid, presentedFrames, elapsed)) {
                    android.util.Log.i("Radiomarchy", "grace: ignore UI stall " + gapMs + "ms presented="
                            + presentedFrames + " elapsed=" + elapsed + "ms");
                } else {
                    onUiStall(gapMs);
                    if (safetyTripped) return;
                }
            }
            lastUiFrameNs = frameTimeNanos;
            if (skipFrames > 0) {
                skipFrames--;
            } else {
                long period = 1_000_000_000L / Math.max(1, r.fpsCap);
                long cost = r.lastPresentCostNs;
                long start = r.lastDrawStartNs;
                // Don't queue another GL frame while the previous swap is still owed (UI must not wait on queueBuffer).
                boolean pending = start != 0 && cost > 0 && frameTimeNanos - start < cost;
                if (!pending && (lastRequestNs == 0 || frameTimeNanos - lastRequestNs >= period)) {
                    lastRequestNs = frameTimeNanos;
                    requestRender();
                }
            }
            if (pumpOn) Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private void markGraceStart() {
        graceStartMs = SystemClock.elapsedRealtime();
        presentedFrames = 0;
        lastUiFrameNs = 0;
        lastRequestNs = 0;
    }

    /** First stall after grace drops a level or reduces iters; only repeated stalls at the floor disable it. */
    private void onUiStall(long gapMs) {
        long now = SystemClock.elapsedRealtime();
        int kept = 0;
        for (int i = 0; i < stallCount; i++) {
            if (now - stallAt[i] <= STALL_WINDOW_MS) stallAt[kept++] = stallAt[i];
        }
        if (kept < stallAt.length) stallAt[kept] = now;
        else stallAt[stallAt.length - 1] = now;
        stallCount = Math.min(kept + 1, stallAt.length);
        boolean canDrop = pinnedQuality < 0 && r.level < LEVELS.length - 1;
        int action = afterGraceAction(r.level, gapMs, r.consecutiveSlow, stallCount, canDrop);
        if (action == STALL_KILL) {
            tripSafety("UI stall " + gapMs + "ms (" + stallCount + " in 20s at " + LEVELS[r.level].name + ")");
            return;
        }
        if (isLight(r.level)) {
            android.util.Log.i("Radiomarchy", "UI stall " + gapMs + "ms: light cap=" + r.fpsCap);
            skipFrames = 4;
            return;
        }
        if (pinnedQuality < 0 && r.level < LEVELS.length - 1) {
            r.level++;
            r.applyLowPolicy();
            applyBuffer();
            android.util.Log.i("Radiomarchy", "UI stall " + gapMs + "ms: drop to " + LEVELS[r.level].name
                    + " cheap=" + r.cheapScale + " cap=" + r.fpsCap);
        } else {
            r.capFps = true;
            r.freezePalette = true;
            if (r.cheapScale < FBO_SCALE_CHEAP) r.cheapScale++;
            if (r.iterMul > 0.4f) r.iterMul = 0.4f;
            r.applyLowPolicy();
            android.util.Log.i("Radiomarchy", "UI stall " + gapMs + "ms: floor, reduce iters/fps cap=" + r.fpsCap
                    + " cheap=" + r.cheapScale);
        }
        skipFrames = 4;
    }

    private void startPump() {
        if (pumpOn) return;
        pumpOn = true;
        markGraceStart();
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        Choreographer.getInstance().postFrameCallback(pump);
    }

    private void stopPump() {
        pumpOn = false;
        Choreographer.getInstance().removeFrameCallback(pump);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && homeWallpaper) markGraceStart();
    }

    private void tripSafety(String why) {
        if (safetyTripped) return;
        safetyTripped = true;
        preferLight = true;
        try {
            getContext().getApplicationContext()
                    .getSharedPreferences("omarchytv", Context.MODE_PRIVATE)
                    .edit().putBoolean(PREF_LIGHT, true).apply();
        } catch (Throwable ignored) {
        }
        stopPump();
        ui.post(() -> {
            android.util.Log.w("Radiomarchy", "wallpaper off for this session: " + why);
            SafetyListener safety = onSafety;
            if (safety != null) safety.onSafety(why);
            else {
                Runnable f = onFail;
                if (f != null) f.run();
            }
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
        String gpu = "";
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
        int fpsCap = SAVER_FPS;
        int lateInWindow, slowWindows;
        int cheapScale = 1;
        int fbo, fboTex, blitProg, blitPos, blitTex, blitW, blitH;
        int bakeFbo, bakeTex, bakeW, bakeH, lightProg, lightPos, lightTexU;
        int lightTime, lightPulse, lightBass, lightCycle, lightDim, lightLevel, lightPal;
        boolean bakeDirty = true, lightProcedural;
        int consecutiveSlow;
        volatile long lastDrawStartNs, lastPresentCostNs;

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            int[] range = new int[2], precision = new int[1];
            GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER, GLES20.GL_HIGH_FLOAT, range, 0, precision, 0);
            lowp = precision[0] < 16;
            gpu = String.valueOf(GLES20.glGetString(GLES20.GL_RENDERER));
            rememberGpu(getContext(), gpu);
            level = pinnedQuality >= 0 ? pinnedQuality : firstLevel(gpu, lowp, ramBytes, preferLight);
            fpsCap = homeWallpaper ? HOME_FPS : sceneFps(gpu, ramBytes);
            dropFbo();
            dropBake();
            bakeDirty = true;
            applyLowPolicy();
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
            applyBuffer();
        }

        private int loc(String n) {
            return GLES20.glGetUniformLocation(prog, n);
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
            lastDrawNs = ns;
            if (lastDrawStartNs != 0) lastPresentCostNs = ns - lastDrawStartNs;
            lastDrawStartNs = ns;
            presentedFrames++;
            if (presentedFrames == 1) {
                Runnable first = onFirstPresent;
                if (first != null) ui.post(first);
            }
            long elapsed = now - graceStartMs;
            boolean laid = w > 0 && h > 0;
            if (lastPresentCostNs > 0 && !inGracePeriod(laid, presentedFrames, elapsed)) {
                if (isSlowPresent(lastPresentCostNs)) consecutiveSlow++;
                else consecutiveSlow = 0;
                long presentMs = lastPresentCostNs / 1_000_000L;
                if (afterGraceAction(level, presentMs, consecutiveSlow, 0) == STALL_KILL) {
                    tripSafety("present " + presentMs + "ms x" + consecutiveSlow + " at " + LEVELS[level].name);
                    return;
                }
            }
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
            if (isLight(level)) {
                drawLight(now, pulse, t0);
                return;
            }

            int iw = w, ih = h;
            if (cheapScale > 1) {
                ih = internalHeight(h, cheapScale);
                iw = Math.max(1, Math.round(w * (ih / (float) h)));
                if (bindFbo(iw, ih)) {
                    GLES20.glViewport(0, 0, iw, ih);
                } else {
                    iw = w;
                    ih = h;
                    GLES20.glViewport(0, 0, w, h);
                }
            } else {
                GLES20.glViewport(0, 0, w, h);
            }
            GLES20.glUseProgram(prog);
            GLES20.glUniform2f(uRes, iw, ih);
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
            if (cheapScale > 1 && fbo != 0) blitFbo();
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
                bakeDirty = true;
                return;
            }
            randomise(n);
            newPalette = true;
            if (isLight(level)) {
                cur = n;
                next = -1;
                mix = 0;
                sceneSince = now;
                beatCount = 0;
                bakeDirty = true;
                return;
            }
            next = n;
            mix = 0;
            mixStart = now;
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
         * Adapts to the TV using the real present cost (the start-to-start interval, which includes
         * eglSwapBuffers). A couple of seconds of backpressure drops a level; at the 720p floor, fps and
         * iterations drop next. Home wallpaper that still cannot hold the floor is turned off for the session.
         * After 20 s of headroom the level climbs (Home never goes above 1080p).
         */
        private void measure(long ns, long now) {
            if (qualityChanged) {
                qualityChanged = false;
                if (pinnedQuality >= 0) level = pinnedQuality;
                applyLowPolicy();
                applyBuffer();
                frames = 0;
                frameNs = 0;
                late = 0;
                lateInWindow = 0;
                slowWindows = 0;
                goodSince = now;
            }
            if (lastNs != 0) {
                long d = ns - lastNs;
                if (d < 200_000_000L) {   // a pause (the app in the background) is not a slow frame
                    frameNs += d;
                    frames++;
                    if (isBackpressured(d, fpsCap)) {
                        late++;
                        lateInWindow++;
                    } else {
                        late = 0;
                    }
                }
            }
            boolean rush = late >= LATE_RUSH && pinnedQuality < 0;
            if (frames < WATCH_FRAMES && !rush) return;
            double avg = frames > 0 ? frameNs / (double) frames / 1e6 : 30;
            boolean pressure = rush || (frames > 0 && lateInWindow * 2 >= frames);
            if (frames >= 8) {
                android.util.Log.i("Radiomarchy", "fps=" + String.format(java.util.Locale.US, "%.1f", 1000.0 / avg)
                        + " frame=" + String.format(java.util.Locale.US, "%.1f", avg) + "ms level=" + LEVELS[level].name
                        + " scale=" + scaleFor(level, h) + " cheap=" + cheapScale + " buf=" + w + "x" + h
                        + (capFps ? " cap=" + fpsCap : "") + (freezePalette ? " pal=static" : "")
                        + (pressure ? " backpressure" : ""));
            }
            frames = 0;
            frameNs = 0;
            lateInWindow = 0;
            if (!rush) late = 0;
            if (pinnedQuality >= 0) { applyLowPolicy(); return; }
            if (pressure) {
                if (!isLight(level) && level < LIGHT_LEVEL) {
                    level++;
                    goodSince = now;
                    slowWindows = 0;
                    bakeDirty = true;
                    applyLowPolicy();
                    applyBuffer();
                } else {
                    capFps = true;
                    if (!isLight(level)) {
                        freezePalette = true;
                        if (cheapScale < FBO_SCALE_CHEAP) cheapScale++;
                        if (iterMul > 0.4f) iterMul = 0.4f;
                    }
                    applyLowPolicy();
                    slowWindows++;
                    if (shouldDisableAtFloor(level, slowWindows) || stallAction(level, slowWindows) == STALL_KILL) {
                        tripSafety("GL frame " + String.format(java.util.Locale.US, "%.1f", avg) + "ms at " + LEVELS[level].name);
                    }
                }
            } else {
                slowWindows = 0;
                if (goodSince == 0) goodSince = now;
                if (now - goodSince > 20000) {
                    int want = homeWallpaper ? HOME_FPS : sceneFps(gpu, ramBytes);
                    if (isLight(level)) {
                        goodSince = now;   // stay on light: climbing back to the full shader stutters on Mali-T8xx
                    } else if (cheapScale > 1) {
                        cheapScale--;
                    } else if (fpsCap < want || freezePalette || iterMul < 0.99f) {
                        freezePalette = false;
                        iterMul = 1f;
                    } else if (level > 0) {
                        level--;
                    }
                    goodSince = now;
                    applyLowPolicy();
                    applyBuffer();
                }
            }
        }

        private void applyLowPolicy() {
            capFps = true;
            int base = homeWallpaper ? HOME_FPS : sceneFps(gpu, ramBytes);
            if (isLight(level)) {
                freezePalette = false;
                cheapScale = 1;
                iterMul = 1f;
                fpsCap = base;
            } else if (level >= 3) {
                freezePalette = true;
                cheapScale = cheapScaleFor(level, cheapScale);
                if (fpsCap > CHEAP_FPS) fpsCap = CHEAP_FPS;
                if (iterMul > 0.55f) iterMul = 0.55f;
            } else if (pinnedQuality >= 0) {
                freezePalette = false;
                iterMul = 1f;
                cheapScale = cheapScaleFor(level, 1);
                fpsCap = base;
            } else if (fpsCap < CHEAP_FPS) {
                /* kept by the watchdog after the 720p floor */
            } else {
                fpsCap = base;
                if (level < 3) cheapScale = 1;
            }
        }

        private boolean bindFbo(int bw, int bh) {
            if (fbo != 0 && blitW == bw && blitH == bh) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
                return true;
            }
            dropFbo();
            int[] t = new int[1], f = new int[1];
            GLES20.glGenTextures(1, t, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, bw, bh, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glGenFramebuffers(1, f, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t[0], 0);
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                GLES20.glDeleteFramebuffers(1, f, 0);
                GLES20.glDeleteTextures(1, t, 0);
                return false;
            }
            fbo = f[0];
            fboTex = t[0];
            blitW = bw;
            blitH = bh;
            return true;
        }

        private static final float[] BAKE_PAL = {
                0, 0, 0, 0.33f, 0.33f, 0.33f, 0.66f, 0.66f, 0.66f, 1, 1, 1
        };

        private void drawLight(long now, float pulse, long t0) {
            if (!lightProcedural && (bakeDirty || bakeTex == 0)) {
                if (!bakeOnce()) lightProcedural = true;
            }
            if (lightProg == 0) {
                lightProg = link(LIGHT_VERT, LIGHT_FRAG);
                if (lightProg == 0) return;
                lightPos = GLES20.glGetAttribLocation(lightProg, "aPos");
                lightTexU = GLES20.glGetUniformLocation(lightProg, "uTex");
                lightTime = GLES20.glGetUniformLocation(lightProg, "uTime");
                lightPulse = GLES20.glGetUniformLocation(lightProg, "uPulse");
                lightBass = GLES20.glGetUniformLocation(lightProg, "uBass");
                lightCycle = GLES20.glGetUniformLocation(lightProg, "uCycle");
                lightDim = GLES20.glGetUniformLocation(lightProg, "uDim");
                lightLevel = GLES20.glGetUniformLocation(lightProg, "uLevel");
                lightPal = GLES20.glGetUniformLocation(lightProg, "uPal");
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, w, h);
            GLES20.glUseProgram(lightProg);
            GLES20.glUniform1f(lightTime, (now - t0) / 1000f);
            GLES20.glUniform1f(lightPulse, pulse);
            GLES20.glUniform1f(lightBass, f.bass);
            GLES20.glUniform1f(lightCycle, cycle);
            GLES20.glUniform1f(lightDim, dim);
            GLES20.glUniform1f(lightLevel, f.level);
            GLES20.glUniform3fv(lightPal, 4, pal, 0);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(lightProg, "uHave"), (bakeTex != 0 && !lightProcedural) ? 1 : 0);
            if (bakeTex != 0) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bakeTex);
                GLES20.glUniform1i(lightTexU, 0);
            }
            GLES20.glEnableVertexAttribArray(lightPos);
            GLES20.glVertexAttribPointer(lightPos, 2, GLES20.GL_FLOAT, false, 0, quad);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        private boolean bakeOnce() {
            int bh = BAKE_H;
            int bw = Math.max(1, Math.round(bh * (w / (float) Math.max(1, h))));
            if (bakeTex == 0 || bakeW != bw || bakeH != bh) {
                dropBake();
                int[] t = new int[1], fb = new int[1];
                GLES20.glGenTextures(1, t, 0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, bw, bh, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, null);
                GLES20.glGenFramebuffers(1, fb, 0);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[0]);
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t[0], 0);
                if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                    GLES20.glDeleteFramebuffers(1, fb, 0);
                    GLES20.glDeleteTextures(1, t, 0);
                    return false;
                }
                bakeFbo = fb[0];
                bakeTex = t[0];
                bakeW = bw;
                bakeH = bh;
            } else {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bakeFbo);
            }
            GLES20.glViewport(0, 0, bakeW, bakeH);
            GLES20.glUseProgram(prog);
            float t = (SystemClock.elapsedRealtime() - t0) / 1000f;
            float zoom = (float) (2.6 * Math.exp(-dive));
            GLES20.glUniform2f(uRes, bakeW, bakeH);
            GLES20.glUniform1f(uTime, t);
            GLES20.glUniform1f(uBass, 0);
            GLES20.glUniform1f(uMid, 0);
            GLES20.glUniform1f(uHigh, 0);
            GLES20.glUniform1f(uLevel, 0.4f);
            GLES20.glUniform1f(uPulse, 0);
            GLES20.glUniform1i(uScene, cur);
            GLES20.glUniform1i(uScene2, cur);
            GLES20.glUniform1f(uMix, 0);
            GLES20.glUniform3fv(uPal, 4, BAKE_PAL, 0);
            GLES20.glUniform1f(uCycle, 0);
            GLES20.glUniform1f(uZoom, zoom);
            GLES20.glUniform2f(uCenter, (float) center[0], (float) center[1]);
            GLES20.glUniform1f(uIter, BAKE_ITERS);
            GLES20.glUniform1f(uRot, 0);
            GLES20.glUniform1f(uDim, 1);
            GLES20.glUniform1f(uAA, 1);
            GLES20.glUniform4fv(uP, SCENES.length, params, 0);
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            bakeDirty = false;
            android.util.Log.i("Radiomarchy", "light bake " + bakeW + "x" + bakeH + " scene=" + SCENES[cur]);
            return true;
        }

        private void dropBake() {
            if (bakeFbo != 0) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                int[] f = { bakeFbo }, t = { bakeTex };
                GLES20.glDeleteFramebuffers(1, f, 0);
                GLES20.glDeleteTextures(1, t, 0);
                bakeFbo = bakeTex = bakeW = bakeH = 0;
            }
            bakeDirty = true;
        }

        private void dropFbo() {
            if (fbo != 0) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                int[] f = { fbo }, t = { fboTex };
                GLES20.glDeleteFramebuffers(1, f, 0);
                GLES20.glDeleteTextures(1, t, 0);
                fbo = fboTex = blitW = blitH = 0;
            }
        }

        private void blitFbo() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, w, h);
            if (blitProg == 0) {
                blitProg = link(BLIT_VERT, BLIT_FRAG);
                if (blitProg == 0) return;
                blitPos = GLES20.glGetAttribLocation(blitProg, "aPos");
                blitTex = GLES20.glGetUniformLocation(blitProg, "uTex");
            }
            GLES20.glUseProgram(blitProg);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex);
            GLES20.glUniform1i(blitTex, 0);
            GLES20.glEnableVertexAttribArray(blitPos);
            GLES20.glVertexAttribPointer(blitPos, 2, GLES20.GL_FLOAT, false, 0, quad);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
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
    static final String BLIT_VERT = "attribute vec2 aPos;\nvarying vec2 vUv;\nvoid main(){ vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }\n";
    static final String BLIT_FRAG = "precision mediump float;\nvarying vec2 vUv;\nuniform sampler2D uTex;\nvoid main(){ gl_FragColor = texture2D(uTex, vUv); }\n";
    static final String LIGHT_VERT = "attribute vec2 aPos;\nvarying vec2 vUv;\nvoid main(){ vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }\n";
    static final String LIGHT_FRAG = "precision mediump float;\nvarying vec2 vUv;\nuniform sampler2D uTex;\nuniform float uTime, uPulse, uBass, uCycle, uDim, uLevel;\nuniform vec3 uPal[4];\nuniform int uHave;\n"
            + "vec3 pal(float t){ t = fract(t) * 4.0; float k = fract(t); k = k * k * (3.0 - 2.0 * k);\n"
            + "  if (t < 1.0) return mix(uPal[0], uPal[1], k); if (t < 2.0) return mix(uPal[1], uPal[2], k);\n"
            + "  if (t < 3.0) return mix(uPal[2], uPal[3], k); return mix(uPal[3], uPal[0], k); }\n"
            + "void main(){\n"
            + "  float z = 1.0 + 0.14 * uPulse + 0.08 * uBass;\n"
            + "  vec2 pan = 0.045 * vec2(sin(uTime * 0.07), cos(uTime * 0.053));\n"
            + "  vec2 uv = (vUv - 0.5) / z + 0.5 + pan;\n"
            + "  uv = clamp(uv, 0.001, 0.999);\n"
            + "  float idx;\n"
            + "  if (uHave > 0) idx = texture2D(uTex, uv).r;\n"
            + "  else {\n"
            + "    vec2 p = uv * 2.0 - 1.0;\n"
            + "    idx = 0.5 + 0.25 * sin(p.x * 6.0 + uTime * 0.3) + 0.25 * sin(p.y * 5.0 - uTime * 0.2)\n"
            + "        + 0.2 * sin((p.x + p.y) * 4.0 + uTime * 0.15);\n"
            + "  }\n"
            + "  vec3 c = pal(idx + uCycle) * (0.85 + 0.4 * uLevel);\n"
            + "  c = c / (1.0 + c * 0.35);\n"
            + "  gl_FragColor = vec4(c * uDim, 1.0);\n"
            + "}\n";

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
