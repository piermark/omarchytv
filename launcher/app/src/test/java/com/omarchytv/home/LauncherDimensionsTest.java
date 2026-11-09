package com.omarchytv.home;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LauncherDimensionsTest {
    @Test public void legacyTextMapsToReadableRoles() {
        assertEquals(LauncherDimensions.TEXT_BODY_SP, LauncherDimensions.legacyTextSp(19), 0f);
        assertEquals(LauncherDimensions.TEXT_BODY_SP, LauncherDimensions.legacyTextSp(30), 0f);
        assertEquals(LauncherDimensions.TEXT_HEADING_SP, LauncherDimensions.legacyTextSp(34), 0f);
        assertEquals(LauncherDimensions.TEXT_DISPLAY_SP, LauncherDimensions.legacyTextSp(50), 0f);
    }
}
