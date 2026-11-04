package com.omarchytv.home;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/**
 * v2.10: the OmarchyTV screensaver window, in its own process (":saver"), so the launcher process never holds a
 * WebView. Opened by the launcher after the idle timeout on the home and by the menu preview; any key closes it
 * and the key is swallowed. Shows {@link TteSaver} (tte.js 37 effects -> bundle -> native logo).
 */
public class ScreensaverPreviewActivity extends Activity {

    private TteSaver saver;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherConfig.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        saver = new TteSaver(this);
        saver.window = getWindow();
        // v2.14: the WebView renderer died (low-RAM TV): close the screensaver, the Home screen is underneath.
        saver.onDead = () -> {
            if (!isFinishing()) {
                finish();
                overridePendingTransition(0, 0);
            }
        };
        if (getIntent() != null) {
            String fe = getIntent().getStringExtra("omarchytv.effect"), fm = getIntent().getStringExtra("omarchytv.mode");
            if (fe != null) saver.forceEffect = fe;
            if (fm != null) saver.forceMode = fm;
            if (getIntent().getBooleanExtra("omarchytv.swlayer", false)) saver.softwareLayer();
            long to = getIntent().getLongExtra("omarchytv.timeout", 0);
            if (to >= 1000 && to <= 60000) saver.drawTimeoutMs = to;
        }
        setContentView(saver.view());
    }

    @Override
    protected void onResume() {
        super.onResume();
        saver.start();
    }

    @Override
    protected void onPause() {
        saver.stop();
        super.onPause();
        finish();   // never left behind in the background
        overridePendingTransition(0, 0);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && !isFinishing()) {
            int kc = e.getKeyCode();
            if ((kc == KeyEvent.KEYCODE_M || kc == KeyEvent.KEYCODE_MUTE || kc == KeyEvent.KEYCODE_VOLUME_MUTE)
                    && saver != null && saver.isAquariumActive()) {
                saver.toggleSound();
                return true;
            }
            finish();
            overridePendingTransition(0, 0);
        }
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Own process: exit with the window so its WebView memory goes back to the TV right away.
        if (isFinishing()) android.os.Process.killProcess(android.os.Process.myPid());
    }
}
