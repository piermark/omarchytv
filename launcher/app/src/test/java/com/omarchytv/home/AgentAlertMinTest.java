package com.omarchytv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** v2.22: the remote picks which agent messages reach the screen (all / warnings and alerts / alerts only). */
public class AgentAlertMinTest {
    private static LauncherConfig cfg(JSONObject j) {
        return new LauncherConfig(null, j, 0L);
    }

    @Test
    public void allByDefaultAndUnknownValuesMeanAll() throws Exception {
        assertEquals("info", cfg(new JSONObject()).agentAlertMin);
        assertEquals("info", cfg(new JSONObject().put("agentAlertMin", "loud")).agentAlertMin);
        assertEquals("warn", cfg(new JSONObject().put("agentAlertMin", "warn")).agentAlertMin);
        assertEquals("alert", cfg(new JSONObject().put("agentAlertMin", "alert")).agentAlertMin);
    }

    @Test
    public void levelsBelowTheMinimumAreDropped() {
        assertFalse(LauncherConfig.belowAgentMin("info", "info"));
        assertTrue(LauncherConfig.belowAgentMin("warn", "info"));
        assertFalse(LauncherConfig.belowAgentMin("warn", "warn"));
        assertFalse(LauncherConfig.belowAgentMin("warn", "alert"));
        assertTrue(LauncherConfig.belowAgentMin("alert", "warn"));
        assertFalse(LauncherConfig.belowAgentMin("alert", "alert"));
    }

    @Test
    public void tonesAndVoiceStayOnUnlessTurnedOff() throws Exception {
        assertTrue(cfg(new JSONObject()).notifySound);
        assertFalse(cfg(new JSONObject().put("link", new JSONObject().put("notifySound", false))).notifySound);
    }
}
