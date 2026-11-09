package com.omarchytv.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.omarchytv.home.link.LinkServer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Panes the TV fetches itself, for data that has no PC on the LAN to push it (e.g. market prices from a
 * server): omarchytv.json "remotePanes": {"url": "https://…/panes.json", "refreshSec": 300}. The body is the
 * one of PUT /v1/widgets, panes only (a URL cannot add shortcuts or actions), checked with the same limits
 * ({@link LinkServer#cleanPanes}). HTTPS, or plain http:// only to a literal private / link-local LAN address
 * (the web tiles' rule, {@link #urlAllowed}; e.g. Node-RED at home), no redirects, at most 64 KiB, fetched on a
 * background thread at most every refreshSec, only while the home screen is resumed; the last good answer is cached in the files dir, so the
 * panes survive a restart and go stale on their own ttl (dim after ttl, hidden after 2 x ttl).
 */
final class RemotePanes {

    static final int MAX_BYTES = 64 * 1024;
    /** v2.15 (#115): a pane's optional picture, PNG or JPEG, at most this big. */
    static final int MAX_IMAGE = 512 * 1024;
    private static volatile boolean fetching;
    private static volatile long lastAttemptMs;
    private static volatile boolean forced;

    private RemotePanes() {
    }

    /**
     * omarchytv.json "remotePanes" url: https://, or http:// whose host is a literal private / link-local address
     * ({@link LanHttpDataSource#lanHost}, no DNS names, never the internet). At most 512 chars.
     */
    static boolean urlAllowed(String url) {
        if (url == null || url.length() > 512) return false;
        if (url.startsWith("https://")) return true;
        if (!url.startsWith("http://")) return false;
        try {
            return httpLan(new URL(url));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean httpLan(URL u) {
        return "http".equals(u.getProtocol()) && LanHttpDataSource.lanHost(u.getHost());
    }

    static File cacheFile(Context c) {
        return new File(c.getFilesDir(), "remote-panes.json");
    }

    /** Cached {url, fetchedAt (ms), panes} for this url, or null. */
    static JSONObject cached(Context c, String url) {
        if (url == null || url.isEmpty()) return null;
        File f = cacheFile(c);
        if (!f.isFile() || f.length() > 2L * MAX_BYTES) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            JSONObject o = new JSONObject(new String(readAll(in, 2 * MAX_BYTES), StandardCharsets.UTF_8));
            return url.equals(o.optString("url")) ? o : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Unix seconds the pane's data is from: its own "at", else when it was fetched. */
    static long paneAt(JSONObject cache, JSONObject pane) {
        long at = pane.optLong("at", 0L);
        return at > 0 ? at : cache.optLong("fetchedAt", 0L) / 1000L;
    }

    /** The URL was saved from the menu: fetch on the next resume, even when the cache is still fresh. */
    static void forget() {
        forced = true;
    }

    static void refreshIfStale(Context ctx, String url, int refreshSec, Runnable onDone) {
        if (url == null || url.isEmpty() || fetching) return;
        long now = System.currentTimeMillis();
        boolean force = forced;
        forced = false;
        if (!force && now - lastAttemptMs < refreshSec * 1000L) return;
        final Context c = ctx.getApplicationContext();
        JSONObject old = cached(c, url);
        if (!force && lastAttemptMs == 0 && old != null && now - old.optLong("fetchedAt", 0L) < refreshSec * 1000L) {
            lastAttemptMs = old.optLong("fetchedAt", 0L);   // fresh from before a restart: don't fetch again yet
            return;
        }
        lastAttemptMs = now;
        fetching = true;
        new Thread(() -> {
            HttpURLConnection con = null;
            try {
                URL u = new URL(url);
                if (!"https".equals(u.getProtocol()) && !httpLan(u)) return;   // checked again at fetch time
                con = (HttpURLConnection) u.openConnection();
                con.setInstanceFollowRedirects(false);   // only the configured URL, never where it points to
                con.setConnectTimeout(8000);
                con.setReadTimeout(8000);
                con.setRequestProperty("Accept", "application/json");
                if (con.getResponseCode() != 200) return;
                byte[] body;
                try (InputStream in = con.getInputStream()) {
                    body = readAll(in, MAX_BYTES);
                }
                JSONArray in = new JSONObject(new String(body, StandardCharsets.UTF_8)).optJSONArray("panes");
                String[] why = new String[2];
                JSONArray panes = LinkServer.cleanPanes(in != null ? in : new JSONArray(), System.currentTimeMillis() / 1000L, why);
                if (panes == null) return;
                images(c, in, panes);
                JSONObject out = new JSONObject().put("url", url).put("fetchedAt", System.currentTimeMillis()).put("panes", panes);
                File f = cacheFile(c), tmp = new File(f.getPath() + ".tmp");
                try (FileOutputStream o = new FileOutputStream(tmp)) {
                    o.write(out.toString().getBytes(StandardCharsets.UTF_8));
                }
                if (!tmp.renameTo(f)) tmp.delete();
            } catch (Exception ignored) {
            } finally {
                if (con != null) con.disconnect();
                fetching = false;
                new Handler(Looper.getMainLooper()).post(onDone);
            }
        }, "omarchytv-remote-panes").start();
    }

    /**
     * v2.15 (#115, asked by @tolixx): a pane may carry "image": a PNG or JPEG URL (the same rule as the panes URL:
     * https, or http only to a LAN address; no redirects; at most 512 KiB). It is fetched here, on the same thread, and
     * kept in the files dir next to the panes, so it survives a restart and goes stale with the pane's ttl. A pane
     * with a picture shows it under its title instead of its lines. A picture that can't be fetched keeps the last one.
     */
    private static void images(Context c, JSONArray in, JSONArray panes) {
        for (int i = 0; in != null && i < panes.length(); i++) {
            JSONObject p = panes.optJSONObject(i), src = in.optJSONObject(i);
            String url = src != null ? src.optString("image", "") : "";
            if (p == null || url.isEmpty() || !urlAllowed(url)) continue;
            File f = imageFile(c, p.optString("id"));
            try {
                byte[] img = fetchImage(url);
                if (img != null) {
                    File tmp = new File(f.getPath() + ".tmp");
                    try (FileOutputStream o = new FileOutputStream(tmp)) {
                        o.write(img);
                    }
                    if (!tmp.renameTo(f)) tmp.delete();
                }
            } catch (Exception ignored) {
            }
            try {
                if (f.isFile()) p.put("image", f.getName());
            } catch (Exception ignored) {
            }
        }
    }

    static File imageFile(Context c, String paneId) {
        return new File(c.getFilesDir(), "remote-pane-" + paneId.replaceAll("[^A-Za-z0-9_-]", "_") + ".img");
    }

    private static byte[] fetchImage(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        try {
            URL u = con.getURL();
            if (!"https".equals(u.getProtocol()) && !httpLan(u)) return null;
            con.setInstanceFollowRedirects(false);
            con.setConnectTimeout(8000);
            con.setReadTimeout(8000);
            con.setRequestProperty("Accept", "image/png, image/jpeg");
            if (con.getResponseCode() != 200) return null;
            byte[] b;
            try (InputStream in = con.getInputStream()) {
                b = readAll(in, MAX_IMAGE);
            }
            return imageOk(b) ? b : null;
        } finally {
            con.disconnect();
        }
    }

    /** PNG or JPEG by their first bytes (whatever the server says the type is). */
    static boolean imageOk(byte[] b) {
        if (b == null || b.length < 8) return false;
        boolean png = (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
        boolean jpg = (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff;
        return png || jpg;
    }

    /** Reads at most {@code max} bytes; more is an error (a pane file is small). */
    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (b.size() + n > max) throw new IOException("too large");
            b.write(buf, 0, n);
        }
        return b.toByteArray();
    }
}
