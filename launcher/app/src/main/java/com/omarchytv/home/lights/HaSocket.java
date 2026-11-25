package com.omarchytv.home.lights;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Iterator;

/**
 * v2.22 (#160) live entity states from Home Assistant's websocket API (auth, then subscribe_entities for the listed
 * ids), with a minimal RFC 6455 client: text frames only, masked as a client must, ping answered, nothing else.
 * Used only while an entities widget is on screen; {@link #close()} when it leaves. On any failure the caller falls
 * back to polling. The token is sent once in the auth message and never logged.
 */
public final class HaSocket implements Runnable {
    public interface Listener {
        /** entity → {state, unit?, name?}; called on the socket thread. */
        void onState(String entity, JSONObject brief);

        void onClosed(String why);
    }

    private final HomeAssistantClient ha;
    private final JSONArray ids;
    private final Listener l;
    private volatile Socket sock;
    private volatile boolean closed;
    private final SecureRandom rnd = new SecureRandom();

    public HaSocket(HomeAssistantClient ha, JSONArray entityIds, Listener l) {
        this.ha = ha;
        this.ids = entityIds;
        this.l = l;
    }

    public void start() {
        Thread t = new Thread(this, "ha-socket");
        t.setDaemon(true);
        t.start();
    }

    public void close() {
        closed = true;
        Socket s = sock;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void run() {
        String why = "closed";
        try {
            URI u = new URI(ha.wsUrl());
            boolean tls = "wss".equals(u.getScheme());
            int port = u.getPort() > 0 ? u.getPort() : tls ? 443 : 80;
            Socket s = tls ? javax.net.ssl.SSLSocketFactory.getDefault().createSocket() : new Socket();
            s.connect(new InetSocketAddress(u.getHost(), port), 4000);
            s.setSoTimeout(70_000);   // HA pings; a minute of silence means the link is gone
            sock = s;
            if (closed) return;
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            byte[] key = new byte[16];
            rnd.nextBytes(key);
            String hostHdr = u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
            String req = "GET " + u.getRawPath() + " HTTP/1.1\r\nHost: " + hostHdr + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + base64(key)
                    + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String status = readLine(in);
            if (!status.contains(" 101")) throw new Exception("upgrade " + status);
            while (!readLine(in).isEmpty()) {
                // headers
            }
            int nextId = 1;
            while (!closed) {
                String msg = readText(in, out);
                if (msg == null) break;
                JSONObject m = new JSONObject(msg);
                String type = m.optString("type");
                if ("auth_required".equals(type)) {
                    send(out, new JSONObject().put("type", "auth").put("access_token", ha.token).toString());
                } else if ("auth_invalid".equals(type)) {
                    why = "unauthorized";
                    break;
                } else if ("auth_ok".equals(type)) {
                    send(out, new JSONObject().put("id", nextId++).put("type", "subscribe_entities").put("entity_ids", ids).toString());
                } else if ("event".equals(type)) {
                    apply(m.optJSONObject("event"), l);
                } else if ("result".equals(type) && !m.optBoolean("success", true)) {
                    why = "rejected";
                    break;
                }
            }
        } catch (Exception e) {
            why = closed ? "closed" : HomeAssistantClient.errorOf(e);
        } finally {
            close();
            l.onClosed(why);
        }
    }

    /**
     * subscribe_entities events in HA's compressed form: "a" (added: {s, a}), "c" (changed: {"+": {s, a}}),
     * "r" (removed). Calls the listener with {state, unit?, name?} for each entity that has a state.
     */
    static void apply(JSONObject ev, Listener l) throws Exception {
        if (ev == null) return;
        for (String part : new String[]{"a", "c"}) {
            JSONObject o = ev.optJSONObject(part);
            if (o == null) continue;
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject e = o.optJSONObject(id);
                if (e == null) continue;
                if ("c".equals(part)) e = e.optJSONObject("+");
                if (e == null || !e.has("s")) continue;
                JSONObject b = new JSONObject().put("state", e.optString("s"));
                JSONObject attrs = e.optJSONObject("a");
                if (attrs != null) {
                    if (!attrs.optString("unit_of_measurement").isEmpty()) b.put("unit", attrs.optString("unit_of_measurement"));
                    if (!attrs.optString("friendly_name").isEmpty()) b.put("name", attrs.optString("friendly_name"));
                }
                l.onState(id, b);
            }
        }
    }

    private void send(OutputStream out, String text) throws Exception {
        byte[] p = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(0x81);
        if (p.length < 126) f.write(0x80 | p.length);
        else if (p.length < 65536) {
            f.write(0x80 | 126);
            f.write(p.length >> 8);
            f.write(p.length);
        } else {
            f.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) f.write((int) ((long) p.length >> (8 * i)));
        }
        byte[] mask = new byte[4];
        rnd.nextBytes(mask);
        f.write(mask);
        for (int i = 0; i < p.length; i++) f.write(p[i] ^ mask[i & 3]);
        synchronized (this) {
            out.write(f.toByteArray());
            out.flush();
        }
    }

    /** The next text message (fragments joined), answering pings; null when the server closes. At most 4 MB. */
    private String readText(InputStream in, OutputStream out) throws Exception {
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        while (true) {
            int b0 = in.read(), b1 = in.read();
            if (b0 < 0 || b1 < 0) return null;
            int op = b0 & 0x0F;
            long len = b1 & 0x7F;
            if (len == 126) len = (in.read() << 8) | in.read();
            else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
            }
            if (len > 4L * 1024 * 1024 || msg.size() + len > 4L * 1024 * 1024) throw new Exception("too_large");
            byte[] p = new byte[(int) len];
            int got = 0;
            while (got < len) {
                int n = in.read(p, got, (int) len - got);
                if (n < 0) return null;
                got += n;
            }
            if (op == 0x8) return null;   // close
            if (op == 0x9) {               // ping → pong with the same payload
                ByteArrayOutputStream f = new ByteArrayOutputStream();
                f.write(0x8A);
                f.write(0x80 | p.length);
                byte[] mask = new byte[4];
                rnd.nextBytes(mask);
                f.write(mask);
                for (int i = 0; i < p.length; i++) f.write(p[i] ^ mask[i & 3]);
                synchronized (this) {
                    out.write(f.toByteArray());
                    out.flush();
                }
                continue;
            }
            if (op == 0xA) continue;
            msg.write(p);
            if ((b0 & 0x80) != 0) return new String(msg.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Standard base64 with padding (the 16-byte handshake key); java.util.Base64 needs API 26. */
    static String base64(byte[] b) {
        String abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < b.length; i += 3) {
            int n = (b[i] & 0xFF) << 16 | (i + 1 < b.length ? (b[i + 1] & 0xFF) << 8 : 0) | (i + 2 < b.length ? b[i + 2] & 0xFF : 0);
            s.append(abc.charAt(n >> 18 & 63)).append(abc.charAt(n >> 12 & 63))
                    .append(i + 1 < b.length ? abc.charAt(n >> 6 & 63) : '=').append(i + 2 < b.length ? abc.charAt(n & 63) : '=');
        }
        return s.toString();
    }

    private static String readLine(InputStream in) throws Exception {
        StringBuilder b = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.append((char) c);
            if (b.length() > 8192) throw new Exception("header too long");
        }
        return b.toString();
    }
}
