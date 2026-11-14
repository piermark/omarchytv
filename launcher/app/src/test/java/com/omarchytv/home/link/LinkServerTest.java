package com.omarchytv.home.link;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
            StringBuilder h = new StringBuilder(method + " " + target + " HTTP/1.1\r\nHost: tv\r\nConnection: close\r\n");
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
