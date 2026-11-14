package com.omarchytv.home;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

/** Media keys stay with Radiomarchy after another app has taken audio focus. */
public class RadioPlayerTest {
    @Test
    public void mediaKeysAreTheTransportButtons() {
        assertTrue(RadioPlayer.isMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY));
        assertTrue(RadioPlayer.isMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        assertTrue(RadioPlayer.isMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE));
        assertTrue(RadioPlayer.isMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT));
        assertTrue(RadioPlayer.isMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        assertTrue(RadioPlayer.isMediaKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD));
        assertTrue(RadioPlayer.isMediaKey(KeyEvent.KEYCODE_MEDIA_REWIND));
        assertTrue(RadioPlayer.isMediaKey(KeyEvent.KEYCODE_HEADSETHOOK));
        // channel keys change workspace on Home; they are not stolen here
        assertFalse(RadioPlayer.isMediaKey(KeyEvent.KEYCODE_CHANNEL_UP));
        assertFalse(RadioPlayer.isMediaKey(KeyEvent.KEYCODE_DPAD_CENTER));
        assertFalse(RadioPlayer.isMediaKey(KeyEvent.KEYCODE_BACK));
    }
}
