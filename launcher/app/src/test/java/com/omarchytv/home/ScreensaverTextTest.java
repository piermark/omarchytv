package com.omarchytv.home;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** #84: a screensaver text pushed by the PC wins by default, until a logo is picked on the TV. */
public class ScreensaverTextTest {

    private static JSONObject linked(String text) throws Exception {
        return new JSONObject().put("link", new JSONObject().put("screensaver", text));
    }

    @Test
    public void thePcTextWinsUntilALogoIsPickedOnTheTv() throws Exception {
        JSONObject untouched = linked("OMARCHY");
        assertTrue(new LauncherConfig(null, untouched, 0L).screensaverFromPc);

        JSONObject picked = linked("OMARCHY");
        picked.put("screensaver", new JSONObject().put("art", "omarchy-tv").put("pcText", false));
        assertFalse(new LauncherConfig(null, picked, 1L).screensaverFromPc);

        JSONObject backToPc = linked("OMARCHY");
        backToPc.put("screensaver", new JSONObject().put("art", "omarchy-tv").put("pcText", true));
        assertTrue(new LauncherConfig(null, backToPc, 2L).screensaverFromPc);
        assertTrue(new LauncherConfig(null, null, 3L).screensaverFromPc);   // no config at all: default on
    }
}
