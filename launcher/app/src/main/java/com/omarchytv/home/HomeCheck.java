package com.omarchytv.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;

/**
 * After a reboot (or a system update) Sony/Google boot receivers sometimes put the stock launcher back as the
 * home screen. This only notices it and asks, once per boot: it never changes the default home, never
 * disables or enables anything. "Don't ask again" turns it off for good.
 *
 * BOOT_COMPLETED: first starts Omarchy Link as a foreground service (when Link is enabled and a PC is paired,
 * whatever the default home is, see {@link LinkBoot}). Then one PackageManager lookup; if OmarchyTV is not the default home, the
 * launcher opens with the prompt (Back or "Not now" returns to whatever home is set).
 *
 * MY_PACKAGE_REPLACED: an update stops the process, so Link is started again (and nothing else).
 */
public final class HomeCheck extends BroadcastReceiver {
    static final String EXTRA_ASK = "homeCheck";
    private static final String PREFS = "home_check", NEVER = "never", BOOT = "askedBoot";

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            LinkBoot.startAtBoot(c);   // an update killed the process: bring Link back without waiting for Home
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) return;
        LinkBoot.startAtBoot(c);   // before the early return below: Link must not depend on the home check
        if (isDefaultHome(c) || !shouldAsk(c)) return;
        Intent i = new Intent(c, MainActivity.class).putExtra(EXTRA_ASK, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            c.startActivity(i);
        } catch (RuntimeException ignored) {   // the system may refuse a start from boot: the next resume asks
        }
    }

    /** The HOME intent resolves to this app (not a chooser, not another launcher). */
    static boolean isDefaultHome(Context c) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo r = c.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return r != null && r.activityInfo != null && c.getPackageName().equals(r.activityInfo.packageName);
    }

    /** Not turned off, and not asked yet since this boot. */
    static boolean shouldAsk(Context c) {
        SharedPreferences p = prefs(c);
        return !p.getBoolean(NEVER, false) && p.getLong(BOOT, 0) != bootId();
    }

    static void asked(Context c) {
        prefs(c).edit().putLong(BOOT, bootId()).apply();
    }

    static void never(Context c) {
        prefs(c).edit().putBoolean(NEVER, true).apply();
    }

    /** Boot time to the minute: the same for the whole boot, different after a reboot. */
    private static long bootId() {
        return (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000L;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
