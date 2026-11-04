package com.omarchytv.home;

import org.json.JSONObject;

import java.util.Locale;

/**
 * v2.14 Liftoff: what the big-screen countdown says for a build or an update (pure, no Android; mirrored by
 * webos/app/liftoff.js and checked against the same cases in LiftoffScriptTest and webos/tests/liftoff.test.js).
 * State (from omarchy-link, or the launcher's own update): {kind build|update, name, state running|ok|failed,
 * stage, elapsedS, expectedS?, pct?, code?, tail[]}.
 */
final class LiftoffScript {
    private LiftoffScript() {
    }

    /** The rocket, 7 rows. "OT" on the body like the boot screen's badge. */
    static final String[] ROCKET = {
            "    /\\    ",
            "   /  \\   ",
            "  |    |  ",
            "  | OT |  ",
            "  |    |  ",
            " /|____|\\ ",
            "/_/ /\\ \\_\\",
    };
    /** Exhaust while counting down (idle) and at liftoff (two frames each). */
    static final String[][] FLAME = {
            {"   .  .   ", "    ..    "},
            {"   )()(   ", "  ( )( )  "},
    };

    static String mmss(long s) {
        s = Math.max(0, s);
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60);
    }

    /** Seconds since the start, counting the time since the state arrived while it still runs. */
    static long elapsed(JSONObject s, long sinceReceivedS) {
        long e = Math.max(0, s.optLong("elapsedS", 0));
        return "running".equals(s.optString("state")) ? e + Math.max(0, sinceReceivedS) : e;
    }

    /** "T-00:42" while under the expected time, "T+01:12" past it (or with no estimate), "ABORT" on failure. */
    static String clock(JSONObject s, long sinceReceivedS) {
        String st = s.optString("state");
        if ("failed".equals(st)) return "ABORT";
        long e = elapsed(s, sinceReceivedS);
        long exp = s.optLong("expectedS", 0);
        if ("running".equals(st) && exp > 0 && e < exp) return "T-" + mmss(exp - e);
        return "T+" + mmss(e);
    }

    /** 0..100, or -1 when there is nothing to base it on. Never 100 before the end. */
    static int progress(JSONObject s, long sinceReceivedS) {
        String st = s.optString("state");
        if ("ok".equals(st)) return 100;
        if (s.has("pct") && !s.isNull("pct")) return Math.max(0, Math.min(99, s.optInt("pct")));
        long exp = s.optLong("expectedS", 0);
        if (exp <= 0) return -1;
        return (int) Math.min(99, elapsed(s, sinceReceivedS) * 100 / exp);
    }

    /** "[████████░░░░░░░░] 50%" (width cells), or "" without a value. */
    static String bar(int pct, int width) {
        if (pct < 0) return "";
        int n = Math.round(pct * width / 100f);
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < width; i++) b.append(i < n ? '█' : '░');
        return b.append("] ").append(pct).append('%').toString();
    }

    /** True while the countdown is in its last 10 seconds: the clock blinks. */
    static boolean finalCount(JSONObject s, long sinceReceivedS) {
        long exp = s.optLong("expectedS", 0);
        long left = exp - elapsed(s, sinceReceivedS);
        return "running".equals(s.optString("state")) && exp > 0 && left > 0 && left <= 10;
    }
}
