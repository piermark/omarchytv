package com.omarchytv.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Weather pane data from Open-Meteo (no key, https://open-meteo.com). Only used when the "weather" pane
 * is enabled and omarchytv.json has weather.lat/lon. Fetched on a background thread at most every
 * 30 minutes, only while the home screen is resumed; the last answer is cached in the files dir.
 */
final class Weather {

    static final long MAX_AGE_MS = 30 * 60_000L;
    private static volatile boolean fetching;

    private Weather() {
    }

    static File cacheFile(Context c) {
        return new File(c.getFilesDir(), "weather.json");
    }

    /** Cached Open-Meteo answer, or null. */
    static JSONObject cached(Context c) {
        File f = cacheFile(c);
        if (!f.isFile() || f.length() > 64 * 1024) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    static void refreshIfStale(Context ctx, double lat, double lon, Runnable onDone) {
        if (Double.isNaN(lat) || Double.isNaN(lon) || fetching) return;
        final Context c = ctx.getApplicationContext();
        File f = cacheFile(c);
        if (f.isFile() && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS) {
            // v2.10: a cache for another place (e.g. the IP location, "Milan") is stale for a manual city.
            JSONObject old = cached(c);
            boolean samePlace = old == null || !old.has("latitude")
                    || Math.abs(old.optDouble("latitude") - lat) < 0.3 && Math.abs(old.optDouble("longitude") - lon) < 0.3;
            if (samePlace) return;
        }
        fetching = true;
        new Thread(() -> {
            HttpURLConnection con = null;
            try {
                URL url = new URL(String.format(Locale.ROOT,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                                + "&current=temperature_2m,weather_code,wind_speed_10m"
                                + "&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=6&timezone=auto", lat, lon));
                con = (HttpURLConnection) url.openConnection();
                con.setConnectTimeout(8000);
                con.setReadTimeout(8000);
                if (con.getResponseCode() == 200) {
                    byte[] body;
                    try (InputStream in = con.getInputStream()) {
                        body = readAll(in);
                    }
                    new JSONObject(new String(body, StandardCharsets.UTF_8));   // validate
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(body);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (con != null) con.disconnect();
                fetching = false;
                new Handler(Looper.getMainLooper()).post(onDone);
            }
        }, "omarchytv-weather").start();
    }

    // ---- v2.9: location (auto from the TV's public IP, or a city picked in settings) ----------------------

    /** {lat, lon, place} from omarchytv.json weather (manual), else the cached IP geolocation, else null. */
    static double[] where(Context c, LauncherConfig cfg) {
        if (!Double.isNaN(cfg.weatherLat) && !Double.isNaN(cfg.weatherLon)) return new double[]{cfg.weatherLat, cfg.weatherLon};
        JSONObject g = readJson(geoFile(c));
        if (g == null || !g.has("lat")) return null;
        return new double[]{g.optDouble("lat"), g.optDouble("lon")};
    }

    /** City name to show: the manual place, else the IP geolocation city. */
    static String place(Context c, LauncherConfig cfg) {
        if (!Double.isNaN(cfg.weatherLat)) return cfg.weatherPlace;
        JSONObject g = readJson(geoFile(c));
        return g != null ? g.optString("city", "") : "";
    }

    static File geoFile(Context c) {
        return new File(c.getFilesDir(), "geo.json");
    }

    private static volatile boolean locating;

    /**
     * Home refresh: no manual city → IP geolocation (https://ipapi.co, fallback https://ipwho.is; no key; cached
     * 24 h), then Open-Meteo every 30 min. Background threads only; onDone runs on the main thread.
     */
    static void refreshAuto(Context ctx, LauncherConfig cfg, Runnable onDone) {
        final Context c = ctx.getApplicationContext();
        double[] w = where(c, cfg);
        File g = geoFile(c);
        boolean manual = !Double.isNaN(cfg.weatherLat);
        if (!manual && !locating && (w == null || System.currentTimeMillis() - g.lastModified() > 24 * 3600_000L)) {
            locating = true;
            new Thread(() -> {
                try {
                    JSONObject out = null;
                    JSONObject a = getJson("https://ipapi.co/json/");
                    if (a != null && a.has("latitude")) {
                        out = new JSONObject().put("lat", a.optDouble("latitude")).put("lon", a.optDouble("longitude")).put("city", a.optString("city", ""));
                    } else {
                        JSONObject b = getJson("https://ipwho.is/");
                        if (b != null && b.optBoolean("success", false)) {
                            out = new JSONObject().put("lat", b.optDouble("latitude")).put("lon", b.optDouble("longitude")).put("city", b.optString("city", ""));
                        }
                    }
                    // v2.10: a city picked by hand while this lookup ran always wins; never write the IP place over it.
                    if (out != null && Double.isNaN(LauncherConfig.get(c).weatherLat)) {
                        try (FileOutputStream o = new FileOutputStream(g)) {
                            o.write(out.toString().getBytes(StandardCharsets.UTF_8));
                        }
                        cacheFile(c).delete();   // new place: fetch fresh weather
                    }
                } catch (Exception ignored) {
                } finally {
                    locating = false;
                    new Handler(Looper.getMainLooper()).post(() -> {
                        double[] w2 = where(c, LauncherConfig.get(c));
                        if (w2 != null) refreshIfStale(c, w2[0], w2[1], onDone);
                        onDone.run();
                    });
                }
            }, "omarchytv-geo").start();
            return;
        }
        if (w != null) refreshIfStale(c, w[0], w[1], onDone);
    }

    /** Open-Meteo geocoding (no key): up to 6 {name, admin1, country, lat, lon}. Call off the main thread. */
    static java.util.List<JSONObject> searchCity(String name, String lang) {
        java.util.List<JSONObject> out = new java.util.ArrayList<>();
        try {
            JSONObject r = getJson("https://geocoding-api.open-meteo.com/v1/search?count=6&format=json&language=" + lang
                    + "&name=" + java.net.URLEncoder.encode(name.trim(), "UTF-8"));
            org.json.JSONArray a = r != null ? r.optJSONArray("results") : null;
            for (int i = 0; a != null && i < a.length(); i++) out.add(a.getJSONObject(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    private static JSONObject readJson(File f) {
        if (!f.isFile() || f.length() > 64 * 1024) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject getJson(String u) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(u).openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(8000);
            con.setRequestProperty("User-Agent", "OmarchyTV");
            if (con.getResponseCode() != 200) return null;
            try (InputStream in = con.getInputStream()) {
                return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** A °C value from Open-Meteo, rounded, in the unit picked in settings. */
    static long temp(double celsius, boolean fahrenheit) {
        return Math.round(fahrenheit ? celsius * 9.0 / 5.0 + 32.0 : celsius);
    }

    /** WMO weather code to a string resource. */
    static int describe(int code) {
        if (code == 0) return R.string.wx_clear;
        if (code <= 2) return R.string.wx_partly;
        if (code == 3) return R.string.wx_cloudy;
        if (code == 45 || code == 48) return R.string.wx_fog;
        if (code >= 51 && code <= 57) return R.string.wx_drizzle;
        if (code >= 61 && code <= 67) return R.string.wx_rain;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.string.wx_snow;
        if (code >= 80 && code <= 82) return R.string.wx_showers;
        if (code >= 95) return R.string.wx_storm;
        return R.string.wx_cloudy;
    }

    /** Line icon for a WMO weather code (same buckets as {@link #describe}). */
    static int icon(int code) {
        if (code == 0) return R.drawable.ln_wx_sun;
        if (code <= 2) return R.drawable.ln_wx_partly;
        if (code == 45 || code == 48) return R.drawable.ln_wx_fog;
        if (code >= 51 && code <= 67 || code >= 80 && code <= 82) return R.drawable.ln_wx_rain;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ln_wx_snow;
        if (code >= 95) return R.drawable.ln_wx_storm;
        return R.drawable.ln_wx_cloud;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > 64 * 1024) break;
        }
        return bo.toByteArray();
    }
}
