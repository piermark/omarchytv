package com.omarchytv.home.link;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v2.22 (#163) the settings that the web page, app tokens and MCP (tv_settings_get / tv_settings_set) may read and
 * change: one list, with types, allowed values and defaults, so the page is drawn from {@link #schema} and every
 * caller gets the same validation. Keys are paths in omarchytv.json ("plugins.radio"), except the "ha.*" keys, which
 * live in the TV's private lights store. Secrets ("secret" type) are never returned: only whether they are set.
 * Pure (no Android), so it is tested on the JVM.
 */
final class WebSettings {
    static final String HA_URL = "ha.url", HA_TOKEN = "ha.token";

    static final class Field {
        final String key, section, type, label;
        final List<String> values;
        final double min, max;
        final Object def;

        Field(String key, String section, String type, String label, Object def, double min, double max, String... values) {
            this.key = key;
            this.section = section;
            this.type = type;
            this.label = label;
            this.def = def;
            this.min = min;
            this.max = max;
            this.values = values.length == 0 ? Collections.<String>emptyList() : java.util.Arrays.asList(values);
        }
    }

    static final String[][] SECTIONS = {
            {"appearance", "Appearance"}, {"home", "Home"}, {"screensaver", "Screensaver & sound"}, {"plugins", "Plugins"},
            {"homeassistant", "Home Assistant"}, {"agents", "Agents & sharing"}};

    static final List<Field> FIELDS;

    static {
        List<Field> f = new ArrayList<>();
        f.add(new Field("theme", "appearance", "enum", "Theme", "last-horizon", 0, 0));   // values from the TV (choices)
        f.add(new Field("randomTheme", "appearance", "bool", "A random theme at start", true, 0, 0));
        f.add(new Field("randomScope", "appearance", "enum", "Random themes from", "intros", 0, 0, "intros", "all"));
        f.add(new Field("randomWallpaper", "appearance", "bool", "A random wallpaper", true, 0, 0));
        f.add(new Field("uiSize", "appearance", "enum", "Text size", "medium", 0, 0, "small", "medium", "large"));
        f.add(new Field("language", "appearance", "enum", "Language", "auto", 0, 0));   // auto + the TV's languages
        f.add(new Field("logoAnim", "appearance", "bool", "Animated logo", true, 0, 0));
        f.add(new Field("textTiles", "appearance", "bool", "App names instead of banners", false, 0, 0));
        f.add(new Field("antiBurnIn", "appearance", "bool", "Burn-in protection", true, 0, 0));
        f.add(new Field("widgetNavPosition", "home", "enum", "Page navigator", "bottom", 0, 0, "bottom", "top"));
        f.add(new Field("widgetPageRotateSec", "home", "int", "Rotate widget pages every (s, 0 = off)", 0, 0, 3600));
        f.add(new Field("barWeather", "home", "bool", "Weather in the bar", false, 0, 0));
        f.add(new Field("tempUnit", "home", "enum", "Temperature", "C", 0, 0, "C", "F"));
        f.add(new Field("weather.lat", "home", "number", "Weather latitude", null, -90, 90));
        f.add(new Field("weather.lon", "home", "number", "Weather longitude", null, -180, 180));
        f.add(new Field("weather.place", "home", "string", "Weather place name", "", 0, 40));
        f.add(new Field("saverSound", "screensaver", "bool", "Screensaver sound", true, 0, 0));
        f.add(new Field("pulseNotice", "screensaver", "bool", "Pulse notices", false, 0, 0));
        f.add(new Field("wallpaperLoop", "screensaver", "bool", "Loop the wallpaper video", false, 0, 0));
        f.add(new Field("bootSound", "screensaver", "bool", "Boot sound", true, 0, 0));
        f.add(new Field("arcadeSound", "screensaver", "bool", "Arcade sound", true, 0, 0));
        f.add(new Field("plugins.radio", "plugins", "bool", "Radio (mini player in the bar)", false, 0, 0));
        f.add(new Field("radio.key", "plugins", "enum", "Radio play / pause key", "off", 0, 0, "off", "red", "green", "yellow", "blue", "play_pause"));
        f.add(new Field("radio.allowHttp", "plugins", "bool", "Radio: allow http:// streams from the internet", false, 0, 0));
        f.add(new Field("plugins.spacexTv", "plugins", "bool", "SpaceX TV", false, 0, 0));
        f.add(new Field("plugins.grokbot", "plugins", "bool", "Grok Bot / Agent Desktop", true, 0, 0));
        f.add(new Field("plugins.omastorm", "plugins", "bool", "Omastorm (weather radar)", false, 0, 0));
        f.add(new Field(HA_URL, "homeassistant", "url", "Home Assistant URL", "", 0, 200));
        f.add(new Field(HA_TOKEN, "homeassistant", "secret", "Long-lived access token", "", 0, 400));
        f.add(new Field("agentAlert", "agents", "bool", "Agent messages and questions on the TV", false, 0, 0));
        f.add(new Field("agentAlertMin", "agents", "enum", "Show agent messages from", "info", 0, 0, "info", "warn", "alert"));
        f.add(new Field("link.notifySound", "agents", "bool", "Sound with agent messages", true, 0, 0));
        f.add(new Field("link.shareTheme", "agents", "bool", "Share the theme with apps on the network", true, 0, 0));
        FIELDS = Collections.unmodifiableList(f);
    }

    private WebSettings() {
    }

    static Field field(String key) {
        for (Field f : FIELDS) if (f.key.equals(key)) return f;
        return null;
    }

    static boolean external(Field f) {
        return f.key.startsWith("ha.");
    }

    /** The values a field may take: its own, or those the TV gives (theme, language). */
    static List<String> allowed(Field f, Map<String, List<String>> choices) {
        if (!f.values.isEmpty()) return f.values;
        List<String> c = choices == null ? null : choices.get(f.key);
        List<String> out = new ArrayList<>();
        if ("language".equals(f.key)) out.add("auto");
        if (c != null) out.addAll(c);
        return out;
    }

    static JSONObject schema(Map<String, List<String>> choices) throws JSONException {
        JSONArray sections = new JSONArray();
        for (String[] s : SECTIONS) {
            JSONArray fields = new JSONArray();
            for (Field f : FIELDS) {
                if (!f.section.equals(s[0])) continue;
                JSONObject o = new JSONObject().put("key", f.key).put("type", f.type).put("label", f.label);
                if ("enum".equals(f.type)) o.put("values", new JSONArray(allowed(f, choices)));
                if ("int".equals(f.type) || "number".equals(f.type)) o.put("min", f.min).put("max", f.max);
                if ("string".equals(f.type) || "url".equals(f.type) || "secret".equals(f.type)) o.put("maxLength", (int) f.max);
                if (!"secret".equals(f.type)) o.put("default", f.def == null ? JSONObject.NULL : f.def);
                fields.put(o);
            }
            sections.put(new JSONObject().put("id", s[0]).put("label", s[1]).put("fields", fields));
        }
        return new JSONObject().put("version", 1).put("sections", sections);
    }

    /** The value at a dotted path of omarchytv.json, or null. */
    static Object at(JSONObject cfg, String key) {
        if (cfg == null) return null;
        String[] parts = key.split("\\.");
        JSONObject o = cfg;
        for (int i = 0; i < parts.length - 1; i++) {
            o = o.optJSONObject(parts[i]);
            if (o == null) return null;
        }
        Object v = o.opt(parts[parts.length - 1]);
        return v == null || v == JSONObject.NULL ? null : v;
    }

    /**
     * {values: {key: value}, secrets: {key: {set: bool}}}. {@code ext} carries the "ha.*" fields: {"ha.url": "...",
     * "ha.token": true|false}. A secret's value never leaves this method.
     */
    static JSONObject values(JSONObject cfg, JSONObject ext, String section) throws JSONException {
        JSONObject values = new JSONObject(), secrets = new JSONObject();
        for (Field f : FIELDS) {
            if (section != null && !section.isEmpty() && !f.section.equals(section)) continue;
            if ("secret".equals(f.type)) {
                secrets.put(f.key, new JSONObject().put("set", ext != null && ext.optBoolean(f.key, false)));
                continue;
            }
            Object v = external(f) ? (ext == null ? null : ext.opt(f.key)) : current(cfg, f);
            values.put(f.key, v == null ? (f.def == null ? JSONObject.NULL : f.def) : v);
        }
        return new JSONObject().put("values", values).put("secrets", secrets);
    }

    private static Object current(JSONObject cfg, Field f) {
        Object v = at(cfg, f.key);
        if ("theme".equals(f.key)) {
            Object t = cfg == null ? null : cfg.opt("theme");
            if (t instanceof JSONObject) return ((JSONObject) t).optString("name", (String) f.def);
            return t instanceof String ? t : f.def;
        }
        if (v == null) return f.def;
        switch (f.type) {
            case "bool": return v instanceof Boolean ? v : f.def;
            case "int": return v instanceof Number ? ((Number) v).intValue() : f.def;
            case "number": return v instanceof Number ? v : f.def;
            default: return String.valueOf(v);
        }
    }

    /** A checked, normalised value for {@code f}; throws IllegalArgumentException with the reason. */
    static Object normalize(Field f, Object v, Map<String, List<String>> choices) {
        switch (f.type) {
            case "bool":
                if (v instanceof Boolean) return v;
                throw new IllegalArgumentException("true or false");
            case "int": {
                if (!(v instanceof Number) || ((Number) v).doubleValue() != Math.rint(((Number) v).doubleValue())) {
                    throw new IllegalArgumentException("a whole number");
                }
                long n = ((Number) v).longValue();
                if (n < f.min || n > f.max) throw new IllegalArgumentException((long) f.min + "-" + (long) f.max);
                return (int) n;
            }
            case "number": {
                if (v == null || v == JSONObject.NULL) return JSONObject.NULL;
                if (!(v instanceof Number)) throw new IllegalArgumentException("a number");
                double d = ((Number) v).doubleValue();
                if (Double.isNaN(d) || d < f.min || d > f.max) throw new IllegalArgumentException(f.min + " to " + f.max);
                return d;
            }
            case "enum": {
                String s = v instanceof String ? (String) v : null;
                List<String> ok = allowed(f, choices);
                if (s == null || !ok.contains(s)) throw new IllegalArgumentException("one of " + ok);
                return s;
            }
            case "string": {
                if (!(v instanceof String)) throw new IllegalArgumentException("text");
                String s = LinkServer.printable((String) v, (int) f.max);
                if (!s.equals(((String) v).trim())) throw new IllegalArgumentException("plain text, up to " + (int) f.max + " characters");
                return s;
            }
            case "url": {
                String s = v instanceof String ? ((String) v).trim() : null;
                if (s == null || s.length() > f.max || s.indexOf(' ') >= 0) throw new IllegalArgumentException("a URL");
                while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
                if (!s.isEmpty() && !s.matches("^https?://[A-Za-z0-9.\\-\\[\\]:]+(:[0-9]{1,5})?$")) {
                    throw new IllegalArgumentException("http(s)://host[:port], no path");
                }
                return s;
            }
            case "secret": {
                String s = v instanceof String ? ((String) v).trim() : null;
                if (s == null || s.length() > f.max) throw new IllegalArgumentException("up to " + (int) f.max + " characters");
                for (int i = 0; i < s.length(); i++) if (s.charAt(i) < 0x21 || s.charAt(i) > 0x7e) throw new IllegalArgumentException("printable ASCII");
                return s;
            }
            default:
                throw new IllegalArgumentException("not settable");
        }
    }

    /** Writes a normalised value into omarchytv.json (only for fields that live there). */
    static void write(JSONObject cfg, Field f, Object v) throws JSONException {
        if ("theme".equals(f.key)) {
            cfg.put("theme", new JSONObject().put("name", v).put("source", "tv"));
            cfg.put("wallpaper_bg", 0);
            return;
        }
        String[] parts = f.key.split("\\.");
        JSONObject o = cfg;
        for (int i = 0; i < parts.length - 1; i++) {
            JSONObject n = o.optJSONObject(parts[i]);
            if (n == null) o.put(parts[i], n = new JSONObject());
            o = n;
        }
        String last = parts[parts.length - 1];
        if (v == JSONObject.NULL) o.remove(last);
        else o.put(last, v);
    }

    /**
     * Checks every change of one request first, so nothing is written when one value is wrong. Returns the
     * normalised changes in order, or throws IllegalArgumentException("key: reason").
     */
    static LinkedHashMap<Field, Object> check(JSONObject changes, Map<String, List<String>> choices) {
        if (changes == null || changes.length() == 0) throw new IllegalArgumentException("nothing to change");
        if (changes.length() > FIELDS.size()) throw new IllegalArgumentException("too many keys");
        LinkedHashMap<Field, Object> out = new LinkedHashMap<>();
        for (java.util.Iterator<String> it = changes.keys(); it.hasNext(); ) {
            String k = it.next();
            Field f = field(k);
            if (f == null) throw new IllegalArgumentException(clip(k) + ": unknown setting");
            try {
                out.put(f, normalize(f, changes.opt(k), choices));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(k + ": " + e.getMessage());
            }
        }
        return out;
    }

    private static String clip(String k) {
        return LinkServer.printable(k, 40);
    }
}
