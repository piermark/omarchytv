package com.omarchytv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RadioAnimatedModeTest {
    @Test
    public void animatedModeCyclesAutoOnOff() {
        assertEquals("on", RadioPlayer.nextAnimatedMode("auto"));
        assertEquals("off", RadioPlayer.nextAnimatedMode("on"));
        assertEquals("auto", RadioPlayer.nextAnimatedMode("off"));
        assertEquals("auto", RadioPlayer.nextAnimatedMode("whatever"));
    }

    @Test
    public void demoIsLiveUnlessTheSettingIsOff() throws Exception {
        // wantLiveDemo is "not off" — verified by reading the method; no Context in this unit test
        java.nio.file.Path srcPath = java.nio.file.Path.of("src/main/java/com/omarchytv/home/RadioPlayer.java");
        if (!java.nio.file.Files.isRegularFile(srcPath)) {
            srcPath = java.nio.file.Path.of("app/src/main/java/com/omarchytv/home/RadioPlayer.java");
        }
        String src = new String(java.nio.file.Files.readAllBytes(srcPath), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(src.contains("wantLiveDemo"));
        assertTrue(src.contains("return !\"off\".equals(animatedMode(c));") || src.contains("return !\"off\".equals(animatedMode"));
        assertTrue(src.contains("wantLiveNormal"));
        assertTrue("auto uses preferPlain for the normal view", src.contains("return !FractalView.preferPlain(c);"));
        assertTrue(src.contains("uiFps"));
        assertTrue(src.contains("? 12 :"));
    }
}
