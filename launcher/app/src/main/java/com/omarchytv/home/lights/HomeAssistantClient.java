package com.omarchytv.home.lights;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * v2.22 (#160) the Home Assistant client shared by the Lights hub settings and the Home widgets: REST for states and
 * services (this class) and {@link HaSocket} for live states. One configuration, the URL and long-lived token of
 * Settings › Ambilight & Lights (kept in private prefs). The token is write-only: nothing here ever returns or logs
 * it, and {@link #status()} names only the host.
 */
public final class HomeAssistantClient {
    public final String url;
    final String token;
    static final int TIMEOUT_MS = 3000;
    /** Domains whose entities OK toggles; everything else is read-only (scenes and scripts run, automations trigger). */
    static final java.util.Set<String> TOGGLES = new java.util.HashSet<>(java.util.Arrays.asList("light", "switch", "fan", "input_boolean"));

    public HomeAssistantClient(String url, String token) {
        this.url = url == null ? "" : url.trim().replaceAll("/+$", "");
        this.token = token == null ? "" : token.trim();
    }

    public static HomeAssistantClient fromStore(Context c) {
        return new HomeAssistantClient(LightsStore.get(c, "ha_url"), LightsStore.get(c, "ha_token"));
    }

    public boolean configured() {
        return (url.startsWith("http://") || url.startsWith("https://")) && !token.isEmpty();
    }

    /** {configured, reachable, version, host}: no token, no path. */
    public JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            o.put("configured", configured()).put("host", host(url));
            if (!configured()) return o.put("reachable", false);
            try {
                JSONObject cfg = new JSONObject(Http.request("GET", url + "/api/config", null, token, TIMEOUT_MS));
                o.put("reachable", true).put("version", cfg.optString("version", ""));
            } catch (Exception e) {
                o.put("reachable", false).put("error", errorOf(e));
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    static String host(String url) {
        try {
            return url.isEmpty() ? "" : new URL(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    /** A short error for the screen: unauthorized, not_found, timeout, unreachable. Never the URL or the token. */
    public static String errorCode(Exception e) {
        return errorOf(e);
    }

    static String errorOf(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("401") || m.contains("403")) return "unauthorized";
        if (m.contains("404")) return "not_found";
        if (e instanceof java.net.SocketTimeoutException) return "timeout";
        return "unreachable";
    }

    /** GET /api/states filtered: [{entity_id, name, state, unit?}], sorted by name, at most {@code max}. */
    public JSONArray states(String domain, String query, int max) throws Exception {
        return filterStates(Http.request("GET", url + "/api/states", null, token, 6000), domain, query, max);
    }

    /** One entity: {entity_id, name, state, unit?}. */
    public JSONObject state(String entity) throws Exception {
        if (!ENTITY.matcher(entity).matches()) throw new Exception("bad entity");
        return brief(new JSONObject(Http.request("GET", url + "/api/states/" + entity, null, token, TIMEOUT_MS)));
    }

    static final java.util.regex.Pattern ENTITY = java.util.regex.Pattern.compile("^[a-z_]{1,32}\\.[a-z0-9_]{1,64}$");

    static JSONArray filterStates(String json, String domain, String query, int max) throws Exception {
        JSONArray all = new JSONArray(json.trim());
        List<JSONObject> out = new ArrayList<>();
        String d = domain == null ? "" : domain.trim(), q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("entity_id");
            if (!d.isEmpty() && !id.startsWith(d + ".")) continue;
            JSONObject b = brief(o);
            if (!q.isEmpty() && !id.contains(q) && !b.optString("name").toLowerCase(java.util.Locale.ROOT).contains(q)) continue;
            out.add(b);
        }
        out.sort((a, b) -> a.optString("name").compareToIgnoreCase(b.optString("name")));
        JSONArray r = new JSONArray();
        for (int i = 0; i < out.size() && i < max; i++) r.put(out.get(i));
        return r;
    }

    static JSONObject brief(JSONObject o) throws Exception {
        JSONObject a = o.optJSONObject("attributes");
        String id = o.optString("entity_id");
        JSONObject b = new JSONObject().put("entity_id", id)
                .put("name", a != null && !a.optString("friendly_name").isEmpty() ? a.optString("friendly_name") : id)
                .put("state", o.optString("state"));
        if (a != null && !a.optString("unit_of_measurement").isEmpty()) b.put("unit", a.optString("unit_of_measurement"));
        return b;
    }

    /** {domain, service} that "runs" an entity: scene / script turn_on, automation trigger, toggle for switches. */
    public static String[] serviceFor(String entity) {
        if (entity == null || !ENTITY.matcher(entity).matches()) return null;
        String domain = entity.substring(0, entity.indexOf('.'));
        switch (domain) {
            case "scene":
            case "script":
                return new String[]{domain, "turn_on"};
            case "automation":
                return new String[]{domain, "trigger"};
            default:
                return TOGGLES.contains(domain) ? new String[]{domain, "toggle"} : null;
        }
    }

    /** Runs a scene / script / automation or toggles a switch-like entity; null when it worked, else a short error. */
    public String run(String entity) {
        String[] s = serviceFor(entity);
        if (s == null) return "read_only";
        if (!configured()) return "not_configured";
        try {
            Http.request("POST", url + "/api/services/" + s[0] + "/" + s[1], new JSONObject().put("entity_id", entity).toString(), token, TIMEOUT_MS);
            return null;
        } catch (Exception e) {
            return errorOf(e);
        }
    }

    /** The websocket URL: ws(s)://host[:port]/api/websocket. */
    String wsUrl() {
        return (url.startsWith("https://") ? "wss://" : "ws://") + url.replaceFirst("^https?://", "") + "/api/websocket";
    }
}
