package com.omarchytv.home;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * v2.21: the theme the TV shows, readable by other apps on the network (GET /v1/theme, PROTOCOL.md §6 "Theme for
 * apps"). One canonical source for custom dashboards, Remote panes and agent pages: they read colours and wallpaper
 * here instead of running their own theme server. The TV is always on and already holds the theme the PC pushed
 * (or the one picked on the TV), so it answers even while the PC sleeps.
 * <ul>
 * <li>Read-only, no pairing needed, LAN (and tailnet, when enabled) only, like every Omarchy Link request.</li>
 * <li>Off with Settings → Connect Omarchy Linux → "Share theme with apps on the network" ({@code link.shareTheme}).</li>
 * <li>Wallpaper: the PC's pushed JPEG when the TV shows it, else the theme's own background (WebP, as bundled);
 *     none for procedural or animated wallpapers.</li>
 * </ul>
 */
final class ThemeShare {
    static final String SCHEMA = "omarchytv-theme/1";
    private static final long WALLPAPER_MAX = 16L << 20;

    /** A wallpaper ready to serve. */
    static final class Wallpaper {
        final byte[] bytes;
        final String type;
        final String sha256;

        Wallpaper(byte[] bytes, String type) {
            this.bytes = bytes;
            this.type = type;
            this.sha256 = sha256(bytes);
        }
    }

    private static volatile Object[] cache;   // {key, Wallpaper}: the last wallpaper read, keyed by source + mtime

    private ThemeShare() {
    }

    /**
     * The JSON body. Top-level background / foreground / accent / accent2 for the simple case, the full colors.toml
     * in {@code colors}, and the wallpaper's address and checksum ({@code null} when there is none to share).
     */
    static JSONObject payload(Theme t, Wallpaper wp, String source) throws JSONException {
        JSONObject colors = t.palette();
        JSONObject o = new JSONObject();
        o.put("schema", SCHEMA);
        o.put("name", t.name);
        o.put("mode", t.light ? "light" : "dark");
        o.put("source", source);
        o.put("background", hex(t.bg));
        o.put("foreground", hex(t.fg));
        o.put("accent", hex(t.accent));
        o.put("accent2", hex(t.accent2));
        o.put("colors", colors);
        if (wp != null) {
            o.put("wallpaper", new JSONObject().put("url", "/v1/theme/wallpaper").put("type", wp.type).put("sha256", wp.sha256)
                    .put("bytes", wp.bytes.length));
        } else {
            o.put("wallpaper", JSONObject.NULL);
        }
        return o;
    }

    /** "theme.source" from omarchytv.json: "omarchy-link" when the PC pushed it, else "tv". */
    static String source(JSONObject themeCfg) {
        return themeCfg != null && "omarchy-link".equals(themeCfg.optString("source")) ? "omarchy-pc" : "tv";
    }

    /** What the Home wallpaper shows, as bytes: the PC's JPEG, or the theme background asset; null otherwise. */
    static Wallpaper wallpaper(Context c) {
        LauncherConfig cfg = LauncherConfig.get(c);
        String raw = cfg.wallpaper == null ? "" : cfg.wallpaper;
        File custom = raw.startsWith("/") && cfg.syncPcWallpaper ? new File(raw) : null;
        if (custom != null && !custom.isFile()) custom = null;
        String which = custom != null ? null : (raw.startsWith("/") || FractalLayer.is(raw) ? "omarchy" : raw);
        String asset = custom != null ? null : WallpaperView.assetFor(c, which, cfg.wallpaperBg);
        if (custom == null && asset == null) return null;
        String key = custom != null ? custom.getPath() + "@" + custom.lastModified() : "asset:" + asset;
        Object[] hit = cache;
        if (hit != null && key.equals(hit[0])) return (Wallpaper) hit[1];
        Wallpaper w = null;
        try {
            if (custom != null) {
                if (custom.length() <= WALLPAPER_MAX) {
                    byte[] b = read(new FileInputStream(custom));
                    w = new Wallpaper(b, sniff(b, "image/jpeg"));
                }
            } else {
                byte[] b = WallpaperView.assetBytes(c, asset);
                if (b != null && b.length <= WALLPAPER_MAX) w = new Wallpaper(b, sniff(b, "image/webp"));
            }
        } catch (IOException | RuntimeException e) {
            w = null;
        }
        cache = new Object[]{key, w};
        return w;
    }

    /** The image type from its first bytes (JPEG, PNG, WebP), else {@code def}. */
    static String sniff(byte[] b, String def) {
        if (b == null || b.length < 12) return def;
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "image/jpeg";
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return def;
    }

    static String hex(int argb) {
        return String.format(java.util.Locale.ROOT, "#%06x", argb & 0xFFFFFF);
    }

    static String sha256(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder s = new StringBuilder(64);
            for (byte x : d) s.append(String.format(java.util.Locale.ROOT, "%02x", x & 0xFF));
            return s.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] read(InputStream in) throws IOException {
        try (InputStream i = in) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = i.read(buf)) > 0) {
                o.write(buf, 0, n);
                if (o.size() > WALLPAPER_MAX) throw new IOException("too large");
            }
            return o.toByteArray();
        }
    }
}
