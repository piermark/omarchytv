package com.omarchytv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.view.KeyEvent;

import org.junit.Test;

/** v2.14 Omastorm on the TV: the remote keys map to Omastorm's actions (names the PC knows in omastorm.KEYS). */
public class OmastormKeysTest {
    @Test
    public void mapping() {
        assertEquals("left", OmastormKeys.of(KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals("play", OmastormKeys.of(KeyEvent.KEYCODE_DPAD_CENTER));
        assertEquals("next", OmastormKeys.of(KeyEvent.KEYCODE_CHANNEL_UP));
        assertEquals("mosaic", OmastormKeys.of(KeyEvent.KEYCODE_7));
        assertEquals("help", OmastormKeys.of(KeyEvent.KEYCODE_MENU));
        assertNull(OmastormKeys.of(KeyEvent.KEYCODE_BACK));
        assertNull(OmastormKeys.of(KeyEvent.KEYCODE_VOLUME_UP));
    }

    @Test
    public void xApp() {
        assertEquals(true, XKeys.isX("x", "https://x.com/home"));
        assertEquals(false, XKeys.isX("grok", "https://x.com/i/grok"));
        assertEquals(false, XKeys.isX("chatgpt", "https://chatgpt.com"));
    }
}
