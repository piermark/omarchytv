package com.omarchytv.home;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

/** 2.15.1 screensaver.pulsePanels, with screensaver.earthquakes still an alias. */
public class PulsePanelsTest {
    @Test
    public void defaultsAreOn() throws Exception {
        LauncherConfig def = new LauncherConfig(null, new JSONObject(), 0L);
        for (String id : LauncherConfig.PULSE_PANEL_IDS) {
            if ("racing".equals(id)) assertFalse("racing follows the Home widget, off until you ask", def.pulsePanel(id));
            else assertTrue(id, def.pulsePanel(id));
        }
        assertTrue(def.pulseQuakes);
        assertFalse(def.pulseRacing);
    }

    @Test
    public void earthquakesAliasAndPulsePanelsWin() throws Exception {
        LauncherConfig alias = new LauncherConfig(null, new JSONObject()
                .put("screensaver", new JSONObject().put("earthquakes", false)), 0L);
        assertFalse(alias.pulseQuakes);
        assertFalse(alias.pulsePanel("earthquakes"));

        LauncherConfig panels = new LauncherConfig(null, new JSONObject()
                .put("screensaver", new JSONObject()
                        .put("earthquakes", true)
                        .put("pulsePanels", new JSONObject().put("earthquakes", false).put("racing", true).put("planes", false))), 0L);
        assertFalse(panels.pulsePanel("earthquakes"));
        assertFalse(panels.pulseQuakes);
        assertTrue(panels.pulsePanel("racing"));
        assertFalse(panels.pulsePanel("planes"));
        assertTrue(panels.pulsePanel("weather"));
    }

    @Test
    public void putPulsePanelKeepsAliasesInStep() throws Exception {
        JSONObject ss = new JSONObject();
        LauncherConfig.putPulsePanel(ss, "earthquakes", false);
        LauncherConfig.putPulsePanel(ss, "racing", false);
        assertFalse(ss.getBoolean("earthquakes"));
        assertFalse(ss.getBoolean("racing"));
        assertFalse(ss.getJSONObject("pulsePanels").getBoolean("earthquakes"));
        assertFalse(ss.getJSONObject("pulsePanels").getBoolean("racing"));
        LauncherConfig cfg = new LauncherConfig(null, new JSONObject().put("screensaver", ss), 0L);
        assertFalse(cfg.pulsePanel("earthquakes"));
        assertFalse(cfg.pulseRacing);
    }
}
