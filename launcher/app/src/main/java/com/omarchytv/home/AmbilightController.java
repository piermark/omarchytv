package com.omarchytv.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ambient Lighting / Ambilight Sync controller for OmarchyTV:
 * <ul>
 *   <li><b>Local Philips Lounge TVs</b>: controls {@code /sys/class/leds/ambilight} or Philips
 *       JointSpace API ({@code http://127.0.0.1:1925/6/ambilight/mode} &amp; {@code cached}) to switch
 *       between theme static bias lighting on the home launcher and native follow-video inside streaming apps.</li>
 *   <li><b>External LAN Bridges</b>: WLED ({@code /json/state}) or Hyperion.NG ({@code /json-rpc})
 *       when {@code ambilight_host} is configured in {@code omarchytv.json}.</li>
 * </ul>
 */
public final class AmbilightController {

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "omarchy-ambilight");
        t.setDaemon(true);
        return t;
    });

    private AmbilightController() {
    }

    /**
     * Applies the current Omarchy theme bias light when on the Home screen. Modes: "theme" / "hybrid" /
     * "video" paint the TV LEDs on Home (unchanged since v2.12); "cinema" paints them dim. A connected
     * lights hub (Hue / Home Assistant) is painted in every mode except "off".
     */
    public static void syncTheme(Context ctx) {
        LauncherConfig cfg = LauncherConfig.get(ctx);
        String mode = cfg.ambilightMode;
        if ("off".equals(mode)) return;
        Theme t = Theme.cur();
        boolean dim = "cinema".equals(mode);
        int bri = cfg.ambilightBrightness;
        int ledBri = dim ? Math.min(bri, 15) : bri;
        int color = scale(dim ? t.accent2 : t.accent, ledBri);
        int color2 = scale(t.accent2, ledBri);
        String host = cfg.ambilightHost;
        String tv = cfg.ambilightTvIp;
        com.omarchytv.home.lights.Lights.push(ctx, t.accent, t.accent2, t.bg, bri, dim);
        EXEC.execute(() -> {
            applyPhilipsStaticColor(tv, color);
            if (host != null && !host.trim().isEmpty()) {
                applyLanBridgeColor(host.trim(), color, color2, true);
            }
        });
    }

    /**
     * Switches Ambilight to native follow-video mode when entering a video player or streaming app
     * (when {@code ambilight_mode} is {@code "hybrid"}, {@code "video"} or {@code "cinema"}).
     */
    public static void restoreFollowVideo(Context ctx) {
        LauncherConfig cfg = LauncherConfig.get(ctx);
        String mode = cfg.ambilightMode;
        if ("off".equals(mode) || "theme".equals(mode)) return;
        String host = cfg.ambilightHost;
        String tv = cfg.ambilightTvIp;
        EXEC.execute(() -> {
            restorePhilipsFollowVideo(tv);
            if (host != null && !host.trim().isEmpty()) {
                restoreLanBridgeFollowVideo(host.trim());
            }
        });
    }

    /** Cycles {@code ambilight_mode} through {@code off -> theme -> hybrid -> video -> cinema -> off}. */
    public static String nextMode(String current) {
        if ("off".equals(current)) return "theme";
        if ("theme".equals(current)) return "hybrid";
        if ("hybrid".equals(current)) return "video";
        if ("video".equals(current)) return "cinema";
        return "off";
    }

    /** Mode cycle for the settings page when Ambilight is enabled (never lands on "off"). */
    public static String nextEnabledMode(String current) {
        String n = nextMode(current);
        return "off".equals(n) ? "theme" : n;
    }

    /** Cinemarchy: dim bias light on the TV LEDs + hub (unless Ambilight is off). */
    public static void cinemaDim(Context ctx) {
        LauncherConfig cfg = LauncherConfig.get(ctx);
        if ("off".equals(cfg.ambilightMode)) return;
        Theme t = Theme.cur();
        com.omarchytv.home.lights.Lights.push(ctx, t.accent, t.accent2, t.bg, cfg.ambilightBrightness, true);
        String tv = cfg.ambilightTvIp;
        int c = scale(t.accent2, Math.min(cfg.ambilightBrightness, 15));
        EXEC.execute(() -> applyPhilipsStaticColor(tv, c));
    }

    /** Lights hub only (Hue / Home Assistant), back on screen: theme colours, dim inside Cinemarchy. TV LEDs untouched. */
    public static void paintHub(Context ctx, boolean dim) {
        LauncherConfig cfg = LauncherConfig.get(ctx);
        if ("off".equals(cfg.ambilightMode)) return;
        Theme t = Theme.cur();
        com.omarchytv.home.lights.Lights.push(ctx, t.accent, t.accent2, t.bg, cfg.ambilightBrightness, dim || "cinema".equals(cfg.ambilightMode));
    }

    /** Scales an RGB colour by pct 0..100. */
    static int scale(int argb, int pct) {
        int p = Math.max(0, Math.min(100, pct));
        int r = ((argb >> 16) & 0xFF) * p / 100, g = ((argb >> 8) & 0xFF) * p / 100, b = (argb & 0xFF) * p / 100;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * Blocking JointSpace probe (call off the main thread): GET http://ip:1925/6/ambilight/mode.
     * Newer Philips models that need HTTPS :1926 + digest pairing report "error: unreachable".
     */
    public static String testTv(String ip) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + ip + ":1925/6/ambilight/mode").openConnection();
            c.setConnectTimeout(1200);
            c.setReadTimeout(1200);
            int code = c.getResponseCode();
            String body = "";
            if (code == 200) {
                java.io.InputStream in = c.getInputStream();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                int n;
                while ((n = in.read(buf)) > 0 && bo.size() < 65536) bo.write(buf, 0, n);
                in.close();
                body = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            }
            c.disconnect();
            if (code != 200) return "error: http " + code;
            return "ok · mode=" + new JSONObject(body).optString("current", "?");
        } catch (Throwable e) {
            return "error: unreachable";
        }
    }

    /** Tries this TV (127.0.0.1) then the configured IP; returns the first reachable host or null. */
    public static String autoDetect(String configured) {
        if (testTv("127.0.0.1").startsWith("ok")) return "127.0.0.1";
        if (configured != null && !configured.isEmpty() && !"127.0.0.1".equals(configured)
                && testTv(configured).startsWith("ok")) return configured;
        return null;
    }

    private static void applyPhilipsStaticColor(String tv, int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        try {
            postJson("http://" + tv + ":1925/6/ambilight/mode",
                    new JSONObject().put("current", "manual").toString(), 450);
            JSONObject px = new JSONObject().put("r", r).put("g", g).put("b", b);
            JSONObject body = new JSONObject().put("layer1",
                    new JSONObject()
                            .put("left", px)
                            .put("top", px)
                            .put("right", px)
                            .put("bottom", px));
            postJson("http://" + tv + ":1925/6/ambilight/cached", body.toString(), 450);
        } catch (Throwable ignored) {
        }
    }

    private static void restorePhilipsFollowVideo(String tv) {
        try {
            postJson("http://" + tv + ":1925/6/ambilight/mode",
                    new JSONObject().put("current", "internal").toString(), 450);
        } catch (Throwable ignored) {
        }
    }

    private static void applyLanBridgeColor(String host, int c1, int c2, boolean on) {
        int r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;
        try {
            if (host.contains(":19444") || host.endsWith("/json-rpc")) {
                String url = host.startsWith("http") ? host : ("http://" + host);
                if (!url.endsWith("/json-rpc")) url += "/json-rpc";
                JSONObject rpc = new JSONObject()
                        .put("command", "color")
                        .put("color", new JSONArray().put(r1).put(g1).put(b1))
                        .put("priority", 50)
                        .put("origin", "OmarchyTV");
                postJson(url, rpc.toString(), 700);
            } else {
                String url = host.startsWith("http") ? host : ("http://" + host);
                if (!url.endsWith("/json/state")) url = url.replaceAll("/+$", "") + "/json/state";
                JSONArray col = new JSONArray()
                        .put(new JSONArray().put(r1).put(g1).put(b1))
                        .put(new JSONArray().put(r2).put(g2).put(b2));
                JSONObject seg = new JSONObject().put("col", col).put("fx", 0);
                JSONObject state = new JSONObject()
                        .put("on", on)
                        .put("bri", 165)
                        .put("transition", 7)
                        .put("seg", new JSONArray().put(seg));
                postJson(url, state.toString(), 700);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void restoreLanBridgeFollowVideo(String host) {
        try {
            if (host.contains(":19444") || host.endsWith("/json-rpc")) {
                String url = host.startsWith("http") ? host : ("http://" + host);
                if (!url.endsWith("/json-rpc")) url += "/json-rpc";
                JSONObject rpc = new JSONObject()
                        .put("command", "clear")
                        .put("priority", 50);
                postJson(url, rpc.toString(), 700);
            } else {
                String url = host.startsWith("http") ? host : ("http://" + host);
                if (!url.endsWith("/json/state")) url = url.replaceAll("/+$", "") + "/json/state";
                JSONObject state = new JSONObject()
                        .put("on", true)
                        .put("udpn", new JSONObject().put("recv", true));
                postJson(url, state.toString(), 700);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void postJson(String urlStr, String json, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] out = json.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(out);
        }
        conn.getResponseCode();
        conn.disconnect();
    }
}
