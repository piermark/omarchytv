package com.omarchytv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;

/**
 * v2.14.1 Cinemarchy's two tape modes on Android TV, as on LG and Samsung (cinemarchy.js): VHS/CRT (scanlines, a
 * tracking bar rolling down every 7 s, the VCR's "PLAY ▶" and "SP 0:12:34") and ASCII (the film drawn live as
 * theme-coloured characters, 120 columns, about 11 frames a second). The video keeps playing on its SurfaceView
 * underneath; ASCII reads it back with PixelCopy, already scaled down to the grid, so nothing big is copied.
 * A stream that cannot be read (protected video, or a TV chip that only hands back black) falls back to the normal
 * picture with the same "cine_ascii_blocked" note as there.
 */
final class CineFxView extends View {
    static final String NORMAL = "normal", VHS = "vhs", ASCII = "ascii";

    interface Clock {
        boolean paused();
        long positionMs();
    }

    private static final String RAMP = " .,:;-=+*#%@";
    private static final int COLS = 120;
    private static final long ASCII_MS = 90, TRACK_MS = 7000;

    private final SurfaceView video;
    private final Clock clock;
    private final Runnable onBlocked;
    private final Paint scan = new Paint(), track = new Paint(), vcr = new Paint(Paint.ANTI_ALIAS_FLAG), asc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String mode = NORMAL;
    private HandlerThread copier;
    private Handler copyH;
    private Bitmap frame;
    private char[][] grid;
    private int rows, fails, blank;
    private boolean copying;
    private long trackStart;
    private volatile float[] range = {Float.NaN, Float.NaN};   // the eased contrast range (low, high), per film

    CineFxView(Context c, SurfaceView video, Clock clock, Runnable onBlocked) {
        super(c);
        this.video = video;
        this.clock = clock;
        this.onBlocked = onBlocked;
        Theme t = Theme.cur();
        // One scanline every 4 px, the darkest at the third pixel (the webOS gradient, as a repeating 1x4 tile).
        Bitmap tile = Bitmap.createBitmap(1, 4, Bitmap.Config.ARGB_8888);
        tile.setPixel(0, 0, 0);
        tile.setPixel(0, 1, 0);
        tile.setPixel(0, 2, Color.argb(89, 0, 0, 0));
        tile.setPixel(0, 3, Color.argb(30, 0, 0, 0));
        scan.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        vcr.setColor(Color.WHITE);
        vcr.setTypeface(CastUi.font(c));
        vcr.setLetterSpacing(0.1f);
        asc.setColor(0xFF000000 | (t.accent & 0xFFFFFF));
        asc.setTypeface(CastUi.font(c));
        asc.setTextAlign(Paint.Align.CENTER);
        setVisibility(GONE);
    }

    String mode() {
        return mode;
    }

    void setMode(String m) {
        mode = VHS.equals(m) || ASCII.equals(m) ? m : NORMAL;
        stopAscii();
        setVisibility(NORMAL.equals(mode) ? GONE : VISIBLE);
        setBackgroundColor(ASCII.equals(mode) ? Color.BLACK : Color.TRANSPARENT);
        if (VHS.equals(mode)) trackStart = SystemClock.uptimeMillis();
        if (ASCII.equals(mode)) startAscii();
        invalidate();
    }

    static String next(String m) {
        return NORMAL.equals(m) ? VHS : VHS.equals(m) ? ASCII : NORMAL;
    }

    void release() {
        mode = NORMAL;
        stopAscii();
    }

    // ---- ASCII: read the video back small, map light to characters ----

    private void startAscii() {
        fails = 0;
        blank = 0;
        grid = null;
        range = new float[]{Float.NaN, Float.NaN};
        copier = new HandlerThread("cine-ascii");
        copier.start();
        copyH = new Handler(copier.getLooper());
        ui.post(asciiTick);
    }

    private void stopAscii() {
        ui.removeCallbacks(asciiTick);
        if (copier != null) copier.quitSafely();
        copier = null;
        copyH = null;
        copying = false;
    }

    private final Runnable asciiTick = new Runnable() {
        @Override
        public void run() {
            if (!ASCII.equals(mode)) return;
            ui.postDelayed(this, ASCII_MS);
            int vw = video.getWidth(), vh = video.getHeight();
            if (copying || copyH == null || vw == 0 || vh == 0 || !video.getHolder().getSurface().isValid()) return;
            int r = Math.max(20, Math.round(COLS * (float) vh / vw * 0.5f));
            if (frame == null || frame.getHeight() != r) {
                frame = Bitmap.createBitmap(COLS, r, Bitmap.Config.ARGB_8888);
                rows = r;
            }
            final Bitmap b = frame;
            copying = true;
            try {
                PixelCopy.request(video, b, result -> {
                    char[][] g = null;
                    if (result == PixelCopy.SUCCESS) g = toAscii(b, range);
                    final char[][] done = g;
                    final int res = result;
                    ui.post(() -> {
                        copying = false;
                        if (!ASCII.equals(mode)) return;
                        if (done != null) {
                            fails = 0;
                            // Some TV chips hand back only black for a hardware video plane: after 6 s of a playing
                            // film without a single lit character, give the picture back instead of a black screen.
                            blank = isBlank(done) && !clock.paused() ? blank + 1 : 0;
                            if (blank >= 6000 / ASCII_MS) {
                                blocked();
                                return;
                            }
                            grid = done;
                            invalidate();
                        } else if (res != PixelCopy.ERROR_SOURCE_NO_DATA && ++fails >= 15 && !clock.paused()) {
                            blocked();
                        }
                    });
                }, copyH);
            } catch (RuntimeException e) {   // the surface went away between the check and the copy
                copying = false;
                if (++fails >= 15) blocked();
            }
        }
    };

    private void blocked() {
        setMode(NORMAL);
        if (onBlocked != null) onBlocked.run();
    }

    private static boolean isBlank(char[][] g) {
        for (char[] row : g) for (char ch : row) if (ch != ' ') return false;
        return true;
    }

    /**
     * Light to characters with each frame's contrast stretched, as cinemarchy.js asciiArt does on LG and Samsung: the
     * darkest 2 % become spaces, the brightest 2 % the densest characters, the range eased over a few frames (no
     * flicker); a quarter of the plain light is kept, and a flat frame keeps its own level (black blank, white white).
     * A hazy film used to come out as a sheet of ":;,"; now its figures stand out.
     */
    static char[][] toAscii(Bitmap b, float[] range) {
        int w = b.getWidth(), h = b.getHeight(), n = w * h, bins = 32;
        int[] px = new int[n], hist = new int[bins];
        float[] lum = new float[n];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < n; i++) {
            int c = px[i];
            float l = (((c >> 16) & 255) * 0.3f + ((c >> 8) & 255) * 0.59f + (c & 255) * 0.11f) / 255f;
            lum[i] = l;
            hist[Math.min(bins - 1, (int) (l * bins))]++;
        }
        float lo = 0f, hi = 1f, cut = n * 0.02f;
        int acc = 0;
        for (int k = 0; k < bins; k++) {
            acc += hist[k];
            if (acc > cut) { lo = (float) k / bins; break; }
        }
        acc = 0;
        for (int k = bins - 1; k >= 0; k--) {
            acc += hist[k];
            if (acc > cut) { hi = (float) (k + 1) / bins; break; }
        }
        if (range != null) {
            if (Float.isNaN(range[0])) { range[0] = lo; range[1] = hi; }
            else { range[0] += (lo - range[0]) * 0.3f; range[1] += (hi - range[1]) * 0.3f; }
            lo = range[0];
            hi = range[1];
        }
        float span = hi - lo;
        if (span < 0.2f) {   // a flat frame: black, grey or white as it is
            lo = Math.max(0f, Math.min(0.8f, (lo + hi) / 2f - 0.1f));
            span = 0.2f;
        }
        int r = RAMP.length();
        char[][] g = new char[h][w];
        for (int i = 0; i < n; i++) {
            float v = 0.25f * lum[i] + 0.75f * Math.max(0f, Math.min(1f, (lum[i] - lo) / span));
            g[i / w][i % w] = RAMP.charAt(Math.min(r - 1, (int) (v * r)));
        }
        return g;
    }

    // ---- drawing ----

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        if (ASCII.equals(mode)) {
            char[][] g = grid;
            if (g == null) return;
            float size = Math.min(w / (COLS * 0.6f), (float) h / g.length);
            asc.setTextSize(size);
            asc.setShadowLayer(size * 0.4f, 0, 0, (0x88 << 24) | (asc.getColor() & 0xFFFFFF));
            float top = (h - size * g.length) / 2f - asc.ascent();
            for (int y = 0; y < g.length; y++) c.drawText(g[y], 0, g[y].length, w / 2f, top + y * size, asc);
        } else if (VHS.equals(mode)) {
            c.drawRect(0, 0, w, h, scan);
            float bar = h * 0.06f, span = h * 1.13f;
            float y = -h * 0.08f + span * ((SystemClock.uptimeMillis() - trackStart) % TRACK_MS) / TRACK_MS;
            track.setShader(new LinearGradient(0, y, 0, y + bar,
                    new int[]{0, Color.argb(31, 255, 255, 255), Color.argb(13, 255, 255, 255), 0}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, y, w, y + bar, track);
            boolean paused = clock.paused();
            vcr.setTextSize(w * 0.024f);
            vcr.setShadowLayer(w * 0.0015f, w * 0.0015f, w * 0.0015f, Color.BLACK);
            c.drawText(paused ? "PAUSE ‖" : "PLAY ▶", w * 0.04f, h * 0.05f - vcr.ascent(), vcr);
            vcr.setTextSize(w * 0.02f);
            String sp = "SP  " + CastUi.clock(clock.positionMs() / 1000.0);
            c.drawText(sp, w - w * 0.04f - vcr.measureText(sp), h - h * 0.06f - vcr.descent(), vcr);
            postInvalidateOnAnimation();
        }
    }
}
