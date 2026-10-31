package com.omarchytv.home;

import java.util.List;
import java.util.function.Predicate;

/**
 * ◀▶ in the top bar. Pure logic (unit-tested): from the focused item, step to the next usable one
 * (visible and focusable), skipping hidden workspace slots. No wrap-around: at the ends focus stays.
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

    /** ▶ on the last usable item (nothing further right): the floating companion is the next stop. */
    static <T> boolean pastEnd(List<T> order, T current, boolean right, Predicate<? super T> usable) {
        return right && order.indexOf(current) >= 0 && next(order, current, true, usable) < 0;
    }
}
