package com.omarchytv.home;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Build;

import com.omarchytv.home.link.NightShift;

import org.json.JSONObject;

/**
 * v2.22 the night shift's morning: at the wake time the TV opens the shift page. With exact alarms allowed it is an
 * alarm clock (the system starts the page, and lights the screen where the TV lets it); without, or when the TV was
 * off, the first Home after the wake time opens it ({@link #dueOnHome}). Never twice for one shift.
 */
final class ShiftWake {
    private ShiftWake() {
    }

    private static PendingIntent pi(Context c) {
        return PendingIntent.getActivity(c, 0xA11E, ShiftActivity.intent(c, true),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Plans (or cancels) the alarm for the shift in the config. */
    static void plan(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        JSONObject s = LauncherConfig.readJson(c).optJSONObject("shift");
        long wake = s != null && s.optBoolean("active") ? s.optLong("wake") : 0;
        am.cancel(pi(c));
        if (wake <= System.currentTimeMillis()) return;
        try {
            boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            if (exact) am.setAlarmClock(new AlarmManager.AlarmClockInfo(wake, pi(c)), pi(c));
        } catch (SecurityException ignored) {
            // exact alarms not allowed on this TV: the first Home after the wake time opens the page
        }
    }

    /** The Home is in front: open the page once if the wake time of the last shift has passed. */
    static boolean dueOnHome(Context c) {
        JSONObject cfg = LauncherConfig.readJson(c);
        JSONObject s = cfg.optJSONObject("shift");
        long now = System.currentTimeMillis();
        if (s == null || s.optLong("wake") == 0 || now < s.optLong("wake") || s.optBoolean("wakeShown")) return false;
        if (now - s.optLong("wake") > 18L * 3600_000L) return false;   // a day later it is old news
        try {
            LauncherConfig.updateOrThrow(c, j -> {
                JSONObject x = j.optJSONObject("shift");
                if (x != null) x.put("wakeShown", true);
            });
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    /** For the Home's notes: a shift is running and alerts are kept for the morning. */
    static boolean quiet(Context c) {
        return NightShift.quiet(LauncherConfig.readJson(c), System.currentTimeMillis());
    }
}
