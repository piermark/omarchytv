package com.omarchytv.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.omarchytv.home.link.Agents;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * "> agents": the full-screen agent monitor (PROTOCOL.md §6 Agents), one terminal-style Hyprland window. Tabs
 * along the top are the agents the PC reports (names and ids come from the PC; nothing is hard-coded); the body
 * scrolls the selected agent's last steps live, its pending question, and what was asked from the TV.
 *
 * The "> " line sends an agent_ask (keyboard, or the remote mic through the system speech recognizer), only
 * for an agent the PC marks canAsk while it is done or idle. The PC decides everything else: it may ask for
 * confirmation on the PC first (awaiting_approval), run the agent, and send the answer back. Plain text only;
 * the TV holds no keys and talks to no AI service. PC off: "not connected", nothing is sent, nothing polls.
 */
public class AgentActivity extends Activity implements OmarchyLink.Listener {

    static final String EXTRA_AGENT = "agent";
    private VoiceInput voice;
    private CharSequence idleHint;   // the input's hint while VoiceInput shows "Listening…" in its place

    private TextView header, log, hint, prompt, mic;
    private EditText input;
    private LinearLayout tabs;
    private ScrollView scroll;

    private Agents data = new Agents();
    private String selected;
    private String tabsSig = "";
    /** What was asked from this TV, per agent id (memory only, gone when the view closes). */
    private final Map<String, SpannableStringBuilder> transcripts = new HashMap<>();
    /** Request id → agent id, until agent_answer / agent_ask_error. */
    private final Map<String, String> pending = new HashMap<>();
    private final SecureRandom random = new SecureRandom();

    private final Runnable tick = this::render;

    // Typewriter for answers ("streamed" onto the screen like a terminal).
    private final StringBuilder queue = new StringBuilder();
    private SpannableStringBuilder typingInto;
    private final Runnable typer = new Runnable() {
        @Override
        public void run() {
            if (typingInto == null) return;
            int n = Math.min(queue.length(), 3);
            typingInto.append(queue, 0, n);
            queue.delete(0, n);
            render();
            if (queue.length() > 0) log.postDelayed(this, 16);
            else typingInto = null;
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherConfig.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_agent);
        Theme.apply(getWindow().getDecorView());
        header = findViewById(R.id.agentHeader);
        log = findViewById(R.id.agentLog);
        hint = findViewById(R.id.agentHint);
        prompt = findViewById(R.id.agentPrompt);
        mic = findViewById(R.id.agentMic);
        input = findViewById(R.id.agentInput);
        tabs = findViewById(R.id.agentTabs);
        scroll = findViewById(R.id.agentScroll);
        float d = getResources().getDisplayMetrics().density;
        prompt.setText("> ");
        mic.setText(getString(R.string.agent_mic));
        mic.setBackground(Theme.cur().control(d));
        mic.setOnClickListener(v -> listen());
        input.setOnEditorActionListener((v, actionId, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                send(input.getText().toString());
                return true;
            }
            return false;
        });
        hint.setText(R.string.agent_hint);
        selected = getIntent().getStringExtra(EXTRA_AGENT);
        load();
        TextFx.type(header, headerText(), 260);
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        OmarchyLink.addListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        OmarchyLink.agentSnapshot();   // visible: fresh activity from the PC (no-op when none is connected)
        render();
    }

    @Override
    protected void onPause() {
        log.removeCallbacks(tick);
        if (voice != null && voice.listening()) stopListening(null);
        super.onPause();
    }

    @Override
    protected void onStop() {
        OmarchyLink.removeListener(this);
        super.onStop();
    }

    private void load() {
        data = Agents.parse(OmarchyLink.agents());
        if (agent() == null) selected = data.list.isEmpty() ? null : data.list.get(0).id;
    }

    private Agents.Agent agent() {
        for (Agents.Agent g : data.list) if (g.id.equals(selected)) return g;
        return null;
    }

    private String headerText() {
        return "> agents  ·  " + getString(OmarchyLink.connected() ? R.string.link_connected_word : R.string.link_offline);
    }

    @Override
    public void onLinkEvent(String type, JSONObject d) {
        switch (type) {
            case "agent_activity":
                load();
                render();
                break;
            case "clients":
                header.setText(headerText());
                render();
                break;
            case "agent_ask_state": {
                String aid = pending.get(d.optString("id"));
                if (aid == null) return;
                boolean approval = "awaiting_approval".equals(d.optString("state"));
                line(aid, getString(R.string.ask_sys), getString(approval ? R.string.agent_confirm_on_pc : R.string.agent_running),
                        approval ? Theme.cur().yellow : Theme.cur().accent);
                break;
            }
            case "agent_answer": {
                String aid = pending.remove(d.optString("id"));
                if (aid == null) return;
                String who = nameOf(aid).toLowerCase(java.util.Locale.ROOT);
                line(aid, who, "", Theme.cur().cyan);
                type(transcripts.get(aid), d.optString("text", ""));
                break;
            }
            case "agent_ask_error": {
                String aid = pending.remove(d.optString("id"));
                if (aid == null) return;
                line(aid, getString(R.string.ask_sys), AgentUi.error(this, d.optString("error", "failed"),
                        d.optString("message", ""), d.optBoolean("local")), Theme.cur().red);
                break;
            }
            default:
                break;
        }
    }

    private String nameOf(String aid) {
        for (Agents.Agent g : data.list) if (g.id.equals(aid)) return g.name;
        return "agent";
    }

    private void type(SpannableStringBuilder into, String text) {
        if (into == null || text.isEmpty()) return;
        if (typingInto != null && typingInto != into) {   // finish the previous answer at once
            typingInto.append(queue);
            queue.setLength(0);
        }
        typingInto = into;
        queue.append(text);
        log.removeCallbacks(typer);
        log.post(typer);
    }

    private void line(String aid, String who, String text, int color) {
        SpannableStringBuilder t = transcripts.get(aid);
        if (t == null) transcripts.put(aid, t = new SpannableStringBuilder());
        if (t.length() > 0) t.append('\n');
        int s = t.length();
        t.append(who).append("> ");
        t.setSpan(new ForegroundColorSpan(color), s, t.length(), 0);
        t.append(text);
        render();
    }

    // ---- rendering ---------------------------------------------------------------------------------

    private void render() {
        log.removeCallbacks(tick);
        boolean connected = OmarchyLink.connected();
        renderTabs();
        Theme th = Theme.cur();
        int accent = LauncherConfig.get(this).accent;
        prompt.setTextColor(accent);
        SpannableStringBuilder b = new SpannableStringBuilder();
        Agents.Agent g = agent();
        long now = System.currentTimeMillis();
        if (!connected) b.append(color(getString(R.string.link_offline), Theme.m(0xFFFF9E64))).append("\n\n");
        if (g == null) {
            b.append(color(getString(data.enabled ? R.string.agent_none : R.string.agent_off), th.fgDim));
        } else {
            b.append(color(g.name, accent));
            if (!g.project.isEmpty()) b.append(color("  ·  " + g.project, th.fgDim));
            if (!g.title.isEmpty()) b.append(color("  —  " + g.title, th.fgDim));
            b.append('\n');
            b.append(color(AgentUi.glyph(g.status) + " " + AgentUi.word(this, g.status), AgentUi.color(g.status)));
            if (!g.step.isEmpty()) b.append("  ").append(g.step);
            b.append(color("  " + Agents.span(data.elapsedNow(g, now)), th.fgDim)).append("\n\n");
            for (Agents.Step s : g.steps) {
                String gl = "error".equals(s.status) ? "✗" : "running".equals(s.status) ? "●" : "✓";
                int c = "error".equals(s.status) ? th.red : "running".equals(s.status) ? th.accent : th.green;
                b.append(color(clock(s.at), th.fgDim)).append(' ').append(color(gl, c)).append(' ').append(s.text).append('\n');
            }
            if (g.needsInput) {
                b.append('\n');
                int q = b.length();
                b.append("? ").append(g.question.isEmpty() ? getString(R.string.agent_needs_input) : g.question);
                b.setSpan(new ForegroundColorSpan(th.yellow), q, b.length(), 0);
                b.setSpan(new BackgroundColorSpan(Theme.withAlpha(th.yellow, 0.16f)), q, b.length(), 0);
                b.append('\n').append(color("  " + getString(R.string.agent_answer_on_pc), th.fgDim)).append('\n');
            }
            SpannableStringBuilder t = transcripts.get(g.id);
            if (t != null && t.length() > 0) b.append('\n').append(t);
        }
        log.setText(b);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));

        // Input line: only for an agent that can take it right now; otherwise say why, in plain words.
        String why = null;
        if (!connected) why = getString(R.string.link_offline);
        else if (g == null) why = getString(R.string.agent_none);
        else if (!g.canAsk) why = getString(R.string.agent_presence_only);
        else if (g.needsInput) why = getString(R.string.agent_answer_on_pc);
        else if (!g.askable()) why = getString(R.string.agent_wait_done);
        else if (pending.containsValue(g.id)) why = getString(R.string.agent_running);
        boolean can = why == null;
        if (!can && (input.hasFocus() || mic.hasFocus()) && tabs.getChildCount() > 0) focusSelectedTab();
        input.setEnabled(can);
        input.setFocusable(can);
        mic.setEnabled(can);
        mic.setFocusable(can);
        mic.setAlpha(can ? 1f : 0.4f);
        input.setHint(can ? getString(R.string.agent_input_hint) : why);
        if (data.anyLive() && connected) log.postDelayed(tick, 1000);
    }

    private void renderTabs() {
        StringBuilder sig = new StringBuilder();
        for (Agents.Agent g : data.list) sig.append(g.id).append('\u0001');
        float d = getResources().getDisplayMetrics().density;
        if (!sig.toString().equals(tabsSig)) {
            boolean hadFocus = tabs.hasFocus();
            tabsSig = sig.toString();
            tabs.removeAllViews();
            for (Agents.Agent g : data.list) {
                TextView t = new TextView(this);
                t.setTypeface(CastUi.font(this));
                t.setTextSize(14);
                t.setPadding((int) (10 * d), (int) (4 * d), (int) (10 * d), (int) (4 * d));
                t.setFocusable(true);
                t.setBackground(Theme.cur().control(d));
                t.setTag(g.id);
                t.setOnFocusChangeListener((v, has) -> {
                    if (has && !v.getTag().equals(selected)) {
                        selected = (String) v.getTag();
                        render();
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMarginEnd((int) (5 * d));   // Omarchy gaps_in 5
                tabs.addView(t, lp);
            }
            if (hadFocus || getCurrentFocus() == null) focusSelectedTab();
        }
        Theme th = Theme.cur();
        int accent = LauncherConfig.get(this).accent;
        for (int i = 0; i < tabs.getChildCount(); i++) {
            TextView t = (TextView) tabs.getChildAt(i);
            Agents.Agent g = data.list.get(i);
            boolean sel = g.id.equals(selected);
            SpannableStringBuilder s = new SpannableStringBuilder();
            s.append(color(AgentUi.glyph(g.status), AgentUi.color(g.status))).append(' ');
            s.append(color(AgentUi.clip(g.name, 16), sel ? accent : th.fg));
            if (g.needsInput) s.append(' ').append(color("!", th.yellow));
            t.setBackground(g.needsInput ? needsInputBg(d) : Theme.cur().control(d));
            t.setText(s);
            t.setSelected(sel);
        }
    }

    /** Needs-input tab: yellow tint under the usual control states. */
    private android.graphics.drawable.Drawable needsInputBg(float d) {
        android.graphics.drawable.LayerDrawable l = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                new android.graphics.drawable.ColorDrawable(Theme.withAlpha(Theme.cur().yellow, 0.16f)), Theme.cur().control(d)});
        return l;
    }

    private void focusSelectedTab() {
        for (int i = 0; i < tabs.getChildCount(); i++) {
            if (tabs.getChildAt(i).getTag().equals(selected)) {
                tabs.getChildAt(i).requestFocus();
                return;
            }
        }
    }

    private static CharSequence color(String s, int c) {
        android.text.SpannableString x = new android.text.SpannableString(s);
        x.setSpan(new ForegroundColorSpan(c), 0, x.length(), 0);
        return x;
    }

    /** A step's time on the TV clock (HH:mm:ss), or blanks when the PC gave none. */
    private String clock(String iso) {
        long ms = Agents.isoMs(iso);
        if (ms < 0) return "        ";
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date(ms));
    }

    // ---- asking ------------------------------------------------------------------------------------

    // voice: VoiceInput (the in-app recognizer, the system dialog only as a fallback); what it has to say goes in the hint
    private void listen() {
        if (!input.isEnabled()) return;
        if (voice == null) voice = new VoiceInput(this, getString(R.string.ask_prompt), new VoiceInput.Callback() {
            @Override public void onListening(String partial) {
                if (idleHint == null) idleHint = input.getHint();
                input.setHint(partial.isEmpty() ? "🎤 Listening…" : "🎤 " + partial);
            }

            @Override public void onHeard(java.util.List<String> words) {
                stopListening(null);
                send(words.get(0));
            }

            @Override public void onStopped(String message) {
                stopListening(message);
            }
        });
        voice.start();
    }

    private void stopListening(String message) {
        if (voice != null) voice.destroy();
        if (idleHint != null) {
            input.setHint(message != null ? message : idleHint);
            idleHint = null;
        }
        if (VoiceInput.NO_RECOGNIZER.equals(message)) input.requestFocus();   // the on-screen keyboard instead
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (voice != null) voice.onRequestPermissionsResult(req, res);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent d) {
        super.onActivityResult(requestCode, resultCode, d);
        if (voice != null) voice.onActivityResult(requestCode, resultCode, d);
    }

    private void send(String raw) {
        String text = raw == null ? "" : raw.trim();
        Agents.Agent g = agent();
        if (text.isEmpty() || g == null || !input.isEnabled()) return;
        if (text.codePointCount(0, text.length()) > Agents.ASK_MAX) text = text.substring(0, text.offsetByCodePoints(0, Agents.ASK_MAX));
        String id = Long.toHexString(random.nextLong() | Long.MIN_VALUE);   // fresh id, 16 hex
        input.setText("");
        pending.put(id, g.id);
        line(g.id, getString(R.string.ask_you), text, Theme.cur().green);
        OmarchyLink.agentAsk(id, g.id, text);   // only {id, agentId, text}; refusals come back as agent_ask_error
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_SEARCH || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) {
            listen();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
}
