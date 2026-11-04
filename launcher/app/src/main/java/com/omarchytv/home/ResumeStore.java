package com.omarchytv.home;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Iterator;

/**
 * Where you stopped each video (Cinemarchy and the cast player): { url: {pos, dur, at, title} }, the newest 200,
 * on this TV only. Barely started (< 20 s) or finished (> 95 %) videos are forgotten, so they start fresh.
 * Cinemarchy's progress bars and "resume at" come from here, never from made-up numbers.
 */
final class ResumeStore {
    private static final String PREFS = "cine_resume", KEY = "v1";
    private static final int MAX = 200;

    private ResumeStore() {
    }

    private static JSONObject all(Context c) {
        try {
            return new JSONObject(prefs(c).getString(KEY, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static synchronized void put(Context c, String url, double posS, double durS, String title) {
        if (url == null || url.isEmpty() || durS <= 0) return;
        JSONObject a = all(c);
        try {
            if (posS < 20 || posS > durS * 0.95) a.remove(url);
            else a.put(url, new JSONObject().put("pos", Math.floor(posS)).put("dur", Math.floor(durS))
                    .put("at", System.currentTimeMillis()).put("title", title == null ? "" : title));
            while (a.length() > MAX) {   // drop the oldest
                String oldest = null;
                long t = Long.MAX_VALUE;
                for (Iterator<String> it = a.keys(); it.hasNext(); ) {
                    String k = it.next();
                    long at = a.optJSONObject(k) != null ? a.optJSONObject(k).optLong("at") : 0;
                    if (at < t) { t = at; oldest = k; }
                }
                if (oldest == null) break;
                a.remove(oldest);
            }
        } catch (Exception ignored) {
        }
        prefs(c).edit().putString(KEY, a.toString()).apply();
    }

    /** {pos, dur} in seconds, or null. */
    static double[] get(Context c, String url) {
        if (url == null) return null;
        JSONObject o = all(c).optJSONObject(url);
        if (o == null || o.optDouble("dur", 0) <= 0) return null;
        return new double[]{o.optDouble("pos", 0), o.optDouble("dur", 0)};
    }

    /** "███░░ 42%" for a list row, or "" when there is nothing to resume. */
    static String bar(Context c, String url) {
        double[] r = get(c, url);
        if (r == null) return "";
        int pct = (int) Math.round(r[0] * 100 / r[1]), full = Math.max(0, Math.min(5, (int) Math.round(r[0] * 5 / r[1])));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 5; i++) b.append(i < full ? '█' : '░');
        return b.append(' ').append(pct).append('%').toString();
    }
}
