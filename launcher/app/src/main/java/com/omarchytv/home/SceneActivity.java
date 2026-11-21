package com.omarchytv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.Toast;

import com.omarchytv.home.link.Studio;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * v2.22 a scene an agent composed (lights, an app, the theme, the radio…): its steps in plain words, and the owner's
 * answer with the remote. Cancel has the focus; Run / Save waits 1.5 s, as for an agent's question. Runs off the main
 * thread (the steps are the TV's own tools); the result goes back to the agent (tv_scene_result).
 */
public class SceneActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());

    static Intent intent(Context c, String id, String from, String name, String steps, boolean save) {
        return new Intent(c, SceneActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("id", id).putExtra("from", from).putExtra("name", name).putExtra("steps", steps).putExtra("save", save);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = getIntent();
        String id = i.getStringExtra("id"), name = i.getStringExtra("name");
        boolean save = i.getBooleanExtra("save", false);
        StringBuilder m = new StringBuilder();
        try {
            JSONArray steps = new JSONArray(i.getStringExtra("steps"));
            for (int k = 0; k < steps.length(); k++) m.append(k + 1).append(". ").append(Studio.describe(steps.getJSONObject(k))).append('\n');
        } catch (Exception e) {
            finish();
            return;
        }
        String title = "🎬 " + i.getStringExtra("from") + ": " + (name == null || name.isEmpty() ? getString(R.string.scene_new) : name);
        AlertDialog d = new AlertDialog.Builder(this).setTitle(title)
                .setMessage(getString(save ? R.string.scene_ask_save : R.string.scene_ask_run) + "\n\n" + m)
                .setNegativeButton(android.R.string.cancel, (x, w) -> answer(id, false))
                .setPositiveButton(save ? R.string.scene_save : R.string.scene_run, (x, w) -> answer(id, true))
                .setOnCancelListener(x -> answer(id, false)).create();
        d.setOnShowListener(x -> {
            Button no = d.getButton(AlertDialog.BUTTON_NEGATIVE), yes = d.getButton(AlertDialog.BUTTON_POSITIVE);
            yes.setEnabled(false);
            no.requestFocus();
            h.postDelayed(() -> yes.setEnabled(true), AgentQuestionActivity.OK_GUARD_MS);
        });
        d.show();
    }

    private void answer(String id, boolean yes) {
        new Thread(() -> {
            JSONObject r = null;
            try {
                r = OmarchyLink.sceneAnswer(id, yes);
            } catch (Exception ignored) {
            }
            JSONObject res = r;
            h.post(() -> {
                if (yes && res != null) Toast.makeText(this, "saved".equals(res.optString("state")) ? "✓" : "▶ ✓", Toast.LENGTH_SHORT).show();
                finish();
            });
        }, "scene").start();
    }
}
