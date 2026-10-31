package com.omarchytv.home;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.Test;

public class BarFocusTest {
    // Same order as the top bar: menu, 5 workspace slots, SpaceX TV, then the right-hand icons.
    private static final List<String> BAR = Arrays.asList(
            "menu", "ws1", "ws2", "ws3", "ws4", "ws5", "spacex", "cinemarchy", "wifi", "bt", "vol", "input", "screen");

    private static Predicate<String> hidden(String... gone) {
        Set<String> g = new HashSet<>(Arrays.asList(gone));
        return s -> !g.contains(s);
    }

    private static String step(String from, boolean right, Predicate<String> usable) {
        int to = BarFocus.next(BAR, from, right, usable);
        return to < 0 ? null : BAR.get(to);
    }

    @Test
    public void rightFromTheLastVisibleWorkspaceReachesTheIcons() {
        // 4 workspaces (home, apps, pc, system), SpaceX TV off: "4" → Cinemarchy, not stuck on "4".
        assertEquals("cinemarchy", step("ws4", true, hidden("ws5", "spacex")));
    }

    @Test
    public void rightFromTheLastVisibleWorkspaceReachesSpacexWhenItIsOn() {
        assertEquals("spacex", step("ws4", true, hidden("ws5")));
        assertEquals("cinemarchy", step("spacex", true, hidden("ws5")));
        assertEquals("ws4", step("spacex", false, hidden("ws5")));
    }

    @Test
    public void hiddenSlotsAreSkippedBothWays() {
        Predicate<String> u = hidden("ws2", "ws3", "ws5", "spacex");
        assertEquals("ws4", step("ws1", true, u));
        assertEquals("ws1", step("ws4", false, u));
        assertEquals("ws4", step("cinemarchy", false, u));
    }

    @Test
    public void allVisibleIsPlainStepping() {
        Predicate<String> u = s -> true;
        assertEquals("ws1", step("menu", true, u));
        assertEquals("ws5", step("ws4", true, u));
        assertEquals("spacex", step("ws5", true, u));
        assertEquals("vol", step("input", false, u));
    }

    @Test
    public void endsDoNotWrap() {
        Predicate<String> u = hidden("ws5");
        assertEquals(null, step("menu", false, u));
        assertEquals(null, step("screen", true, u));
        // nothing usable further left of a hidden-only stretch: stay
        assertEquals(null, step("ws1", false, hidden("menu")));
    }

    @Test
    public void unknownCurrentStays() {
        assertEquals(-1, BarFocus.next(BAR, "clock", true, s -> true));
    }

    @Test
    public void rightOnTheLastIconGoesToTheCompanionOnlyThere() {
        org.junit.Assert.assertTrue(BarFocus.pastEnd(BAR, "screen", true, hidden()));
        org.junit.Assert.assertTrue(BarFocus.pastEnd(BAR, "input", true, hidden("screen")));
        org.junit.Assert.assertFalse(BarFocus.pastEnd(BAR, "input", true, hidden()));
        org.junit.Assert.assertFalse(BarFocus.pastEnd(BAR, "screen", false, hidden()));
        org.junit.Assert.assertFalse(BarFocus.pastEnd(BAR, "elsewhere", true, hidden()));
    }

    @Test
    public void spacexSitsBetweenTheWorkspacesAndCinemarchy() {
        assertEquals("spacex", step("ws5", true, hidden()));
        assertEquals("cinemarchy", step("spacex", true, hidden()));
        assertEquals("spacex", step("cinemarchy", false, hidden()));
    }
}
