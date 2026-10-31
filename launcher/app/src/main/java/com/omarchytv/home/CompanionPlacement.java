package com.omarchytv.home;

import android.view.Gravity;

/**
 * Where the floating Grok Bot companion sits on the home screen, and where a question goes.
 * Pure logic (unit-tested): corner names, clamped offsets, and the routing rule.
 * Built for @tcballard's Grok Bot Desktop Companion v0.1.0 (MIT); my own code.
 */
final class CompanionPlacement {
    static final String[] CORNERS = {"bottom-right", "bottom-left", "top-right", "top-left"};
    static final int MAX_OFFSET_DP = 240;
    /** Gentle movement: a few dp of bob, slow enough not to pull the eye from the dock. */
    static final int BOB_DP = 4;
    static final long BOB_PERIOD_MS = 4200;

    private CompanionPlacement() {
    }

    static String corner(String v) {
        for (String c : CORNERS) if (c.equals(v)) return c;
        return "bottom-right";
    }

    static String next(String v) {
        String c = corner(v);
        for (int i = 0; i < CORNERS.length; i++) if (CORNERS[i].equals(c)) return CORNERS[(i + 1) % CORNERS.length];
        return CORNERS[0];
    }

    static int gravity(String v) {
        String c = corner(v);
        return (c.startsWith("top") ? Gravity.TOP : Gravity.BOTTOM) | (c.endsWith("left") ? Gravity.START : Gravity.END);
    }

    static int offset(int dp) {
        return Math.max(0, Math.min(MAX_OFFSET_DP, dp));
    }

    /**
     * Bottom corners keep clear of the dock: the companion floats above it unless the user moved it further.
     * Top corners keep clear of the status bar row.
     */
    static int safeVertical(String v, int offsetDp, int dockDp, int topBarDp) {
        return offset(offsetDp) + (corner(v).startsWith("top") ? topBarDp : dockDp);
    }

    /** Bob in dp at time t: a sine, 0 at t = 0. */
    static float bob(long t) {
        return (float) (BOB_DP * Math.sin(2 * Math.PI * (t % BOB_PERIOD_MS) / BOB_PERIOD_MS));
    }

    /** Next blink delay: 3.5 to 7 s, from a 0..1 random. */
    static long blinkDelay(double r) {
        return 3500 + (long) (Math.max(0, Math.min(1, r)) * 3500);
    }

    /** "companion" = to @tcballard's companion on the PC through omarchy-link; "ask" = OmarchyTV's own Ask. */
    static String route(String mirroredBotName, boolean pcConnected, boolean bridgeOn) {
        return bridgeOn && pcConnected && mirroredBotName != null && !mirroredBotName.isEmpty() ? "companion" : "ask";
    }
}
