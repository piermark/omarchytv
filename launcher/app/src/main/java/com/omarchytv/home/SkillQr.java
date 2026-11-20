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

    static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
