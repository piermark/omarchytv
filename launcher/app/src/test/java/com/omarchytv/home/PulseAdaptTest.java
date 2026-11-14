package com.omarchytv.home;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PulseAdaptTest {
    @Test
    public void onlyPulseHtml() {
        assertTrue(PulseAdapt.pulseHtml("pulse/index.html"));
        assertTrue(PulseAdapt.pulseHtml("pulse/scene-mars.html"));
        assertFalse(PulseAdapt.pulseHtml("saver/index.html"));
        assertFalse(PulseAdapt.pulseHtml("pulse/pulse-tv.js"));
    }

    @Test
    public void injectsAdapterBeforePulseScripts() throws Exception {
        String src = "<html><head></head><body><script src=\"pulse-tv-data.js\"></script><script src=\"pulse-tv.js\"></script></body></html>";
        String out = new String(PulseAdapt.inject(new ByteArrayInputStream(src.getBytes(StandardCharsets.UTF_8))).readAllBytes(), StandardCharsets.UTF_8);
        int adapt = out.indexOf("omarchytv-pulse-adapt.js");
        int tv = out.indexOf("pulse-tv-data.js");
        assertTrue(adapt >= 0 && adapt < tv);
        // a second pass is a no-op
        String again = new String(PulseAdapt.inject(new ByteArrayInputStream(out.getBytes(StandardCharsets.UTF_8))).readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(1, again.split("omarchytv-pulse-adapt.js", -1).length - 1);
    }

    @Test
    public void scenePagesGetTheAdapterInHead() throws Exception {
        String src = "<!doctype html><html><head><meta charset=\"utf-8\"></head><body><canvas id=\"c\"></canvas></body></html>";
        String out = new String(PulseAdapt.inject(new ByteArrayInputStream(src.getBytes(StandardCharsets.UTF_8))).readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(out.contains("<head><script src=\"../omarchytv-pulse-adapt.js\"></script>"));
    }

    @Test
    public void injectsDeviceHintBeforeTheAdapter() throws Exception {
        String src = "<html><head></head><body><script src=\"pulse-tv-data.js\"></script></body></html>";
        String out = new String(PulseAdapt.inject(new ByteArrayInputStream(src.getBytes(StandardCharsets.UTF_8)), 2f, 3840, 2160).readAllBytes(), StandardCharsets.UTF_8);
        int hint = out.indexOf("__omarchyDpr=2");
        int adapt = out.indexOf("omarchytv-pulse-adapt.js");
        int tv = out.indexOf("pulse-tv-data.js");
        assertTrue(hint >= 0 && hint < adapt && adapt < tv);
        assertTrue(out.contains("__omarchyDevicePixels={w:3840,h:2160}"));
    }
}
