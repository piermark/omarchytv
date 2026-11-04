package com.omarchytv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * v2.14: a WebView whose renderer dies takes the whole process down unless its client overrides
 * onRenderProcessGone and returns true. Every file that builds a WebView must handle it, so the launcher never
 * dies on the Home screen, in the Arcade, in the screensaver or behind a stream because of a low-RAM TV.
 */
public class WebViewCrashGuardTest {

    private static List<File> javaSources() {
        List<File> out = new ArrayList<>();
        collect(new File("src/main/java"), out);
        return out;
    }

    private static void collect(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) collect(f, out);
            else if (f.getName().endsWith(".java")) out.add(f);
        }
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void everyWebViewHandlesItsRendererDeath() throws Exception {
        List<String> owners = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (File f : javaSources()) {
            String src = read(f);
            if (!src.contains("new WebView(")) continue;
            owners.add(f.getName());
            if (!src.contains("onRenderProcessGone")) missing.add(f.getName());
        }
        assertFalse("no WebView found: the scan is looking in the wrong place", owners.isEmpty());
        assertTrue("WebView renderer death not handled in " + missing, missing.isEmpty());
    }

    @Test
    public void aHandledRendererDeathReturnsTrue() throws Exception {
        // Returning false means "the app is not handling it" and Android still kills the process.
        for (File f : javaSources()) {
            String src = read(f);
            int i = src.indexOf("return true;", src.indexOf("onRenderProcessGone"));
            int j = src.indexOf("return false;", src.indexOf("onRenderProcessGone"));
            if (!src.contains("onRenderProcessGone")) continue;
            assertTrue(f.getName() + ": onRenderProcessGone must return true", i > 0);
            if (j > 0) assertTrue(f.getName() + ": returns false before true", i < j);
        }
    }

    @Test
    public void theArcadeReloadsOnceThenLeaves() throws Exception {
        String src = read(new File("src/main/java/com/omarchytv/home/ArcadeActivity.java"));
        assertTrue(src.contains("if (rendererCrashes++ > 0)"));
        assertTrue(src.contains("R.string.arcade_crashed"));
        assertTrue(src.contains("attachWeb()"));
        assertTrue("the reload must be posted, never done inside the callback",
                src.contains("root.post("));
    }

    @Test
    public void theOmaFireLayerGivesUpAfterTwoDeaths() throws Exception {
        String src = read(new File("src/main/java/com/omarchytv/home/OmaFireLayer.java"));
        assertTrue(src.contains("MAX_DEATHS = 2"));
        assertTrue("the layer must not come back once it has used up its deaths",
                src.contains("deaths >= MAX_DEATHS"));
        assertTrue(src.contains("deaths++"));
        // Only onResume builds the WebView: the crash callback must never reload it (that would be a loop).
        int gone = src.indexOf("onRenderProcessGone");
        int drop = src.indexOf("private static void drop");
        assertEquals("drop() must not attach a new WebView", -1, src.indexOf("new WebView(", gone));
        assertTrue(drop > gone);
    }

    @Test
    public void theScreensaverClosesBackToHome() throws Exception {
        String saver = read(new File("src/main/java/com/omarchytv/home/TteSaver.java"));
        assertTrue(saver.contains("Runnable onDead"));
        assertTrue(saver.contains("onDead.run()"));
        String preview = read(new File("src/main/java/com/omarchytv/home/ScreensaverPreviewActivity.java"));
        assertTrue("the preview window closes on a dead renderer", preview.contains("onDead"));
        assertTrue(preview.contains("finish()"));
    }

    @Test
    public void theWebAppClosesWithAToast() throws Exception {
        String src = read(new File("src/main/java/com/omarchytv/home/WebAppActivity.java"));
        int gone = src.indexOf("private void rendererGone");
        assertTrue(gone > 0);
        String body = src.substring(gone);
        assertTrue("the dead page must say so", body.contains("R.string.web_crashed"));
        assertTrue("and close the web app", body.contains("finish();"));
        assertTrue(body.contains("Toast.makeText"));
    }

    @Test
    public void theWebViewsNeverKeepADeadOneAround() throws Exception {
        for (String name : new String[]{"ArcadeActivity.java", "WebAppActivity.java", "TteSaver.java",
                "OmarchyApp.java"}) {
            File f = new File("src/main/java/com/omarchytv/home/" + name);
            if (!f.exists()) continue;
            String src = read(f);
            if (!src.contains("new WebView(")) continue;
            assertTrue(name + ": the dead WebView must be destroyed", src.contains(".destroy()"));
            assertTrue(name + ": the dead WebView must leave its parent",
                    src.contains("removeView"));
        }
    }
}
