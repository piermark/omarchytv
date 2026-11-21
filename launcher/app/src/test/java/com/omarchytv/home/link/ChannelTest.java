package com.omarchytv.home.link;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.TimeZone;

/** v2.22 Televideo AI and the agents' Guide. */
public class ChannelTest {
    static final TimeZone ROME = TimeZone.getTimeZone("Europe/Rome");
    static final long T0 = 1795212000000L;   // 2026-11-20 23:00 in Rome

    interface Call {
        void run() throws Exception;
    }

    static void bad(Call c, String code) throws Exception {
        try {
            c.run();
            fail("expected " + code);
        } catch (Studio.Bad e) {
            assertEquals(code, e.code);
        }
    }

    @Test
    public void teletextPagesAreTextOnlyAndNumbered() throws Exception {
        JSONObject cfg = new JSONObject();
        bad(() -> Teletext.number("100"), "bad_page");
        bad(() -> Teletext.number("900"), "bad_page");
        bad(() -> Teletext.number("abc"), "bad_page");
        assertEquals(101, Teletext.number(" 101"));
        JSONObject one = new JSONObject().put("title", "Build").put("lines", new JSONArray().put("tests 368/368 ✓‮ and a very long line that goes past forty")
                .put(new JSONObject().put("text", "lint ok").put("color", "green")));
        JSONObject p = Teletext.publish(cfg, 101, one, "hermes", T0);
        assertEquals(40, p.getJSONArray("lines").getJSONObject(0).getString("text").length());
        assertEquals("green", p.getJSONArray("lines").getJSONObject(1).getString("color"));
        bad(() -> Teletext.publish(cfg, 102, new JSONObject().put("title", "x").put("lines", new JSONArray().put(new JSONObject().put("text", "x").put("color", "pink"))), "a", T0), "bad_color");
        JSONArray many = new JSONArray();
        for (int i = 0; i < 23; i++) many.put("x");
        bad(() -> Teletext.publish(cfg, 102, new JSONObject().put("title", "x").put("lines", many), "a", T0), "too_many");
        Teletext.publish(cfg, 300, new JSONObject().put("title", "Home").put("lines", new JSONArray().put("21°")), "ha", T0);
        JSONObject idx = Teletext.page(cfg, 100);
        assertEquals(2, idx.getJSONArray("lines").length());
        assertTrue(idx.getJSONArray("lines").getJSONObject(0).getString("text").endsWith("101"));
        assertEquals(300, Teletext.step(cfg, 101, 1));
        assertEquals(100, Teletext.step(cfg, 300, 1));   // wraps to the index
        assertEquals(300, Teletext.step(cfg, 100, -1));
        assertEquals(300, Teletext.step(cfg, 200, 1));   // from a missing page, the next one
        assertEquals(101, Teletext.step(cfg, 200, -1));
        bad(() -> Teletext.page(cfg, 555), "no_such_page");
        Teletext.remove(cfg, 300);
        bad(() -> Teletext.remove(cfg, 300), "no_such_page");
        for (int i = 0; i < Teletext.MAX_PAGES - 1; i++) Teletext.publish(cfg, 400 + i, one, "a", T0);
        bad(() -> Teletext.publish(cfg, 899, one, "a", T0), "full");
        Teletext.publish(cfg, 101, one, "a", T0 + 1);   // replacing a page is always fine
    }

    @Test
    public void theGuideHasTheAgentsTheShiftAndTheBriefing() throws Exception {
        JSONObject cfg = new JSONObject();
        bad(() -> Guide.add(cfg, new JSONObject(), "a", T0, ROME), "bad_title");
        bad(() -> Guide.add(cfg, new JSONObject().put("title", "x").put("start", T0 + 49 * 3600_000L), "a", T0, ROME), "bad_start");
        bad(() -> Guide.add(cfg, new JSONObject().put("title", "x").put("start", "23:30").put("end", T0), "a", T0, ROME), "bad_end");
        JSONObject p = Guide.add(cfg, new JSONObject().put("title", "Refactor the widgets").put("start", "23:30").put("end", "01:00"), "Claude", T0, ROME);
        assertEquals("p1", p.getString("id"));
        assertEquals(T0 + 30 * 60_000L, p.getLong("start"));
        assertEquals(T0 + 120 * 60_000L, p.getLong("end"));   // "01:00" after the start: the next night
        Guide.add(cfg, new JSONObject().put("title", "Tests"), "Codex", T0, ROME);   // now, for an hour
        NightShift.start(cfg, new JSONObject().put("tasks", new JSONArray().put("fix")).put("until", "07:00"), "owner", T0, ROME);
        Studio.briefingSet(cfg, new JSONObject().put("sections", new JSONArray().put(new JSONObject().put("title", "x").put("lines", new JSONArray().put("y"))))
                .put("wake", "07:30"), "hermes", T0, ROME);
        JSONArray v = Guide.view(cfg, T0 + 60_000L);
        assertEquals(4, v.length());
        assertEquals("on_air", byId(v, "p2").getString("now"));
        assertEquals("next", byId(v, "p1").getString("now"));
        assertEquals("on_air", byId(v, "shift").getString("now"));
        assertEquals("next", byId(v, "briefing").getString("now"));
        Guide.update(cfg, "p2", new JSONObject().put("state", "failed").put("detail", "lint"), T0 + 120_000L);
        assertEquals("past", byId(Guide.view(cfg, T0 + 180_000L), "p2").getString("now"));
        Guide.update(cfg, "p1", new JSONObject().put("state", "on_air"), T0 + 180_000L);   // started early
        assertEquals("on_air", byId(Guide.view(cfg, T0 + 200_000L), "p1").getString("now"));
        bad(() -> Guide.update(cfg, "p1", new JSONObject().put("state", "maybe"), T0), "bad_state");
        bad(() -> Guide.update(cfg, "p9", new JSONObject(), T0), "no_such_programme");
        Guide.remove(cfg, "p2");
        assertEquals(3, Guide.view(cfg, T0 + 200_000L).length());
    }

    static JSONObject byId(JSONArray a, String id) throws Exception {
        for (int i = 0; i < a.length(); i++) if (id.equals(a.getJSONObject(i).getString("id"))) return a.getJSONObject(i);
        throw new AssertionError(id + " not in " + a);
    }
}
