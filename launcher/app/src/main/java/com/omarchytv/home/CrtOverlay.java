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
 * v2.14 Retro CRT plugin (plugins.retroCrt: "off" | "light" | "strong"): Home shrinks to 88% inside a CRT set drawn
 * on the window's decor view: dark body, a rim and glow in the active theme's accent, an OMARCHY TV badge with a power
 * LED; on the screen, scanlines, an RGB phosphor mask, vignette, a glass highlight and a gentle flicker. "strong" on
 * Android 13+ also bends the picture like a tube (AGSL barrel shader on the root view).
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
    private final Paint scan = new Paint(), mask = new Paint(), vignette = new Paint(),
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
        b.setPixel(0, 2, strong ? 0x6B000000 : 0x48000000);
        scan.setShader(new BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        Bitmap m = Bitmap.createBitmap(3, 1, Bitmap.Config.ARGB_8888);
        m.setPixel(0, 0, strong ? 0x17FF0000 : 0x0FFF0000);
        m.setPixel(1, 0, strong ? 0x1200FF00 : 0x0B00FF00);
        m.setPixel(2, 0, strong ? 0x170000FF : 0x0F0000FF);
        mask.setShader(new BitmapShader(m, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        edge.setStyle(Paint.Style.STROKE);
        glow.setStyle(Paint.Style.STROKE);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    static final float K = 0.88f;   // the home shrinks to 88% inside the set, like the LG/Samsung version

    /** From MainActivity.onCreate: Home shrinks into a CRT set (overlay on the window's decor view) when on. */
    static void attach(android.app.Activity a, FrameLayout root, String mode) {
        if (root == null || !("light".equals(mode) || "strong".equals(mode))) return;
        View decor = a.getWindow().getDecorView();
        if (!(decor instanceof android.view.ViewGroup)) return;
        root.setScaleX(K);
        root.setScaleY(K);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            v.setPivotX((r - l) / 2f);
            v.setPivotY((b - t) / 2f - (b - t) * 0.012f / (1 - K));
        });
        CrtOverlay v = new CrtOverlay(a, "strong".equals(mode));
        ((android.view.ViewGroup) decor).addView(v, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
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

    private final RectF screen = new RectF();
    private final android.graphics.Path frame = new android.graphics.Path();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG), glass = new Paint();

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float sw = w * K, sh = h * K, x = (w - sw) / 2f, y = (h - sh) / 2f - h * 0.012f;
        screen.set(x, y, x + sw, y + sh);
        float rad = h * 0.022f;
        frame.reset();
        frame.setFillType(android.graphics.Path.FillType.EVEN_ODD);
        frame.addRect(0, 0, w, h, android.graphics.Path.Direction.CW);
        frame.addRoundRect(screen, rad, rad, android.graphics.Path.Direction.CW);
        float r = (float) Math.hypot(sw / 2.0, sh / 2.0);
        vignette.setShader(new RadialGradient(screen.centerX(), screen.centerY(), r, new int[]{Color.TRANSPARENT, Color.TRANSPARENT,
                strong ? 0xB8000000 : 0x8C000000}, new float[]{0f, strong ? 0.5f : 0.58f, 1f}, Shader.TileMode.CLAMP));
        glass.setShader(new android.graphics.LinearGradient(screen.left, screen.top, screen.centerX(), screen.centerY(),
                0x17FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        label.setTypeface(CastUi.font(getContext()));
        label.setTextSize(h * 0.016f);
        label.setLetterSpacing(0.6f);
        label.setColor(accent);
        label.setShadowLayer(h * 0.01f, 0, 0, accent);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float rad = h * 0.022f;
        // Inside the screen: scanlines, phosphor mask, vignette, glass highlight, flicker.
        c.save();
        c.clipRect(screen);
        c.drawRect(screen, scan);
        c.drawRect(screen, mask);
        c.drawRect(screen, vignette);
        c.drawRect(screen, glass);
        if (flicker < 0.985f) c.drawColor((int) ((1f - flicker) * 255 * 0.8f) << 24);
        c.restore();
        // The set: dark body all around, accent rim with glow, the OMARCHY TV badge and a power LED.
        fill.setColor(0xFF0B0B0F);
        c.drawPath(frame, fill);
        glow.setStrokeWidth(h * 0.012f);
        glow.setColor((accent & 0xFFFFFF) | (int) (0x55 * flicker) << 24);
        c.drawRoundRect(screen, rad, rad, glow);
        edge.setStrokeWidth(Math.max(2f, h * 0.0045f));
        edge.setColor(accent);
        c.drawRoundRect(screen, rad, rad, edge);
        String badge = "OMARCHY TV";
        float tw = label.measureText(badge), by = screen.bottom + (h - screen.bottom) * 0.62f;
        c.drawText(badge, (w - tw) / 2f - h * 0.012f, by, label);
        fill.setColor(accent);
        c.drawCircle((w + tw) / 2f + h * 0.012f, by - h * 0.005f, h * 0.0055f, fill);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }
}
