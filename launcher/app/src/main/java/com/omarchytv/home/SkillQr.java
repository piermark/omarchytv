package com.omarchytv.home;

import android.content.Context;

/**
 * v2.22 (#161) where the OmarchyTV agent skill lives, for the QR on the TV: the public skills page of the repository,
 * pinned to the installed version. Only the public URL and the version, never an address, a token or a name.
 */
final class SkillQr {
    static final String BASE = "https://github.com/piermark/omarchytv/blob/main/skills/README.md";

    private SkillQr() {
    }

    static String url(Context c) {
        return url(version(c));
    }

    static String url(String version) {
        String v = version == null ? "" : version.replaceAll("[^0-9A-Za-z.\\-]", "");
        return v.isEmpty() ? BASE : BASE + "?v=" + v;
    }

    /**
     * "This TV": the same page with the TV's LAN address in the fragment (#tv=192.168.1.40:47800). A fragment is not
     * sent to GitHub; the agent reads it from the link it is given. No token: pairing still needs the code on screen.
     */
    static String thisTv(String url, String ip, int port) {
        if (ip == null || !ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}") || port <= 0 || port > 65535) return url;
        return url + "#tv=" + ip + ":" + port;
    }

    static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
