package com.omarchytv.home;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** 2.20: the SpaceX TV pill is off until the user turns it on; a saved choice is kept. */
public class SpacexDefaultTest {
    @Test
    public void offByDefaultUnlessSaved() throws Exception {
        assertFalse(new LauncherConfig(null, new JSONObject(), 0L).pluginSpacexTv);
        assertFalse(new LauncherConfig(null, new JSONObject().put("plugins", new JSONObject()), 0L).pluginSpacexTv);
        assertFalse(new LauncherConfig(null, new JSONObject().put("plugins", new JSONObject().put("grokbot", true)), 0L).pluginSpacexTv);
        assertFalse(new LauncherConfig(null, new JSONObject().put("plugins", new JSONObject().put("spacexTv", false)), 0L).pluginSpacexTv);
        assertTrue(new LauncherConfig(null, new JSONObject().put("plugins", new JSONObject().put("spacexTv", true)), 0L).pluginSpacexTv);
    }
}
