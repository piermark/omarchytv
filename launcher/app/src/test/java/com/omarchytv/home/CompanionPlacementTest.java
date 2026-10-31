package com.omarchytv.home;

import android.view.Gravity;
import android.view.KeyEvent;

import org.junit.Test;

import static org.junit.Assert.*;

public class CompanionPlacementTest {
    @Test
    public void cornersCycleAndUnknownFallsBack() {
        assertEquals("bottom-right", CompanionPlacement.corner("middle"));
        assertEquals("bottom-left", CompanionPlacement.next("bottom-right"));
        assertEquals("bottom-right", CompanionPlacement.next("top-left"));
        assertEquals(Gravity.TOP | Gravity.START, CompanionPlacement.gravity("top-left"));
        assertEquals(Gravity.BOTTOM | Gravity.END, CompanionPlacement.gravity(null));
    }

    @Test
    public void offsetsAreClampedAndKeepClearOfTheDock() {
        assertEquals(0, CompanionPlacement.offset(-5));
        assertEquals(CompanionPlacement.MAX_OFFSET_DP, CompanionPlacement.offset(9999));
        assertEquals(10 + 120, CompanionPlacement.safeVertical("bottom-left", 10, 120, 40));
        assertEquals(10 + 40, CompanionPlacement.safeVertical("top-right", 10, 120, 40));
    }

    @Test
    public void movementIsGentle() {
        assertEquals(0f, CompanionPlacement.bob(0), 1e-4);
        for (long t = 0; t < 10_000; t += 97) assertTrue(Math.abs(CompanionPlacement.bob(t)) <= CompanionPlacement.BOB_DP + 1e-4);
        assertEquals(3500, CompanionPlacement.blinkDelay(-1));
        assertEquals(7000, CompanionPlacement.blinkDelay(2));
    }

    @Test
    public void questionsGoToThePcCompanionOnlyWhenItIsThere() {
        assertEquals("companion", CompanionPlacement.route("Bob", true, true));
        assertEquals("ask", CompanionPlacement.route("Bob", false, true));
        assertEquals("ask", CompanionPlacement.route("", true, true));
        assertEquals("ask", CompanionPlacement.route("Bob", true, false));
    }

    @org.junit.Test
    public void orbIsGrokBotsOwnMark() {
        org.junit.Assert.assertEquals("orb", GrokbotMark.family("orb"));
        org.junit.Assert.assertEquals("round", GrokbotMark.family("circle"));
        org.junit.Assert.assertEquals(0xFFE06512, GrokbotMark.GROKBOT_ORANGE);
    }

    @Test
    public void blueKeyOpensTheChatUnlessAskHasIt() {
        assertTrue(CompanionPlacement.opensChat(KeyEvent.KEYCODE_PROG_BLUE, "none"));
        assertTrue(CompanionPlacement.opensChat(KeyEvent.KEYCODE_PROG_BLUE, "red"));
        assertFalse(CompanionPlacement.opensChat(KeyEvent.KEYCODE_PROG_BLUE, "blue"));
        assertFalse(CompanionPlacement.opensChat(KeyEvent.KEYCODE_PROG_RED, "none"));
        assertFalse(CompanionPlacement.opensChat(KeyEvent.KEYCODE_MENU, "none"));
    }
}
