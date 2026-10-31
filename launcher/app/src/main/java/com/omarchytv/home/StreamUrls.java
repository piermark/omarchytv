package com.omarchytv.home;

import java.util.Locale;

/** v2.14: a direct live/VOD stream URL (HLS or plain media; no DASH module) plays in ExoPlayer, not in a WebView web app. */
final class StreamUrls {
    private StreamUrls() {}

    static boolean isDirectStream(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase(Locale.ROOT);
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return false;
        int cut = u.length();
        for (char c : new char[]{'?', '#'}) { int i = u.indexOf(c); if (i >= 0 && i < cut) cut = i; }
        String path = u.substring(0, cut);
        for (String ext : new String[]{".m3u8", ".mp4", ".ts", ".mkv", ".webm", ".mp3", ".aac"}) {
            if (path.endsWith(ext)) return true;
        }
        return false;
    }
}
