package com.omarchytv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** v2.21 lighter fractals: one small program per scene, loops capped at 320 unless ultra. */
public class FractalProgramsTest {

    @Test
    public void headerDefinesOnlyTheScenesOnScreen() {
        String h = FractalView.header(false, (1 << 0) | (1 << 3), 320);
        assertTrue(h.startsWith("precision highp float;\n"));
        assertTrue(h.contains("#define MAXIT 320\n"));
        assertTrue(h.contains("#define ONLY 1\n#define S0 1\n#define S3 1\n"));
        assertFalse(h.contains("#define S1"));
        String all = FractalView.header(true, 0, 500);
        assertTrue(all.contains("#define LOWP 1\n"));
        assertFalse(all.contains("ONLY"));
    }

    @Test
    public void ultraIsTheOnlyLevelWith500Iterations() {
        assertEquals(500, FractalView.maxIterFor(0));
        for (int l = 1; l < FractalView.LEVELS.length; l++) {
            assertEquals(320, FractalView.maxIterFor(l));
            assertTrue(FractalView.LEVELS[l].iter <= 320);
        }
    }

    @Test
    public void everySceneIsBehindItsOwnDefine() {
        String f = FractalView.FRAG;
        assertTrue(f.contains("i < MAXIT"));
        assertFalse(f.contains("i < 500"));
        for (int s : new int[]{0, 1, 3, 4}) assertTrue(f.contains("defined(S" + s + ")"));
    }
}
