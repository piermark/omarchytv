package com.omarchytv.home;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * The ASCII logos shared by the home wordmark, Cinemarchy hub, and the screensaver. Config value ("logo"):
 * <ul>
 * <li>"omarchy-tv" (default): Omarchy's logo.txt (MIT) with a matching "TV" drawn in the same
 *     half-block style (res/raw/omarchy_tv_logo.txt; the TV glyphs are original).</li>
 * <li>"omarchy": Omarchy's logo.txt unchanged (MIT, Copyright (c) David Heinemeier Hansson).</li>
 * <li>"cinemarchy": 35mm film-reel frame + "CINE" in Omarchy half-block style + Omarchy's "MARCHY"
 *     verbatim from logo.txt (MIT, Copyright (c) David Heinemeier Hansson).</li>
 * <li>"radiomarchy" (v2.20, the music app): "RADIO" in the same half-block style (R and A cut from logo.txt, O from
 *     its O, I from CINE, D drawn to match) + Omarchy's "MARCHY" verbatim from logo.txt (MIT, Copyright (c) David
 *     Heinemeier Hansson).</li>
 * <li>"original": OmarchyTV's own block font ({@link BlockFont}), no Omarchy artwork at all.</li>
 * </ul>
 */
final class Art {

    /** First column of "TV" in omarchy_tv_logo.txt. */
    private static final int OMARCHY_TV_SPLIT = 86;

    /** First column of "MARCHY" in cinemarchy_logo.txt (columns < 40 are "CINE"). */
    static final int CINEMARCHY_SPLIT = 40;

    /** First column of "MARCHY" in radiomarchy_logo.txt (columns < 53 are "RADIO"). */
    static final int RADIOMARCHY_SPLIT = 53;

    private Art() {
    }

    static String normalise(String which) {
        if ("omarchy".equals(which)) return "omarchy";
        if ("cinemarchy".equals(which)) return "cinemarchy";
        if ("radiomarchy".equals(which)) return "radiomarchy";
        if ("aquarium".equals(which)) return "aquarium";
        if ("aquarium-classic".equals(which)) return "aquarium-classic";
        if ("original".equals(which) || "omarchytv".equals(which)) return "original";
        return "omarchy-tv";
    }

    static String load(Context c, String which) {
        switch (normalise(which)) {
            case "omarchy": return raw(c, R.raw.omarchy_logo);
            case "cinemarchy": return raw(c, R.raw.cinemarchy_logo);
            case "radiomarchy": return raw(c, R.raw.radiomarchy_logo);
            case "original": return blockFontArt();
            default: return raw(c, R.raw.omarchy_tv_logo);
        }
    }

    /** Column where the second colour zone starts, or -1. */
    static int split(String which) {
        switch (normalise(which)) {
            case "omarchy": return -1;
            case "cinemarchy": return CINEMARCHY_SPLIT;
            case "radiomarchy": return RADIOMARCHY_SPLIT;
            case "original": return BlockFont.splitColumn(WordmarkView.TEXT);
            default: return OMARCHY_TV_SPLIT;
        }
    }

    /** True when columns BEFORE {@link #split(String)} get the accent gradient and columns AFTER get foreground ("cinemarchy"). */
    static boolean isPrefixAccent(String which) {
        String n = normalise(which);
        return "cinemarchy".equals(n) || "radiomarchy".equals(n);
    }

    private static String blockFontArt() {
        char[][] g = BlockFont.render(WordmarkView.TEXT);
        StringBuilder sb = new StringBuilder();
        for (char[] row : g) sb.append(new String(row).replace('#', '\u2588')).append('\n');
        return sb.toString();
    }

    private static String raw(Context c, int res) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getResources().openRawResource(res), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    /** Splits art into trimmed lines (blank lines at both ends removed, inner spacing kept). */
    static String[] lines(String text) {
        String[] lines = text.split("\n");
        int first = 0, last = lines.length - 1;
        while (first <= last && lines[first].trim().isEmpty()) first++;
        while (last >= first && lines[last].trim().isEmpty()) last--;
        String[] out = new String[Math.max(0, last - first + 1)];
        System.arraycopy(lines, first, out, 0, out.length);
        return out;
    }
}
