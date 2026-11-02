package com.omarchytv.home;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.widget.FrameLayout;

/**
 * v2.14 Retro CRT plugin (plugins.retroCrt: "off" | "light" | "strong"): scanlines, a faint RGB phosphor mask,
 * vignette, a gentle flicker and a rounded bezel in the active Omarchy theme (dark body, accent edge and glow), over
 * all of Home. "strong" on Android 13+ also bends the picture like a tube (AGSL barrel shader on the root view).
 * The bezel follows the theme: rebuilt whenever the activity is (theme changes recreate it).
 */
final class CrtOverlay extends View {
    static final String CURVE_AGSL = "uniform shader content; uniform float2 size; uniform float k;"
            + "half4 main(float2 p) {"
            + "  float2 uv = p / size * 2.0 - 1.0;"
            + "  float2 d = uv * (1.0 + k * dot(uv, uv));"
            + "  if (abs(d.x) > 1.0 || abs(d.y) > 1.0) return half4(0.0, 0.0, 0.0, 1.0);"
            + "  return content.eval((d * 0.5 + 0.5) * size);"
            + "}";

    private final boolean strong;
    private final Paint scan = new Paint(), mask = new Paint(), vignette = new Paint(), bezel = new Paint(Paint.ANTI_ALIAS_FLAG),
            edge = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int dark, accent;
    private float flicker = 1f;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            flicker = 0.97f + (float) Math.random() * 0.03f;
            invalidate();
            postDelayed(this, 120);
        }
    };

    private CrtOverlay(Context c, boolean strong) {
        super(c);
        this.strong = strong;
        Theme t = Theme.cur();
        dark = t.bgDarker | 0xFF000000;
        accent = t.accent | 0xFF000000;
        Bitmap b = Bitmap.createBitmap(1, 3, Bitmap.Config.ARGB_8888);
        b.setPixel(0, 2, strong ? 0x55000000 : 0x30000000);
        scan.setShader(new BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        Bitmap m = Bitmap.createBitmap(3, 1, Bitmap.Config.ARGB_8888);
        m.setPixel(0, 0, 0x0CFF0000);
        m.setPixel(1, 0, 0x0800FF00);
        m.setPixel(2, 0, 0x0C0000FF);
        mask.setShader(new BitmapShader(m, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        bezel.setStyle(Paint.Style.STROKE);
        edge.setStyle(Paint.Style.STROKE);
        glow.setStyle(Paint.Style.STROKE);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** From MainActivity.onCreate (after BootScreenView): adds the overlay when the plugin is on. */
    static void attach(FrameLayout root, String mode) {
        if (root == null || !("light".equals(mode) || "strong".equals(mode))) return;
        CrtOverlay v = new CrtOverlay(root.getContext(), "strong".equals(mode));
        root.addView(v, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        v.post(v.tick);
        if (v.strong && Build.VERSION.SDK_INT >= 33) curve(root);
    }

    @TargetApi(33)
    private static void curve(View root) {
        RuntimeShader sh = new RuntimeShader(CURVE_AGSL);
        sh.setFloatUniform("k", 0.035f);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l <= 0 || b - t <= 0) return;
            sh.setFloatUniform("size", r - l, b - t);
            v.setRenderEffect(RenderEffect.createRuntimeShaderEffect(sh, "content"));
        });
        root.requestLayout();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float r = (float) Math.hypot(w / 2.0, h / 2.0);
        vignette.setShader(new RadialGradient(w / 2f, h / 2f, r, new int[]{Color.TRANSPARENT, Color.TRANSPARENT,
                strong ? 0x99000000 : 0x66000000}, new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, h, scan);
        c.drawRect(0, 0, w, h, mask);
        c.drawRect(0, 0, w, h, vignette);
        float bw = h * 0.011f, rad = h * 0.032f;
        RectF out = new RectF(bw / 2, bw / 2, w - bw / 2, h - bw / 2);
        bezel.setStrokeWidth(bw);
        bezel.setColor(dark);
        c.drawRoundRect(out, rad, rad, bezel);
        RectF in = new RectF(bw, bw, w - bw, h - bw);
        glow.setStrokeWidth(bw * 0.9f);
        glow.setColor((accent & 0xFFFFFF) | (int) (0x30 * flicker) << 24);
        c.drawRoundRect(new RectF(in.left + bw * 0.45f, in.top + bw * 0.45f, in.right - bw * 0.45f, in.bottom - bw * 0.45f), rad, rad, glow);
        edge.setStrokeWidth(Math.max(2f, h / 540f));
        edge.setColor(accent);
        c.drawRoundRect(in, rad * 0.8f, rad * 0.8f, edge);
        if (flicker < 0.985f) {
            c.drawColor((int) ((1f - flicker) * 255 * 0.6f) << 24);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }
}
