package com.omarchytv.home;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/**
 * Optional "OmarchyTV remote buttons" accessibility service: runs the bindings of omarchytv.json "keys" for single,
 * double, triple and long presses of a remote key, in any app. The accessibility input filter sees key events
 * before the system's own key hooks (such as the SHIELD Netflix button launching Netflix), so a bound key is
 * consumed here. A key is only touched if it has bindings AND comes from a remote listed in assets/remotes.json
 * (matched by vendor / product id) whose profile lists that button; anything else passes through. It listens to no events and reads no window content.
 * Enable in Settings > Accessibility, or over adb (replaces any other enabled services):
 *   adb shell settings put secure enabled_accessibility_services com.omarchytv.home/.KeyMapService
 *   adb shell settings put secure accessibility_enabled 1
 */
public class KeyMapService extends AccessibilityService {

    private static final String TAG = "OmarchyTV";
    /** After a key goes up: how long to wait for the next press of a multi-press. Real remotes repeat 200-300 ms apart. */
    private static final long MULTI_PRESS_MS = 350;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** The key being counted (-1 = none), its quick presses so far, and whether it is physically down. */
    private int activeKey = -1;
    private int count;
    private boolean down, longFired;

    private final Runnable longPress = () -> {
        if (activeKey < 0 || !down) return;
        longFired = true;
        count = 0;
        LauncherConfig.KeyBinding[] b = LauncherConfig.get(this).keys.get(activeKey);
        if (b != null) run(b[LauncherConfig.LONG_PRESS]);
    };

    private final Runnable resolve = this::resolveNow;

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        LauncherConfig.KeyBinding[] b = LauncherConfig.get(this).keys.get(code);
        if (b != null && !RemoteProfiles.handles(this, e)) b = null;   // only profiled buttons of known remotes
        if (b != null && code == KeyEvent.KEYCODE_HOME && homeGivenBack()) b = null;   // let the TV's own Home through
        if (b == null) {
            if (code == activeKey) reset();   // unbound while a press was in progress
            return false;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() > 0) return true;   // Android's auto-repeat: ignored
            if (activeKey >= 0 && activeKey != code) resolveNow();   // another key: settle the pending one first
            handler.removeCallbacks(resolve);
            activeKey = code;
            down = true;
            longFired = false;
            if (b[LauncherConfig.LONG_PRESS] != null) handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
        } else if (e.getAction() == KeyEvent.ACTION_UP && code == activeKey) {
            down = false;
            handler.removeCallbacks(longPress);
            if (longFired) {
                reset();
            } else {
                count++;
                if (count >= 3 || !LauncherConfig.keyWaitsForMore(b, count)) resolveNow();
                else handler.postDelayed(resolve, MULTI_PRESS_MS);
            }
        }
        return true;
    }

    private void resolveNow() {
        handler.removeCallbacks(resolve);
        LauncherConfig.KeyBinding[] b = LauncherConfig.get(this).keys.get(activeKey);
        int n = count;
        reset();
        if (b != null && n > 0) run(LauncherConfig.keyPressBinding(b, n));
    }

    /**
     * Same rule as HomeOverride: after the crash watchdog gave the home screen back, or in "Originale" mode,
     * a bound Home button must not keep opening OmarchyTV.
     */
    private boolean homeGivenBack() {
        return Watchdog.crashedTooOften(this) || Originale.isOriginal(this);
    }

    private void reset() {
        handler.removeCallbacks(longPress);
        handler.removeCallbacks(resolve);
        activeKey = -1;
        count = 0;
        down = false;
        longFired = false;
    }

    private void run(LauncherConfig.KeyBinding kb) {
        if (kb == null) return;
        try {
            switch (kb.type) {
                case LauncherConfig.KeyBinding.APP: {
                    PackageManager pm = getPackageManager();
                    Intent i = pm.getLeanbackLaunchIntentForPackage(kb.value);
                    if (i == null) i = pm.getLaunchIntentForPackage(kb.value);
                    if (i == null) throw new ActivityNotFoundException(kb.value);
                    startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    break;
                }
                case LauncherConfig.KeyBinding.INTENT: {
                    Intent i = Intent.parseUri(kb.value, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        startActivity(i);
                    } catch (ActivityNotFoundException nobody) {
                        sendBroadcast(i);   // not an activity: maybe a broadcast
                    }
                    break;
                }
                case LauncherConfig.KeyBinding.ACTION:
                    startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_ACTION, kb.value)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    break;
                default:
                    break;
            }
        } catch (Exception ex) {
            Log.w(TAG, "key binding " + kb.type + " " + kb.value + " failed: " + ex);
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        reset();
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
        reset();
    }
}
