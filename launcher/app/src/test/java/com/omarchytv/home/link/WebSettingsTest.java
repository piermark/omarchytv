package com.omarchytv.home.link;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** v2.22 #163: the settings list behind the web page, app tokens and MCP. */
public class WebSettingsTest {
    static Map<String, List<String>> choices() {
        Map<String, List<String>> m = new HashMap<>();
        m.put("theme", Arrays.asList("last-horizon", "tokyo-night"));
        m.put("language", Arrays.asList("en", "it"));
        return m;
    }

    @Test
    public void schemaListsEveryFieldOnceWithoutSecrets() throws Exception {
        JSONObject s = WebSettings.schema(choices());
        JSONArray secs = s.getJSONArray("sections");
        int n = 0;
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (int i = 0; i < secs.length(); i++) {
            JSONArray f = secs.getJSONObject(i).getJSONArray("fields");
            for (int k = 0; k < f.length(); k++) {
                assertTrue(keys.add(f.getJSONObject(k).getString("key")));
                if ("secret".equals(f.getJSONObject(k).getString("type"))) assertFalse(f.getJSONObject(k).has("default"));
                n++;
            }
        }
        assertEquals(WebSettings.FIELDS.size(), n);
        assertTrue(s.toString().contains("tokyo-night"));
    }

    @Test
    public void valuesNeverCarryASecret() throws Exception {
        JSONObject cfg = new JSONObject().put("uiSize", "large").put("theme", new JSONObject().put("name", "tokyo-night"))
                .put("plugins", new JSONObject().put("radio", true));
        JSONObject ext = new JSONObject().put("ha.url", "http://h:8123").put("ha.token", true);
        JSONObject v = WebSettings.values(cfg, ext, null);
        assertEquals("large", v.getJSONObject("values").getString("uiSize"));
        assertEquals("tokyo-night", v.getJSONObject("values").getString("theme"));
        assertTrue(v.getJSONObject("values").getBoolean("plugins.radio"));
        assertFalse(v.getJSONObject("values").has("ha.token"));
        assertTrue(v.getJSONObject("secrets").getJSONObject("ha.token").getBoolean("set"));
        assertEquals(1, WebSettings.values(cfg, ext, "homeassistant").getJSONObject("values").length());
    }

    @Test
    public void checkRefusesTheWholeRequestOnOneBadValue() throws Exception {
        bad("{\"uiSize\":\"huge\"}", "uiSize");
        bad("{\"widgetPageRotateSec\":1.5}", "widgetPageRotateSec");
        bad("{\"widgetPageRotateSec\":99999}", "widgetPageRotateSec");
        bad("{\"weather.lat\":91}", "weather.lat");
        bad("{\"ha.url\":\"http://h:8123/api\"}", "ha.url");
        bad("{\"ha.url\":\"javascript:alert(1)\"}", "ha.url");
        bad("{\"ha.token\":\"a b\"}", "ha.token");
        bad("{\"weather.place\":\"x\\u0000y\"}", "weather.place");
        bad("{\"randomTheme\":\"yes\"}", "randomTheme");
        bad("{\"link.token\":\"x\"}", "unknown");
        bad("{}", "nothing");
        assertEquals("http://192.168.1.5:8123", WebSettings.check(new JSONObject("{\"ha.url\":\"http://192.168.1.5:8123/\"}"), choices())
                .values().iterator().next());
        assertEquals(JSONObject.NULL, WebSettings.check(new JSONObject("{\"weather.lat\":null}"), choices()).values().iterator().next());
    }

    @Test
    public void writeKeepsTheRestOfTheConfig() throws Exception {
        JSONObject cfg = new JSONObject().put("plugins", new JSONObject().put("omastorm", true)).put("link", new JSONObject().put("pcs", 1));
        WebSettings.write(cfg, WebSettings.field("plugins.radio"), true);
        WebSettings.write(cfg, WebSettings.field("link.notifySound"), false);
        WebSettings.write(cfg, WebSettings.field("theme"), "tokyo-night");
        assertTrue(cfg.getJSONObject("plugins").getBoolean("omastorm"));
        assertTrue(cfg.getJSONObject("plugins").getBoolean("radio"));
        assertEquals(1, cfg.getJSONObject("link").getInt("pcs"));
        assertEquals("tokyo-night", cfg.getJSONObject("theme").getString("name"));
    }

    private static void bad(String json, String expect) throws Exception {
        try {
            WebSettings.check(new JSONObject(json), choices());
            fail(json);
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(expect));
        }
    }
}
