package com.omarchytv.home;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * v2.14 Living screensaver on Android TV: the feed from omarchy-link ("living": GitHub, the PC's notifications, X),
 * kept in memory only, plus the TV's own weather, handed to the screensaver page (assets/saver/living.js draws it).
 */
final class Living {
    private static volatile JSONObject feed = new JSONObject();

    private Living() {
    }

    /** From OmarchyLink: the PC's latest feed (already clipped by LinkServer.living()). */
    static void set(JSONObject d) {
        feed = d != null ? d : new JSONObject();
    }

    /** What the TV asks the PC for ("living_prefs"); all off while the Living screensaver is off. */
    static JSONObject prefs(LauncherConfig c) {
        try {
            return new JSONObject().put("notes", c.living && c.livingNotes).put("github", c.living && c.livingGithub)
                    .put("x", c.living ? c.livingX : "off");
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Weather.cached (Open-Meteo) as {t, unit, code, desc, min, max, place}, or null. */
    static JSONObject weather(Context c, LauncherConfig cfg) {
        try {
            JSONObject w = Weather.cached(c);
            JSONObject cur = w != null ? w.optJSONObject("current") : null;
            if (cur == null || !cur.has("temperature_2m")) return null;
            int code = cur.optInt("weather_code", 0);
            JSONObject o = new JSONObject().put("t", Weather.temp(cur.optDouble("temperature_2m"), cfg.tempF))
                    .put("unit", cfg.tempF ? "F" : "").put("code", code).put("desc", c.getString(Weather.describe(code)))
                    .put("place", Weather.place(c, cfg));
            JSONObject daily = w.optJSONObject("daily");
            JSONArray mn = daily != null ? daily.optJSONArray("temperature_2m_min") : null;
            JSONArray mx = daily != null ? daily.optJSONArray("temperature_2m_max") : null;
            if (mn != null && mx != null && mn.length() > 0 && mx.length() > 0) {
                o.put("min", Weather.temp(mn.optDouble(0), cfg.tempF)).put("max", Weather.temp(mx.optDouble(0), cfg.tempF));
            }
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** OmarchySaver.living() in the screensaver page: {enabled, data, labels, color, accent}. */
    static String forSaver(Context c) {
        LauncherConfig cfg = LauncherConfig.get(c);
        try {
            JSONObject out = new JSONObject().put("enabled", cfg.living);
            if (!cfg.living) return out.toString();
            JSONObject f = feed, data = new JSONObject();
            boolean pc = OmarchyLink.hasPc();
            if (pc && cfg.livingGithub) data.put("github", f.optJSONArray("github"));
            if (pc && cfg.livingNotes) data.put("notes", f.optJSONArray("notes"));
            if (pc && !"off".equals(cfg.livingX)) {
                data.put("x", f.optJSONArray("x")).put("xMode", f.optString("xMode", cfg.livingX));
                if (f.has("xError")) data.put("xError", f.optString("xError"));
            }
            JSONObject w = weather(c, cfg);
            if (w != null) data.put("weather", w);
            Theme t = Theme.cur();
            JSONObject labels = new JSONObject().put("weather", c.getString(R.string.living_weather))
                    .put("notes", c.getString(R.string.living_notes_title)).put("x", c.getString(R.string.living_x_title))
                    .put("github", "github").put("x_not_set_up", c.getString(R.string.living_x_not_set_up))
                    .put("x_budget", c.getString(R.string.living_x_budget)).put("x_error", c.getString(R.string.living_x_error))
                    .put("x_user", c.getString(R.string.living_x_error));
            return out.put("data", data).put("labels", labels)
                    .put("color", String.format(Locale.ROOT, "#%06x", t.fg & 0xFFFFFF))
                    .put("accent", String.format(Locale.ROOT, "#%06x", t.accent & 0xFFFFFF)).toString();
        } catch (Exception e) {
            return "{\"enabled\":false}";
        }
    }
}
