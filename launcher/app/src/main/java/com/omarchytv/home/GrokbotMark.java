package com.omarchytv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.RectF;
import android.view.View;

/**
 * The home tile's mirror of the Grok Bot that @tcballard's Grok Bot Desktop Companion shows on the PC
 * (built for his v0.1.0; shape and colour names come from his MIT bots.json).
 * The contours here are my own simple primitives, not his: a handful of families (round, polygon, pill,
 * drop, star, heart, shield) cover his 25 shape names well enough at tile size. Two capsule eyes, closed
 * when {@link #blink} says so.
 * v2.13: "orb" is Grok Bot's own ball mark, redrawn from Tom Ballard's MIT asset
 * (grokbot-desktop.svg, (c) 2026 Tom Ballard): a round body in Grok Bot orange #E06512 with a soft top
 * highlight and two capsule eyes that blink and, when idle, narrow for a moment.
 */
final class GrokbotMark extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint eye = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    static final int GROKBOT_ORANGE = 0xFFE06512;   // body colour of Tom Ballard's grokbot-desktop.svg
    private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String shape = "orb";
    private boolean open = true;
    private float lid = 1f;   // 1 open, ~0.5 idle squint

    GrokbotMark(Context c) {
        super(c);
        fill.setColor(GROKBOT_ORANGE);
        eye.setColor(0xFF141416);
        edge.setStyle(Paint.Style.STROKE);
        edge.setColor(0xFF141416);
        setFocusable(false);
    }

    void set(String shapeName, int color) {
        shape = shapeName == null || shapeName.isEmpty() ? "orb" : shapeName;
        fill.setColor(color);
        // Dark cut-out eyes, light ones on a dark body (same readability rule as on the desktop).
        eye.setColor(luma(color) < 0.25f ? 0xFFF4F4F5 : 0xFF141416);
        invalidate();
    }

    void blink(boolean on) {
        if (open != on) {
            open = on;
            invalidate();
        }
    }

    /** Idle squint: 1 = open, smaller = narrower (clamped 0.3..1). */
    void narrow(float f) {
        f = Math.max(0.3f, Math.min(1f, f));
        if (f != lid) {
            lid = f;
            invalidate();
        }
    }

    static float luma(int c) {
        return (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
    }

    /** Which primitive family draws a shape name (public for the unit test). */
    static String family(String s) {
        switch (s == null ? "" : s) {
            case "orb": return "orb";
            case "circle": case "cloud": case "flower": case "clover": case "pebble": case "crescent":
                return "round";
            case "square": return "square";
            case "triangle": return "poly3";
            case "diamond": return "diamond";
            case "pentagon": return "poly5";
            case "hexagon": return "poly6";
            case "heptagon": return "poly7";
            case "octagon": return "poly8";
            case "pill": case "peanut": return "pill";
            case "drop": return "drop";
            case "star": case "spark": case "burst": case "bolt": return "star";
            case "heart": return "heart";
            case "shield": case "ghost": return "shield";
            default: return "bean";          // bean, sprout and anything unknown
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), s = Math.min(w, h) * 0.46f, cx = w / 2f, cy = h / 2f;
        path.reset();
        String f = family(shape);
        switch (f) {
            case "orb":
            case "round":
                path.addCircle(cx, cy, s, Path.Direction.CW);
                break;
            case "square":
                r.set(cx - s, cy - s, cx + s, cy + s);
                path.addRoundRect(r, s * 0.45f, s * 0.45f, Path.Direction.CW);
                break;
            case "diamond":
                path.moveTo(cx, cy - s);
                path.lineTo(cx + s, cy);
                path.lineTo(cx, cy + s);
                path.lineTo(cx - s, cy);
                path.close();
                break;
            case "pill":
                r.set(cx - s * 0.62f, cy - s, cx + s * 0.62f, cy + s);
                path.addRoundRect(r, s * 0.62f, s * 0.62f, Path.Direction.CW);
                break;
            case "drop":
                path.moveTo(cx, cy - s);
                path.cubicTo(cx + s * 0.35f, cy - s * 0.45f, cx + s * 0.9f, cy - s * 0.05f, cx + s * 0.9f, cy + s * 0.32f);
                path.cubicTo(cx + s * 0.9f, cy + s * 0.8f, cx + s * 0.45f, cy + s, cx, cy + s);
                path.cubicTo(cx - s * 0.45f, cy + s, cx - s * 0.9f, cy + s * 0.8f, cx - s * 0.9f, cy + s * 0.32f);
                path.cubicTo(cx - s * 0.9f, cy - s * 0.05f, cx - s * 0.35f, cy - s * 0.45f, cx, cy - s);
                path.close();
                break;
            case "star":
                for (int i = 0; i < 10; i++) {
                    double a = -Math.PI / 2 + i * Math.PI / 5;
                    float rr = i % 2 == 0 ? s : s * 0.55f;
                    float x = cx + (float) (Math.cos(a) * rr), y = cy + (float) (Math.sin(a) * rr);
                    if (i == 0) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }
                path.close();
                break;
            case "heart":
                path.moveTo(cx, cy + s * 0.9f);
                path.cubicTo(cx - s * 1.2f, cy + s * 0.1f, cx - s * 0.8f, cy - s * 1.0f, cx, cy - s * 0.45f);
                path.cubicTo(cx + s * 0.8f, cy - s * 1.0f, cx + s * 1.2f, cy + s * 0.1f, cx, cy + s * 0.9f);
                path.close();
                break;
            case "shield":
                path.moveTo(cx - s * 0.85f, cy - s * 0.8f);
                path.quadTo(cx, cy - s * 1.05f, cx + s * 0.85f, cy - s * 0.8f);
                path.lineTo(cx + s * 0.85f, cy + s * 0.1f);
                path.quadTo(cx + s * 0.7f, cy + s * 0.75f, cx, cy + s);
                path.quadTo(cx - s * 0.7f, cy + s * 0.75f, cx - s * 0.85f, cy + s * 0.1f);
                path.close();
                break;
            case "bean":
                path.moveTo(cx - s * 0.2f, cy - s * 0.9f);
                path.cubicTo(cx + s * 0.6f, cy - s * 1.05f, cx + s, cy - s * 0.3f, cx + s * 0.9f, cy + s * 0.25f);
                path.cubicTo(cx + s * 0.8f, cy + s * 0.85f, cx + s * 0.1f, cy + s, cx - s * 0.4f, cy + s * 0.85f);
                path.cubicTo(cx - s * 0.95f, cy + s * 0.7f, cx - s, cy + s * 0.05f, cx - s * 0.85f, cy - s * 0.35f);
                path.cubicTo(cx - s * 0.75f, cy - s * 0.7f, cx - s * 0.5f, cy - s * 0.85f, cx - s * 0.2f, cy - s * 0.9f);
                path.close();
                break;
            default: {   // poly3 .. poly8, slightly rounded by the stroke join
                int n = f.charAt(4) - '0';
                for (int i = 0; i < n; i++) {
                    double a = -Math.PI / 2 + i * 2 * Math.PI / n;
                    float x = cx + (float) (Math.cos(a) * s), y = cy + (float) (Math.sin(a) * s);
                    if (i == 0) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }
                path.close();
            }
        }
        c.drawPath(path, fill);
        if (f.equals("orb")) {   // soft highlight so the ball reads as round at TV distance
            shine.setShader(new RadialGradient(cx - s * 0.35f, cy - s * 0.45f, s * 0.9f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, s, shine);
        }
        edge.setStrokeWidth(Math.max(1f, s * 0.06f));
        c.drawPath(path, edge);
        float ew = s * 0.16f, eh = open ? Math.max(ew, s * 0.42f * lid) : s * 0.08f, gap = s * 0.3f, ey = cy - s * 0.05f;
        r.set(cx - gap - ew / 2, ey - eh / 2, cx - gap + ew / 2, ey + eh / 2);
        c.drawRoundRect(r, ew / 2, ew / 2, eye);
        r.set(cx + gap - ew / 2, ey - eh / 2, cx + gap + ew / 2, ey + eh / 2);
        c.drawRoundRect(r, ew / 2, ew / 2, eye);
    }
}
