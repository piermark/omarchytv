package com.omarchytv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Wallpaper. config.xml "wallpaper" = "omarchy" draws Omarchy's default Tokyo Night background
 * (themes/tokyo-night/backgrounds, 3840x2160, centre-cropped and decoded at the view size) unaltered, like the
 * Omarchy desktop (optional uniform dimming in Layout); "procedural" draws the original wallpaper below.
 *
 * Original, procedurally drawn Tokyo Night wallpaper: a night-blue gradient, two soft aurora glows
 * (purple top right, blue bottom left), a faint dot grid like a terminal canvas and a thin horizon of
 * scan lines. Rendered once into an RGB_565 bitmap at view size (about 4 MB at 1080p), then just blitted,
 * so it costs nothing per frame on a 1 GB TV.
 */
public class WallpaperView extends View {

    private Bitmap cache;
    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG);

    public WallpaperView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (cache != null) cache.recycle();
        cache = null;
        if (w > 0 && h > 0) cache = make(w, h);
    }

    /** Wallpaper file + mtime the cache was built from, so a re-pushed image at the same path is picked up. */
    private String builtFrom;
    private long builtMtime;
    private long builtScrim;

    /** Dimming level: changes what panes see behind them. */
    private long scrimKey() {
        LauncherConfig cfg = LauncherConfig.get(getContext());
        return ((long) cfg.transparency << 40) ^ ((long) cfg.wallpaperDim.hashCode() << 20) ^ (cfg.accent & 0xFFFFFFFFL)
                ^ ((long) Theme.cur().name.hashCode() << 8) ^ ((long) cfg.wallpaperBg << 52) ^ (cfg.syncPcWallpaper ? (1L << 58) : 0L);
    }

    /** Rebuilds when the wallpaper image changed on disk (e.g. pushed again by adb or Omarchy Link). */
    void refreshIfChanged() {
        String which = LauncherConfig.get(getContext()).wallpaper;
        long m = which.startsWith("/") ? new java.io.File(which).lastModified() : 0L;
        if (which.equals(builtFrom) && m == builtMtime && scrimKey() == builtScrim) return;
        if (getWidth() > 0) {
            Bitmap old = cache;
            cache = make(getWidth(), getHeight());
            if (old != null) old.recycle();
            invalidate();
        }
    }

    /**
     * Centre-crop rectangle in source pixels for a srcW x srcH image filling a w x h view (same as
     * ImageView centerCrop): never stretched, the image's short side fills the view and the rest is cut
     * evenly. Works for any source aspect (16:9, 16:10, 4:3, portrait, ultrawide).
     */
    static int[] cropRect(int srcW, int srcH, int w, int h) {
        double view = w / (double) h, src = srcW / (double) srcH;
        int cw = srcW, ch = srcH;
        if (src > view) cw = (int) Math.round(srcH * view);   // too wide: cut the sides
        else ch = (int) Math.round(srcW / view);              // too tall: cut top and bottom
        cw = Math.max(1, Math.min(srcW, cw));
        ch = Math.max(1, Math.min(srcH, ch));
        int x = (srcW - cw) / 2, y = (srcH - ch) / 2;
        return new int[]{x, y, x + cw, y + ch};
    }

    /** Largest power-of-two subsample that still leaves the cropped region at least w x h (sharp, little memory). */
    static int sampleFor(int regionW, int regionH, int w, int h) {
        int sample = 1;
        while (regionW / (sample * 2) >= w && regionH / (sample * 2) >= h) sample *= 2;
        return sample;
    }

    private Bitmap make(int w, int h) {
        float density = getResources().getDisplayMetrics().density;
        LauncherConfig cfg = LauncherConfig.get(getContext());
        String rawWhich = cfg.wallpaper;
        boolean custom = rawWhich.startsWith("/") && cfg.syncPcWallpaper && new java.io.File(rawWhich).isFile();
        String which = (rawWhich.startsWith("/") && !custom) ? "omarchy" : rawWhich;
        if (FractalLayer.is(which)) which = "omarchy";   // still theme wallpaper underneath, and if GL fails
        builtFrom = rawWhich;
        builtMtime = custom ? new java.io.File(rawWhich).lastModified() : 0L;
        builtScrim = scrimKey();
        buildId++;
        // v2.10: each Omarchy theme ships its Omarchy-original wordmark background (third-party art excluded, see THIRD-PARTY.md) in assets/wallpapers/<theme>/ (3840x2160 WebP).
        String asset = custom ? null : assetFor(getContext(), which, LauncherConfig.get(getContext()).wallpaperBg);
        photo = false;
        if (asset == null && !custom) return render(w, h, density);
        // Read a bundled asset once, base64-unwrapped: some themes ship their WebP as a base64 text file, and
        // probing or decoding that text directly fails before the unwrapping below ever runs.
        byte[] raw = custom ? null : assetBytes(asset);
        if (!custom && raw == null) return render(w, h, density);
        Bitmap src = null;
        try {
            android.graphics.BitmapFactory.Options b = new android.graphics.BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            if (custom) android.graphics.BitmapFactory.decodeFile(which, b);
            else android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length, b);
            if (b.outWidth <= 0 || b.outHeight <= 0) return render(w, h, density);
            int[] crop = cropRect(b.outWidth, b.outHeight, w, h);
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            o.inSampleSize = sampleFor(crop[2] - crop[0], crop[3] - crop[1], w, h);
            // Decode only the visible crop at (about) display resolution: a 4K wallpaper on a 1080p UI
            // surface decodes at 1920x1080, on a 4K surface at 3840x2160; huge or ultrawide photos stay cheap.
            try (java.io.InputStream in = custom ? new java.io.FileInputStream(which)
                    : new java.io.ByteArrayInputStream(raw)) {
                android.graphics.BitmapRegionDecoder dec = android.graphics.BitmapRegionDecoder.newInstance(in, false);
                src = dec.decodeRegion(new android.graphics.Rect(crop[0], crop[1], crop[2], crop[3]), o);
                dec.recycle();
            } catch (Exception regionUnsupported) {   // e.g. GIF/BMP: full decode, then crop when drawing
                src = null;
            }
            android.graphics.Rect from = null;
            if (src == null) {
                o.inSampleSize = sampleFor(b.outWidth, b.outHeight, w, h);
                src = custom ? android.graphics.BitmapFactory.decodeFile(which, o)
                        : android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
                if (src == null) return render(w, h, density);
                int[] c2 = cropRect(src.getWidth(), src.getHeight(), w, h);
                from = new android.graphics.Rect(c2[0], c2[1], c2[2], c2[3]);
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            Canvas c = new Canvas(bmp);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            // The crop has the view's aspect, so this is a uniform scale (no stretch), usually 1:1.
            c.drawBitmap(src, from, new android.graphics.Rect(0, 0, w, h), p);
            src.recycle();
            src = null;
            // No scrim: the theme wallpaper as on the Omarchy desktop (optional uniform dim in onDraw).
            photo = true;
            return bmp;
        } catch (Throwable t) {   // OOM or missing file: fall back to the cheap one
            if (src != null) src.recycle();
            return render(w, h, density);
        }
    }

    private boolean photo;
    private final Paint dim = new Paint();

    /**
     * Worst case (brightest sampled pixel) of the wallpaper as shown, inside r (root px), or white when the
     * wallpaper is not built yet. Panes use it to pick a background just opaque enough for their text.
     */
    int worstBehind(android.graphics.RectF r) {
        boolean light = Theme.cur().light;   // light theme: dark text, the darkest pixel is the worst case
        if (cache == null) return light ? 0x000000 : 0xFFFFFF;
        int px = brightestIn(cache, r, light);
        float g = photo ? Surfaces.globalDim(Surfaces.dimLevel(LauncherConfig.get(getContext()).wallpaperDim)) : 0f;
        return g > 0 ? Surfaces.over(Surfaces.bg(), g, px) : px;
    }

    /** Bumped on every rebuild, so callers know to re-check what is behind them. */
    int buildId;

    /** Brightest sampled pixel (every 4th) of the wallpaper inside r: the worst case behind that text. */
    static int brightestIn(Bitmap bmp, android.graphics.RectF r, boolean darkest) {
        int l = Math.max(0, (int) r.left), t = Math.max(0, (int) r.top);
        int rr = Math.min(bmp.getWidth(), (int) r.right), b = Math.min(bmp.getHeight(), (int) r.bottom);
        if (rr <= l || b <= t) return 0;
        int[] row = new int[rr - l];
        int best = 0;
        double bl = -1;
        for (int y = t; y < b; y += 4) {
            bmp.getPixels(row, 0, rr - l, l, y, rr - l, 1);
            for (int x = 0; x < row.length; x += 4) {
                int px = row[x] & 0xFFFFFF;
                double lum = Surfaces.luminance(px);
                if (darkest) lum = 1 - lum;
                if (lum > bl) {
                    bl = lum;
                    best = px;
                }
            }
        }
        return best;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (cache != null) cache.recycle();
        cache = null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (cache == null && getWidth() > 0) cache = make(getWidth(), getHeight());
        if (cache != null) {
            if (LauncherConfig.get(getContext()).antiBurnIn) {
                long step = System.currentTimeMillis() / 45_000L;
                float dx = ((step % 5) - 2) * 1.0f;
                float dy = (((step / 5) % 5) - 2) * 1.0f;
                canvas.save();
                canvas.translate(dx, dy);
                canvas.drawBitmap(cache, 0, 0, blit);
                canvas.restore();
            } else {
                canvas.drawBitmap(cache, 0, 0, blit);
            }
        }
        // Omarchy look: the theme wallpaper untouched. Optional uniform dimming (Layout → Wallpaper dimming).
        if (photo && cache != null) {
            float global = Surfaces.globalDim(Surfaces.dimLevel(LauncherConfig.get(getContext()).wallpaperDim));
            if (global > 0) {
                dim.setColor((Math.round(global * 255) << 24) | Surfaces.bg());
                canvas.drawRect(0, 0, getWidth(), getHeight(), dim);
            }
        }
    }

    /**
     * Bytes of a bundled wallpaper. Some themes ship theirs as a base64 text file (starts with "UklG", base64
     * for "RIFF"); unwrap it so the bounds probe, the region decode and the full decode all see the WebP.
     */
    private byte[] assetBytes(String asset) {
        try (java.io.InputStream in = getContext().getAssets().open(asset)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            byte[] raw = bos.toByteArray();
            if (raw.length >= 4 && raw[0] == 'U' && raw[1] == 'k' && raw[2] == 'l' && raw[3] == 'G') {
                raw = android.util.Base64.decode(raw, android.util.Base64.DEFAULT);
            }
            return raw;
        } catch (Exception e) {
            return null;
        }
    }

    /** Official still backgrounds of a theme (asset paths, sorted like Omarchy's backgrounds folder, excluding .mp4 intros). */
    static String[] backgrounds(Context c, String theme) {
        try {
            String[] f = c.getAssets().list("wallpapers/" + theme);
            if (f == null || f.length == 0) return new String[0];
            java.util.Arrays.sort(f);
            java.util.List<String> out = new java.util.ArrayList<>();
            for (String s : f) {
                if (!s.endsWith(".mp4")) out.add("wallpapers/" + theme + "/" + s);
            }
            return out.toArray(new String[0]);
        } catch (java.io.IOException e) {
            return new String[0];
        }
    }

    /** Optional MP4 video intro matching the current theme background (e.g. cumulus, last-horizon), or null. */
    static String introAssetFor(Context c, String which, int idx) {
        String img = assetFor(c, which, idx);
        if (img == null || !img.endsWith(".webp")) return null;
        String mp4 = img.substring(0, img.length() - 5) + ".mp4";
        try (java.io.InputStream in = c.getAssets().open(mp4)) {
            return mp4;
        } catch (Exception e) {
            return null;
        }
    }

    /** "omarchy" (and legacy "omarchy-liftoff"/"omarchy-road") = background #idx of the current theme; else none. */
    static String assetFor(Context c, String which, int idx) {
        if (which != null && which.startsWith("wallpapers/") && which.endsWith(".webp")) return which;
        if (!"omarchy".equals(which) && !"omarchy-liftoff".equals(which) && !"omarchy-road".equals(which)) return null;
        String[] all = backgrounds(c, Theme.cur().name);
        if (all.length == 0) all = backgrounds(c, "tokyo-night");
        if (all.length == 0) return null;
        if ("omarchy-road".equals(which) && idx == 0) idx = 1;
        return all[idx % all.length];
    }

    static Bitmap render(int w, int h, float density) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

        Theme t = Theme.cur();
        // Base: theme background fading to darker_background (v2.10: follows the theme).
        p.setShader(new LinearGradient(0, 0, 0, h, t.bg | 0xFF000000, t.bgDarker | 0xFF000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);

        // Aurora glows in the theme's magenta / accent / cyan.
        int m = t.magenta & 0xFFFFFF, a = t.accent & 0xFFFFFF, cy = t.cyan & 0xFFFFFF;
        float r1 = w * 0.55f;
        p.setShader(new RadialGradient(w * 0.82f, h * 0.12f, r1,
                new int[]{0x40000000 | m, 0x14000000 | m, m}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        float r2 = w * 0.6f;
        p.setShader(new RadialGradient(w * 0.12f, h * 0.95f, r2,
                new int[]{0x33000000 | a, 0x10000000 | a, a}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        float r3 = w * 0.25f;
        p.setShader(new RadialGradient(w * 0.55f, h * 0.55f, r3,
                new int[]{0x14000000 | cy, cy}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        // Dot grid.
        float step = 24 * density;
        p.setColor(0x26000000 | (t.fgDark & 0xFFFFFF));
        float dot = Math.max(1.5f, 1.5f * density);
        for (float y = step / 2; y < h; y += step) {
            for (float x = step / 2; x < w; x += step) {
                c.drawRect(x, y, x + dot, y + dot, p);
            }
        }

        // Horizon: thin scan lines getting denser towards the bottom third.
        p.setStrokeWidth(Math.max(1f, density));
        float y = h * 0.62f;
        float gap = 26 * density;
        int alpha = 0x10;
        while (y < h) {
            p.setColor(alpha << 24 | a);
            c.drawLine(0, y, w, y, p);
            y += gap;
            gap = Math.max(5 * density, gap * 0.82f);
            alpha = Math.min(0x22, alpha + 2);
        }
        return bmp;
    }
}
