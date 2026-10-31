package com.omarchytv.home;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The ordered ways to open one YouTube video on a TV, kept free of Android types so it is unit-tested.
 *
 * Sony / Google TV ship "YouTube for Android TV" (com.google.android.youtube.tv). Some builds land on their home or
 * profile screen for one URI form and play the other, so both forms go to that app first, then the phone app,
 * then any app that handles the watch URL. After the apps come the in-app embed player and the WebApp.
 */
final class YoutubeLaunch {
    static final String TV = "com.google.android.youtube.tv";
    static final String PHONE = "com.google.android.youtube";
    /** SmartTube (stable, then beta): common on Sony / Android 9 TVs where the stock app is old. */
    static final String[] SMARTTUBE = {"com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta"};

    /** One attempt: a package (null = any handler) and a URI. */
    static final class Step {
        final String pkg;
        final String uri;

        Step(String pkg, String uri) {
            this.pkg = pkg;
            this.uri = uri;
        }

        @Override
        public String toString() {
            return (pkg == null ? "*" : pkg) + " " + uri;
        }
    }

    private YoutubeLaunch() {
    }

    /** App attempts for a validated 11-character id; empty for anything else. */
    static List<Step> appSteps(String id, long startSec) {
        if (!DhhFeed.validId(id)) return Collections.emptyList();
        String t = startSec > 0 ? "&t=" + startSec + "s" : "";
        String watch = DhhFeed.watchUrl(id) + t;
        String vnd = "vnd.youtube:" + id + (startSec > 0 ? "?t=" + startSec : "");
        List<Step> out = new ArrayList<>();
        out.add(new Step(TV, watch));
        out.add(new Step(TV, vnd));
        out.add(new Step(PHONE, watch));
        out.add(new Step(PHONE, vnd));
        for (String s : SMARTTUBE) out.add(new Step(s, watch));
        out.add(new Step(null, watch));
        return out;
    }

    /**
     * A URL served by YouTube: its pages, or a googlevideo.com stream. Direct googlevideo URLs are signed for one IP
     * and a few hours, so ExoPlayer gets 403 / 410 once they expire: never treat them as a plain stream.
     */
    static boolean youtubeOrigin(String url) {
        if (url == null) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(?:https?://)?([^/?#:]+)").matcher(url.trim().toLowerCase(java.util.Locale.ROOT));
        if (!m.find()) return url.trim().startsWith("vnd.youtube:");
        String h = m.group(1);
        for (String d : new String[]{"googlevideo.com", "youtube.com", "youtu.be", "youtube-nocookie.com"}) {
            if (h.equals(d) || h.endsWith("." + d)) return true;
        }
        return false;
    }

    /** What the player does on an HTTP error: "app" (hand the id to the YouTube app), "expired", or "refused". */
    static String onHttpError(String url, int status) {
        if (youtubeOrigin(url) && status >= 400 && status < 500) return DhhFeed.validId(idOf(url)) ? "app" : "expired";
        return "refused";
    }

    /** The video id when a Cinemarchy entry URL is a YouTube video (watch, youtu.be, shorts, embed, live). */
    static String idOf(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.startsWith("vnd.youtube:")) {
            String id = u.substring(12).replaceFirst("[?&#/].*$", "");
            return DhhFeed.validId(id) ? id : "";
        }
        String id = CompanionCommands.youtubeId(u);
        return DhhFeed.validId(id) ? id : "";
    }
}
