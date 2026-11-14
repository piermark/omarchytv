package com.omarchytv.home;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FractalViewQualityTest {

    @Test
    public void bufferNeverExceeds1080pAndMediumIs720p() {
        // 1080p UI: high fills the view, medium/low use the 720p floor
        assertEquals(1080, FractalView.bufferHeight(1, 1080, 1080));
        assertEquals(720, FractalView.bufferHeight(2, 1080, 1080));
        assertEquals(720, FractalView.bufferHeight(3, 1080, 1080));
        assertEquals(1f, FractalView.scaleFor(1, 1080), 1e-6f);
        assertEquals(720f / 1080f, FractalView.scaleFor(2, 1080), 1e-6f);
        assertEquals(720f / 1080f, FractalView.scaleFor(3, 1080), 1e-6f);
        assertEquals(1f, FractalView.scaleFor(3, 720), 1e-6f);

        // 4K view (Sony panel, 1080p UI sometimes reports the native size): cap at 1080p, never 2160
        assertEquals(1080, FractalView.bufferHeight(0, 2160, 1080));
        assertEquals(1080, FractalView.bufferHeight(1, 2160, 1080));
        assertEquals(720, FractalView.bufferHeight(2, 2160, 1080));
        assertEquals(1080f / 2160f, FractalView.scaleFor(1, 2160), 1e-6f);
        int[] high4k = FractalView.bufferSize(1, 3840, 2160, 1080);
        assertArrayEquals(new int[] { 1920, 1080 }, high4k);
        int[] med4k = FractalView.bufferSize(2, 3840, 2160, 1080);
        assertArrayEquals(new int[] { 1280, 720 }, med4k);
        assertTrue(FractalView.LEVELS[0].scale >= 0.99f);
        assertTrue(FractalView.LEVELS[1].scale >= 0.99f);
    }

    @Test
    public void firstLevelStartsMaliT8xxAndLowRamAtMedium() {
        assertEquals(3, FractalView.firstLevel("Mali-450 MP", false, 0));
        assertEquals(3, FractalView.firstLevel("anything", true, 8L * 1024 * 1024 * 1024));
        assertEquals(2, FractalView.firstLevel("Mali-T860", false, 0));
        assertEquals(2, FractalView.firstLevel("Mali-T760", false, 0));
        assertEquals(2, FractalView.firstLevel("Mali-G52", false, 2L * 1024 * 1024 * 1024));
        assertEquals(1, FractalView.firstLevel("Mali-G52", false, 0));
        assertEquals(1, FractalView.firstLevel("Mali-G52", false, 4L * 1024 * 1024 * 1024));
        assertEquals(2, FractalView.firstLevel("Adreno (TM) 630", false, 1900L * 1024 * 1024));
    }

    @Test
    public void presentCostDetectsSwapBackpressureNotTheFpsCap() {
        long cap24 = 1_000_000_000L / 24;           // ~41.7 ms
        assertFalse(FractalView.isBackpressured(cap24, 24));
        assertFalse(FractalView.isBackpressured(cap24 + 5_000_000L, 24));
        assertTrue(FractalView.isBackpressured(cap24 + 20_000_000L, 24));
        assertTrue(FractalView.isBackpressured(80_000_000L, 24));
        assertFalse(FractalView.shouldDisableAtFloor(2, 5));
        assertFalse(FractalView.shouldDisableAtFloor(3, 1));
        assertTrue(FractalView.shouldDisableAtFloor(3, 2));
        assertEquals(24, FractalView.HOME_FPS);
        assertEquals(30, FractalView.SAVER_FPS);
    }

    @Test
    public void contrastPushesADarkAccentOffADarkBackground() {
        int bg = 0xFF111111;
        int dark = 0xFF1A1A1A;
        assertTrue(FractalView.contrastRatio(dark, bg) < 4.5f);
        int fixed = FractalView.ensureContrast(dark, bg);
        assertTrue(FractalView.contrastRatio(fixed, bg) >= 4.5f);
        int already = 0xFF7AA2F7;
        assertEquals(already, FractalView.ensureContrast(already, bg));
    }
}
