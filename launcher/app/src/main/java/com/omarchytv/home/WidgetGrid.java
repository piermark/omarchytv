package com.omarchytv.home;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.omarchytv.home.link.HomeWidgets;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * v2.22 (#157) one page of the Home widget grid: 12 × 6 cells between the bar and the app dock, each widget drawn
 * natively (text as text). Only the page on screen is inflated; the others stay JSON in omarchytv.json "widgets".
 * D-pad moves between widgets; OK runs a widget's action; a long press opens Move / Resize / Remove.
 */
final class WidgetGrid extends FrameLayout {
    interface Host {
        void runShortcut(LauncherConfig.Shortcut s);
        void openPage(String url, String title);
        void openQr(String url, String label);
        void changed();
    }

    private final Activity act;
    private final float s;
    private final Typeface tf, tfBold;
    private final Host host;
    private Theme t;
    private JSONArray all = new JSONArray();
    private int page = 1;
    private final List<View> boxes = new ArrayList<>();
    /** Images by url, decoded down to the cell size; a few at most, so 2 GB TVs stay light. */
    private static final LruCache<String, Bitmap> IMAGES = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };
    // editing (long press): "move" or "resize"
    private String editId, editMode;
    private int ex, ey, ew, eh;

    WidgetGrid(Activity a, float s, Typeface tf, Typeface tfBold, Host host) {
        super(a);
        this.act = a;
        this.s = s;
        this.tf = tf;
        this.tfBold = tfBold;
        this.host = host;
        setClipChildren(false);
        addOnLayoutChangeListener((v, l, top, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - top != ob - ot) post(this::rebuild);
        });
    }

    /** The pages that hold widgets, ascending (page 1 is always there once any widget exists). */
    static List<Integer> pages(JSONArray all) {
        return HomeWidgets.pages(all);
    }

    void set(JSONArray widgets, Theme theme) {
        all = widgets != null ? widgets : new JSONArray();
        t = theme;
        if (editId == null) rebuild();
    }

    void showPage(int p) {
        page = p;
        editId = null;
        rebuild();
    }

    int page() {
        return page;
    }

    boolean editing() {
        return editId != null;
    }

    private int px(float v) {
        return Math.round(v * s);
    }

    private float cellW() {
        return getWidth() / (float) HomeWidgets.COLS;
    }

    private float cellH() {
        return getHeight() / (float) HomeWidgets.ROWS;
    }

    // ---------------------------------------------------------------- building

    void rebuild() {
        View focused = act.getCurrentFocus();
        String focusId = focused != null && focused.getTag() instanceof String ? (String) focused.getTag() : null;
        removeAllViews();
        boxes.clear();
        if (t == null || getWidth() <= 0 || getHeight() <= 0) return;
        JSONArray shown;
        try {
            shown = HomeWidgets.list(all, page);
        } catch (Exception e) {
            return;
        }
        long now = System.currentTimeMillis() / 1000;
        int gap = px(8);
        for (int i = 0; i < shown.length(); i++) {
            JSONObject w = shown.optJSONObject(i);
            if (w == null) continue;
            int ttl = w.optInt("ttl", 0);
            long age = now - w.optLong("updatedAt", now);
            if (ttl > 0 && age > 2L * ttl) continue;   // stale twice over: hidden, like panes
            boolean editingThis = w.optString("id").equals(editId);
            int x = editingThis ? ex : w.optInt("x"), y = editingThis ? ey : w.optInt("y");
            int cw = editingThis ? ew : w.optInt("w", 1), ch = editingThis ? eh : w.optInt("h", 1);
            View box = build(w, Math.round(cw * cellW()) - gap, Math.round(ch * cellH()) - gap);
            if (box == null) continue;
            if (ttl > 0 && age > ttl) box.setAlpha(0.55f);
            if (editingThis) box.setAlpha(0.8f);
            LayoutParams lp = new LayoutParams(Math.round(cw * cellW()) - gap, Math.round(ch * cellH()) - gap);
            lp.leftMargin = Math.round(x * cellW()) + gap / 2;
            lp.topMargin = Math.round(y * cellH()) + gap / 2;
            addView(box, lp);
            boxes.add(box);
            if (w.optString("id").equals(focusId) || editingThis) box.post(box::requestFocus);
        }
    }

    private View build(JSONObject w, int wPx, int hPx) {
        String type = w.optString("type"), id = w.optString("id");
        JSONObject d = w.optJSONObject("data");
        if (d == null) d = new JSONObject();
        BoxView box = new BoxView(act, s, tf);
        String title = w.optString("title", "");
        String icon = w.optString("icon", "");
        box.setLegend((icon.isEmpty() ? "" : icon + " ") + (title.isEmpty() ? id : title), px(10), px(14));
        int bg = Theme.withAlpha(t.bg, 0.92f);
        box.setColors(bg, t.muted, t.accent, t.cyan);
        box.focusBorder = true;
        box.setFocusable(true);
        box.setTag(id);
        box.setOnLongClickListener(v -> {
            editMenu(v, w);
            return true;
        });
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        box.addView(body, new LinearLayout.LayoutParams(-1, -1));
        switch (type) {
            case "lines": {
                JSONArray ls = d.optJSONArray("lines");
                for (int i = 0; ls != null && i < ls.length(); i++) {
                    JSONObject l = ls.optJSONObject(i);
                    if (l == null) continue;
                    LinearLayout row = new LinearLayout(act);
                    TextView k = text(l.optString("label"), 18, false, t.fgDim);
                    TextView v = text(l.optString("value"), 18, false, "up".equals(l.optString("tone")) ? t.green
                            : "down".equals(l.optString("tone")) ? t.red : t.fg);
                    v.setGravity(Gravity.END);
                    row.addView(k, new LinearLayout.LayoutParams(0, -2, 1f));
                    row.addView(v, new LinearLayout.LayoutParams(-2, -2));
                    body.addView(row);
                }
                break;
            }
            case "stat": {
                TextView v = text(d.optString("value") + (d.optString("unit").isEmpty() ? "" : " " + d.optString("unit")),
                        Math.min(72, Math.max(28, hPx / s / 3f)), true, t.fgBright);
                body.addView(v);
                String sub = d.optString("label");
                String trend = d.optString("trend");
                String delta = d.optString("delta");
                if (!delta.isEmpty() || !trend.isEmpty()) {
                    String arrow = "up".equals(trend) ? "▲ " : "down".equals(trend) ? "▼ " : "flat".equals(trend) ? "▬ " : "";
                    sub = (sub.isEmpty() ? "" : sub + "  ") + arrow + delta;
                }
                body.addView(text(sub, 18, false, "up".equals(trend) ? t.green : "down".equals(trend) ? t.red : t.fgDim));
                break;
            }
            case "text": {
                TextView v = text(d.optString("text"), 18, false, t.fg);
                v.setSingleLine(false);
                v.setEllipsize(TextUtils.TruncateAt.END);
                body.addView(v, new LinearLayout.LayoutParams(-1, -1));
                break;
            }
            case "chart":
                body.addView(new Chart(d), new LinearLayout.LayoutParams(-1, -1));
                break;
            case "image": {
                ImageView iv = new ImageView(act);
                iv.setScaleType("contain".equals(d.optString("fit")) ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
                body.addView(iv, new LinearLayout.LayoutParams(-1, -1));
                loadImage(d.optString("url"), iv, wPx, hPx);
                break;
            }
            case "page": {
                String url = d.optString("url");
                body.addView(text(hostOf(url), 18, false, t.fgDim));
                body.addView(text("OK ▸", 18, true, t.accent));
                box.setOnClickListener(v -> host.openPage(url, title.isEmpty() ? id : title));
                break;
            }
            case "qr": {
                String url = "skill".equals(d.optString("preset")) ? SkillQr.url(act) : d.optString("url");
                String label = "skill".equals(d.optString("preset")) ? act.getString(R.string.skill_qr_label) : d.optString("label");
                body.addView(text("▣ " + (label.isEmpty() ? hostOf(url) : label), 18, false, t.fg));
                body.addView(text("OK ▸", 18, true, t.accent));
                box.setOnClickListener(v -> host.openQr(url, label));
                break;
            }
            case "shortcuts": {
                JSONArray items = d.optJSONArray("items");
                LinearLayout row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (int i = 0; items != null && i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) continue;
                    LauncherConfig.Shortcut sc = LauncherConfig.shortcutOf(it);
                    if (sc == null) continue;
                    TextView chip = text((it.optString("icon").isEmpty() ? "" : it.optString("icon") + " ") + it.optString("label"), 18, false, t.fg);
                    chip.setPadding(px(10), px(6), px(10), px(6));
                    chip.setFocusable(true);
                    chip.setTag(id);
                    chip.setBackground(focusBg());
                    chip.setOnClickListener(v -> host.runShortcut(sc));
                    chip.setOnLongClickListener(v -> {
                        editMenu(box, w);
                        return true;
                    });
                    row.addView(chip);
                }
                body.addView(row);
                box.setFocusable(items == null || items.length() == 0);
                break;
            }
            case "homeassistant.scenes":
            case "homeassistant.entities":
                HaWidgets.bind(act, body, d, type, t, this::text, box);
                break;
            case "radio":
                RadioWidget.bind(act, body, t, this::text, box);
                break;
            default:
                return null;   // a type this version does not know: skipped (forward compatible)
        }
        return box;
    }

    interface Texts {
        TextView text(String v, float sizePx, boolean bold, int color);
    }

    TextView text(String v, float sizePx, boolean bold, int color) {
        TextView tv = new TextView(act);
        tv.setText(v);
        tv.setTypeface(bold ? tfBold : tf);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx * s);
        tv.setIncludeFontPadding(false);
        tv.setTextColor(color);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        return tv;
    }

    /** A chip background: the theme's selection colour when focused, nothing otherwise. */
    private android.graphics.drawable.Drawable focusBg() {
        android.graphics.drawable.StateListDrawable d = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable on = new android.graphics.drawable.GradientDrawable();
        on.setColor(t.selection);
        on.setStroke(px(2), t.accent);
        d.addState(new int[]{android.R.attr.state_focused}, on);
        d.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(0));
        return d;
    }

    static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    /** A sparkline or bars of the data's points, in the theme's accent, with the last value. */
    private final class Chart extends View {
        private final double[] pts;
        private final boolean bars;
        private final String label, unit;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG);

        Chart(JSONObject d) {
            super(act);
            JSONArray a = d.optJSONArray("points");
            pts = new double[a != null ? a.length() : 0];
            for (int i = 0; i < pts.length; i++) pts[i] = a.optDouble(i, 0);
            bars = "bars".equals(d.optString("kind"));
            label = d.optString("label");
            unit = d.optString("unit");
            line.setColor(t.accent);
            line.setStrokeWidth(px(3));
            line.setStyle(Paint.Style.STROKE);
            txt.setColor(t.fg);
            txt.setTypeface(tf);
            txt.setTextSize(px(18));
        }

        @Override
        protected void onDraw(Canvas c) {
            if (pts.length == 0) return;
            int top = px(26), h = getHeight() - top, w = getWidth();
            double lo = pts[0], hi = pts[0];
            for (double v : pts) {
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
            double span = hi - lo == 0 ? 1 : hi - lo;
            String last = fmt(pts[pts.length - 1]) + (unit.isEmpty() ? "" : " " + unit);
            c.drawText((label.isEmpty() ? "" : label + "  ") + last, 0, px(18), txt);
            if (bars) {
                Paint f = new Paint(line);
                f.setStyle(Paint.Style.FILL);
                float bw = w / (float) pts.length;
                for (int i = 0; i < pts.length; i++) {
                    float bh = (float) ((pts[i] - Math.min(0, lo)) / (hi - Math.min(0, lo) == 0 ? 1 : hi - Math.min(0, lo)) * h);
                    c.drawRect(i * bw + bw * 0.15f, getHeight() - bh, (i + 1) * bw - bw * 0.15f, getHeight(), f);
                }
            } else {
                Path p = new Path();
                for (int i = 0; i < pts.length; i++) {
                    float x = pts.length == 1 ? w / 2f : i * (w - 1f) / (pts.length - 1);
                    float y = top + (float) ((hi - pts[i]) / span * (h - px(4))) + px(2);
                    if (i == 0) p.moveTo(x, y);
                    else p.lineTo(x, y);
                }
                c.drawPath(p, line);
            }
        }
    }

    static String fmt(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e12 ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    // ---------------------------------------------------------------- images (https:// or LAN http://, ≤ 512 KB)

    private void loadImage(String url, ImageView iv, int wPx, int hPx) {
        Bitmap hit = IMAGES.get(url);
        if (hit != null) {
            iv.setImageBitmap(hit);
            return;
        }
        if (!RemotePanes.urlAllowed(url)) return;
        new Thread(() -> {
            Bitmap b = fetch(url, Math.max(1, wPx), Math.max(1, hPx));
            if (b != null) {
                IMAGES.put(url, b);
                iv.post(() -> iv.setImageBitmap(b));
            }
        }, "widget-image").start();
    }

    static Bitmap fetch(String url, int wPx, int hPx) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(6000);
            c.setInstanceFollowRedirects(false);
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            try (InputStream in = c.getInputStream()) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > RemotePanes.MAX_IMAGE) return null;
                }
            }
            byte[] bytes = out.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= wPx && o.outHeight / (sample * 2) >= hPx) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            d.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, d);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------------------------------------------------------------- editing with the remote

    private void editMenu(View anchor, JSONObject w) {
        PopupMenu m = new PopupMenu(act, anchor);
        m.getMenu().add(0, 1, 0, R.string.ctx_move);
        m.getMenu().add(0, 2, 1, R.string.widget_resize);
        m.getMenu().add(0, 3, 2, R.string.widget_remove);
        m.setOnMenuItemClickListener(item -> {
            String id = w.optString("id");
            if (item.getItemId() == 3) {
                save(j -> HomeWidgets.delete(j, id));
                return true;
            }
            editId = id;
            editMode = item.getItemId() == 1 ? "move" : "resize";
            ex = w.optInt("x");
            ey = w.optInt("y");
            ew = w.optInt("w", 1);
            eh = w.optInt("h", 1);
            Toast.makeText(act, item.getItemId() == 1 ? R.string.move_hint : R.string.widget_resize_hint, Toast.LENGTH_SHORT).show();
            rebuild();
            return true;
        });
        m.show();
    }

    /** While moving or resizing: arrows change the box (only into free cells), OK saves, BACK cancels. */
    boolean onKey(KeyEvent e) {
        if (editId == null) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int k = e.getKeyCode();
        int nx = ex, ny = ey, nw = ew, nh = eh;
        boolean move = "move".equals(editMode);
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (move) nx--; else nw--;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (move) nx++; else nw++;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (move) ny--; else nh--;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (move) ny++; else nh++;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER: {
                String id = editId;
                int sx = ex, sy = ey, sw = ew, sh = eh;
                editId = null;
                save(j -> HomeWidgets.move(j, id, new JSONObject().put("x", sx).put("y", sy).put("w", sw).put("h", sh),
                        System.currentTimeMillis() / 1000));
                return true;
            }
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                editId = null;
                rebuild();
                return true;
            default:
                return true;
        }
        if (HomeWidgets.fits(all, page, nx, ny, nw, nh, editId)) {
            ex = nx;
            ey = ny;
            ew = nw;
            eh = nh;
            rebuild();
        }
        return true;
    }

    interface Change {
        void apply(JSONArray widgets) throws Exception;
    }

    private void save(Change c) {
        try {
            LauncherConfig.updateOrThrow(act, j -> {
                JSONArray a = j.optJSONArray("widgets");
                if (a == null) j.put("widgets", a = new JSONArray());
                c.apply(a);
            });
        } catch (Exception e) {
            Toast.makeText(act, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
        host.changed();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        editId = null;
    }
}
