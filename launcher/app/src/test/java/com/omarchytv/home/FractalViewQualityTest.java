package com.omarchytv.home;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FractalViewQualityTest {

    @Test
    public void scaleFloorIs720pAndHighStaysFull() {
        assertEquals(1f, FractalView.scaleFor(1, 1080), 1e-6f);  // high
        assertEquals(1f, FractalView.scaleFor(2, 1080), 1e-6f);  // medium
        assertEquals(720f / 1080f, FractalView.scaleFor(3, 1080), 1e-6f);
        assertEquals(1f, FractalView.scaleFor(3, 720), 1e-6f);
        assertTrue(FractalView.LEVELS[0].scale >= 0.99f);
        assertTrue(FractalView.LEVELS[1].scale >= 0.99f);
        assertTrue(FractalView.LEVELS[2].scale >= 0.99f);
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
