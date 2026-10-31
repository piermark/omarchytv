package com.omarchytv.home;

import org.junit.Test;

import static org.junit.Assert.*;

/** Issue #58: Fire OS sends two window events per Home press, and bursts kept the loop guard tripped. */
public class TakeoverGuardTest {
    private static boolean press(TakeoverGuard g, String key, long now) {
        boolean ok = g.allow(key, now);
        if (ok) g.started(now);
        return ok;
    }

    @Test
    public void doubleEventIsOnePress() {
        TakeoverGuard g = new TakeoverGuard();
        String home = "com.amazon.tv.launcher/.ui.HomeActivity_vNext";
        assertTrue(press(g, home, 1_000));
        assertFalse(press(g, home, 1_290));    // same press, second event
        assertTrue(press(g, home, 8_000));
        assertFalse(press(g, home, 8_100));
        assertTrue(press(g, home, 12_000));    // three real presses in 15 s still work
        assertTrue(press(g, home, 14_000));    // and a fourth
    }

    @Test
    public void realLoopStillStops() {
        TakeoverGuard g = new TakeoverGuard();
        long t = 10_000;
        for (int i = 0; i < TakeoverGuard.LOOP_LIMIT; i++, t += 2_000) assertTrue(press(g, "h", t));
        assertFalse(press(g, "h", t));
    }

    @Test
    public void burstWhileTrippedDoesNotExtendIt() {
        TakeoverGuard g = new TakeoverGuard();
        for (int i = 0; i < TakeoverGuard.LOOP_LIMIT; i++) assertTrue(press(g, "h", 10_000 + i * 2_000L));
        for (long t = 17_000; t < 24_000; t += 1_500) assertFalse(press(g, "h", t));   // refused, not recorded
        assertTrue(press(g, "h", 10_000 + TakeoverGuard.LOOP_MS + 1));
    }

    @Test
    public void differentActivityIsNotCoalesced() {
        TakeoverGuard g = new TakeoverGuard();
        assertTrue(press(g, "a", 1_000));
        assertTrue(press(g, "b", 1_100));
    }
}
