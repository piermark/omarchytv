package com.omarchytv.home;

import android.content.Context;

/**
 * v2.15 Pulse on the TV, the PC's side: the latest Pulse state the paired PC pushed through omarchy-link (POST
 * /v1/pulse), kept in memory only, and the effects the TV's own Pulse raises (for "Look up, now").
 */
final class PulseLink {
    private PulseLink() {
    }

    private static volatile String state;
    private static volatile long at;

    /** From LinkServer: the PC's Pulse state (JSON, already size-checked). Old after 30 s: the PC went away. */
    static void put(String json) {
        state = json;
        at = System.currentTimeMillis();
    }

    static String state() {
        String s = state;
        return s != null && System.currentTimeMillis() - at < 30_000 ? s : null;
    }

    /** An effect from the Pulse page (ISS overhead, liftoff, a Starlink train): logged; the page itself shows it. */
    static void onFx(Context c, String json) {
        android.util.Log.i("OmarchyTV", "pulse fx: " + (json != null && json.length() > 160 ? json.substring(0, 160) : json));
    }
}
