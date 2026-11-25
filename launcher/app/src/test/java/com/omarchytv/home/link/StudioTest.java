package com.omarchytv.home.link;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.TimeZone;

/** v2.22 the morning briefing, the agents' debate and the AI's scenes. */
public class StudioTest {
    static final TimeZone ROME = TimeZone.getTimeZone("Europe/Rome");
    static final long T0 = 1795212000000L;   // 2026-11-20 23:00 in Rome

    @Test
    public void theBriefingIsCheckedAndDueOnce() throws Exception {
        JSONObject cfg = new JSONObject();
        bad(() -> Studio.briefingSet(cfg, new JSONObject(), "a", T0, ROME), "bad_sections");
        JSONObject b = Studio.briefingSet(cfg, new JSONObject().put("sections", new JSONArray()
                .put(new JSONObject().put("title", "Overnight").put("lines", new JSONArray().put("352 tests green‮").put(""))))
                .put("say", "Good morning Marco.").put("wake", "07:30"), "hermes", T0, ROME);
        assertEquals("352 tests green", b.getJSONArray("sections").getJSONObject(0).getJSONArray("lines").getString(0));
        assertEquals(1, b.getJSONArray("sections").getJSONObject(0).getJSONArray("lines").length());
        assertFalse(Studio.briefingDue(cfg, T0));
        long wake = b.getLong("wake");
        assertTrue(Studio.briefingDue(cfg, wake + 1));
        cfg.getJSONObject("briefing").put("shown", true);
        assertFalse(Studio.briefingDue(cfg, wake + 1));
    }

    @Test
    public void onlyTheTvPicksTheWinner() throws Exception {
        Studio.resetDebates();
        JSONObject d = Studio.debateStart(new JSONObject().put("topic", "How should we cache the widgets?"), "owner", 1);
        String id = d.getString("id");
        Studio.debatePost(id, "Claude", new JSONObject().put("text", "An LRU of 32 bitmaps."), 2);
        Studio.debatePost(id, "Grok", new JSONObject().put("text", "Disk cache."), 3);
        Studio.debatePost(id, "Grok", new JSONObject().put("text", "Disk cache, 50 MB."), 4);   // replaces
        assertEquals(2, Studio.debate(id).getJSONArray("entries").length());
        assertEquals(id, Studio.debateOnAir().getString("id"));
        bad(() -> Studio.debatePost(id, "Hermes", new JSONObject().put("text", "x").put("preview", new JSONObject().put("kind", "html")), 5), "bad_preview");
        Studio.debateChoose(id, 1, 6);
        assertEquals("Grok", Studio.debate(id).getString("winner"));
        assertNull(Studio.debateOnAir());
        bad(() -> Studio.debatePost(id, "Hermes", new JSONObject().put("text", "late"), 7), "closed");
        bad(() -> Studio.debateChoose(id, 0, 8), "closed");
    }

    @Test
    public void scenesUseOnlyTheTvsOwnTools() throws Exception {
        JSONArray ok = new JSONArray().put(new JSONObject().put("tool", "tv_ha_run").put("args", new JSONObject().put("entity", "scene.movie")))
                .put(new JSONObject().put("tool", "tv_launch_app").put("args", new JSONObject().put("package", "com.netflix.ninja")));
        assertEquals(2, Studio.sceneSteps(ok).length());
        assertEquals("Home Assistant: scene.movie", Studio.describe(ok.getJSONObject(0)));
        bad(() -> Studio.sceneSteps(new JSONArray().put(new JSONObject().put("tool", "tv_settings_set"))), "bad_step");
        bad(() -> Studio.sceneSteps(new JSONArray().put(new JSONObject().put("tool", "tv_publish_page"))), "bad_step");
        JSONObject cfg = new JSONObject();
        Studio.sceneSave(cfg, "Movie night", ok, "hermes", 1);
        assertEquals(2, Studio.scene(cfg, "Movie night").getJSONArray("steps").length());
        bad(() -> Studio.scene(cfg, "nope"), "no_such_scene");
    }

    interface Call {
        void run() throws Exception;
    }

    static void bad(Call c, String code) {
        try {
            c.run();
            fail(code);
        } catch (Studio.Bad e) {
            assertEquals(code, e.code);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
