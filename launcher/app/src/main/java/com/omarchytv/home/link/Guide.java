package com.omarchytv.home.link;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

/**
 * v2.22 the Guide: the agents' work as a TV schedule. Agents put programmes on it (a title, a start, an end) and say
 * how they went; the night shift and the morning briefing are on it by themselves. Kept in omarchytv.json ("guide"),
 * 50 programmes at most; a programme that ended more than 24 h ago leaves. Pure model, tested on the JVM.
 */
public final class Guide {
    public static final int MAX = 50;
    public static final long PAST_MS = 24L * 3600_000L, AHEAD_MS = 48L * 3600_000L, LONGEST_MS = 16L * 3600_000L;
    public static final List<String> STATES = Arrays.asList("scheduled", "on_air", "done", "failed", "cancelled");

    private Guide() {
    }

    private static JSONObject store(JSONObject cfg) throws JSONException {
        JSONObject g = cfg.optJSONObject("guide");
        if (g == null) cfg.put("guide", g = new JSONObject().put("seq", 0).put("programmes", new JSONArray()));
        if (g.optJSONArray("programmes") == null) g.put("programmes", new JSONArray());
        return g;
    }

    private static void prune(JSONObject g, long now) throws JSONException {
        JSONArray in = g.getJSONArray("programmes"), out = new JSONArray();
        for (int i = 0; i < in.length(); i++) if (now - in.getJSONObject(i).optLong("end") < PAST_MS) out.put(in.getJSONObject(i));
        g.put("programmes", out);
    }

    public static JSONObject add(JSONObject cfg, JSONObject b, String agent, long now, TimeZone tz) throws Studio.Bad, JSONException {
        String title = LinkServer.printable(b.optString("title", ""), 60);
        if (title.isEmpty()) throw new Studio.Bad("bad_title", "title: 1 to 60 characters", 400);
        long start = b.has("start") ? at(b.opt("start"), now, tz) : now;
        if (start < 0 || start < now - 12L * 3600_000L || start > now + AHEAD_MS) throw new Studio.Bad("bad_start", "start: \"23:30\" or epoch ms, within 48 hours", 400);
        long end = b.has("end") ? at(b.opt("end"), start, tz) : start + 3600_000L;
        if (end <= start || end - start > LONGEST_MS) throw new Studio.Bad("bad_end", "end: after start, 16 hours at most", 400);
        JSONObject g = store(cfg);
        prune(g, now);
        JSONArray ps = g.getJSONArray("programmes");
        if (ps.length() >= MAX) throw new Studio.Bad("full", MAX + " programmes", 409);
        int seq = g.optInt("seq") + 1;
        g.put("seq", seq);
        JSONObject p = new JSONObject().put("id", "p" + seq).put("agent", LinkServer.printable(agent, 40)).put("title", title)
                .put("detail", NightShift.multiline(b.optString("detail", ""), 300, 6)).put("start", start).put("end", end)
                .put("state", "scheduled").put("at", now);
        ps.put(p);
        return p;
    }

    /** "23:30" relative to ref (the next one), epoch ms, or -1. */
    private static long at(Object v, long ref, TimeZone tz) {
        return NightShift.at(v, ref, tz);
    }

    public static JSONObject update(JSONObject cfg, String id, JSONObject b, long now) throws Studio.Bad, JSONException {
        JSONArray ps = store(cfg).getJSONArray("programmes");
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.getJSONObject(i);
            if (!id.equals(p.optString("id"))) continue;
            String st = b.optString("state", p.optString("state"));
            if (!STATES.contains(st)) throw new Studio.Bad("bad_state", "state: one of " + STATES, 400);
            p.put("state", st);
            if (b.has("detail")) p.put("detail", NightShift.multiline(b.optString("detail", ""), 300, 6));
            if ("done".equals(st) || "failed".equals(st) || "cancelled".equals(st)) p.put("end", Math.min(p.optLong("end"), Math.max(now, p.optLong("start") + 60_000L)));
            if ("on_air".equals(st) && p.optLong("start") > now) p.put("start", now);
            return p;
        }
        throw new Studio.Bad("no_such_programme", id, 404);
    }

    public static void remove(JSONObject cfg, String id) throws Studio.Bad, JSONException {
        JSONArray ps = store(cfg).getJSONArray("programmes"), out = new JSONArray();
        boolean found = false;
        for (int i = 0; i < ps.length(); i++) {
            if (id.equals(ps.getJSONObject(i).optString("id"))) found = true;
            else out.put(ps.getJSONObject(i));
        }
        if (!found) throw new Studio.Bad("no_such_programme", id, 404);
        store(cfg).put("programmes", out);
    }

    /**
     * The schedule from 6 h ago on, in time order: the agents' programmes, the night shift and the morning briefing.
     * Each has "now": past | on_air | next.
     */
    public static JSONArray view(JSONObject cfg, long now) throws JSONException {
        List<JSONObject> all = new ArrayList<>();
        JSONObject g = cfg.optJSONObject("guide");
        JSONArray ps = g == null ? null : g.optJSONArray("programmes");
        for (int i = 0; ps != null && i < ps.length(); i++) all.add(new JSONObject(ps.getJSONObject(i).toString()).put("kind", "agent"));
        JSONObject sh = NightShift.status(cfg, now);
        if (!"none".equals(sh.optString("state"))) {
            JSONArray ts = sh.optJSONArray("tasks");
            long end = sh.has("endedAt") ? Math.min(sh.optLong("until"), sh.optLong("endedAt")) : sh.optLong("until");
            all.add(new JSONObject().put("id", "shift").put("kind", "shift").put("agent", sh.optString("by")).put("title", "Night shift")
                    .put("detail", (ts == null ? 0 : ts.length()) + " tasks").put("start", sh.optLong("startedAt")).put("end", Math.max(end, sh.optLong("startedAt") + 60_000L))
                    .put("state", "running".equals(sh.optString("state")) ? "on_air" : "done"));
        }
        JSONObject br = cfg.optJSONObject("briefing");
        if (br != null && br.optLong("wake") > 0) {
            all.add(new JSONObject().put("id", "briefing").put("kind", "briefing").put("agent", br.optString("by")).put("title", br.optString("title"))
                    .put("detail", "").put("start", br.optLong("wake")).put("end", br.optLong("wake") + 15 * 60_000L)
                    .put("state", br.optBoolean("shown") ? "done" : "scheduled"));
        }
        all.sort((a, b) -> Long.compare(a.optLong("start"), b.optLong("start")));
        JSONArray out = new JSONArray();
        for (JSONObject p : all) {
            if (now - p.optLong("end") > 6L * 3600_000L) continue;
            String st = p.optString("state");
            boolean closed = "done".equals(st) || "failed".equals(st) || "cancelled".equals(st);
            String when = closed || p.optLong("end") <= now ? "past" : "on_air".equals(st) || p.optLong("start") <= now ? "on_air" : "next";
            out.put(p.put("now", when));
        }
        return out;
    }
}
