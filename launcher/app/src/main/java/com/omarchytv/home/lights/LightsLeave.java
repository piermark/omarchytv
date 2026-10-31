package com.omarchytv.home.lights;

/**
 * What the lights hub does when the launcher goes to the background. Pure logic (unit-tested).
 * The lights carry the theme colour only while OmarchyTV is on screen; leaving it turns them off (default),
 * puts back the state found before OmarchyTV painted them, or keeps them as they are.
 */
public final class LightsLeave {
    public static final String OFF = "off", RESTORE = "restore", KEEP = "keep";
    public static final String[] POLICIES = {OFF, RESTORE, KEEP};
    /** A launcher activity handing over to another one (Home → Cinemarchy → player) stops and starts within this. */
    public static final long DEBOUNCE_MS = 2500;

    private LightsLeave() {
    }

    public static String policy(String v) {
        for (String p : POLICIES) if (p.equals(v)) return p;
        return OFF;
    }

    public static String next(String v) {
        String c = policy(v);
        for (int i = 0; i < POLICIES.length; i++) if (POLICIES[i].equals(c)) return POLICIES[(i + 1) % POLICIES.length];
        return OFF;
    }

    /** "none", "restore" or "off": restore without a snapshot falls back to off. */
    public static String action(String policy, boolean haveSnapshot) {
        String p = policy(policy);
        if (KEEP.equals(p)) return "none";
        if (RESTORE.equals(p)) return haveSnapshot ? "restore" : "off";
        return "off";
    }

    /** Take a snapshot on entering only for "restore", and only when none is kept from an earlier visit. */
    public static boolean takeSnapshot(String policy, boolean haveSnapshot) {
        return RESTORE.equals(policy(policy)) && !haveSnapshot;
    }

    /** Started-activity count after a start / stop; the launcher is "inside" while it is above zero. */
    public static int count(int started, boolean start) {
        return Math.max(0, started + (start ? 1 : -1));
    }
}
