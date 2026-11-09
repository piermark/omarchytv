package com.omarchytv.home;

import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.webkit.WebResourceResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * v2.15 Pulse on the TV (assets/pulse: Pulse 1.6's own page, plus pulse-tv*.js standing in for the PC's hub).
 * What the page asks the launcher for: where the TV is and its theme (config), the TV's own numbers (tvStats), the
 * PC's Pulse state when one is paired (pcPulse), and the public data services it reads, fetched here (proxy) because
 * some of them (adsb.lol, CelesTrak) don't allow a web page to read them. Only those hosts, only GET, size-capped.
 */
final class PulseSaver {
    private PulseSaver() {
    }

    /** The only services the Pulse page may reach, through the launcher. */
    static final Set<String> HOSTS = new HashSet<>(Arrays.asList("api.open-meteo.com", "api.adsb.lol", "api.adsbdb.com",
            "api.wheretheiss.at", "services.swpc.noaa.gov", "ll.thespacedevs.com", "celestrak.org"));
    private static final int MAX_BYTES = 8 << 20;   // CelesTrak's last-30-days list is a few MB

    static boolean proxied(Uri u) {
        return u != null && "https".equals(u.getScheme()) && u.getHost() != null && HOSTS.contains(u.getHost());
    }

    /** GET the URL here and hand it to the page with a CORS header (the page's origin is the saver's asset host). */
    static WebResourceResponse proxy(Uri u, String origin) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u.toString()).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "OmarchyTV-Pulse");
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (in != null) {
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) {
                    out.write(b, 0, n);
                    if (out.size() > MAX_BYTES) throw new java.io.IOException("too big");
                }
                in.close();
            }
            Map<String, String> h = new HashMap<>();
            h.put("Access-Control-Allow-Origin", origin);
            h.put("Cache-Control", "no-store");
            return new WebResourceResponse("application/json", "utf-8", code, code >= 400 ? "Error" : "OK", h,
                    new ByteArrayInputStream(out.toByteArray()));
        } catch (Exception e) {
            Map<String, String> h = new HashMap<>();
            h.put("Access-Control-Allow-Origin", origin);
            return new WebResourceResponse("application/json", "utf-8", 502, "Bad Gateway", h, new ByteArrayInputStream("{}".getBytes()));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** {lat, lon, city, imperial, theme: {name, colors}, scene, quality}: what pulse-tv.js reads at start. */
    static String config(Context c) {
        try {
            LauncherConfig cfg = LauncherConfig.get(c);
            Theme t = Theme.cur();
            JSONObject colors = Theme.paletteOf(c, t.name);
            if (colors == null) {   // a theme pushed by the PC: its own colours
                colors = new JSONObject().put("accent", hex(t.accent)).put("background", hex(t.bg)).put("foreground", hex(t.fg));
            }
            JSONObject o = new JSONObject().put("city", cfg.weatherPlace).put("imperial", cfg.tempF)
                    .put("theme", new JSONObject().put("name", t.name).put("colors", colors))
                    .put("scene", cfg.pulseScene).put("quality", "auto");
            if (!Double.isNaN(cfg.weatherLat) && !Double.isNaN(cfg.weatherLon)) o.put("lat", cfg.weatherLat).put("lon", cfg.weatherLon);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String hex(int c) {
        return String.format(java.util.Locale.ROOT, "#%06x", c & 0xFFFFFF);
    }

    // ---- the TV's own numbers, for Pulse's system and network panels ----
    private static long lastRx = -1, lastTx = -1, lastAt;
    private static long[] lastCpu;

    static synchronized String tvStats(Context c) {
        try {
            JSONObject o = new JSONObject();
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            if (mi.totalMem > 0) {
                o.put("ramTotal", mi.totalMem).put("ramUsed", mi.totalMem - mi.availMem)
                        .put("ram", Math.round(1000.0 * (mi.totalMem - mi.availMem) / mi.totalMem) / 10.0);
            }
            JSONArray cores = new JSONArray();
            double cpu = cpuLoad(cores);
            if (cpu >= 0) o.put("cpu", Math.round(cpu * 10) / 10.0);
            if (cores.length() > 0) o.put("cores", cores);
            double temp = temperature();
            if (temp > 0) o.put("temp", temp);
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            long tot = fs.getTotalBytes(), free = fs.getAvailableBytes();
            if (tot > 0) o.put("disk", Math.round(1000.0 * (tot - free) / tot) / 10.0).put("diskFree", free);
            long rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes(), now = SystemClock.elapsedRealtime();
            if (rx >= 0 && tx >= 0) {
                if (lastRx >= 0 && now > lastAt) {
                    double s = (now - lastAt) / 1000.0;
                    o.put("rx", Math.max(0, (rx - lastRx) / s)).put("tx", Math.max(0, (tx - lastTx) / s));
                } else {
                    o.put("rx", 0).put("tx", 0);
                }
                lastRx = rx;
                lastTx = tx;
                lastAt = now;
            }
            o.put("uptime", SystemClock.elapsedRealtime() / 1000.0).put("host", Build.MODEL).put("user", "tv").put("ip", ip(c));
            return o.toString();
        } catch (Exception e) {
            return "null";
        }
    }

    /**
     * The whole CPU's load from /proc/stat where the TV still lets an app read it (Android 7 and some TVs); else, from
     * Android 8 on, how fast the cores run against their top speed: not the load, but it rises and falls with it.
     */
    private static double cpuLoad(JSONArray cores) {
        try {
            String l = read(new File("/proc/stat")).split("\n")[0];
            String[] p = l.trim().split("\\s+");
            long[] v = new long[p.length - 1];
            for (int i = 1; i < p.length; i++) v[i - 1] = Long.parseLong(p[i]);
            long idle = v[3] + (v.length > 4 ? v[4] : 0), total = 0;
            for (long x : v) total += x;
            long[] prev = lastCpu;
            lastCpu = new long[]{idle, total};
            if (prev != null && total > prev[1]) return 100.0 * (1 - (double) (idle - prev[0]) / (total - prev[1]));
            return 0;
        } catch (Exception ignored) {
        }
        double sum = 0;
        int n = 0;
        for (int i = 0; i < 16; i++) {
            try {
                long cur = Long.parseLong(read(new File("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq")).trim());
                long max = Long.parseLong(read(new File("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq")).trim());
                if (max <= 0) continue;
                double pc = Math.min(100, 100.0 * cur / max);
                cores.put(Math.round(pc));
                sum += pc;
                n++;
            } catch (Exception e) {
                break;
            }
        }
        return n > 0 ? sum / n : -1;
    }

    private static double temperature() {
        double best = 0;
        for (int i = 0; i < 12; i++) {
            try {
                double v = Double.parseDouble(read(new File("/sys/class/thermal/thermal_zone" + i + "/temp")).trim());
                if (v > 1000) v /= 1000;
                if (v > best && v < 130) best = v;
            } catch (Exception e) {
                if (i > 2) break;
            }
        }
        return Math.round(best * 10) / 10.0;
    }

    static String ip(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            LinkProperties lp = n != null ? cm.getLinkProperties(n) : null;
            if (lp != null) for (LinkAddress a : lp.getLinkAddresses()) {
                if (a.getAddress() instanceof java.net.Inet4Address) return a.getAddress().getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String read(File f) throws java.io.IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[4096];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n) : "";
        }
    }
}
