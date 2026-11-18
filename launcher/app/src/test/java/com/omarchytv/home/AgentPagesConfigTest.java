package com.omarchytv.home;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public class AgentPagesConfigTest {
    @Test
    public void onlyValidPagesBecomeTilesWithTheBadgeInTheLabel() throws Exception {
        JSONArray a = new JSONArray()
                .put(new JSONObject().put("id", "dubai").put("name", "Dubai flats").put("url", "http://192.168.1.2/").put("badge", 3))
                .put(new JSONObject().put("id", "dubai").put("name", "dup").put("url", "http://192.168.1.3/"))
                .put(new JSONObject().put("id", "Bad Id").put("name", "x").put("url", "http://192.168.1.4/"))
                .put(new JSONObject().put("id", "web").put("name", "web").put("url", "https://example.com/"))
                .put(new JSONObject().put("id", "many").put("name", "Inbox").put("url", "http://10.0.0.2/").put("badge", 250));
        List<LauncherConfig.AgentPage> p = LauncherConfig.parsePages(new JSONObject().put("agentPages", a));
        assertEquals(2, p.size());
        assertEquals("Dubai flats · 3", p.get(0).label());
        assertEquals("Inbox · 99+", p.get(1).label());
    }
}
