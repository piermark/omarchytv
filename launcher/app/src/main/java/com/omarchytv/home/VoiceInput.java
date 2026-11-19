package com.omarchytv.home;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Speech to text for every screen with a mic (search, Ask, the agents, Grok Bot, Grok Bot direct, the Office), the
 * same way on all of them: the in-app SpeechRecognizer first, Google's RECOGNIZE_SPEECH activity only as a fallback.
 * Through that activity katniss hears the words, plays its success beep and returns nothing to onActivityResult,
 * leaving its own search box on screen (a Sony on Android 9, a Sharp AQUOS on Android 10 with katniss 7.55). The
 * SpeechRecognizer service of the same katniss delivers the words to onResults, from the remote's microphone.
 *
 * The screen owns the UI (a "Listening…" line, the "didn't catch that" reply) through the Callback; this class owns the
 * recognizer, the microphone permission and the fallback. Route onRequestPermissionsResult and onActivityResult here,
 * and call {@link #destroy()} from onDestroy.
 */
final class VoiceInput {
    interface Callback {
        /** Listening started ("" at first), then the words heard so far. */
        void onListening(String partial);
        /** The words, best guess first; never empty. */
        void onHeard(List<String> words);
        /** Listening ended without words: a line for the user, or null when there is nothing to say (cancelled). */
        void onStopped(String message);
    }

    static final int REQ_MIC = 46;
    static final int REQ_INTENT = 47;
    static final int MAX_RESULTS = 5;
    static final String NOTHING_HEARD = "I didn't catch that. Try again or type.";
    static final String NO_RECOGNIZER = "No speech recognizer on this TV: type instead.";
    static final String NO_PERMISSION = "The microphone permission is off for OmarchyTV.";

    private final Activity activity;
    private final String prompt;
    private final Callback cb;
    private SpeechRecognizer recognizer;

    VoiceInput(Activity activity, String prompt, Callback cb) {
        this.activity = activity;
        this.prompt = prompt;
        this.cb = cb;
    }

    boolean listening() {
        return recognizer != null;
    }

    /** Start listening: in-app recognizer when the TV has one and the microphone is allowed, else the system dialog. */
    void start() {
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            intent();
            return;
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        listen();
    }

    /** True when the result was ours. */
    boolean onRequestPermissionsResult(int req, int[] res) {
        if (req != REQ_MIC) return false;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) listen();
        else intent();   // no microphone for the app: the system recognizer may still work
        return true;
    }

    /** True when the result was ours (the fallback dialog). */
    boolean onActivityResult(int req, int res, Intent data) {
        if (req != REQ_INTENT) return false;
        List<String> w = Collections.emptyList();
        if (res == Activity.RESULT_OK && data != null) {
            w = words(data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS));
            if (w.isEmpty()) w = words(Collections.singletonList(data.getStringExtra("query")));   // some recognizers only set "query"
        }
        if (!w.isEmpty()) cb.onHeard(w);
        else cb.onStopped(res == Activity.RESULT_OK ? NOTHING_HEARD : null);
        return true;
    }

    void destroy() {
        if (recognizer == null) return;
        try {
            recognizer.destroy();
        } catch (Exception ignored) {
        }
        recognizer = null;
    }

    private void listen() {
        destroy();
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
        } catch (Exception e) {
            intent();
            return;
        }
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onEvent(int type, Bundle p) { }

            @Override public void onPartialResults(Bundle r) {
                List<String> w = words(r);
                if (recognizer != null && !w.isEmpty()) cb.onListening(w.get(0));
            }

            @Override public void onResults(Bundle r) {
                if (recognizer == null) return;   // destroyed meanwhile
                List<String> w = words(r);
                destroy();
                if (!w.isEmpty()) cb.onHeard(w);
                else cb.onStopped(NOTHING_HEARD);
            }

            @Override public void onError(int err) {
                if (recognizer == null) return;
                destroy();
                cb.onStopped(errorMessage(err));
            }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, MainActivity.speechLanguage(activity))
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.getPackageName());
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            destroy();
            intent();
            return;
        }
        cb.onListening("");
    }

    private void intent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, MainActivity.speechLanguage(activity))
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt);
        try {
            activity.startActivityForResult(i, REQ_INTENT);
            cb.onListening("");
        } catch (ActivityNotFoundException e) {
            cb.onStopped(NO_RECOGNIZER);
        }
    }

    private static List<String> words(Bundle r) {
        return r == null ? Collections.<String>emptyList() : words(r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
    }

    /** The recognizer's alternatives, trimmed, without the blank ones; empty when it heard nothing. */
    static List<String> words(List<String> alternatives) {
        List<String> out = new ArrayList<>();
        if (alternatives == null) return out;
        for (String s : alternatives) {
            String t = s == null ? "" : s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** What the user reads when the recognizer gives up. */
    static String errorMessage(int err) {
        switch (err) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return NOTHING_HEARD;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return NO_PERMISSION;
            default:
                return "Voice error " + err + ". Try again or type.";
        }
    }
}
