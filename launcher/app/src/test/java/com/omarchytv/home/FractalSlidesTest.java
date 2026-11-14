package com.omarchytv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Home is a slideshow of bundled stills, not a live GL surface. */
public class FractalSlidesTest {
    @Test
    public void catalogIsADozenStillsWithASlowCrossfade() {
        assertTrue(FractalSlides.catalogOk());
        assertEquals(12, FractalSlides.count());
        assertEquals(12_000, FractalSlides.INTERVAL_MS);
        assertEquals(1_600, FractalSlides.FADE_MS);
        assertEquals(1280, FractalSlides.WIDTH);
        assertEquals(720, FractalSlides.HEIGHT);
        assertTrue(FractalSlides.asset(0).startsWith("fractals/"));
        assertTrue(FractalSlides.asset(0).endsWith(".jpg"));
        assertEquals(FractalSlides.asset(0), FractalSlides.asset(12));
        float[] p = FractalSlides.pal(0);
        assertEquals(12, p.length);
        for (float v : p) {
            assertTrue(v >= 0f && v <= 1f);
        }
        int[] acc = FractalSlides.accents(0, 0xFF111111);
        assertEquals(2, acc.length);
        assertTrue(FractalView.contrastRatio(acc[0], 0xFF111111) >= 4.5f);
        assertTrue(FractalView.contrastRatio(acc[1], 0xFF111111) >= 4.5f);
    }

    @Test
    public void homeLayerIsNotAGlSurface() throws Exception {
        java.nio.file.Path srcPath = java.nio.file.Path.of("src/main/java/com/omarchytv/home/FractalLayer.java");
        if (!java.nio.file.Files.isRegularFile(srcPath)) {
            srcPath = java.nio.file.Path.of("app/src/main/java/com/omarchytv/home/FractalLayer.java");
        }
        String src = new String(java.nio.file.Files.readAllBytes(srcPath), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(src.contains("ImageView"));
        assertFalse(src.contains("new FractalView"));
        assertFalse(src.contains("new GLSurfaceView") || src.contains("extends GLSurfaceView"));
        assertTrue(src.contains("ensureWallpaperMusic"));
        assertTrue(src.contains("applyFractalAccent"));
        assertTrue(src.contains("Ken Burns") || src.contains("SCALE_X"));
    }
}
