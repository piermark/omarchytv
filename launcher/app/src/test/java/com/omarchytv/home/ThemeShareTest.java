package com.omarchytv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** v2.21 GET /v1/theme: the payload other apps read, and the colors.toml it carries. */
public class ThemeShareTest {

    @Test
    public void payloadHasTheSimpleKeysTheFullPaletteAndTheWallpaper() throws Exception {
        Theme t = Theme.cur();   // Tokyo Night until a config is loaded
        ThemeShare.Wallpaper wp = new ThemeShare.Wallpaper(new byte[]{(byte) 0xFF, (byte) 0xD8, 1}, "image/jpeg");
        JSONObject o = ThemeShare.payload(t, wp, "tv");
        assertEquals("omarchytv-theme/1", o.getString("schema"));
        assertEquals("dark", o.getString("mode"));
        assertEquals("#1a1b26", o.getString("background"));
        assertEquals("#a9b1d6", o.getString("foreground"));
        assertEquals("#7aa2f7", o.getString("accent"));
        assertEquals("#bb9af7", o.getString("accent2"));
        assertEquals("#1a1b26", o.getJSONObject("colors").getString("background"));
        assertEquals("/v1/theme/wallpaper", o.getJSONObject("wallpaper").getString("url"));
        assertEquals(64, o.getJSONObject("wallpaper").getString("sha256").length());
        assertTrue(ThemeShare.payload(t, null, "tv").isNull("wallpaper"));
    }

    @Test
    public void paletteIsStockThenPushedColoursOnly() throws Exception {
        JSONObject stock = new JSONObject().put("background", "#000000").put("accent", "#111111").put("color4", "#222222");
        JSONObject over = new JSONObject().put("accent", "#ABCDEF").put("_accent2", "#ffffff").put("mode", "light")
                .put("note", "not a colour").put("hyprland_active_border", "rgba(33ccffee)");
        JSONObject m = Theme.merge(stock, over);
        assertEquals("#abcdef", m.getString("accent"));
        assertEquals("#000000", m.getString("background"));
        assertEquals("#222222", m.getString("color4"));
        assertEquals("rgba(33ccffee)", m.getString("hyprland_active_border"));
        assertFalse(m.has("_accent2"));
        assertFalse(m.has("mode"));
        assertFalse(m.has("note"));
        assertEquals("#a9b1d6", m.getString("foreground"));   // what the TV draws when the theme has no such key
    }

    @Test
    public void sourceAndImageSniffing() throws Exception {
        assertEquals("omarchy-pc", ThemeShare.source(new JSONObject().put("source", "omarchy-link")));
        assertEquals("tv", ThemeShare.source(new JSONObject().put("name", "gruvbox")));
        assertEquals("tv", ThemeShare.source(null));
        byte[] webp = "RIFF\0\0\0\0WEBPVP8 ".getBytes("ISO-8859-1");
        assertEquals("image/webp", ThemeShare.sniff(webp, "x"));
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0, 0, 0, 0, 0};
        assertEquals("image/png", ThemeShare.sniff(png, "x"));
        assertEquals("image/jpeg", ThemeShare.sniff(new byte[]{(byte) 0xFF, (byte) 0xD8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, "x"));
        assertEquals("x", ThemeShare.sniff(new byte[3], "x"));
    }
}
