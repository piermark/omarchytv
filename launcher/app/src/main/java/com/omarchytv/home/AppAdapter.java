package com.omarchytv.home;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * App tiles: every installed app with a leanback launcher activity. The first {@link MainActivity#COLUMNS}
 * positions are the dock. Order: the saved dock list (omarchytv.json "dock", default Netflix, Prime Video,
 * YouTube, Disney+ when installed), then the saved "order", then the rest alphabetically; missing apps are
 * skipped, so the dock falls back to the first apps found.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for D-pad Up/Down on a tile. Return true when handled. */
        boolean onTileVerticalKey(int position, int keyCode);

        /** Long-press OK on a tile: start move mode. */
        void onTileLongPress(int position);
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        boolean system;
        /** Web app (omarchytv.json "webapps"): opened in WebAppActivity; pkg is "web:<id>". */
        LauncherConfig.WebApp web;
        /** v2.21 agent page (omarchytv.json "agentPages"): opened in AgentPageActivity; pkg is "page:<id>". */
        LauncherConfig.AgentPage page;
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this(label, component, component.getPackageName(), banner, icon);
        }

        Entry(String label, ComponentName component, String pkg, Drawable banner, Drawable icon) {
            this.label = label;
            this.component = component;
            this.pkg = pkg;
            this.banner = banner;
            this.icon = icon;
        }
    }

    static final float FOCUS_SCALE = 1.04f;

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** Dock order (omarchytv.json "dock", else res/values/config.xml dock_apps). */
    private List<String> favourites;
    /** Saved grid order (omarchytv.json "order"). */
    private List<String> order;
    /** Never shown as tiles (omarchytv.json "hidden", else config.xml hidden_apps). */
    private Set<String> hidden;
    private final List<Entry> items = new ArrayList<>();
    static final java.util.Set<String> SONY_HIDDEN = new HashSet<>(java.util.Arrays.asList(
            "recorded title list", "elenco titoli registrati", "help", "guida", "aiuto", "hilfe"));

    AppAdapter(Context ctx, TileListener listener, int dockSize) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    void reload() {
        LauncherConfig cfg = LauncherConfig.get(ctx);
        favourites = cfg.dock;
        order = cfg.order;
        hidden = new HashSet<>(cfg.hidden);
        boolean showSony = LauncherConfig.readJson(ctx).optBoolean("showSonySystem", true);
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        List<Entry> fresh = new ArrayList<>();
        // With the "> tv" section on, the Live TV app lives there; don't show it twice.
        String liveTv = cfg.tvEnabled ? MainActivity.liveTvPackage(ctx) : "";
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || cfg.isHidden(pkg) || pkg.equals(liveTv)) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Drawable custom = bannerOverride(pkg);
            if (custom != null) banner = custom;
            Drawable icon = ri.loadIcon(pm);
            String label = String.valueOf(ri.loadLabel(pm));
            // v2.10: Sony system tiles hide list exists but is OFF by default (no apps hidden); "showSonySystem": false hides them.
            if (pkg.startsWith("com.sony.") && !showSony && SONY_HIDDEN.contains(label.trim().toLowerCase(java.util.Locale.ROOT))) continue;
            Entry en = new Entry(label, new ComponentName(pkg, ri.activityInfo.name), banner, icon);
            en.system = (ri.activityInfo.applicationInfo.flags
                    & (android.content.pm.ApplicationInfo.FLAG_SYSTEM | android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            fresh.add(en);
        }
        for (LauncherConfig.AgentPage pg : cfg.agentPages) {   // v2.21: pages agents published, before the web apps
            String key = "page:" + pg.id;
            if (cfg.isHidden(key)) continue;
            Entry en = new Entry(pg.label(), new ComponentName(ctx.getPackageName(), AgentPageActivity.class.getName() + "#" + pg.id),
                    key, null, ctx.getDrawable(R.drawable.ic_web));
            en.system = true;
            en.page = pg;
            fresh.add(en);
        }
        for (LauncherConfig.WebApp w : cfg.webapps) {
            String key = "web:" + w.id;
            if (cfg.isHidden(key)) continue;
            Drawable icon = null;
            if (w.icon.startsWith("/")) {
                try {
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inSampleSize = 2;
                    android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(w.icon, o);
                    if (b != null) icon = new android.graphics.drawable.BitmapDrawable(ctx.getResources(), b);
                } catch (Exception ignored) {
                }
            }
            if (icon == null) icon = ctx.getDrawable(R.drawable.ic_web);
            Drawable banner = "grok-bot".equals(w.id) ? grokBotBanner(ctx) : null;
            Entry en = new Entry(w.name, new ComponentName(ctx.getPackageName(),
                    ("grok-bot".equals(w.id) ? AskActivity.class.getName() : WebAppActivity.class.getName()) + "#" + w.id), key, banner, icon);
            en.system = true;   // no uninstall / app info; "Remove web app" instead
            en.web = w;
            fresh.add(en);
        }
        Collections.sort(fresh, (a, b) -> {
            int ra = rank(a.pkg), rb = rank(b.pkg);
            if (ra != rb) return Integer.compare(ra, rb);
            return a.label.compareToIgnoreCase(b.label);
        });
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    /** Tile size, gaps and border from settings (omarchytv.json "geometry"). */
    /** Base tile size in dp (16:9 banner): sized for reading at ~3 m on a 55" screen. */
    static final int TILE_W_DP = 198, TILE_H_DP = 111;

    /** {width, height} in px of an app tile for this config, capped to the column minus a 12dp gutter. */
    static int[] tileSize(LauncherConfig cfg, float density, int cellPx) {
        float scale = scale(cfg);
        if (cellPx > 0) scale = Math.min(scale, (cellPx - 12 * density) / (TILE_W_DP * density));
        return new int[]{Math.round(TILE_W_DP * scale * density), Math.round(TILE_H_DP * scale * density)};
    }

    /** Tile surface: flat and dim, with a hairline border (Settings → Layout → border adds to it). */
    static Drawable tileBackground(LauncherConfig cfg, float density, boolean outlined) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Surfaces.surface(cfg.transparency));
        if (outlined) bg.setStroke(Math.max(1, Math.round(cfg.borderDp * density)), Theme.cur().inactiveBorder);   // Hyprland border_size, col.inactive_border
        return bg;
    }

    /** Unfocused banners lose a little colour and at most 15 % brightness (readability first); focus = full colour. */
    private static final android.graphics.ColorMatrixColorFilter MUTED;

    static {
        android.graphics.ColorMatrix m = new android.graphics.ColorMatrix();
        m.setSaturation(0.75f);
        android.graphics.ColorMatrix dim = new android.graphics.ColorMatrix();
        dim.setScale(0.85f, 0.85f, 0.86f, 1f);
        m.postConcat(dim);
        MUTED = new android.graphics.ColorMatrixColorFilter(m);
    }

    static void setMuted(ImageView v, boolean muted) {
        v.setColorFilter(muted ? MUTED : null);
    }

    /** A tile that cannot run right now (PC command while not connected): greyed, but still opaque. */
    static final android.graphics.ColorMatrixColorFilter UNAVAILABLE;

    static {
        android.graphics.ColorMatrix m = new android.graphics.ColorMatrix();
        m.setSaturation(0f);
        android.graphics.ColorMatrix dim = new android.graphics.ColorMatrix();
        dim.setScale(0.7f, 0.7f, 0.72f, 1f);
        m.postConcat(dim);
        UNAVAILABLE = new android.graphics.ColorMatrixColorFilter(m);
    }

    static void applyGeometry(View item, LauncherConfig cfg, float density, int cellPx) {
        int[] size = tileSize(cfg, density, cellPx);
        int cw = size[0], ch = size[1];
        int gap = Math.round(cfg.gapDp * density);
        item.setPadding(0, gap, 0, gap);
        View card = item.findViewById(R.id.card);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) card.getLayoutParams();
        lp.width = cw;
        lp.height = ch;
        card.setLayoutParams(lp);
        card.setBackground(tileBackground(cfg, density, true));
        // Fallback icon (no banner): shrinks with the tile so small sizes keep the same proportions.
        View icon = card.findViewById(R.id.icon);
        if (icon != null) {
            float f = Math.min(1f, cw / (TILE_W_DP * density));
            ViewGroup.MarginLayoutParams il = (ViewGroup.MarginLayoutParams) icon.getLayoutParams();
            il.width = il.height = Math.round(48 * density * f);
            il.bottomMargin = Math.round(14 * density * f);
            icon.setLayoutParams(il);
        }
        View name = item.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nl = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nl.width = cw;
        nl.topMargin = ch + Math.round(7 * density);
        name.setLayoutParams(nl);
    }

    /** Height of one decorated-less row in dp (padding + card + name line), matching applyGeometry. */
    static float rowHeightDp(LauncherConfig cfg) {
        return cfg.gapDp * 2 + TILE_H_DP * scale(cfg) + 7 + 22;
    }

    /** App size preset (4 / 5 / 6 / 7 per row, see PRESET) times the tile-scale fine adjust. */
    static float scale(LauncherConfig cfg) {
        return scaleFor(cfg.tileScale, cfg.columns);
    }

    static float scaleFor(float tileScale, int columns) {
        int c = Math.max(4, Math.min(7, columns));
        return tileScale * PRESET[c - 4];
    }

    /**
     * Base scale per preset: the same 14dp gutter between banners as Large has (848dp grid / columns − 14dp,
     * over 198dp): 198 / 155 / 127 / 107dp wide, 16:9.
     */
    static final float[] PRESET = {1f, 0.785f, 0.643f, 0.541f};

    private int rank(String pkg) {
        int f = favourites.indexOf(pkg);
        if (f >= 0) return f;
        int o = order.indexOf(pkg);
        if (o >= 0) return 10_000 + o;
        return Integer.MAX_VALUE;
    }

    /** Moves a tile (move mode). Dock membership is positional: the first dockSize tiles are the dock. */
    void move(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= items.size() || to >= items.size()) return;
        Entry e = items.remove(from);
        items.add(to, e);
        notifyItemMoved(from, to);
    }

    /** Package names in screen order (for saving, and for cancelling move mode). */
    List<String> packagesInOrder() {
        List<String> out = new ArrayList<>();
        for (Entry e : items) out.add(e.pkg);
        return out;
    }

    /** Restores an order captured with {@link #packagesInOrder()}. */
    void restoreOrder(List<String> pkgs) {
        Collections.sort(items, (a, b) -> {
            int ia = pkgs.indexOf(a.pkg), ib = pkgs.indexOf(b.pkg);
            return Integer.compare(ia < 0 ? Integer.MAX_VALUE : ia, ib < 0 ? Integer.MAX_VALUE : ib);
        });
        notifyDataSetChanged();
    }

    boolean isSystemAt(int position) {
        return position < 0 || position >= items.size() || items.get(position).system;
    }

    String packageAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).pkg : null;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        applyGeometry(v, LauncherConfig.get(ctx), v.getResources().getDisplayMetrics().density, parent.getWidth() / Math.max(1, dockSize));
        return new Holder(v, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.setTag(e.label);
        h.name.setText(e.label);
        h.name.setVisibility(position < dockSize ? View.INVISIBLE : View.VISIBLE);   // the dock has no labels
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(focused ? 1f : 0f);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            setMuted(h.banner, !focused);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            setMuted(h.icon, !focused);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (listener != null && pos != RecyclerView.NO_POSITION) listener.onTileLongPress(pos);
            return true;
        });
    }

    /** System app-info page (formerly the long-press action; now in move mode via MENU, and in the logo menu). */
    static void openAppInfo(Context ctx, String pkg) {
        if (pkg == null) return;
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    /** Launches the tile at a position (voice search). */
    void launchAt(int position) {
        if (position >= 0 && position < items.size()) launch(items.get(position));
    }

    LauncherConfig.WebApp webAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).web : null;
    }

    private void launch(Entry e) {
        AmbilightController.restoreFollowVideo(ctx);
        if (e.page != null) {
            ctx.startActivity(AgentPageActivity.intent(ctx, e.page.url));
            return;
        }
        if (e.web != null) {
            if ("grok-bot".equals(e.web.id)) {
                AskActivity.open(ctx);
                return;
            }
            WebAppActivity.open(ctx, e.web);
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).component.flattenToShortString().hashCode();
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).component.flattenToShortString() : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) return i;
        }
        return -1;
    }

    /**
     * Optional per-app banner: banners/<package>.png in the app's external files dir (pushed by adb or the PC).
     * Absent (the default) or unreadable → null, and the app's own banner is used.
     */
    private Drawable bannerOverride(String pkg) {
        java.io.File dir = ctx.getExternalFilesDir(null);
        if (dir == null || pkg.indexOf('/') >= 0 || pkg.startsWith(".")) return null;
        java.io.File f = new java.io.File(new java.io.File(dir, "banners"), pkg + ".png");
        if (!f.isFile() || f.length() > 4L * 1024 * 1024) return null;
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= 640) o.inSampleSize *= 2;   // keep tiles light (≈640 px wide)
        android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
        return b != null ? new android.graphics.drawable.BitmapDrawable(ctx.getResources(), b) : null;
    }

    Drawable bannerAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).banner : null;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    /** The real label of a tile name view (its text may be mid-scramble). */
    static CharSequence labelOf(TextView name) {
        Object t = name.getTag();
        return t instanceof CharSequence ? (CharSequence) t : name.getText();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            final float radius = 0f;   // Hyprland rounding = 0 in Omarchy
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                // Short linear lift, no overshoot; the name below decrypts in like terminal text.
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s).setDuration(90).setInterpolator(null).start();
                setMuted(banner, !focused);
                setMuted(icon, !focused);
                if (focused) {
                    name.setAlpha(1f);
                    TextFx.decrypt(name, labelOf(name), 280);
                } else {
                    TextFx.finish(name, labelOf(name));
                    name.setAlpha(0f);
                }
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }

    /** Live Omarchy TUI 16:9 banner for the Grok Bot tile on the Home shelf, rendered dynamically in Theme.cur() colors. */
    static Drawable grokBotBanner(Context ctx) {
        final android.graphics.Typeface tf = CastUi.font(ctx);
        final android.graphics.Typeface tfBold = android.graphics.Typeface.create(tf, android.graphics.Typeface.BOLD);
        return new Drawable() {
            private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

            @Override
            public void draw(@NonNull android.graphics.Canvas c) {
                android.graphics.Rect b = getBounds();
                int w = b.width(), h = b.height();
                if (w <= 0 || h <= 0) return;
                Theme t = Theme.cur();
                p.setStyle(android.graphics.Paint.Style.FILL);
                p.setColor(t.bg);
                c.drawRect(b, p);

                // Subtle inner terminal frame
                int pad = Math.max(4, Math.round(w * 0.04f));
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1.5f, w * 0.008f));
                p.setColor(t.muted);
                c.drawRect(b.left + pad, b.top + pad, b.right - pad, b.bottom - pad, p);

                p.setStyle(android.graphics.Paint.Style.FILL);
                // Top terminal prompt: grokbot@tv:~$
                p.setTypeface(tf);
                p.setTextSize(h * 0.14f);
                p.setTextAlign(android.graphics.Paint.Align.LEFT);
                p.setColor(t.green);
                c.drawText("grokbot@tv:~$", b.left + pad * 2.2f, b.top + h * 0.28f, p);

                // Center title: > GROK BOT
                p.setTypeface(tfBold);
                p.setTextSize(h * 0.23f);
                p.setColor(t.fgBright);
                c.drawText("> GROK BOT", b.left + pad * 2.2f, b.top + h * 0.58f, p);

                // Bottom badge: [ omarchy · tui ]
                p.setTypeface(tf);
                p.setTextSize(h * 0.135f);
                p.setColor(t.cyan);
                c.drawText("[ omarchy · tui ]", b.left + pad * 2.2f, b.top + h * 0.82f, p);
            }

            @Override
            public void setAlpha(int alpha) {}

            @Override
            public void setColorFilter(android.graphics.ColorFilter colorFilter) {}

            @Override
            public int getOpacity() {
                return android.graphics.PixelFormat.OPAQUE;
            }
        };
    }
}
