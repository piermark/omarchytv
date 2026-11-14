package com.omarchytv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The now-playing label in the top bar must never cover the centred clock. */
public class RadioBarTest {

    private static final int GAP = HomeV29.RADIO_BAR_GAP, MIN = HomeV29.RADIO_BAR_MIN;

    private static void fits(int barW, int clockW, int rightRestW, int gap, int max) {
        if (max == 0) return;
        int clockRight = (barW + clockW) / 2;
        assertTrue("label stays to the right of the clock", clockRight + gap + max + rightRestW <= barW);
    }

    @Test
    public void nowPlayingFitsBesideTheClock() {
        // 1920×1080: 8 icons + padding (~420), or with the SpaceX pill (~580), or weather too (~670)
        int c = 260;   // a 12-hour clock such as "wed 11 nov  5:58 AM" at 22 px Caskaydia
        int a = HomeV29.radioBarMaxWidth(1920, c, 420, GAP, MIN);
        int b = HomeV29.radioBarMaxWidth(1920, c, 580, GAP, MIN);
        int d = HomeV29.radioBarMaxWidth(1920, c, 670, GAP, MIN);
        assertEquals(394, a);
        assertEquals(234, b);
        assertEquals(144, d);
        fits(1920, c, 420, GAP, a);
        fits(1920, c, 580, GAP, b);
        fits(1920, c, 670, GAP, d);

        // 1280×720 and 1366×768, same design scaled (and with SpaceX)
        int s720Bar = 1280, s720Clock = 173, s720Rest = 387, s720Gap = 11, s720Min = 53;
        int m720 = HomeV29.radioBarMaxWidth(s720Bar, s720Clock, s720Rest, s720Gap, s720Min);
        assertTrue("room for a title at 720p with SpaceX", m720 >= s720Min);
        fits(s720Bar, s720Clock, s720Rest, s720Gap, m720);

        int s768Bar = 1366, s768Clock = 185, s768Rest = 412, s768Gap = 11, s768Min = 57;
        int m768 = HomeV29.radioBarMaxWidth(s768Bar, s768Clock, s768Rest, s768Gap, s768Min);
        assertTrue("room for a title at 1366×768 with SpaceX", m768 >= s768Min);
        fits(s768Bar, s768Clock, s768Rest, s768Gap, m768);

        // LG/Samsung: racing countdown also lives on the right; hide when nothing is left
        int crowded = HomeV29.radioBarMaxWidth(1920, c, 900, GAP, MIN);
        assertEquals(0, crowded);
        int exact = HomeV29.radioBarMaxWidth(1920, c, 1920 - (1920 + c) / 2 - GAP - MIN, GAP, MIN);
        assertEquals(MIN, exact);
        int shortByOne = HomeV29.radioBarMaxWidth(1920, c, 1920 - (1920 + c) / 2 - GAP - MIN + 1, GAP, MIN);
        assertEquals(0, shortByOne);
    }
}
