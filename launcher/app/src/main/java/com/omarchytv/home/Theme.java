package com.omarchytv.home;

import android.content.Context;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The Omarchy theme on the TV. Every colour the launcher draws comes from here, resolved like Omarchy does from
 * {@code themes/<name>/colors.toml}:
 * <ol>
 * <li>colours pushed by the PC ({@code theme.colors}, Omarchy Link PUT /v1/theme),</li>
 * <li>else the bundled palette of that stock theme name ({@code assets/omarchy_themes.json}, all stock themes
 * from github.com/basecamp/omarchy themes/*&#47;colors.toml),</li>
 * <li>else Tokyo Night.</li>
 * </ol>
 * Hyprland borders follow default/themed/hyprland.lua.tpl: active = {@code hyprland_active_border} or accent,
 * inactive = {@code hyprland_inactive_border} or rgba(595959aa).
 *
 * The UI was drawn with Tokyo Night constants; {@link #map(int)} translates each such constant to its
 * colors.toml role in the current theme (alpha kept), so code and XML keep one vocabulary.
 */
final class Theme {

    final String name;
    final boolean light;
    final int bg, bgDark, bgDarker, bgLight, selection, muted;
    final int fg, fgBright, fgLight, fgDark, fgDim;
    final int accent, accent2, red, yellow, orange, green, cyan, blue;
    final int activeBorder, activeBorder2, inactiveBorder;
    /** colors.toml magenta: v2.9 prompt ❯ and music icon. */
    final int magenta;
    /** shell.toml hyprland.active-border-foreground: the theme's active border gradient, else foreground. */
    final int activeBorderFg;

    private static volatile Theme current;
    private static JSONObject bundled;

    /** Theme of the last loaded LauncherConfig (Tokyo Night until one is loaded). */
    static Theme cur() {
        Theme t = current;
        if (t == null) t = current = new Theme("tokyo-night", null, null);
        return t;
    }

    static Theme resolve(Context c, JSONObject themeCfg) {
        String name = themeCfg != null ? themeCfg.optString("name", "last-horizon").trim() : "last-horizon";
        JSONObject stock = palette(c, name);
        if (stock == null) stock = palette(c, "tokyo-night");
        JSONObject pushed = themeCfg != null ? themeCfg.optJSONObject("colors") : null;
        // PC payload accent/accent2 win (they are colors.toml accent and bright_magenta/magenta).
        JSONObject over = new JSONObject();
        try {
            if (pushed != null) {
                for (java.util.Iterator<String> it = pushed.keys(); it.hasNext(); ) {
                    String k = it.next();
                    over.put(k, pushed.get(k));
                }
            }
            if (themeCfg != null && themeCfg.has("accent")) over.put("accent", themeCfg.optString("accent"));
            if (themeCfg != null && themeCfg.has("accent2")) over.put("_accent2", themeCfg.optString("accent2"));
            if (themeCfg != null && themeCfg.has("mode")) over.put("mode", themeCfg.optString("mode"));
        } catch (Exception ignored) {
        }
        Theme t = new Theme(name, stock, over);
        current = t;
        return t;
    }

    /** Stock theme names bundled with the launcher (sorted). */
    static String[] names(Context c) {
        palette(c, "tokyo-night");
        java.util.List<String> out = new java.util.ArrayList<>();
        if (bundled != null) for (java.util.Iterator<String> it = bundled.keys(); it.hasNext(); ) out.add(it.next());
        java.util.Collections.sort(out);
        return out.toArray(new String[0]);
    }

    /** v2.15 Pulse on the TV: a stock theme's colors.toml values (null for a theme pushed by the PC). */
    static JSONObject paletteOf(Context c, String name) {
        return palette(c, name);
    }

    private static synchronized JSONObject palette(Context c, String name) {
        try {
            if (bundled == null) {
                try (InputStream in = c.getAssets().open("omarchy_themes.json")) {
                    byte[] b = new byte[in.available()];
                    int n = 0;
                    while (n < b.length) {
                        int r = in.read(b, n, b.length - n);
                        if (r < 0) break;
                        n += r;
                    }
                    bundled = new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8)).getJSONObject("themes");
                }
            }
            return bundled.optJSONObject(name);
        } catch (Exception e) {
            return null;
        }
    }

    /** Tokyo Night colors.toml (themes/tokyo-night/colors.toml), the fallback for every key. */
    private static final String[][] TN = {
            {"accent", "#7aa2f7"}, {"selection", "#292e42"}, {"muted", "#414868"},
            {"background", "#1a1b26"}, {"dark_background", "#13141c"}, {"darker_background", "#0e0e14"},
            {"lighter_background", "#24283b"}, {"foreground", "#a9b1d6"}, {"dark_foreground", "#565f89"},
            {"light_foreground", "#b4bee6"}, {"bright_foreground", "#c0caf5"}, {"red", "#f7768e"},
            {"yellow", "#e0af68"}, {"orange", "#eb927b"}, {"green", "#9ece6a"}, {"cyan", "#449dab"},
            {"blue", "#7aa2f7"}, {"magenta", "#ad8ee6"}, {"bright_yellow", "#ff9e64"}, {"bright_cyan", "#0db9d7"},
            {"bright_magenta", "#bb9af7"}};

    private Theme(String name, JSONObject stock, JSONObject over) {
        this.name = name;
        light = "light".equals(str(stock, over, "mode", "dark"));
        bg = col(stock, over, "background");
        bgDark = col(stock, over, "dark_background");
        bgDarker = col(stock, over, "darker_background");
        bgLight = col(stock, over, "lighter_background");
        selection = col(stock, over, "selection");
        muted = col(stock, over, "muted");
        fg = col(stock, over, "foreground");
        fgBright = col(stock, over, "bright_foreground");
        fgLight = col(stock, over, "light_foreground");
        fgDark = col(stock, over, "dark_foreground");
        accent = col(stock, over, "accent");
        String a2 = over != null ? over.optString("_accent2", null) : null;
        int acc2 = parse(a2, 0);
        if (acc2 == 0) {
            String bm = str(stock, over, "bright_magenta", null);
            acc2 = bm != null ? parse(bm, 0) : 0;
            if (acc2 == 0) acc2 = col(stock, over, "magenta");
        }
        accent2 = acc2;
        red = col(stock, over, "red");
        yellow = col(stock, over, "yellow");
        String or = str(stock, over, "orange", null);
        orange = or != null ? parse(or, col(stock, over, "bright_yellow")) : col(stock, over, "bright_yellow");
        green = col(stock, over, "green");
        String bc = str(stock, over, "bright_cyan", null);
        cyan = bc != null ? parse(bc, col(stock, over, "cyan")) : col(stock, over, "cyan");
        blue = col(stock, over, "blue");
        magenta = col(stock, over, "magenta");
        int[] act = gradient(str(stock, over, "hyprland_active_border", null), accent);
        activeBorder = act[0];
        activeBorder2 = act[1];
        inactiveBorder = gradient(str(stock, over, "hyprland_inactive_border", null), 0xAA595959)[0];
        String ab = str(stock, over, "hyprland_active_border", null);
        activeBorderFg = ab != null ? activeBorder : fg;
        fgDim = readable(fgDark, fg, bg);
    }

    /** dark_foreground, mixed towards foreground only as far as needed for 4.5:1 on background. */
    static int readable(int dim, int fg, int bg) {
        for (int i = 0; i <= 20; i++) {
            int c = mix(dim, fg, i / 20f);
            if (Surfaces.contrast(Surfaces.luminance(c & 0xFFFFFF), Surfaces.luminance(bg & 0xFFFFFF)) >= 4.6) return c;
        }
        return fg;
    }

    static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static String str(JSONObject stock, JSONObject over, String key, String def) {
        if (over != null && over.has(key)) return over.optString(key);
        if (stock != null && stock.has(key)) return stock.optString(key);
        for (String[] kv : TN) if (kv[0].equals(key)) return kv[1];
        return def;
    }

    private static int col(JSONObject stock, JSONObject over, String key) {
        String tn = null;
        for (String[] kv : TN) if (kv[0].equals(key)) tn = kv[1];
        int fallback = parse(tn, 0xFF000000);
        return parse(str(stock, over, key, tn), fallback);
    }

    /** "#rrggbb", "rgb(rrggbb)", "rgba(rrggbbaa)" (Hyprland), opaque ARGB out unless rgba. */
    static int parse(String s, int def) {
        if (s == null) return def;
        s = s.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            if (s.startsWith("#") && s.length() == 7) return 0xFF000000 | Integer.parseInt(s.substring(1), 16);
            if (s.startsWith("rgb(") && s.endsWith(")") && s.length() == 11) return 0xFF000000 | Integer.parseInt(s.substring(4, 10), 16);
            if (s.startsWith("rgba(") && s.endsWith(")") && s.length() == 14) {
                long v = Long.parseLong(s.substring(5, 13), 16);
                return (int) (((v & 0xFF) << 24) | (v >>> 8));
            }
        } catch (NumberFormatException ignored) {
        }
        return def;
    }

    /** Hyprland gradient "c1 c2 45deg" → {c1, c2 (or c1)}. */
    static int[] gradient(String s, int def) {
        if (s == null || s.trim().isEmpty()) return new int[]{def, def};
        String[] parts = s.trim().split("\\s+");
        int a = parse(parts[0], def);
        int b = parts.length > 1 && !parts[1].endsWith("deg") ? parse(parts[1], a) : a;
        return new int[]{a, b};
    }

    /**
     * A Tokyo Night constant (any alpha) → the same role in this theme, alpha kept. Unknown colours unchanged.
     */
    int map(int argb) {
        int a = argb & 0xFF000000;
        int r;
        switch (argb & 0xFFFFFF) {
            case 0x1A1B26: r = bg; break;
            case 0x16161E: r = bg; break;          // window surfaces: theme background (Omarchy windows/popups)
            case 0x13141C: r = bgDark; break;
            case 0x0E0E14: r = bgDarker; break;
            case 0x24283B: r = bgLight; break;
            case 0x292E42: r = selection; break;
            case 0x414868: r = muted; break;
            case 0x565F89: r = fgDark; break;
            case 0x7F88B3: r = fgDim; break;
            case 0xA9B1D6: r = fg; break;
            case 0xC0CAF5: r = fgBright; break;
            case 0x7AA2F7: r = accent; break;
            case 0xBB9AF7: r = accent2; break;
            case 0x7DCFFF: r = cyan; break;
            case 0x9ECE6A: r = green; break;
            case 0xFF9E64: r = orange; break;
            case 0xF7768E: r = red; break;
            case 0xE0AF68: r = yellow; break;
            default: return argb;
        }
        return a | (r & 0xFFFFFF);
    }

    static int m(int argb) {
        return cur().map(argb);
    }

    static <V extends android.view.View> V applied(V v) {
        apply(v);
        return v;
    }

    /** Recolours a view tree inflated from XML (Tokyo Night constants) to the current theme. */
    static void apply(android.view.View v) {
        Theme t = cur();
        if (v instanceof android.widget.TextView) {
            android.widget.TextView tv = (android.widget.TextView) v;
            android.content.res.ColorStateList csl = tv.getTextColors();
            if (csl != null && !csl.isStateful()) tv.setTextColor(t.map(csl.getDefaultColor()));
            else if (csl != null) tv.setTextColor(t.stateful(csl));
            if (tv.getShadowColor() != 0 && t.light) tv.setShadowLayer(0, 0, 0, 0);   // light themes: no dark halo
        }
        if (v instanceof android.widget.ImageView) {
            android.widget.ImageView iv = (android.widget.ImageView) v;
            if (iv.getImageTintList() != null) iv.setImageTintList(t.stateful(iv.getImageTintList()));
        }
        if (v instanceof android.widget.TextView) {
            android.content.res.ColorStateList dt = ((android.widget.TextView) v).getCompoundDrawableTintList();
            if (dt != null) ((android.widget.TextView) v).setCompoundDrawableTintList(t.stateful(dt));
        }
        Object tag = v.getTag();
        float d = v.getResources().getDisplayMetrics().density;
        if ("control".equals(tag)) v.setBackground(t.control(d));
        else if ("popup".equals(tag)) v.setBackground(t.popup(d, 1f));
        else {
            android.graphics.drawable.Drawable bgd = v.getBackground();
            if (bgd != null) t.recolor(bgd.mutate());
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) apply(g.getChildAt(i));
        }
    }

    /**
     * Omarchy shell [controls] / [menu] selected row (default/themed/shell.toml.tpl): idle transparent; focused =
     * fill foreground at 0.08, 1px border foreground at 0.25 (TV: 1dp). Square (Hyprland rounding 0).
     */
    android.graphics.drawable.Drawable control(float density) {
        android.graphics.drawable.GradientDrawable on = new android.graphics.drawable.GradientDrawable();
        on.setColor(withAlpha(fg, 0.08f));
        on.setStroke(Math.max(1, Math.round(density)), withAlpha(fg, 0.25f));
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{android.R.attr.state_selected}, on.getConstantState().newDrawable().mutate());
        s.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(0));
        return s;
    }

    /**
     * Omarchy shell [popups] / [menu] card: background at {@code alpha} (Omarchy 1.0), 2px border in the Hyprland
     * active border colour (popups) — TV: 2dp.
     */
    android.graphics.drawable.GradientDrawable popup(float density, float alpha) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(withAlpha(bg, alpha));
        g.setStroke(Math.round(2 * density), activeBorder);
        return g;
    }

    /** Omarchy shell [menu] card: background alpha 1.0, border hyprland.active-border-foreground (2dp). */
    android.graphics.drawable.GradientDrawable menuCard(float density) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0xFF000000 | (bg & 0xFFFFFF));
        g.setStroke(Math.round(2 * density), activeBorderFg);
        return g;
    }

    static int withAlpha(int c, float a) {
        return (Math.round(a * 255) << 24) | (c & 0xFFFFFF);
    }

    /** Focused / activated / pressed / default colours remapped (our selectors only use these states). */
    android.content.res.ColorStateList stateful(android.content.res.ColorStateList csl) {
        int[][] states = {{android.R.attr.state_focused}, {android.R.attr.state_activated}, {android.R.attr.state_selected}, {}};
        int[] colors = new int[states.length];
        for (int i = 0; i < states.length; i++) colors[i] = map(csl.getColorForState(states[i], csl.getDefaultColor()));
        return new android.content.res.ColorStateList(states, colors);
    }

    void recolor(android.graphics.drawable.Drawable d) {
        if (d instanceof android.graphics.drawable.ColorDrawable) {
            android.graphics.drawable.ColorDrawable c = (android.graphics.drawable.ColorDrawable) d;
            c.setColor(map(c.getColor()));
        } else if (d instanceof android.graphics.drawable.GradientDrawable) {
            android.graphics.drawable.GradientDrawable g = (android.graphics.drawable.GradientDrawable) d;
            android.content.res.ColorStateList c = g.getColor();
            if (c != null) g.setColor(c.isStateful() ? stateful(c) : android.content.res.ColorStateList.valueOf(map(c.getDefaultColor())));
        } else if (d instanceof android.graphics.drawable.DrawableContainer) {
            android.graphics.drawable.Drawable.ConstantState cs = d.getConstantState();
            if (cs instanceof android.graphics.drawable.DrawableContainer.DrawableContainerState) {
                android.graphics.drawable.Drawable[] ch = ((android.graphics.drawable.DrawableContainer.DrawableContainerState) cs).getChildren();
                for (android.graphics.drawable.Drawable c : ch) if (c != null) recolor(c.mutate());
            }
        } else if (d instanceof android.graphics.drawable.LayerDrawable) {
            android.graphics.drawable.LayerDrawable l = (android.graphics.drawable.LayerDrawable) d;
            for (int i = 0; i < l.getNumberOfLayers(); i++) recolor(l.getDrawable(i).mutate());
        }
    }
}
