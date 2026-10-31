package com.omarchytv.home.lights;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class LightsLeaveTest {
    @Test
    public void turnOffIsTheDefaultAndRestoreFallsBackToOff() {
        assertEquals("off", LightsLeave.policy(""));
        assertEquals("off", LightsLeave.policy("bogus"));
        assertEquals("off", LightsLeave.action("", false));
        assertEquals("restore", LightsLeave.action("restore", true));
        assertEquals("off", LightsLeave.action("restore", false));
        assertEquals("none", LightsLeave.action("keep", true));
        assertEquals("restore", LightsLeave.next("off"));
        assertEquals("keep", LightsLeave.next("restore"));
        assertEquals("off", LightsLeave.next("keep"));
    }

    @Test
    public void snapshotOnlyForRestoreAndOnlyOnce() {
        assertTrue(LightsLeave.takeSnapshot("restore", false));
        assertFalse(LightsLeave.takeSnapshot("restore", true));
        assertFalse(LightsLeave.takeSnapshot("off", false));
        assertFalse(LightsLeave.takeSnapshot("keep", false));
    }

    @Test
    public void launcherHandOverKeepsTheCountAboveZero() {
        int n = LightsLeave.count(0, true);      // Home started
        n = LightsLeave.count(n, true);          // Cinemarchy started before Home stops
        n = LightsLeave.count(n, false);         // Home stopped
        assertEquals(1, n);
        assertEquals(0, LightsLeave.count(LightsLeave.count(n, false), false));   // never negative
        assertTrue(LightsLeave.DEBOUNCE_MS >= 1000 && LightsLeave.DEBOUNCE_MS <= 5000);
    }

    @Test
    public void hueSnapshotKeepsTheGroupsLightsAndTheirColourMode() throws Exception {
        String group = "{\"name\":\"TV\",\"lights\":[\"1\",\"3\"]}";
        String lights = "{\"1\":{\"state\":{\"on\":true,\"bri\":200,\"colormode\":\"ct\",\"ct\":366,\"xy\":[0.4,0.4]}},"
                + "\"2\":{\"state\":{\"on\":true,\"bri\":10}},"
                + "\"3\":{\"state\":{\"on\":false,\"bri\":50,\"colormode\":\"xy\",\"xy\":[0.3,0.2]}}}";
        JSONObject s = HueBridge.snapshotOf(group, lights);
        assertEquals(2, s.length());
        assertFalse(s.has("2"));
        assertEquals(366, s.getJSONObject("1").getInt("ct"));
        assertFalse(s.getJSONObject("1").has("xy"));
        JSONObject on = HueBridge.restoreBody(s.getJSONObject("1"));
        assertTrue(on.getBoolean("on"));
        assertEquals(200, on.getInt("bri"));
        JSONObject off = HueBridge.restoreBody(s.getJSONObject("3"));
        assertFalse(off.getBoolean("on"));
        assertFalse(off.has("xy"));
    }

    @Test
    public void homeAssistantRestoreUsesTurnOnWithTheOldColourOrTurnOff() throws Exception {
        JSONObject on = HomeAssistantHub.stateOf("{\"state\":\"on\",\"attributes\":{\"brightness\":120,\"color_mode\":\"rgb\",\"rgb_color\":[255,0,10]}}");
        String[] c = HomeAssistantHub.restoreCall("light.tv", on);
        assertEquals("turn_on", c[0]);
        JSONObject b = new JSONObject(c[1]);
        assertEquals("light.tv", b.getString("entity_id"));
        assertEquals(120, b.getInt("brightness"));
        assertEquals(10, b.getJSONArray("rgb_color").getInt(2));
        JSONObject ct = HomeAssistantHub.stateOf("{\"state\":\"on\",\"attributes\":{\"brightness\":90,\"color_mode\":\"color_temp\",\"color_temp_kelvin\":2700,\"rgb_color\":[255,180,100]}}");
        assertEquals(2700, new JSONObject(HomeAssistantHub.restoreCall("light.a", ct)[1]).getInt("color_temp_kelvin"));
        JSONObject off = HomeAssistantHub.stateOf("{\"state\":\"off\",\"attributes\":{\"brightness\":null}}");
        assertEquals("turn_off", HomeAssistantHub.restoreCall("light.a", off)[0]);
    }
}
