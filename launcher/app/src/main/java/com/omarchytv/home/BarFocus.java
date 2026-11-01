package com.omarchytv.home;

import java.util.List;
import java.util.function.Predicate;

/**
 * ◀▶ in the top bar. Pure logic (unit-tested): from the focused item, step to the next usable one
 * (visible and focusable), skipping hidden workspace slots. {@link #next} does not wrap: at the ends it stays.
 * {@link #wrap} gives the opposite end for callers that want the bar to loop.
 */
final class BarFocus {
    private BarFocus() {
    }

    /** Index to focus next, or -1 to stay (current not in the list, or nothing usable that way). */
    static <T> int next(List<T> order, T current, boolean right, Predicate<? super T> usable) {
        int i = order.indexOf(current);
        if (i < 0) return -1;
        for (int to = i + (right ? 1 : -1); to >= 0 && to < order.size(); to += right ? 1 : -1) {
            if (usable.test(order.get(to))) return to;
        }
        return -1;
    }

    /**
     * Index of the usable item at the opposite end, for a bar that loops: ▶ off the last usable item goes to the
     * first, ◀ off the first goes to the last. -1 if the current item is unknown, is not at that end, or is the
     * only usable item.
     */
    static <T> int wrap(List<T> order, T current, boolean right, Predicate<? super T> usable) {
        int i = order.indexOf(current);
        if (i < 0 || next(order, current, right, usable) >= 0) return -1;
        for (int to = right ? 0 : order.size() - 1; to != i && to >= 0 && to < order.size(); to += right ? 1 : -1) {
            if (usable.test(order.get(to))) return to;
        }
        return -1;
    }

    /** ▶ on the last usable item (nothing further right): the floating companion is the next stop. */
    static <T> boolean pastEnd(List<T> order, T current, boolean right, Predicate<? super T> usable) {
        return right && order.indexOf(current) >= 0 && next(order, current, true, usable) < 0;
    }
}
