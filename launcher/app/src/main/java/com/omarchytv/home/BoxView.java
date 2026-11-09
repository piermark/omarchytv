package com.omarchytv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.widget.LinearLayout;

/**
 * v2.9 home window: square 2 px border (muted; accent when focused), no rounding, no shadow, translucent theme
 * background, and an optional btop-style legend cut into the top border: the border line is broken behind the
 * title (padding both sides) and the title is vertically centred on the line. Children are laid out strictly
 * inside border + padding, so content never touches or crosses the frame.
 */
final class BoxView extends LinearLayout {

    private final Paint line = new Paint();
    private final Paint fill = new Paint();
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String legend;
    private final float s;
    private int border, borderFocus, bg;
    boolean focusBorder;

    BoxView(Context c, float s, Typeface tf) {
        super(c);
        this.s = s;
        setOrientation(VERTICAL);
        setWillNotDraw(false);
        title.setTypeface(tf);
        title.setTextSize(LauncherDimensions.sp(c, LauncherDimensions.TEXT_BOX_TITLE_SP));
        line.setStyle(Paint.Style.FILL);
    }

    void setColors(int bg, int border, int borderFocus, int titleColor) {
        this.bg = bg;
        this.border = border;
        this.borderFocus = borderFocus;
        title.setColor(titleColor);
        invalidate();
    }

    /** Title in the top border; null for none. Padding: 18×22 (spec) inside the border. */
    void setLegend(String t, int padV, int padH) {
        legend = t;
        int top = legendHalf();
        setPadding(padH, top + padV, padH, padV);
        invalidate();
    }

    /** Half the legend height: the border line sits this far below the view top. */
    int legendHalf() {
        if (legend == null) return Math.round(2 * s);
        Paint.FontMetrics fm = title.getFontMetrics();
        return (int) Math.ceil((fm.descent - fm.ascent) / 2f);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float b = Math.max(2f, 2 * s);
        boolean f = focusBorder && hasFocus();
        if (f) b = Math.max(3f, 3 * s);
        float y0 = legend == null ? 0 : legendHalf() - b / 2f;
        fill.setColor(bg);
        c.drawRect(0, y0, w, h, fill);
        line.setColor(f ? borderFocus : border);
        float tx = 14 * s, gap = 8 * s;
        float tw = legend == null ? 0 : Math.min(title.measureText(legend), w - 2 * tx - 2 * gap);
        if (legend == null) {
            c.drawRect(0, y0, w, y0 + b, line);
        } else {
            // The legend's upper half sits above the box over the wallpaper: give it the box background so the
            // title reads as cut into the border instead of overlapping it.
            c.drawRect(tx, 0, tx + gap + tw + gap, y0, fill);
            c.drawRect(0, y0, tx, y0 + b, line);                    // ─ before the gap
            c.drawRect(tx + gap + tw + gap, y0, w, y0 + b, line);   // ─ after the gap
        }
        c.drawRect(0, h - b, w, h, line);
        c.drawRect(0, y0, b, h, line);
        c.drawRect(w - b, y0, w, h, line);
        if (legend != null) {
            Paint.FontMetrics fm = title.getFontMetrics();
            float base = y0 + b / 2f - (fm.ascent + fm.descent) / 2f;   // text centre on the line centre
            String t = legend;
            if (title.measureText(t) > tw) {
                int n = title.breakText(t, true, tw - title.measureText("…"), null);
                t = t.substring(0, Math.max(0, n)) + "…";
            }
            c.drawText(t, tx + gap, base, title);
        }
    }

    @Override
    protected void onFocusChanged(boolean gain, int dir, android.graphics.Rect prev) {
        super.onFocusChanged(gain, dir, prev);
        invalidate();
    }
}
