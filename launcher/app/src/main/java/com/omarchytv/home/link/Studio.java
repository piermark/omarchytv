package com.omarchytv.home.link;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * v2.22 three more shows on AI LIVE: the morning briefing, the agents' debate and the AI's scenes. Pure models,
 * tested on the JVM; everything is text and nothing here runs anything.
 *
 * Briefing: an agent prepares the morning's news (sections of short lines, and what to read aloud); the TV puts it on
 * air at the wake time, or when asked. Kept in omarchytv.json ("briefing").
 *
 * Debate: the owner (or an agent) puts a question to several agents, each posts its answer, and the owner picks the
 * winner on the TV with the remote: only the TV picks. Memory only.
 *
 * Scenes: a short sequence of the TV's own MCP tools (lights, an app, the theme, the radio, a key, a page, a note).
 * A new scene runs or is saved only after the owner's OK on the TV; a saved scene runs at once (the owner approved it).
 * Saved scenes are kept in omarchytv.json ("scenes").
 */
public final class Studio {
    public static final int MAX_SECTIONS = 8, MAX_LINES = 8, MAX_DEBATERS = 4, MAX_STEPS = 12, MAX_SCENES = 24;
    /** The tools a scene may use: the TV's own, no network fetches, no settings, no tokens, no pages from the network. */
    public static final List<String> SCENE_TOOLS = Arrays.asList("tv_ha_run", "tv_launch_app", "tv_set_theme", "tv_radio",
            "tv_send_key", "tv_open", "tv_notify", "tv_screensaver", "tv_page_show");

    /** An API error: code, message, HTTP status. */
    public static final class Bad extends Exception {
        public final String code;
        public final int status;

        Bad(String code, String message, int status) {
            super(message);
            this.code = code;
            this.status = status;
        }
    }

    private Studio() {
    }

    // ---- the morning briefing ------------------------------------------------------------------------------------

    public static JSONObject briefingSet(JSONObject cfg, JSONObject b, String by, long now, TimeZone tz) throws Bad, JSONException {
        String title = LinkServer.printable(b.optString("title", "Morning briefing"), 60);
        JSONArray in = b.optJSONArray("sections"), secs = new JSONArray();
        if (in == null || in.length() == 0) throw new Bad("bad_sections", "sections: 1 to " + MAX_SECTIONS + " {title, lines[]}", 400);
        if (in.length() > MAX_SECTIONS) throw new Bad("too_many", MAX_SECTIONS + " sections", 400);
        for (int i = 0; i < in.length(); i++) {
            JSONObject s = in.optJSONObject(i);
            if (s == null) throw new Bad("bad_sections", "sections: objects", 400);
            JSONArray ls = s.optJSONArray("lines"), out = new JSONArray();
            for (int k = 0; ls != null && k < ls.length() && k < MAX_LINES; k++) {
                String l = LinkServer.printable(ls.optString(k, ""), 120);
                if (!l.isEmpty()) out.put(l);
            }
            String st = LinkServer.printable(s.optString("title", ""), 40);
            if (st.isEmpty() || out.length() == 0) throw new Bad("bad_sections", "every section needs a title and lines", 400);
            secs.put(new JSONObject().put("title", st).put("lines", out));
        }
        long wake = 0;
        if (b.has("wake") && !JSONObject.NULL.equals(b.opt("wake"))) {
            wake = NightShift.at(b.opt("wake"), now, tz);
            if (wake <= now || wake - now > NightShift.WAKE_WITHIN_MS) throw new Bad("bad_wake", "wake: \"07:30\" or epoch ms, within 24 hours", 400);
        }
        JSONObject br = new JSONObject().put("title", title).put("sections", secs)
                .put("say", NightShift.multiline(b.optString("say", ""), 1500, 20)).put("by", LinkServer.printable(by, 40))
                .put("at", now).put("wake", wake).put("shown", false);
        cfg.put("briefing", br);
        return br;
    }

    /** The briefing is due on the Home: its wake time has passed (within 18 h) and it has not been on air. */
    public static boolean briefingDue(JSONObject cfg, long now) {
        JSONObject b = cfg.optJSONObject("briefing");
        return b != null && b.optLong("wake") > 0 && now >= b.optLong("wake") && now - b.optLong("wake") < 18L * 3600_000L
                && !b.optBoolean("shown");
    }

    // ---- the debate (memory only) --------------------------------------------------------------------------------

    private static final Map<String, JSONObject> DEBATES = new LinkedHashMap<>();
    private static int debateSeq;

    /** For tests. */
    public static synchronized void resetDebates() {
        DEBATES.clear();
        debateSeq = 0;
    }

    public static synchronized JSONObject debateStart(JSONObject b, String by, long now) throws Bad, JSONException {
        String topic = LinkServer.printable(b.optString("topic", ""), 160);
        if (topic.isEmpty()) throw new Bad("bad_topic", "topic: 1 to 160 characters", 400);
        while (DEBATES.size() >= 8) DEBATES.remove(DEBATES.keySet().iterator().next());
        String id = "d" + (++debateSeq);
        JSONObject d = new JSONObject().put("id", id).put("topic", topic).put("by", LinkServer.printable(by, 40))
                .put("at", now).put("state", "open").put("entries", new JSONArray());
        DEBATES.put(id, d);
        return d;
    }

    public static synchronized JSONObject debatePost(String id, String agent, JSONObject b, long now) throws Bad, JSONException {
        JSONObject d = DEBATES.get(id);
        if (d == null) throw new Bad("no_such_debate", id, 404);
        if (!"open".equals(d.optString("state"))) throw new Bad("closed", "the owner has already chosen", 409);
        String text = NightShift.multiline(b.optString("text", ""), 1200, 24);
        if (text.isEmpty()) throw new Bad("bad_text", "text: your answer, ≤1200", 400);
        String who = LinkServer.printable(agent, 40);
        JSONArray en = d.getJSONArray("entries");
        JSONObject e = new JSONObject().put("agent", who).put("text", text).put("at", now);
        if (b.has("preview") && !JSONObject.NULL.equals(b.opt("preview"))) {
            JSONObject pv = LiveFeed.preview(b.optJSONObject("preview"));
            if (pv == null) throw new Bad("bad_preview", "{kind: image, url}, {kind: diff | text, text}, {kind: chart, points}", 400);
            e.put("preview", pv);
        }
        for (int i = 0; i < en.length(); i++) {
            if (who.equals(en.getJSONObject(i).optString("agent"))) {   // a second post replaces the first
                en.put(i, e);
                return d;
            }
        }
        if (en.length() >= MAX_DEBATERS) throw new Bad("full", MAX_DEBATERS + " agents already answered", 409);
        en.put(e);
        return d;
    }

    /** The owner's pick, from the TV only. */
    public static synchronized JSONObject debateChoose(String id, int index, long now) throws Bad, JSONException {
        JSONObject d = DEBATES.get(id);
        if (d == null) throw new Bad("no_such_debate", id, 404);
        JSONArray en = d.getJSONArray("entries");
        if (index < 0 || index >= en.length()) throw new Bad("bad_choice", "no such answer", 400);
        if (!"open".equals(d.optString("state"))) throw new Bad("closed", "already chosen", 409);
        d.put("state", "chosen").put("winner", en.getJSONObject(index).optString("agent")).put("chosenAt", now);
        return d;
    }

    public static synchronized JSONObject debate(String id) throws Bad {
        JSONObject d = DEBATES.get(id);
        if (d == null) throw new Bad("no_such_debate", id, 404);
        return d;
    }

    /** The newest open debate (what the TV shows), or null. */
    public static synchronized JSONObject debateOnAir() {
        JSONObject last = null;
        for (JSONObject d : DEBATES.values()) if ("open".equals(d.optString("state"))) last = d;
        return last;
    }

    // ---- scenes ------------------------------------------------------------------------------------------------

    /** Checks the steps: only SCENE_TOOLS, each with an args object, at most MAX_STEPS. */
    public static JSONArray sceneSteps(JSONArray in) throws Bad, JSONException {
        if (in == null || in.length() == 0) throw new Bad("bad_steps", "steps: 1 to " + MAX_STEPS + " {tool, args}", 400);
        if (in.length() > MAX_STEPS) throw new Bad("too_many", MAX_STEPS + " steps", 400);
        JSONArray out = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            JSONObject s = in.optJSONObject(i);
            String tool = s == null ? "" : s.optString("tool", "");
            if (!SCENE_TOOLS.contains(tool)) throw new Bad("bad_step", "step " + (i + 1) + ": one of " + SCENE_TOOLS, 400);
            JSONObject args = s.optJSONObject("args") != null ? s.optJSONObject("args") : new JSONObject();
            if (args.toString().length() > 2000) throw new Bad("bad_step", "step " + (i + 1) + ": args too big", 400);
            out.put(new JSONObject().put("tool", tool).put("args", args));
        }
        return out;
    }

    /** A step in plain words, for the owner's question on the TV. */
    public static String describe(JSONObject step) {
        JSONObject a = step.optJSONObject("args") != null ? step.optJSONObject("args") : new JSONObject();
        switch (step.optString("tool")) {
            case "tv_ha_run": return "Home Assistant: " + LinkServer.printable(a.optString("entity", a.optString("entity_id", "?")), 80)
                    + (a.has("service") ? " (" + LinkServer.printable(a.optString("service"), 30) + ")" : "");
            case "tv_launch_app": return "open the app " + LinkServer.printable(a.optString("package", a.optString("name", "?")), 80);
            case "tv_set_theme": return "theme " + LinkServer.printable(a.optString("name", "?"), 40);
            case "tv_radio": return "radio: " + LinkServer.printable(a.optString("action", "?"), 20)
                    + (a.has("station") ? " " + LinkServer.printable(a.optString("station"), 40) : "");
            case "tv_send_key": return "press " + LinkServer.printable(a.optString("key", "?"), 30);
            case "tv_open": return "open " + LinkServer.printable(a.optString("target", "?"), 30);
            case "tv_notify": return "a note: " + LinkServer.printable(a.optString("text", a.optString("title", "")), 80);
            case "tv_screensaver": return "screensaver " + LinkServer.printable(a.optString("action", "on"), 20);
            case "tv_page_show": return "show the widget page " + LinkServer.printable(a.optString("page", "?"), 40);
            default: return step.optString("tool");
        }
    }

    public static String sceneName(String s) throws Bad {
        String n = LinkServer.printable(s == null ? "" : s, 40);
        if (n.isEmpty()) throw new Bad("bad_name", "name: 1 to 40 characters", 400);
        return n;
    }

    public static JSONObject sceneSave(JSONObject cfg, String name, JSONArray steps, String by, long now) throws Bad, JSONException {
        JSONObject sc = cfg.optJSONObject("scenes");
        if (sc == null) cfg.put("scenes", sc = new JSONObject());
        if (!sc.has(name) && sc.length() >= MAX_SCENES) throw new Bad("too_many", MAX_SCENES + " scenes", 409);
        JSONObject rec = new JSONObject().put("steps", sceneSteps(steps)).put("by", LinkServer.printable(by, 40)).put("at", now);
        sc.put(name, rec);
        return rec;
    }

    public static JSONObject scene(JSONObject cfg, String name) throws Bad {
        JSONObject sc = cfg.optJSONObject("scenes");
        JSONObject rec = sc == null ? null : sc.optJSONObject(name);
        if (rec == null) throw new Bad("no_such_scene", name, 404);
        return rec;
    }
}
