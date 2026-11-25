package com.omarchytv.home.link;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.TimeZone;

/** v2.22: the night shift's diary and morning queue. */
public class NightShiftTest {
    static final TimeZone ROME = TimeZone.getTimeZone("Europe/Rome");
    static final long T0 = 1795212000000L;   // 2026-11-20 23:00 in Rome

    static JSONObject started(JSONObject cfg) throws Exception {
        NightShift.start(cfg, new JSONObject().put("tasks", new JSONArray().put("fix the red tests").put("draft the X post"))
                .put("until", "07:00").put("wake", "07:30"), "hermes", T0, ROME);
        return cfg;
    }

    @Test
    public void timesAreTheNextOnesInTheTvsZone() {
        long seven = NightShift.at("07:00", T0, ROME);
        assertEquals(8 * 3600_000L, seven - T0);
        assertEquals(23 * 3600_000L + 30 * 60_000L, NightShift.at("22:30", T0, ROME) - T0);
        assertEquals(T0 + 5, NightShift.at(T0 + 5, T0, ROME));
        assertEquals(-1, NightShift.at("7 o'clock", T0, ROME));
        assertEquals(-1, NightShift.at("24:00", T0, ROME));
    }

    @Test
    public void aShiftHasTasksAnEndAndOneAtATime() throws Exception {
        JSONObject cfg = new JSONObject();
        bad(() -> NightShift.start(cfg, new JSONObject().put("until", "07:00"), "a", T0, ROME), "bad_tasks");
        bad(() -> NightShift.start(cfg, new JSONObject().put("tasks", new JSONArray().put("x")).put("until", T0 + 17 * 3600_000L), "a", T0, ROME), "bad_until");
        started(cfg);
        assertEquals("running", NightShift.status(cfg, T0).getString("state"));
        assertTrue(NightShift.quiet(cfg, T0 + 1000));
        bad(() -> started(cfg), "running");
        assertEquals("ended", NightShift.status(cfg, T0 + 8 * 3600_000L).getString("state"));
        assertFalse(NightShift.quiet(cfg, T0 + 8 * 3600_000L));
    }

    @Test
    public void theDiaryIsCappedAndClosesAfterTheGrace() throws Exception {
        JSONObject cfg = started(new JSONObject());
        for (int i = 0; i < NightShift.MAX_LOG + 10; i++) NightShift.log(cfg, "hermes", new JSONObject().put("text", "line " + i), T0 + i);
        JSONArray log = NightShift.full(cfg, T0).getJSONArray("log");
        assertEquals(NightShift.MAX_LOG, log.length());
        assertEquals("line 10", log.getJSONObject(0).getString("text"));
        JSONObject ok = NightShift.log(cfg, "hermes", new JSONObject().put("level", "ok").put("text", "tests green‮").put("task", 0)
                .put("detail", "352 passed\n0 failed"), T0);
        assertEquals("tests green", ok.getString("text"));
        bad(() -> NightShift.log(cfg, "a", new JSONObject().put("level", "boom").put("text", "x"), T0), "bad_level");
        bad(() -> NightShift.log(cfg, "a", new JSONObject().put("text", "x").put("task", 9), T0), "bad_task");
        long end = cfg.getJSONObject("shift").getLong("until");
        NightShift.log(cfg, "a", new JSONObject().put("text", "late build"), end + NightShift.GRACE_MS - 1);
        bad(() -> NightShift.log(cfg, "a", new JSONObject().put("text", "too late"), end + NightShift.GRACE_MS), "no_shift");
        NightShift.end(cfg, T0 + 10);
        bad(() -> NightShift.log(cfg, "a", new JSONObject().put("text", "after the owner ended it"), T0 + 11), "no_shift");
    }

    @Test
    public void onlyTheOwnerAnswersTheQueueOnce() throws Exception {
        JSONObject cfg = started(new JSONObject());
        JSONObject q = NightShift.queue(cfg, "hermes", new JSONObject().put("title", "push tv-2.22").put("risk", "push").put("detail", "git push origin tv-2.22"), T0);
        assertEquals("q1", q.getString("id"));
        assertEquals("pending", NightShift.decision(cfg, "q1").getString("state"));
        assertEquals(1, NightShift.status(cfg, T0).getJSONObject("counts").getInt("waiting"));
        NightShift.answer(cfg, "q1", true, T0 + 5);
        assertEquals("approved", NightShift.decision(cfg, "q1").getString("state"));
        bad(() -> NightShift.answer(cfg, "q1", false, T0 + 6), "answered");
        bad(() -> NightShift.decision(cfg, "q9"), "no_such_item");
        for (int i = 0; i < NightShift.MAX_QUEUE; i++) NightShift.queue(cfg, "a", new JSONObject().put("title", "t" + i), T0);
        bad(() -> NightShift.queue(cfg, "a", new JSONObject().put("title", "one more"), T0), "too_many");
    }

    interface Call {
        void run() throws Exception;
    }

    static void bad(Call c, String code) {
        try {
            c.run();
            fail(code);
        } catch (NightShift.Bad e) {
            assertEquals(code, e.code);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
