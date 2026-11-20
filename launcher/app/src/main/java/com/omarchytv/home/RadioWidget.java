package com.omarchytv.home;

import android.app.Activity;
import android.widget.LinearLayout;

/** v2.22 (#162) the radio widget on the Home grid. */
final class RadioWidget {
    private RadioWidget() {
    }

    static void bind(Activity a, LinearLayout body, Theme t, WidgetGrid.Texts texts, BoxView box) {
        body.addView(texts.text("♪ Radiomarchy", 20, true, t.fgBright));
    }
}
