package com.omarchytv.home;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

/** v2.21: the GL-thread safety trip crashed in Choreographer.getInstance() (no Looper on GLThread). */
public class FractalViewThreadTest {
    @Test
    public void stopPumpHandsTheChoreographerToTheMainThread() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get("src/main/java/com/omarchytv/home/FractalView.java")),
                StandardCharsets.UTF_8);
        int i = src.indexOf("private void stopPump()");
        String body = src.substring(i, src.indexOf("\n    }\n", i));
        int guard = body.indexOf("Looper.myLooper() != android.os.Looper.getMainLooper()");
        int post = body.indexOf("ui.post(");
        int choreo = body.indexOf("Choreographer.getInstance()");
        assertTrue("checks the thread", guard > 0);
        assertTrue("posts to the UI thread first", post > guard && choreo > post);
    }
}
