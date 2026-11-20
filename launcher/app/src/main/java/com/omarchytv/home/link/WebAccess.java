package com.omarchytv.home.link;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v2.22 (#163) the web settings page: {@code http://<tv-ip>:47800/settings/}, off unless the owner turns it on
 * (omarchytv.json link.webSettings).
 *
 * <ul>
 * <li>A browser asks once ({@code POST /v1/settings/session}); the TV shows Deny / Allow read-only / Allow (the remote
 * answers, Deny has the focus). On Allow the browser gets an app token (scope read or control, named "web · …") in an
 * {@code HttpOnly; SameSite=Strict} cookie: the page's script never sees it. Revoking the token ends the session at
 * once (every request looks it up).</li>
 * <li>Every request: the Host header must be an IP address (or a *.ts.net name), so a DNS-rebinding page cannot use
 * the TV's cookie; changes also need the per-session {@code X-Omarchy-CSRF} value and an Origin equal to the
 * page's own. No CORS headers: other sites cannot read anything.</li>
 * <li>At most one approval request per browser address every 30 s, three waiting at a time.</li>
 * <li>The TV can show a QR with a one-time code (5 minutes) so the dialog says the request comes from it.</li>
 * </ul>
 */
final class WebAccess {
    static final String COOKIE = "otv_web", CSRF_HEADER = "X-Omarchy-CSRF", NAME_PREFIX = "web · ";
    static final int EXPIRE_DAYS = 30, MAX_PENDING = 3;
    static final long ASK_MS = 120_000, CODE_MS = 5 * 60_000;
    static final Pattern SESSION_PATH = Pattern.compile("^/v1/settings/session/([0-9a-f]{16})$");
    static final Pattern TOKEN_PATH = Pattern.compile("^/v1/settings/tokens/([0-9a-f]{8})$");
    static final Pattern HOST_OK = Pattern.compile("^(\\d{1,3}(\\.\\d{1,3}){3}|\\[[0-9a-fA-F:]+\\]|[a-z0-9-]+(\\.[a-z0-9-]+)*\\.ts\\.net)(:\\d{1,5})?$");

    static final class Pending {
        final String id, peer, device;
        final boolean codeOk;
        final long deadline;
        final List<String> choices;
        volatile String answer;

        Pending(String id, String peer, String device, boolean codeOk, long deadline, List<String> choices) {
            this.id = id;
            this.peer = peer;
            this.device = device;
            this.codeOk = codeOk;
            this.deadline = deadline;
            this.choices = choices;
        }
    }

    private final LinkServer s;
    private final LinkServer.Host host;
    private final AppApi app;
    private final byte[] secret = LinkCrypto.randomBytes(32);   // CSRF values; new at every start
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private String code;
    private long codeUntil;

    WebAccess(LinkServer s, LinkServer.Host host, AppApi app) {
        this.s = s;
        this.host = host;
        this.app = app;
    }

    boolean enabled() {
        JSONObject c = host.config();
        JSONObject l = c == null ? null : c.optJSONObject("link");
        return l != null && l.optBoolean("webSettings", false);
    }

    static String cookie(LinkServer.Request req) {
        for (String part : req.header("Cookie").split(";")) {
            String p = part.trim();
            if (p.startsWith(COOKIE + "=")) {
                String v = p.substring(COOKIE.length() + 1).trim();
                return v.startsWith(AppTokens.PREFIX) && v.length() <= 80 ? v : null;
            }
        }
        return null;
    }

    /** The page, its API, and the app routes called with the page's cookie (never with a Bearer token or a PC). */
    boolean wants(LinkServer.Request req) {
        String p = AppApi.path(req.target);
        if ("/settings".equals(p) || p.startsWith("/settings/")) return true;
        if (!req.header("Authorization").isEmpty()) return false;
        if (p.startsWith("/v1/settings")) return true;
        return cookie(req) != null && AppApi.appPath(p) && !"/v1/stream".equals(p) && !"/v1/sdk/omarchytv.js".equals(p);
    }

    /** One-time code for the QR on the TV (5 minutes, one use). */
    synchronized String newCode() {
        code = String.valueOf(100000 + new java.security.SecureRandom().nextInt(900000));
        codeUntil = System.currentTimeMillis() + CODE_MS;
        return code;
    }

    private synchronized boolean takeCode(String c) {
        boolean ok = code != null && c != null && System.currentTimeMillis() < codeUntil
                && LinkCrypto.equal(code.getBytes(StandardCharsets.US_ASCII), c.getBytes(StandardCharsets.US_ASCII));
        if (ok) code = null;
        return ok;
    }

    String csrf(String token) {
        return LinkCrypto.hex(LinkCrypto.hmac(secret, ("csrf\n" + token).getBytes(StandardCharsets.US_ASCII))).substring(0, 32);
    }

    /** The answer from the remote (AgentQuestionActivity through LinkServer.answerAsk). */
    synchronized boolean answer(String id, String choice) {
        Pending p = pending.get(id);
        if (p == null || p.answer != null || System.currentTimeMillis() >= p.deadline || !p.choices.contains(choice)) return false;
        p.answer = choice;
        notifyAll();
        return true;
    }

    private static LinkServer.Response json(int status, JSONObject o) {
        LinkServer.Response r = new LinkServer.Response(status, o);
        r.headers.put("Cache-Control", "no-store");
        r.headers.put("X-Content-Type-Options", "nosniff");
        return r;
    }

    private static LinkServer.Response error(int status, String code, String msg) {
        return json(status, LinkServer.err(code, msg));
    }

    LinkServer.Response handle(LinkServer.Request req) {
        try {
            return route(req);
        } catch (Exception e) {
            return error(400, "bad_request", String.valueOf(e.getMessage()));
        }
    }

    private LinkServer.Response route(LinkServer.Request req) throws Exception {
        String m = req.method, p = AppApi.path(req.target);
        if (!enabled()) return error(404, "not_found", null);
        String hostHeader = req.header("Host").toLowerCase(java.util.Locale.ROOT);
        if (!HOST_OK.matcher(hostHeader).matches()) return error(403, "bad_host", "open the page by the TV's IP address");
        boolean write = !"GET".equals(m) && !"HEAD".equals(m);
        String origin = req.header("Origin");
        if (write && !origin.equals("http://" + hostHeader)) return error(403, "bad_origin", "changes only from the TV's own page");

        if ("/settings".equals(p)) {
            LinkServer.Response r = new LinkServer.Response(301, new byte[0], "text/plain");
            r.headers.put("Location", "/settings/");
            return r;
        }
        if (p.startsWith("/settings/")) return "GET".equals(m) ? page(p.substring("/settings/".length())) : error(405, "method", null);

        JSONObject body;
        try {
            body = req.body == null || req.body.length == 0 ? new JSONObject() : new JSONObject(new String(req.body, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            return error(400, "bad_json", null);
        }

        // ---- before approval
        if ("POST".equals(m) && "/v1/settings/session".equals(p)) return ask(req, body);
        Matcher sm = SESSION_PATH.matcher(p);
        if ("GET".equals(m) && sm.matches()) return poll(req, sm.group(1));

        // ---- with the cookie
        String token = cookie(req);
        AppTokens.App a = app.tokens.find(token);
        if (a == null || !a.name.startsWith(NAME_PREFIX)) return error(401, "unauthorized", "not approved on the TV (or revoked)");
        if (write && !LinkCrypto.equal(csrf(token).getBytes(StandardCharsets.US_ASCII), req.header(CSRF_HEADER).getBytes(StandardCharsets.US_ASCII))) {
            return error(403, "csrf", "missing or wrong " + CSRF_HEADER);
        }
        long retry = s.limited(req.peer + "|web:" + a.id, 60, 10_000);
        if (retry > 0) {
            LinkServer.Response r = error(429, "rate_limited", "slow down");
            r.headers.put("Retry-After", String.valueOf(retry));
            return r;
        }
        app.tokens.touch(a, System.currentTimeMillis() / 1000);
        AppApi.Principal who = AppApi.Principal.app(a);
        if ("GET".equals(m) && "/v1/settings/me".equals(p)) {
            return json(200, new JSONObject().put("id", a.id).put("device", a.name.substring(NAME_PREFIX.length()))
                    .put("scope", a.scope).put("csrf", csrf(token)));
        }
        if ("DELETE".equals(m) && "/v1/settings/session".equals(p)) {
            app.tokens.remove(a.id);
            host.log("web settings: " + a.name + " signed out");
            LinkServer.Response r = json(200, LinkServer.ok());
            r.headers.put("Set-Cookie", COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
            return r;
        }
        if ("GET".equals(m) && "/v1/settings/theme".equals(p)) {
            JSONObject t = host.webTheme();
            return json(200, t != null ? t : new JSONObject());
        }
        if ("/v1/settings/tokens".equals(p) || TOKEN_PATH.matcher(p).matches()) {
            if (!who.can("control")) return error(403, "scope", "this browser is read-only (needs control)");
            return tokens(m, p, body, a);
        }
        if (write) host.log("web " + a.name + ": " + m + " " + p);
        LinkServer.Response r = app.route(who, req, body);   // the same routes as app tokens: settings, radio, widgets, …
        if (r == null) return error(404, "not_found", null);
        r.headers.remove("Access-Control-Allow-Origin");
        return r;
    }

    // ---- approval -----------------------------------------------------------------------------------------

    private LinkServer.Response ask(LinkServer.Request req, JSONObject body) throws JSONException {
        long retry = s.limited(req.peer + "|webask", 1, 30_000);
        if (retry > 0) {
            LinkServer.Response r = error(429, "rate_limited", "one request every 30 s");
            r.headers.put("Retry-After", String.valueOf(retry));
            return r;
        }
        String device = LinkServer.printable(body.optString("device", ""), 32);
        if (device.isEmpty()) device = "a browser";
        boolean codeOk = takeCode(body.optString("code", "").trim());
        String id = LinkCrypto.hex(LinkCrypto.randomBytes(8));
        long deadline = System.currentTimeMillis() + ASK_MS;
        List<String> choices = host.webChoices();
        synchronized (this) {
            sweep();
            if (pending.size() >= MAX_PENDING) return error(429, "busy", "too many requests waiting on the TV");
            pending.put(id, new Pending(id, req.peer, device, codeOk, deadline, choices));
        }
        String e = host.approveWeb(id, device, req.peer, codeOk, choices, deadline);
        if (e != null) {
            synchronized (this) {
                pending.remove(id);
            }
            return error(503, e, "the TV cannot show the question now");
        }
        host.log("web settings: approval asked by " + device + " (" + req.peer + ")");
        return json(200, new JSONObject().put("id", id).put("state", "pending").put("expires", deadline / 1000));
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        for (Iterator<Pending> it = pending.values().iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (now >= p.deadline + 60_000) it.remove();
        }
    }

    private LinkServer.Response poll(LinkServer.Request req, String id) throws JSONException {
        Pending p;
        synchronized (this) {
            p = pending.get(id);
            if (p != null && !p.peer.equals(req.peer)) p = null;   // only the browser that asked
            if (p != null && p.answer == null && System.currentTimeMillis() < p.deadline) {
                try {
                    wait(Math.min(10_000, p.deadline - System.currentTimeMillis()));   // long poll: answered sooner
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            if (p != null && (p.answer != null || System.currentTimeMillis() >= p.deadline)) pending.remove(id);
        }
        if (p == null) return error(404, "not_found", "no such request");
        if (p.answer == null) {
            return json(200, new JSONObject().put("state", System.currentTimeMillis() >= p.deadline ? "expired" : "pending"));
        }
        int pick = p.choices.indexOf(p.answer);   // 0 deny, 1 read only, 2 allow
        if (pick <= 0) return json(200, new JSONObject().put("state", "denied"));
        String scope = pick == 1 ? "read" : "control";
        String[] t = app.tokens.add(NAME_PREFIX + p.device, scope, "info", EXPIRE_DAYS, System.currentTimeMillis() / 1000);
        if (t == null) return error(409, "too_many_tokens", "the TV has 16 app tokens: revoke one in Connect Omarchy Linux");
        host.log("web settings: " + p.device + " (" + p.peer + ") allowed, " + scope);
        LinkServer.Response r = json(200, new JSONObject().put("state", "approved").put("scope", scope).put("csrf", csrf(t[1])));
        r.headers.put("Set-Cookie", COOKIE + "=" + t[1] + "; Path=/; Max-Age=" + (EXPIRE_DAYS * 86400) + "; HttpOnly; SameSite=Strict");
        return r;
    }

    // ---- app tokens from the page (control) -------------------------------------------------------------------

    private LinkServer.Response tokens(String m, String p, JSONObject body, AppTokens.App self) throws JSONException {
        if ("GET".equals(m) && "/v1/settings/tokens".equals(p)) {
            return json(200, new JSONObject().put("tokens", app.tokens.list()).put("self", self.id).put("max", AppTokens.MAX));
        }
        if ("POST".equals(m) && "/v1/settings/tokens".equals(p)) {
            String name = LinkServer.printable(body.optString("name", ""), 40), scope = body.optString("scope", "");
            if (name.isEmpty() || name.startsWith(NAME_PREFIX.trim())) return error(400, "bad_name", "1-40 characters, not starting with \"web ·\"");
            if (!AppTokens.validScope(scope)) return error(400, "bad_scope", "read, notify or control");
            String[] t = app.tokens.add(name, scope, System.currentTimeMillis() / 1000);
            if (t == null) return error(409, "too_many_tokens", AppTokens.MAX + " app tokens");
            host.log("web " + self.name + ": new app token " + name + " (" + scope + ")");
            return json(200, new JSONObject().put("id", t[0]).put("token", t[1]).put("name", name).put("scope", scope));   // shown once
        }
        Matcher tm = TOKEN_PATH.matcher(p);
        if ("DELETE".equals(m) && tm.matches()) {
            if (!app.tokens.remove(tm.group(1))) return error(404, "not_found", "no such token");
            host.log("web " + self.name + ": revoked app token " + tm.group(1));
            return json(200, LinkServer.ok());
        }
        return error(404, "not_found", null);
    }

    // ---- the page --------------------------------------------------------------------------------------------

    static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; "
            + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    private LinkServer.Response page(String name) {
        if (name.isEmpty()) name = "index.html";
        String type = name.endsWith(".html") ? "text/html; charset=utf-8" : name.endsWith(".js") ? "text/javascript; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8" : null;
        if (type == null || !name.matches("^[a-z0-9-]+\\.(html|js|css)$")) return error(404, "not_found", null);
        byte[] b = host.settingsAsset(name);
        if (b == null) return error(404, "not_found", null);
        LinkServer.Response r = new LinkServer.Response(200, b, type);
        r.headers.put("Content-Security-Policy", CSP);
        r.headers.put("X-Content-Type-Options", "nosniff");
        r.headers.put("X-Frame-Options", "DENY");
        r.headers.put("Referrer-Policy", "no-referrer");
        r.headers.put("Cache-Control", "no-cache");
        return r;
    }

    /** For tests: requests still waiting. */
    synchronized List<String> pendingIds() {
        return new ArrayList<>(pending.keySet());
    }
}
