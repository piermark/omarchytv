package com.omarchytv.home.link;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** The server end to end over loopback, with a tiny Java PC client. */
public class LinkServerTest {

    File dir;
    FakeHost host;
    LinkServer server;
    int port;
    String pcId = "abcdef0123456789";
    byte[] token;
    String tvId;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("otv-link-test").toFile();
        host = new FakeHost(dir);
        server = new LinkServer(host, new LinkStore(new File(dir, "link.json")));
        host.server = server;
        port = server.start(InetAddress.getLoopbackAddress(), 0);
    }

    @After
    public void tearDown() {
        server.shutdown();
    }

    static final class Resp {
        int status;
        Map<String, String> headers = new HashMap<>();
        byte[] body;

        JSONObject json() throws Exception {
            return new JSONObject(new String(body, StandardCharsets.UTF_8));
        }
    }

    Resp http(String method, String target, byte[] body, Map<String, String> headers) throws Exception {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(5000);
            boolean ownHost = headers != null && headers.containsKey("Host");
            StringBuilder h = new StringBuilder(method + " " + target + " HTTP/1.1\r\n" + (ownHost ? "" : "Host: tv\r\n") + "Connection: close\r\n");
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            h.append("Content-Length: ").append(body.length).append("\r\n\r\n");
            OutputStream out = s.getOutputStream();
            out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) all.write(buf, 0, n);
            byte[] raw = all.toByteArray();
            String text = new String(raw, StandardCharsets.ISO_8859_1);
            int end = text.indexOf("\r\n\r\n");
            Resp r = new Resp();
            String[] lines = text.substring(0, end).split("\r\n");
            r.status = Integer.parseInt(lines[0].split(" ")[1]);
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                r.headers.put(lines[i].substring(0, c).toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
            }
            r.body = java.util.Arrays.copyOfRange(raw, end + 4, raw.length);
            return r;
        }
    }

    Resp post(String target, String json) throws Exception {
        return http("POST", target, json.getBytes(StandardCharsets.UTF_8), null);
    }

    void pair(String code) throws Exception {
        LinkCrypto.PcPairing a = new LinkCrypto.PcPairing(code, pcId, null);
        Resp start = post("/v1/pair/start", new JSONObject().put("protocol", 1).put("pcId", pcId).put("pcName", "omarchy-box")
                .put("t", LinkCrypto.b64u(a.message())).toString());
        assertEquals(200, start.status);
        JSONObject s = start.json();
        tvId = s.getString("tvId");
        LinkCrypto.Keys k = a.finish(tvId, LinkCrypto.unb64u(s.getString("s")));
        // pair/start and pair/confirm have separate 1 req / 2 s buckets so confirm succeeds immediately
        Resp done = post("/v1/pair/confirm", new JSONObject().put("pairingId", s.getString("pairingId"))
                .put("confirm", LinkCrypto.b64u(k.confirmPc)).toString());
        if (done.status == 200) {
            assertTrue(LinkCrypto.equal(LinkCrypto.unb64u(done.json().getString("confirm")), k.confirmTv));
            token = k.token;
        } else {
            throw new AssertionError("confirm " + done.status + " " + new String(done.body, StandardCharsets.UTF_8));
        }
    }

    Resp signed(String method, String target, String body, String nonce, long ts) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String sig = LinkCrypto.signRequest(token, method, target, String.valueOf(ts), nonce, b);
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "OTV1 pc=" + pcId + ", ts=" + ts + ", nonce=" + nonce + ", sig=" + sig);
        return http(method, target, b, h);
    }

    Resp signed(String method, String target, String body) throws Exception {
        return signed(method, target, body, LinkCrypto.b64u(LinkCrypto.randomBytes(16)), System.currentTimeMillis() / 1000);
    }

    void assertSigned(Resp r, String nonce) {
        assertEquals(r.headers.get("x-otv-sig"), LinkCrypto.signResponse(token, nonce, r.status, r.body));
    }

    @Test
    public void infoAndNoUnauthenticatedEndpoints() throws Exception {
        Resp info = http("GET", "/v1/info", new byte[0], null);
        assertEquals(200, info.status);
        assertEquals(5, info.json().length());
        assertEquals("no-store", info.headers.get("cache-control"));
        assertNotNull(info.headers.get("x-otv-time"));
        for (String[] e : new String[][]{{"GET", "/v1/status"}, {"POST", "/v1/remote/key"}, {"PUT", "/v1/ai"}, {"GET", "/v1/nope"}}) {
            assertEquals(e[1], 401, http(e[0], e[1], "{}".getBytes(), null).status);
        }
    }

    @Test
    public void pairingSignedRequestsReplaySkew() throws Exception {
        pair("123456");
        assertEquals(1, host.codes);   // a code pairs one PC
        JSONObject cfg = host.readConfig();
        assertEquals("omarchy-box", cfg.getJSONObject("link").getJSONArray("pcs").getJSONObject(0).getString("name"));
        String text = cfg.toString();
        assertFalse(text.contains(LinkCrypto.b64u(token)));
        assertFalse(text.contains(java.util.Base64.getEncoder().encodeToString(token)));

        String nonce = LinkCrypto.b64u(LinkCrypto.randomBytes(16));
        long ts = System.currentTimeMillis() / 1000;
        Resp r = signed("POST", "/v1/remote/key", "{\"key\":\"home\"}", nonce, ts);
        assertEquals(200, r.status);
        assertSigned(r, nonce);
        assertEquals("replay", signed("POST", "/v1/remote/key", "{\"key\":\"home\"}", nonce, ts).json().getString("error"));
        assertEquals("clock_skew", signed("GET", "/v1/status", "", "n1", ts - 600).json().getString("error"));
        // tampered body: signature over another body
        String sig = LinkCrypto.signRequest(token, "POST", "/v1/remote/key", String.valueOf(ts), "n2", "{\"key\":\"home\"}".getBytes());
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "OTV1 pc=" + pcId + ", ts=" + ts + ", nonce=n2, sig=" + sig);
        assertEquals(401, http("POST", "/v1/remote/key", "{\"key\":\"mute\"}".getBytes(), h).status);

        Resp bad = signed("POST", "/v1/remote/key", "{\"key\":\"reboot\"}");
        assertEquals(400, bad.status);
        assertNotNull(bad.headers.get("x-otv-sig"));
        assertEquals(409, signed("POST", "/v1/remote/key", "{\"key\":\"back\"}").status);
        assertEquals(404, signed("GET", "/v1/nope", "").status);
        assertEquals(200, signed("GET", "/v1/status", "").status);
    }

    @Test
    public void wrongCodeRotatesAfterFive() throws Exception {
        for (int i = 0; i < 5; i++) {
            LinkCrypto.PcPairing a = new LinkCrypto.PcPairing("000000", pcId, null);
            Resp start = post("/v1/pair/start", new JSONObject().put("pcId", pcId).put("t", LinkCrypto.b64u(a.message())).toString());
            assertEquals(200, start.status);
            Thread.sleep(2050);
            JSONObject s = start.json();
            LinkCrypto.Keys k = a.finish(s.getString("tvId"), LinkCrypto.unb64u(s.getString("s")));
            Resp done = post("/v1/pair/confirm", new JSONObject().put("pairingId", s.getString("pairingId"))
                    .put("confirm", LinkCrypto.b64u(k.confirmPc)).toString());
            assertEquals(401, done.status);
            assertEquals(4 - i, done.json().getInt("attemptsLeft"));
            Thread.sleep(2050);
        }
        assertEquals(1, host.codes);
        host.pairingOpen = false;
        assertEquals("pairing_closed", post("/v1/pair/start", "{}").json().getString("error"));
    }

    @Test
    public void keepAliveFromThePc() throws Exception {   // v2.21: omarchy-link tv keepalive auto|on|off
        pair("123456");
        assertEquals(200, signed("PUT", "/v1/link/keepalive", "{\"mode\":\"on\"}").status);
        assertTrue(host.readConfig().getJSONObject("link").getBoolean("keepAlive"));
        assertEquals(200, signed("PUT", "/v1/link/keepalive", "{\"mode\":\"off\"}").status);
        assertFalse(host.readConfig().getJSONObject("link").getBoolean("keepAlive"));
        assertEquals(200, signed("PUT", "/v1/link/keepalive", "{\"mode\":\"auto\"}").status);
        assertFalse(host.readConfig().getJSONObject("link").has("keepAlive"));
        assertEquals(400, signed("PUT", "/v1/link/keepalive", "{\"mode\":\"always\"}").status);
    }

    @Test
    public void widgetPaneOptionalAt() throws Exception {
        pair("123456");
        long now = System.currentTimeMillis() / 1000;
        String body = "{\"panes\":[{\"id\":\"cpu\",\"title\":\"cpu\",\"at\":" + (now - 30) + ",\"lines\":[{\"label\":\"load\",\"value\":\"1\"}]},"
                + "{\"id\":\"old\",\"lines\":[]},{\"id\":\"future\",\"at\":" + (now + 86_400) + "}],\"shortcuts\":[]}";
        assertEquals(200, signed("PUT", "/v1/widgets", body).status);
        org.json.JSONArray p = host.readConfig().getJSONObject("link").getJSONArray("panes");
        assertEquals(now - 30, p.getJSONObject(0).getLong("at"));
        assertFalse(p.getJSONObject(1).has("at"));   // backward compatible: no "at" = widgetsAt
        assertFalse(p.getJSONObject(2).has("at"));   // a clock far in the future is ignored
    }

    @Test
    public void cleanPanesKeepsKnownTonesOnly() throws Exception {
        org.json.JSONArray in = new org.json.JSONArray("[{\"id\":\"rates\",\"lines\":[{\"label\":\"US 10Y\",\"value\":\"4.99%\",\"tone\":\"up\"},"
                + "{\"label\":\"WTI\",\"value\":\"$100\",\"tone\":\"down\"},{\"label\":\"x\",\"value\":\"y\",\"tone\":\"<b>\"}]}]");
        String[] why = new String[2];
        org.json.JSONArray l = LinkServer.cleanPanes(in, System.currentTimeMillis() / 1000, why).getJSONObject(0).getJSONArray("lines");
        assertEquals("up", l.getJSONObject(0).getString("tone"));
        assertEquals("down", l.getJSONObject(1).getString("tone"));
        assertFalse(l.getJSONObject(2).has("tone"));
        assertEquals(null, LinkServer.cleanPanes(new org.json.JSONArray("[{\"id\":\"Bad Id\"}]"), 0, why));
        assertEquals("bad_widgets", why[0]);
    }

    @Test
    public void customBanners() throws Exception {
        pair("123456");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0};
        assertEquals(200, signedBytes("PUT", "/v1/banners/com.netflix.ninja", png, "image/png").status);
        java.io.File f = new java.io.File(host.wallpaperDir().getParentFile(), "banners/com.netflix.ninja.png");
        assertTrue(f.isFile());
        assertEquals("com.netflix.ninja", signed("GET", "/v1/banners", "").json().getJSONArray("banners").getString(0));
        assertEquals(415, signedBytes("PUT", "/v1/banners/com.netflix.ninja", "nope".getBytes(), "image/png").status);
        assertEquals(404, signed("PUT", "/v1/banners/..evil", "").status);   // not a package name: no route
        assertEquals(200, signed("DELETE", "/v1/banners/com.netflix.ninja", "").status);
        assertFalse(f.exists());
        assertEquals(404, signed("DELETE", "/v1/banners/com.netflix.ninja", "").status);
        assertTrue(host.events.stream().anyMatch(e -> e.startsWith("banners ")));
    }

    @Test
    public void themeForAppsNeedsNoPairingHasEtagCorsAndCanBeTurnedOff() throws Exception {
        server.limits = false;
        host.theme = new JSONObject().put("schema", "omarchytv-theme/1").put("name", "tokyo-night").put("background", "#1a1b26");
        Resp r = http("GET", "/v1/theme", new byte[0], null);
        assertEquals(200, r.status);
        assertEquals("tokyo-night", r.json().getString("name"));
        assertEquals("*", r.headers.get("access-control-allow-origin"));
        assertEquals("no-cache", r.headers.get("cache-control"));
        String etag = r.headers.get("etag");
        assertNotNull(etag);
        Map<String, String> h = new HashMap<>();
        h.put("If-None-Match", etag);
        Resp same = http("GET", "/v1/theme?x=1", new byte[0], h);
        assertEquals(304, same.status);
        assertEquals(0, same.body.length);
        // a new theme, a new tag
        host.theme.put("name", "gruvbox");
        assertEquals(200, http("GET", "/v1/theme", new byte[0], h).status);
        // CORS preflight
        Resp pre = http("OPTIONS", "/v1/theme", new byte[0], null);
        assertEquals(204, pre.status);
        assertTrue(pre.headers.get("access-control-allow-headers").contains("If-None-Match"));
        // the wallpaper, served as the image it is
        assertEquals(404, http("GET", "/v1/theme/wallpaper", new byte[0], null).status);
        byte[] img = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
        host.wallpaper = new LinkServer.Blob(img, "image/jpeg", "abc123");
        Resp wp = http("GET", "/v1/theme/wallpaper", new byte[0], null);
        assertEquals(200, wp.status);
        assertEquals("image/jpeg", wp.headers.get("content-type"));
        assertArrayEquals(img, wp.body);
        assertEquals("\"abc123\"", wp.headers.get("etag"));
        // writing the theme still needs a paired, signed PC
        assertEquals(401, http("PUT", "/v1/theme", "{}".getBytes(), null).status);
        // the owner turned sharing off
        host.theme = null;
        Resp off = http("GET", "/v1/theme", new byte[0], null);
        assertEquals(403, off.status);
        assertEquals("theme_sharing_off", off.json().getString("error"));
    }

    @Test
    public void themeForAppsIsLanOnlyAndRateLimited() throws Exception {
        host.theme = new JSONObject().put("name", "tokyo-night");
        int ok = 0, limited = 0;
        for (int i = 0; i < 12; i++) {
            int st = http("GET", "/v1/theme", new byte[0], null).status;
            if (st == 200) ok++;
            if (st == 429) limited++;
        }
        assertEquals(10, ok);
        assertEquals(2, limited);
        host.allowLoopback = false;
        assertEquals(403, http("GET", "/v1/theme", new byte[0], null).status);
    }

    @Test
    public void notLanIsRefused() throws Exception {
        host.allowLoopback = false;
        Resp r = http("GET", "/v1/info", new byte[0], null);
        assertEquals(403, r.status);
        assertEquals("not_lan", r.json().getString("error"));
    }

    @Test
    public void aiNullsAndSources() throws Exception {
        pair("123456");
        server.limits = false;   // three PUT /v1/ai in a row (1 per 5 s otherwise)
        String body = "{\"ai\":[{\"name\":\"Claude 5h\",\"used\":null,\"limit\":null,\"unit\":\"%\"}],"
                + "\"aiSources\":[{\"id\":\"omarchy-agents\",\"name\":\"Omarchy Agents panel\",\"enabled\":true,\"available\":true},"
                + "{\"id\":\"omacash\",\"name\":\"Omacash\",\"enabled\":false}]}";
        assertEquals(200, signed("PUT", "/v1/ai", body).status);
        JSONObject cfg = host.readConfig();
        assertTrue(cfg.getJSONArray("ai").getJSONObject(0).isNull("used"));   // verbatim: the TV shows "?"
        assertEquals("omacash", cfg.getJSONArray("aiSources").getJSONObject(1).getString("id"));
        assertFalse(cfg.getJSONArray("aiSources").getJSONObject(1).getBoolean("enabled"));
        // a bad source id is refused; without aiSources the list (and the TV switches) go away
        assertEquals(400, signed("PUT", "/v1/ai", "{\"ai\":[],\"aiSources\":[{\"id\":\"rm -rf\"}]}").status);
        assertEquals(200, signed("PUT", "/v1/ai", "{\"ai\":[]}").status);
        assertFalse(host.readConfig().has("aiSources"));
        assertEquals("not_connected", server.aiSources(new JSONObject().put("id", "x").put("enabled", new org.json.JSONArray())));
    }

    static int threads(String name) {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) if (t.isAlive() && t.getName().equals(name)) n++;
        return n;
    }

    /**
     * PC off / never there: the server is passive. Binding is quick, nothing is scheduled (no ping timer, no
     * pool thread), and plain HTTP traffic does not start a timer either: only an open event socket does.
     */
    @Test
    public void idleWithoutPcIsPassive() throws Exception {
        long t0 = System.nanoTime();
        LinkServer s2 = new LinkServer(host, new LinkStore(new File(dir, "idle.json")));
        s2.start(java.net.InetAddress.getLoopbackAddress(), 0);
        long startMs = (System.nanoTime() - t0) / 1_000_000;
        try {
            System.out.println("BENCH LinkServer construct+bind (no PC): " + startMs + " ms");
            assertTrue("bind took " + startMs + " ms", startMs < 250);
            Thread.sleep(300);
            assertTrue(s2.idle());
            assertEquals(0, threads("otv-link-ping"));
            assertEquals("not_connected", s2.ask(new JSONObject().put("id", "q").put("text", "hi")));   // instant
            pair("123456");                       // HTTP only on the other server
            assertTrue(server.idle());
            assertEquals(0, threads("otv-link-ping"));
        } finally {
            s2.shutdown();
        }
    }

    @Test
    public void bodyLimit() throws Exception {
        assertEquals(413, http("PUT", "/v1/ai", new byte[64 * 1024 + 1], null).status);
    }

    // ---- cast (PROTOCOL.md §6 Cast)

    Resp signedBytes(String method, String target, byte[] b, String contentType) throws Exception {
        String nonce = LinkCrypto.b64u(LinkCrypto.randomBytes(16));
        long ts = System.currentTimeMillis() / 1000;
        String sig = LinkCrypto.signRequest(token, method, target, String.valueOf(ts), nonce, b);
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "OTV1 pc=" + pcId + ", ts=" + ts + ", nonce=" + nonce + ", sig=" + sig);
        h.put("Content-Type", contentType);
        return http(method, target, b, h);
    }

    @Test
    public void castValidationUploadAndControl() throws Exception {
        host.castPlay = false;
        host.castDir = new File(dir, "cache-cast");
        pair("123456");
        server.limits = false;
        JSONObject st = signed("GET", "/v1/status", "").json();
        assertTrue(st.getJSONArray("capabilities").toString().contains("\"cast-upload\""));
        assertEquals(6, st.getJSONArray("castKinds").length());
        assertEquals(64L << 20, st.getLong("castUploadMax"));

        String[][] bad = {
                {"{\"id\":\"a\",\"kind\":\"url\",\"url\":\"javascript:alert(1)\"}", "400", "bad_cast"},
                {"{\"id\":\"a\",\"kind\":\"url\",\"url\":\"intent://x#Intent;end\"}", "400", "bad_cast"},
                {"{\"id\":\"a\",\"kind\":\"video\",\"url\":\"file:///sdcard/x.mp4\"}", "400", "bad_cast"},
                {"{\"id\":\"a\",\"kind\":\"video\",\"url\":\"content://media/1\"}", "400", "bad_cast"},
                {"{\"id\":\"a\",\"kind\":\"url\",\"url\":\"https://ex ample.org/\"}", "400", "bad_cast"},
                {"{\"id\":\"a\",\"kind\":\"youtube\",\"videoId\":\"short\"}", "400", "bad_cast"},
                {"{\"id\":\"a\",\"kind\":\"youtube\",\"videoId\":\"dQw4w9WgXc&\"}", "400", "bad_cast"},
                {"{\"id\":\"\",\"kind\":\"text\",\"text\":\"hi\"}", "400", "bad_cast"},
                {"{\"id\":\"a\",\"kind\":\"apk\"}", "415", "unsupported_kind"},
                {"{\"id\":\"a\",\"kind\":\"image\",\"file\":\"" + "0".repeat(64) + "\"}", "404", "no_such_file"},
                {"{\"id\":\"a\",\"kind\":\"image\",\"file\":\"../../link.json\"}", "400", "bad_cast"},
                {"{\"id\":\"a\",\"kind\":\"video\"}", "400", "bad_cast"},
        };
        for (String[] b : bad) {
            Resp r = signed("POST", "/v1/cast", b[0]);
            assertEquals(b[0], Integer.parseInt(b[1]), r.status);
            assertEquals(b[0], b[2], r.json().getString("error"));
            assertNotNull(r.headers.get("x-otv-sig"));
        }
        String big = "x".repeat(32 * 1024 + 1);
        assertEquals(400, signed("POST", "/v1/cast", new JSONObject().put("id", "t").put("kind", "text").put("text", big).toString()).status);
        assertEquals(409, signed("POST", "/v1/cast/control", "{\"action\":\"pause\"}").status);   // nothing_playing

        // upload: streamed to the private cache, answered with its sha256
        byte[] img = new byte[200_000];
        new java.util.Random(7).nextBytes(img);
        System.arraycopy(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}, 0, img, 0, 8);
        Resp up = signedBytes("PUT", "/v1/cast/file", img, "image/png");
        assertEquals(200, up.status);
        String sha = LinkCrypto.hex(LinkCrypto.sha256(img));
        assertEquals(sha, up.json().getString("file"));
        assertEquals(img.length, up.json().getLong("size"));
        assertTrue(java.util.Arrays.equals(img, Files.readAllBytes(new File(host.castDir, sha).toPath())));
        String[] left = host.castDir.list();
        assertEquals(1, left.length);   // no temp files left behind

        // a stranger (unknown pc) cannot make the TV write: refused before the body is read
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "OTV1 pc=zzzzzzzzzzzzzzzz, ts=" + System.currentTimeMillis() / 1000 + ", nonce=n, sig=AAAA");
        assertEquals(401, http("PUT", "/v1/cast/file", new byte[1000], h).status);
        // a paired PC with a body that does not match its signature: 401, nothing stored
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        h.put("Authorization", "OTV1 pc=" + pcId + ", ts=" + ts + ", nonce=n9, sig="
                + LinkCrypto.signRequest(token, "PUT", "/v1/cast/file", ts, "n9", new byte[10]));
        assertEquals(401, http("PUT", "/v1/cast/file", new byte[11], h).status);
        assertEquals(1, host.castDir.list().length);
        server.castUploadMax = 1000;
        assertEquals(413, signedBytes("PUT", "/v1/cast/file", new byte[1001], "video/mp4").status);
        server.castUploadMax = 64L << 20;

        Resp c1 = signed("POST", "/v1/cast", new JSONObject().put("id", "one").put("kind", "image").put("file", sha).put("title", "Photo\u0007").toString());
        assertEquals(200, c1.status);
        assertEquals("viewer", c1.json().getString("app"));
        assertEquals("opening", c1.json().getString("state"));
        Resp c2 = signed("POST", "/v1/cast", "{\"id\":\"two\",\"kind\":\"youtube\",\"videoId\":\"dQw4w9WgXcQ\",\"url\":\"https://evil.example/\"}");
        assertEquals("youtube", c2.json().getString("app"));
        assertEquals("two", server.currentCast());
        String last = new String(Files.readAllBytes(new File(dir, "casts.jsonl").toPath()), StandardCharsets.UTF_8).trim();
        JSONObject yt = new JSONObject(last.substring(last.lastIndexOf('\n') + 1));
        assertFalse(yt.has("url"));   // only the videoId reaches the YouTube intent
        assertEquals(200, signed("POST", "/v1/cast/control", "{\"action\":\"seek\",\"position\":12.5}").status);
        assertEquals(409, signed("POST", "/v1/cast/control", "{\"action\":\"pause\",\"id\":\"one\"}").status);   // replaced
        assertEquals(400, signed("POST", "/v1/cast/control", "{\"action\":\"eject\"}").status);
        assertEquals(200, signed("POST", "/v1/cast/control", "{\"action\":\"stop\"}").status);
        assertEquals(null, server.currentCast());
        assertEquals(409, signed("POST", "/v1/cast/control", "{\"action\":\"play\"}").status);

        host.castOn = false;   // a launcher that cannot cast right now
        assertFalse(signed("GET", "/v1/status", "").json().getJSONArray("capabilities").toString().contains("cast"));
        assertEquals(409, signed("POST", "/v1/cast", "{\"id\":\"x\",\"kind\":\"text\",\"text\":\"hi\"}").status);
    }

    @Test
    public void castCacheSweep() throws Exception {
        File d = Files.createTempDirectory("otv-cast").toFile();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            File f = new File(d, String.format("%064x", i));
            Files.write(f.toPath(), new byte[100]);
            f.setLastModified(now - (10 - i) * 60_000L);
        }
        File old = new File(d, "f".repeat(64));
        Files.write(old.toPath(), new byte[1]);
        old.setLastModified(now - 25 * 3_600_000L);
        Cast.sweep(d, now, String.format("%064x", 0), 8, 1L << 30);
        assertFalse(old.exists());                                         // older than 24 h
        assertTrue(new File(d, String.format("%064x", 0)).exists());       // the open cast's file is kept
        assertFalse(new File(d, String.format("%064x", 1)).exists());      // oldest first
        assertEquals(8, d.list().length);
        Cast.sweep(d, now, null, 8, 250);
        assertEquals(2, d.list().length);                                  // byte cap
    }

    @Test
    public void themePushSetsSanitisedPrompt() throws Exception {
        pair("123456");
        JSONObject t = new JSONObject().put("name", "tokyo-night").put("accent", "#7aa2f7").put("accent2", "#bb9af7")
                .put("prompt", new JSONObject().put("user", "Jane Doe!").put("host", "omarchy-box.local.lan.example"));
        assertEquals(200, signed("PUT", "/v1/theme", t.toString()).status);
        JSONObject p = host.readConfig().getJSONObject("prompt");
        assertEquals("janedoe", p.getString("user"));
        assertEquals("omarchy-box.loca", p.getString("host"));   // 16 characters at most
    }

    @Test
    public void agentsPutIsBoundedPlainTextAndServed() throws Exception {
        pair("123456");
        assertTrue(server.status().getJSONArray("capabilities").toString().contains("\"agents\""));
        org.json.JSONArray many = new org.json.JSONArray();
        for (int i = 0; i < 9; i++) many.put(new JSONObject().put("id", "a" + i));
        assertEquals(400, signed("PUT", "/v1/agents", new JSONObject().put("agents", many).toString()).status);
        StringBuilder longStep = new StringBuilder();
        for (int i = 0; i < 100; i++) longStep.append('x');
        org.json.JSONArray steps = new org.json.JSONArray();
        for (int i = 0; i < 10; i++) steps.put(new JSONObject().put("text", "s" + i).put("status", i == 9 ? "weird" : "ok"));
        JSONObject a = new JSONObject().put("id", "claude-code:7d0c6a2e-111").put("agent", "claude-code").put("agentName", "Claude\u0007")
                .put("project", "shop").put("status", "nonsense").put("step", longStep.toString()).put("question", "<b>ok?</b>")
                .put("since", "2026-10-19T08:11:58Z").put("elapsed", 12).put("needsInput", true).put("canAsk", true)
                .put("cwd", "/home/someone").put("steps", steps);
        JSONObject body = new JSONObject().put("at", "2026-10-19T08:12:00Z").put("enabled", true)
                .put("agents", new org.json.JSONArray().put(a).put(new JSONObject().put("agentName", "no id")));
        assertEquals(200, signed("PUT", "/v1/agents", body.toString()).status);
        JSONObject stored = host.readConfig().getJSONObject("link").getJSONObject("agents");
        assertTrue(host.readConfig().getJSONObject("link").getLong("agentsAt") > 0);
        assertEquals(1, stored.getJSONArray("agents").length());   // the entry without an id is dropped
        JSONObject g = stored.getJSONArray("agents").getJSONObject(0);
        assertEquals("Claude", g.getString("agentName"));
        assertEquals("idle", g.getString("status"));
        assertEquals(72, g.getString("step").length());
        assertEquals("<b>ok?</b>", g.getString("question"));   // kept as plain text, never rendered as HTML
        assertFalse(g.has("cwd"));
        assertEquals(8, g.getJSONArray("steps").length());
        assertEquals("s2", g.getJSONArray("steps").getJSONObject(0).getString("text"));
        assertEquals("ok", g.getJSONArray("steps").getJSONObject(7).getString("status"));
        assertEquals("claude-code:7d0c6a2e-111", new JSONObject(new String(signed("GET", "/v1/agents", "").body, StandardCharsets.UTF_8))
                .getJSONArray("agents").getJSONObject(0).getString("id"));
        assertTrue(host.events.stream().anyMatch(e -> e.startsWith("agent_activity ")));
    }

    @Test
    public void officeHireIsRefusedLocallyWithoutAPcOrForGrok() throws Exception {
        assertEquals("bad_request", server.officeHire(new JSONObject().put("id", "h1").put("name", "X").put("provider", "grok")));
        assertEquals("bad_request", server.officeHire(new JSONObject().put("name", "X").put("provider", "claude")));
        assertEquals("not_connected", server.officeHire(new JSONObject().put("id", "h1").put("name", "X").put("provider", "claude")));
        assertTrue(server.status().getJSONArray("capabilities").toString().contains("\"office\""));
    }

    @Test
    public void officePushIsCleanedStoredAndReadBack() throws Exception {
        pair("123456");
        org.json.JSONArray many = new org.json.JSONArray();
        for (int i = 0; i < 25; i++) many.put(new JSONObject().put("name", "b" + i));
        assertEquals(400, signed("PUT", "/v1/office", new JSONObject().put("bots", many).toString()).status);
        JSONObject body = new JSONObject().put("bots", new org.json.JSONArray()
                .put(new JSONObject().put("name", "Trimmer").put("role", "trims tokens").put("status", "nonsense"))
                .put(new JSONObject().put("role", "no name")))
                .put("usage", new JSONObject().put("weekly_pct", 140));
        assertEquals(200, signed("PUT", "/v1/office", body.toString()).status);
        JSONObject got = new JSONObject(new String(signed("GET", "/v1/office", "").body, StandardCharsets.UTF_8));
        assertEquals(1, got.getJSONArray("bots").length());   // the bot without a name is dropped
        assertEquals("Trimmer", got.getJSONArray("bots").getJSONObject(0).getString("name"));
        assertEquals("idle", got.getJSONArray("bots").getJSONObject(0).getString("status"));
        assertFalse(got.has("usage"));   // 140% is not a real weekly usage: no card rather than a made-up number
    }

    @Test
    public void agentAskRefusesLocally() throws Exception {
        assertEquals("bad_request", server.agentAsk("r1", "claude-code:1", "   "));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 2001; i++) big.append('a');
        assertEquals("bad_request", server.agentAsk("r1", "claude-code:1", big.toString()));
        assertEquals("not_connected", server.agentAsk("r1", "claude-code:1", "hi"));
    }

    @Test
    public void agentTimersAndTimes() {
        assertEquals(1760861518000L, Agents.isoMs("2025-10-19T08:11:58Z"));
        assertEquals(1760861518250L, Agents.isoMs("2025-10-19T08:11:58.25Z"));
        assertEquals(-1, Agents.isoMs("yesterday"));
        assertEquals("42s", Agents.span(42));
        assertEquals("2m", Agents.span(150));
        assertEquals("3h", Agents.span(3 * 3600 + 5));
        Agents a = new Agents();
        a.rx = 1_000_000;
        Agents.Agent run = new Agents.Agent();
        run.status = "running";
        run.elapsed = 10;
        Agents.Agent done = new Agents.Agent();
        done.status = "done";
        done.elapsed = 10;
        assertEquals(15, a.elapsedNow(run, 1_005_000));
        assertEquals(10, a.elapsedNow(done, 1_005_000));
    }

    @Test
    public void notifyNeedsPairingTheOwnersSwitchAndStaysShort() throws Exception {
        assertEquals(401, http("POST", "/v1/notify", "{\"text\":\"hi\"}".getBytes(StandardCharsets.UTF_8), null).status);
        pair("123456");
        server.limits = false;
        Resp off = signed("POST", "/v1/notify", "{\"text\":\"build done\"}");
        assertEquals(403, off.status);
        assertEquals("notify_off", off.json().getString("error"));
        host.notifyOn = true;
        assertEquals(400, signed("POST", "/v1/notify", "{\"text\":\"  \"}").status);
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 50; i++) longText.append("words ");
        Resp ok = signed("POST", "/v1/notify", new JSONObject().put("text", "line one\nline two " + longText).put("from", "Claude Code").toString());
        assertEquals(200, ok.status);
        String shown = host.notes.get(0);
        assertTrue(shown.startsWith("Claude Code: line one line two"));
        assertTrue(shown.length() <= "Claude Code: ".length() + 200);
        assertEquals(1, host.notes.size());
        // MCP: tv_notify and tv_get_theme
        host.theme = new JSONObject().put("schema", "omarchytv-theme/1").put("accent", "#7aa2f7");
        Resp list = signed("POST", "/v1/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        String names = list.json().getJSONObject("result").getJSONArray("tools").toString();
        assertTrue(names.contains("tv_notify") && names.contains("tv_get_theme"));
        Resp call = signed("POST", "/v1/mcp", new JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/call")
                .put("params", new JSONObject().put("name", "tv_notify").put("arguments", new JSONObject().put("text", "tests pass").put("from", "Hermes"))).toString());
        assertFalse(call.json().getJSONObject("result").getBoolean("isError"));
        assertEquals("Hermes: tests pass", host.notes.get(1));
        // v2.21 title and level: info waits while a film is on, alert shows at once
        host.watching = true;
        Resp held = signed("POST", "/v1/notify", "{\"title\":\"Build\",\"text\":\"green\",\"level\":\"info\"}");
        assertTrue(held.json().getBoolean("deferred"));
        Resp now = signed("POST", "/v1/notify", "{\"text\":\"prod is down\",\"level\":\"alert\"}");
        assertTrue(now.json().getBoolean("shown"));
        assertEquals("PC: Build · green", host.notes.get(2));
        assertTrue(host.spoken.isEmpty());
        signed("POST", "/v1/notify", "{\"title\":\"Deploy\",\"text\":\"done\",\"level\":\"warn\",\"speak\":true}");
        assertEquals("Deploy. done", host.spoken.get(0));
        signed("POST", "/v1/notify", "{\"text\":\"quiet\",\"speak\":true}");   // held while watching: not spoken
        assertEquals(1, host.spoken.size());
        host.watching = false;
        Resp th = signed("POST", "/v1/mcp", new JSONObject().put("jsonrpc", "2.0").put("id", 3).put("method", "tools/call")
                .put("params", new JSONObject().put("name", "tv_get_theme")).toString());
        String txt = th.json().getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text");
        assertEquals("#7aa2f7", new JSONObject(txt).getString("accent"));
    }

    // ---- v2.21 app API ----

    String appToken(String name, String scope) throws Exception {
        Resp r = signed("POST", "/v1/tokens", new JSONObject().put("name", name).put("scope", scope).toString());
        assertEquals(200, r.status);
        assertTrue(r.json().getString("token").startsWith("otva_"));
        return r.json().getString("token");
    }

    Resp app(String method, String target, String body, String tok) throws Exception {
        Map<String, String> h = new HashMap<>();
        if (tok != null) h.put("Authorization", "Bearer " + tok);
        return http(method, target, body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8), h);
    }

    @Test
    public void appTokensAreMadeByAPairedPcAndScoped() throws Exception {
        pair("123456");
        server.limits = false;
        assertEquals(401, app("POST", "/v1/tokens", "{\"name\":\"x\"}", null).status);   // not without a PC
        String read = appToken("Dashboard", "read"), ctl = appToken("Hermes", "control");
        Resp list = signed("GET", "/v1/tokens", "");
        assertEquals(2, list.json().getJSONArray("apps").length());
        assertFalse(list.toString().contains("otva_"));
        assertFalse(new String(list.body, StandardCharsets.UTF_8).contains(read));   // never shown again
        // read: state yes, notify no
        Resp st = app("GET", "/v1/state", null, read);
        assertEquals(200, st.status);
        assertEquals("home", st.json().getString("screen"));
        assertEquals("*", st.headers.get("access-control-allow-origin"));
        assertEquals(200, app("GET", "/v1/state?token=" + read, null, null).status);   // EventSource-style
        Resp no = app("POST", "/v1/notify", "{\"text\":\"x\"}", read);
        assertEquals(403, no.status);
        assertEquals("scope", no.json().getString("error"));
        assertEquals(403, app("GET", "/v1/diagnostics", null, read).status);
        // an app cannot manage tokens
        assertEquals(401, app("GET", "/v1/tokens", null, read).status);
        // control: diagnostics, page
        Resp d = app("GET", "/v1/diagnostics", null, ctl);
        assertEquals(200, d.status);
        assertEquals(2, d.json().getJSONObject("link").getInt("appTokens"));
        assertEquals(200, app("POST", "/v1/page", "{\"url\":\"http://192.168.1.9:8080/\"}", ctl).status);
        assertEquals(400, app("POST", "/v1/page", "{\"url\":\"https://evil.example/\"}", ctl).status);
        assertEquals("http://192.168.1.9:8080/", host.pages.get(0));
        // v2.21 tiled: up to 4, LAN only, no duplicates
        Resp tiled = app("POST", "/v1/page", "{\"urls\":[\"http://192.168.1.9:1/\",\"https://x.example/\",\"http://192.168.1.9:1/\",\"http://192.168.1.2/\",\"http://192.168.1.3/\",\"http://192.168.1.4/\",\"http://192.168.1.5/\"]}", ctl);
        assertEquals(4, tiled.json().getJSONArray("urls").length());
        // MCP Streamable HTTP: a notification gets 202 and no body (Hermes, the MCP SDKs)
        Resp nt = app("POST", "/v1/mcp", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", read);
        assertEquals(202, nt.status);
        assertEquals(0, nt.body.length);
        // MCP: a read token sees read tools only
        Resp tl = app("POST", "/v1/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", read);
        String tools = tl.json().getJSONObject("result").getJSONArray("tools").toString();
        assertTrue(tools.contains("tv_get_state") && tools.contains("tv_get_theme"));
        assertFalse(tools.contains("tv_send_key") || tools.contains("tv_notify"));
        Resp key = app("POST", "/v1/mcp", new JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/call")
                .put("params", new JSONObject().put("name", "tv_send_key").put("arguments", new JSONObject().put("key", "home"))).toString(), read);
        assertTrue(key.json().has("error"));
        assertTrue(host.keys.isEmpty());
        // revoke
        String id = list.json().getJSONArray("apps").getJSONObject(0).getString("id");
        assertEquals(200, signed("DELETE", "/v1/tokens/" + id, "").status);
        assertEquals(401, app("GET", "/v1/state", null, read).status);
        assertEquals(401, app("GET", "/v1/state", null, "otva_forged").status);
        // preflight and SDK need no token
        assertEquals(204, app("OPTIONS", "/v1/state", null, null).status);
        Resp sdk = app("GET", "/v1/sdk/omarchytv.js", null, null);
        assertEquals(200, sdk.status);
        assertTrue(sdk.headers.get("content-type").startsWith("text/javascript"));
        // tokens survive a restart (only their hash is on disk)
        assertEquals(1, new AppTokens(new File(dir, "link-apps.json")).size());
        assertFalse(new String(java.nio.file.Files.readAllBytes(new File(dir, "link-apps.json").toPath()), StandardCharsets.UTF_8).contains(ctl));
    }

    @Test
    public void agentPagesBecomeHomeTilesWithABadge() throws Exception {
        pair("123456");
        server.limits = false;
        String ctl = appToken("Hermes", "control"), note = appToken("Watcher", "notify");
        Resp put = app("PUT", "/v1/pages/dubai-flats", "{\"name\":\"Dubai flats\",\"url\":\"http://192.168.1.20:8080/\",\"icon\":\"D\",\"badge\":2}", ctl);
        assertEquals(200, put.status);
        assertEquals(400, app("PUT", "/v1/pages/x", "{\"name\":\"x\",\"url\":\"https://example.com/\"}", ctl).status);
        assertEquals(403, app("PUT", "/v1/pages/y", "{\"name\":\"y\",\"url\":\"http://192.168.1.2/\"}", note).status);
        assertEquals(200, app("POST", "/v1/pages/dubai-flats/badge", "{\"badge\":5}", note).status);   // notify may update the count
        JSONObject cfg = host.readConfig();
        JSONObject pg = cfg.getJSONArray("agentPages").getJSONObject(0);
        assertEquals("Dubai flats", pg.getString("name"));
        assertEquals(5, pg.getInt("badge"));
        assertEquals("Hermes", pg.getString("by"));
        assertEquals(1, app("GET", "/v1/pages", null, note).json().getJSONArray("pages").length());
        assertEquals(200, app("POST", "/v1/pages/dubai-flats/show", null, ctl).status);
        assertEquals("http://192.168.1.20:8080/", host.pages.get(0));
        assertEquals(200, app("DELETE", "/v1/pages/dubai-flats", null, ctl).status);
        assertEquals(0, host.readConfig().getJSONArray("agentPages").length());
        assertEquals(404, app("POST", "/v1/pages/dubai-flats/badge", "{\"badge\":1}", note).status);
    }

    @Test
    public void configBackupAndRestoreKeepTheTvsOwnLink() throws Exception {
        pair("123456");
        server.limits = false;
        JSONObject cfg = signed("GET", "/v1/config", "").json().getJSONObject("config");
        cfg.put("wallpaper", "plain").put("link", new JSONObject().put("evil", true));
        assertEquals(200, signed("PUT", "/v1/config", new JSONObject().put("config", cfg).toString()).status);
        JSONObject now = host.readConfig();
        assertEquals("plain", now.getString("wallpaper"));
        assertFalse(now.optJSONObject("link") != null && now.getJSONObject("link").has("evil"));
        String app = appToken("x", "control");
        assertEquals(401, app("GET", "/v1/config", null, app).status);   // apps never read or write it
    }

    @Test
    public void askIsAnsweredOnTheRemoteAndOnlyItsAppSeesIt() throws Exception {
        pair("123456");
        server.limits = false;
        String a = appToken("Claude Code", "notify"), b = appToken("Other", "notify");
        assertEquals(403, app("POST", "/v1/ask", "{\"question\":\"Deploy?\"}", a).status);   // agent alerts off
        host.notifyOn = true;
        Resp q = app("POST", "/v1/ask", "{\"question\":\"Deploy now?\",\"choices\":[\"Yes\",\"Not now\",\"Yes\"],\"timeout\":60}", a);
        assertEquals(200, q.status);
        String id = q.json().getString("id");
        assertEquals("pending", q.json().getString("state"));
        assertEquals(2, q.json().getJSONArray("choices").length());
        assertTrue(host.asked.get(0).contains("|Claude Code|Deploy now?|[Yes, Not now]"));
        assertEquals("", host.details.get(0));
        assertEquals(404, app("GET", "/v1/ask/" + id, null, b).status);   // another app's question
        new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            assertFalse(server.answerAsk(id, "Maybe"));   // not one of the choices
            assertTrue(server.answerAsk(id, "Not now"));
        }).start();
        long t0 = System.currentTimeMillis();
        Resp r = app("GET", "/v1/ask/" + id + "?wait=5", null, a);
        assertEquals("answered", r.json().getString("state"));
        assertEquals("Not now", r.json().getString("answer"));
        assertTrue(System.currentTimeMillis() - t0 < 4000);
        assertFalse(server.answerAsk(id, "Yes"));   // once
        assertTrue(server.pendingAsks().isEmpty());
        // the PC sees it too, and MCP tv_ask_result
        assertEquals("answered", signed("GET", "/v1/ask/" + id, "").json().getString("state"));
    }

    @Test
    public void appTokenLevelIsCappedByThePc() throws Exception {   // v2.22: the rule is the owner's, not the model's
        pair("123456");
        server.limits = false;
        host.notifyOn = true;
        Resp t = signed("POST", "/v1/tokens", new JSONObject().put("name", "hermes-nas").put("scope", "notify").put("maxLevel", "warn").toString());
        assertEquals("warn", t.json().getString("maxLevel"));
        String tok = t.json().getString("token"), id = t.json().getString("id");
        Resp r = app("POST", "/v1/notify", "{\"text\":\"disk 91%\",\"level\":\"alert\",\"speak\":true}", tok);
        assertEquals(200, r.status);
        assertEquals("warn", r.json().getString("capped"));
        assertEquals("warn", host.levels.get(host.levels.size() - 1));
        assertTrue(host.notes.get(host.notes.size() - 1).startsWith("hermes-nas: "));
        assertTrue(host.spoken.isEmpty());   // a capped alert does not talk
        assertFalse(app("POST", "/v1/notify", "{\"text\":\"ok\",\"level\":\"warn\"}", tok).json().has("capped"));
        assertEquals(200, signed("PUT", "/v1/tokens/" + id, "{\"maxLevel\":\"info\"}").status);
        assertEquals("info", app("POST", "/v1/notify", "{\"text\":\"x\",\"level\":\"warn\"}", tok).json().getString("capped"));
        assertEquals(400, signed("PUT", "/v1/tokens/" + id, "{\"maxLevel\":\"loud\"}").status);
        assertEquals(400, signed("POST", "/v1/tokens", "{\"name\":\"x\",\"scope\":\"notify\",\"maxLevel\":\"loud\"}").status);
        assertTrue(signed("GET", "/v1/tokens", "").json().getJSONArray("apps").getJSONObject(0).getString("maxLevel").equals("info"));
        // the TV's own MCP endpoint takes the same path: tv_notify with this token is capped and named by the TV
        JSONObject call = new JSONObject().put("jsonrpc", "2.0").put("id", 9).put("method", "tools/call")
                .put("params", new JSONObject().put("name", "tv_notify").put("arguments",
                        new JSONObject().put("text", "via mcp").put("level", "alert").put("from", "Somebody else")));
        Resp m = app("POST", "/v1/mcp", call.toString(), tok);
        assertEquals(200, m.status);
        assertTrue(m.json().toString(), m.json().toString().contains("capped"));
        assertEquals("info", host.levels.get(host.levels.size() - 1));
        assertTrue(host.notes.get(host.notes.size() - 1).startsWith("hermes-nas: "));
        // the paired PC itself is never capped
        signed("POST", "/v1/notify", "{\"text\":\"red\",\"level\":\"alert\"}");
        assertEquals("alert", host.levels.get(host.levels.size() - 1));
    }

    @Test
    public void aChattyAgentNeverCrowdsOutARedBuild() throws Exception {   // v2.22: rate buckets per sender, alerts apart
        pair("123456");
        host.notifyOn = true;
        server.limits = true;
        for (int i = 0; i < 3; i++) assertEquals(200, signed("POST", "/v1/notify", "{\"text\":\"hi\",\"from\":\"Chatty\"}").status);
        assertEquals(429, signed("POST", "/v1/notify", "{\"text\":\"hi\",\"from\":\"Chatty\"}").status);
        assertEquals(200, signed("POST", "/v1/notify", "{\"text\":\"tests pass\",\"from\":\"CI\"}").status);
        assertEquals(200, signed("POST", "/v1/notify", "{\"text\":\"build failed\",\"from\":\"CI\",\"level\":\"alert\"}").status);
        assertEquals(200, signed("POST", "/v1/notify", "{\"text\":\"me too\",\"from\":\"chatty\",\"level\":\"alert\"}").status);
        String[] k = LinkServer.notifyBucket("/v1/mcp", ("{\"params\":{\"name\":\"tv_notify\",\"arguments\":"
                + "{\"from\":\"Hermes\",\"level\":\"alert\"}}}").getBytes(StandardCharsets.UTF_8));
        assertEquals("hermes", k[0]);
        assertEquals("alert", k[1]);
        assertEquals("", LinkServer.notifyBucket("/v1/notify", "not json".getBytes(StandardCharsets.UTF_8))[0]);
    }

    @Test
    public void widgetsApiAndMcpWithScopes() throws Exception {   // v2.22 #157
        pair("123456");
        server.limits = false;
        String ctl = appToken("hermes", "control"), nfy = appToken("ci", "notify"), rd = appToken("wall", "read");
        String body = "{\"id\":\"nas\",\"type\":\"lines\",\"w\":4,\"h\":2,\"title\":\"NAS\",\"data\":{\"lines\":[{\"label\":\"Disk\",\"value\":\"71%\"}]}}";
        assertEquals(403, app("POST", "/v1/widgets/item", body, nfy).status);
        Resp c = app("POST", "/v1/widgets/item", body, ctl);
        assertEquals(200, c.status);
        assertEquals("app:hermes", c.json().getJSONObject("widget").getString("owner"));
        assertEquals(409, app("POST", "/v1/widgets/item", body.replace("\"w\":4", "\"x\":1,\"w\":4"), ctl).status);   // exists
        // notify may change the content, not the place
        assertEquals(200, app("PATCH", "/v1/widgets/item/nas", "{\"data\":{\"lines\":[{\"label\":\"Disk\",\"value\":\"72%\"}]}}", nfy).status);
        assertEquals(403, app("PATCH", "/v1/widgets/item/nas", "{\"x\":6}", nfy).status);
        assertEquals(403, app("DELETE", "/v1/widgets/item/nas", "", nfy).status);
        assertEquals(200, app("PATCH", "/v1/widgets/item/nas", "{\"x\":6,\"y\":3}", ctl).status);
        // read sees it, with the free cells
        Resp l = app("GET", "/v1/widgets/list?page=1", "", rd);
        JSONObject nas = l.json().getJSONArray("widgets").getJSONObject(0);
        assertEquals(6, nas.getInt("x"));
        assertEquals("72%", nas.getJSONObject("data").getJSONArray("lines").getJSONObject(0).getString("value"));
        assertEquals(8, l.json().getJSONArray("free").length());   // the first 8 free cells
        assertEquals(403, app("POST", "/v1/widgets/item", body, rd).status);
        // the PC's panes show as read-only lines widgets on page 1
        signed("PUT", "/v1/widgets", "{\"panes\":[{\"id\":\"cpu\",\"title\":\"CPU\",\"lines\":[]}]}");
        JSONArray ws = app("GET", "/v1/widgets/list?page=1", "", rd).json().getJSONArray("widgets");
        assertEquals("pane:cpu", ws.getJSONObject(1).getString("id"));
        assertEquals(409, app("DELETE", "/v1/widgets/item/pane:cpu", "", ctl).status);
        // the PC (signed) has every scope
        assertEquals(200, signed("POST", "/v1/widgets/item", "{\"id\":\"temp\",\"type\":\"stat\",\"data\":{\"value\":\"21\"}}").status);
        assertEquals(200, signed("GET", "/v1/widgets/types", "").status);
        // MCP: same code, same scopes
        Resp m = app("POST", "/v1/mcp", new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call").put("params",
                new JSONObject().put("name", "tv_widget_update").put("arguments", new JSONObject().put("id", "temp").put("x", 9)
                        .put("data", new JSONObject().put("value", "22")))).toString(), nfy);
        assertEquals(200, m.status);
        assertTrue(m.json().toString(), m.json().toString().contains("\\\"22\\\""));
        JSONArray after = app("GET", "/v1/widgets/list?page=1", "", rd).json().getJSONArray("widgets");
        for (int i = 0; i < after.length(); i++) {
            if ("temp".equals(after.getJSONObject(i).getString("id"))) assertFalse(after.getJSONObject(i).getInt("x") == 9);   // update never moves
        }
        Resp mt = app("POST", "/v1/mcp", new JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/list").toString(), nfy);
        String tl = mt.json().toString();
        assertTrue(tl.contains("tv_widget_update"));
        assertFalse(tl.contains("tv_widget_create"));   // not offered to a notify token
        assertEquals(200, app("DELETE", "/v1/widgets/item/nas", "", ctl).status);
        assertEquals(404, app("DELETE", "/v1/widgets/item/nas", "", ctl).status);
    }

    @Test
    public void widgetPagesApiAndMcp() throws Exception {   // v2.22 #158 / #159
        pair("123456");
        server.limits = false;
        String ctl = appToken("hermes", "control"), rd = appToken("wall", "read");
        assertEquals(200, app("POST", "/v1/widgets/item", "{\"id\":\"a\",\"type\":\"stat\",\"page\":\"casa\",\"data\":{\"value\":\"1\"}}", ctl).status);
        JSONObject l = app("GET", "/v1/widget-pages", "", rd).json();
        assertEquals(2, l.getInt("total"));   // page 1 + "casa" made on demand
        assertEquals("casa", l.getJSONArray("pages").getJSONObject(1).getString("id"));
        assertEquals(403, app("POST", "/v1/widget-pages", "{\"name\":\"x\"}", rd).status);
        assertEquals(200, app("POST", "/v1/widget-pages", "{\"name\":\"Server\",\"id\":\"server\"}", ctl).status);
        assertEquals(200, app("PATCH", "/v1/widget-pages/server", "{\"name\":\"Servers\",\"rotate\":true}", ctl).status);
        assertEquals(200, app("POST", "/v1/widget-pages/casa/show", "", ctl).status);
        assertEquals(2, host.readConfig().getInt("widgetPageCurrent"));
        assertEquals(404, app("POST", "/v1/widget-pages/nope/show", "", ctl).status);
        assertEquals(400, app("DELETE", "/v1/widget-pages/1", "", ctl).status);
        Resp m = app("POST", "/v1/mcp", new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call").put("params",
                new JSONObject().put("name", "tv_page_delete").put("arguments", new JSONObject().put("page", "casa").put("moveWidgetsTo", 1))).toString(), ctl);
        assertEquals(200, m.status);
        assertTrue(m.json().toString(), m.json().toString().contains("movedWidgets"));
        assertEquals(2, app("GET", "/v1/widget-pages", "", rd).json().getInt("total"));
        assertEquals(200, app("GET", "/v1/widget-pages/current", "", rd).status);
    }

    @Test
    public void homeAssistantRoutesWithScopes() throws Exception {   // v2.22 #160 (FakeHost: HA not set up)
        pair("123456");
        server.limits = false;
        String nfy = appToken("ci", "notify"), ctl = appToken("hermes", "control");
        Resp st = app("GET", "/v1/ha/status", "", nfy);
        assertEquals(200, st.status);
        assertFalse(st.json().getBoolean("configured"));
        assertEquals(403, app("POST", "/v1/ha/run", "{\"entity_id\":\"scene.movie\"}", nfy).status);
        assertEquals(409, app("POST", "/v1/ha/run", "{\"entity_id\":\"scene.movie\"}", ctl).status);
        assertEquals(409, app("GET", "/v1/ha/states?domain=scene", "", nfy).status);
        assertEquals(400, app("GET", "/v1/ha/states?domain=Scene;rm", "", nfy).status);
        Resp m = app("POST", "/v1/mcp", new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call").put("params",
                new JSONObject().put("name", "tv_ha_status").put("arguments", new JSONObject())).toString(), nfy);
        assertEquals(200, m.status);
        assertTrue(m.json().toString().contains("configured"));
    }

    Resp web(String method, String target, String body, String cookie, String csrf, String origin) throws Exception {
        Map<String, String> h = new java.util.LinkedHashMap<>();
        h.put("Host", "127.0.0.1:" + port);
        if (body != null) h.put("Content-Type", "application/json");
        if (cookie != null) h.put("Cookie", "theme=x; otv_web=" + cookie);
        if (csrf != null) h.put("X-Omarchy-CSRF", csrf);
        if (origin != null) h.put("Origin", origin);
        return http(method, target, body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8), h);
    }

    /** Asks for a session as a browser, answers {@code choice} on the "TV"; returns the poll's response. */
    Resp webApprove(String choice) throws Exception {
        String origin = "http://127.0.0.1:" + port;
        Resp ask = web("POST", "/v1/settings/session", "{\"device\":\"Firefox on Linux\"}", null, null, origin);
        assertEquals(200, ask.status);
        String id = ask.json().getString("id");
        assertEquals(id, host.webAsk);
        assertTrue(server.answerAsk(id, choice));
        return web("GET", "/v1/settings/session/" + id, null, null, null, null);
    }

    static String cookieOf(Resp r) {
        String c = r.headers.get("set-cookie");
        assertTrue(c, c.contains("HttpOnly") && c.contains("SameSite=Strict"));
        return c.substring("otv_web=".length(), c.indexOf(';'));
    }

    @Test
    public void webSettingsAreOffUntilTurnedOnAndNeedTheTv() throws Exception {   // v2.22 #163
        String origin = "http://127.0.0.1:" + port;
        assertEquals(404, web("GET", "/settings/", null, null, null, null).status);
        assertEquals(404, web("POST", "/v1/settings/session", "{}", null, null, origin).status);
        host.editConfig(j -> j.put("link", new JSONObject().put("webSettings", true)));
        Resp page = web("GET", "/settings/", null, null, null, null);
        assertEquals(200, page.status);
        assertTrue(page.headers.get("content-security-policy").contains("default-src 'none'"));
        assertEquals("DENY", page.headers.get("x-frame-options"));
        assertEquals(404, web("GET", "/settings/../link.json", null, null, null, null).status);
        // a name instead of the IP (DNS rebinding), another site's Origin
        Map<String, String> h = new HashMap<>();
        h.put("Host", "evil.example:" + port);
        assertEquals(403, http("GET", "/settings/", new byte[0], h).status);
        assertEquals(403, web("POST", "/v1/settings/session", "{}", null, null, "http://evil.example").status);
        assertEquals(401, web("GET", "/v1/settings", null, null, null, null).status);
        // denied, then read-only
        assertEquals("denied", webApprove("Deny").json().getString("state"));
        server.limits = false;   // the next approvals come at once (one every 30 s per address otherwise)
        Resp ro = webApprove("Allow read-only");
        assertEquals("read", ro.json().getString("scope"));
        String rc = cookieOf(ro);
        assertEquals(200, web("GET", "/v1/settings", null, rc, null, null).status);
        String rcsrf = web("GET", "/v1/settings/me", null, rc, null, null).json().getString("csrf");
        assertEquals(403, web("PATCH", "/v1/settings", "{\"uiSize\":\"large\"}", rc, rcsrf, origin).status);
        // allowed
        Resp ok = webApprove("Allow");
        String c = cookieOf(ok), csrf = ok.json().getString("csrf");
        assertFalse(ok.json().toString().contains(c));   // the token only travels in the cookie
        assertEquals(403, web("PATCH", "/v1/settings", "{\"uiSize\":\"large\"}", c, null, origin).status);
        assertEquals(403, web("PATCH", "/v1/settings", "{\"uiSize\":\"large\"}", c, "0".repeat(32), origin).status);
        assertEquals(403, web("PATCH", "/v1/settings", "{\"uiSize\":\"large\"}", c, csrf, null).status);
        assertEquals(200, web("PATCH", "/v1/settings", "{\"uiSize\":\"large\",\"plugins.radio\":true}", c, csrf, origin).status);
        assertEquals("large", host.readConfig().getString("uiSize"));
        assertTrue(host.readConfig().getJSONObject("plugins").getBoolean("radio"));
        // one bad value: nothing is written
        Resp bad = web("PATCH", "/v1/settings", "{\"uiSize\":\"small\",\"theme\":\"nope\"}", c, csrf, origin);
        assertEquals(400, bad.status);
        assertEquals("large", host.readConfig().getString("uiSize"));
        assertEquals(400, web("PATCH", "/v1/settings", "{\"link.pcs\":1}", c, csrf, origin).status);
        // Home Assistant: the token is set, never read back
        assertEquals(200, web("PATCH", "/v1/settings", "{\"ha.url\":\"http://192.168.1.5:8123/\",\"ha.token\":\"abc.def\"}", c, csrf, origin).status);
        assertEquals("abc.def", host.haToken);
        JSONObject got = web("GET", "/v1/settings", null, c, null, null).json();
        assertEquals("http://192.168.1.5:8123", got.getJSONObject("values").getString("ha.url"));
        assertTrue(got.getJSONObject("secrets").getJSONObject("ha.token").getBoolean("set"));
        assertFalse(got.toString().contains("abc.def"));
        web("PATCH", "/v1/settings", "{\"ha.url\":\"http://192.168.1.6:8123\"}", c, csrf, origin);
        assertEquals("", host.haToken);   // a new address forgets the old token
        // tokens from the page; revoking the browser's own token ends the session
        Resp nt = web("POST", "/v1/settings/tokens", "{\"name\":\"hermes\",\"scope\":\"notify\"}", c, csrf, origin);
        assertEquals(200, nt.status);
        assertEquals(400, web("POST", "/v1/settings/tokens", "{\"name\":\"web · x\",\"scope\":\"notify\"}", c, csrf, origin).status);
        assertEquals(403, web("GET", "/v1/settings/tokens", null, rc, null, null).status);
        assertNull(web("GET", "/v1/settings", null, c, null, null).headers.get("access-control-allow-origin"));
        Resp out = web("DELETE", "/v1/settings/session", null, c, csrf, origin);
        assertEquals(200, out.status);
        assertTrue(out.headers.get("set-cookie").contains("Max-Age=0"));
        assertEquals(401, web("GET", "/v1/settings", null, c, null, null).status);
        // the same settings for app tokens and MCP
        pair("123456");
        String ctl = appToken("hermes2", "control");
        assertEquals(200, app("PATCH", "/v1/settings", "{\"tempUnit\":\"F\"}", ctl).status);
        assertEquals("F", host.readConfig().getString("tempUnit"));
        JSONObject schema = app("GET", "/v1/settings/schema", "", ctl).json();
        assertEquals(1, schema.getInt("version"));
    }

    /** The app API's "*" is for Bearer tokens; the settings routes never answer another site's preflight. */
    @Test
    public void settingsNeverAnswerAnotherSitesPreflight() throws Exception {
        for (boolean on : new boolean[]{false, true}) {
            host.editConfig(j -> j.put("link", new JSONObject().put("webSettings", on)));
            Map<String, String> h = new HashMap<>();
            h.put("Host", "127.0.0.1:" + port);
            h.put("Origin", "http://evil.example");
            h.put("Access-Control-Request-Method", "PATCH");
            h.put("Access-Control-Request-Headers", "content-type,x-omarchy-csrf");
            Resp r = http("OPTIONS", "/v1/settings", new byte[0], h);
            assertNull(String.valueOf(on), r.headers.get("access-control-allow-origin"));
            assertTrue(r.status >= 400);
        }
    }

    @Test
    public void webApprovalsAreLimited() throws Exception {
        String origin = "http://127.0.0.1:" + port;
        host.editConfig(j -> j.put("link", new JSONObject().put("webSettings", true)));
        assertEquals(200, web("POST", "/v1/settings/session", "{}", null, null, origin).status);
        assertEquals(429, web("POST", "/v1/settings/session", "{}", null, null, origin).status);   // one every 30 s
        String id = server.web.pendingIds().get(0);
        assertFalse(server.answerAsk(id, "Something else"));
        Resp other = web("GET", "/v1/settings/session/0123456789abcdef", null, null, null, null);
        assertEquals(404, other.status);
    }

    @Test
    public void nightShiftDiaryAndMorningQueue() throws Exception {   // v2.22
        pair("123456");
        server.limits = false;
        String ctl = appToken("owner-agent", "control"), ntf = appToken("hermes", "notify"), rd = appToken("wall", "read");
        assertEquals(409, app("POST", "/v1/shift/log", "{\"text\":\"x\"}", ntf).status);   // no shift yet
        assertEquals(403, app("POST", "/v1/shift/start", "{\"tasks\":[\"x\"],\"until\":\"07:00\"}", ntf).status);
        long until = System.currentTimeMillis() + 3600_000L;
        Resp st = app("POST", "/v1/shift/start", "{\"tasks\":[\"fix tests\",\"draft post\"],\"until\":" + until + "}", ctl);
        assertEquals(200, st.status);
        assertEquals("running", st.json().getJSONObject("shift").getString("state"));
        assertEquals(403, app("POST", "/v1/shift/log", "{\"text\":\"x\"}", rd).status);
        assertEquals(200, app("POST", "/v1/shift/log", "{\"level\":\"ok\",\"text\":\"352 tests green\",\"task\":0}", ntf).status);
        Resp q = app("POST", "/v1/shift/queue", "{\"title\":\"push tv-2.22\",\"risk\":\"push\"}", ntf);
        assertEquals("q1", q.json().getJSONObject("item").getString("id"));
        assertEquals("pending", app("GET", "/v1/shift/queue/q1", "", ntf).json().getString("state"));
        // there is no way to answer from the network: only the TV page does
        assertEquals(404, app("POST", "/v1/shift/queue/q1", "{\"answer\":\"approved\"}", ctl).status);
        JSONObject counts = app("GET", "/v1/shift", "", rd).json().getJSONObject("counts");
        assertEquals(1, counts.getInt("ok"));
        assertEquals(1, counts.getInt("waiting"));
        // the same through MCP
        Resp r = app("POST", "/v1/mcp", new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call").put("params",
                new JSONObject().put("name", "tv_shift_log").put("arguments", new JSONObject().put("level", "fail").put("text", "lint failed"))).toString(), ntf);
        assertFalse(r.json().toString(), r.json().getJSONObject("result").optBoolean("isError"));
        Resp denied = app("POST", "/v1/mcp", new JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/call").put("params",
                new JSONObject().put("name", "tv_shift_log").put("arguments", new JSONObject().put("text", "x"))).toString(), rd);
        assertFalse(denied.json().toString().contains("\"ok\":true"));
        assertEquals(2, app("GET", "/v1/shift", "", rd).json().getJSONObject("counts").getInt("ok") + app("GET", "/v1/shift", "", rd).json().getJSONObject("counts").getInt("fail"));
        assertEquals(200, app("POST", "/v1/shift/end", "{}", ctl).status);
        assertEquals(409, app("POST", "/v1/shift/log", "{\"text\":\"late\"}", ntf).status);
    }

    @Test
    public void radioStationsAndPluginsKeepTheirSwitches() throws Exception {   // v2.22 #162
        pair("123456");
        server.limits = false;
        String ctl = appToken("hermes", "control"), rd = appToken("wall", "read");
        assertEquals(400, app("POST", "/v1/radio/stations", "{\"name\":\"X\",\"url\":\"http://example.com/x.mp3\"}", ctl).status);
        Resp add = app("POST", "/v1/radio/stations", "{\"name\":\"Radio Paradise\",\"url\":\"https://stream.radioparadise.com/aac-320\"}", ctl);
        assertEquals(200, add.status);
        assertEquals("radio-paradise", add.json().getString("id"));
        assertEquals(403, app("POST", "/v1/radio/stations", "{\"name\":\"Y\",\"url\":\"https://y.example/s\"}", rd).status);
        JSONArray st = app("GET", "/v1/radio/stations", "", rd).json().getJSONArray("stations");
        assertEquals("omarchy", st.getJSONObject(0).getString("id"));
        assertEquals("radio-paradise", st.getJSONObject(1).getString("id"));
        assertEquals(400, app("POST", "/v1/radio", "{\"action\":\"volume\",\"volume\":140}", ctl).status);
        assertEquals(400, app("POST", "/v1/radio", "{\"action\":\"sleep\",\"minutes\":-1}", ctl).status);
        assertEquals(400, app("DELETE", "/v1/radio/stations/omarchy", "", ctl).status);
        assertEquals(200, app("DELETE", "/v1/radio/stations/radio-paradise", "", ctl).status);
        assertEquals(404, app("DELETE", "/v1/radio/stations/radio-paradise", "", ctl).status);
        // PUT /v1/plugins from an older PC keeps the radio switch
        host.editConfig(j -> j.put("plugins", new JSONObject().put("radio", true).put("omastorm", true)));
        signed("PUT", "/v1/plugins", "{\"spacexTv\":true,\"grokbot\":true,\"plugins\":[]}");
        JSONObject pl = host.readConfig().getJSONObject("plugins");
        assertTrue(pl.getBoolean("radio"));
        assertTrue(pl.getBoolean("omastorm"));
        assertTrue(pl.getBoolean("spacexTv"));
    }

    @Test
    public void appTokenFloorDropsItsOwnNoise() throws Exception {   // v2.22: per-agent floor, beside the global one
        pair("123456");
        server.limits = false;
        host.notifyOn = true;
        Resp t = signed("POST", "/v1/tokens", "{\"name\":\"chatty\",\"scope\":\"notify\",\"minLevel\":\"warn\"}");
        assertEquals("warn", t.json().getString("minLevel"));
        String tok = t.json().getString("token"), id = t.json().getString("id");
        int n = host.notes.size();
        Resp r = app("POST", "/v1/notify", "{\"text\":\"step 3 of 40\"}", tok);
        assertTrue(r.json().getBoolean("filtered"));
        assertEquals(n, host.notes.size());
        app("POST", "/v1/notify", "{\"text\":\"disk full\",\"level\":\"alert\"}", tok);
        assertEquals(n + 1, host.notes.size());
        assertEquals(400, signed("PUT", "/v1/tokens/" + id, "{\"minLevel\":\"alert\",\"maxLevel\":\"warn\"}").status);
        Resp u = signed("PUT", "/v1/tokens/" + id, "{\"minLevel\":\"info\"}");
        assertEquals("info", u.json().getString("minLevel"));
        assertEquals(400, signed("POST", "/v1/tokens", "{\"name\":\"x\",\"scope\":\"notify\",\"minLevel\":\"alert\",\"maxLevel\":\"info\"}").status);
        assertEquals(404, signed("PUT", "/v1/tokens/0000ffff", "{\"minLevel\":\"warn\"}").status);
    }

    @Test
    public void riskyAskNeverOffersAYes() throws Exception {   // v2.22: risky requests are approved at the PC only
        pair("123456");
        server.limits = false;
        host.notifyOn = true;
        String a = appToken("Claude Code", "notify");
        Resp q = app("POST", "/v1/ask", "{\"question\":\"rm -rf build?\",\"risk\":\"deletes files\",\"choices\":[\"Allow\",\"Deny\",\"On the PC\"]}", a);
        assertEquals(200, q.status);
        assertEquals("[\"Deny\",\"On the PC\"]", q.json().getJSONArray("choices").toString());
        assertEquals("deletes files", server.pendingAsks().get(0)[5]);
        Resp only = app("POST", "/v1/ask", "{\"question\":\"x?\",\"risk\":\"force push\",\"choices\":[\"Yes\",\"OK\"]}", a);
        assertEquals("[\"Deny\",\"On the PC\"]", only.json().getJSONArray("choices").toString());
        assertTrue(AppTokens.sayYes("Approve deploy"));
        assertFalse(AppTokens.sayYes("Wait"));
    }

    @Test
    public void askCarriesTheFullDetail() throws Exception {   // v2.22: the whole command under the question
        pair("123456");
        server.limits = false;
        host.notifyOn = true;
        String a = appToken("Claude Code", "notify");
        StringBuilder cmd = new StringBuilder("rsync -av");
        for (int i = 0; i < 60; i++) cmd.append(" dir").append(i).append('/');
        JSONObject body = new JSONObject().put("question", "Run rsync?").put("detail", cmd + "\n\tsecond line\u0007");
        assertEquals(200, app("POST", "/v1/ask", body.toString(), a).status);
        String d = host.details.get(0);
        assertTrue(d, d.contains("dir59/"));
        assertTrue(d.endsWith("\n second line"));
        assertEquals(d, server.pendingAsks().get(0)[4]);
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 100; i++) many.append("line ").append(i).append('\n');
        assertEquals(40, AppApi.detail(many.toString()).split("\n").length);
        assertTrue(AppApi.detail(new String(new char[5000]).replace('\0', 'x')).length() <= 2000);
        assertEquals("", AppApi.detail("  \n "));
    }

    @Test
    public void streamSendsStateAndNotify() throws Exception {
        pair("123456");
        server.limits = false;
        host.notifyOn = true;
        host.theme = new JSONObject().put("name", "nord");
        String tok = appToken("Board", "notify");
        try (java.net.Socket sk = new java.net.Socket(InetAddress.getLoopbackAddress(), port)) {
            sk.setSoTimeout(8000);
            sk.getOutputStream().write(("GET /v1/stream?token=" + tok + " HTTP/1.1\r\nHost: tv\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(sk.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder seen = new StringBuilder();
            String line;
            boolean sent = false;
            long until = System.currentTimeMillis() + 7000;
            while (System.currentTimeMillis() < until && (line = in.readLine()) != null) {
                seen.append(line).append('\n');
                if (!sent && seen.indexOf("event: theme") >= 0 && seen.indexOf("event: state") >= 0) {
                    sent = true;
                    assertEquals(200, app("POST", "/v1/notify", "{\"text\":\"tests pass\"}", tok).status);
                }
                if (seen.indexOf("event: notify") >= 0 && line.startsWith("data:") && line.contains("tests pass")) break;
            }
            String all = seen.toString();
            assertTrue(all, all.startsWith("HTTP/1.1 200 OK"));
            assertTrue(all, all.contains("text/event-stream"));
            assertTrue(all, all.contains("\"screen\":\"home\""));
            assertTrue(all, all.contains("\"name\":\"nord\""));
            assertTrue(all, all.contains("\"from\":\"Board\""));   // an app's notify carries its own name
            // v2.21 revoking every token from the TV ends their streams at once
            server.revokeAppTokens();
            long until2 = System.currentTimeMillis() + 5000;
            boolean ended = false;
            try {
                while (System.currentTimeMillis() < until2) {
                    if (in.readLine() == null) {
                        ended = true;
                        break;
                    }
                }
            } catch (java.io.IOException e) {
                ended = true;
            }
            assertTrue("stream closed after revoke", ended);
        }
    }

    @Test
    public void notifyIsRateLimited() throws Exception {
        pair("123456");
        host.notifyOn = true;
        int ok = 0, limited = 0;
        for (int i = 0; i < 5; i++) {
            int st = signed("POST", "/v1/notify", "{\"text\":\"n" + i + "\"}").status;
            if (st == 200) ok++;
            if (st == 429) limited++;
        }
        assertEquals(3, ok);
        assertEquals(2, limited);
    }

    @Test
    public void mcpInitializeListAndCall() throws Exception {
        pair("123456");
        server.limits = false;
        assertTrue(server.status().getJSONArray("capabilities").toString().contains("\"mcp\""));

        // 1. Unauthenticated / wrong Bearer token -> 401
        Map<String, String> badH = new HashMap<>();
        badH.put("Authorization", "Bearer wrongtoken");
        assertEquals(401, http("POST", "/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}".getBytes(StandardCharsets.UTF_8), badH).status);

        // 2. Bearer token authentication on /mcp
        Map<String, String> bearerH = new HashMap<>();
        bearerH.put("Authorization", "Bearer " + LinkCrypto.b64u(token));
        Resp init = http("POST", "/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}".getBytes(StandardCharsets.UTF_8), bearerH);
        assertEquals(200, init.status);
        assertEquals("omarchytv-mcp", init.json().getJSONObject("result").getJSONObject("serverInfo").getString("name"));
        // The TV advertises its own launcher version, never a hard-coded one (it said 2.12.0 until 2.14.0-beta).
        assertEquals("2.14.0", init.json().getJSONObject("result").getJSONObject("serverInfo").getString("version"));

        // 3. tools/list via Bearer
        Resp list = http("POST", "/mcp", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}".getBytes(StandardCharsets.UTF_8), bearerH);
        assertEquals(200, list.status);
        assertTrue(list.json().getJSONObject("result").getJSONArray("tools").length() >= 8);

        // 4. tools/call tv_set_theme via OTV1 signed request on /v1/mcp
        JSONObject callReq = new JSONObject()
                .put("jsonrpc", "2.0").put("id", 3).put("method", "tools/call")
                .put("params", new JSONObject().put("name", "tv_set_theme").put("arguments", new JSONObject().put("name", "jade-horse")));
        Resp call = signed("POST", "/v1/mcp", callReq.toString());
        assertEquals(200, call.status);
        assertFalse(call.json().getJSONObject("result").getBoolean("isError"));
        assertEquals("jade-horse", host.readConfig().getJSONObject("theme").getString("name"));
    }

    @Test
    public void pluginsSandboxedEndpoints() throws Exception {
        pair("123456");
        server.limits = false;
        assertTrue(server.status().getJSONArray("capabilities").toString().contains("plugins"));

        Resp get0 = signed("GET", "/v1/plugins", "");
        assertEquals(200, get0.status);
        assertFalse(get0.json().getBoolean("spacexTv"));   // 2.20: off by default
        assertTrue(get0.json().getBoolean("grokbot"));

        JSONObject putBody = new JSONObject()
                .put("spacexTv", false)
                .put("grokbot", true)
                .put("plugins", new org.json.JSONArray()
                        .put(new JSONObject().put("id", "com.sighmon.spacex-tv").put("name", "SpaceX TV").put("author", "sighmon").put("license", "MIT").put("enabled", false))
                        .put(new JSONObject().put("id", "io.github.tcballard.grokbot-desktop").put("name", "Agent Desktop").put("author", "tcballard").put("license", "MIT").put("enabled", true)));
        Resp putR = signed("PUT", "/v1/plugins", putBody.toString());
        assertEquals(200, putR.status);
        assertFalse(putR.json().getBoolean("spacexTv"));

        Resp gbPost = signed("POST", "/v1/plugins/grokbot", new JSONObject().put("message", "Deploy finished on omarchy-pc").toString());
        assertEquals(200, gbPost.status);
        assertEquals("Deploy finished on omarchy-pc", gbPost.json().getString("message"));

        Resp gbGet = signed("GET", "/v1/plugins/grokbot", "");
        assertEquals(200, gbGet.status);
        assertEquals("Deploy finished on omarchy-pc", gbGet.json().getString("message"));
    }

    @Test
    public void grokbotCompanionMirrorIsSanitizedAndKept() throws Exception {
        pair("123456");
        server.limits = false;
        JSONObject bot = new JSONObject().put("name", "OmaBot\u0007").put("shortName", "OmaBot").put("shape", "bean")
                .put("color", "green").put("fill", "#5bc67a").put("mode", "relay").put("evil", "<script>");
        Resp r = signed("PUT", "/v1/plugins/grokbot", new JSONObject().put("bot", bot).put("from", "pc").toString());
        assertEquals(200, r.status);
        JSONObject got = signed("GET", "/v1/plugins/grokbot", "").json().getJSONObject("bot");
        assertEquals("OmaBot", got.getString("shortName"));
        assertEquals("#5BC67A", got.getString("fill"));
        assertFalse(got.has("evil"));
        // unknown shape/colour/mode fall back; a bad fill becomes null
        JSONObject odd = LinkServer.sanitizeGrokbotBot(new JSONObject().put("name", "Zed").put("shape", "../x")
                .put("color", "plaid").put("fill", "red").put("mode", "root"));
        assertEquals("bean", odd.getString("shape"));
        assertEquals("orange", odd.getString("color"));
        assertTrue(odd.isNull("fill"));
        assertEquals("offline", odd.getString("mode"));
        assertNull(LinkServer.sanitizeGrokbotBot(new JSONObject().put("shape", "bean")));
        assertEquals(400, signed("PUT", "/v1/plugins/grokbot", new JSONObject().put("bot", new JSONObject().put("name", " ")).toString()).status);
        // the plugin switches keep the mirrored bot
        signed("PUT", "/v1/plugins", new JSONObject().put("spacexTv", true).put("grokbot", true).toString());
        assertEquals("OmaBot", signed("GET", "/v1/plugins/grokbot", "").json().getJSONObject("bot").getString("name"));
        // bot:null = companion gone on the PC
        assertEquals(200, signed("PUT", "/v1/plugins/grokbot", new JSONObject().put("bot", JSONObject.NULL).toString()).status);
        assertTrue(signed("GET", "/v1/plugins/grokbot", "").json().isNull("bot"));
    }

    @Test
    public void grokbotAskRefusesLocallyAndRepliesArePlain() throws Exception {
        assertEquals("bad_request", server.grokbotAsk("r1", "  ", null));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 241; i++) big.append('x');
        assertEquals("bad_request", server.grokbotAsk("r1", big.toString(), null));
        assertEquals("not_connected", server.grokbotAsk("r1", "hi", "OmaBot"));
        JSONObject a = LinkServer.grokbotReply("grokbot_answer", new JSONObject().put("id", "r1").put("bot", "Bob")
                .put("text", "line\u001b[31m red").put("mode", "weird"));
        assertEquals("relay", a.getString("mode"));
        assertFalse(a.getString("text").contains("\u001b"));
        JSONObject e = LinkServer.grokbotReply("grokbot_error", new JSONObject().put("id", "r1").put("error", "unknown_bot"));
        assertEquals("unknown_bot", e.getString("error"));
    }

    @Test
    public void cinemarchyQueueKeepsSubtitles() throws Exception {
        pair("123456");
        server.limits = false;
        Resp r1 = signed("POST", "/v1/cinemarchy/queue", new JSONObject().put("url", "https://example.org/a.mp4")
                .put("title", "A").put("subtitle", "https://example.org/a.vtt").toString());
        assertEquals(200, r1.status);
        Resp r2 = signed("POST", "/v1/cinemarchy/queue", new JSONObject().put("url", "https://example.org/b.mp4")
                .put("title", "B").put("subtitleData", "1\n00:00:01,000 --> 00:00:02,000\nciao\n").put("subtitleFormat", "srt").toString());
        assertEquals(200, r2.status);
        Resp bad = signed("POST", "/v1/cinemarchy/queue", new JSONObject().put("url", "https://example.org/c.mp4")
                .put("subtitleData", "x").put("subtitleFormat", "exe").toString());
        assertEquals(400, bad.status);
        org.json.JSONArray q = host.readConfig().getJSONObject("cinemarchy").getJSONArray("queue");
        assertEquals("https://example.org/a.vtt", q.getJSONObject(0).getString("subs"));
        String local = q.getJSONObject(1).getString("subs");
        assertTrue(local.startsWith("file://") && local.endsWith(".srt"));
        assertTrue(new String(java.nio.file.Files.readAllBytes(new java.io.File(local.substring(7)).toPath()),
                java.nio.charset.StandardCharsets.UTF_8).contains("ciao"));
    }
}
