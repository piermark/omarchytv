package com.omarchytv.home;

import java.util.ArrayList;
import java.util.List;

/**
 * Live TV entry points, per platform.
 *
 * <p>Amazon Fire OS ships {@code com.amazon.tv.livetv} (the Live TV / DTV guide app) on every
 * Fire TV and Fire Stick. Its real launcher activity is permission-gated
 * ({@code com.amazon.tv.livetv.permission.SHOW_EPG} / {@code TUNE_CHANNEL}), so starting it
 * directly throws a {@code SecurityException}. The app exposes unguarded wrapper activities for
 * exactly this purpose:
 *
 * <ul>
 *   <li>{@code .GuideWrapperActivity} &rarr; opens the Live TV channel guide (EPG), with the
 *       channel list and a programme timeline.</li>
 * </ul>
 *
 * <p>So on Fire OS the TV tile must target the wrapper, not the launcher. On every other platform
 * (Sony, Sharp, Philips, Google TV, DTVKit) the plain launch intent is correct and is kept.
 *
 * <p>Without this, a Fire Stick has no Live TV package in the configured candidate list, no HDMI
 * passthrough input and no DTV tuner, so the TV tile falls through to the generic
 * {@code ACTION_VIEW content://media/tv/channels} intent, which no app on the device handles.
 */
final class LiveTv {

    /** The Fire OS Live TV package. */
    static final String FIRE_LIVE_TV = "com.amazon.tv.livetv";

    /** The unguarded wrapper activity that opens Amazon's Live TV guide. */
    static final String FIRE_GUIDE_WRAPPER = "com.amazon.tv.livetv/.GuideWrapperActivity";

    private LiveTv() {
    }

    /**
     * The activity to launch for a given Live TV package, or {@code null} when the package is not
     * one we know how to enter.
     */
    static String entryActivity(String pkg) {
        if (pkg == null) return null;
        if (FIRE_LIVE_TV.equals(pkg)) return FIRE_GUIDE_WRAPPER;
        return null;
    }

    /**
     * The Live TV candidate packages, most specific first.
     *
     * <p>{@code com.amazon.tv.livetv} is always included so a Fire OS device can reach the Live TV
     * guide even when the device-specific list does not mention it.
     */
    static List<String> candidates(List<String> configured) {
        List<String> out = new ArrayList<>();
        if (configured != null) {
            for (String p : configured) {
                if (p == null) continue;
                String t = p.trim();
                if (!t.isEmpty() && !out.contains(t)) out.add(t);
            }
        }
        if (!out.contains(FIRE_LIVE_TV)) out.add(FIRE_LIVE_TV);
        return out;
    }
}
