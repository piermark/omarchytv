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
            return new JSONObject().put("id", id).put("name", name).put("scope", scope).put("created", created)
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

    static String hashOf(String token) {
        return LinkCrypto.hex(LinkCrypto.sha256(token.getBytes(StandardCharsets.UTF_8)));
    }

    /** A new token: {id, token}. Null when 16 already exist. The name is plain text, at most 40 chars. */
    public synchronized String[] add(String name, String scope, long nowS) {
        if (apps.size() >= MAX || !validScope(scope)) return null;
        String token = PREFIX + LinkCrypto.b64u(LinkCrypto.randomBytes(24));
        String id = LinkCrypto.hex(LinkCrypto.randomBytes(4));
        apps.add(new App(id, name, scope, hashOf(token), nowS, 0));
        save();
        return new String[]{id, token};
    }

    /** The app for a presented token, or null. Constant-time per entry. */
    public synchronized App find(String token) {
        if (token == null || !token.startsWith(PREFIX) || token.length() > 80) return null;
        byte[] h = hashOf(token).getBytes(StandardCharsets.US_ASCII);
        App hit = null;
        for (App a : apps) if (LinkCrypto.equal(h, a.hash.getBytes(StandardCharsets.US_ASCII))) hit = a;
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
                apps.add(new App(o.optString("id"), o.optString("name"), scope, hash, o.optLong("created"), o.optLong("lastUsed")));
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
