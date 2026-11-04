package com.omarchytv.home;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** v2.14 liftoff countdown, Wake-on-LAN packet and the update picker (same cases as webos/tests/liftoff.test.js). */
public class LiftoffScriptTest {

    @Test
    public void sharedCases() throws Exception {
        String json = new String(Files.readAllBytes(new File("../../webos/tests/liftoff-cases.json").toPath()), StandardCharsets.UTF_8);
        JSONArray cases = new JSONArray(json);
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i), s = c.getJSONObject("s");
            long since = c.getLong("since");
            assertEquals(c.toString(), c.getString("clock"), LiftoffScript.clock(s, since));
            assertEquals(c.toString(), c.getInt("progress"), LiftoffScript.progress(s, since));
            assertEquals(c.toString(), c.getBoolean("final"), LiftoffScript.finalCount(s, since));
        }
        assertEquals("[█████░░░░░] 50%", LiftoffScript.bar(50, 10));
        assertEquals("", LiftoffScript.bar(-1, 10));
    }

    @Test
    public void wakeOnLanPacket() {
        byte[] p = WakeOnLan.packet("AA:bb:cc:00:11:22");
        assertEquals(102, p.length);
        for (int i = 0; i < 6; i++) assertEquals((byte) 0xff, p[i]);
        for (int r = 0; r < 16; r++) {
            assertEquals((byte) 0xaa, p[6 + r * 6]);
            assertEquals((byte) 0x22, p[6 + r * 6 + 5]);
        }
        assertArrayEquals(WakeOnLan.packet("aa-bb-cc-00-11-22"), p);
        assertNull(WakeOnLan.packet("00:00:00:00:00:00"));
        assertNull(WakeOnLan.packet("aa:bb:cc:00:11"));
        assertNull(WakeOnLan.packet("zz:bb:cc:00:11:22"));
        assertEquals(true, WakeOnLan.ipv4("192.168.1.255"));
        assertEquals(false, WakeOnLan.ipv4("evil.example"));
        assertEquals(false, WakeOnLan.ipv4("300.1.1.1"));
    }

    @Test
    public void updaterPicksNewestOfficialApk() throws Exception {
        assertEquals(-1, Updater.compare("2.13.8", "2.13.10"));
        assertEquals(-1, Updater.compare("2.14.0-beta", "2.14.0"));
        assertEquals(1, Updater.compare("2.14.0", "2.13.11"));
        assertEquals(0, Updater.compare("v2.13.8", "2.13.8"));
        String dl = "https://github.com/piermark/omarchytv/releases/download/";
        JSONArray rel = new JSONArray()
                .put(new JSONObject().put("draft", false).put("assets", new JSONArray()
                        .put(new JSONObject().put("name", "omarchytv-webos-2.13.11-testing.ipk").put("browser_download_url", dl + "v2.13.11/x.ipk"))))
                .put(new JSONObject().put("draft", true).put("assets", new JSONArray()
                        .put(new JSONObject().put("name", "omarchytv-release-9.0.0.apk").put("browser_download_url", dl + "v9/omarchytv-release-9.0.0.apk"))))
                .put(new JSONObject().put("draft", false).put("assets", new JSONArray()
                        .put(new JSONObject().put("name", "omarchytv-release-8.0.0.apk").put("browser_download_url", "https://evil.example/omarchytv-release-8.0.0.apk"))
                        .put(new JSONObject().put("name", "omarchytv-mobile-2.14.0.apk").put("browser_download_url", dl + "v2.14.0/omarchytv-mobile-2.14.0.apk"))
                        .put(new JSONObject().put("name", "omarchytv-release-2.14.0.apk").put("browser_download_url", dl + "v2.14.0/omarchytv-release-2.14.0.apk")
                                .put("size", 123).put("digest", "sha256:ABCD"))))
                .put(new JSONObject().put("draft", false).put("assets", new JSONArray()
                        .put(new JSONObject().put("name", "omarchytv-release-2.13.8.apk").put("browser_download_url", dl + "v2.13.8/omarchytv-release-2.13.8.apk"))));
        Updater.Asset a = Updater.pick(rel, "2.13.8");
        assertEquals("2.14.0", a.version);
        assertEquals("abcd", a.sha256);
        assertEquals(123, a.size);
        assertNull(Updater.pick(rel, "2.14.0"));
    }
}
