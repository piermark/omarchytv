package com.omarchytv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/** #86: the text dialogs must confirm from the TV keyboard, not only from a D-pad trip to OK. */
public class DialogInputTest {
    private static String main() throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/java/com/omarchytv/home/MainActivity.java")),
                StandardCharsets.UTF_8);
    }

    @Test
    public void promptAndCityDialogsGoThroughTheHelper() throws Exception {
        String src = main();
        assertEquals("both text dialogs are wired", 2, src.split("DialogInput\\.wire\\(", -1).length - 1);
        assertTrue(src.contains("DialogInput.wire(e, d)"));   // prompt name / host name
        assertTrue(src.contains("DialogInput.wire(q, d)"));   // weather city search
    }

    @Test
    public void theHelperSetsTheDoneActionAndPointsDownAtOk() throws Exception {
        String h = new String(Files.readAllBytes(
                Paths.get("src/main/java/com/omarchytv/home/DialogInput.java")), StandardCharsets.UTF_8);
        assertTrue(h.contains("EditorInfo.IME_ACTION_DONE"));
        assertTrue(h.contains("setNextFocusDownId(android.R.id.button1)"));
        assertTrue(h.contains("dialog.getButton(AlertDialog.BUTTON_POSITIVE)"));
    }
}
