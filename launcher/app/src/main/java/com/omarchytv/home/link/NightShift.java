package com.omarchytv.home.link;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * v2.22 the night shift: before bed the owner starts a shift (tasks, an end, a wake-up time); during the night the
 * agents write a diary on the TV and leave anything risky (push, merge, posts, payments) in a queue for the morning;
 * in the morning the TV opens on the shift page and only the owner, with the remote, answers the queue.
 *
 * Pure model on the "shift" object of omarchytv.json, so it survives a restart and is tested on the JVM:
 * {active, startedAt, until, wake, by, tasks[], log[], queue[], seq, endedAt?}. Times are epoch milliseconds.
 * Everything is text; nothing here runs anything.
 */
public final class NightShift {
    public static final int MAX_TASKS = 20, MAX_LOG = 200, MAX_QUEUE = 50;
    public static final long MAX_LENGTH_MS = 16L * 3600_000L, WAKE_WITHIN_MS = 24L * 3600_000L;
    /** After the end, agents may still close their lines for a while (a build that finishes late). */
    public static final long GRACE_MS = 2L * 3600_000L;
    static final String[] LEVELS = {"ok", "fail", "info"};

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

    private NightShift() {
    }

    /** "07:30" (the next one after now, in tz) or epoch milliseconds; -1 when it is neither. */
    public static long at(Object v, long now, TimeZone tz) {
        if (v instanceof Number) return ((Number) v).longValue();
        String s = v == null ? "" : String.valueOf(v).trim();
        if (s.matches("\\d{12,14}")) return Long.parseLong(s);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([01]?\\d|2[0-3]):([0-5]\\d)").matcher(s);
        if (!m.matches()) return -1;
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(1)));
        c.set(Calendar.MINUTE, Integer.parseInt(m.group(2)));
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    public static JSONObject of(JSONObject cfg) throws JSONException {
        JSONObject s = cfg.optJSONObject("shift");
        if (s == null) cfg.put("shift", s = new JSONObject());
        return s;
    }

    /** Running now: started, not ended, before its end. */
    public static boolean running(JSONObject cfg, long now) {
        JSONObject s = cfg.optJSONObject("shift");
        return s != null && s.optBoolean("active") && now < s.optLong("until");
    }

    /** Agents may write: running, or within GRACE_MS of an end that was not pressed by the owner. */
    static boolean writable(JSONObject s, long now) {
        if (s == null || s.optLong("startedAt") == 0) return false;
        if (s.optBoolean("active")) return now < s.optLong("until") + GRACE_MS;
        return false;
    }

    public static JSONObject start(JSONObject cfg, JSONObject body, String by, long now, TimeZone tz) throws Bad, JSONException {
        if (running(cfg, now) && !body.optBoolean("replace")) throw new Bad("running", "a shift is running; end it or pass replace:true", 409);
        JSONArray in = body.optJSONArray("tasks");
        if (in == null || in.length() == 0) throw new Bad("bad_tasks", "tasks: 1 to " + MAX_TASKS + " lines", 400);
        if (in.length() > MAX_TASKS) throw new Bad("too_many", MAX_TASKS + " tasks", 400);
        JSONArray tasks = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            String t = LinkServer.printable(in.opt(i) instanceof JSONObject ? ((JSONObject) in.opt(i)).optString("text") : in.optString(i, ""), 200);
            if (!t.isEmpty()) tasks.put(new JSONObject().put("text", t));
        }
        if (tasks.length() == 0) throw new Bad("bad_tasks", "tasks: text", 400);
        long until = at(body.opt("until"), now, tz);
        if (until <= now || until - now > MAX_LENGTH_MS) throw new Bad("bad_until", "until: \"07:00\" or epoch ms, within 16 hours", 400);
        long wake = 0;
        if (body.has("wake") && !JSONObject.NULL.equals(body.opt("wake"))) {
            wake = at(body.opt("wake"), now, tz);
            if (wake <= now || wake - now > WAKE_WITHIN_MS) throw new Bad("bad_wake", "wake: \"07:30\" or epoch ms, within 24 hours", 400);
        }
        JSONObject s = new JSONObject().put("active", true).put("startedAt", now).put("until", until).put("wake", wake)
                .put("by", LinkServer.printable(by, 40)).put("tasks", tasks).put("log", new JSONArray()).put("queue", new JSONArray())
                .put("seq", 0);
        cfg.put("shift", s);
        return status(cfg, now);
    }

    public static JSONObject end(JSONObject cfg, long now) throws Bad, JSONException {
        JSONObject s = cfg.optJSONObject("shift");
        if (s == null || !s.optBoolean("active")) throw new Bad("no_shift", "no shift is running", 409);
        s.put("active", false).put("endedAt", now);
        return status(cfg, now);
    }

    public static JSONObject log(JSONObject cfg, String agent, JSONObject body, long now) throws Bad, JSONException {
        JSONObject s = cfg.optJSONObject("shift");
        if (!writable(s, now)) throw new Bad("no_shift", "no shift is running: the diary is closed", 409);
        String level = body.optString("level", "info");
        if (!java.util.Arrays.asList(LEVELS).contains(level)) throw new Bad("bad_level", "ok, fail or info", 400);
        String text = LinkServer.printable(body.optString("text", ""), 200);
        if (text.isEmpty()) throw new Bad("bad_text", "text: 1 to 200 characters", 400);
        JSONObject e = new JSONObject().put("at", now).put("agent", LinkServer.printable(agent, 40)).put("level", level).put("text", text);
        String detail = multiline(body.optString("detail", ""), 4000, 80);
        if (!detail.isEmpty()) e.put("detail", detail);
        if (body.has("task")) {
            int t = body.optInt("task", -1);
            if (t < 0 || t >= s.getJSONArray("tasks").length()) throw new Bad("bad_task", "task: an index of tasks[]", 400);
            e.put("task", t);
        }
        JSONArray log = s.getJSONArray("log");
        log.put(e);
        while (log.length() > MAX_LOG) log.remove(0);
        return e;
    }

    public static JSONObject queue(JSONObject cfg, String agent, JSONObject body, long now) throws Bad, JSONException {
        JSONObject s = cfg.optJSONObject("shift");
        if (!writable(s, now)) throw new Bad("no_shift", "no shift is running: ask the owner directly", 409);
        String title = LinkServer.printable(body.optString("title", ""), 120);
        if (title.isEmpty()) throw new Bad("bad_title", "title: 1 to 120 characters", 400);
        JSONArray q = s.getJSONArray("queue");
        int pending = 0;
        for (int i = 0; i < q.length(); i++) if ("pending".equals(q.getJSONObject(i).optString("state"))) pending++;
        if (pending >= MAX_QUEUE) throw new Bad("too_many", MAX_QUEUE + " decisions are already waiting", 409);
        int seq = s.optInt("seq") + 1;
        s.put("seq", seq);
        JSONObject e = new JSONObject().put("id", "q" + seq).put("at", now).put("agent", LinkServer.printable(agent, 40))
                .put("title", title).put("state", "pending");
        String detail = multiline(body.optString("detail", ""), 8000, 200);
        if (!detail.isEmpty()) e.put("detail", detail);
        String risk = LinkServer.printable(body.optString("risk", ""), 80);
        if (!risk.isEmpty()) e.put("risk", risk);
        q.put(e);
        return e;
    }

    public static JSONObject decision(JSONObject cfg, String id) throws Bad {
        JSONObject e = find(cfg, id);
        if (e == null) throw new Bad("no_such_item", id, 404);
        return e;
    }

    /** The owner's answer, from the TV only (the shift page). A second answer changes nothing. */
    public static JSONObject answer(JSONObject cfg, String id, boolean yes, long now) throws Bad, JSONException {
        JSONObject e = find(cfg, id);
        if (e == null) throw new Bad("no_such_item", id, 404);
        if (!"pending".equals(e.optString("state"))) throw new Bad("answered", "already " + e.optString("state"), 409);
        e.put("state", yes ? "approved" : "denied").put("answeredAt", now);
        return e;
    }

    static JSONObject find(JSONObject cfg, String id) {
        JSONObject s = cfg.optJSONObject("shift");
        JSONArray q = s == null ? null : s.optJSONArray("queue");
        if (q == null || id == null) return null;
        for (int i = 0; i < q.length(); i++) if (id.equals(q.optJSONObject(i).optString("id"))) return q.optJSONObject(i);
        return null;
    }

    public static JSONObject status(JSONObject cfg, long now) throws JSONException {
        JSONObject s = cfg.optJSONObject("shift");
        JSONObject out = new JSONObject();
        if (s == null || s.optLong("startedAt") == 0) return out.put("state", "none");
        out.put("state", running(cfg, now) ? "running" : "ended").put("startedAt", s.optLong("startedAt"))
                .put("until", s.optLong("until")).put("wake", s.optLong("wake")).put("by", s.optString("by"))
                .put("tasks", s.optJSONArray("tasks"));
        int ok = 0, fail = 0, info = 0, pending = 0;
        JSONArray log = s.optJSONArray("log"), q = s.optJSONArray("queue");
        for (int i = 0; log != null && i < log.length(); i++) {
            String l = log.optJSONObject(i).optString("level");
            if ("ok".equals(l)) ok++;
            else if ("fail".equals(l)) fail++;
            else info++;
        }
        for (int i = 0; q != null && i < q.length(); i++) if ("pending".equals(q.optJSONObject(i).optString("state"))) pending++;
        out.put("counts", new JSONObject().put("ok", ok).put("fail", fail).put("info", info).put("waiting", pending));
        if (s.has("endedAt")) out.put("endedAt", s.optLong("endedAt"));
        return out;
    }

    /** The whole shift for the TV page: status, every diary line and the queue (newest last). */
    public static JSONObject full(JSONObject cfg, long now) throws JSONException {
        JSONObject out = status(cfg, now);
        JSONObject s = cfg.optJSONObject("shift");
        if (s != null) {
            out.put("log", s.optJSONArray("log") != null ? s.optJSONArray("log") : new JSONArray());
            out.put("queue", s.optJSONArray("queue") != null ? s.optJSONArray("queue") : new JSONArray());
        }
        return out;
    }

    /** True when the TV should keep quiet: a shift is running (alerts wait for the morning page). */
    public static boolean quiet(JSONObject cfg, long now) {
        return running(cfg, now);
    }

    static String multiline(String s, int maxChars, int maxLines) {
        String[] lines = String.valueOf(s).replace("\r\n", "\n").split("\n", -1);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lines.length && i < maxLines && b.length() < maxChars; i++) {
            if (i > 0) b.append('\n');
            b.append(LinkServer.printable(lines[i], maxChars - b.length()));
        }
        return b.toString().trim();
    }
}
