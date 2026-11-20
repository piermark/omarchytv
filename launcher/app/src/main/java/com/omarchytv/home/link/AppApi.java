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
    static final Pattern PAGE_PATH = Pattern.compile("^/v1/pages/([a-z0-9][a-z0-9-]{0,39})(/badge|/show)?$");
    static final Pattern LAN_URL = Pattern.compile("^http://(10\\.|192\\.168\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.|100\\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\\.)[0-9.]+(:[0-9]{1,5})?(/[^\\s]*)?$");

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
        String detail = "";   // v2.22: the full text shown under the question (the whole command), scrollable
        String risk = "";     // v2.22: why it is risky; framed red, no yes on the remote
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
                || "/v1/mcp".equals(p) || "/mcp".equals(p) || "/v1/sdk/omarchytv.js".equals(p)
                || "/v1/radio".equals(p) || p.startsWith("/v1/radio/") || "/v1/open".equals(p) || "/v1/pages".equals(p) || PAGE_PATH.matcher(p).matches()
                || p.startsWith("/v1/widgets/") || p.equals("/v1/widget-pages") || p.startsWith("/v1/widget-pages/")
                || p.startsWith("/v1/ha/")   // v2.22 #157 (PUT /v1/widgets, the PC's panes, stays PC-only)
                || "/v1/settings".equals(p) || "/v1/settings/schema".equals(p);   // v2.22 #163
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
        tokens.touch(a, System.currentTimeMillis() / 1000);
        if (!"GET".equals(req.method) || "/v1/stream".equals(p) || "/v1/diagnostics".equals(p)) {
            host.log("app " + a.name + ": " + req.method + " " + p);   // v2.21: integration calls in the diagnostics
        }
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
        r.headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS");
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
        if (p.startsWith("/v1/widgets/")) return widgetRoute(who, m, p, req.target, req.peer, body);   // v2.22 #157
        if (p.equals("/v1/widget-pages") || p.startsWith("/v1/widget-pages/")) return pageRoute(who, m, p, req.target, body);   // #158
        if (p.startsWith("/v1/ha/")) return haRoute(who, m, p, req.target, body);   // #160
        if ("/v1/settings".equals(p) || "/v1/settings/schema".equals(p)) return settingsRoute(who, m, p, req.target, body);   // #163
        if ("GET".equals(m) && "/v1/state".equals(p)) return who.can("read") ? new LinkServer.Response(200, state()) : forbidden("read");
        if ("POST".equals(m) && "/v1/notify".equals(p)) {
            if (!who.can("notify")) return forbidden("notify");
            return notifyAs(who, body);
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
            List<String> us = urls(body);
            String url = us.isEmpty() ? "" : us.get(0);
            String e = host.openPages(us, who.name);
            return e == null ? new LinkServer.Response(200, LinkServer.ok().put("url", url).put("urls", new JSONArray(us)))
                    : new LinkServer.Response("not_lan_url".equals(e) ? 400 : 409, LinkServer.err(e, "a http:// page on the LAN or tailnet, by IP address"));
        }
        if ("/v1/radio".equals(p) && ("GET".equals(m) || "POST".equals(m))) {   // v2.21 Radiomarchy
            if ("GET".equals(m)) return who.can("read") ? radioCmd(new JSONObject().put("action", "now")) : forbidden("read");
            return who.can("control") ? radioCmd(body) : forbidden("control");   // v2.22: station, volume, sleep
        }
        if ("/v1/radio/stations".equals(p)) {   // v2.22 #162
            if ("GET".equals(m)) return who.can("read") ? radioStationsGet() : forbidden("read");
            if ("POST".equals(m)) return who.can("control") ? radioStationAdd(body) : forbidden("control");
        }
        if (p.startsWith("/v1/radio/stations/") && "DELETE".equals(m)) {
            return who.can("control") ? radioStationRemove(p.substring("/v1/radio/stations/".length())) : forbidden("control");
        }
        if ("POST".equals(m) && "/v1/open".equals(p)) {
            if (!who.can("control")) return forbidden("control");
            String e = host.openTarget(body.optString("target", ""));
            return e == null ? new LinkServer.Response(200, LinkServer.ok().put("target", body.optString("target")))
                    : new LinkServer.Response(400, LinkServer.err(e, "home, radiomarchy, cinemarchy, arcade, office, skill"));
        }
        if ("GET".equals(m) && "/v1/pages".equals(p)) return who.can("read") ? new LinkServer.Response(200, new JSONObject().put("pages", pages())) : forbidden("read");
        Matcher pm = PAGE_PATH.matcher(p);
        if (pm.matches()) {   // v2.21 agent pages as Home tiles
            String id = pm.group(1), sub = pm.group(2);
            if ("/badge".equals(sub) && "POST".equals(m)) return who.can("notify") ? pageBadge(id, body.optInt("badge", 0)) : forbidden("notify");
            if (!who.can("control")) return forbidden("control");
            if ("/show".equals(sub) && "POST".equals(m)) {
                JSONObject pg = page(id);
                if (pg == null) return new LinkServer.Response(404, LinkServer.err("no_such_page", null));
                String e = host.openPage(pg.optString("url"), who.name);
                return e == null ? new LinkServer.Response(200, LinkServer.ok()) : new LinkServer.Response(409, LinkServer.err(e, null));
            }
            if (sub == null && "PUT".equals(m)) return publishPage(id, body, who);
            if (sub == null && "DELETE".equals(m)) return removePage(id);
        }
        if ("GET".equals(m) && "/v1/diagnostics".equals(p)) return who.can("control") ? new LinkServer.Response(200, diagnostics()) : forbidden("control");
        if ("POST".equals(m) && ("/v1/mcp".equals(p) || "/mcp".equals(p))) return s.mcp(body, who);
        // paired PCs only
        if (who.pc && "/v1/config".equals(p)) {   // v2.21 backup / restore of omarchytv.json (no secrets: those are in link.json)
            if ("GET".equals(m)) {
                JSONObject c = host.config();
                return new LinkServer.Response(200, new JSONObject().put("config", c != null ? c : new JSONObject()));
            }
            if ("PUT".equals(m)) {
                JSONObject in = body.optJSONObject("config");
                if (in == null) return new LinkServer.Response(400, LinkServer.err("bad_config", "{\"config\": {…}}"));
                host.editConfig(j -> {
                    JSONObject keepLink = j.optJSONObject("link");   // this TV's pairings and panes stay as they are
                    java.util.List<String> keys = new ArrayList<>();
                    for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) keys.add(it.next());
                    for (String k : keys) j.remove(k);
                    for (java.util.Iterator<String> it = in.keys(); it.hasNext(); ) {
                        String k = it.next();
                        if (!"link".equals(k)) j.put(k, in.get(k));
                    }
                    if (keepLink != null) j.put("link", keepLink);
                });
                host.log("config restored from the PC");
                return new LinkServer.Response(200, LinkServer.ok());
            }
        }
        // paired PCs only
        if (who.pc && "/v1/tokens".equals(p)) {
            if ("GET".equals(m)) return new LinkServer.Response(200, new JSONObject().put("apps", tokens.list()).put("max", AppTokens.MAX));
            if ("POST".equals(m)) {
                String name = LinkServer.printable(body.optString("name", ""), 40), scope = body.optString("scope", "read");
                String maxLevel = body.optString("maxLevel", "alert"), minLevel = body.optString("minLevel", "info");
                if (name.isEmpty()) return new LinkServer.Response(400, LinkServer.err("bad_name", "name is required"));
                if (!AppTokens.validScope(scope)) return new LinkServer.Response(400, LinkServer.err("bad_scope", "read, notify or control"));
                if (AppTokens.levelRank(maxLevel) < 0 || AppTokens.levelRank(minLevel) < 0 || AppTokens.levelRank(minLevel) > AppTokens.levelRank(maxLevel)) {
                    return new LinkServer.Response(400, LinkServer.err("bad_level", "info, warn or alert; the floor not above the ceiling"));
                }
                int expireDays = body.optInt("expireDays", AppTokens.DEFAULT_EXPIRE_DAYS);
                if (expireDays < 0 || expireDays > AppTokens.MAX_EXPIRE_DAYS) {
                    return new LinkServer.Response(400, LinkServer.err("bad_expiry", "expireDays 0 (never) to " + AppTokens.MAX_EXPIRE_DAYS));
                }
                String[] t = tokens.add(name, scope, maxLevel, expireDays, System.currentTimeMillis() / 1000);
                if (t != null && !"info".equals(minLevel)) tokens.setLevels(t[0], minLevel, null);
                if (t == null) return new LinkServer.Response(409, LinkServer.err("too_many_apps", AppTokens.MAX + " app tokens; revoke one"));
                host.log("app token added: " + name + " (" + scope + ")");
                return new LinkServer.Response(200, new JSONObject().put("id", t[0]).put("token", t[1]).put("name", name).put("scope", scope)
                        .put("maxLevel", maxLevel).put("minLevel", minLevel).put("expireDays", expireDays));
            }
        }
        Matcher tm = TOKEN_PATH.matcher(p);
        if (who.pc && "PUT".equals(m) && tm.matches()) {   // v2.22 {"maxLevel"?, "minLevel"?}: info | warn | alert
            String max = body.has("maxLevel") ? body.optString("maxLevel") : null, min = body.has("minLevel") ? body.optString("minLevel") : null;
            if (max == null && min == null || max != null && AppTokens.levelRank(max) < 0 || min != null && AppTokens.levelRank(min) < 0) {
                return new LinkServer.Response(400, LinkServer.err("bad_level", "maxLevel / minLevel: info, warn or alert"));
            }
            if (tokens.byId(tm.group(1)) == null) return new LinkServer.Response(404, LinkServer.err("no_such_app", null));
            if (!tokens.setLevels(tm.group(1), min, max)) return new LinkServer.Response(400, LinkServer.err("bad_level", "the floor is above the ceiling"));
            AppTokens.App x = tokens.byId(tm.group(1));
            return new LinkServer.Response(200, LinkServer.ok().put("minLevel", x.minLevel).put("maxLevel", x.maxLevel));
        }
        if (who.pc && "DELETE".equals(m) && tm.matches()) {
            closeStreams("app:" + tm.group(1));
            return tokens.remove(tm.group(1)) ? new LinkServer.Response(200, LinkServer.ok()) : new LinkServer.Response(404, LinkServer.err("no_such_app", null));
        }
        return null;
    }

    static final java.util.Set<String> RADIO = new java.util.HashSet<>(java.util.Arrays.asList("now", "play", "pause", "next", "previous", "stop"));

    // ---- v2.22 #163 settings: one backend for the web page, app tokens and MCP ----------------------------------

    LinkServer.Response settingsRoute(Principal who, String m, String p, String target, JSONObject body) throws Exception {
        if ("GET".equals(m) && "/v1/settings/schema".equals(p)) {
            return who.can("read") ? new LinkServer.Response(200, WebSettings.schema(host.settingsChoices())) : forbidden("read");
        }
        if ("GET".equals(m)) {
            if (!who.can("read")) return forbidden("read");
            String section = query(target, "section");
            return new LinkServer.Response(200, settingsGet(section == null ? null : java.net.URLDecoder.decode(section, "UTF-8")));
        }
        if ("PATCH".equals(m)) return who.can("control") ? settingsSet(body) : forbidden("control");
        return new LinkServer.Response(405, LinkServer.err("method", "GET or PATCH"));
    }

    JSONObject settingsGet(String section) throws JSONException {
        JSONObject ha = host.haConfig();
        JSONObject ext = new JSONObject().put(WebSettings.HA_URL, ha.optString("url", ""))
                .put(WebSettings.HA_TOKEN, ha.optBoolean("tokenSet", false));
        JSONObject v = WebSettings.values(host.config(), ext, section);
        String rev = LinkCrypto.hex(LinkCrypto.sha256(LinkCrypto.utf8(v.toString()))).substring(0, 16);
        return v.put("rev", rev);
    }

    /** {key: value, …}: all checked first, then written at once; "ha.url" / "ha.token" go to the TV's lights store. */
    LinkServer.Response settingsSet(JSONObject changes) throws Exception {
        java.util.LinkedHashMap<WebSettings.Field, Object> ch;
        try {
            ch = WebSettings.check(changes, host.settingsChoices());
        } catch (IllegalArgumentException e) {
            return new LinkServer.Response(400, LinkServer.err("bad_value", e.getMessage()));
        }
        String haUrl = null, haToken = null;
        boolean cfg = false;
        JSONArray keys = new JSONArray();
        for (Map.Entry<WebSettings.Field, Object> e : ch.entrySet()) {
            keys.put(e.getKey().key);
            if (WebSettings.HA_URL.equals(e.getKey().key)) haUrl = (String) e.getValue();
            else if (WebSettings.HA_TOKEN.equals(e.getKey().key)) haToken = (String) e.getValue();
            else cfg = true;
        }
        if (cfg) {
            host.editConfig(j -> {
                for (Map.Entry<WebSettings.Field, Object> e : ch.entrySet()) {
                    if (!WebSettings.external(e.getKey())) WebSettings.write(j, e.getKey(), e.getValue());
                }
            });
        }
        if (haUrl != null || haToken != null) host.haConfigure(haUrl, haToken);   // a new URL without a token forgets the old one
        s.broadcast("settings_changed", new JSONObject().put("keys", keys));
        return new LinkServer.Response(200, LinkServer.ok().put("changed", keys));
    }

    /** v2.22 #162 POST /v1/radio {action, station?, volume?, minutes?}. */
    LinkServer.Response radioCmd(JSONObject b) throws JSONException {
        String action = b.optString("action", "");
        if (!RADIO.contains(action) && !"volume".equals(action) && !"sleep".equals(action)) {
            return new LinkServer.Response(400, LinkServer.err("bad_action", "now, play, pause, next, previous, stop, volume, sleep"));
        }
        if ("volume".equals(action) && (!b.has("volume") || b.optInt("volume", -1) < 0 || b.optInt("volume", -1) > 100)) {
            return new LinkServer.Response(400, LinkServer.err("bad_volume", "0-100"));
        }
        if ("sleep".equals(action) && (b.optInt("minutes", -1) < 0 || b.optInt("minutes", -1) > 240)) {
            return new LinkServer.Response(400, LinkServer.err("bad_minutes", "0-240"));
        }
        JSONObject cmd = new JSONObject().put("action", action);
        if (b.has("station")) cmd.put("station", LinkServer.printable(b.optString("station", ""), 512));
        if (b.has("volume")) cmd.put("volume", b.optInt("volume"));
        if (b.has("minutes")) cmd.put("minutes", b.optInt("minutes"));
        JSONObject r = host.radioCmd(cmd);
        if (r != null && r.has("error")) return new LinkServer.Response(404, r);
        return r == null ? new LinkServer.Response(409, LinkServer.err("no_radio", "Radiomarchy is not available"))
                : new LinkServer.Response(200, r);
    }

    LinkServer.Response radioStationsGet() throws JSONException {
        JSONObject cfg = host.config();
        JSONObject radio = cfg != null ? cfg.optJSONObject("radio") : null;
        JSONArray a = radio != null ? radio.optJSONArray("stations") : null;
        JSONArray out = new JSONArray().put(new JSONObject().put("id", "omarchy").put("name", "Omarchy Radio").put("url", "https://radio.omarchy.org"));
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) out.put(new JSONObject().put("id", o.optString("id")).put("name", o.optString("name")).put("url", o.optString("url"))
                    .put("fav", o.optBoolean("fav")));
        }
        return new LinkServer.Response(200, new JSONObject().put("stations", out));
    }

    /** {name, url, logo?}: https any host, http only on the LAN unless radio.allowHttp; at most 200. */
    LinkServer.Response radioStationAdd(JSONObject b) throws Exception {
        String name = LinkServer.printable(b.optString("name", ""), 40), url = b.optString("url", "").trim(), logo = b.optString("logo", "").trim();
        if (name.isEmpty()) return new LinkServer.Response(400, LinkServer.err("bad_name", "1 to 40 characters"));
        String[] id = new String[1];
        String[] bad = new String[1];
        host.editConfig(j -> {
            JSONObject radio = j.optJSONObject("radio");
            if (radio == null) j.put("radio", radio = new JSONObject());
            if (!stationUrlOk(url, radio.optBoolean("allowHttp"))) {
                bad[0] = "bad_url";
                return;
            }
            JSONArray a = radio.optJSONArray("stations");
            if (a == null) radio.put("stations", a = new JSONArray());
            if (a.length() >= 200) {
                bad[0] = "too_many";
                return;
            }
            java.util.Set<String> taken = new java.util.HashSet<>();
            for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null) taken.add(a.optJSONObject(i).optString("id"));
            String base = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
            if (base.isEmpty()) base = "station";
            if (base.length() > 32) base = base.substring(0, 32);
            String nid = base;
            for (int n = 2; taken.contains(nid) || "omarchy".equals(nid); n++) nid = base + "-" + n;
            JSONObject st = new JSONObject().put("id", nid).put("name", name).put("url", url);
            if (stationUrlOk(logo, false) && logo.startsWith("https://")) st.put("logo", logo);
            a.put(st);
            id[0] = nid;
        });
        if ("bad_url".equals(bad[0])) return new LinkServer.Response(400, LinkServer.err("bad_url", "https://, or http:// on the LAN (or allow http streams on the TV)"));
        if ("too_many".equals(bad[0])) return new LinkServer.Response(409, LinkServer.err("too_many", "200 stations"));
        return new LinkServer.Response(200, LinkServer.ok().put("id", id[0]).put("name", name));
    }

    static boolean stationUrlOk(String url, boolean allowHttp) {
        if (url == null || url.length() > 512 || url.indexOf(' ') >= 0) return false;
        if (url.startsWith("https://")) return url.length() > 9;
        if (!url.startsWith("http://")) return false;
        return allowHttp ? url.length() > 8 : lanUrl(url) || url.matches("http://(10|127|192\\.168|172\\.(1[6-9]|2\\d|3[01]))\\.[0-9.]+(:\\d+)?(/.*)?");
    }

    LinkServer.Response radioStationRemove(String id) throws Exception {
        if ("omarchy".equals(id)) return new LinkServer.Response(400, LinkServer.err("built_in", "Omarchy Radio stays"));
        boolean[] gone = new boolean[1];
        host.editConfig(j -> {
            JSONObject radio = j.optJSONObject("radio");
            JSONArray a = radio != null ? radio.optJSONArray("stations") : null;
            for (int i = a == null ? -1 : a.length() - 1; i >= 0; i--) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && id.equals(o.optString("id"))) {
                    a.remove(i);
                    gone[0] = true;
                }
            }
        });
        return gone[0] ? new LinkServer.Response(200, LinkServer.ok().put("removed", id))
                : new LinkServer.Response(404, LinkServer.err("no_such_station", id));
    }

    LinkServer.Response radio(String action) throws JSONException {
        if (!RADIO.contains(action)) return new LinkServer.Response(400, LinkServer.err("bad_action", "now, play, pause, next, previous, stop"));
        JSONObject r = host.radio(action);
        return r == null ? new LinkServer.Response(409, LinkServer.err("no_radio", "Radiomarchy is not available"))
                : new LinkServer.Response(200, r);
    }

    // ---- agent pages (Home tiles) ----

    JSONArray pages() {
        JSONObject c = host.config();
        JSONArray a = c != null ? c.optJSONArray("agentPages") : null;
        return a != null ? a : new JSONArray();
    }

    JSONObject page(String id) {
        JSONArray a = pages();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) return o;
        }
        return null;
    }

    /** "url" and / or "urls" (at most 4, no duplicates, LAN / tailnet only). */
    static List<String> urls(JSONObject b) {
        List<String> out = new ArrayList<>();
        JSONArray a = b.optJSONArray("urls");
        for (int i = 0; a != null && i < a.length() && out.size() < 4; i++) {
            String u = a.optString(i, "").trim();
            if (lanUrl(u) && !out.contains(u)) out.add(u);
        }
        String one = b.optString("url", "").trim();
        if (lanUrl(one) && !out.contains(one) && out.size() < 4) out.add(0, one);
        return out;
    }

    static boolean lanUrl(String url) {
        return url != null && url.length() <= 2000 && LAN_URL.matcher(url).matches();
    }

    LinkServer.Response publishPage(String id, JSONObject b, Principal who) throws Exception {
        if (!id.matches("[a-z0-9][a-z0-9-]{0,39}")) return new LinkServer.Response(400, LinkServer.err("bad_id", "a-z, 0-9, dashes"));
        String name = LinkServer.printable(b.optString("name", ""), 32), url = b.optString("url", "").trim();
        if (name.isEmpty()) return new LinkServer.Response(400, LinkServer.err("bad_name", "name is required"));
        if (!lanUrl(url)) return new LinkServer.Response(400, LinkServer.err("not_lan_url", "http:// to a LAN or tailnet IP address"));
        String icon = LinkServer.printable(b.optString("icon", ""), 2);
        int badge = Math.max(0, Math.min(999, b.optInt("badge", 0)));
        final boolean[] full = {false};
        host.editConfig(j -> {
            JSONArray a = j.optJSONArray("agentPages");
            if (a == null) j.put("agentPages", a = new JSONArray());
            JSONObject o = null;
            for (int i = 0; i < a.length(); i++) if (id.equals(a.optJSONObject(i) != null ? a.optJSONObject(i).optString("id") : "")) o = a.optJSONObject(i);
            if (o == null) {
                if (a.length() >= HomePages.MAX_AGENT_PAGES) {   // v2.22 #158: was 16
                    full[0] = true;
                    return;
                }
                a.put(o = new JSONObject().put("id", id));
            }
            o.put("name", name).put("url", url).put("icon", icon).put("badge", badge).put("by", who.name)
                    .put("updated", System.currentTimeMillis() / 1000);
        });
        if (full[0]) return new LinkServer.Response(409, LinkServer.err("too_many_pages", HomePages.MAX_AGENT_PAGES + " pages; remove one"));
        host.log("page published by " + who.name + ": " + id);
        return new LinkServer.Response(200, LinkServer.ok().put("id", id));
    }

    LinkServer.Response pageBadge(String id, int badge) throws Exception {
        final boolean[] found = {false};
        int v = Math.max(0, Math.min(999, badge));
        host.editConfig(j -> {
            JSONArray a = j.optJSONArray("agentPages");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && id.equals(o.optString("id"))) {
                    o.put("badge", v);
                    found[0] = true;
                }
            }
        });
        return found[0] ? new LinkServer.Response(200, LinkServer.ok().put("badge", v)) : new LinkServer.Response(404, LinkServer.err("no_such_page", null));
    }

    LinkServer.Response removePage(String id) throws Exception {
        final boolean[] found = {false};
        host.editConfig(j -> {
            JSONArray a = j.optJSONArray("agentPages");
            if (a == null) return;
            JSONArray keep = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && id.equals(o.optString("id"))) found[0] = true;
                else if (o != null) keep.put(o);
            }
            j.put("agentPages", keep);
        });
        return found[0] ? new LinkServer.Response(200, LinkServer.ok()) : new LinkServer.Response(404, LinkServer.err("no_such_page", null));
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

    /**
     * POST /v1/notify and MCP tv_notify on behalf of a caller. An app token: its name is the sender, and (v2.22) a level
     * above its maxLevel is lowered to it, without the voice, and the answer says "capped". A paired PC is not capped.
     */
    LinkServer.Response notifyAs(Principal who, JSONObject body) throws Exception {
        JSONObject b = new JSONObject(body.toString());
        boolean capped = false;
        if (!who.pc) {
            b.put("from", who.name);
            String max = who.app != null ? who.app.maxLevel : "alert";
            String lvl = b.optString("level", "info");
            if (!LinkServer.LEVELS.contains(lvl)) lvl = "info";
            String min = who.app != null ? who.app.minLevel : "info";
            if (AppTokens.levelRank(lvl) < AppTokens.levelRank(min)) {   // v2.22: below this app's own floor
                host.log("notify from " + who.name + " (" + lvl + ", below its floor " + min + ")");
                return new LinkServer.Response(200, LinkServer.ok().put("shown", false).put("deferred", false).put("filtered", true));
            }
            if (AppTokens.levelRank(lvl) > AppTokens.levelRank(max)) {
                b.put("level", max).remove("speak");
                capped = true;
            }
        }
        LinkServer.Response r = s.notifyPost(b);
        if (!capped || r.status != 200) return r;
        return new LinkServer.Response(200, new JSONObject(new String(r.body, java.nio.charset.StandardCharsets.UTF_8))
                .put("capped", b.optString("level")));
    }

    // ---------------------------------------------------------------- v2.22 #157 Home widgets

    static final Pattern WIDGET_ITEM = Pattern.compile("^/v1/widgets/item/([^/?#]{1,60})$");

    /** The widgets API, shared by the paired PC, app tokens and MCP (the tool calls build the same requests). */
    LinkServer.Response widgetRoute(Principal who, String m, String p, String target, String peer, JSONObject body) throws Exception {
        if ("GET".equals(m) && "/v1/widgets/types".equals(p)) return who.can("read") ? new LinkServer.Response(200, HomeWidgets.types()) : forbidden("read");
        if ("GET".equals(m) && "/v1/widgets/list".equals(p)) {
            if (!who.can("read")) return forbidden("read");
            int page = 0;
            String q = query(target, "page");
            if (q != null && !q.isEmpty()) {
                try {
                    page = Integer.parseInt(q);
                } catch (NumberFormatException e) {
                    return new LinkServer.Response(400, LinkServer.err("bad_page", "page: a number"));
                }
            }
            return new LinkServer.Response(200, widgetList(page));
        }
        if ("POST".equals(m) && "/v1/widgets/item".equals(p)) {
            if (!who.can("control")) return forbidden("control");
            return widgetEdit((cfg, all) -> {
                JSONObject in = new JSONObject(body.toString());
                if (in.has("page")) in.put("page", HomePages.onDemand(cfg, in.opt("page"), in.optBoolean("strict"), nowS()));   // #158
                JSONObject wd = HomeWidgets.create(all, in, who.pc ? "pc" : "app:" + who.name, nowS());
                HomePages.touch(cfg, wd.optInt("page", 1), nowS());
                return wd;
            }, "created");
        }
        Matcher im = WIDGET_ITEM.matcher(p);
        if (!im.matches()) return new LinkServer.Response(404, LinkServer.err("not_found", null));
        String id = im.group(1);
        if (id.startsWith("pane:")) {
            return new LinkServer.Response(409, LinkServer.err("pane_widget", "a pane pushed by the PC (PUT /v1/widgets): change it there"));
        }
        if ("DELETE".equals(m)) {
            if (!who.can("control")) return forbidden("control");
            return widgetEdit((cfg, all) -> {
                int i = HomeWidgets.indexOf(all, id);
                if (i < 0) throw new HomeWidgets.Bad(404, "no_such_widget", id, null);
                JSONObject gone = all.getJSONObject(i);
                HomeWidgets.delete(all, id);
                return gone;
            }, "deleted");
        }
        if ("PATCH".equals(m) || "PUT".equals(m)) {
            if (body.has("type")) return new LinkServer.Response(400, LinkServer.err("bad_type", "the type cannot change: delete and create"));
            boolean shape = body.has("page") || body.has("x") || body.has("y") || body.has("w") || body.has("h");
            if (!who.can(shape ? "control" : "notify")) return forbidden(shape ? "control" : "notify");
            long retry = s.limited(peer + "|widget:" + id, 4, 1000);   // at most 4 changes a second per widget
            if (retry > 0) return new LinkServer.Response(429, LinkServer.err("rate_limited", "4 changes a second per widget"));
            boolean content = body.has("title") || body.has("icon") || body.has("ttl") || body.has("data");
            return widgetEdit((cfg, all) -> {
                JSONObject wd = null;
                JSONObject in = new JSONObject(body.toString());
                if (in.has("page")) in.put("page", HomePages.onDemand(cfg, in.opt("page"), in.optBoolean("strict"), nowS()));   // #158
                if (shape) wd = HomeWidgets.move(all, id, in, nowS());
                if (content || !shape) wd = HomeWidgets.update(all, id, in, nowS());
                if (wd != null) HomePages.touch(cfg, wd.optInt("page", 1), nowS());
                return wd;
            }, shape ? "moved" : "updated");
        }
        return new LinkServer.Response(405, LinkServer.err("bad_method", null));
    }

    // ---------------------------------------------------------------- v2.22 #158 / #159 widget pages

    static final Pattern WPAGE = Pattern.compile("^/v1/widget-pages/([^/?#]{1,40})(/show)?$");

    LinkServer.Response pageRoute(Principal who, String m, String p, String target, JSONObject body) throws Exception {
        if ("GET".equals(m) && ("/v1/widget-pages".equals(p) || "/v1/widget-pages/current".equals(p))) {
            if (!who.can("read")) return forbidden("read");
            JSONObject cfg = host.config();
            if (cfg == null) cfg = new JSONObject();
            JSONObject cur = currentPage(cfg);
            if ("/v1/widget-pages/current".equals(p)) return new LinkServer.Response(200, cur);
            return new LinkServer.Response(200, new JSONObject().put("pages", HomePages.list(new JSONObject(cfg.toString()), nowS()))
                    .put("total", HomePages.total(cfg)).put("current", cur));
        }
        if (!who.can("control")) return forbidden("control");
        if ("POST".equals(m) && "/v1/widget-pages".equals(p)) {
            int[] at = new int[1];
            LinkServer.Response r = pageEdit(cfg -> at[0] = HomePages.create(cfg, body, nowS()));
            return r != null ? r : pageOk("created", at[0]);
        }
        Matcher pm = WPAGE.matcher(p);
        if (!pm.matches()) return new LinkServer.Response(404, LinkServer.err("not_found", null));
        String ref = java.net.URLDecoder.decode(pm.group(1), "UTF-8");
        Object r0 = ref.matches("\\d{1,4}") ? (Object) Integer.parseInt(ref) : ref;
        if (pm.group(2) != null) {   // POST /v1/widget-pages/<page>/show
            if (!"POST".equals(m)) return new LinkServer.Response(405, LinkServer.err("bad_method", null));
            int[] at = new int[1];
            LinkServer.Response r = pageEdit(cfg -> {
                int idx = HomePages.resolve(cfg, r0);
                if (idx < 1 || idx > HomePages.total(cfg)) throw new HomeWidgets.Bad(404, "no_such_page", ref, null);
                cfg.put("widgetPageCurrent", idx).put("widgetPageShowAt", System.currentTimeMillis());
                at[0] = idx;
            });
            return r != null ? r : pageOk("shown", at[0]);
        }
        if ("PATCH".equals(m) || "PUT".equals(m)) {
            int[] at = new int[1];
            LinkServer.Response r = pageEdit(cfg -> {
                int idx = HomePages.resolve(cfg, r0);
                if (body.has("name")) HomePages.rename(cfg, idx > 0 ? idx : r0, body.optString("name"), nowS());
                if (body.has("to")) idx = HomePages.reorder(cfg, idx > 0 ? idx : r0, body.optInt("to"), nowS());
                if (body.has("rotate") || body.has("hidden")) {
                    if (idx < 1 || idx > HomePages.total(cfg)) throw new HomeWidgets.Bad(404, "no_such_page", ref, null);
                    HomePages.rename(cfg, idx, HomePages.name(cfg, idx), nowS());   // makes sure the entry exists
                    JSONObject meta = HomePages.meta(cfg, idx);
                    if (body.has("rotate")) meta.put("rotate", body.optBoolean("rotate"));
                    if (body.has("hidden")) meta.put("hidden", idx != 1 && body.optBoolean("hidden"));
                }
                at[0] = idx;
            });
            return r != null ? r : pageOk("updated", at[0]);
        }
        if ("DELETE".equals(m)) {
            Object moveTo = body.has("moveWidgetsTo") ? body.opt("moveWidgetsTo") : query(target, "moveTo");
            int[] moved = new int[1];
            LinkServer.Response r = pageEdit(cfg -> moved[0] = HomePages.delete(cfg, r0, moveTo, nowS()));
            return r != null ? r : new LinkServer.Response(200, LinkServer.ok().put("deleted", true).put("movedWidgets", moved[0]));
        }
        return new LinkServer.Response(405, LinkServer.err("bad_method", null));
    }

    private JSONObject currentPage(JSONObject cfg) throws JSONException {
        int idx = HomePages.shown;
        return new JSONObject().put("index", idx).put("name", idx == 0 ? "Home" : HomePages.name(cfg, idx))
                .put("id", idx == 0 ? "home" : HomePages.meta(cfg, idx) != null ? HomePages.meta(cfg, idx).optString("id", "") : "")
                .put("total", HomePages.total(cfg)).put("classicHome", idx == 0);
    }

    interface PageOp {
        void apply(JSONObject cfg) throws Exception;
    }

    private LinkServer.Response pageEdit(PageOp op) throws Exception {
        try {
            host.editConfig(op::apply);
        } catch (HomeWidgets.Bad e) {
            return new LinkServer.Response(e.status, LinkServer.err(e.code, e.getMessage()));
        }
        s.broadcast("widgets_changed", new JSONObject().put("op", "pages"));
        return null;
    }

    private LinkServer.Response pageOk(String what, int index) throws JSONException {
        JSONObject cfg = host.config();
        return new LinkServer.Response(200, LinkServer.ok().put(what, true).put("index", index)
                .put("name", cfg != null ? HomePages.name(cfg, index) : "Page " + index));
    }

    // ---------------------------------------------------------------- v2.22 #160 Home Assistant

    LinkServer.Response haRoute(Principal who, String m, String p, String target, JSONObject body) throws Exception {
        if ("GET".equals(m) && "/v1/ha/status".equals(p)) return who.can("read") ? new LinkServer.Response(200, host.haStatus()) : forbidden("read");
        if ("GET".equals(m) && "/v1/ha/states".equals(p)) {
            if (!who.can("read")) return forbidden("read");
            String d = query(target, "domain"), q = query(target, "q");
            if (d != null && !d.isEmpty() && !d.matches("[a-z_]{1,32}")) return new LinkServer.Response(400, LinkServer.err("bad_domain", "e.g. scene"));
            try {
                return new LinkServer.Response(200, new JSONObject().put("entities", host.haList(d == null ? "" : d, q == null ? "" : LinkServer.printable(q, 64))));
            } catch (Exception e) {
                String code = e.getMessage() == null ? "unreachable" : e.getMessage();
                return new LinkServer.Response("not_configured".equals(code) ? 409 : 502, LinkServer.err("ha_" + code, "Home Assistant"));
            }
        }
        if ("POST".equals(m) && "/v1/ha/run".equals(p)) {
            if (!who.can("control")) return forbidden("control");
            String e = body.optString("entity_id", "");
            String err = host.haRun(e);
            if (err == null) {
                host.log("Home Assistant: " + e + " run by " + who.name);
                return new LinkServer.Response(200, LinkServer.ok().put("entity_id", e));
            }
            int st = "read_only".equals(err) ? 400 : "not_configured".equals(err) ? 409 : 502;
            return new LinkServer.Response(st, LinkServer.err("ha_" + err, "Home Assistant"));
        }
        return new LinkServer.Response(404, LinkServer.err("not_found", null));
    }

    interface WidgetOp {
        JSONObject apply(JSONObject cfg, JSONArray all) throws Exception;
    }

    /** Runs one change on omarchytv.json "widgets" atomically (nothing is written on an error) and tells /v1/events. */
    private LinkServer.Response widgetEdit(WidgetOp op, String what) throws Exception {
        JSONObject[] out = new JSONObject[1];
        try {
            host.editConfig(j -> {
                JSONArray all = j.optJSONArray("widgets");
                if (all == null) j.put("widgets", all = new JSONArray());
                out[0] = op.apply(j, all);
            });
        } catch (HomeWidgets.Bad e) {
            JSONObject err = LinkServer.err(e.code, e.getMessage());
            if (e.free != null) err.put("free", e.free);
            return new LinkServer.Response(e.status, err);
        }
        JSONObject wd = out[0];
        if (wd != null) {
            s.broadcast("widgets_changed", new JSONObject().put("op", what).put("id", wd.optString("id")).put("page", wd.optInt("page", 1)));
        }
        return new LinkServer.Response(200, LinkServer.ok().put(what, true).put("widget", wd == null ? JSONObject.NULL : wd));
    }

    /** The widgets, the PC's panes as read-only "lines" widgets on page 1, and the free 1x1 cells of the page asked. */
    JSONObject widgetList(int page) throws JSONException {
        JSONObject cfg = host.config();
        JSONArray all = cfg != null && cfg.optJSONArray("widgets") != null ? cfg.optJSONArray("widgets") : new JSONArray();
        JSONObject link = cfg != null ? cfg.optJSONObject("link") : null;
        JSONArray panes = HomeWidgets.paneWidgets(all, link != null ? link.optJSONArray("panes") : null);
        JSONArray shown = HomeWidgets.list(all, page);
        if (page == 0 || page == 1) for (int i = 0; i < panes.length(); i++) shown.put(panes.get(i));
        JSONObject o = new JSONObject().put("widgets", shown).put("grid", new JSONObject().put("cols", HomeWidgets.COLS).put("rows", HomeWidgets.ROWS));
        if (page > 0) {
            JSONArray occupied = new JSONArray();
            for (int i = 0; i < all.length(); i++) occupied.put(all.get(i));
            if (page == 1) for (int i = 0; i < panes.length(); i++) occupied.put(panes.get(i));
            o.put("page", page).put("free", HomeWidgets.freeSlots(occupied, page, 1, 1, null));
        }
        return o;
    }

    private static long nowS() {
        return System.currentTimeMillis() / 1000;
    }

    /** v2.22 "detail": at most 40 lines and 2000 characters, each line printable (tabs become spaces). */
    static String detail(String s) {
        if (s == null || s.trim().isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        String[] lines = s.replace("\r\n", "\n").replace('\t', ' ').split("\n", -1);
        for (int i = 0; i < lines.length && i < 40 && out.length() < 2000; i++) {
            if (i > 0) out.append('\n');
            String l = lines[i];   // like LinkServer.printable, but keeps the indentation (diffs, YAML)
            for (int k = 0; k < l.length() && out.length() < 2000; ) {
                int cp = l.codePointAt(k);
                k += Character.charCount(cp);
                if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT && cp != 0x200D) continue;
                out.appendCodePoint(cp);
            }
        }
        while (out.length() > 0 && Character.isWhitespace(out.charAt(out.length() - 1))) out.setLength(out.length() - 1);
        while (out.length() > 0 && out.charAt(0) == '\n') out.deleteCharAt(0);
        return out.toString();
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
        String risk = LinkServer.printable(b.optString("risk", "").replace('\n', ' '), 60);
        if (!risk.isEmpty()) choices.removeIf(AppTokens::sayYes);   // v2.22: a risky request is approved at the PC only
        if (choices.isEmpty()) {
            choices.add(risk.isEmpty() ? "Yes" : "Deny");
            choices.add(risk.isEmpty() ? "No" : "On the PC");
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
            ask.detail = detail(b.optString("detail", ""));
            ask.risk = risk;
            asks.put(ask.id, ask);
        }
        String e = host.askUser(ask.id, ask.from, ask.question, ask.choices, ask.deadline, ask.detail, ask.risk);
        if (e != null) {
            synchronized (asks) {
                asks.remove(ask.id);
            }
            return new LinkServer.Response(403, LinkServer.err(e, "turn on agent alerts on the TV (Settings > Grok Bot & AI > AI agent alerts while you watch)"));
        }
        if (b.optBoolean("speak")) {   // v2.22: read it aloud first ("say": the full text, e.g. the whole command)
            String say = LinkServer.printable(b.optString("say", "").replace('\n', ' ').trim(), 300);
            host.speak(say.isEmpty() ? ask.from + ". " + ask.question : say);   // silent when tones and voice are off
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

    void closeAppStreams() {
        for (Sse c : streams) if (c.owner.startsWith("app:")) c.closed = true;
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
        JSONArray pcs = new JSONArray();
        for (LinkStore.Pc pc : s.store().list()) {
            String at = pc.lastAddress;
            boolean ts = false;
            try {
                ts = at != null && Tailnet.isTailnet(java.net.InetAddress.getByName(at));
            } catch (Exception ignored) {
            }
            pcs.put(new JSONObject().put("name", pc.name).put("online", s.connected(pc.id))
                    .put("lastAddress", at == null ? JSONObject.NULL : at).put("via", at == null ? JSONObject.NULL : ts ? "tailscale" : "lan"));
        }
        d.put("pcs", pcs);
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
