package com.omarchytv.home.lights;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Home Assistant via its documented REST API: POST {url}/api/services/light/turn_on with a
 * long-lived access token (Bearer). The token is stored only in private prefs and never logged.
 */
public final class HomeAssistantHub implements LightsHub {
    final String url, token, entities;

    public HomeAssistantHub(String url, String token, String entities) {
        this.url = url == null ? "" : url.trim().replaceAll("/+$", "");
        this.token = token == null ? "" : token.trim();
        this.entities = entities == null ? "" : entities.trim();
    }

    @Override
    public String id() {
        return "homeassistant";
    }

    @Override
    public boolean configured() {
        return url.startsWith("http") && !token.isEmpty() && !entities.isEmpty();
    }

    /** Comma-separated light.* ids -> JSON array (invalid ids dropped). */
    public static JSONArray entityIds(String csv) {
        JSONArray a = new JSONArray();
        for (String s : csv.split(",")) {
            String e = s.trim();
            if (e.matches("light\\.[a-z0-9_]+")) a.put(e);
        }
        return a;
    }

    public static JSONObject turnOnBody(String csv, int rgb, int brightnessPct, boolean dim) throws Exception {
        return new JSONObject()
                .put("entity_id", entityIds(csv))
                .put("rgb_color", new JSONArray().put((rgb >> 16) & 0xFF).put((rgb >> 8) & 0xFF).put(rgb & 0xFF))
                .put("brightness_pct", dim ? Math.min(brightnessPct, 12) : Math.max(1, Math.min(100, brightnessPct)))
                .put("transition", dim ? 2 : 1);
    }

    /** One snapshot entry from GET /api/states/&lt;entity&gt;: on/off, brightness and one colour form. */
    public static JSONObject stateOf(String json) throws Exception {
        JSONObject o = new JSONObject(json.trim());
        JSONObject a = o.optJSONObject("attributes");
        JSONObject s = new JSONObject().put("on", "on".equals(o.optString("state")));
        if (a != null) {
            if (!a.isNull("brightness") && a.has("brightness")) s.put("brightness", a.optInt("brightness"));
            String mode = a.optString("color_mode", "");
            if (("color_temp".equals(mode) || a.isNull("rgb_color")) && a.has("color_temp_kelvin") && !a.isNull("color_temp_kelvin")) {
                s.put("color_temp_kelvin", a.optInt("color_temp_kelvin"));
            } else if (a.optJSONArray("rgb_color") != null) {
                s.put("rgb_color", a.optJSONArray("rgb_color"));
            }
        }
        return s;
    }

    /** {service, body} that puts one light back: turn_on with its brightness / colour, or turn_off. */
    public static String[] restoreCall(String entity, JSONObject s) throws Exception {
        JSONObject b = new JSONObject().put("entity_id", entity);
        if (!s.optBoolean("on", false)) return new String[]{"turn_off", b.toString()};
        for (String k : new String[]{"brightness", "rgb_color", "color_temp_kelvin"}) if (s.has(k)) b.put(k, s.get(k));
        return new String[]{"turn_on", b.put("transition", 1).toString()};
    }

    @Override
    public String snapshot() throws Exception {
        if (!configured()) return null;
        JSONArray ids = entityIds(entities);
        JSONObject out = new JSONObject();
        for (int i = 0; i < ids.length(); i++) {
            String e = ids.optString(i);
            out.put(e, stateOf(Http.request("GET", url + "/api/states/" + e, null, token, 3000)));
        }
        return out.length() == 0 ? null : out.toString();
    }

    @Override
    public void restore(String snapshot) throws Exception {
        if (!configured() || snapshot == null) return;
        JSONObject s = new JSONObject(snapshot);
        for (java.util.Iterator<String> it = s.keys(); it.hasNext(); ) {
            String e = it.next();
            JSONObject st = s.optJSONObject(e);
            if (st == null || !e.matches("light\\.[a-z0-9_]+")) continue;
            String[] c = restoreCall(e, st);
            Http.request("POST", url + "/api/services/light/" + c[0], c[1], token, 3000);
        }
    }

    @Override
    public void off() throws Exception {
        if (!configured()) return;
        Http.request("POST", url + "/api/services/light/turn_off",
                new JSONObject().put("entity_id", entityIds(entities)).put("transition", 1).toString(), token, 3000);
    }

    @Override
    public void apply(int accent, int accent2, int bg, int brightnessPct, boolean dim) throws Exception {
        if (!configured()) return;
        Http.request("POST", url + "/api/services/light/turn_on", turnOnBody(entities, dim ? accent2 : accent, brightnessPct, dim).toString(), token, 3000);
    }

    @Override
    public String test() {
        if (!url.startsWith("http")) return "no url";
        if (token.isEmpty()) return "no token";
        try {
            Http.request("GET", url + "/api/", null, token, 3000);
            return "ok · Home Assistant";
        } catch (Exception e) {
            String m = String.valueOf(e.getMessage());
            return m.contains("401") ? "error: unauthorized" : "error: unreachable";
        }
    }
}
