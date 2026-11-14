package com.omarchytv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Layout → App size: 4 / 5 / 6 / 7 per row, 16:9 banners that always fit their column with a gutter. */
public class AppSizeTest {
    @Test
    public void presetsFitTheirColumns() {
        float gridDp = 960 - 2 * 56;   // 1920x1080 at density 2: screen minus the 56dp side margins
        for (int cols = 4; cols <= 7; cols++) {
            float w = AppAdapter.TILE_W_DP * AppAdapter.scaleFor(1f, cols);
            float h = AppAdapter.TILE_H_DP * AppAdapter.scaleFor(1f, cols);
            assertTrue(cols + " per row fits", w <= gridDp / cols - 12);
            assertEquals(16f / 9f, w / h, 0.01f);
            assertEquals(cols + " per row keeps the Large gutter", 14f, gridDp / cols - w, 0.6f);
        }
        assertEquals(1f, AppAdapter.scaleFor(1f, 4), 0f);          // Large = the v2.8 size
        assertEquals(0.92f * AppAdapter.PRESET[3], AppAdapter.scaleFor(0.92f, 7), 1e-6f);   // tile scale stays a fine adjust
        assertEquals(4, LauncherConfig.APP_SIZES.length);
    }

    /** v2.x home row: App size sets how many 16:9 tiles show at once; more per row = smaller tiles, lower row. */
    @Test
    public void homeRowFollowsAppSize() {
        for (int cols = 4; cols <= 7; cols++) assertEquals(cols, HomeV29.rowCols(cols));
        assertEquals(6, HomeV29.rowCols(0));   // no config yet: the v2.9 row
        assertEquals(4, HomeV29.rowCols(2));
        assertEquals(7, HomeV29.rowCols(12));
        int inner = 1920 - 64 - 56 - 4, gap = 20;   // 1920x1080: margins, window padding, border
        int prevW = Integer.MAX_VALUE;
        for (int cols = 4; cols <= 7; cols++) {
            int w = HomeV29.tileW(inner, gap, cols);
            assertTrue(cols + " tiles fit the row", cols * w + (cols - 1) * gap <= inner);
            assertTrue(cols + " tiles fill the row", (cols + 1) * w + cols * gap > inner);
            assertTrue(cols + " per row is smaller", w < prevW);
            prevW = w;
        }
        assertEquals(282, HomeV29.tileW(inner, gap, 6));   // unchanged v2.9 size at the default
    }

    /** Racing takes a fixed-width slot in the remaining space; the row must not overlap the 32+640 term box. */
    @Test
    public void racingSlotFitsBesideTheTermBox() {
        int termRight = HomeV29.HOME_MARGIN + HomeV29.TERM_W;
        assertEquals(672, termRight);
        assertEquals(1120, HomeV29.widgetsWidth(false));
        assertEquals(1200, HomeV29.widgetsWidth(true));
        assertEquals(382, HomeV29.raceColWidth());
        assertEquals(768, HomeV29.widgetsLeft(false));
        assertEquals(688, HomeV29.widgetsLeft(true));
        assertTrue("racing-off row stays right of the term box", HomeV29.widgetsLeft(false) >= termRight);
        assertTrue("racing-on row stays right of the term box", HomeV29.widgetsLeft(true) >= termRight + HomeV29.WIDGET_GAP);
        int gap = HomeV29.WIDGET_GAP;
        int sum = HomeV29.raceColWidth() + HomeV29.weatherColWidth(true) + 2 * HomeV29.sideColWidth(true) + 3 * gap;
        assertEquals("four columns fill the 1200 row", 1200, sum);
        // 18 px pad each side: name + 10 px gap + "sun 22 nov 05:00" at 19 px Caskaydia (cell 11.140625)
        assertTrue("racing fits a full GP name and session time", HomeV29.raceColWidth() - 36 >= 333);
        assertTrue("weather still fits a city and condition line", HomeV29.weatherColWidth(true) - 36 >= 246);
        assertEquals(HomeV29.sideColWidth(true) + 80, HomeV29.weatherColWidth(true));
        // racing off: weather still has the extra 80, pc/ai stay at the original column
        assertEquals((1120 - 32) / 3, HomeV29.sideColWidth(false));
        assertEquals((1120 - 32) / 3 + 80, HomeV29.weatherColWidth(false));
    }
}
