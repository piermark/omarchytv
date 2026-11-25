package com.omarchytv.home;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * v2.22 (#162) the radio widget on the Home grid: what plays (station and ICY title for internet radio) and
 * ⏮ ⏯ ⏭ ☰; follows the one {@link RadioPlayer} while on screen.
 */
final class RadioWidget {
    private RadioWidget() {
    }

    static void bind(Activity a, LinearLayout body, Theme t, WidgetGrid.Texts texts, BoxView box) {
        TextView now = texts.text("♪ Radio", 20, true, t.fgBright);
        TextView sub = texts.text("", 18, false, t.fg);
        body.addView(now);
        body.addView(sub);
        LinearLayout row = new LinearLayout(a);
        body.addView(row);
        box.setFocusable(false);
        String[] keys = {"⏮", "⏯", "⏭", "☰"};
        for (int i = 0; i < keys.length; i++) {
            TextView k = texts.text(keys[i], 24, false, t.fg);
            int pad = Math.round(10 * a.getResources().getDisplayMetrics().density);
            k.setPadding(pad, pad / 3, pad, pad / 3);
            k.setFocusable(true);
            k.setBackground(HaWidgets.chipBg(t));
            final int which = i;
            k.setOnClickListener(v -> {
                RadioPlayer p = RadioPlayer.get(a);
                switch (which) {
                    case 0: p.advance(-1, true); break;
                    case 1:
                        if (p.current() == null) p.playOrStart();
                        else p.toggle();
                        break;
                    case 2: p.advance(1, true); break;
                    default: RadioMini.show(a); break;
                }
            });
            k.setOnLongClickListener(v -> box.performLongClick());
            row.addView(k);
        }
        RadioPlayer.Listener l = () -> body.post(() -> fill(now, sub));
        body.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                RadioPlayer p = RadioPlayer.peek();
                if (p != null) p.addListener(l);
                fill(now, sub);
            }
            @Override public void onViewDetachedFromWindow(View v) {
                RadioPlayer p = RadioPlayer.peek();
                if (p != null) p.removeListener(l);
            }
        });
    }

    private static void fill(TextView now, TextView sub) {
        RadioPlayer p = RadioPlayer.peek();
        RadioCatalog.Track tr = p != null ? p.current() : null;
        if (tr == null) {
            now.setText("♪ Radio");
            sub.setText("");
            return;
        }
        now.setText((p.playing() ? "♫ " : "‖ ") + tr.title);
        sub.setText(p.stationId() != null ? p.icyTitle() : tr.artist);
    }
}
