package com.omarchytv.home;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FractalWallpaperDefaultTest {

    @Test
    public void implicitInstallGetsFractalsAndLocksRandom() throws Exception {
        JSONObject j = new JSONObject();
        assertTrue(LauncherConfig.fractalWallpaperDefault(j));
        assertEquals(FractalLayer.ID, j.getString("wallpaper"));
        assertFalse(j.getBoolean("random_wallpaper"));
        assertTrue(j.getBoolean("fractalWp220"));
        assertFalse("idempotent", LauncherConfig.fractalWallpaperDefault(j));
        assertEquals(FractalLayer.ID, j.getString("wallpaper"));
    }

    @Test
    public void oldFactoryOmarchyWithRandomOnMigrates() throws Exception {
        JSONObject j = new JSONObject().put("wallpaper", "omarchy").put("random_wallpaper", true);
        assertTrue(LauncherConfig.fractalWallpaperDefault(j));
        assertEquals(FractalLayer.ID, j.getString("wallpaper"));
        assertFalse(j.getBoolean("random_wallpaper"));
    }

    @Test
    public void explicitOmaFireIsKept() throws Exception {
        JSONObject j = new JSONObject().put("wallpaper", "omafire:embers").put("random_wallpaper", true);
        assertTrue(LauncherConfig.fractalWallpaperDefault(j));
        assertEquals("omafire:embers", j.getString("wallpaper"));
        assertTrue(j.getBoolean("random_wallpaper"));
    }

    @Test
    public void lockedOmarchyIsKept() throws Exception {
        JSONObject j = new JSONObject().put("wallpaper", "omarchy").put("random_wallpaper", false);
        assertTrue(LauncherConfig.fractalWallpaperDefault(j));
        assertEquals("omarchy", j.getString("wallpaper"));
        assertFalse(j.getBoolean("random_wallpaper"));
    }

    @Test
    public void alreadyFractalsIsKeptAndLockedIfRandomWasOn() throws Exception {
        JSONObject j = new JSONObject().put("wallpaper", FractalLayer.ID).put("random_wallpaper", true);
        assertTrue(LauncherConfig.fractalWallpaperDefault(j));
        assertEquals(FractalLayer.ID, j.getString("wallpaper"));
        assertFalse(j.getBoolean("random_wallpaper"));
    }
}
