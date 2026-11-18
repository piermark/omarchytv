package com.omarchytv.home.link;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v2.21 the app API: what dashboards, agent pages and AI agents use, with an app token (AppTokens) or as a paired
 * PC. LAN (and, when allowed, tailnet) only, like every Link route.
 *
 * <pre>
 * GET  /v1/state              read     what is on screen: home / app / screensaver / off, the app, the theme, music
 * GET  /v1/stream             read     Server-Sent Events: state, theme, notify, ask_answer (token in ?token= too)
 * POST /v1/notify             notify   one line over whatever is playing
 * POST /v1/ask                notify   a question with up to 4 answers, picked on the remote
 * GET  /v1/ask/&lt;id&gt;?wait=25    notify   its state; waits up to 25 s for the answer
 * POST /v1/page  {url}        control  open a page of the LAN / tailnet full screen on the TV; DELETE closes it
 * GET  /v1/diagnostics        control  version, device, network, Link, the last log lines (no secrets)
 * POST /v1/mcp                any      MCP; the tools the token's scope allows
 * GET  /v1/sdk/omarchytv.js   none     the JavaScript SDK
 * POST /v1/tokens, GET /v1/tokens, DELETE /v1/tokens/&lt;id&gt;   paired PC only
 * </pre>
 */
final class AppApi {
    static final int MAX_SSE = 4, MAX_ASKS = 4;
    static final Pattern ASK_PATH = Pattern.compile("^/v1/ask/([0-9a-f]{16})$");
    static final Pattern TOKEN_PATH = Pattern.compile("^/v1/tokens/([0-9a-f]{8})$");

    /** Who is calling: an app token, or a paired PC (everything an app may do, plus managing tokens). */
    static final class Principal {
        final String key, name, scope;
        final boolean pc;
        final AppTokens.App app;

        private Principal(String key, String name, String scope, boolean pc, AppTokens.App app) {
            this.key = key;
            this.name = name;
            this.scope = scope;
            this.pc = pc;
            this.app = app;
        }

        static Principal pc(String pcId) {
            return new Principal("pc:" + pcId, "PC", "control", true, null);
        }

        static Principal app(AppTokens.App a) {
            return new Principal("app:" + a.id, a.name, a.scope, false, a);
        }

        boolean can(String need) {
            return pc || app.can(need);
        }
    }

    static final class Ask {
        final String id, owner, from, question;
        final List<String> choices;
        final long deadline;
        volatile String answer;

        Ask(String id, String owner, String from, String question, List<String> choices, long deadline) {
            this.id = id;
            this.owner = owner;
            this.from = from;
            this.question = question;
            this.choices = choices;
            this.deadline = deadline;
        }

        String state(long now) {
            return answer != null ? "answered" : now >= deadline ? "expired" : "pending";
        }

        JSONObject json(long now) throws JSONException {
            return new JSONObject().put("id", id).put("state", state(now)).put("answer", answer == null ? JSONObject.NULL : answer)
                    .put("question", question).put("choices", new JSONArray(choices)).put("expires", deadline / 1000);
        }
    }

    private final LinkServer s;
    private final LinkServer.Host host;
    final AppTokens tokens;
    private final Map<String, Ask> asks = new LinkedHashMap<>();
    private final List<Sse> streams = new CopyOnWriteArrayList<>();

    AppApi(LinkServer s, LinkServer.Host host, AppTokens tokens) {
        this.s = s;
        this.host = host;
        this.tokens = tokens;
    }

    // ---- request entry ---------------------------------------------------------------------------------------

    static String path(String target) {
        int q = target == null ? -1 : target.indexOf('?');
        return q < 0 ? target : target.substring(0, q);
    }

    static String query(String target, String key) {
        int q = target == null ? -1 : target.indexOf('?');
        if (q < 0) return null;
        for (String kv : target.substring(q + 1).split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(key)) return kv.substring(eq + 1);
        }
        return null;
    }

    static boolean appPath(String p) {
        return "/v1/state".equals(p) || "/v1/stream".equals(p) || "/v1/notify".equals(p) || "/v1/ask".equals(p)
                || ASK_PATH.matcher(p).matches() || "/v1/page".equals(p) || "/v1/diagnostics".equals(p)
                || "/v1/mcp".equals(p) || "/mcp".equals(p) || "/v1/sdk/omarchytv.js".equals(p);
    }

    /** The app token a request presents: "Authorization: Bearer otva_…", or ?token= on GET /v1/state and /v1/stream. */
    static String presented(LinkServer.Request req) {
        String a = req.header("Authorization").trim();
        if (a.startsWith("Bearer " + AppTokens.PREFIX)) return a.substring(7).trim();
        String p = path(req.target);
        if ("GET".equals(req.method) && ("/v1/stream".equals(p) || "/v1/state".equals(p))) {
            String t = query(req.target, "token");
            if (t != null && t.startsWith(AppTokens.PREFIX)) return t;
        }
        return null;
    }

    /** True when this server path handles the request instead of the signed-PC flow. */
    boolean wants(LinkServer.Request req) {
        String p = path(req.target);
        if (!appPath(p)) return false;
        if ("OPTIONS".equals(req.method)) return true;
        if ("/v1/sdk/omarchytv.js".equals(p)) return true;
        return presented(req) != null;
    }

    LinkServer.Response handle(LinkServer.Request req, OutputStream out, Socket sock) throws IOException {
        String p = path(req.target);
        if ("OPTIONS".equals(req.method)) return cors(new LinkServer.Response(204, (JSONObject) null));
        if ("/v1/sdk/omarchytv.js".equals(p)) return cors(sdk());
        AppTokens.App a = tokens.find(presented(req));
        if (a == null) return cors(new LinkServer.Response(401, LinkServer.err("unauthorized", "unknown or revoked app token")));
        long retry = s.limited(req.peer + "|app:" + a.id, 30, 10_000);
        if (retry > 0) {
            LinkServer.Response r = cors(new LinkServer.Response(429, LinkServer.err("rate_limited", "slow down")));
            r.headers.put("Retry-After", String.valueOf(retry));
            return r;
        }
        a.lastUsed = System.currentTimeMillis() / 1000;
        JSONObject body;
        try {
            body = req.body == null || req.body.length == 0 ? new JSONObject() : new JSONObject(new String(req.body, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            return cors(new LinkServer.Response(400, LinkServer.err("bad_json", null)));
        }
        Principal who = Principal.app(a);
        if ("GET".equals(req.method) && "/v1/stream".equals(p)) {
            if (!who.can("read")) return cors(forbidden("read"));
            return stream(who, out, sock);   // null: answered on the socket
        }
        try {
            LinkServer.Response r = route(who, req, body);
            return cors(r != null ? r : new LinkServer.Response(404, LinkServer.err("not_found", null)));
        } catch (Exception e) {
            return cors(new LinkServer.Response(400, LinkServer.err("bad_request", String.valueOf(e.getMessage()))));
        }
    }

    static LinkServer.Response cors(LinkServer.Response r) {
        r.headers.put("Access-Control-Allow-Origin", "*");
        r.headers.put("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        r.headers.put("Access-Control-Allow-Headers", "Authorization, Content-Type");
        r.headers.put("Access-Control-Allow-Private-Network", "true");
        r.headers.put("Access-Control-Max-Age", "600");
        return r;
    }

    static LinkServer.Response forbidden(String need) {
        return new LinkServer.Response(403, LinkServer.err("scope", "this app token cannot do that (needs " + need + ")"));
    }

    /** Shared by app tokens and signed PCs. Null when the path is not an app route. */
    LinkServer.Response route(Principal who, LinkServer.Request req, JSONObject body) throws Exception {
        String m = req.method, p = path(req.target);
        if ("GET".equals(m) && "/v1/state".equals(p)) return who.can("read") ? new LinkServer.Response(200, state()) : forbidden("read");
        if ("POST".equals(m) && "/v1/notify".equals(p)) {
            if (!who.can("notify")) return forbidden("notify");
            JSONObject b = new JSONObject(body.toString());
            if (!who.pc) b.put("from", who.name);
            return s.notifyPost(b);
        }
        if ("POST".equals(m) && "/v1/ask".equals(p)) return who.can("notify") ? askPost(who, body) : forbidden("notify");
        Matcher am = ASK_PATH.matcher(p);
        if ("GET".equals(m) && am.matches()) {
            if (!who.can("notify")) return forbidden("notify");
            int wait = 0;
            try {
                wait = Integer.parseInt(String.valueOf(query(req.target, "wait")));
            } catch (NumberFormatException ignored) {
            }
            return askGet(who, am.group(1), Math.max(0, Math.min(25, wait)) * 1000L);
        }
        if ("/v1/page".equals(p) && ("POST".equals(m) || "DELETE".equals(m))) {
            if (!who.can("control")) return forbidden("control");
            if ("DELETE".equals(m)) return new LinkServer.Response(200, LinkServer.ok().put("closed", host.closePage()));
            String url = body.optString("url", "");
            String e = host.openPage(url, who.name);
            return e == null ? new LinkServer.Response(200, LinkServer.ok().put("url", url))
                    : new LinkServer.Response("not_lan_url".equals(e) ? 400 : 409, LinkServer.err(e, "a http:// page on the LAN or tailnet, by IP address"));
        }
        if ("GET".equals(m) && "/v1/diagnostics".equals(p)) return who.can("control") ? new LinkServer.Response(200, diagnostics()) : forbidden("control");
        if ("POST".equals(m) && ("/v1/mcp".equals(p) || "/mcp".equals(p))) return s.mcp(body, who);
        // paired PCs only
        if (who.pc && "/v1/tokens".equals(p)) {
            if ("GET".equals(m)) return new LinkServer.Response(200, new JSONObject().put("apps", tokens.list()).put("max", AppTokens.MAX));
            if ("POST".equals(m)) {
                String name = LinkServer.printable(body.optString("name", ""), 40), scope = body.optString("scope", "read");
                if (name.isEmpty()) return new LinkServer.Response(400, LinkServer.err("bad_name", "name is required"));
                if (!AppTokens.validScope(scope)) return new LinkServer.Response(400, LinkServer.err("bad_scope", "read, notify or control"));
                String[] t = tokens.add(name, scope, System.currentTimeMillis() / 1000);
                if (t == null) return new LinkServer.Response(409, LinkServer.err("too_many_apps", AppTokens.MAX + " app tokens; revoke one"));
                host.log("app token added: " + name + " (" + scope + ")");
                return new LinkServer.Response(200, new JSONObject().put("id", t[0]).put("token", t[1]).put("name", name).put("scope", scope));
            }
        }
        Matcher tm = TOKEN_PATH.matcher(p);
        if (who.pc && "DELETE".equals(m) && tm.matches()) {
            closeStreams("app:" + tm.group(1));
            return tokens.remove(tm.group(1)) ? new LinkServer.Response(200, LinkServer.ok()) : new LinkServer.Response(404, LinkServer.err("no_such_app", null));
        }
        return null;
    }

    // ---- state ---------------------------------------------------------------------------------------------

    JSONObject state() throws JSONException {
        JSONObject st = host.status();
        JSONObject o = new JSONObject();
        boolean on = st.optBoolean("screenOn", true);
        JSONObject fg = st.optJSONObject("foreground");
        String screen = !on ? "off" : st.optBoolean("screensaver") ? "screensaver" : st.optBoolean("homeVisible") ? "home" : fg != null ? "app" : "unknown";
        o.put("screen", screen);
        o.put("app", fg != null && "app".equals(screen) ? new JSONObject().put("package", fg.optString("package")).put("label", fg.optString("label")) : JSONObject.NULL);
        JSONObject theme = host.sharedTheme();
        o.put("theme", theme != null ? theme.optString("name") : JSONObject.NULL);
        JSONObject np = host.nowPlaying();
        o.put("nowPlaying", np != null ? np : JSONObject.NULL);
        o.put("launcherVersion", st.optString("launcherVersion"));
        o.put("time", System.currentTimeMillis() / 1000);
        return o;
    }

    // ---- ask_user -------------------------------------------------------------------------------------------

    private static String newAskId() {
        return LinkCrypto.hex(LinkCrypto.randomBytes(8));
    }

    LinkServer.Response askPost(Principal who, JSONObject b) throws JSONException {
        String q = LinkServer.printable(b.optString("question", "").replace('\n', ' '), 200);
        if (q.isEmpty()) return new LinkServer.Response(400, LinkServer.err("bad_ask", "question is required"));
        List<String> choices = new ArrayList<>();
        JSONArray c = b.optJSONArray("choices");
        for (int i = 0; c != null && i < c.length() && choices.size() < 4; i++) {
            String x = LinkServer.printable(c.optString(i, ""), 24);
            if (!x.isEmpty() && !choices.contains(x)) choices.add(x);
        }
        if (choices.isEmpty()) {
            choices.add("Yes");
            choices.add("No");
        }
        int timeout = Math.max(10, Math.min(300, b.optInt("timeout", 120)));
        String from = who.pc ? LinkServer.printable(b.optString("from", ""), 40) : who.name;
        if (from.isEmpty()) from = "PC";
        long now = System.currentTimeMillis();
        Ask ask;
        synchronized (asks) {
            for (Iterator<Ask> it = asks.values().iterator(); it.hasNext(); ) {
                Ask x = it.next();
                if (x.deadline + 600_000 < now) it.remove();   // forgotten 10 min after it ended
            }
            int pending = 0;
            for (Ask x : asks.values()) if ("pending".equals(x.state(now))) pending++;
            if (pending >= MAX_ASKS) return new LinkServer.Response(429, LinkServer.err("busy", MAX_ASKS + " questions are already waiting on the TV"));
            ask = new Ask(newAskId(), who.key, from, q, choices, now + timeout * 1000L);
            asks.put(ask.id, ask);
        }
        String e = host.askUser(ask.id, ask.from, ask.question, ask.choices, ask.deadline);
        if (e != null) {
            synchronized (asks) {
                asks.remove(ask.id);
            }
            return new LinkServer.Response(403, LinkServer.err(e, "turn on agent alerts on the TV (Settings > Omarchy Link)"));
        }
        return new LinkServer.Response(200, ask.json(now));
    }

    LinkServer.Response askGet(Principal who, String id, long waitMs) throws JSONException {
        Ask a;
        synchronized (asks) {
            a = asks.get(id);
        }
        if (a == null || !(who.pc || a.owner.equals(who.key))) return new LinkServer.Response(404, LinkServer.err("no_such_ask", null));
        await(a, waitMs);
        return new LinkServer.Response(200, a.json(System.currentTimeMillis()));
    }

    /** Waits for an answer at most {@code ms} (and never past the deadline). */
    JSONObject await(String id, long ms) throws JSONException {
        Ask a;
        synchronized (asks) {
            a = asks.get(id);
        }
        if (a == null) return null;
        await(a, ms);
        return a.json(System.currentTimeMillis());
    }

    private void await(Ask a, long ms) {
        long until = Math.min(System.currentTimeMillis() + ms, a.deadline);
        synchronized (a) {
            while (a.answer == null) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    a.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /** From the TV's dialog. False when the question is gone, already answered or expired. */
    boolean answer(String id, String choice) {
        Ask a;
        synchronized (asks) {
            a = asks.get(id);
        }
        if (a == null || a.answer != null || System.currentTimeMillis() >= a.deadline || !a.choices.contains(choice)) return false;
        synchronized (a) {
            a.answer = choice;
            a.notifyAll();
        }
        try {
            JSONObject ev = new JSONObject().put("id", id).put("answer", choice);
            s.broadcast("ask_answer", ev);   // PCs (WebSocket) and pages (SSE)
        } catch (JSONException ignored) {
        }
        return true;
    }

    /** Questions still waiting, oldest first (Home shows them again when it comes back). */
    List<Ask> pending() {
        long now = System.currentTimeMillis();
        List<Ask> out = new ArrayList<>();
        synchronized (asks) {
            for (Ask a : asks.values()) if ("pending".equals(a.state(now))) out.add(a);
        }
        return out;
    }

    // ---- Server-Sent Events --------------------------------------------------------------------------------

    final class Sse {
        final String owner;
        final LinkedBlockingQueue<String> q = new LinkedBlockingQueue<>(64);
        volatile boolean closed;

        Sse(String owner) {
            this.owner = owner;
        }
    }

    /** Called by LinkServer.broadcast: notify and ask_answer reach pages too (ask_answer only its asker's). */
    void event(String type, JSONObject data) {
        if (streams.isEmpty() || !("notify".equals(type) || "ask_answer".equals(type))) return;
        String owner = null;
        if ("ask_answer".equals(type)) {
            synchronized (asks) {
                Ask a = asks.get(data.optString("id"));
                owner = a != null ? a.owner : "";
            }
        }
        String frame = frame(type, data.toString());
        for (Sse c : streams) if (owner == null || owner.equals(c.owner)) c.q.offer(frame);
    }

    static String frame(String type, String json) {
        return "event: " + type + "\ndata: " + json + "\n\n";
    }

    private void closeStreams(String owner) {
        for (Sse c : streams) if (c.owner.equals(owner)) c.closed = true;
    }

    /** Keeps the socket for up to an hour: state on change (2 s), theme on change (5 s), events, a ping every 20 s. */
    LinkServer.Response stream(Principal who, OutputStream out, Socket sock) {
        if (streams.size() >= MAX_SSE) return cors(new LinkServer.Response(503, LinkServer.err("busy", MAX_SSE + " streams are open")));
        Sse c = new Sse(who.key);
        streams.add(c);
        try {
            sock.setSoTimeout(0);
            String head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-store\r\n"
                    + "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Private-Network: true\r\nX-Accel-Buffering: no\r\n"
                    + "Connection: close\r\n\r\nretry: 3000\n\n";
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            String lastState = "", lastTheme = "";
            long end = System.currentTimeMillis() + 3_600_000L, nextState = 0, nextTheme = 0, nextPing = System.currentTimeMillis() + 20_000;
            while (!c.closed && System.currentTimeMillis() < end && s.isRunning()) {
                long now = System.currentTimeMillis();
                StringBuilder w = new StringBuilder();
                if (now >= nextState) {
                    nextState = now + 2000;
                    JSONObject st = state();
                    st.remove("time");
                    String k = st.toString();
                    if (!k.equals(lastState)) {
                        lastState = k;
                        w.append(frame("state", st.put("time", now / 1000).toString()));
                    }
                }
                if (now >= nextTheme) {
                    nextTheme = now + 5000;
                    JSONObject t = host.sharedTheme();
                    String k = t == null ? "null" : t.toString();
                    if (!k.equals(lastTheme)) {
                        lastTheme = k;
                        if (t != null) w.append(frame("theme", k));
                    }
                }
                if (now >= nextPing) {
                    nextPing = now + 20_000;
                    w.append(": ping\n\n");
                }
                String ev;
                while ((ev = c.q.poll()) != null) w.append(ev);
                if (w.length() > 0) {
                    out.write(w.toString().getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                ev = c.q.poll(1, TimeUnit.SECONDS);
                if (ev != null) c.q.add(ev);   // sent on the next turn, at once
            }
        } catch (IOException | JSONException | IllegalStateException e) {
            // the page went away
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            streams.remove(c);
        }
        return null;
    }

    int streams() {
        return streams.size();
    }

    // ---- diagnostics, SDK -----------------------------------------------------------------------------------

    JSONObject diagnostics() throws JSONException {
        JSONObject d = host.diagnostics();
        if (d == null) d = new JSONObject();
        d.put("link", new JSONObject().put("pcs", s.store().list().size()).put("events", s.connectedClients())
                .put("streams", streams.size()).put("appTokens", tokens.size()).put("pendingQuestions", pending().size()));
        return d;
    }

    private LinkServer.Response sdk() {
        byte[] js = host.sdk();
        if (js == null) return new LinkServer.Response(404, LinkServer.err("not_found", null));
        LinkServer.Response r = new LinkServer.Response(200, js, "text/javascript; charset=utf-8");
        r.headers.put("Cache-Control", "max-age=3600");
        return r;
    }
}
