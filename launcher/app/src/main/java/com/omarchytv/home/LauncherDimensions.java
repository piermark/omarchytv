package com.omarchytv.home;

import android.content.Context;
import android.util.TypedValue;

/** Shared launcher dimensions. Use the launcher's wrapped context so UI and text scales apply once. */
final class LauncherDimensions {
    static final float TEXT_BODY_SP = 15f;
    static final float TEXT_SECONDARY_SP = 15f;
    static final float TEXT_ITEM_TITLE_SP = 15f;
    static final float TEXT_BOX_TITLE_SP = 15f;
    static final float TEXT_HEADING_SP = 17f;
    static final float TEXT_DISPLAY_SP = 25f;
    static final float STATUS_ICON_DP = 16f;

    private LauncherDimensions() {}

    static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    static float sp(Context context, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                context.getResources().getDisplayMetrics());
    }

    /** Translate legacy 1080p text sizes to the current semantic scale. */
    static float legacyTextSp(float designPx) {
        if (designPx >= 50f) return TEXT_DISPLAY_SP;
        if (designPx >= 34f) return TEXT_HEADING_SP;
        return TEXT_BODY_SP;
    }
}
