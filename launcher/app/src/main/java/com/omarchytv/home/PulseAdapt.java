package com.omarchytv.home;

import android.content.Context;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * v2.20 host injection: the Pulse adapter ({@code omarchytv-pulse-adapt.js}) is ours, not the vendored
 * engine. When the saver serves {@code assets/pulse/*.html} it inserts a script tag in front of Pulse's
 * own scripts so the 720p-floor / frame-time / fps-cap wrapper is in place before the first canvas resize.
 * A small inline hint gives the adapter the TV's real pixel size when WebView's devicePixelRatio is 1
 * on a 4K panel (otherwise the scene and plane labels would be drawn at CSS size and look soft).
 */
final class PulseAdapt {
    static final String SCRIPT = "../omarchytv-pulse-adapt.js";
    private static final String MARK = "omarchytv-pulse-adapt.js";

    private PulseAdapt() {
    }

    static boolean pulseHtml(String rel) {
        return rel != null && rel.startsWith("pulse/") && rel.endsWith(".html");
    }

    /** Physical pixels and density of the TV, for the Pulse host hint. */
    static float deviceDpr(Context c) {
        if (c == null) return 1f;
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        float dpr = dm.density;
        int[] px = devicePixels(c);
        int cssW = Math.max(1, dm.widthPixels);
        int cssH = Math.max(1, dm.heightPixels);
        dpr = Math.max(dpr, Math.max(px[0] / (float) cssW, px[1] / (float) cssH));
        return Math.max(1f, dpr);
    }

    static int[] devicePixels(Context c) {
        DisplayMetrics dm = new DisplayMetrics();
        try {
            WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            Display d = wm != null ? wm.getDefaultDisplay() : null;
            if (d != null) {
                if (Build.VERSION.SDK_INT >= 17) d.getRealMetrics(dm);
                else d.getMetrics(dm);
            }
        } catch (Exception e) {
            dm = c.getResources().getDisplayMetrics();
        }
        if (dm.widthPixels <= 0 || dm.heightPixels <= 0) {
            dm = c.getResources().getDisplayMetrics();
        }
        return new int[]{Math.max(1, dm.widthPixels), Math.max(1, dm.heightPixels)};
    }

    static InputStream inject(InputStream in) throws IOException {
        return inject(in, 1f, 0, 0);
    }

    static InputStream inject(InputStream in, Context c) throws IOException {
        if (c == null) return inject(in);
        int[] px = devicePixels(c);
        return inject(in, deviceDpr(c), px[0], px[1]);
    }

    static InputStream inject(InputStream in, float dpr, int pxW, int pxH) throws IOException {
        byte[] raw = read(in);
        String html = new String(raw, StandardCharsets.UTF_8);
        if (html.contains(MARK)) return new ByteArrayInputStream(raw);
        String hint = "";
        if (dpr > 1.01f || pxW > 0) {
            hint = "<script>window.__omarchyDpr=" + String.format(Locale.US, "%.3f", Math.max(1f, dpr))
                    + ";window.__omarchyDevicePixels={w:" + Math.max(0, pxW) + ",h:" + Math.max(0, pxH) + "};</script>";
        }
        String tag = hint + "<script src=\"" + SCRIPT + "\"></script>";
        String needle = "<script src=\"pulse-tv-data.js\">";
        int at = html.indexOf(needle);
        if (at >= 0) {
            html = html.substring(0, at) + tag + "\n" + html.substring(at);
        } else {
            int head = html.indexOf("<head>");
            if (head >= 0) html = html.substring(0, head + 6) + tag + html.substring(head + 6);
            else html = tag + html;
        }
        return new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            in.close();
        }
        return out.toByteArray();
    }
}
