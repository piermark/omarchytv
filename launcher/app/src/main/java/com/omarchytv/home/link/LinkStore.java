package com.omarchytv.home.link;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Link secrets in internal storage (getFilesDir()/link.json, PROTOCOL.md §8): the TV id, paired PCs with
 * their tokens, and the sha256 of the last pushed wallpaper. Never in the public omarchytv.json.
 */
public final class LinkStore {

    public static final int MAX_PCS = 8;
    /** lastSeen/lastAddress are written to disk at most this often (they change on every request). */
    private static final long PERSIST_SEEN_MS = 60_000L;

    public static final class Pc {
        public final String id;
        public String name;
        public final byte[] token;
        public final long pairedAt;
        public long lastSeen;
        public String lastAddress;

        Pc(String id, String name, byte[] token, long pairedAt, long lastSeen, String lastAddress) {
            this.id = id;
            this.name = name;
            this.token = token;
            this.pairedAt = pairedAt;
            this.lastSeen = lastSeen;
            this.lastAddress = lastAddress;
        }
    }

    private final File file;
    private final Map<String, Pc> pcs = new LinkedHashMap<>();
    private String tvId;
    private String wallpaperSha;
    private long savedAt;

    /** The folder of link.json (internal storage); v2.21 app tokens live next to it. */
    public File dir() {
        File d = file.getAbsoluteFile().getParentFile();
        return d != null ? d : new File(".");
    }

    public LinkStore(File file) {
        this.file = file;
        load();
        if (tvId == null || !tvId.matches("[0-9a-f]{32}")) {
            tvId = LinkCrypto.hex(LinkCrypto.randomBytes(16));
            save();
        }
    }

    private void load() {
        if (!file.exists()) return;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] b = new byte[(int) Math.min(file.length(), 1 << 20)];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            JSONObject j = new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8));
            tvId = j.optString("tvId", null);
            wallpaperSha = j.isNull("wallpaperSha") ? null : j.optString("wallpaperSha", null);
            JSONObject p = j.optJSONObject("pcs");
            if (p != null) {
                for (java.util.Iterator<String> it = p.keys(); it.hasNext(); ) {
                    String id = it.next();
                    JSONObject o = p.optJSONObject(id);
                    if (o == null) continue;
                    try {
                        pcs.put(id, new Pc(id, o.optString("name", "Omarchy"), com.omarchytv.home.ApiCompat.unb64(o.optString("token")),
                                o.optLong("pairedAt"), o.optLong("lastSeen"), o.optString("lastAddress", "")));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
            // A corrupt file loses the pairings (PCs pair again); the TV id is regenerated.
        }
    }

    private synchronized void save() {
        try {
            JSONObject j = new JSONObject();
            j.put("tvId", tvId);
            j.put("wallpaperSha", wallpaperSha == null ? JSONObject.NULL : wallpaperSha);
            JSONObject p = new JSONObject();
            for (Pc pc : pcs.values()) {
                p.put(pc.id, new JSONObject().put("name", pc.name)
                        .put("token", com.omarchytv.home.ApiCompat.b64(pc.token))
                        .put("pairedAt", pc.pairedAt).put("lastSeen", pc.lastSeen).put("lastAddress", pc.lastAddress));
            }
            j.put("pcs", p);
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(j.toString(1).getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            // Owner-only, like MODE_PRIVATE (getFilesDir() is private to the app anyway).
            tmp.setReadable(false, false);
            tmp.setReadable(true, true);
            tmp.setWritable(false, false);
            tmp.setWritable(true, true);
            if (!tmp.renameTo(file)) throw new java.io.IOException("rename");
            savedAt = System.currentTimeMillis();
        } catch (Exception ignored) {
        }
    }

    public synchronized String tvId() {
        return tvId;
    }

    public synchronized Pc get(String id) {
        return pcs.get(id);
    }

    public synchronized List<Pc> list() {
        return new ArrayList<>(pcs.values());
    }

    public synchronized boolean full(String pcId) {
        return !pcs.containsKey(pcId) && pcs.size() >= MAX_PCS;
    }

    public synchronized void put(String id, String name, byte[] token, String address) {
        long now = System.currentTimeMillis() / 1000;
        pcs.remove(id);
        pcs.put(id, new Pc(id, name, token, now, now, address));
        save();
    }

    public synchronized boolean remove(String id) {
        boolean had = pcs.remove(id) != null;
        if (had) save();
        return had;
    }

    /** Records a verified request; returns true when it was persisted (so link.pcs may be refreshed). */
    public synchronized boolean seen(String id, String address) {
        Pc pc = pcs.get(id);
        if (pc == null) return false;
        pc.lastSeen = System.currentTimeMillis() / 1000;
        boolean moved = address != null && !address.equals(pc.lastAddress);
        pc.lastAddress = address;
        if (moved || System.currentTimeMillis() - savedAt > PERSIST_SEEN_MS) {
            save();
            return true;
        }
        return false;
    }

    public synchronized String wallpaperSha() {
        return wallpaperSha;
    }

    public synchronized void setWallpaperSha(String sha) {
        wallpaperSha = sha;
        save();
    }

    /** The public view for omarchytv.json link.pcs: [{name, address, lastSeen}] (no ids, no tokens). */
    public synchronized JSONArray publicPcs() {
        JSONArray a = new JSONArray();
        try {
            for (Pc pc : pcs.values()) {
                a.put(new JSONObject().put("name", pc.name).put("address", pc.lastAddress == null ? "" : pc.lastAddress)
                        .put("lastSeen", pc.lastSeen));
            }
        } catch (Exception ignored) {
        }
        return a;
    }
}
