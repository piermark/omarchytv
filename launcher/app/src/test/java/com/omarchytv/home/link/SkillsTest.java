package com.omarchytv.home.link;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** v2.22 skills: every skill in the catalogue passes the same checks as tv_widget_create; install and remove are clean. */
public class SkillsTest {
    static JSONObject load(String id) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(new File("src/main/assets/skills/" + id + ".json").toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void theCatalogueIsValid() throws Exception {
        File[] all = new File("src/main/assets/skills").listFiles((d, n) -> n.endsWith(".json"));
        assertTrue(all.length >= 3);
        for (File f : all) {
            JSONObject s = Skills.check(new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
            assertEquals(f.getName(), s.getString("id") + ".json");
        }
    }

    @Test
    public void installPutsAPageAndRemoveTakesItAway() throws Exception {
        JSONObject cfg = new JSONObject().put("widgets", new JSONArray().put(new JSONObject().put("id", "mine").put("type", "stat")
                .put("page", 1).put("x", 0).put("y", 0).put("w", 2).put("h", 1).put("data", new JSONObject())));
        JSONObject rec = Skills.install(cfg, load("homelab"), 1000);
        assertEquals(2, rec.getInt("page"));
        assertEquals(5, rec.getJSONArray("widgets").length());
        assertEquals("Homelab", HomePages.list(cfg, 1000).getJSONObject(1).getString("name"));
        Skills.install(cfg, load("homelab"), 2000);   // an update replaces, never doubles
        assertEquals(6, cfg.getJSONArray("widgets").length());
        Skills.remove(cfg, "homelab");
        assertEquals(1, cfg.getJSONArray("widgets").length());
        assertEquals("mine", cfg.getJSONArray("widgets").getJSONObject(0).getString("id"));
        assertEquals(1, HomePages.total(cfg));
        assertFalse(Skills.installed(cfg).has("homelab"));
    }

    @Test
    public void aSkillIsDataAndAsksOnlyKnownPermissions() throws Exception {
        bad(new JSONObject().put("id", "x!").put("name", "n").put("author", "a").put("version", "1.0"));
        bad(load("homelab").put("permissions", new JSONArray().put("widgets").put("root")));
        bad(load("homelab").put("permissions", new JSONArray().put("notify")));   // widgets without the permission
        bad(load("movie-night").put("permissions", new JSONArray().put("widgets")));   // HA widgets without ha.read
        JSONObject evil = load("homelab");
        evil.getJSONArray("widgets").put(new JSONObject().put("id", "x").put("type", "page").put("data", new JSONObject().put("url", "https://evil.example")));
        bad(evil);   // a page widget only opens LAN pages
    }

    static void bad(JSONObject s) {
        try {
            Skills.check(s);
            fail(s.toString());
        } catch (HomeWidgets.Bad expected) {
            // refused
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
