package com.omarchytv.home;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** v2.21: Last Horizon (Eyes Wide) is the default Home again, only where nobody chose. */
public class EyesWallpaperDefaultTest {

    private static void assertEyes(JSONObject j) {
        assertEquals("omarchy", j.optString("wallpaper"));
        assertEquals(0, j.optInt("wallpaper_bg", -1));
        assertFalse(j.optBoolean("random_wallpaper", true));
        assertTrue(j.optBoolean("wallpaperLoop", false));
    }

    @Test
    public void newInstallGetsTheEyesLoopingAndNoFractals() throws Exception {
        JSONObject j = new JSONObject();
        assertTrue(LauncherConfig.eyesWallpaperDefault(j));
        assertEyes(j);
        assertEquals("last-horizon", j.getJSONObject("theme").getString("name"));
        assertFalse(j.getBoolean("random_theme"));
        assertFalse("the 2.20 migration does not run after", LauncherConfig.fractalWallpaperDefault(j));
        assertEyes(j);
        assertFalse("once", LauncherConfig.eyesWallpaperDefault(j));
    }

    @Test
    public void the220DefaultFractalsBecomeTheEyes() throws Exception {
        JSONObject j = new JSONObject();
        LauncherConfig.fractalWallpaperDefault(j);   // what 2.20 did on its first launch
        assertTrue(LauncherConfig.eyesWallpaperDefault(j));
        assertEyes(j);
    }

    @Test
    public void fractalsPickedByHandStay() throws Exception {
        JSONObject j = new JSONObject().put("fractalWp220", true);
        LauncherConfig.lockWallpaper(j, FractalLayer.ID, 0);
        assertTrue(LauncherConfig.eyesWallpaperDefault(j));
        assertEquals(FractalLayer.ID, j.getString("wallpaper"));
        assertFalse(j.has("wallpaperLoop"));
    }

    @Test
    public void explicitChoicesStay() throws Exception {
        for (String wp : new String[]{"omafire:embers", "wallpapers/tokyo-night/01-liftoff-sunset.webp", "procedural"}) {
            JSONObject j = new JSONObject().put("wallpaper", wp).put("random_wallpaper", false);
            assertTrue(LauncherConfig.eyesWallpaperDefault(j));
            assertEquals(wp, j.getString("wallpaper"));
        }
        JSONObject locked = new JSONObject().put("wallpaper", "omarchy").put("random_wallpaper", false).put("wallpaper_bg", 2);
        assertTrue(LauncherConfig.eyesWallpaperDefault(locked));
        assertEquals(2, locked.getInt("wallpaper_bg"));
    }

    @Test
    public void aThemePickedByHandKeepsItsTheme() throws Exception {
        JSONObject j = new JSONObject().put("random_theme", false)
                .put("theme", new JSONObject().put("name", "gruvbox").put("source", "tv"));
        LauncherConfig.fractalWallpaperDefault(j);
        assertTrue(LauncherConfig.eyesWallpaperDefault(j));
        assertEquals("gruvbox", j.getJSONObject("theme").getString("name"));
        assertEquals("omarchy", j.getString("wallpaper"));   // the theme's own wallpaper, as before 2.20
    }

    @Test
    public void loopGuardStopsOnlyASlowLoop() {
        WallpaperLoopGuard ok = new WallpaperLoopGuard(15), slow = new WallpaperLoopGuard(15);
        boolean okTrip = false, slowTrip = false;
        for (long t = 0; t <= 20_000; t += 67) okTrip |= ok.frame(t);       // ~15 fps
        for (long t = 0; t <= 20_000; t += 250) slowTrip |= slow.frame(t);  // 4 fps
        assertFalse(okTrip);
        assertTrue(slowTrip);
    }

    @Test
    public void weakTvsPlayOnlyALightCopy() {
        assertEquals("full", WallpaperView.introPlan(false, true));
        assertEquals("full", WallpaperView.introPlan(false, false));
        assertEquals("light", WallpaperView.introPlan(true, true));
        assertEquals("skip", WallpaperView.introPlan(true, false));   // Cumulus on the Sony: the still image
    }
}
