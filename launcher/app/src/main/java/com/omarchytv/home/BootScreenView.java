package com.omarchytv.home;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.util.List;

/**
 * v2.14 boot screen: plays {@link BootScript} over Home (CRT power-on, BIOS POST with the OTV badge, systemd
 * spinner → [ OK ] lines, the prompt types {@code omarchy-tv} and the OMARCHY TV wordmark draws itself), then
 * fades out. About 7 s, any key skips, keys never reach Home. LG webOS / Samsung Tizen: webos/app/boot.js.
 *
 * omarchytv.json:
 * <ul>
 * <li>{@code "bootScreen"}: {@code "boot"} (default, once per TV boot), {@code "wake"} (also every time the
 *     screen turns on: most Android TVs only go to standby, they rarely reboot), {@code "off"};</li>
 * <li>{@code "bootFx"}: scanlines and text glow (default true).</li>
 * </ul>
 * One View drawn with Canvas: no layout inflation, no extra Activity, nothing running once it has finished.
 */
final class BootScreenView extends View {

    private static final String PREFS = "boot_screen", SHOWN_BOOT = "shownBoot";

    private static BootScreenView showing;
    private static BroadcastReceiver screenOn;
    private static boolean wakePending;

    private final BootScript script;
    private final boolean fx;
    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bold = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint();
    private final Paint scan = new Paint();
    private final int bg, fg, fgBright, fgDim, accent, accent2, green, red, yellow;
    private long start;
    private boolean fading, done;
    /** v2.14 boot sound (bootSound, default on): cues from {@link BootScript#cues}, played by {@link BootSound}. */
    private BootSound sound;
    private int lastMs = -1;

    private BootScreenView(Context c, BootScript script, boolean fx) {
        super(c);
        this.script = script;
        this.fx = fx;
        density = c.getResources().getDisplayMetrics().density;
        Theme t = Theme.cur();
        bg = t.bgDarker | 0xFF000000;
        fg = t.fg;
        fgBright = t.fgBright;
        fgDim = t.fgDim;
        accent = t.accent;
        accent2 = t.accent2;
        green = t.green;
        red = t.red;
        yellow = t.yellow;
        Typeface tf = CastUi.font(c);
        paint.setTypeface(tf);
        bold.setTypeface(Typeface.create(tf, Typeface.BOLD));
        if (fx) {   // scanlines: one dark row every 3 px, as a repeating 1×3 shader (one drawPaint per frame)
            Bitmap b = Bitmap.createBitmap(1, 3, Bitmap.Config.ARGB_8888);
            b.setPixel(0, 2, 0x38000000);
            scan.setShader(new BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        }
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    // ---- MainActivity hooks ----

    /** From MainActivity.onCreate, after setContentView. */
    static void onCreate(Activity a, FrameLayout root) {
        String mode = LauncherConfig.get(a).bootScreen;
        if ("wake".equals(mode) && screenOn == null) {
            screenOn = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    wakePending = true;
                }
            };
            try {
                a.getApplicationContext().registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
            } catch (RuntimeException e) {
                screenOn = null;
            }
        }
        if ("off".equals(mode)) return;
        SharedPreferences p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long boot = bootId();
        if (p.getLong(SHOWN_BOOT, 0) == boot) return;   // already played since this boot
        p.edit().putLong(SHOWN_BOOT, boot).apply();
        show(a, root);
    }

    /** From MainActivity.onResume: with "wake", play again after the screen came back on. */
    static void onResume(Activity a, FrameLayout root) {
        if (!wakePending) return;
        wakePending = false;
        if ("wake".equals(LauncherConfig.get(a).bootScreen)) show(a, root);
    }

    /** From MainActivity.onDestroy. */
    static void onDestroy(Activity a) {
        if (screenOn != null) {
            try {
                a.getApplicationContext().unregisterReceiver(screenOn);
            } catch (RuntimeException ignored) {
            }
            screenOn = null;
        }
        if (showing != null) showing.finish();
    }

    /** From the top of MainActivity.dispatchKeyEvent: while it plays, any key skips and no key reaches Home. */
    static boolean consumeKey(KeyEvent e) {
        BootScreenView v = showing;
        if (v == null) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) v.fade();
        return true;
    }

    static boolean isShowing() {
        return showing != null;
    }

    private static void show(Activity a, FrameLayout root) {
        if (showing != null || root == null) return;
        LauncherConfig cfg = LauncherConfig.get(a);
        String version = "";
        try {
            version = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        long ramMb = 0;
        try {
            ActivityManager am = a.getSystemService(ActivityManager.class);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            if (am != null) {
                am.getMemoryInfo(mi);
                ramMb = mi.totalMem / (1024L * 1024L);
            }
        } catch (RuntimeException ignored) {
        }
        String model = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        BootScript s = BootScript.build(version, Theme.cur().name, model, ramMb, displayMode(a), appCount(a),
                cfg.promptUser, cfg.promptHost, new TvState(a.getApplicationContext()));
        BootScreenView v = new BootScreenView(a, s, cfg.bootFx);
        if (cfg.bootSound) v.sound = new BootSound(0.5f);
        showing = v;
        root.addView(v, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        v.bringToFront();
        v.setTranslationZ(10_000f);   // stays above views Home adds later (wallpaper intro, panes, companion)
        v.start = SystemClock.uptimeMillis();
        v.invalidate();
    }

    /** "3840x2160 @ 60Hz" from the display's physical mode, or "" if unknown. */
    private static String displayMode(Activity a) {
        try {
            android.view.Display d = a.getWindowManager().getDefaultDisplay();
            android.view.Display.Mode m = d.getMode();
            return m.getPhysicalWidth() + "x" + m.getPhysicalHeight() + " @ " + Math.round(m.getRefreshRate()) + "Hz";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Apps with a TV or phone launcher entry (the same apps Home lists), or -1 if unknown. */
    private static int appCount(Activity a) {
        try {
            android.content.pm.PackageManager pm = a.getPackageManager();
            java.util.Set<String> pkgs = new java.util.HashSet<>();
            for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
                for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(i, 0)) pkgs.add(r.activityInfo.packageName);
            }
            pkgs.remove(a.getPackageName());
            return pkgs.size();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** The real network and omarchy-link state, asked while their spinners turn. */
    private static final class TvState implements BootScript.Live {
        private final Context c;

        TvState(Context c) {
            this.c = c;
        }

        @Override
        public BootScript.Status get(String key, boolean timedOut) {
            return "net".equals(key) ? net(timedOut) : "pc".equals(key) ? pc(timedOut) : new BootScript.Status(true, "");
        }

        private BootScript.Status net(boolean timedOut) {
            android.net.ConnectivityManager cm = c.getSystemService(android.net.ConnectivityManager.class);
            android.net.Network n = cm != null ? cm.getActiveNetwork() : null;
            android.net.NetworkCapabilities caps = n != null ? cm.getNetworkCapabilities(n) : null;
            if (caps == null) return timedOut ? new BootScript.Status(false, "Network Manager: no connection yet.") : null;
            String ip = null;
            android.net.LinkProperties lp = cm.getLinkProperties(n);
            if (lp != null) for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() instanceof java.net.Inet4Address) ip = la.getAddress().getHostAddress();
            }
            if (ip == null && !timedOut) return null;   // link up, no address yet: keep spinning
            String at = ip != null ? ", " + ip : "";
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return new BootScript.Status(true, "Connected via Ethernet" + at + ".");
            }
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                String ssid = null;
                try {
                    android.net.wifi.WifiManager wm = c.getSystemService(android.net.wifi.WifiManager.class);
                    android.net.wifi.WifiInfo wi = wm != null ? wm.getConnectionInfo() : null;
                    ssid = wi != null ? wi.getSSID() : null;
                } catch (RuntimeException ignored) {
                }
                if (ssid != null) ssid = ssid.replace("\"", "").trim();
                // Without the location permission Android hides the name ("<unknown ssid>"): say Wi-Fi only.
                boolean named = ssid != null && !ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x");
                return new BootScript.Status(true, "Connected to Wi-Fi" + (named ? " \"" + ssid + "\"" : "") + at + ".");
            }
            return new BootScript.Status(true, "Connected" + at + ".");
        }

        private BootScript.Status pc(boolean timedOut) {
            if (!OmarchyLink.running()) {
                return timedOut ? new BootScript.Status(false, "omarchy-link.service did not start.") : null;
            }
            List<com.omarchytv.home.link.LinkStore.Pc> pcs = OmarchyLink.pcs();
            if (pcs.isEmpty()) return new BootScript.Status(true, "Started omarchy-link.service (no PC paired yet).");
            for (com.omarchytv.home.link.LinkStore.Pc p : pcs) {
                if (OmarchyLink.connected(p.id)) {
                    return new BootScript.Status(true, "omarchy-link: connected to " + name(p) + ".");
                }
            }
            return timedOut ? new BootScript.Status(false, "omarchy-link: " + name(pcs.get(0)) + " not reachable.") : null;
        }

        private static String name(com.omarchytv.home.link.LinkStore.Pc p) {
            return p.name != null && !p.name.trim().isEmpty() ? p.name.trim() : "your PC";
        }
    }

    /** Boot time to the minute, as HomeCheck: the same for the whole boot, different after a reboot. */
    private static long bootId() {
        return (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000L;
    }

    // ---- drawing ----

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawColor(0xFF000000);
        if (w == 0 || h == 0) return;
        int real = (int) (SystemClock.uptimeMillis() - start);
        int ms = script.clock(real);   // held while network / omarchy-link really wait
        if (sound != null && !fading) for (String cue : script.cues(lastMs, ms)) sound.cue(cue);
        lastMs = ms;

        if (ms < BootScript.CRT_MS) {
            drawCrt(canvas, w, h, BootScript.crt(ms));
        } else {
            canvas.drawColor(bg);
            if (!script.flickering(ms)) drawText(canvas, w, h, ms, real);
            if (fx) canvas.drawPaint(scan);
        }

        if (ms >= script.fadeAt) fade();
        if (!done) postInvalidateOnAnimation();
    }

    /** Power-on: a bright line grows across the middle, then opens vertically into the screen. */
    private void drawCrt(Canvas c, int w, int h, float p) {
        float line = Math.max(2f, 2f * density);
        if (p < 0.5f) {
            float q = p / 0.5f, half = w * q * q / 2f;
            fill.setColor(withAlpha(fgBright, 0.35f));
            c.drawRect(w / 2f - half, h / 2f - line * 3, w / 2f + half, h / 2f + line * 3, fill);
            fill.setColor(0xFFFFFFFF);
            c.drawRect(w / 2f - half, h / 2f - line / 2, w / 2f + half, h / 2f + line / 2, fill);
        } else {
            float q = (p - 0.5f) / 0.5f, ease = 1f - (1f - q) * (1f - q), half = Math.max(line, h * ease / 2f);
            fill.setColor(bg);
            c.drawRect(0, h / 2f - half, w, h / 2f + half, fill);
            fill.setColor(withAlpha(0xFFFFFFFF, 0.55f * (1f - q)));
            c.drawRect(0, h / 2f - half, w, h / 2f + half, fill);
        }
    }

    private void drawText(Canvas canvas, int w, int h, int ms, int real) {
        // BootScript.COLUMNS over 86% of the width, never more than 30 lines tall.
        paint.setTextSize(100f);
        float cell100 = paint.measureText("M");
        float size = Math.min(100f * (w * 0.86f) / (BootScript.COLUMNS * cell100), h / 30f / 1.35f);
        paint.setTextSize(size);
        bold.setTextSize(size);
        float cell = paint.measureText("M");
        float lineH = size * 1.35f, logoH = size * 1.2f;
        float x0 = w * 0.07f, top = h * 0.07f + size, y = top;

        if (ms < script.biosEnd) {   // OTV badge top right, as the old BIOS logos
            float bw = script.badge.get(0).length() * cell, by = top;
            for (String row : script.badge) {
                blocks(canvas, row, 0, row.length(), w * 0.93f - bw, by, cell, logoH, accent);
                by += logoH;
            }
        }

        List<BootScript.Line> vis = script.visibleAt(ms);
        BootScript.Line last = vis.isEmpty() ? null : vis.get(vis.size() - 1);
        for (BootScript.Line l : vis) {
            switch (l.kind) {
                case BLANK:
                    break;
                case OK: {
                    if (BootScript.spinning(l, ms)) {
                        float x = text(canvas, "[", x0, y, fg, paint);
                        x = text(canvas, BootScript.spinner(real - l.at), x, y, red, bold);
                        text(canvas, "] A start job is running for " + l.detail, x, y, fg, paint);
                    } else if (l.doneOk()) {
                        float x = text(canvas, "[  ", x0, y, fg, paint);
                        x = text(canvas, "OK", x, y, green, bold);
                        text(canvas, "  ] " + l.doneText(), x, y, fg, paint);
                    } else {
                        float x = text(canvas, "[ ", x0, y, fg, paint);
                        x = text(canvas, "WARN", x, y, yellow, bold);
                        text(canvas, " ] " + l.doneText(), x, y, fg, paint);
                    }
                    break;
                }
                case TITLE:
                    text(canvas, l.text, x0, y, accent, bold);
                    break;
                case PROMPT: {
                    int n = BootScript.typedChars(l, ms);
                    float x = text(canvas, l.text, x0, y, green, paint);
                    x = text(canvas, l.detail.substring(0, n), x, y, fgBright, paint);
                    boolean typing = n < l.detail.length();
                    if (typing || (l == last && (ms / 400) % 2 == 0)) {
                        fill.setColor(fg | 0xFF000000);
                        canvas.drawRect(x, y - size * 0.85f, x + cell, y + size * 0.2f, fill);
                    }
                    break;
                }
                case LOGO: {
                    int split = Math.min(script.logoSplit, l.text.length());
                    blocks(canvas, l.text, 0, split, x0, y, cell, logoH, accent);
                    blocks(canvas, l.text, split, l.text.length(), x0, y, cell, logoH, accent2);
                    y += logoH;
                    continue;
                }
                case INFO:
                    text(canvas, l.text, x0, y, fg, paint);
                    break;
                case MEMTEST:
                    text(canvas, BootScript.memText(l, ms), x0, y, fg, paint);
                    break;
                default:
                    text(canvas, l.text, x0, y, fgDim, paint);
                    break;
            }
            y += lineH;
        }
    }

    /**
     * Full-block cells of {@code row} in [from, to) as solid rectangles (not U+2588 glyphs, which leave seams
     * between cells on some fonts). Rows {@code rowH} apart touch, so letters are solid. {@code y} is the baseline.
     */
    private void blocks(Canvas c, String row, int from, int to, float x0, float y, float cell, float rowH, int color) {
        fill.setColor(color | 0xFF000000);
        if (fx) fill.setShadowLayer(cell * 0.9f, 0, 0, withAlpha(color, 0.5f));   // glow (hardware layer: API 28+)
        float top = y - rowH * 0.8f;
        for (int i = from; i < to; ) {
            if (row.charAt(i) != '\u2588') {
                i++;
                continue;
            }
            int j = i;
            while (j < to && row.charAt(j) == '\u2588') j++;
            c.drawRect(x0 + i * cell, top, x0 + j * cell, top + rowH, fill);
            i = j;
        }
        fill.clearShadowLayer();
    }

    private float text(Canvas c, String s, float x, float y, int color, Paint p) {
        p.setColor(color | 0xFF000000);
        if (fx) p.setShadowLayer(p.getTextSize() * 0.4f, 0, 0, withAlpha(color, 0.55f));
        else p.clearShadowLayer();
        c.drawText(s, x, y, p);
        return x + p.measureText(s);
    }

    private static int withAlpha(int color, float a) {
        return (Math.round(Math.max(0f, Math.min(1f, a)) * 255) << 24) | (color & 0x00FFFFFF);
    }

    private void fade() {
        if (fading) return;
        fading = true;
        animate().alpha(0f).setDuration(BootScript.FADE_MS).withEndAction(this::finish).start();
    }

    private void finish() {
        if (sound != null) {
            sound.close();
            sound = null;
        }
        if (done) return;
        done = true;
        animate().cancel();
        if (getParent() instanceof FrameLayout) ((FrameLayout) getParent()).removeView(this);
        if (showing == this) showing = null;
    }
}
