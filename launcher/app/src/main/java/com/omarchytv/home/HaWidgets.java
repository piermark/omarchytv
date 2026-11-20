package com.omarchytv.home;

import android.app.Activity;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

/** v2.22 (#160) Home Assistant widgets: scenes to run and entities to show. Filled in by HomeAssistantWidgets. */
final class HaWidgets {
    private HaWidgets() {
    }

    static void bind(Activity a, LinearLayout body, JSONObject d, String type, Theme t, WidgetGrid.Texts texts, BoxView box) {
        Object items = d.opt("items");
        if (items instanceof JSONArray) {
            JSONArray arr = (JSONArray) items;
            for (int i = 0; i < arr.length() && i < 6; i++) {
                JSONObject it = arr.optJSONObject(i);
                if (it != null) body.addView(texts.text(it.optString("label", it.optString("entity")), 18, false, t.fg));
            }
        } else {
            body.addView(texts.text("scene.*", 18, false, t.fgDim));
        }
    }
}
