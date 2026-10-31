package com.omarchytv.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Grok Bot diretto (senza PC)": the companion chat when no PC companion is linked and a webhook routine is set
 * in Settings › Grok Bot e AI. Local app commands ("apri Netflix") run first, as in the PC companion chat; the
 * rest goes to the user's own Grok Bot routine (GrokbotDirect), and the reply comes back through ntfy.sh on a
 * random topic. The reply is shown, spoken (TextToSpeech, toggle in Settings) and its action run. A separate
 * screen: @tcballard's Grok Bot Desktop Companion integration (CompanionChatActivity) is not changed.
 */
public class GrokbotDirectActivity extends Activity {
    private static final int REQ_VOICE = 43;
    private static final int HISTORY = 30;
    static final String PREFS = "grokbot_direct";
    private LinearLayout log;
    private ScrollView scroll;
    private EditText input;
    private TextView mic;
    private GrokbotMark mark;
    private Theme t;
    private Typeface mono;
    private TextToSpeech tts;
    private volatile boolean ttsReady;
    private volatile String pendingId;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ---- settings kept only on this TV (own prefs, allowBackup=false, never in the shared config) ----

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        SharedPreferences p = prefs(c);
        return p.getBoolean("enabled", false) && GrokbotDirect.configured(p.getString("url", ""), p.getString("key", ""));
    }

    static boolean speak(Context c) {
        return prefs(c).getBoolean("speak", true);
    }

    static String topic(Context c) {
        SharedPreferences p = prefs(c);
        String tp = p.getString("topic", "");
        if (!GrokbotDirect.validTopic(tp)) {
            tp = GrokbotDirect.newTopic();
            p.edit().putString("topic", tp).apply();
        }
        return tp;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherConfig.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        t = Theme.cur();
        try {
            mono = CastUi.font(this);
        } catch (Exception e) {
            mono = Typeface.MONOSPACE;
        }
        LauncherConfig cfg = LauncherConfig.get(this);
        FrameLayout shade = new FrameLayout(this);
        shade.setBackgroundColor(0xB0000000);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.withAlpha(t.bg, 0.97f));
        bg.setStroke(dp(2), t.activeBorder);
        bg.setCornerRadius(dp(6));
        panel.setBackground(bg);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        mark = new GrokbotMark(this);
        CompanionOverlay.paint(mark, cfg);
        head.addView(mark, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = text("Grok Bot · direct (no PC)", 16, t.fgBright, true);
        title.setPadding(dp(12), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(head);
        TextView via = text("Asks your Grok Bot webhook routine; replies come back through ntfy.sh on a random topic.", 11, t.fgDim, false);
        via.setPadding(0, dp(6), 0, dp(10));
        panel.addView(via);

        scroll = new ScrollView(this);
        scroll.setFocusable(false);
        log = new LinearLayout(this);
        log.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(log);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, 0);
        input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Ask Grok Bot…  (OK / Enter to send)");
        input.setTextColor(t.fgBright);
        input.setHintTextColor(t.fgDim);
        input.setTypeface(mono);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, id, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (id == EditorInfo.IME_ACTION_SEND || id == EditorInfo.IME_ACTION_DONE || enter) {
                send(input.getText().toString());
                return true;
            }
            return false;
        });
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mic = button("🎤 Speak");
        mic.setOnClickListener(v -> voice());
        row.addView(mic);
        TextView sendBtn = button("Send ›");
        sendBtn.setOnClickListener(v -> send(input.getText().toString()));
        row.addView(sendBtn);
        TextView clear = button("Clear");
        clear.setOnClickListener(v -> {
            prefs(this).edit().remove("chat").apply();
            log.removeAllViews();
        });
        row.addView(clear);
        panel.addView(row);

        shade.addView(panel, new FrameLayout.LayoutParams(dp(720), dp(460), Gravity.CENTER));
        setContentView(shade);
        restoreHistory();
        if (speak(this)) {
            tts = new TextToSpeech(this, s -> {
                if (s == TextToSpeech.SUCCESS && tts != null) {
                    tts.setLanguage(Locale.forLanguageTag(MainActivity.speechLanguage(this)));
                    ttsReady = true;
                }
            });
        }
        if (getIntent().getBooleanExtra("xai_test", false)) testXai();
        String q = getIntent().getStringExtra("q");
        if (q != null && !q.trim().isEmpty()) send(q);
        if (getIntent().getBooleanExtra("voice", false)) voice();
        else mic.requestFocus();
    }

    @Override
    protected void onDestroy() {
        pendingId = null;
        stopListening();
        if (player != null) {
            player.release();
            player = null;
        }
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (input != null && !input.hasFocus() && e.getAction() == KeyEvent.ACTION_DOWN && e.isPrintingKey() && e.getUnicodeChar() != 0) {
            input.requestFocus();
            input.append(String.valueOf((char) e.getUnicodeChar()));
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void send(String raw) {
        String q = raw == null ? "" : raw.trim();
        if (q.isEmpty()) return;
        if (q.length() > 240) q = q.substring(0, 240);
        input.setText("");
        line("You", q, true);
        String lq = CompanionCommands.launchQuery(q);
        if (!lq.isEmpty() && launchByName(lq)) return;   // local app commands first, as today
        if (pendingId != null) {
            line("OmarchyTV", "Still waiting for the previous answer…", false);
            return;
        }
        SharedPreferences p = prefs(this);
        String url = p.getString("url", ""), key = p.getString("key", "");
        if (!GrokbotDirect.configured(url, key)) {
            line("OmarchyTV", "Set the webhook URL and key in Settings › Grok Bot e AI › Grok Bot diretto.", false);
            return;
        }
        final String id = GrokbotDirect.newId(new SecureRandom());
        final String topic = topic(this);
        final String lang = MainActivity.speechLanguage(this).substring(0, 2);
        final List<String> apps = GrokbotDirect.names(installedApps(null));
        final String question = q;
        pendingId = id;
        line("Grok Bot", "…", false, false);
        mark.pose("think");
        new Thread(() -> {
            String err = null;
            GrokbotDirect.Reply r = null;
            try {
                long since = System.currentTimeMillis() / 1000 - 5;
                post(url, GrokbotDirect.headers(key), GrokbotDirect.body(question, lang, topic, id, apps, "OmarchyTV on Android TV"));
                long end = System.currentTimeMillis() + GrokbotDirect.TIMEOUT_MS;
                while (r == null && id.equals(pendingId) && System.currentTimeMillis() < end) {
                    Thread.sleep(GrokbotDirect.POLL_MS);
                    try {
                        r = GrokbotDirect.find(get(GrokbotDirect.pollUrl(topic, since)), id);
                    } catch (Exception ignored) {   // one failed poll: keep trying until the deadline
                    }
                }
                if (r == null && id.equals(pendingId)) err = "No answer from Grok Bot within 90 s. Check the routine and its ntfy.sh step (see docs/GROKBOT-DIRECT.md).";
            } catch (HttpCode e) {
                err = e.code == 401 || e.code == 403 ? "The webhook refused the key (HTTP " + e.code + "): check it in Settings." : "The webhook answered HTTP " + e.code + ".";
            } catch (Exception e) {
                err = "Could not reach the webhook: " + e.getClass().getSimpleName() + ".";   // never the key or URL
            }
            final GrokbotDirect.Reply fr = r;
            final String fe = err;
            ui.post(() -> done(id, fr, fe));
        }, "grokbot-direct").start();
    }

    private void done(String id, GrokbotDirect.Reply r, String err) {
        if (!id.equals(pendingId)) return;
        pendingId = null;
        if (isFinishing() || isDestroyed()) {
            if (r != null && !r.text.isEmpty()) remember("Grok Bot", r.text, false);
            return;
        }
        if (log.getChildCount() > 0) log.removeViewAt(log.getChildCount() - 1);
        if (r == null) {
            mark.pose("alert");
            line("OmarchyTV", err == null ? "No answer." : err, false);
            mark.postDelayed(() -> mark.pose("idle"), 2500);
            return;
        }
        mark.pose("happy");
        mark.postDelayed(() -> mark.pose("idle"), 2500);
        if (!r.text.isEmpty()) {
            line("Grok Bot", r.text, false);
            if (speak(this) && xaiOn(this)) speakXai(r.text, r.lang, id);
            else if (speak(this)) speakSystem(r.text, r.lang, id);
        }
        if (r.dropped) line("OmarchyTV", "Grok Bot sent an action I can't run (ignored).", false);
        if (r.action != null) mark.postDelayed(() -> run(r.action), ttsReady ? 600 : 0);
    }

    /** The system TextToSpeech, in the reply's lang when valid and installed, else the launcher language. */
    private void speakSystem(String text, String lang, String id) {
        if (!ttsReady || tts == null) return;
        int ok = tts.setLanguage(Locale.forLanguageTag(lang == null || lang.isEmpty() ? MainActivity.speechLanguage(this) : lang));
        if (ok == TextToSpeech.LANG_MISSING_DATA || ok == TextToSpeech.LANG_NOT_SUPPORTED)
            tts.setLanguage(Locale.forLanguageTag(MainActivity.speechLanguage(this)));
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
    }

    // ---- optional "Voce di Grok (xAI)": off by default; the key stays in these prefs, masked, never logged ----

    static boolean xaiOn(Context c) {
        SharedPreferences p = prefs(c);
        return p.getBoolean("xai_on", false) && GrokbotDirect.validKey(p.getString("xai_key", ""));
    }

    static String xaiVoice(Context c) {
        String v = prefs(c).getString("xai_voice", GrokbotDirect.DEFAULT_VOICE);
        return GrokbotDirect.validVoice(v) ? v : GrokbotDirect.DEFAULT_VOICE;
    }

    private android.media.MediaPlayer player;
    private boolean xaiNoted;

    private void speakXai(String text, String lang, String id) {
        final String key = prefs(this).getString("xai_key", ""), voice = xaiVoice(this);
        new Thread(() -> {
            java.io.File f = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(GrokbotDirect.XAI_TTS).openConnection();
                byte[] mp3;
                try {
                    c.setConnectTimeout(GrokbotDirect.XAI_TIMEOUT_MS);
                    c.setReadTimeout(GrokbotDirect.XAI_TIMEOUT_MS);
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setRequestProperty("Authorization", "Bearer " + key);
                    c.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream o = c.getOutputStream()) {
                        o.write(GrokbotDirect.ttsBody(text, voice, lang).getBytes(StandardCharsets.UTF_8));
                    }
                    if (c.getResponseCode() != 200) throw new HttpCode(c.getResponseCode());
                    try (InputStream in = c.getInputStream()) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buf = new byte[16384];
                        int n;
                        long end = System.currentTimeMillis() + GrokbotDirect.XAI_TIMEOUT_MS;
                        while ((n = in.read(buf)) > 0 && out.size() < 8 * 1024 * 1024) {
                            out.write(buf, 0, n);
                            if (System.currentTimeMillis() > end) throw new java.net.SocketTimeoutException();
                        }
                        mp3 = out.toByteArray();
                    }
                } finally {
                    c.disconnect();
                }
                if (mp3.length < 64) throw new java.io.IOException("empty");
                f = new java.io.File(getCacheDir(), "grok-voice.mp3");
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
                    o.write(mp3);
                }
                final String path = f.getAbsolutePath();
                ui.post(() -> playMp3(path, text, lang, id));
            } catch (Exception e) {
                ui.post(() -> xaiFallback(text, lang, id));
            }
        }, "grok-voice").start();
    }

    private void playMp3(String path, String text, String lang, String id) {
        if (isFinishing() || isDestroyed()) return;
        try {
            if (player != null) player.release();
            player = new android.media.MediaPlayer();
            player.setDataSource(path);
            player.setOnCompletionListener(mp -> {
                mp.release();
                if (player == mp) player = null;
            });
            player.setOnErrorListener((mp, w, x) -> {
                xaiFallback(text, lang, id);
                return true;
            });
            player.prepare();
            player.start();
        } catch (Exception e) {
            xaiFallback(text, lang, id);
        }
    }

    /** Any xAI error or timeout: the system voice, and one short note per chat. */
    private void xaiFallback(String text, String lang, String id) {
        if (isFinishing() || isDestroyed()) return;
        if (!xaiNoted) {
            xaiNoted = true;
            line("OmarchyTV", "Grok voice unavailable, using the TV voice.", false, false);
        }
        speakSystem(text, lang, id);
    }

    /** Settings › test the Grok voice: refresh the voice list (GET /v1/tts/voices), then speak a line. */
    private void testXai() {
        final String key = prefs(this).getString("xai_key", "");
        if (!GrokbotDirect.validKey(key)) {
            line("OmarchyTV", "Enter the xAI API key in Settings first.", false, false);
            return;
        }
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(GrokbotDirect.XAI_VOICES).openConnection();
                c.setConnectTimeout(GrokbotDirect.XAI_TIMEOUT_MS);
                c.setReadTimeout(GrokbotDirect.XAI_TIMEOUT_MS);
                c.setRequestProperty("Authorization", "Bearer " + key);
                if (c.getResponseCode() == 200) {
                    try (InputStream in = c.getInputStream()) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0 && out.size() < 256 * 1024) out.write(buf, 0, n);
                        List<String> v = GrokbotDirect.voices(out.toString("UTF-8"));
                        if (!v.isEmpty()) prefs(this).edit().putString("xai_voices", android.text.TextUtils.join(",", v)).apply();
                    }
                }
                c.disconnect();
            } catch (Exception ignored) {
            }
            ui.post(() -> {
                String say = "Ciao! Sono Grok, con la voce " + xaiVoice(this) + ".";
                line("Grok Bot", say, false, false);
                speakXai(say, "it", "test");
            });
        }, "grok-voices").start();
    }

    private void run(GrokbotDirect.Action a) {
        switch (a.type) {
            case "play_video":
                if (a.youtube()) {
                    String yid = YoutubeLaunch.idOf(a.url);
                    if (DhhFeed.validId(yid)) YoutubeEmbedActivity.openInYoutubeApp(this, yid, a.title);
                    else WebAppActivity.openUrl(this, "YouTube", a.url);
                } else {
                    startActivity(new Intent(this, CastPlayerActivity.class)
                            .putExtra(CastRouter.EXTRA_ID, "grokbot-" + System.currentTimeMillis())
                            .putExtra(CastRouter.EXTRA_KIND, "video")
                            .putExtra(CastRouter.EXTRA_URL, a.url)
                            .putExtra(CastRouter.EXTRA_TITLE, a.title.isEmpty() ? "Grok Bot" : a.title)
                            .putExtra(CastRouter.EXTRA_FROM, "Cinemarchy"));
                }
                break;
            case "open_app":
                if (!launchByName(a.name)) line("OmarchyTV", "No installed app matches \"" + a.name + "\".", false);
                break;
            case "search_youtube":
                WebAppActivity.openUrl(this, "YouTube", GrokbotDirect.youtubeSearchUrl(a.query));
                break;
            default:
        }
    }

    // ---- HTTP (no logging: the key and the URL never reach logcat) ----

    static final class HttpCode extends Exception {
        final int code;

        HttpCode(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    private static void post(String url, Map<String, String> headers, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(20_000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            c.setRequestProperty("User-Agent", "OmarchyTV-grokbot-direct");
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new HttpCode(code);
        } finally {
            c.disconnect();
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8_000);
            c.setReadTimeout(10_000);
            if (c.getResponseCode() != 200) return "";
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 512 * 1024) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    // ---- installed apps (same matching as the PC companion chat: CompanionCommands.matchApp) ----

    private List<String[]> installedApps(List<ResolveInfo> infosOut) {
        PackageManager pm = getPackageManager();
        List<String[]> names = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            List<ResolveInfo> r = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0);
            if (r == null) continue;
            for (ResolveInfo ri : r) {
                if (ri.activityInfo == null || getPackageName().equals(ri.activityInfo.packageName)) continue;
                if (!seen.add(ri.activityInfo.packageName + "/" + ri.activityInfo.name)) continue;
                names.add(new String[]{String.valueOf(ri.loadLabel(pm)), ri.activityInfo.packageName});
                if (infosOut != null) infosOut.add(ri);
            }
        }
        return names;
    }

    private boolean launchByName(String query) {
        if (query == null || query.isEmpty()) return false;
        List<ResolveInfo> infos = new ArrayList<>();
        List<String[]> names = installedApps(infos);
        int k = CompanionCommands.matchApp(query, names);
        if (k < 0) return false;
        ResolveInfo ri = infos.get(k);
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(ri.activityInfo.packageName);
        if (i == null) i = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            line("OmarchyTV", "Opening " + names.get(k)[0] + "…", false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- voice: the in-app SpeechRecognizer. Through RECOGNIZE_SPEECH, Google's katniss on Android 9 (Sony) heard the
    // words but returned nothing to onActivityResult and opened its own search box, so the intent is only a fallback. ----

    private static final int REQ_MIC = 44;
    private android.speech.SpeechRecognizer recognizer;
    private TextView listening;

    private void voice() {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceIntent();
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        listen();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_MIC) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) listen();
        else voiceIntent();   // no mic permission: the system recognizer may still work
    }

    private void listen() {
        stopListening();
        try {
            recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this);
        } catch (Exception e) {
            voiceIntent();
            return;
        }
        listening = text("🎤 Listening…", 13, t.cyan, false);
        listening.setPadding(0, dp(3), 0, dp(3));
        log.addView(listening);
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        mark.pose("alert");
        recognizer.setRecognitionListener(new android.speech.RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() {
                if (listening != null) listening.setText("🎤 …");
            }
            @Override public void onEvent(int type, Bundle p) { }

            @Override public void onPartialResults(Bundle r) {
                String s = first(r);
                if (listening != null && !s.isEmpty()) listening.setText("🎤 Listening… " + s);
            }

            @Override public void onResults(Bundle r) {
                String s = first(r);
                stopListening();
                mark.pose("idle");
                if (!s.isEmpty()) send(s);
                else line("OmarchyTV", "I didn't catch that. Try again or type.", false, false);
            }

            @Override public void onError(int err) {
                stopListening();
                mark.pose("idle");
                line("OmarchyTV", err == android.speech.SpeechRecognizer.ERROR_NO_MATCH || err == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        ? "I didn't catch that. Try again or type."
                        : err == android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ? "The microphone permission is off for OmarchyTV."
                        : "Voice error " + err + ". Try again or type.", false, false);
            }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, MainActivity.speechLanguage(this))
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            stopListening();
            voiceIntent();
        }
    }

    private static String first(Bundle r) {
        if (r == null) return "";
        List<String> l = r.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
        return l != null && !l.isEmpty() && l.get(0) != null ? l.get(0).trim() : "";
    }

    private void stopListening() {
        if (listening != null) {
            log.removeView(listening);
            listening = null;
        }
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (Exception ignored) {
            }
            recognizer = null;
        }
    }

    /** Fallback: the system recognizer activity. */
    private void voiceIntent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, MainActivity.speechLanguage(this))
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Grok Bot");
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (ActivityNotFoundException e) {
            line("OmarchyTV", "No speech recognizer on this TV: type instead.", false);
            input.requestFocus();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_VOICE || res != RESULT_OK || data == null) return;
        List<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        String q = r != null && !r.isEmpty() ? r.get(0) : data.getStringExtra("query");   // some recognizers only set "query"
        if (q != null && !q.trim().isEmpty()) send(q);
    }

    @Override
    protected void onPause() {
        stopListening();
        super.onPause();
    }

    // ---- chat lines and history ----

    private void line(String who, String msg, boolean me) {
        line(who, msg, me, true);
    }

    private void line(String who, String msg, boolean me, boolean keep) {
        TextView v = text(who + ": " + msg, 13, me ? t.cyan : t.fgBright, false);
        v.setPadding(0, dp(3), 0, dp(3));
        log.addView(v);
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        if (keep) remember(who, msg, me);
    }

    private void remember(String who, String msg, boolean me) {
        try {
            SharedPreferences p = prefs(this);
            JSONArray a = new JSONArray(p.getString("chat", "[]"));
            a.put(new JSONObject().put("who", who).put("msg", msg).put("me", me));
            JSONArray keep = new JSONArray();
            for (int i = Math.max(0, a.length() - HISTORY); i < a.length(); i++) keep.put(a.get(i));
            p.edit().putString("chat", keep.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void restoreHistory() {
        try {
            JSONArray a = new JSONArray(prefs(this).getString("chat", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                line(o.optString("who"), o.optString("msg"), o.optBoolean("me"), false);
            }
        } catch (Exception ignored) {
        }
    }

    private TextView button(String s) {
        TextView v = text(s, 13, t.fg, true);
        v.setFocusable(true);
        v.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        v.setLayoutParams(lp);
        style(v, false);
        v.setOnFocusChangeListener((x, f) -> style((TextView) x, f));
        return v;
    }

    private void style(TextView v, boolean f) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(f ? t.selection : Theme.withAlpha(t.bgDarker, 0.8f));
        g.setStroke(dp(f ? 2 : 1), f ? t.activeBorder : t.inactiveBorder);
        v.setBackground(g);
        v.setTextColor(f ? t.fgBright : t.fg);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(mono, bold ? Typeface.BOLD : Typeface.NORMAL);
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
