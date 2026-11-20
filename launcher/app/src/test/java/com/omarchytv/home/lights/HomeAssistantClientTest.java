package com.omarchytv.home.lights;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** v2.22 #160: the Home Assistant client against a local mock of the REST API; the token never comes back. */
public class HomeAssistantClientTest {
    private static final String TOKEN = "secret-token-12345";
    private ServerSocket srv;
    private final List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
    private String base;

    /** A tiny HTTP/1.0 mock of the HA REST API (one request per connection). */
    @Before
    public void up() throws Exception {
        srv = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (!srv.isClosed()) {
                try (Socket c = srv.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                    String[] rl = in.readLine().split(" ");
                    String auth = "", line;
                    int len = 0;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        String l = line.toLowerCase(java.util.Locale.ROOT);
                        if (l.startsWith("authorization:")) auth = line.substring(14).trim();
                        if (l.startsWith("content-length:")) len = Integer.parseInt(line.substring(15).trim());
                    }
                    char[] cb = new char[len];
                    int got = 0;
                    while (got < len) got += in.read(cb, got, len - got);
                    String path = rl[1], body = new String(cb);
                    calls.add(rl[0] + " " + path + " " + body);
                    int code = 200;
                    String out;
                    if (!("Bearer " + TOKEN).equals(auth)) {
                        code = 401;
                        out = "{}";
                    } else if (path.equals("/api/config")) {
                        out = "{\"version\":\"2026.11.1\",\"location_name\":\"Home\"}";
                    } else if (path.equals("/api/states")) {
                        out = "[{\"entity_id\":\"scene.movie\",\"state\":\"x\",\"attributes\":{\"friendly_name\":\"Movie\"}},"
                                + "{\"entity_id\":\"scene.dinner\",\"state\":\"x\",\"attributes\":{\"friendly_name\":\"Dinner\"}},"
                                + "{\"entity_id\":\"sensor.living_temperature\",\"state\":\"21.4\",\"attributes\":{\"friendly_name\":\"Living\",\"unit_of_measurement\":\"°C\"}}]";
                    } else if (path.startsWith("/api/states/")) {
                        out = "{\"entity_id\":\"sensor.living_temperature\",\"state\":\"21.6\",\"attributes\":{\"friendly_name\":\"Living\",\"unit_of_measurement\":\"°C\"}}";
                    } else if (path.startsWith("/api/services/")) {
                        out = "[]";
                    } else {
                        code = 404;
                        out = "{}";
                    }
                    byte[] b = out.getBytes(StandardCharsets.UTF_8);
                    OutputStream os = c.getOutputStream();
                    os.write(("HTTP/1.0 " + code + " X\r\nContent-Type: application/json\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    os.write(b);
                    os.flush();
                } catch (Exception ignored) {
                    // closed
                }
            }
        }, "mock-ha");
        t.setDaemon(true);
        t.start();
        base = "http://127.0.0.1:" + srv.getLocalPort();
    }

    @After
    public void down() throws Exception {
        srv.close();
    }

    @Test
    public void statusNamesTheHostButNeverTheToken() throws Exception {
        JSONObject s = new HomeAssistantClient(base + "/", TOKEN).status();
        assertTrue(s.getBoolean("reachable"));
        assertEquals("2026.11.1", s.getString("version"));
        assertEquals("127.0.0.1", s.getString("host"));
        assertFalse(s.toString().contains(TOKEN));
        JSONObject bad = new HomeAssistantClient(base, "wrong").status();
        assertFalse(bad.getBoolean("reachable"));
        assertEquals("unauthorized", bad.getString("error"));
        assertFalse(new HomeAssistantClient("", "").status().getBoolean("configured"));
    }

    @Test
    public void statesAreFilteredAndSorted() throws Exception {
        HomeAssistantClient ha = new HomeAssistantClient(base, TOKEN);
        JSONArray scenes = ha.states("scene", "", 48);
        assertEquals(2, scenes.length());
        assertEquals("Dinner", scenes.getJSONObject(0).getString("name"));
        JSONArray q = ha.states("", "living", 48);
        assertEquals("°C", q.getJSONObject(0).getString("unit"));
        assertEquals("21.6", ha.state("sensor.living_temperature").getString("state"));
        assertFalse(scenes.toString().contains(TOKEN));
    }

    @Test
    public void runCallsTheRightServiceAndNothingForReadOnly() {
        HomeAssistantClient ha = new HomeAssistantClient(base, TOKEN);
        assertNull(ha.run("scene.movie"));
        assertTrue(calls.get(calls.size() - 1).startsWith("POST /api/services/scene/turn_on {\"entity_id\":\"scene.movie\"}"));
        assertNull(ha.run("light.living"));
        assertTrue(calls.get(calls.size() - 1).startsWith("POST /api/services/light/toggle"));
        assertNull(ha.run("automation.away"));
        assertTrue(calls.get(calls.size() - 1).startsWith("POST /api/services/automation/trigger"));
        int n = calls.size();
        assertEquals("read_only", ha.run("sensor.living_temperature"));
        assertEquals("read_only", ha.run("scene.movie/../x"));
        assertEquals(n, calls.size());
        assertEquals("unauthorized", new HomeAssistantClient(base, "wrong").run("scene.movie"));
        assertArrayEquals(new String[]{"script", "turn_on"}, HomeAssistantClient.serviceFor("script.good_night"));
        assertEquals("ws://127.0.0.1:" + srv.getLocalPort() + "/api/websocket", ha.wsUrl());
        assertEquals("wss://ha.example/api/websocket", new HomeAssistantClient("https://ha.example/", TOKEN).wsUrl());
    }

    @Test
    public void websocketEventsAndHandshakeKey() throws Exception {
        List<String> seen = new ArrayList<>();
        HaSocket.Listener l = new HaSocket.Listener() {
            @Override public void onState(String entity, JSONObject b) {
                seen.add(entity + "=" + b.optString("state") + b.optString("unit"));
            }
            @Override public void onClosed(String why) {
            }
        };
        HaSocket.apply(new JSONObject("{\"a\":{\"sensor.t\":{\"s\":\"21\",\"a\":{\"unit_of_measurement\":\"°C\"}}},"
                + "\"c\":{\"light.x\":{\"+\":{\"s\":\"on\"}},\"light.y\":{\"-\":{\"a\":[\"brightness\"]}}}}"), l);
        assertEquals(2, seen.size());
        assertTrue(seen.contains("sensor.t=21°C"));
        assertTrue(seen.contains("light.x=on"));
        assertEquals("AAECAwQFBgcICQoLDA0ODw==", HaSocket.base64(new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}));
        assertEquals("Zm9vYg==", HaSocket.base64("foob".getBytes(StandardCharsets.US_ASCII)));
    }
}
