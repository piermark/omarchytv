package com.omarchytv.home;

import android.view.KeyEvent;

/**
 * v2.14 Omastorm on the TV: remote keys -> Omastorm's own actions (the PC presses its key bindings in the window).
 * ◀▲▼▶ pan · OK / ⏯ play-pause the loop · ⏩ / CH+ next frame · ⏪ / CH− previous · 1 2 3 Pixels / Glyphs / Stipple ·
 * 4 nearest radar · 5 zoom in · 6 zoom out · 7 all radars (mosaic) · 8 my location · 9 aviation · 0 reset ·
 * MENU / INFO the key sheet · BACK closes.
 */
final class OmastormKeys {
    private OmastormKeys() {
    }

    static String of(int kc) {
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return "left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "right";
            case KeyEvent.KEYCODE_DPAD_UP: return "up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "down";
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE: return "play";
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_CHANNEL_UP: return "next";
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_CHANNEL_DOWN: return "prev";
            case KeyEvent.KEYCODE_1: return "pixels";
            case KeyEvent.KEYCODE_2: return "glyphs";
            case KeyEvent.KEYCODE_3: return "stipple";
            case KeyEvent.KEYCODE_4: return "nearest";
            case KeyEvent.KEYCODE_5: return "zoom_in";
            case KeyEvent.KEYCODE_6: return "zoom_out";
            case KeyEvent.KEYCODE_7: return "mosaic";
            case KeyEvent.KEYCODE_8: return "locate";
            case KeyEvent.KEYCODE_9: return "aviation";
            case KeyEvent.KEYCODE_0: return "reset";
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO: return "help";
            default: return null;
        }
    }
}
