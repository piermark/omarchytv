package com.omarchytv.home;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.json.JSONObject;
import org.junit.Test;

/** v2.14: Erik Johansson's MIT themes ship a palette and TV-sized wallpapers on Android and webOS/Tizen. */
public class CommunityThemesTest {
    private static final String[] ADDED = {"neon-glow", "norrsken", "green-mosaic", "blue-mosaic"};

    @Test
    public void everyAddedThemeHasAPaletteAndTvSizedWallpapers() throws Exception {
        JSONObject themes = new JSONObject(new String(Files.readAllBytes(
                Paths.get("src/main/assets/omarchy_themes.json")), StandardCharsets.UTF_8)).getJSONObject("themes");
        for (String t : ADDED) {
            assertTrue(t, themes.has(t));
            for (String dir : new String[]{"src/main/assets/wallpapers/"}) {
                File[] f = new File(dir + t).listFiles((d, n) -> n.endsWith(".webp"));
                assertTrue(dir + t, f != null && f.length > 0);
                for (File w : f) assertTrue(w + " stays TV-sized", w.length() < 300_000);
            }
        }
    }
}
