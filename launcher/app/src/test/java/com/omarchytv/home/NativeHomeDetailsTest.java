package com.omarchytv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** #165 / #164 (Chris OHearn): reserved panes feed the native Home widgets and never show twice. */
public class NativeHomeDetailsTest {
    @Test
    public void reservedPanesStayOutOfTheLowerRow() {
        assertTrue(HomeV29.reservedPane("native-pc-details"));
        assertTrue(HomeV29.reservedPane("native-mac"));
        assertTrue(HomeV29.reservedPane("native-ai"));
        assertFalse(HomeV29.reservedPane("grafana"));
    }

    @Test
    public void percentagesAreReadStrictly() {
        assertEquals(80, HomeV29.nativePercent("80%|2h"), 0.001);
        assertEquals(12.5, HomeV29.nativePercent("12.5"), 0.001);
        assertEquals(-1, HomeV29.nativePercent("n/a"), 0.001);    // missing is not zero usage
        assertEquals(-1, HomeV29.nativePercent("120%"), 0.001);
        assertEquals(-1, HomeV29.nativePercent("-5"), 0.001);
    }

    @Test
    public void grokbotWidgetIsOnUnlessTurnedOff() throws Exception {
        assertTrue(new LauncherConfig(null, new JSONObject(), 0L).wGrokbot);
        assertFalse(new LauncherConfig(null, new JSONObject().put("homeWidgets", new JSONObject().put("grokbot", false)), 0L).wGrokbot);
    }
}
