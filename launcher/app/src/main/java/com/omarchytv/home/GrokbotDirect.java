package com.omarchytv.home;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * "Grok Bot diretto (senza PC)": the TV asks a Grok Bot webhook routine itself when no PC companion is linked.
 * The question goes to the user's own routine webhook (POST JSON, the routine's Sender key as Bearer and
 * X-Automation-Key, the same two headers grokbot-ask sends); the routine publishes {id, text, action} to
 * https://ntfy.sh/&lt;reply_topic&gt; and the TV polls that topic. Plain Java (org.json only), unit-tested; the same
 * rules as webos/app/grokdirect.js. It is an addition next to the PC companion, which it never touches.
 */
final class GrokbotDirect {
    static final long POLL_MS = 2_000, TIMEOUT_MS = 90_000;
    static final String NTFY = "https://ntfy.sh/";
    private static final String ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789";

    private GrokbotDirect() {
    }

    /** "otv-" + 32 random lowercase letters/digits: the private reply topic, made once and kept on the TV. */
    static String newTopic(Random r) {
        StringBuilder b = new StringBuilder("otv-");
        for (int i = 0; i < 32; i++) b.append(ALNUM.charAt(r.nextInt(ALNUM.length())));
        return b.toString();
    }

    static String newTopic() {
        return newTopic(new SecureRandom());
    }

    static boolean validTopic(String t) {
        return t != null && t.matches("otv-[a-z0-9]{32}");
    }

    /** A request id: 16 lowercase letters/digits. */
    static String newId(Random r) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 16; i++) b.append(ALNUM.charAt(r.nextInt(ALNUM.length())));
        return b.toString();
    }

    /** A plain BCP-47 tag such as "en", "it", "pt-BR", "zh-Hant-TW". */
    static boolean validLang(String l) {
        return l != null && l.length() <= 35 && l.matches("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*");
    }

    static boolean validUrl(String u) {
        return u != null && u.matches("https://[^\\s/?#]+(/[^\\s]*)?") && u.length() <= 512;
    }

    static boolean validKey(String k) {
        return k != null && k.matches("[A-Za-z0-9_\\-.]{8,256}");
    }

    static boolean configured(String url, String key) {
        return validUrl(url) && validKey(key);
    }

    /** What Settings shows instead of the key: never the key itself. */
    static String mask(String k) {
        if (k == null || k.isEmpty()) return "not set";
        String tail = k.length() > 12 ? k.substring(k.length() - 4) : "";
        return (k.startsWith("crsr_") ? "crsr_" : "") + "••••••••" + tail;
    }

    static Map<String, String> headers(String key) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json, text/plain");
        h.put("Authorization", "Bearer " + key);
        h.put("X-Automation-Key", key);
        return h;
    }

    static String body(String question, String lang, String topic, String id, List<String> apps, String context) throws Exception {
        JSONArray a = new JSONArray();
        if (apps != null) for (String s : apps) if (s != null && !s.isEmpty() && a.length() < 80) a.put(s.length() > 40 ? s.substring(0, 40) : s);
        return new JSONObject()
                .put("question", question == null ? "" : question)
                .put("lang", lang == null || lang.isEmpty() ? "en" : lang)
                .put("reply_topic", topic)
                .put("id", id)
                .put("apps", a)
                .put("context", context == null ? "" : context)
                .toString();
    }

    static String pollUrl(String topic, long sinceSec) {
        return NTFY + topic + "/json?poll=1&since=" + sinceSec;
    }

    /** A routine's reply: text to show and speak, and one optional action. */
    static final class Reply {
        final String text;
        final Action action;
        /** True when the routine sent an action the TV could not use (shown as a short line in the chat). */
        final boolean dropped;
        /** The reply's BCP-47 language for speech ("" = the launcher language). */
        final String lang;

        Reply(String text, Action action, boolean dropped, String lang) {
            this.text = text;
            this.action = action;
            this.dropped = dropped;
            this.lang = lang;
        }
    }

    /** type: play_video (url, title), open_app (name), search_youtube (query); null action = none. */
    static final class Action {
        final String type, url, title, name, query;

        Action(String type, String url, String title, String name, String query) {
            this.type = type;
            this.url = url;
            this.title = title;
            this.name = name;
            this.query = query;
        }

        /** play_video: the YouTube chain for YouTube content, else Cinemarchy's internal player. */
        boolean youtube() {
            return "play_video".equals(type) && YoutubeLaunch.youtubeOrigin(url);
        }
    }

    /** The reply for this id in an ntfy poll (newline-delimited JSON events), or null. The payload is untrusted. */
    static Reply find(String ndjson, String id) {
        if (ndjson == null || id == null) return null;
        for (String line : ndjson.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                JSONObject ev = new JSONObject(line);
                if (!"message".equals(ev.optString("event"))) continue;
                Reply r = parse(ev.optString("message", ""), id);
                if (r != null) return r;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    static Reply parse(String message, String id) {
        try {
            JSONObject m = new JSONObject(message);
            if (!id.equals(m.optString("id"))) return null;
            String text = clean(m.optString("text", ""), 2000);
            Object raw = m.opt("action");
            Action a = action(raw);
            String lang = m.optString("lang", "").trim();
            return new Reply(text, a, a == null && dropped(raw), validLang(lang) ? lang : "");
        } catch (Exception e) {
            return null;
        }
    }

    /** An action that was sent but is not usable: anything but missing, null, "none" or {"type":"none"}. */
    static boolean dropped(Object raw) {
        if (raw == null || raw == JSONObject.NULL || "none".equals(raw) || "".equals(raw)) return false;
        return !(raw instanceof JSONObject) || !"none".equals(((JSONObject) raw).optString("type", "none"));
    }

    static Action action(Object o) {
        if (!(o instanceof JSONObject)) return null;
        JSONObject a = (JSONObject) o;
        String type = a.optString("type", "none");
        switch (type) {
            case "play_video": {
                String u = a.optString("url", "").trim();
                if (!u.matches("https?://\\S+") || u.length() > 2000) {
                    // No usable url: a query (or title) becomes a YouTube search.
                    String q = clean(a.optString("query", a.optString("title", "")), 120);
                    return q.isEmpty() ? null : new Action("search_youtube", "", "", "", q);
                }
                return new Action(type, u, clean(a.optString("title", ""), 120), "", "");
            }
            case "open_app": {
                String n = clean(a.optString("name", a.optString("app", "")), 40);
                return n.isEmpty() ? null : new Action(type, "", "", n, "");
            }
            case "search_youtube": {
                String q = clean(a.optString("query", ""), 120);
                return q.isEmpty() ? null : new Action(type, "", "", "", q);
            }
            default:
                return null;
        }
    }

    static String youtubeSearchUrl(String q) {
        try {
            return "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(q, "UTF-8");
        } catch (Exception e) {
            return "https://www.youtube.com/";
        }
    }

    private static String clean(String s, int max) {
        String t = s == null ? "" : s.replaceAll("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]", "").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    static List<String> names(List<String[]> apps) {
        List<String> out = new ArrayList<>();
        for (String[] a : apps) out.add(a[0]);
        return out;
    }
}
