package com.omarchytv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GrokBot Office: the room, drawn on the canvas (the same art as webos/app/office.js, all our own). The room, the
 * neon sign and a desk are drawn once into bitmaps; each frame only places them and the bots. At most 30 frames a
 * second, and no frames at all unless the activity is resumed and the view is on screen (setRunning).
 */
final class OfficeView extends View {
    static final float W = 1280, H = 720, TW = 92, TH = 46, OX = 640, OY = 178;
    private final Theme t;
    private final Typeface mono;
    private Office.Data data = Office.Data.empty();
    private Office.Pending pending;
    private String pendingTag = "pending", moreFmt = "+%1$d";
    private final Map<String, Float> walkers = new HashMap<>();
    private final Map<Integer, Shader> bodies = new HashMap<>();
    private Bitmap bg, sign, desk;
    private float signX, signY;
    private final long t0 = SystemClock.uptimeMillis();
    private long last;
    private boolean running;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rf = new RectF();
    private final DashPathEffect dash = new DashPathEffect(new float[]{4, 3}, 0);
    private final Runnable tick = this::invalidate;

    OfficeView(Context c, Theme t, Typeface mono) {
        super(c);
        this.t = t;
        this.mono = mono;
        tp.setTypeface(Typeface.create(mono, Typeface.BOLD));
    }

    void setLabels(String pendingTag, String moreFmt) {
        this.pendingTag = pendingTag;
        this.moreFmt = moreFmt;
    }

    void setData(Office.Data d, Office.Pending pend) {
        Office.Pending was = pending;
        data = d == null ? Office.Data.empty() : d;
        pending = pend;
        if (was != null && pend == null) {   // confirmed: the new bot keeps walking from where it was
            Float w = walkers.remove("__pending");
            Office.Bot nb = null;   // only the bot that really arrived (a failed hire walks nobody in)
            for (Office.Bot b : data.bots) if (!was.name.isEmpty() && b.name.equalsIgnoreCase(was.name)) nb = b;
            if (nb == null && was.name.isEmpty() && data.bots.size() > was.before) nb = data.bots.get(data.bots.size() - 1);
            if (nb != null && w != null) walkers.put(nb.id, w);
        }
        invalidate();
    }

    /** Frames only while the activity is resumed and the view is shown. */
    void setRunning(boolean on) {
        running = on;
        removeCallbacks(tick);
        if (on) invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        setRunning(false);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int v) {
        super.onWindowVisibilityChanged(v);
        if (v != VISIBLE) removeCallbacks(tick);
        else if (running) invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        recycle();
    }

    private void recycle() {
        if (bg != null) bg.recycle();
        if (sign != null) sign.recycle();
        if (desk != null) desk.recycle();
        bg = sign = desk = null;
    }

    private float scale() {
        return Math.min(getWidth() / W, getHeight() / H);
    }

    private void prerender() {
        float s = scale();
        bg = Bitmap.createBitmap(Math.max(1, Math.round(W * s)), Math.max(1, Math.round(H * s)), Bitmap.Config.RGB_565);
        Canvas c = new Canvas(bg);
        c.scale(s, s);
        room(c);
        sign = Bitmap.createBitmap(Math.round(360 * s), Math.round(300 * s), Bitmap.Config.ARGB_8888);
        Canvas sc = new Canvas(sign);
        sc.scale(s, s);
        PointF a = iso(4.3f, 0), b = iso(8.6f, 0);
        signX = (a.x + b.x) / 2 - 180;
        signY = (a.y + b.y) / 2 - 168 - 150;
        sc.translate(180, 150);
        neon(sc);
        desk = Bitmap.createBitmap(Math.round(140 * s), Math.round(90 * s), Bitmap.Config.ARGB_8888);
        Canvas dc = new Canvas(desk);
        dc.scale(s, s);
        dc.translate(70, 40);
        deskArt(dc);
    }

    // ---- drawing helpers ----

    static final class PointF {
        final float x, y;

        PointF(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    static PointF iso(float x, float y) {
        return new PointF(OX + (x - y) * TW / 2, OY + (x + y) * TH / 2);
    }

    static int shade(int c, float k) {
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        return Color.rgb(m(r, k), m(g, k), m(b, k));
    }

    private static int m(int v, float k) {
        return Math.max(0, Math.min(255, Math.round(k >= 0 ? v + (255 - v) * k : v * (1 + k))));
    }

    static int alpha(int c, float a) {
        return Color.argb(Math.round(a * 255), Color.red(c), Color.green(c), Color.blue(c));
    }

    private void poly(Canvas c, int color, PointF... pts) {
        path.reset();
        path.moveTo(pts[0].x, pts[0].y);
        for (int i = 1; i < pts.length; i++) path.lineTo(pts[i].x, pts[i].y);
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        p.setColor(color);
        c.drawPath(path, p);
    }

    private void outline(Canvas c, int color, float w, PointF... pts) {
        path.reset();
        path.moveTo(pts[0].x, pts[0].y);
        for (int i = 1; i < pts.length; i++) path.lineTo(pts[i].x, pts[i].y);
        path.close();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        p.setColor(color);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
    }

    private static PointF up(PointF a, float h) {
        return new PointF(a.x, a.y - h);
    }

    private void room(Canvas c) {
        int bgc = t.bg, acc = t.accent;
        p.setShader(new LinearGradient(0, 0, 0, H, shade(bgc, -0.35f), shade(bgc, -0.6f), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);
        PointF a = iso(0, 0), b = iso(Office.ROOM, 0), d = iso(0, Office.ROOM), e = iso(Office.ROOM, Office.ROOM);
        float wall = 250;
        p.setShader(new LinearGradient(0, a.y - wall, 0, a.y + 120, shade(bgc, 0.1f), shade(bgc, -0.15f), Shader.TileMode.CLAMP));
        path.reset();
        path.moveTo(d.x, d.y - wall); path.lineTo(a.x, a.y - wall); path.lineTo(a.x, a.y); path.lineTo(d.x, d.y); path.close();
        c.drawPath(path, p);
        p.setShader(new LinearGradient(0, a.y - wall, 0, a.y + 120, shade(bgc, 0.16f), shade(bgc, -0.08f), Shader.TileMode.CLAMP));
        path.reset();
        path.moveTo(a.x, a.y - wall); path.lineTo(b.x, b.y - wall); path.lineTo(b.x, b.y); path.lineTo(a.x, a.y); path.close();
        c.drawPath(path, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        p.setColor(alpha(acc, 0.35f));
        c.drawLine(a.x, a.y - wall, a.x, a.y, p);
        p.setStyle(Paint.Style.FILL);
        // windows with a city at night
        for (int w = 0; w < 3; w++) {
            PointF p0 = iso(0, 1.6f + w * 2.6f), p1 = iso(0, 3.6f + w * 2.6f);
            float top = 175, bot = 70;
            PointF[] win = {up(p0, top), up(p1, top), up(p1, bot), up(p0, bot)};
            poly(c, shade(bgc, -0.55f), win);
            c.save();
            c.clipPath(path);
            for (int s = 0; s < 9; s++) {
                float hx = p0.x + (p1.x - p0.x) * (s / 9f), by = p0.y + (p1.y - p0.y) * (s / 9f) - bot;
                float hh = 20 + (Office.hash("b" + w + s) % 60);
                p.setColor(shade(bgc, -0.25f));
                c.drawRect(hx, by - hh, hx + (p1.x - p0.x) / 9 + 1, by + 30, p);
                p.setColor(alpha(t.yellow, 0.55f));
                for (int l = 0; l < 3; l++) if (Office.hash("l" + w + s + l) % 3 == 0) c.drawRect(hx + 3 + l * 5, by - hh + 6 + l * 7, hx + 5 + l * 5, by - hh + 9 + l * 7, p);
            }
            c.restore();
            outline(c, alpha(acc, 0.5f), 2, win);
        }
        // the door the new bots walk in through
        PointF d0 = iso(0, 8.3f), d1 = iso(0, 9.7f);
        poly(c, shade(bgc, -0.42f), up(d0, 150), up(d1, 150), d1, d0);
        outline(c, alpha(acc, 0.6f), 2, up(d0, 150), up(d1, 150), d1, d0);
        p.setColor(alpha(t.green, 0.9f));
        c.drawCircle(d1.x + 8, d1.y - 75, 3, p);
        // floor
        for (int i = 0; i < Office.ROOM; i++)
            for (int j = 0; j < Office.ROOM; j++)
                poly(c, (i + j) % 2 == 1 ? shade(bgc, -0.05f) : shade(bgc, 0.03f), iso(i, j), iso(i + 1, j), iso(i + 1, j + 1), iso(i, j + 1));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        p.setColor(alpha(acc, 0.12f));
        for (int i = 0; i <= Office.ROOM; i++) {
            PointF x0 = iso(i, 0), x1 = iso(i, Office.ROOM), y0 = iso(0, i), y1 = iso(Office.ROOM, i);
            c.drawLine(x0.x, x0.y, x1.x, x1.y, p);
            c.drawLine(y0.x, y0.y, y1.x, y1.y, p);
        }
        p.setStyle(Paint.Style.FILL);
        outline(c, alpha(acc, 0.45f), 2, a, b, e, d);
        poly(c, alpha(acc, 0.08f), iso(1.5f, 1.4f), iso(9.2f, 1.4f), iso(9.2f, 9.2f), iso(1.5f, 9.2f));
        // a plant
        PointF pl = iso(9.3f, 0.6f);
        p.setColor(shade(bgc, 0.2f));
        rf.set(pl.x - 14, pl.y - 26, pl.x + 14, pl.y);
        c.drawRoundRect(rf, 5, 5, p);
        p.setColor(t.green);
        for (int f = 0; f < 6; f++) {
            c.save();
            float cx = (float) (pl.x + Math.cos(f) * 12), cy = pl.y - 40 - (f % 3) * 10;
            c.rotate((float) Math.toDegrees(f), cx, cy);
            rf.set(cx - 8, cy - 16, cx + 8, cy + 16);
            c.drawOval(rf, p);
            c.restore();
        }
    }

    private void neon(Canvas c) {
        int col = t.magenta;
        c.skew(0, 0.5f);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(46);
        tp.setTypeface(Typeface.create(mono, Typeface.BOLD_ITALIC));
        tp.setColor(col);
        tp.setShadowLayer(22, 0, 0, col);
        c.drawText("ship it", 0, 16, tp);
        tp.setShadowLayer(8, 0, 0, col);
        tp.setColor(alpha(Color.WHITE, 0.7f));
        c.drawText("ship it", 0, 16, tp);
        tp.clearShadowLayer();
        tp.setTypeface(Typeface.create(mono, Typeface.BOLD));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3);
        p.setColor(alpha(col, 0.8f));
        rf.set(-120, -40, 120, 40);
        c.drawRoundRect(rf, 14, 14, p);
        p.setStyle(Paint.Style.FILL);
    }

    private static PointF dp(float x, float y) {
        return new PointF((x - y) * TW / 2, (x + y) * TH / 2);
    }

    private void deskArt(Canvas c) {
        int bgc = t.bg;
        float w = 1.25f, d = 0.7f, h = 32;
        PointF t0 = dp(-w / 2, -d / 2), t1 = dp(w / 2, -d / 2), t2 = dp(w / 2, d / 2), t3 = dp(-w / 2, d / 2);
        poly(c, shade(bgc, -0.3f), t3, t2, new PointF(t2.x, t2.y + h), new PointF(t3.x, t3.y + h));
        poly(c, shade(bgc, -0.18f), t2, t1, new PointF(t1.x, t1.y + h), new PointF(t2.x, t2.y + h));
        poly(c, shade(bgc, 0.28f), t0, t1, t2, t3);
        outline(c, alpha(t.accent, 0.4f), 1.5f, t0, t1, t2, t3);
        PointF k = dp(0.28f, 0.02f);
        float lid = 17;
        poly(c, shade(bgc, 0.5f), new PointF(k.x - 20, k.y), new PointF(k.x + 2, k.y - 11), new PointF(k.x + 24, k.y), new PointF(k.x + 2, k.y + 11));
        poly(c, shade(bgc, -0.4f), new PointF(k.x - 20, k.y), new PointF(k.x + 2, k.y + 11), new PointF(k.x + 2, k.y + 11 - lid), new PointF(k.x - 20, k.y - lid));
        p.setColor(alpha(t.accent, 0.8f));
        c.drawCircle(k.x - 9, k.y - lid / 2 + 3, 2.2f, p);
        PointF mg = dp(-0.35f, -0.1f);
        p.setColor(shade(t.accent2, -0.1f));
        rf.set(mg.x - 5, mg.y - 11, mg.x + 5, mg.y);
        c.drawRoundRect(rf, 2, 2, p);
    }

    /** One bot; returns the top of its head. */
    private float blob(Canvas c, float x, float y, float s, int col, float tt, int phase, String status, boolean walking, boolean pend) {
        float bob = (float) Math.sin(tt * 2.2 + phase) * s * 0.03f, walk = walking ? (float) Math.abs(Math.sin(tt * 9)) * s * 0.12f : 0;
        float sq = walking ? (float) Math.sin(tt * 18) * 0.05f : (float) Math.sin(tt * 2.2 + phase) * 0.025f;
        float rx = s * 0.5f * (1 + sq), ry = s * 0.56f * (1 - sq), cy = y - ry - walk + bob;
        int a = pend ? Math.round(255 * (0.55f + 0.25f * (float) Math.sin(tt * 4))) : 255;
        p.setShader(null);
        p.setColor(Color.argb(Math.round(a * 0.28f), 0, 0, 0));
        rf.set(x - rx * 0.9f, y - rx * 0.32f, x + rx * 0.9f, y + rx * 0.32f);
        c.drawOval(rf, p);
        Shader body = bodies.get(col);
        if (body == null) {
            body = new RadialGradient(-0.35f, -0.4f, 0.75f, new int[]{shade(col, 0.45f), col, shade(col, -0.35f)}, new float[]{0, 0.6f, 1}, Shader.TileMode.CLAMP);
            bodies.put(col, body);
        }
        c.save();
        c.translate(x, cy);
        c.scale(s, s);
        float nrx = rx / s, nry = ry / s;
        path.reset();
        path.moveTo(-nrx, nry * 0.35f);
        path.cubicTo(-nrx * 1.05f, -nry * 1.05f, nrx * 1.05f, -nry * 1.05f, nrx, nry * 0.35f);
        path.cubicTo(nrx * 0.95f, nry * 0.95f, -nrx * 0.95f, nry * 0.95f, -nrx, nry * 0.35f);
        p.setShader(body);
        p.setAlpha(a);
        c.drawPath(path, p);
        p.setShader(null);
        c.restore();
        float blink = ((int) Math.floor(tt * 10 + phase * 7) % 47) == 0 ? 0.15f : 1f;
        float look = walking ? 0.25f : (float) Math.sin(tt * 0.7 + phase) * 0.18f;
        for (int e = -1; e <= 1; e += 2) {
            p.setColor(Color.argb(a, 255, 255, 255));
            float ex = x + e * rx * 0.33f + look * rx * 0.3f, ey = cy - ry * 0.12f;
            rf.set(ex - s * 0.1f, ey - s * 0.13f * blink, ex + s * 0.1f, ey + s * 0.13f * blink);
            c.drawOval(rf, p);
            p.setColor(Color.argb(a, 17, 17, 17));
            float px = x + e * rx * 0.33f + look * rx * 0.45f, py = cy - ry * 0.08f;
            rf.set(px - s * 0.05f, py - s * 0.07f * blink, px + s * 0.05f, py + s * 0.07f * blink);
            c.drawOval(rf, p);
        }
        if ("working".equals(status) && !walking) {
            p.setColor(shade(col, 0.2f));
            for (int hnd = -1; hnd <= 1; hnd += 2)
                c.drawCircle(x + hnd * rx * 0.55f, cy + ry * 0.62f - (float) Math.abs(Math.sin(tt * 14 + hnd)) * 4, s * 0.08f, p);
        } else if ("done".equals(status)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            p.setColor(Color.rgb(17, 17, 17));
            rf.set(x - s * 0.09f, cy + ry * 0.2f - s * 0.09f, x + s * 0.09f, cy + ry * 0.2f + s * 0.09f);
            c.drawArc(rf, 27, 126, false, p);
            p.setStyle(Paint.Style.FILL);
        } else if ("error".equals(status)) {
            tp.setColor(t.red);
            tp.setTextSize(s * 0.35f);
            tp.setTextAlign(Paint.Align.CENTER);
            c.drawText("!", x + rx, cy - ry, tp);
        }
        return cy - ry;
    }

    private void plate(Canvas c, float x, float y, String txt, int col, String tag, String prov) {
        tp.setTextAlign(Paint.Align.LEFT);
        tp.setTextSize(11);
        String pl = prov == null ? "" : prov.length() > 10 ? prov.substring(0, 10) : prov;
        float pw = pl.isEmpty() ? 0 : tp.measureText(pl) + 10;
        tp.setTextSize(15);
        float nw = tp.measureText(txt), tw = tag.isEmpty() ? 0 : tp.measureText(tag) + 12, w = Math.min(270, nw + tw + pw + (pw > 0 ? 6 : 0) + 34), h = 24, l = x - w / 2;
        p.setColor(alpha(t.bg, 0.86f));
        rf.set(l, y - h, l + w, y);
        c.drawRoundRect(rf, 8, 8, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f);
        p.setColor(col);
        p.setPathEffect(tag.isEmpty() ? null : dash);
        c.drawRoundRect(rf, 8, 8, p);
        p.setPathEffect(null);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(l + 13, y - h / 2, 4, p);
        Paint.FontMetrics fm = tp.getFontMetrics();
        float base = y - h / 2 - (fm.ascent + fm.descent) / 2;
        tp.setColor(t.fgBright);
        c.drawText(txt, l + 23, base, tp);
        if (!pl.isEmpty()) {   // the provider: a small generic text tag, no logos
            int pc = Color.parseColor(Office.providerColor(prov));
            float px = l + 23 + nw + 6;
            p.setColor(alpha(pc, 0.2f));
            rf.set(px, y - h / 2 - 7, px + pw, y - h / 2 + 7);
            c.drawRoundRect(rf, 4, 4, p);
            tp.setTextSize(11);
            tp.setColor(pc);
            Paint.FontMetrics f2 = tp.getFontMetrics();
            c.drawText(pl, px + 5, y - h / 2 - (f2.ascent + f2.descent) / 2, tp);
            tp.setTextSize(15);
        }
        if (!tag.isEmpty()) {
            tp.setColor(t.yellow);
            c.drawText(tag, l + w - tw - 2, base, tp);
        }
    }

    private static final class Seat {
        final double[] d;
        Office.Bot b;
        boolean pend;

        Seat(double[] d) {
            this.d = d;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getWidth() == 0) return;
        if (bg == null) prerender();
        long now = SystemClock.uptimeMillis();
        last = now;
        float tt = (now - t0) / 1000f, s = scale();
        p.setAlpha(255);
        canvas.drawColor(shade(t.bg, -0.6f));
        canvas.save();
        canvas.translate((getWidth() - W * s) / 2, (getHeight() - H * s) / 2);
        canvas.drawBitmap(bg, 0, 0, p);
        canvas.scale(s, s);
        float fl = 0.82f + 0.18f * (float) (Math.sin(tt * 3.1) * Math.sin(tt * 7.3));   // the neon hums
        if (((int) Math.floor(tt * 10)) % 53 == 0) fl = 0.35f;
        p.setAlpha(Math.round(255 * fl));
        canvas.save();
        canvas.translate(signX, signY);
        canvas.scale(1 / s, 1 / s);
        canvas.drawBitmap(sign, 0, 0, p);
        canvas.restore();
        p.setAlpha(255);
        double[][] desks = Office.layout(data.bots.size() + (pending != null ? 1 : 0));
        List<Seat> seats = new ArrayList<>();
        for (int i = 0; i < desks.length; i++) {
            Seat st = new Seat(desks[i]);
            if (i < data.bots.size() && i < Office.SHOWN) st.b = data.bots.get(i);
            seats.add(st);
        }
        if (pending != null) {
            Seat st = seats.get(Math.min(Math.min(data.bots.size(), Office.SHOWN), desks.length - 1));
            st.b = new Office.Bot("__pending", pending.name.isEmpty() ? "…" : pending.name, pending.role,
                    pending.name.isEmpty() ? String.format("#%06x", t.yellow & 0xFFFFFF) : Office.colorOf(null, pending.name), "idle", "", pending.provider, "");
            st.pend = true;
        }
        Collections.sort(seats, (x, y) -> Double.compare(x.d[0] + x.d[1], y.d[0] + y.d[1]));
        List<Object[]> plates = new ArrayList<>();
        for (Seat st : seats) {
            PointF pt = iso((float) st.d[0], (float) st.d[1]);
            if (st.b == null) {
                p.setAlpha(190);
                drawDesk(canvas, pt, s);
                p.setAlpha(255);
                continue;
            }
            Office.Bot b = st.b;
            int col = Color.parseColor(b.color), phase = (int) (Office.hash(b.id) % 7);
            float seatY = pt.y - TH * 0.42f, x = pt.x, y = seatY;
            boolean walking = false;
            if (st.pend || walkers.containsKey(b.id)) {
                Float w0 = walkers.get(b.id);
                if (w0 == null) walkers.put(b.id, w0 = tt);
                float k = Math.min(1, (tt - w0) / 3.2f);
                PointF door = iso(0.2f, 9.2f);
                if (k < 1) {
                    walking = true;
                    float e = k * k * (3 - 2 * k);
                    x = door.x + (pt.x - door.x) * e;
                    y = door.y + (seatY - door.y) * e;
                } else if (!st.pend) walkers.remove(b.id);
            }
            float top;
            if (walking) {
                drawDesk(canvas, pt, s);
                top = blob(canvas, x, y, 54, col, tt, phase, b.status, true, st.pend);
            } else {
                top = blob(canvas, x, y, 54, col, tt, phase, b.status, false, st.pend);
                drawDesk(canvas, pt, s);
            }
            plates.add(new Object[]{x, top - 8, b.name, col, st.pend ? pendingTag : "", b.provider});
        }
        for (Object[] pl : plates) plate(canvas, (float) pl[0], (float) pl[1], (String) pl[2], (int) pl[3], (String) pl[4], (String) pl[5]);
        if (data.bots.size() > Office.SHOWN) {
            tp.setTextSize(16);
            tp.setTextAlign(Paint.Align.RIGHT);
            tp.setColor(t.fg);
            canvas.drawText(String.format(moreFmt, data.bots.size() - Office.SHOWN), W - 40, 120, tp);
        }
        canvas.restore();
        if (running && getWindowVisibility() == VISIBLE) {
            long wait = Math.max(0, Office.FRAME_MS - (SystemClock.uptimeMillis() - now));
            postDelayed(tick, wait);
        }
    }

    private void drawDesk(Canvas c, PointF pt, float s) {
        c.save();
        c.translate(pt.x - 70, pt.y - 40);
        c.scale(1 / s, 1 / s);
        c.drawBitmap(desk, 0, 0, p);
        c.restore();
    }

    /** OmarchyTV's mark for the office (a rounded terminal tile, a prompt, an orbit): original, no Grok or xAI logo. */
    static void mark(Canvas c, float x, float y, float s, int acc, int fg) {
        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.save();
        c.translate(x, y);
        RectF r = new RectF(-s / 2, -s / 2, s / 2, s / 2);
        q.setColor(Color.argb(90, 0, 0, 0));
        c.drawRoundRect(r, s * 0.22f, s * 0.22f, q);
        q.setStyle(Paint.Style.STROKE);
        q.setStrokeWidth(s * 0.07f);
        q.setColor(acc);
        c.drawRoundRect(r, s * 0.22f, s * 0.22f, q);
        q.setStrokeCap(Paint.Cap.ROUND);
        q.setStrokeJoin(Paint.Join.ROUND);
        q.setColor(fg);
        q.setStrokeWidth(s * 0.08f);
        Path pp = new Path();
        pp.moveTo(-s * 0.24f, -s * 0.16f);
        pp.lineTo(-s * 0.06f, 0);
        pp.lineTo(-s * 0.24f, s * 0.16f);
        c.drawPath(pp, q);
        c.drawLine(s * 0.02f, s * 0.18f, s * 0.24f, s * 0.18f, q);
        q.setColor(acc);
        q.setStrokeWidth(s * 0.035f);
        c.save();
        c.rotate(-25.8f);
        c.drawOval(new RectF(-s * 0.72f, -s * 0.26f, s * 0.72f, s * 0.26f), q);
        c.restore();
        q.setStyle(Paint.Style.FILL);
        c.drawCircle(s * 0.62f, -s * 0.33f, s * 0.07f, q);
        c.restore();
    }
}
