package com.omarchytv.home;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** v2.21: panes the PC pushes (rooms, widgets.json) reach the v2.9 Home, which only drew fetched panes before. */
public class HomePcPanesTest {
    @Test
    public void pushedPanesBecomeHomePanesExceptThePcBox() throws Exception {
        JSONArray panes = new JSONArray()
                .put(new JSONObject().put("id", "pc").put("title", "PC").put("lines", new JSONArray()))
                .put(new JSONObject().put("id", "room-hermes").put("title", "hermes · room").put("ttl", 86400).put("at", 1234L)
                        .put("lines", new JSONArray().put(new JSONObject().put("label", "09:07 cron").put("value", "Backup done"))))
                .put(new JSONObject().put("id", "nas").put("title", "NAS").put("lines", new JSONArray()));
        JSONObject j = new JSONObject().put("link", new JSONObject().put("panes", panes).put("widgetsAt", 999L))
                .put("pcWidgets", new JSONObject().put("hidden", new JSONArray().put("nas")));
        LauncherConfig cfg = new LauncherConfig(null, j, 0L);
        List<JSONObject> out = HomeV29.pcPaneObjects(cfg);
        assertEquals(1, out.size());   // "pc" is the PC box, "nas" is hidden
        JSONObject r = out.get(0);
        assertEquals("hermes · room", r.getString("title"));
        assertEquals(86400, r.getInt("ttl"));
        assertEquals(1234L, r.getLong("at"));
        assertEquals("Backup done", r.getJSONArray("lines").getJSONObject(0).getString("value"));

        LauncherConfig off = new LauncherConfig(null, new JSONObject(j.toString()).put("pcWidgets", new JSONObject().put("panes", false)), 0L);
        assertTrue(HomeV29.pcPaneObjects(off).isEmpty());
    }
}
