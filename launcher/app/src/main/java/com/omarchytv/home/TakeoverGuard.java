package com.omarchytv.home;

/**
 * When {@link HomeOverride} may bring OmarchyTV over the stock home (issue #58, Fire OS 7). Pure logic, unit-tested.
 * <ul>
 *   <li>Events for the same home activity within {@link #COALESCE_MS} are one Home press: Fire OS sends two
 *       TYPE_WINDOW_STATE_CHANGED events per press, which used to count twice against the loop guard.</li>
 *   <li>Only a takeover that is actually started is recorded, so a burst of stock-home events while the guard is
 *       tripped no longer keeps it tripped.</li>
 *   <li>At most {@link #LOOP_LIMIT} takeovers in {@link #LOOP_MS}: more means OmarchyTV is not staying up, so stop.</li>
 * </ul>
 */
final class TakeoverGuard {
    static final long COALESCE_MS = 1_000L;
    static final int LOOP_LIMIT = 4;
    static final long LOOP_MS = 15_000L;

    private final long[] recent = new long[LOOP_LIMIT];
    private int next;
    private String lastKey;
    private long lastAt = Long.MIN_VALUE / 2;

    /** An event for the stock home activity key at now (elapsedRealtime ms): true to start OmarchyTV. */
    synchronized boolean allow(String key, long now) {
        boolean duplicate = key != null && key.equals(lastKey) && now - lastAt >= 0 && now - lastAt < COALESCE_MS;
        lastKey = key;
        lastAt = now;
        if (duplicate) return false;
        long oldest = recent[next];
        return oldest == 0 || now - oldest >= LOOP_MS;
    }

    /** OmarchyTV was started at now: count it against the loop guard. */
    synchronized void started(long now) {
        recent[next] = now;
        next = (next + 1) % LOOP_LIMIT;
    }
}
