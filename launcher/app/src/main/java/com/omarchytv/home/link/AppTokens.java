package com.omarchytv.home.link;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * v2.21 app tokens: what a dashboard, an agent page or an agent on another box uses instead of a paired PC's
 * token. Made by a paired PC (omarchy-link token add), each with one scope:
 * <ul>
 *   <li>{@code read}: GET /v1/state, GET /v1/stream, MCP read tools</li>
 *   <li>{@code notify}: read + POST /v1/notify, POST /v1/ask (a question answered on the remote)</li>
 *   <li>{@code control}: notify + keys, apps, theme, POST /v1/page, diagnostics</li>
 * </ul>
 * Only a SHA-256 of each token is kept (getFilesDir()/link-apps.json, never omarchytv.json); the token itself is
 * shown once, to the PC that made it. Revoked from the PC or, all at once, from the TV's settings.
 */
public final class AppTokens {
    public static final int MAX = 16;
    public static final String PREFIX = "otva_";
    static final String[] SCOPES = {"read", "notify", "control"};

    public static final class App {
        public final String id;
        public final String name;
        public final String scope;
        /** v2.22: the highest notification level this app may use (info, warn, alert); higher is lowered to it. */
        public volatile String maxLevel = "alert";
        /** v2.22: the lowest level shown from this app (its own floor); lower ones are dropped. */
        public volatile String minLevel = "info";
        /** v2.22: revoked by itself after this many days without use (0 = never); counted from the last use. */
        public volatile int expireDays = DEFAULT_EXPIRE_DAYS;
        final String hash;
        public final long created;
        public volatile long lastUsed;

        App(String id, String name, String scope, String hash, long created, long lastUsed) {
            this.id = id;
            this.name = name;
            this.scope = scope;
            this.hash = hash;
            this.created = created;
            this.lastUsed = lastUsed;
        }

        /** read &lt; notify &lt; control. */
        public boolean can(String need) {
            return rank(scope) >= rank(need);
        }

        JSONObject json() throws org.json.JSONException {
            return new JSONObject().put("id", id).put("name", name).put("scope", scope).put("maxLevel", maxLevel).put("minLevel", minLevel)
                    .put("expireDays", expireDays).put("created", created)
                    .put("lastUsed", lastUsed == 0 ? JSONObject.NULL : (Object) lastUsed);
        }
    }

    private final File file;
    private final List<App> apps = new ArrayList<>();

    public AppTokens(File file) {
        this.file = file;
        load();
    }

    static int rank(String scope) {
        for (int i = 0; i < SCOPES.length; i++) if (SCOPES[i].equals(scope)) return i;
        return -1;
    }

    public static boolean validScope(String s) {
        return rank(s) >= 0;
    }

    /** v2.22: unused app tokens go away by themselves after 90 days (a TV that left the house stops trusting them). */
    public static final int DEFAULT_EXPIRE_DAYS = 90, MAX_EXPIRE_DAYS = 3650;

    static boolean expired(App a, long nowS) {
        if (a.expireDays <= 0) return false;
        long since = Math.max(a.created, a.lastUsed);
        return nowS - since > a.expireDays * 86_400L;
    }

    /** For tests: an app by id, expired or not. */
    synchronized App byId(String id) {
        for (App a : apps) if (a.id.equals(id)) return a;
        return null;
    }

    /** v2.22: drops the expired tokens; true when any went. */
    synchronized boolean sweep(long nowS) {
        boolean r = apps.removeIf(a -> expired(a, nowS));
        if (r) save();
        return r;
    }

    /** v2.22 notification levels, lowest first. */
    static final String[] LEVELS = {"info", "warn", "alert"};

    public static int levelRank(String level) {
        for (int i = 0; i < LEVELS.length; i++) if (LEVELS[i].equals(level)) return i;
        return -1;
    }

    /** v2.22: change an app's highest notification level. False for an unknown id or level. */
    public synchronized boolean setMaxLevel(String id, String level) {
        return setLevels(id, null, level);
    }

    /**
     * v2.22: an app's floor (min) and/or ceiling (max); null keeps one. False for an unknown id, a bad level, or a
     * floor above the ceiling.
     */
    public synchronized boolean setLevels(String id, String min, String max) {
        if (min != null && levelRank(min) < 0 || max != null && levelRank(max) < 0) return false;
        for (App a : apps) {
            if (a.id.equals(id)) {
                String lo = min != null ? min : a.minLevel, hi = max != null ? max : a.maxLevel;
                if (levelRank(lo) > levelRank(hi)) return false;
                a.minLevel = lo;
                a.maxLevel = hi;
                save();
                return true;
            }
        }
        return false;
    }

    static String hashOf(String token) {
        return LinkCrypto.hex(LinkCrypto.sha256(token.getBytes(StandardCharsets.UTF_8)));
    }

    /** A new token: {id, token}. Null when 16 already exist. The name is plain text, at most 40 chars. */
    public synchronized String[] add(String name, String scope, long nowS) {
        return add(name, scope, "alert", nowS);
    }

    /** v2.22: with the highest notification level the app may use. */
    public synchronized String[] add(String name, String scope, String maxLevel, long nowS) {
        return add(name, scope, maxLevel, DEFAULT_EXPIRE_DAYS, nowS);
    }

    /** v2.22: with its expiry (days without use, 0 = never). */
    public synchronized String[] add(String name, String scope, String maxLevel, int expireDays, long nowS) {
        sweep(nowS);
        if (apps.size() >= MAX || !validScope(scope) || levelRank(maxLevel) < 0) return null;
        if (expireDays < 0 || expireDays > MAX_EXPIRE_DAYS) return null;
        String token = PREFIX + LinkCrypto.b64u(LinkCrypto.randomBytes(24));
        String id = LinkCrypto.hex(LinkCrypto.randomBytes(4));
        App app = new App(id, name, scope, hashOf(token), nowS, 0);
        app.maxLevel = maxLevel;
        app.expireDays = expireDays;
        apps.add(app);
        save();
        return new String[]{id, token};
    }

    /** The app for a presented token, or null. Constant-time per entry. */
    public synchronized App find(String token) {
        if (token == null || !token.startsWith(PREFIX) || token.length() > 80) return null;
        byte[] h = hashOf(token).getBytes(StandardCharsets.US_ASCII);
        App hit = null;
        for (App a : apps) if (LinkCrypto.equal(h, a.hash.getBytes(StandardCharsets.US_ASCII))) hit = a;
        if (hit != null && expired(hit, System.currentTimeMillis() / 1000)) {   // v2.22: unused too long
            apps.remove(hit);
            save();
            return null;
        }
        return hit;
    }

    private long savedAt;

    /** The app was used now: kept in memory, written to disk at most once a minute. */
    public synchronized void touch(App a, long nowS) {
        a.lastUsed = nowS;
        if (nowS - savedAt >= 60) {
            savedAt = nowS;
            save();
        }
    }

    public synchronized boolean remove(String id) {
        boolean r = apps.removeIf(a -> a.id.equals(id));
        if (r) save();
        return r;
    }

    public synchronized int clear() {
        int n = apps.size();
        apps.clear();
        save();
        return n;
    }

    public synchronized int size() {
        return apps.size();
    }

    public synchronized JSONArray list() {
        sweep(System.currentTimeMillis() / 1000);
        JSONArray a = new JSONArray();
        for (App x : apps) {
            try {
                a.put(x.json());
            } catch (org.json.JSONException ignored) {
            }
        }
        return a;
    }

    private void load() {
        if (!file.exists()) return;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] b = new byte[(int) Math.min(file.length(), 256 * 1024)];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            JSONArray a = new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8)).optJSONArray("apps");
            for (int i = 0; a != null && i < a.length() && apps.size() < MAX; i++) {
                JSONObject o = a.getJSONObject(i);
                String scope = o.optString("scope"), hash = o.optString("hash");
                if (!validScope(scope) || !hash.matches("[0-9a-f]{64}")) continue;
                App app = new App(o.optString("id"), o.optString("name"), scope, hash, o.optLong("created"), o.optLong("lastUsed"));
                String ml = o.optString("maxLevel", "alert");
                app.maxLevel = levelRank(ml) >= 0 ? ml : "alert";
                String mn = o.optString("minLevel", "info");
                app.minLevel = levelRank(mn) >= 0 && levelRank(mn) <= levelRank(app.maxLevel) ? mn : "info";
                int ed = o.optInt("expireDays", DEFAULT_EXPIRE_DAYS);   // tokens from 2.21 get the default
                app.expireDays = ed >= 0 && ed <= MAX_EXPIRE_DAYS ? ed : DEFAULT_EXPIRE_DAYS;
                apps.add(app);
            }
        } catch (Exception ignored) {
            // a damaged file: no app tokens, the paired PCs still work
        }
    }

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (App x : apps) a.put(x.json().put("hash", x.hash));
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(new JSONObject().put("apps", a).toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(file)) {
                file.delete();
                tmp.renameTo(file);
            }
        } catch (Exception ignored) {
        }
    }
}
