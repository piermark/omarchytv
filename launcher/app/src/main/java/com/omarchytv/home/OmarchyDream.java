package com.omarchytv.home;

import android.service.dreams.DreamService;

/**
 * The OmarchyTV screensaver: Omarchy's ASCII-logo terminal screensaver, re-created natively
 * ({@link AsciiSaverView}). A standard DreamService: Android starts it only after the TV's idle
 * timeout and never over playing video (players hold a wake lock); the launcher never starts it.
 * Pick "OmarchyTV" in Settings → Screen saver, or over adb:
 * settings put secure screensaver_components com.omarchytv.home/.OmarchyDream
 */
public class OmarchyDream extends DreamService {

    private TteSaver view;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        view = new TteSaver(this);   // v2.9: identical to omarchy-screensaver (tte.js, 37 effects), black
        view.window = getWindow();
        view.onDead = this::finish;   // v2.14: the WebView renderer died: end the dream, back to the Home screen
        setContentView(view.view());
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        view.start();
    }

    @Override
    public void onDreamingStopped() {
        view.stop();
        super.onDreamingStopped();
    }
}
