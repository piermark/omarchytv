package com.omarchytv.home;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.omarchytv.home.link.HomePages;

import java.util.List;

/**
 * v2.22 (#159) the Home page navigator: ⌂ (the classic Home), ‹, a window of 5 page numbers, ›, then
 * "current / total · name". RIGHT on the last number of a window acts like ›, LEFT on the first like ‹; OK on a
 * number opens that page; a long press opens a list of every page. The window logic is {@link HomePages#window}.
 */
final class PageNavigator extends LinearLayout {
    interface Host {
        void show(int page);
        String name(int page);
    }

    static final int SIZE = 5;
    private final Activity act;
    private final float s;
    private final Typeface tf, tfBold;
    private final Host host;
    private Theme t;
    private List<Integer> pages = new java.util.ArrayList<>();
    private int current, windowStart = 1;

    PageNavigator(Activity a, float s, Typeface tf, Typeface tfBold, Host host) {
        super(a);
        this.act = a;
        this.s = s;
        this.tf = tf;
        this.tfBold = tfBold;
        this.host = host;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setClipChildren(false);
    }

    /** pages: the visible widget pages (1-based, ascending); current: 0 for the classic Home. */
    void set(List<Integer> pages, int current, Theme t) {
        this.pages = pages;
        this.current = current;
        this.t = t;
        int pos = Math.max(1, pages.indexOf(current) + 1);
        int[] w = HomePages.window(pos, pages.size(), SIZE);
        windowStart = w[0];
        rebuild(-1);
    }

    private int px(float v) {
        return Math.round(v * s);
    }

    /** Builds the row for the window starting at windowStart; focuses child {@code focusIdx} if ≥ 0. */
    private void rebuild(int focusIdx) {
        removeAllViews();
        if (t == null || pages.isEmpty()) return;
        int total = pages.size();
        int[] w = HomePages.window(windowStart, total, SIZE);
        addView(item("⌂", current == 0, true, () -> host.show(0)));
        boolean arrows = total > SIZE;
        if (arrows) addView(item("‹", false, w[2] == 1, this::prevWindow));
        for (int i = w[0]; i <= w[1]; i++) {
            int page = pages.get(i - 1);
            final boolean last = i == w[1], first = i == w[0];
            TextView n = item(String.valueOf(page), page == current, true, () -> host.show(page));
            n.setOnKeyListener((v, k, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (k == KeyEvent.KEYCODE_DPAD_RIGHT && last && windowStart + SIZE <= total) {
                    nextWindow();
                    return true;
                }
                if (k == KeyEvent.KEYCODE_DPAD_LEFT && first && windowStart > 1) {
                    prevWindow();
                    return true;
                }
                return false;
            });
            addView(n);
        }
        if (arrows) addView(item("›", false, w[3] == 1, this::nextWindow));
        int pos = pages.indexOf(current) + 1;
        TextView label = text((pos > 0 ? pos + " / " + total + " · " + host.name(current) : total + " · ⌂"), false);
        label.setTextColor(t.fgDim);
        label.setPadding(px(16), 0, 0, 0);
        addView(label);
        if (focusIdx >= 0 && focusIdx < getChildCount()) getChildAt(focusIdx).requestFocus();
    }

    private void nextWindow() {
        if (windowStart + SIZE > pages.size()) return;
        windowStart += SIZE;
        rebuild(pages.size() > SIZE ? 2 : 1);   // the first number of the new window (after ⌂ and ‹)
    }

    private void prevWindow() {
        if (windowStart <= 1) return;
        windowStart = Math.max(1, windowStart - SIZE);
        int[] w = HomePages.window(windowStart, pages.size(), SIZE);
        rebuild(2 + (w[1] - w[0]));   // the last number of the new window
    }

    private TextView text(String v, boolean bold) {
        TextView tv = new TextView(act);
        tv.setText(v);
        tv.setTypeface(bold ? tfBold : tf);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22 * s);
        tv.setIncludeFontPadding(false);
        tv.setSingleLine(true);
        return tv;
    }

    private TextView item(String v, boolean on, boolean enabled, Runnable click) {
        TextView tv = text(v, on);
        tv.setGravity(Gravity.CENTER);
        tv.setMinWidth(px(40));
        tv.setPadding(px(10), px(6), px(10), px(6));
        tv.setTextColor(!enabled ? t.muted : on ? t.accent : t.fg);
        tv.setFocusable(enabled);
        if (enabled) {
            tv.setOnClickListener(x -> click.run());
            tv.setOnLongClickListener(x -> {
                allPages(x);
                return true;
            });
        }
        StateListDrawable d = new StateListDrawable();
        GradientDrawable f = new GradientDrawable();
        f.setColor(t.selection);
        f.setStroke(px(2), t.accent);
        d.addState(new int[]{android.R.attr.state_focused}, f);
        d.addState(new int[]{}, new ColorDrawable(0));
        tv.setBackground(d);
        return tv;
    }

    /** Long press: every page by name, for a fast jump through 100+ pages. */
    private void allPages(android.view.View anchor) {
        PopupMenu m = new PopupMenu(act, anchor);
        m.getMenu().add(0, 0, 0, "⌂");
        for (int i = 0; i < pages.size(); i++) m.getMenu().add(0, pages.get(i), i + 1, pages.get(i) + " · " + host.name(pages.get(i)));
        m.setOnMenuItemClickListener(it -> {
            host.show(it.getItemId());
            return true;
        });
        m.show();
    }
}
