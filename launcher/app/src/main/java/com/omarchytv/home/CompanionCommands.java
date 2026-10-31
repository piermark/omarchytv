package com.omarchytv.home;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Grok Bot companion's local commands, the same rules as webos/app/companion.js: "open Netflix",
 * "apri YouTube", "starte Spotify" are matched against the installed apps before anything goes to an agent,
 * and http(s) links in a reply are offered as buttons (YouTube ones play in the YouTube app).
 * Plain Java, no Android types.
 */
final class CompanionCommands {
    private CompanionCommands() {
    }

    private static final Pattern LAUNCH = Pattern.compile(
            "^\\s*(?:hey\\s+|ok\\s+|ehi\\s+)?(?:(?:please|per favore|bitte)\\s+)?(?:can you\\s+|puoi\\s+)?"
                    + "(open|launch|start|run|apri|avvia|lancia|fai partire|metti|öffne|offne|starte)\\s+"
                    + "(?:(?:the|la|il|lo|app|application|applicazione|die)\\s+)*(.+?)\\s*(?:app|application|applicazione)?\\s*"
                    + "(?:please|per favore|bitte)?[.!?]*\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"')\\]]+");
    private static final Pattern YT = Pattern.compile(
            "^https?://(?:www\\.|m\\.|music\\.)?(?:youtube\\.com/(?:watch\\?(?:[^#]*&)?v=|shorts/|live/|embed/)|youtu\\.be/)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])");

    /** The app name in a launch request, or "" when the text is not one. */
    static String launchQuery(String text) {
        if (text == null) return "";
        Matcher m = LAUNCH.matcher(text.replaceAll("\\b[lL]['’]\\s*", ""));
        if (!m.matches()) return "";
        String q = m.group(2).trim();
        if (q.length() < 2 || q.length() > 40 || q.split("\\s+").length > 4) return "";
        return q;
    }

    static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replace("+", "plus").replaceAll("[^a-z0-9]", "");
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int sub = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), sub);
            }
            prev = cur;
        }
        return prev[b.length()];
    }

    /**
     * Index of the best app for a spoken name, or -1: exact, then prefix, then contained, then a small typo
     * distance. Each candidate is {label, package}.
     */
    static int matchApp(String query, List<String[]> apps) {
        String q = norm(query);
        if (q.length() < 2 || apps == null) return -1;
        int best = -1;
        double bestScore = 1e9;
        for (int k = 0; k < apps.size(); k++) {
            String[] a = apps.get(k);
            for (int c = 0; c < a.length; c++) {
                String n = norm(c == 1 ? lastSegment(a[c]) : a[c]);
                if (n.isEmpty()) continue;
                double s;
                if (n.equals(q)) s = 0;
                else if (n.startsWith(q) || (q.startsWith(n) && n.length() >= 3)) s = 1 + Math.abs(n.length() - q.length()) / 100.0;
                else if (n.indexOf(q) > 0 && q.length() >= 3) s = 2;
                else {
                    int d = distance(q, n);
                    s = d <= Math.max(1, Math.min(q.length(), n.length()) / 4) ? 3 + d : 1e9;
                }
                if (s < bestScore) {
                    bestScore = s;
                    best = k;
                }
            }
        }
        return best;
    }

    /** "com.netflix.ninja" → "ninja"; package names are only a weak hint. */
    private static String lastSegment(String pkg) {
        if (pkg == null) return "";
        int i = pkg.lastIndexOf('.');
        return i >= 0 ? pkg.substring(i + 1) : pkg;
    }

    /** The YouTube video id in a watch / youtu.be / shorts / live / embed URL, or "". */
    static String youtubeId(String url) {
        if (url == null) return "";
        Matcher m = YT.matcher(url);
        return m.find() ? m.group(1) : "";
    }

    /** Up to six distinct http(s) links in a reply, trailing punctuation dropped. */
    static List<String> links(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return new ArrayList<>(out);
        Matcher m = URL.matcher(text);
        while (m.find() && out.size() < 6) {
            String u = m.group().replaceAll("[.,;:!?]+$", "");
            if (u.length() <= 2000) out.add(u);
        }
        return new ArrayList<>(out);
    }
}
