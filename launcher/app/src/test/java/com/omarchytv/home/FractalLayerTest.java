package com.omarchytv.home;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The Home fractal wallpaper is opt-in and does not collide with OmaFire's keys. */
public class FractalLayerTest {
    @Test
    public void onlyTheRadiomarchyFractalsKeyTurnsItOn() {
        assertTrue(FractalLayer.is("radiomarchy:fractals"));
        assertFalse(FractalLayer.is("omafire:campfire"));
        assertFalse(FractalLayer.is("omarchy"));
        assertFalse(FractalLayer.is("procedural"));
        assertFalse(FractalLayer.is(""));
        assertFalse(FractalLayer.is(null));
    }
}
