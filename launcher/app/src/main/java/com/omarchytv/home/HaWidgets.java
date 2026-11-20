package com.omarchytv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.omarchytv.home.lights.HaSocket;
import com.omarchytv.home.lights.HomeAssistantClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v2.22 (#160) Home Assistant widgets on the Home grid: "homeassistant.scenes" (buttons that run scenes, scripts or
 * automations, with ✓ / ✗ feedback and an optional confirmation) and "homeassistant.entities" (state + unit, live over
 * the HA websocket while on screen, a 30 s poll if that fails; OK toggles lights, switches, fans, input booleans).
 * The connection to Home Assistant exists only while the widget is attached to the window.
 */
final class HaWidgets {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ha-widgets");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private HaWidgets() {
    }

    static void bind(Activity a, LinearLayout body, JSONObject d, String type, Theme t, WidgetGrid.Texts texts, BoxView box) {
        HomeAssistantClient ha = HomeAssistantClient.fromStore(a);
        if (!ha.configured()) {
            TextView tv = texts.text(a.getString(R.string.ha_not_set), 18, false, t.fgDim);
            tv.setSingleLine(false);
            body.addView(tv);
            return;
        }
        if ("homeassistant.scenes".equals(type)) scenes(a, body, d, t, texts, box, ha);
        else entities(a, body, d, t, texts, box, ha);
    }

    // ---------------------------------------------------------------- scenes

    private static void scenes(Activity a, LinearLayout body, JSONObject d, Theme t, WidgetGrid.Texts texts, BoxView box, HomeAssistantClient ha) {
        int cols = Math.max(1, Math.min(6, d.optInt("columns", 3)));
        LinearLayout grid = new LinearLayout(a);
        grid.setOrientation(LinearLayout.VERTICAL);
        body.addView(grid);
        box.setFocusable(false);
        if ("all".equals(d.opt("items"))) {
            IO.execute(() -> {
                try {
                    JSONArray s = ha.states("scene", "", 48);
                    List<JSONObject> items = new ArrayList<>();
                    for (int i = 0; i < s.length(); i++) {
                        items.add(new JSONObject().put("entity", s.getJSONObject(i).getString("entity_id")).put("label", s.getJSONObject(i).getString("name")));
                    }
                    MAIN.post(() -> fill(a, grid, items, cols, t, texts, box, ha));
                } catch (Exception e) {
                    MAIN.post(() -> grid.addView(texts.text("✗ " + a.getString(R.string.native_unavailable), 18, false, t.red)));
                }
            });
            return;
        }
        JSONArray arr = d.optJSONArray("items");
        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; arr != null && i < arr.length(); i++) if (arr.optJSONObject(i) != null) items.add(arr.optJSONObject(i));
        fill(a, grid, items, cols, t, texts, box, ha);
        // names from HA for the items without a label, in the background
        IO.execute(() -> {
            for (JSONObject it : items) {
                if (it.has("label")) continue;
                try {
                    String n = ha.state(it.optString("entity")).optString("name");
                    MAIN.post(() -> {
                        View v = grid.findViewWithTag("ha:" + it.optString("entity"));
                        if (v instanceof TextView) ((TextView) v).setText(icon(it) + n);
                    });
                } catch (Exception ignored) {
                }
            }
        });
    }

    private static String icon(JSONObject it) {
        String i = it.optString("icon");
        if (!i.isEmpty()) return i + " ";
        String e = it.optString("entity");
        return e.startsWith("script.") ? "▶ " : e.startsWith("automation.") ? "⚙ " : "✦ ";
    }

    private static void fill(Activity a, LinearLayout grid, List<JSONObject> items, int cols, Theme t, WidgetGrid.Texts texts, BoxView box, HomeAssistantClient ha) {
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(a);
                grid.addView(row);
            }
            JSONObject it = items.get(i);
            String entity = it.optString("entity");
            TextView b = texts.text(icon(it) + it.optString("label", entity), 18, false, t.fg);
            b.setTag("ha:" + entity);
            b.setFocusable(true);
            int p = Math.round(8 * box.getResources().getDisplayMetrics().density);
            b.setPadding(p, p / 2, p, p / 2);
            b.setBackground(chipBg(t));
            b.setOnLongClickListener(v -> box.performLongClick());
            b.setOnClickListener(v -> {
                Runnable go = () -> runWithFeedback(a, b, entity, t, ha);
                if (it.optBoolean("confirm")) {
                    new AlertDialog.Builder(a).setMessage(a.getString(R.string.ha_confirm, b.getText().toString().trim()))
                            .setPositiveButton(android.R.string.ok, (dlg, w) -> go.run())
                            .setNegativeButton(android.R.string.cancel, null).show();
                } else go.run();
            });
            row.addView(b, new LinearLayout.LayoutParams(0, -2, 1f));
        }
    }

    private static void runWithFeedback(Activity a, TextView b, String entity, Theme t, HomeAssistantClient ha) {
        CharSequence label = b.getText();
        b.setTextColor(t.accent);
        IO.execute(() -> {
            String err = ha.run(entity);
            MAIN.post(() -> {
                b.setText((err == null ? "✓ " : "✗ " + err + " · ") + label);
                b.setTextColor(err == null ? t.green : t.red);
                MAIN.postDelayed(() -> {
                    b.setText(label);
                    b.setTextColor(t.fg);
                }, 2500);
            });
        });
    }

    static android.graphics.drawable.Drawable chipBg(Theme t) {
        android.graphics.drawable.StateListDrawable d = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable on = new android.graphics.drawable.GradientDrawable();
        on.setColor(t.selection);
        on.setStroke(3, t.accent);
        d.addState(new int[]{android.R.attr.state_focused}, on);
        d.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(0));
        return d;
    }

    // ---------------------------------------------------------------- entities

    private static void entities(Activity a, LinearLayout body, JSONObject d, Theme t, WidgetGrid.Texts texts, BoxView box, HomeAssistantClient ha) {
        JSONArray arr = d.optJSONArray("items");
        JSONArray ids = new JSONArray();
        Map<String, TextView> rows = new HashMap<>();
        Map<String, String> labels = new HashMap<>();
        box.setFocusable(false);
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it == null) continue;
            String entity = it.optString("entity");
            ids.put(entity);
            labels.put(entity, it.optString("label", ""));
            TextView r = texts.text((it.optString("label").isEmpty() ? entity : it.optString("label")) + "  …", 18, false, t.fg);
            r.setTag("ha:" + entity);
            if (HomeAssistantClient.serviceFor(entity) != null && !entity.startsWith("scene.") && !entity.startsWith("script.")) {
                r.setFocusable(true);
                r.setBackground(chipBg(t));
                r.setOnClickListener(v -> runWithFeedback(a, r, entity, t, ha));
                r.setOnLongClickListener(v -> box.performLongClick());
            }
            rows.put(entity, r);
            body.addView(r);
        }
        HaSocket.Listener live = new HaSocket.Listener() {
            @Override public void onState(String entity, JSONObject b) {
                MAIN.post(() -> show(rows.get(entity), labels.get(entity), entity, b));
            }
            @Override public void onClosed(String why) {
                MAIN.post(() -> poll(body, ha, ids, rows, labels));   // fallback: every 30 s while on screen
            }
        };
        HaSocket[] sock = new HaSocket[1];
        body.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                if (sock[0] == null && ids.length() > 0) {
                    sock[0] = new HaSocket(ha, ids, live);
                    sock[0].start();
                }
            }
            @Override public void onViewDetachedFromWindow(View v) {
                if (sock[0] != null) sock[0].close();
                sock[0] = null;
                MAIN.removeCallbacksAndMessages(body);
            }
        });
    }

    private static void show(TextView r, String label, String entity, JSONObject b) {
        if (r == null) return;
        String name = label != null && !label.isEmpty() ? label : b.optString("name", entity);
        r.setText(name + ": " + b.optString("state") + (b.optString("unit").isEmpty() ? "" : " " + b.optString("unit")));
    }

    private static void poll(LinearLayout body, HomeAssistantClient ha, JSONArray ids, Map<String, TextView> rows, Map<String, String> labels) {
        if (!body.isAttachedToWindow()) return;
        IO.execute(() -> {
            for (int i = 0; i < ids.length(); i++) {
                String e = ids.optString(i);
                try {
                    JSONObject b = ha.state(e);
                    MAIN.post(() -> show(rows.get(e), labels.get(e), e, b));
                } catch (Exception ignored) {
                }
            }
        });
        MAIN.postAtTime(() -> poll(body, ha, ids, rows, labels), body, android.os.SystemClock.uptimeMillis() + 30_000);
    }
}
