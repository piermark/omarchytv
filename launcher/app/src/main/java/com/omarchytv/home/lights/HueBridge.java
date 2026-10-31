package com.omarchytv.home.lights;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Philips Hue Bridge over the public, documented local REST API (v1, http://&lt;bridge&gt;/api).
 * Original code. Discovery: N-UPnP (https://discovery.meethue.com) or a manual IP; pairing is the
 * link-button flow (POST /api {"devicetype":"omarchytv#tv"}). The username is a secret: it is kept in
 * private prefs by {@link LightsStore} and never logged or shown.
 */
public final class HueBridge implements LightsHub {
    public static final String DEVICE_TYPE = "omarchytv#tv";
    static final String DISCOVERY_URL = "https://discovery.meethue.com/";

    final String ip, username, groupId;

    public HueBridge(String ip, String username, String groupId) {
        this.ip = ip == null ? "" : ip.trim();
        this.username = username == null ? "" : username.trim();
        this.groupId = groupId == null ? "" : groupId.trim();
    }

    @Override
    public String id() {
        return "hue";
    }

    @Override
    public boolean configured() {
        return !ip.isEmpty() && !username.isEmpty() && !groupId.isEmpty();
    }

    // ---- pure parsing (unit-tested) -----------------------------------------------------------

    /** Result of POST /api. Exactly one of {@code username} / {@code error} is non-null. */
    public static final class PairResult {
        public final String username, error;
        public final boolean buttonNotPressed;

        PairResult(String username, String error, boolean buttonNotPressed) {
            this.username = username;
            this.error = error;
            this.buttonNotPressed = buttonNotPressed;
        }
    }

    /** Parses {@code [{"success":{"username":"..."}}]} or {@code [{"error":{"type":101,...}}]}. */
    public static PairResult parsePair(String json) {
        try {
            JSONArray a = new JSONArray(json.trim());
            JSONObject o = a.optJSONObject(0);
            if (o == null) return new PairResult(null, "empty response", false);
            JSONObject s = o.optJSONObject("success");
            if (s != null) {
                String u = s.optString("username", "").trim();
                if (!u.isEmpty()) return new PairResult(u, null, false);
            }
            JSONObject e = o.optJSONObject("error");
            if (e != null) {
                int type = e.optInt("type", 0);
                return new PairResult(null, type == 101 ? "press the link button on the bridge" : e.optString("description", "error " + type), type == 101);
            }
        } catch (Exception ignored) {
        }
        return new PairResult(null, "unexpected response", false);
    }

    /** Parses the N-UPnP list {@code [{"id":"..","internalipaddress":"192.168.1.2"}]} into IPs. */
    public static List<String> parseDiscovery(String json) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json.trim());
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                String ip = o == null ? "" : o.optString("internalipaddress", "").trim();
                if (ip.matches("[0-9a-fA-F:.]+") && !out.contains(ip)) out.add(ip);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** A selectable group: Entertainment area, Room or Zone. */
    public static final class Group {
        public final String id, name, type;
        public final int lights;

        Group(String id, String name, String type, int lights) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.lights = lights;
        }

        public String label() {
            String t = "Entertainment".equals(type) ? "entertainment area" : type.toLowerCase(java.util.Locale.ROOT);
            return name + " · " + t + " · " + lights + " lights";
        }
    }

    /** Parses GET /api/&lt;user&gt;/groups; Entertainment areas first, then rooms/zones. */
    public static List<Group> parseGroups(String json) {
        List<Group> ent = new ArrayList<>(), rest = new ArrayList<>();
        try {
            JSONObject o = new JSONObject(json.trim());
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject g = o.optJSONObject(id);
                if (g == null) continue;
                String type = g.optString("type", "");
                if (!"Entertainment".equals(type) && !"Room".equals(type) && !"Zone".equals(type)) continue;
                JSONArray l = g.optJSONArray("lights");
                Group gr = new Group(id, g.optString("name", "Group " + id), type, l == null ? 0 : l.length());
                ("Entertainment".equals(type) ? ent : rest).add(gr);
            }
        } catch (Exception ignored) {
        }
        ent.addAll(rest);
        return ent;
    }

    /** Body for PUT /groups/&lt;id&gt;/action. */
    public static JSONObject actionBody(int rgb, int brightnessPct, boolean dim) throws Exception {
        double[] xy = HueColor.rgbToXy(rgb);
        int bri = HueColor.bri(rgb, dim ? Math.min(brightnessPct, 12) : brightnessPct);
        return new JSONObject()
                .put("on", true)
                .put("xy", new JSONArray().put(xy[0]).put(xy[1]))
                .put("bri", bri)
                .put("transitiontime", dim ? 20 : 8);
    }

    /** Snapshot from GET /groups/&lt;id&gt; and GET /lights: {"&lt;light&gt;": {on, bri, xy|ct|hue+sat}}. */
    public static JSONObject snapshotOf(String groupJson, String lightsJson) throws Exception {
        JSONObject g = new JSONObject(groupJson.trim());
        JSONObject all = new JSONObject(lightsJson.trim());
        JSONArray ids = g.optJSONArray("lights");
        JSONObject out = new JSONObject();
        for (int i = 0; ids != null && i < ids.length(); i++) {
            String id = ids.optString(i);
            JSONObject l = all.optJSONObject(id);
            JSONObject st = l == null ? null : l.optJSONObject("state");
            if (st == null) continue;
            JSONObject s = new JSONObject().put("on", st.optBoolean("on", false));
            if (st.has("bri")) s.put("bri", st.optInt("bri"));
            String mode = st.optString("colormode", "");
            if ("xy".equals(mode) && st.optJSONArray("xy") != null) s.put("xy", st.optJSONArray("xy"));
            else if ("ct".equals(mode) && st.has("ct")) s.put("ct", st.optInt("ct"));
            else if ("hs".equals(mode) && st.has("hue")) s.put("hue", st.optInt("hue")).put("sat", st.optInt("sat"));
            out.put(id, s);
        }
        return out;
    }

    /** PUT /lights/&lt;id&gt;/state body that restores one snapshot entry (colour first, then on/off). */
    public static JSONObject restoreBody(JSONObject s) throws Exception {
        JSONObject b = new JSONObject();
        boolean on = s.optBoolean("on", false);
        if (on) {
            for (String k : new String[]{"bri", "xy", "ct", "hue", "sat"}) if (s.has(k)) b.put(k, s.get(k));
        }
        return b.put("on", on).put("transitiontime", 8);
    }

    @Override
    public String snapshot() throws Exception {
        if (!configured()) return null;
        String g = Http.request("GET", base() + "/groups/" + groupId, null, null, 2500);
        String l = Http.request("GET", base() + "/lights", null, null, 2500);
        JSONObject s = snapshotOf(g, l);
        return s.length() == 0 ? null : s.toString();
    }

    @Override
    public void restore(String snapshot) throws Exception {
        if (!configured() || snapshot == null) return;
        JSONObject s = new JSONObject(snapshot);
        for (Iterator<String> it = s.keys(); it.hasNext(); ) {
            String id = it.next();
            if (!id.matches("[0-9A-Za-z_-]+")) continue;
            JSONObject e = s.optJSONObject(id);
            if (e != null) Http.request("PUT", base() + "/lights/" + id + "/state", restoreBody(e).toString(), null, 2500);
        }
    }

    @Override
    public void off() throws Exception {
        if (!configured()) return;
        Http.request("PUT", base() + "/groups/" + groupId + "/action", "{\"on\":false,\"transitiontime\":8}", null, 2500);
    }

    // ---- network ----------------------------------------------------------------------------

    public static List<String> discover() {
        try {
            return parseDiscovery(Http.request("GET", DISCOVERY_URL, null, null, 4000));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static PairResult pair(String ip) {
        try {
            String body = new JSONObject().put("devicetype", DEVICE_TYPE).toString();
            return parsePair(Http.request("POST", "http://" + ip + "/api", body, null, 3000));
        } catch (Exception e) {
            return new PairResult(null, "bridge unreachable", false);
        }
    }

    public List<Group> groups() throws Exception {
        return parseGroups(Http.request("GET", base() + "/groups", null, null, 3000));
    }

    private String base() {
        return "http://" + ip + "/api/" + username;
    }

    @Override
    public void apply(int accent, int accent2, int bg, int brightnessPct, boolean dim) throws Exception {
        if (!configured()) return;
        Http.request("PUT", base() + "/groups/" + groupId + "/action", actionBody(dim ? accent2 : accent, brightnessPct, dim).toString(), null, 2500);
    }

    @Override
    public String test() {
        if (ip.isEmpty()) return "no bridge";
        if (username.isEmpty()) return "not paired";
        try {
            String r = Http.request("GET", base() + "/config", null, null, 2500);
            JSONObject o = r.trim().startsWith("[") ? null : new JSONObject(r);
            if (o == null || !o.has("whitelist") && !o.has("name")) return "error: unauthorized";
            return "ok · " + o.optString("name", "Hue Bridge");
        } catch (Exception e) {
            return "error: unreachable";
        }
    }
}
