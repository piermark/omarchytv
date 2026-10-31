package com.omarchytv.home;

import org.junit.Test;

import static org.junit.Assert.*;

/** Tom Ballard's avatar on Android: names, aliases, palette, data shape. Full JS parity: launcher/tools/test_grokbot_avatar.py. */
public class GrokbotAvatarTest {
    @Test
    public void fourteenShapesWithAliases() {
        assertEquals(14, GrokbotAvatar.COUNT);
        assertEquals("blob", GrokbotAvatar.resolve("nonsense"));
        assertEquals("pebble", GrokbotAvatar.resolve("pebble"));
        assertFalse(GrokbotAvatar.known("orb"));
        assertEquals("cloud", GrokbotAvatar.next("blob"));
        assertEquals("blob", GrokbotAvatar.next("pebble"));
        for (int i = 0; i < GrokbotAvatar.COUNT; i++) {
            assertEquals(240, GrokbotAvatar.points(i).length);
            assertEquals(10, GrokbotAvatar.eyes(i).length);
            assertEquals(84, GrokbotAvatar.look(i).length);
        }
    }

    @Test
    public void elevenColoursAndPoses() {
        assertEquals(11, GrokbotAvatar.COLOR_NAMES.length);
        assertEquals(0xFFFFFFFF, GrokbotAvatar.fill("black"));
        assertEquals(GrokbotAvatar.fill("orange"), GrokbotAvatar.fill("no-such-colour"));
        assertEquals(0.04f, GrokbotAvatar.pose("sleep")[2], 1e-6);
    }

    @Test
    public void gazeStaysFinite() {
        for (int i = 0; i < GrokbotAvatar.COUNT; i++) {
            GrokbotAvatar.Look l = GrokbotAvatar.look(i, 1f, -1f);
            assertFalse(Float.isNaN(l.turn));
            for (float v : l.eyes) assertFalse(Float.isNaN(v));
        }
    }
}
