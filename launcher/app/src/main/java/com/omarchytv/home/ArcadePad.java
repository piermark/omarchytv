package com.omarchytv.home;

import android.content.res.AssetManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v2.15 phones as gamepads for the Arcade. While the arcade is open (and only then) the TV answers on its own Wi-Fi
 * address, on a random port, behind a random code that only the QR in the arcade's sign carries:
 * <pre>
 *   GET /p/CODE             the gamepad page (assets/games/pad.html): a D-pad, A (OK) and B (Back)
 *   GET /hello?c=CODE&i=ID  "which player am I?" {p: 1 or 2, 0 when two phones already play}, also the heartbeat
 *   GET /k?c=CODE&i=ID&k=up a key, as that player
 * </pre>
 * Nothing to install and no account: the phone's browser is the controller. A phone that stays quiet for 45 s (the
 * page says hello every 15 s) gives its place to the next one. The server closes with the arcade.
 */
final class ArcadePad {
    interface Sink {
        void key(String k, int player);

        void players(List<Integer> on);
    }

    static final Set<String> KEYS = new HashSet<>(Arrays.asList("up", "down", "left", "right", "ok", "back"));
    private static final long IDLE_MS = 45000;
    private static final String ALPHA = "abcdefghjkmnpqrstuvwxyz23456789";

    private final AssetManager assets;
    private final Sink sink;
    private final String code;
    private final Map<String, Integer> slot = new HashMap<>();
    private final Map<String, Long> seen = new HashMap<>();
    private ServerSocket server;
    private ExecutorService pool;
    private String url;

    ArcadePad(AssetManager assets, Sink sink) {
        this(assets, sink, randomCode());
    }

    ArcadePad(AssetManager assets, Sink sink, String code) {
        this.assets = assets;
        this.sink = sink;
        this.code = code;
    }

    static String randomCode() {
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 8; i++) b.append(ALPHA.charAt(r.nextInt(ALPHA.length())));
        return b.toString();
    }

    /** Starts on the TV's own address; the page's URL, or null with no Wi-Fi/Ethernet address (nothing to scan then). */
    synchronized String start(String ip) {
        if (ip == null || ip.isEmpty()) return null;
        try {
            server = new ServerSocket(0, 16, InetAddress.getByName(ip));
        } catch (Exception e) {
            return null;
        }
        pool = Executors.newFixedThreadPool(4);
        final ServerSocket ss = server;
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    final Socket s = ss.accept();
                    pool.execute(() -> serve(s));
                } catch (Exception e) {
                    if (ss.isClosed()) return;
                }
            }
        }, "arcade-pad");
        t.setDaemon(true);
        t.start();
        url = "http://" + ip + ":" + server.getLocalPort() + "/p/" + code;
        return url;
    }

    synchronized void stop() {
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        if (pool != null) pool.shutdownNow();
        server = null;
        pool = null;
    }

    String url() {
        return url;
    }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(5000);
            String line = requestLine(s.getInputStream());
            String[] p = line == null ? new String[0] : line.split(" ");
            Reply r = p.length >= 2 && "GET".equals(p[0]) ? answer(p[1]) : new Reply(405, "text/plain", "no".getBytes());
            write(s.getOutputStream(), r);
        } catch (Exception ignored) {
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    static final class Reply {
        final int code;
        final String type;
        final byte[] body;

        Reply(int code, String type, byte[] body) {
            this.code = code;
            this.type = type;
            this.body = body;
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /** One request (path with its query): what to send back. Package-private for the tests. */
    Reply answer(String target) {
        String path = target, q = "";
        int i = target.indexOf('?');
        if (i >= 0) {
            path = target.substring(0, i);
            q = target.substring(i + 1);
        }
        Map<String, String> a = query(q);
        if (path.equals("/p/" + code)) {
            try (InputStream in = assets.open("games/pad.html")) {
                return new Reply(200, "text/html; charset=utf-8", readAll(in));
            } catch (Exception e) {
                return new Reply(500, "text/plain", "no page".getBytes());
            }
        }
        if (!code.equals(a.get("c"))) return new Reply(403, "text/plain", "wrong code".getBytes());
        String id = a.get("i");
        if (id == null || id.isEmpty() || id.length() > 40) return new Reply(400, "text/plain", "no id".getBytes());
        if (path.equals("/hello")) {
            int n = join(id);
            try {
                return new Reply(200, "application/json", new JSONObject().put("p", n).toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                return new Reply(500, "text/plain", "json".getBytes());
            }
        }
        if (path.equals("/k")) {
            String k = a.get("k");
            if (k == null || !KEYS.contains(k)) return new Reply(400, "text/plain", "key".getBytes());
            int n = join(id);
            if (n == 0) return new Reply(409, "text/plain", "two players already".getBytes());
            sink.key(k, n);
            return new Reply(204, "text/plain", new byte[0]);
        }
        return new Reply(404, "text/plain", "not found".getBytes());
    }

    /** This phone's player number: the one it has, else a free one (or one left quiet too long), else 0. */
    synchronized int join(String id) {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (String other : new ArrayList<>(slot.keySet())) {
            if (!other.equals(id) && now - seen.get(other) > IDLE_MS) {
                slot.remove(other);
                seen.remove(other);
                changed = true;
            }
        }
        Integer n = slot.get(id);
        if (n == null) {
            for (int p = 1; p <= 2 && n == null; p++) if (!slot.containsValue(p)) n = p;
            if (n != null) {
                slot.put(id, n);
                changed = true;
            }
        }
        if (n != null) seen.put(id, now);
        if (changed) sink.players(players());
        return n == null ? 0 : n;
    }

    synchronized List<Integer> players() {
        List<Integer> l = new ArrayList<>(slot.values());
        java.util.Collections.sort(l);
        return l;
    }

    static Map<String, String> query(String q) {
        Map<String, String> m = new HashMap<>();
        for (String kv : q.split("&")) {
            if (kv.isEmpty()) continue;
            int e = kv.indexOf('=');
            try {
                m.put(URLDecoder.decode(e < 0 ? kv : kv.substring(0, e), "UTF-8"), e < 0 ? "" : URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    private static String requestLine(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c, n = 0;
        String first = null;
        while ((c = in.read()) >= 0 && n++ < 8192) {   // the request line, then skip the headers up to the blank line
            if (c == '\n') {
                String l = b.toString("ISO-8859-1").trim();
                b.reset();
                if (first == null) first = l;
                else if (l.isEmpty()) break;
                continue;
            }
            b.write(c);
        }
        return first;
    }

    private static void write(OutputStream out, Reply r) throws java.io.IOException {
        String head = "HTTP/1.1 " + r.code + " " + (r.code < 300 ? "OK" : "Error") + "\r\n"
                + "Content-Type: " + r.type + "\r\nContent-Length: " + r.body.length + "\r\n"
                + "Cache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(r.body);
        out.flush();
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return o.toByteArray();
    }
}
