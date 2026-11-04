package com.omarchytv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Iterator;

public class ThemeTest {

    @Test
    public void parsesHyprlandColours() {
        assertEquals(0xFF7AA2F7, Theme.parse("#7aa2f7", 0));
        assertEquals(0xFF1E1E1E, Theme.parse("rgb(1e1e1e)", 0));
        assertEquals(0xAA595959, Theme.parse("rgba(595959aa)", 0));
        int[] g = Theme.gradient("rgba(26a269ee) rgba(2ec27eee) 45deg", 0);
        assertEquals(0xEE26A269, g[0]);
        assertEquals(0xEE2EC27E, g[1]);
        int[] one = Theme.gradient("rgba(8a8588ee) 45deg", 0);
        assertEquals(one[0], one[1]);
    }

    @Test
    public void tokyoNightFallbackMapsToItself() {
        Theme t = Theme.cur();
        assertEquals(0xFF7AA2F7, t.map(0xFF7AA2F7));
        assertEquals(0x80A9B1D6, t.map(0x80A9B1D6));   // alpha kept
        assertEquals(0xAA595959, t.inactiveBorder);    // default/hypr/looknfeel.lua
        assertEquals(0xFF123456, t.map(0xFF123456));   // unknown colours untouched
    }

    @Test
    public void bundledPalettesCoverEveryStockThemeAndStayReadable() throws Exception {
        String json = new String(Files.readAllBytes(Paths.get("src/main/assets/omarchy_themes.json")), StandardCharsets.UTF_8);
        JSONObject themes = new JSONObject(json).getJSONObject("themes");
        assertTrue(themes.length() >= 20);
        for (Iterator<String> it = themes.keys(); it.hasNext(); ) {
            String name = it.next();
            JSONObject c = themes.getJSONObject(name);
            for (String k : new String[]{"accent", "background", "foreground", "dark_foreground", "selection", "muted"}) {
                assertNotNull(name + " " + k, c.optString(k, null));
            }
            int bg = Theme.parse(c.getString("background"), 0), fg = Theme.parse(c.getString("foreground"), 0);
            int dim = Theme.readable(Theme.parse(c.getString("dark_foreground"), 0), fg, bg);
            double cr = Surfaces.contrast(Surfaces.luminance(dim & 0xFFFFFF), Surfaces.luminance(bg & 0xFFFFFF));
            assertTrue(name + " dim text " + cr, cr >= 4.5 || dim == fg);
        }
        assertTrue(themes.has("cumulus"));
        assertTrue(themes.has("tokyo-roadster"));
    }

    @Test
    public void cinemarchyWordmarkAndAquariumArtNormaliseCleanly() throws Exception {
        assertEquals("cinemarchy", Art.normalise("cinemarchy"));
        assertEquals("aquarium", Art.normalise("aquarium"));
        assertTrue(Art.isPrefixAccent("cinemarchy"));
        assertEquals(40, Art.split("cinemarchy"));
        String logo = new String(Files.readAllBytes(Paths.get("src/main/res/raw/cinemarchy_logo.txt")), StandardCharsets.UTF_8);
        String[] lines = logo.replace("\r", "").split("\n");
        assertTrue(lines.length >= 10);
        assertTrue(logo.contains("█") && logo.contains("▀") && logo.contains("▄"));
    }

    @Test
    public void v213BetaDefaultsAndRandomRotationLocking() throws Exception {
        try {
            LauncherConfig defCfg = new LauncherConfig(null, null, 0L);
            assertEquals("last-horizon", defCfg.theme.name);
            assertEquals("aquarium", defCfg.screensaverArt);
            assertEquals("aquarium", defCfg.screensaverMode);
            assertTrue(defCfg.saverSound);
            assertTrue(defCfg.randomTheme);
            assertTrue(defCfg.randomWallpaper);

            JSONObject j = new JSONObject();
            assertTrue(LauncherConfig.applyRandomRotation(j, new java.util.Random(42L)));
            String rotTheme = j.getJSONObject("theme").getString("name");
            assertTrue(rotTheme, "last-horizon".equals(rotTheme) || "cumulus".equals(rotTheme) || "campfire".equals(rotTheme));   // v2.14: a fire comes with campfire

            // Lock theme manually -> random_theme becomes false and stays locked across rotations
            LauncherConfig.lockTheme(j, "tokyo-night");
            LauncherConfig lockedThemeCfg = new LauncherConfig(null, j, 1L);
            assertEquals("tokyo-night", lockedThemeCfg.theme.name);
            org.junit.Assert.assertFalse(lockedThemeCfg.randomTheme);
            assertTrue(lockedThemeCfg.randomWallpaper);

            assertTrue(LauncherConfig.applyRandomRotation(j, new java.util.Random(7L)));
            assertEquals("tokyo-night", j.getJSONObject("theme").getString("name"));
            String wp = j.getString("wallpaper");   // v2.14: an intro or an OmaFire wallpaper
            assertTrue(wp, wp.startsWith("wallpapers/") || wp.startsWith("omafire:") || "omarchy".equals(wp));

            // Lock wallpaper manually -> both random_theme and random_wallpaper are false, rotation is a no-op
            LauncherConfig.lockWallpaper(j, "omarchy", 2);
            LauncherConfig lockedBoth = new LauncherConfig(null, j, 2L);
            org.junit.Assert.assertFalse(lockedBoth.randomTheme);
            org.junit.Assert.assertFalse(lockedBoth.randomWallpaper);
            org.junit.Assert.assertFalse(LauncherConfig.applyRandomRotation(j, new java.util.Random(99L)));
            assertEquals("tokyo-night", j.getJSONObject("theme").getString("name"));
            assertEquals("omarchy", j.getString("wallpaper"));
            assertEquals(2, j.getInt("wallpaper_bg"));
        } finally {
            Theme.resolve(null, new JSONObject().put("name", "tokyo-night"));
        }
    }
}
