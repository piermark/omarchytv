package com.omarchytv.home.link;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v2.22 AI LIVE: what the agents are doing, as a TV channel. Memory only (nothing on disk): each agent's "now"
 * (a line, a progress, a preview of its work, the question it waits on) and the last events (notes, questions and
 * answers, the night shift's diary). The LIVE screen films it, the director cuts to whoever waits for a yes, and the
 * questions show the preview (#3: see the work before you approve it).
 *
 * Everything is text; a preview is an image URL (https or LAN), a diff, a text or a few numbers, checked here.
 */
public final class LiveFeed {
    public static final int MAX_EVENTS = 300, MAX_AGENTS = 24;
    static final long AGENT_TTL_MS = 6L * 3600_000L;
    private static final LiveFeed ONE = new LiveFeed();

    public static LiveFeed get() {
        return ONE;
    }

    /** One agent on the channel. */
    public static final class Now {
        public final String agent;
        public String text = "", waitingId = "", question = "";
        public int progress = -1;
        public long at, since;
        public JSONObject preview;

        Now(String agent, long now) {
            this.agent = agent;
            this.since = now;
            this.at = now;
        }
    }

    private final Map<String, Now> agents = new LinkedHashMap<>();
    private final ArrayDeque<JSONObject> events = new ArrayDeque<>();
    private long seq;
    private volatile Runnable onChange;

    LiveFeed() {
    }

    /** A fresh feed for tests. */
    public static LiveFeed forTest() {
        return new LiveFeed();
    }

    public void onChange(Runnable r) {
        onChange = r;
    }

    private void changed() {
        Runnable r = onChange;
        if (r != null) r.run();
    }

    private Now agent(String name, long now) {
        String a = LinkServer.printable(name == null || name.isEmpty() ? "agent" : name, 40);
        Now n = agents.get(a);
        if (n == null) {
            if (agents.size() >= MAX_AGENTS) {   // the quietest one leaves the studio
                String oldest = null;
                long t = Long.MAX_VALUE;
                for (Now x : agents.values()) if (x.waitingId.isEmpty() && x.at < t) { t = x.at; oldest = x.agent; }
                if (oldest == null) oldest = agents.keySet().iterator().next();
                agents.remove(oldest);
            }
            agents.put(a, n = new Now(a, now));
        }
        return n;
    }

    /** kind: note, alert, ask, answer, ok, fail, info, queued, status. */
    public synchronized void event(String agent, String kind, String text, long now) {
        try {
            JSONObject e = new JSONObject().put("seq", ++seq).put("at", now).put("agent", LinkServer.printable(agent, 40))
                    .put("kind", kind).put("text", LinkServer.printable(text, 200));
            events.addLast(e);
            while (events.size() > MAX_EVENTS) events.removeFirst();
            agent(agent, now).at = now;
        } catch (JSONException ignored) {
            // not reached: plain values
        }
        changed();
    }

    /** POST /v1/live, tv_live_update: what the agent does now, how far (0-100, -1 unknown) and a preview. */
    public synchronized JSONObject now(String agent, JSONObject b, long now) throws JSONException {
        Now n = agent(agent, now);
        String text = LinkServer.printable(b.optString("text", "").replace('\n', ' '), 120);
        if (!text.isEmpty() && !text.equals(n.text)) {
            n.text = text;
            n.since = now;
            JSONObject e = new JSONObject().put("seq", ++seq).put("at", now).put("agent", n.agent).put("kind", "status").put("text", text);
            events.addLast(e);
            while (events.size() > MAX_EVENTS) events.removeFirst();
        }
        if (b.has("progress")) n.progress = Math.max(-1, Math.min(100, b.optInt("progress", -1)));
        if (b.has("preview")) n.preview = JSONObject.NULL.equals(b.opt("preview")) ? null : preview(b.optJSONObject("preview"));
        n.at = now;
        changed();
        return json(n);
    }

    /** A question waits on the TV (the director cuts to it). */
    public synchronized void waiting(String agent, String id, String question, long now) {
        Now n = agent(agent, now);
        n.waitingId = id == null ? "" : id;
        n.question = LinkServer.printable(question, 200);
        n.at = now;
        event(agent, "ask", question, now);
    }

    public synchronized void answered(String id, String choice, long now) {
        for (Now n : agents.values()) {
            if (!n.waitingId.isEmpty() && n.waitingId.equals(id)) {
                n.waitingId = "";
                n.question = "";
                event(n.agent, "answer", choice, now);
                return;
            }
        }
    }

    /** The agent's preview when it is fresh (10 minutes), for the question dialog. */
    public synchronized JSONObject previewOf(String agent, long now) {
        Now n = agents.get(LinkServer.printable(agent, 40));
        return n != null && n.preview != null && now - n.at < 600_000L ? n.preview : null;
    }

    public synchronized JSONObject snapshot(long now, int maxEvents) throws JSONException {
        JSONArray a = new JSONArray();
        for (Iterator<Now> it = agents.values().iterator(); it.hasNext(); ) {
            Now n = it.next();
            if (n.waitingId.isEmpty() && now - n.at > AGENT_TTL_MS) { it.remove(); continue; }
            a.put(json(n));
        }
        JSONArray ev = new JSONArray();
        List<JSONObject> all = new ArrayList<>(events);
        for (int i = Math.max(0, all.size() - maxEvents); i < all.size(); i++) ev.put(all.get(i));
        return new JSONObject().put("agents", a).put("events", ev).put("now", now);
    }

    static JSONObject json(Now n) throws JSONException {
        JSONObject o = new JSONObject().put("agent", n.agent).put("text", n.text).put("progress", n.progress)
                .put("at", n.at).put("since", n.since);
        if (!n.waitingId.isEmpty()) o.put("waiting", new JSONObject().put("id", n.waitingId).put("question", n.question));
        if (n.preview != null) o.put("preview", n.preview);
        return o;
    }

    /** {kind: image, url} (https or LAN http), {kind: diff | text, text}, {kind: chart, points[], label?}; null if bad. */
    public static JSONObject preview(JSONObject p) throws JSONException {
        if (p == null) return null;
        String k = p.optString("kind", "");
        JSONObject o = new JSONObject().put("kind", k);
        String title = LinkServer.printable(p.optString("title", ""), 60);
        if (!title.isEmpty()) o.put("title", title);
        switch (k) {
            case "image": {
                String u = p.optString("url", "").trim();
                boolean ok = (u.startsWith("https://") && u.length() > 8 && u.length() <= 512 && !u.matches(".*\\s.*")) || AppApi.lanUrl(u);
                return ok ? o.put("url", u) : null;
            }
            case "diff":
            case "text": {
                String t = NightShift.multiline(p.optString("text", ""), "diff".equals(k) ? 8000 : 4000, 200);
                return t.isEmpty() ? null : o.put("text", t);
            }
            case "chart": {
                JSONArray in = p.optJSONArray("points");
                if (in == null || in.length() == 0 || in.length() > 120) return null;
                JSONArray pts = new JSONArray();
                for (int i = 0; i < in.length(); i++) {
                    double d = in.optDouble(i, Double.NaN);
                    if (Double.isNaN(d) || Double.isInfinite(d)) return null;
                    pts.put(d);
                }
                return o.put("points", pts).put("label", LinkServer.printable(p.optString("label", ""), 32));
            }
            default:
                return null;
        }
    }
}
