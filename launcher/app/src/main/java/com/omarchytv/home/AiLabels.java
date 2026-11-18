package com.omarchytv.home;

import java.util.List;

/**
 * Row labels for the "ai credits" widget. Entries from one provider share their first words
 * ("Claude session", "Claude week", "Claude week (Fable)"): cut to a fixed width they all read "Claude…".
 * The shared words move to the widget's legend and each row keeps what tells it apart; the label column
 * takes the room the widget has instead of a fixed 8 characters.
 */
final class AiLabels {
    private AiLabels() {
    }

    /** Smallest bar, in characters, kept beside a long label. */
    static final int MIN_BAR_COLS = 5;
    /** The "100%" column. */
    static final int PCT_COLS = 4;

    /**
     * The leading whole words every name starts with ("Claude"), or "" when there is none, when there is a single
     * name, or when a name would be left empty (that name is the provider itself).
     */
    static String commonPrefix(List<String> names) {
        if (names == null || names.size() < 2) return "";
        String[] first = words(names.get(0));
        int n = first.length;
        for (String name : names) {
            String[] w = words(name);
            int k = 0;
            while (k < n && k < w.length && w[k].equals(first[k])) k++;
            n = k;
            if (w.length <= n) n = w.length - 1;   // never take a whole name: something must remain on its row
        }
        if (n <= 0) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(i == 0 ? "" : " ").append(first[i]);
        return b.toString();
    }

    /** {@code name} without the shared {@code prefix} (and the blank after it). */
    static String strip(String name, String prefix) {
        if (prefix.isEmpty() || !name.trim().startsWith(prefix)) return name.trim();
        String rest = name.trim().substring(prefix.length()).trim();
        return rest.isEmpty() ? name.trim() : rest;
    }

    /**
     * Label column width in characters: the longest label, at least 4, and at most what leaves a
     * {@link #MIN_BAR_COLS} bar and the percentage in {@code availCols} (≤ 0 = unknown yet: at most 8, as before).
     */
    static int labelCols(List<String> labels, int availCols) {
        int longest = 4;
        for (String l : labels) longest = Math.max(longest, l.length());
        int max = availCols > 0 ? availCols - PCT_COLS - MIN_BAR_COLS - 2 : 8;
        return Math.max(4, Math.min(longest, max));
    }

    private static String[] words(String s) {
        String t = s == null ? "" : s.trim();
        return t.isEmpty() ? new String[0] : t.split("\\s+");
    }
}
