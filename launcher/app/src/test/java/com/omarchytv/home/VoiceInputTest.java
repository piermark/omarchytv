package com.omarchytv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.speech.SpeechRecognizer;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** VoiceInput: what the screens get from the recognizer's alternatives and errors. */
public class VoiceInputTest {
    @Test
    public void wordsKeepTheAlternativesInOrderWithoutBlanks() {
        assertEquals(Arrays.asList("open YouTube", "open you tube"),
                VoiceInput.words(Arrays.asList("  open YouTube ", "", null, "open you tube")));
        assertTrue(VoiceInput.words(null).isEmpty());
        assertTrue(VoiceInput.words(Collections.singletonList("   ")).isEmpty());
    }

    @Test
    public void errorsReadAsOneLineForTheUser() {
        assertEquals(VoiceInput.NOTHING_HEARD, VoiceInput.errorMessage(SpeechRecognizer.ERROR_NO_MATCH));
        assertEquals(VoiceInput.NOTHING_HEARD, VoiceInput.errorMessage(SpeechRecognizer.ERROR_SPEECH_TIMEOUT));
        assertEquals(VoiceInput.NO_PERMISSION, VoiceInput.errorMessage(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS));
        assertEquals("Voice error 2. Try again or type.", VoiceInput.errorMessage(SpeechRecognizer.ERROR_NETWORK));
    }

    @Test
    public void everyMicScreenUsesVoiceInputAndNoneTheDialogAlone() throws Exception {
        // The dialog (RECOGNIZE_SPEECH started for a result) keeps the words on some TVs: only VoiceInput may start it.
        java.nio.file.Path src = java.nio.file.Paths.get("src/main/java/com/omarchytv/home");
        for (String f : new String[]{"MainActivity", "AskActivity", "AgentActivity", "CompanionChatActivity",
                "GrokbotDirectActivity", "OfficeActivity"}) {
            String code = new String(java.nio.file.Files.readAllBytes(src.resolve(f + ".java")), "UTF-8");
            assertTrue(f + " uses VoiceInput", code.contains("new VoiceInput("));
            assertTrue(f + " starts the dialog itself", !code.contains("RecognizerIntent.ACTION_RECOGNIZE_SPEECH"));
        }
    }
}
