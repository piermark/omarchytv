package com.omarchytv.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.content.pm.PackageManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OmarchyTV home (Copyright (c) 2026 Marco Porro (x.com/MarcoPorro · www.marcoporro.it), MIT): Tokyo Night wallpaper, a Waybar-style top bar,
 * a pinned dock row, an app grid, and a Hyprland-style focus border.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener, TvAdapter.Listener, ShortcutAdapter.Listener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    private String liveTvPkg;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private FocusFrameView glass;
    private LinearLayout workspaces;
    private View cursor;
    private int activeWorkspace = -1;
    private View ambient;
    private TextView heroName;
    private GradientDrawable ambientGlow;
    private int ambientColor;
    private boolean heroShown;
    private TextView btnWifi;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    private boolean glassShown;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /** Terminal cursor blink period (xterm/VTE default 530 ms). */
    private static final long BLINK_MS = 530;
    /** Blinking terminal cursor after the prompt; runs only while the home screen is resumed. */
    private final Runnable blink = new Runnable() {
        @Override
        public void run() {
            cursor.setVisibility(cursor.getVisibility() == View.VISIBLE ? View.INVISIBLE : View.VISIBLE);
            // Section prompts: "> Apps" (drawn by DockDecoration), "> Omarchy" and "> TV" blink while their section has focus.
            boolean on = cursor.getVisibility() == View.VISIBLE;
            if (dock != null && dock.cursorOn != (focusSection <= 1 && on)) {
                dock.cursorOn = focusSection <= 1 && on;
                grid.invalidate();
            }
            if (tv != null) tv.blink();
            if (sc != null) sc.blink();
            if (home != null) home.blink(on);   // v2.10: the home prompt cursor blinks too
            handler.postDelayed(this, BLINK_MS);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateWifiLabel();
            updatePanes();
            if (home != null) {
                home.refresh();
                LauncherConfig c = LauncherConfig.get(MainActivity.this);
                Weather.refreshAuto(MainActivity.this, c, home::refresh);
                RemotePanes.refreshIfStale(MainActivity.this, c.remotePanesUrl, c.remotePanesRefreshSec, home::refresh);
            }
            handler.postDelayed(this, untilNextMinute());
        }
    };

    // ---- v2: logo menu, move mode, panes ---------------------------------------------------------

    /** Menu page to reopen after a live-preview rebuild, and the row that had focus. Survive recreate(). */
    private static String reopenPage, reopenRow;
    private AsciiMenu menu;
    private View btnMenu;
    private TextView moveHint;
    private LinearLayout panes;
    /** Position of the tile being moved, or -1. */
    private int movingPos = -1;
    private List<String> moveSnapshot;

    @Override
    protected void attachBaseContext(Context base) {
        // Language override + UI size + text scale (omarchytv.json "language", "uiSize", "geometry").
        super.attachBaseContext(LauncherConfig.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        setContentView(R.layout.activity_main);
        Theme.apply(getWindow().getDecorView());
        AmbilightController.syncTheme(this);
        {   // Move-mode hint: themed bg with the Hyprland "dragging" orange border (stroke colours can't be remapped from XML).
            android.graphics.drawable.GradientDrawable hb = new android.graphics.drawable.GradientDrawable();
            hb.setColor(Theme.withAlpha(Theme.cur().bg, 0.95f));
            hb.setStroke(Math.round(2 * getResources().getDisplayMetrics().density), Theme.cur().orange);
            findViewById(R.id.moveHint).setBackground(hb);
        }
        density = getResources().getDisplayMetrics().density;
        COLUMNS = LauncherConfig.get(this).columns;   // Layout → App size (4 / 5 / 6 / 7 per row)
        liveTvPkg = liveTvPackage(this);

        root = findViewById(R.id.root);
        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        heroName = findViewById(R.id.heroName);
        ambientGlow = new GradientDrawable();
        ambientGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambientGlow.setGradientCenter(0.32f, 0.42f);
        ambient.setBackground(ambientGlow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                ambientGlow.setGradientRadius((r - l) * 0.62f));
        workspaces = findViewById(R.id.workspaces);
        cursor = findViewById(R.id.cursor);
        ((TextView) findViewById(R.id.promptUser)).setText(promptText(LauncherConfig.get(this)));
        moveHint = findViewById(R.id.moveHint);
        panes = findViewById(R.id.panes);
        btnMenu = findViewById(R.id.btnMenu);
        btnMenu.setOnClickListener(v -> showMenu(null, null));
        boolean omarchyIcon = getResources().getBoolean(R.bool.omarchy_icon);
        findViewById(R.id.barIcon).setVisibility(omarchyIcon ? View.VISIBLE : View.GONE);
        findViewById(R.id.barMark).setVisibility(omarchyIcon ? View.GONE : View.VISIBLE);
        btnWifi = findViewById(R.id.btnWifi);

        grid = findViewById(R.id.grid);
        gridFrame = findViewById(R.id.gridFrame);
        applySurfaces();
        syncDreams();
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::updatePlates);
        // One span per app; the "> tv" header and its tile take a full row (the tile sizes itself to two columns).
        // Fixed focus line: onTileFocused scrolls the focused row to the dock's place itself, so the layout
        // manager's "scroll just enough" on focus is switched off (it would fight the fixed line).
        GridLayoutManager glm = new GridLayoutManager(this, COLUMNS) {
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull Rect rect, boolean immediate, boolean focusedChildVisible) {
                return false;
            }
        };
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                // apps: 1 each; "> omarchy" header full row, shortcut tiles 1 each; "> tv" header + tile full rows
                int apps = adapter.getItemCount(), scn = sc.getItemCount();
                if (position < apps) return 1;
                if (position < apps + scn) return position == apps ? COLUMNS : 1;
                return COLUMNS;
            }
        });
        grid.setLayoutManager(glm);
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        dock = new DockDecoration(this, COLUMNS, density);
        grid.addItemDecoration(dock);
        adapter = new AppAdapter(this, this, COLUMNS);
        tv = new TvAdapter(this, this);
        sc = new ShortcutAdapter(this, this);
        grid.setAdapter(new androidx.recyclerview.widget.ConcatAdapter(
                new androidx.recyclerview.widget.ConcatAdapter.Config.Builder()
                        .setStableIdMode(androidx.recyclerview.widget.ConcatAdapter.Config.StableIdMode.ISOLATED_STABLE_IDS).build(),
                adapter, sc, tv));

        // tvOS layout: push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            int dockRow = (int) ((AppAdapter.rowHeightDp(LauncherConfig.get(this)) + 6 + 12) * density);   // item + DockDecoration offsets
            int want = Math.max(0, h - dockRow - (int) (30 * density));
            // Bottom padding as tall as the dock area, so even the last row (> TV) can reach the focus line.
            int wantBottom = Math.max((int) (40 * density), h - want);
            if (grid.getPaddingTop() != want || grid.getPaddingBottom() != wantBottom) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), wantBottom);
            }
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy != 0) gridFrame.invalidate();   // top fade follows the scroll
                if (!glassShown) return;
                glassTargetY -= dy;
                if (springY.isRunning()) springY.animateToFinalPosition(glassTargetY);
                else glass.setTranslationY(glassTargetY);
            }
        });

        // Terminal feel: a stiff, critically damped spring (no bounce, ~80 ms) so the border snaps to the tile.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(3000f).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        }

        View btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        btnSettings.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v.getId() == R.id.btnMenu) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnMenu.setOnKeyListener(pillKeys);
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        });

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);
        // Omarchy Link (TV side): LAN server + mDNS, lives with the launcher process.
        OmarchyLink.start(this);
        OmarchyLink.addListener(linkListener);
        setupHomeV29();
    }

    // ---- v2.9 home (HomeV29: Waybar, fastfetch, widgets, app window; workspace 2 = the full grid) -------------

    private HomeV29 home;
    private Walker walker;
    private int workspace = 1;

    private void setupHomeV29() {
        home = new HomeV29(this, new HomeV29.Host() {
            @Override
            public void openSettings(View anchor) {
                showMenu(null, null);
            }

            @Override
            public void showWorkspace(int ws) {
                setWorkspace(ws);   // v2.10: 1 home · 2 tv · 3 app · 4 pc · 5 sistema
            }

            @Override
            public void wifiClick() {
                // SSID needs location on Android 8.1+: ask once from here, else open Wi-Fi settings.
                if (android.os.Build.VERSION.SDK_INT >= 26 && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED && !getPreferences(MODE_PRIVATE).getBoolean("askedLocation", false)) {
                    getPreferences(MODE_PRIVATE).edit().putBoolean("askedLocation", true).apply();
                    requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, 7);
                    return;
                }
                open(new Intent(Settings.ACTION_WIFI_SETTINGS));
            }

            @Override
            public void openBluetooth() {
                openFirst(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
                        new Intent("com.android.tv.settings.accessories.ADD_ACCESSORY"),
                        new Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.accessories.AddAccessoryActivity"),
                        new Intent("android.settings.CONNECTED_DEVICES_SETTINGS"),
                        new Intent(Settings.ACTION_SETTINGS));
            }

            @Override
            public void openTv() {
                onTvClick();
            }

            @Override
            public void tileMenu(int appPos, View anchor) {
                appTileMenu(appPos, anchor);
            }

            @Override
            public void savePanelPos(String id, int cx, int cy) {
                sysSet(j -> {
                    org.json.JSONObject o = j.optJSONObject("panelPos");
                    if (o == null) j.put("panelPos", o = new org.json.JSONObject());
                    o.put(id, new org.json.JSONArray().put(cx).put(cy));
                });
            }

            @Override
            public void moveApp(int from, int to) {
                adapter.move(from, to);
            }

            @Override
            public void finishMove(boolean save) {
                finishHomeMove(save);
            }

            @Override
            public List<HomeV29.Source> sources() {
                return tvSources();
            }

            @Override
            public boolean pcPaired() {
                return !OmarchyLink.pcs().isEmpty();
            }

            @Override
            public void pairPc() {
                OmarchyLink.openPairing();   // same as menu → Omarchy Link: a new code each time the page opens
                showMenu("link", null);
            }

            @Override
            public void sys(String id) {
                sysAction(id);
            }

            @Override
            public void showInputs(View anchor) {
                MainActivity.this.showInputs();
            }

            @Override
            public void screenPc(View anchor) {
                showScreenPc();
            }

            @Override
            public void openWalker() {
                MainActivity.this.openWalker();
            }

            @Override
            public void weatherMenu(View anchor) {
                showWeatherMenu();
            }

            @Override
            public void aiMenu(View anchor) {
                showMenu("ai", null);
            }

            @Override
            public void launchApp(int position) {
                lastFocusedKey = adapter.keyAt(position);
                adapter.launchAt(position);
            }

            @Override
            public String wifiName() {
                return currentNetworkName();
            }
        });
        root.addView(home, 2, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        findViewById(R.id.bar).setVisibility(View.INVISIBLE);   // kept only as the menus' anchor (top left)
        findViewById(R.id.leftBlock).setVisibility(View.GONE);
        panes.setVisibility(View.GONE);
        ViewGroup.MarginLayoutParams gl = (ViewGroup.MarginLayoutParams) gridFrame.getLayoutParams();
        gl.topMargin = home.barHeight() - (int) (34 * density);
        gridFrame.setLayoutParams(gl);
        setWorkspace(reopenWs);
    }

    private void setWorkspace(int ws) {
        if (ws < 1 || ws > 5) return;
        if (!LauncherConfig.get(this).workspaceShown(ws)) ws = 1;   // left out of "workspaces": {"order"}, e.g. a saved reopen
        workspace = ws;
        reopenWs = ws;
        home.setWorkspace(ws);
        gridFrame.setVisibility(View.GONE);   // v2.10: the app grid is workspace 3 inside HomeV29
    }

    /** Workspace to come back to after a settings rebuild (recreate()). */
    private static int reopenWs = 1;

    private boolean openFirst(Intent... intents) {
        for (Intent i : intents) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Exception ignored) {
            }
        }
        Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        return false;
    }

    /** OK lungo on an app tile (home row or workspace 3): Sposta / Nascondi. The TV tile never gets here. */
    private void appTileMenu(int position, View anchor) {
        if (home.movingApp >= 0 || position < 0 || position >= adapter.getItemCount()) return;
        String pkg = adapter.packageAt(position);
        if (pkg == null) return;
        if (menu != null && menu.isShowing()) menu.dismiss();
        menu = new AsciiMenu(this, 300);
        AsciiMenu.Page p = new AsciiMenu.Page(adapter.labelAt(position));
        p.add(str(R.string.ctx_move), "←↑↓→", r -> {
            home.post(() -> {
                moveSnapshot = adapter.packagesInOrder();
                home.startMove(position);
            });
            return false;
        });
        p.add(str(R.string.ctx_hide), null, r -> {
            LauncherConfig.update(this, j -> {
                List<String> list = new ArrayList<>(LauncherConfig.get(this).hidden);
                if (!list.contains(pkg)) list.add(pkg);
                j.put("hidden", LauncherConfig.array(list));
            });
            builtWith = LauncherConfig.get(this);
            reloadApps();
            home.bind(builtWith, adapter);
            home.post(home::focusDefault);
            return false;
        });
        LauncherConfig.WebApp webApp = adapter.webAt(position);
        if (webApp != null) {
            p.add(str(R.string.web_remove), null, r -> {
                removeWebApp(webApp.id);
                home.bind(builtWith, adapter);
                home.post(home::focusDefault);
                return false;
            });
        } else {
            p.add(str(R.string.ctx_info), null, r -> {
                AppAdapter.openAppInfo(this, pkg);
                return false;
            });
        }
        menu.showBelow(anchor, p);
    }

    private void finishHomeMove(boolean save) {
        int at = home.movingApp;
        home.endMove();
        if (at < 0) return;
        if (save) {
            List<String> pkgs = adapter.packagesInOrder();
            int d = Math.min(COLUMNS, pkgs.size());
            List<String> dock = new ArrayList<>(pkgs.subList(0, d));
            List<String> rest = new ArrayList<>(pkgs.subList(d, pkgs.size()));
            builtWith = LauncherConfig.update(this, j -> {
                j.put("dock", LauncherConfig.array(dock));
                j.put("order", LauncherConfig.array(rest));
            });
            Toast.makeText(this, R.string.move_saved, Toast.LENGTH_SHORT).show();
        } else if (moveSnapshot != null) {
            adapter.restoreOrder(moveSnapshot);
            Toast.makeText(this, R.string.move_cancelled, Toast.LENGTH_SHORT).show();
        }
        moveSnapshot = null;
        home.bind(LauncherConfig.get(this), adapter);
    }

    /** Workspace 2 · tv: the TV (Live TV app, last channel) and the connected HDMI inputs, CEC name when known. */
    private List<HomeV29.Source> tvSources() {
        List<HomeV29.Source> out = new ArrayList<>();
        if (liveTvIntent() != null) out.add(new HomeV29.Source("TV", str(R.string.live_tv), R.drawable.ln_monitor, this::onTvClick));
        out.addAll(hdmiSources());
        return out;
    }

    /** Connected HDMI / HDMI-CEC passthrough sources from TvInputManager. */
    private List<HomeV29.Source> hdmiSources() {
        List<HomeV29.Source> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim == null) return out;
        List<TvInputInfo> all;
        try {
            all = new ArrayList<>(tim.getTvInputList());
        } catch (RuntimeException e) {
            return out;
        }
        int ports = 0, connected = 0;
        for (TvInputInfo info : all) {
            if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI || info.getParentId() != null) continue;
            ports++;
            if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) connected++;
        }
        boolean bogus = ports > 1 && connected == ports;   // Sony: every port "connected"
        List<TvInputInfo> hdmi = new ArrayList<>();
        for (TvInputInfo info : all) {
            if (info.isPassthroughInput() && info.getType() == TvInputInfo.TYPE_HDMI && info.getParentId() == null) hdmi.add(info);
        }
        Collections.sort(hdmi, (a, b) -> String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this))));
        for (TvInputInfo port : hdmi) {
            String cec = null;
            for (TvInputInfo c : all) {   // HDMI-CEC devices are child inputs of their port
                if (port.getId().equals(c.getParentId())) {
                    CharSequence l = c.loadLabel(this);
                    if (l != null) cec = l.toString();
                    if (tim.getInputState(c.getId()) == TvInputManager.INPUT_STATE_CONNECTED) break;
                }
            }
            int state = tim.getInputState(port.getId());
            if (!bogus && state == TvInputManager.INPUT_STATE_DISCONNECTED && cec == null) continue;   // only connected inputs
            CharSequence l = port.loadLabel(this);
            String label = l == null ? port.getId() : l.toString();
            if (!label.toUpperCase(java.util.Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
            final String id = port.getId();
            out.add(new HomeV29.Source(cec != null ? cec : label, cec != null ? label : "", R.drawable.ln_input,
                    () -> open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                            TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel"))));
        }
        return out;
    }

    /** Workspace 5 · sistema rows. */
    private void sysAction(String id) {
        switch (id) {
            case "android":
                open(new Intent(Settings.ACTION_SETTINGS));
                break;
            case "themes":
                showMenu("themes", null);
                break;
            case "layout":
                showMenu("layout", null);
                break;
            case "widgets":
                showMenu("widgets", null);
                break;
            case "arrange":
                showMenu("arrange", null);
                break;
            case "screensaver":
                showMenu("screensaver", null);
                break;
            case "hidden":
                showMenu("hidden", null);
                break;
            case "cinemarchy":
                open(new Intent(this, CinemarchyActivity.class));
                break;
            case "grok-bot":
                showMenu("grok-bot", null);
                break;
            case "plugins":
                showMenu("plugins", null);
                break;
            case "spacex-tv":
                showSpacexMenu();
                break;
            case "grokbot-companion":
                showGrokbotCompanionDialog();
                break;
            case "ask":
                open(new Intent(this, AskActivity.class));
                break;
            case "agents":
                open(new Intent(this, AgentActivity.class));
                break;
            case "about":
                showAbout();
                break;
            case "menu":
                showMenu(null, null);
                break;
            case "idle": {
                Integer[] idles = {60, 150, 300, 600, 0};
                int n = next(idles, LauncherConfig.get(this).screensaverIdleSec);
                sysSet(j -> screensaver(j).put("idleSec", n));
                break;
            }
            case "logoAnim": {
                boolean n = !LauncherConfig.get(this).logoAnim;
                sysSet(j -> j.put("logoAnim", n));
                break;
            }
            case "textTiles": {
                boolean n = !LauncherConfig.get(this).textTiles;
                sysSet(j -> j.put("textTiles", n));
                break;
            }
            default:
                break;
        }
    }

    /** Saves one workspace-5 option and redraws in place (no recreate). */
    private void sysSet(LauncherConfig.Edit edit) {
        builtWith = LauncherConfig.update(this, edit);
        home.bind(builtWith, adapter);
        resetIdle();
    }

    private String currentNetworkName() {
        updateWifiLabel();
        return btnWifi.getText().toString();
    }

    private void showScreenPc() {
        if (menu != null && menu.isShowing()) menu.dismiss();
        menu = new AsciiMenu(this, 420);
        AsciiMenu.Page p = new AsciiMenu.Page(str(R.string.v29_screen_pc).toLowerCase(java.util.Locale.ROOT));
        if (!OmarchyLink.connected()) {
            p.hint(str(R.string.v29_pc_off));
        } else {
            p.add(str(R.string.v29_screen_start), null, r -> {
                OmarchyLink.screenMirror(true);
                Toast.makeText(this, R.string.v29_screen_asked, Toast.LENGTH_LONG).show();
                return false;
            });
            p.hint(str(R.string.v29_screen_note));
        }
        menu.showBelow(btnMenu, p);
    }

    private void showWeatherMenu() {
        if (menu != null && menu.isShowing()) menu.dismiss();
        menu = new AsciiMenu(this, 520);
        LauncherConfig cfg = LauncherConfig.get(this);
        AsciiMenu.Page p = new AsciiMenu.Page(str(R.string.v29_wx_title));
        String place = Weather.place(this, cfg);
        p.hint(str(R.string.v29_wx_current) + ": " + (place.isEmpty() ? "—" : place) + (Double.isNaN(cfg.weatherLat) ? " (auto)" : ""));
        p.add(str(R.string.v29_wx_auto), null, r -> {
            LauncherConfig.update(this, j -> j.remove("weather"));
            Weather.cacheFile(this).delete();
            Weather.refreshAuto(this, LauncherConfig.get(this), home::refresh);
            return false;
        });
        p.add(str(R.string.v29_wx_search), null, r -> {
            handler.post(this::cityDialog);
            return false;
        });
        p.add(str(R.string.wx_unit), cfg.tempF ? "°F" : "°C", r -> {
            boolean f = !LauncherConfig.get(this).tempF;
            LauncherConfig.update(this, j -> j.put("tempUnit", f ? "F" : "C"));
            home.bind(LauncherConfig.get(this), adapter);
            updatePanes();
            r.setValue(f ? "°F" : "°C");
            return true;
        });
        menu.showBelow(btnMenu, p);
    }

    private void cityDialog() {
        android.widget.EditText q = new android.widget.EditText(this);
        q.setSingleLine(true);
        new android.app.AlertDialog.Builder(this).setTitle(R.string.v29_wx_search).setView(q)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = q.getText().toString();
                    String lang = getResources().getConfiguration().getLocales().get(0).getLanguage();
                    new Thread(() -> {
                        List<org.json.JSONObject> res = Weather.searchCity(name, lang);
                        runOnUiThread(() -> cityResults(res));
                    }, "omarchytv-geocode").start();
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void cityResults(List<org.json.JSONObject> res) {
        if (!resumed) return;
        if (menu != null && menu.isShowing()) menu.dismiss();
        menu = new AsciiMenu(this, 620);
        AsciiMenu.Page p = new AsciiMenu.Page(str(R.string.v29_wx_title));
        if (res.isEmpty()) p.hint(str(R.string.v29_wx_none));
        for (org.json.JSONObject c : res) {
            String label = c.optString("name") + (c.optString("admin1").isEmpty() ? "" : ", " + c.optString("admin1"));
            p.add(label, c.optString("country_code", ""), r -> {
                LauncherConfig.update(this, j -> j.put("weather", new org.json.JSONObject().put("lat", c.optDouble("latitude"))
                        .put("lon", c.optDouble("longitude")).put("place", c.optString("name"))));
                Weather.cacheFile(this).delete();
                Weather.refreshAuto(this, LauncherConfig.get(this), home::refresh);
                return false;
            });
        }
        menu.showBelow(btnMenu, p);
    }

    private void openWalker() {
        if (walker != null) return;
        List<Walker.Item> items = new ArrayList<>();
        String tApp = str(R.string.v29_type_app);
        for (int i = 0; i < adapter.getItemCount(); i++) {
            final int pos = i;
            items.add(new Walker.Item(adapter.labelAt(i), tApp, () -> adapter.launchAt(pos)));
        }
        LauncherConfig cfg = LauncherConfig.get(this);
        java.util.Set<String> seenNames = new java.util.HashSet<>();
        for (Walker.Item it : items) seenNames.add(it.name.toLowerCase(java.util.Locale.ROOT));
        for (LauncherConfig.WebApp w : LauncherConfig.BUILTIN_AI_WEBAPPS) {
            final LauncherConfig.WebApp fw = w;
            if ("grok-bot".equals(w.id)) {
                if (!seenNames.contains(w.name.toLowerCase(java.util.Locale.ROOT))) {
                    items.add(new Walker.Item(w.name, tApp, () -> AskActivity.open(this)));
                }
                items.add(new Walker.Item("Grok Web (grok.com)", tApp, () -> WebAppActivity.open(this, fw)));
                continue;
            }
            if (!seenNames.contains(w.name.toLowerCase(java.util.Locale.ROOT))) {
                items.add(new Walker.Item(w.name, tApp, () -> WebAppActivity.open(this, fw)));
            }
        }
        items.add(new Walker.Item("🎬 Cinemarchy (Media, Streaming, Live TV & Snippets)", str(R.string.cinemarchy_hub), () -> open(new Intent(this, CinemarchyActivity.class))));
        items.add(new Walker.Item("Aquarium Screensaver (Omarchy Cyber-TUI Ocean)", str(R.string.menu_screensaver), () -> {
            LauncherConfig.update(this, j -> screensaver(j).put("art", "aquarium").put("mode", "aquarium"));
            open(new Intent(this, ScreensaverPreviewActivity.class));
        }));
        items.add(new Walker.Item("> grok bot", str(R.string.grok_bot_hub), () -> open(new Intent(this, AskActivity.class))));
        items.add(new Walker.Item(str(R.string.ask_entry), str(R.string.grok_bot_hub), () -> open(new Intent(this, AskActivity.class))));
        items.add(new Walker.Item(str(R.string.grok_bot_hub), str(R.string.v29_type_settings), () -> handler.post(() -> showMenu("grok-bot", null))));
        if (cfg.shortcutsOn) for (LauncherConfig.Shortcut x : cfg.shortcuts) items.add(new Walker.Item(x.label, str(R.string.v29_type_content), () -> onShortcutClick(x)));
        if (cfg.tvEnabled) items.add(new Walker.Item(str(R.string.tv_tile), str(R.string.v29_type_content), this::onTvClick));
        items.add(new Walker.Item("Wi-Fi: " + currentNetworkName(), str(R.string.v29_type_wifi), () -> open(new Intent(Settings.ACTION_WIFI_SETTINGS))));
        items.addAll(Walker.settingsItems(this, () -> handler.post(() -> showMenu(null, null)), () -> handler.post(this::showInputs)));
        walker = new Walker(this, home.s, items, new Walker.Host() {
            @Override
            public void voice() {
                walkerVoice = true;
                startVoiceSearch();
            }

            @Override
            public void closed() {
                walker = null;
                home.post(home::restoreFocus);
            }
        });
        root.addView(walker, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        walker.focus();
    }

    private boolean walkerVoice;

    /** Intent extra from KeyMapService: the id of an OmarchyTV action to run (see LauncherConfig.KEY_ACTIONS). */
    static final String EXTRA_ACTION = "com.omarchytv.home.extra.ACTION";

    /** Runs a "keys" action binding. Unknown ids do nothing. */
    private void runKeyAction(String id) {
        if (home == null || movingPos >= 0) return;
        if (id.startsWith("workspace-") || id.equals("home")) {
            if (walker != null) walker.close();
            if (menu != null && menu.isShowing()) menu.dismiss();
            int ws = id.equals("home") ? 1 : id.charAt(id.length() - 1) - '0';
            setWorkspace(ws);
            home.onHomeShown();
            return;
        }
        switch (id) {
            case "walker":
                openWalker();
                break;
            case "voice":
                openWalker();
                walkerVoice = true;
                startVoiceSearch();
                break;
            case "ask":
                AskActivity.open(this);
                break;
            case "menu":
                if (walker != null) walker.close();
                showMenu(null, null);
                break;
            case "screensaver":
                if (walker != null) walker.close();
                if (menu != null && menu.isShowing()) menu.dismiss();
                startSaver();
                break;
            case "next-theme": {
                String next = AskActivity.nextThemeName(this);
                if (next != null) applyLive(null, null, AskActivity.themeEdit(next));
                break;
            }
            default:
                break;
        }
    }

    private boolean resumed, linkRefreshing, linkConfigPending;
    private String pendingSourcesId;
    /** "> omarchy" shortcuts section (between apps and "> tv"). */
    private ShortcutAdapter sc;
    private FadeTopFrame gridFrame;

    private final OmarchyLink.Listener linkListener = (type, data) -> {
        switch (type) {
            case "pcs": {
                String n = data.optString("paired", "");
                if (!n.isEmpty()) Toast.makeText(this, getString(R.string.link_paired_toast, n), Toast.LENGTH_LONG).show();
                refreshMenu("link");
                break;
            }
            case "pairing":
                refreshMenu("link");
                break;
            case "config":
                onLinkConfig();
                break;
            case "clients":
                if (resumed) {
                    if (home != null) home.refresh();
                    updatePanes();
                    sc.notifyItemRangeChanged(0, sc.getItemCount());   // "pc" tiles: connected / not connected
                }
                break;
            case "agent_activity":
                if (resumed) updateAgentsPane();
                break;
            case "stats":
                if (resumed && home != null) home.refresh();
                break;
            case "banners":   // a custom app banner was pushed or removed (banners/<package>.png)
                if (resumed && adapter != null) {
                    adapter.reload();
                    if (home != null) home.bind(LauncherConfig.get(this), adapter);
                }
                break;
            case "grokbot_companion":   // omarchy-link pushed the companion's current bot / a bubble line
                onGrokbotCompanion();
                break;
            case "grokbot_answer":
            case "grokbot_error":
                keepCompanionReply(type, data);
                onGrokbotReply(type, data);
                break;
            case "ai_sources_ok":
                if (data.optString("id").equals(pendingSourcesId)) pendingSourcesId = null;
                break;
            case "ai_sources_error":
                if (data.optString("id").equals(pendingSourcesId)) {
                    pendingSourcesId = null;
                    // The switch reverts by itself: the stored aiSources never changed.
                    Toast.makeText(this, getString(R.string.ai_src_failed,
                            data.optString("message", data.optString("error"))), Toast.LENGTH_LONG).show();
                    refreshMenu("ai");
                }
                break;
            default:
                break;
        }
    };

    private static boolean sameShortcuts(LauncherConfig a, LauncherConfig b) {
        if (a.shortcutsOn != b.shortcutsOn || a.shortcuts.size() != b.shortcuts.size()) return false;
        for (int i = 0; i < a.shortcuts.size(); i++) {
            LauncherConfig.Shortcut x = a.shortcuts.get(i), y = b.shortcuts.get(i);
            if (!(x.id + x.label + x.type + x.pkg + x.uri + x.command).equals(y.id + y.label + y.type + y.pkg + y.uri + y.command)) return false;
        }
        return true;
    }

    /** Re-renders an open settings page in place (new code, PC paired, sources switched). */
    private void refreshMenu(String key) {
        if (!resumed || menu == null || !menu.isShowing() || !key.equals(menuPage)) return;
        linkRefreshing = true;
        menu.dismiss();
        linkRefreshing = false;
        showMenu(key, null);
    }

    /** The PC pushed something: credits only refresh the panes; theme/wallpaper/layout rebuild the screen. */
    private void onLinkConfig() {
        if (!resumed) return;   // onResume compares the config anyway
        LauncherConfig cfg = LauncherConfig.get(this);
        if (cfg == builtWith) return;
        // Compared in memory (LauncherConfig.restSig): no extra file read on the UI thread.
        if (builtWith != null && cfg.restSig.equals(builtWith.restSig) && cfg.dataSig.equals(builtWith.dataSig)) {
            // Only the agent list (PUT /v1/agents, every 60 s) changed: update in place, keep focus.
            builtWith = cfg;
            updateAgentsPane();
            return;
        }
        if (builtWith != null && cfg.restSig.equals(builtWith.restSig)) {
            boolean scChanged = !sameShortcuts(builtWith, cfg);
            builtWith = cfg;
            buildPanes();   // PC panes may come, go or change size
            if (scChanged) applyShortcuts();
            refreshMenu("ai");
            return;
        }
        if (menu != null && menu.isShowing()) {
            linkConfigPending = true;   // rebuild when the menu closes
            return;
        }
        builtWith = null;
        recreate();
    }

    /** Config the views were built with; a newer JSON file (adb push, LAN companion) rebuilds the screen. */
    private LauncherConfig builtWith;

    @Override
    protected void onResume() {
        super.onResume();
        bindCompanion();
        OmarchyLink.sweepCasts(this);   // 24 h cleanup of cast uploads (no-op when nothing was ever cast)
        LauncherConfig cfg = LauncherConfig.get(this);
        if (builtWith != null && reopenPage == null && (cfg.randomTheme || cfg.randomWallpaper)) {
            LauncherConfig.update(this, j -> LauncherConfig.applyRandomRotation(j, new java.util.Random()));
            cfg = LauncherConfig.get(this);
        }
        if (builtWith != null && cfg != builtWith && !cfg.restSig.equals(builtWith.restSig)) {
            builtWith = null;
            recreate();
            return;
        }
        builtWith = cfg;
        resumed = true;
        // KEYCODE_ASSIST never reaches dispatchKeyEvent: the system starts the Assistant role holder instead. With
        // OmarchyTV holding that role, the mic key lands here: the Grok Bot companion by voice ("voiceKey": "companion",
        // the default) or Walker ("launcher"; pressed again while Walker is open it starts voice search).
        String act = getIntent().getAction();
        if (Intent.ACTION_ASSIST.equals(act) || Intent.ACTION_VOICE_COMMAND.equals(act) || Intent.ACTION_SEARCH_LONG_PRESS.equals(act)) {
            setIntent(new Intent(getIntent()).setAction(Intent.ACTION_MAIN));
            handler.post(() -> {
                if (voiceToCompanion()) openCompanionChat(true);
                else if (walker == null) openWalker();
                else {
                    walkerVoice = true;
                    startVoiceSearch();
                }
            });
        }
        OmarchyLink.setHomeVisible(true);
        hideSaver();   // back from the screensaver window (own process)
        Theme.apply(getWindow().getDecorView());
        ((WallpaperView) findViewById(R.id.wallpaper)).refreshIfChanged();
        playWallpaperIntro(cfg);
        platesSig = "";
        resetIdle();
        glassShown = false;
        glass.setAlpha(0f);
        heroShown = false;
        heroName.setAlpha(0f);
        ambient.setAlpha(0f);
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        reloadApps();
        applyTv();
        // Terminal-style entrance: the wordmark decrypts; the prompt is just user@omarchytv:~$ and a block cursor.
        cursor.setBackgroundColor(Theme.cur().fg);
        cursor.setVisibility(View.VISIBLE);
        updateWifiLabel();
        buildPanes();
        if (home != null) {
            home.bind(cfg, adapter);
            Weather.refreshAuto(this, cfg, home::refresh);
            RemotePanes.refreshIfStale(this, cfg.remotePanesUrl, cfg.remotePanesRefreshSec, home::refresh);
        }
        // Stock launcher back as home after a reboot? Ask once per boot, gently; never change it ourselves.
        // With the Home button override on, Home already lands here: nothing to ask.
        if (!HomeCheck.isDefaultHome(this) && !HomeOverride.enabled() && (getIntent().getBooleanExtra(HomeCheck.EXTRA_ASK, false) || HomeCheck.shouldAsk(this))) {
            getIntent().removeExtra(HomeCheck.EXTRA_ASK);
            HomeCheck.asked(this);
            handler.postDelayed(this::homePrompt, 600);
        }
        // The agents pane is on screen: ask the PC for fresh activity (nothing is sent with no PC connected).
        if (agentsPane() != null) OmarchyLink.agentSnapshot();
        handler.postDelayed(minuteTick, untilNextMinute());
        handler.postDelayed(blink, BLINK_MS);

        // Return to the app we left from, or the same slot if that app is gone. Always refocus: after
        // reload() the view that holds focus may no longer show the app it had.
        grid.post(() -> {
            if (home != null) home.onHomeShown();   // v2.10: TV tile focused, logo animation (home only)
            else focusTile(Math.max(0, returnSlot));
            if (reopenPage != null) {
                // Live preview: the screen was rebuilt with the new setting; bring the menu back.
                String page = reopenPage, row = reopenRow;
                reopenPage = null;
                reopenRow = null;
                grid.postDelayed(() -> showMenu(page, row), 60);
            }
        });
        // A remote key binding (KeyMapService) asked for an action: run it once, now that the home is up.
        String keyAction = getIntent().getStringExtra(EXTRA_ACTION);
        if (keyAction != null) {
            getIntent().removeExtra(EXTRA_ACTION);
            grid.post(() -> runKeyAction(keyAction));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (companion != null) { companion.setArmed(false); companion.stop(); }
        resumed = false;
        OmarchyLink.setHomeVisible(false);
        handler.removeCallbacks(idle);
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(blink);
        handler.removeCallbacks(agentsTick);
        if (home != null) {
            home.stopLogo();   // no timers while the home is not visible
            if (home.movingApp >= 0) finishHomeMove(false);
        }
        if (movingPos >= 0) finishMove(false);
        stopWallpaperIntro();
        if (menu != null && menu.isShowing()) menu.dismiss();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.unregisterNetworkCallback(networkCallback);
        OmarchyLink.removeListener(linkListener);
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere. Opened while another launcher is home (reboot check): Back leaves,
        // unless the Home button override makes OmarchyTV the home screen anyway.
        if (!HomeCheck.isDefaultHome(this) && !HomeOverride.enabled()) moveTaskToBack(true);
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        boolean app = position < adapter.getItemCount();
        boolean shortcut = !app && position < adapter.getItemCount() + sc.getItemCount();
        focusSection = shortcut ? 3 : !app ? 2 : position < COLUMNS ? 0 : 1;
        tv.setFocusedSection(focusSection == 2);
        sc.setFocusedSection(focusSection == 3);
        if (paneRow2 != null) paneRow2.animate().alpha(focusSection == 0 ? 1f : 0f).setDuration(120).start();
        if (focusSection > 1 && dock.cursorOn) {
            dock.cursorOn = false;
            grid.invalidate();
        }
        if (app) {
            lastFocused = position;
            lastFocusedKey = adapter.keyAt(position);
            updateWorkspaces(position / COLUMNS);
        }
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches FocusFrameView's halo inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(0f);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(90).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
        View item = (View) card.getParent();
        if (app && position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
            showHero(position, r.top - dy);
        } else {
            hideHero();
            // Fixed focus line (leanback): every row - apps, > Omarchy, > TV - comes to rest where the dock sits.
            int dy = item.getTop() - gridFrame.focusLine();
            if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
        }
        updateGridClip(position, app, shortcut);
    }

    // ---- top shelf -------------------------------------------------------------------------------

    /** Waybar-style workspace indicators: one per row (1 = dock), the focused row highlighted. */
    private void updateWorkspaces(int activeRow) {
        int rows = Math.max(1, Math.min(9, (adapter.getItemCount() + COLUMNS - 1) / COLUMNS));
        if (workspaces.getChildCount() != rows) {
            workspaces.removeAllViews();
            for (int i = 0; i < rows; i++) {
                TextView t = (TextView) Theme.applied(getLayoutInflater().inflate(R.layout.item_workspace, workspaces, false));
                t.setText(String.valueOf(i + 1));
                workspaces.addView(t);
            }
            activeWorkspace = -1;
        }
        if (activeRow == activeWorkspace) return;
        activeWorkspace = activeRow;
        for (int i = 0; i < workspaces.getChildCount(); i++) workspaces.getChildAt(i).setActivated(i == activeRow);
    }

    private String platesSig = "";

    /**
     * Omarchy look: the wallpaper stays untouched, so readability comes from the windows' own backgrounds.
     * Each pane gets the user's transparency, raised only as far as its dimmest text needs
     * for >= 4.5:1 over the brightest wallpaper pixel behind it. Cheap; only runs when something moved.
     */
    private void updatePlates() {
        if (root.getHeight() == 0) return;
        WallpaperView wall = findViewById(R.id.wallpaper);
        LauncherConfig cfg = LauncherConfig.get(this);
        java.util.List<View> plates = new java.util.ArrayList<>();
        for (int i = 0; i < panes.getChildCount(); i++) {
            View row = panes.getChildAt(i);
            if (row instanceof ViewGroup) {
                for (int k = 0; k < ((ViewGroup) row).getChildCount(); k++) plates.add(((ViewGroup) row).getChildAt(k));
            }
        }
        StringBuilder sig = new StringBuilder().append(wall.buildId).append(':').append(cfg.transparency).append(':');
        int[] r = new int[2], o = new int[2];
        root.getLocationInWindow(o);
        android.graphics.RectF[] rects = new android.graphics.RectF[plates.size()];
        for (int i = 0; i < plates.size(); i++) {
            View v = plates.get(i);
            v.getLocationInWindow(r);
            rects[i] = new android.graphics.RectF(r[0] - o[0], r[1] - o[1], r[0] - o[0] + v.getWidth(), r[1] - o[1] + v.getHeight());
            sig.append(rects[i].toShortString());
        }
        if (sig.toString().equals(platesSig)) return;
        platesSig = sig.toString();
        for (int i = 0; i < plates.size(); i++) {
            if (rects[i].isEmpty()) continue;
            float a = Surfaces.plateAlpha(wall.worstBehind(rects[i]), cfg.transparency,
                    cfg.theme.fgDim & 0xFFFFFF, cfg.theme.fgBright & 0xFFFFFF, cfg.accent & 0xFFFFFF);
            int color = (Math.round(a * 255) << 24) | Surfaces.bg();
            View v = plates.get(i);
            v.setBackground(paneBackground(color));
        }
    }

    /** Waybar strip at the Layout → Transparency alpha, keeping its thin bottom rule. */
    /** Omarchy shell [bar]: background = theme background, background-alpha = 1.0, text = foreground. */
    private void applySurfaces() {
        applyBar(0xFF000000 | Surfaces.bg());
    }

    private void applyBar(int color) {
        android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
        fill.setColor(color);
        android.graphics.drawable.ColorDrawable rule = new android.graphics.drawable.ColorDrawable(Theme.m(0xFF292E42));
        android.graphics.drawable.LayerDrawable bar = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{rule, fill});
        bar.setLayerInset(1, 0, 0, 0, Math.round(2 * density));
        findViewById(R.id.bar).setBackground(bar);
    }

    /** Pane surface: theme bg at the Layout → Transparency alpha (raised by updatePlates if needed); text opaque. */
    private android.graphics.drawable.Drawable paneBackground(int color) {
        // Same window style as the tiles (Hyprland inactive window: theme border, border_size from Layout).
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setStroke(Math.max(1, Math.round(LauncherConfig.get(this).borderDp * density)), Theme.cur().inactiveBorder);   // Hyprland window border
        return d;
    }

    /** Keeps the scrolled strip under the panes, and pins the section prompt once its own prompt scrolled away. */
    private void updateGridClip(int position, boolean app, boolean shortcut) {
        int minTop = 0;
        if (panes.getVisibility() == View.VISIBLE) {
            int bottom = 0;
            for (int i = 0; i < panes.getChildCount(); i++) {
                View row = panes.getChildAt(i);
                if (row.getVisibility() != View.VISIBLE || (row == paneRow2 && focusSection != 0)) continue;
                bottom = Math.max(bottom, panes.getTop() + row.getBottom());
            }
            int[] a = new int[2], b = new int[2];
            root.getLocationInWindow(a);
            gridFrame.getLocationInWindow(b);
            if (bottom > 0) minTop = bottom - (b[1] - a[1]) + (int) (8 * density);
        }
        gridFrame.setMinTop(Math.max(0, minTop));
        String sticky = null;
        if (app && position >= 2 * COLUMNS) sticky = getString(R.string.apps_prompt);       // row 3+: walker header gone
        else if (shortcut && position - adapter.getItemCount() - 1 >= COLUMNS) sticky = getString(R.string.sc_prompt);
        gridFrame.setSticky(sticky, LauncherConfig.get(this).accent);
    }

    private void showHero(int position, float dockTopInRoot) {
        // Plain app name, smaller than before and above the "> Apps" header, so the header stays a divider.
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 14 * density - 34 * density - 10 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());

        if (!heroShown) {
            heroName.setY(y);
            heroName.setAlpha(1f);
            TextFx.decrypt(heroName, label, 320);
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(600).start();
            heroShown = true;
        } else if (changed) {
            heroName.setY(y);
            TextFx.decrypt(heroName, label, 320);
            if (color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        } else {
            heroName.setY(y);
        }
    }

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        ambientGlow.setColors(new int[]{0x5C000000 | c, 0x26000000 | c, 0x00000000 | c});
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        TextFx.finish(heroName, "");
        heroName.setAlpha(0f);
        ambient.animate().alpha(0f).setDuration(300).start();
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(90).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) {                // last app row: "> omarchy" shortcuts, the TV tile, or stay
                if (sc.tiles() > 0) {
                    focusGlobal(count + 1 + Math.min(position % COLUMNS, sc.tiles() - 1));
                    return true;
                }
                int t = tv.tile();
                if (t >= 0) focusGlobal(tv.offset() + t);
                return true;
            }
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            if (card == null) return;
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            if (caps == null) {
                text = getString(R.string.offline);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                text = getString(R.string.ethernet);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        btnWifi.setText(text);
    }

    private boolean tryOpen(Intent i) {
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    private void open(Intent i) {
        if (!tryOpen(i)) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = Theme.applied(getLayoutInflater().inflate(R.layout.popup_inputs, root, false));
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (tunerId == null || info.getId().compareTo(tunerId) < 0)) {
                    tunerId = info.getId();
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase().contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel")));
            }
        }
        Intent liveTv = liveTvPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, panel.getMeasuredWidth()));

        // Appear like a floating terminal window: instant, then the row labels decrypt.
        popup.showAsDropDown(anchor, 0, (int) (6 * density), Gravity.START);
        for (int i = 0; i < rows.getChildCount(); i++) {
            TextView t = rows.getChildAt(i).findViewById(R.id.title);
            if (t != null) TextFx.decrypt(t, t.getText(), 260);
        }
        rows.getChildAt(0).requestFocus();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(Theme.m(0xFF292E42));   // tn selection
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = Theme.applied(getLayoutInflater().inflate(R.layout.item_input, rows, false));
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }

    // ---- logo menu (settings) --------------------------------------------------------------------

    private String str(int res) {
        return getString(res);
    }

    // ---- Launcher settings > Remote buttons (omarchytv.json "keys", run by KeyMapService) ------------------------------

    /** The key (keycode name) being edited; static so the button page can reopen after the rebuild that follows a change. */
    private static String keyEdit = "";

    private static int keyCode(String name) {
        return android.view.KeyEvent.keyCodeFromString("KEYCODE_" + name);
    }

    /** Profiles (assets/remotes.json) of the remotes connected right now. */
    private List<RemoteProfiles.Profile> connectedRemotes() {
        List<RemoteProfiles.Profile> all = RemoteProfiles.load(this), out = new ArrayList<>();
        for (int id : android.view.InputDevice.getDeviceIds()) {
            android.view.InputDevice d = android.view.InputDevice.getDevice(id);
            RemoteProfiles.Profile p = d == null ? null : RemoteProfiles.forDevice(all, d.getVendorId(), d.getProductId());
            if (p != null && !out.contains(p)) out.add(p);
        }
        return out;
    }

    private AsciiMenu.Page remoteKeysPage() {
        AsciiMenu.Page p = new AsciiMenu.Page(str(R.string.menu_remote_keys));
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        ComponentName me = new ComponentName(this, KeyMapService.class);
        boolean on = false;   // entries can be short (pkg/.Cls) or full (pkg/pkg.Cls): compare as components
        for (String c : enabled != null ? enabled.split(":") : new String[0]) on |= me.equals(ComponentName.unflattenFromString(c));
        p.add(str(R.string.keys_service), str(on ? R.string.on : R.string.off), r -> {
            open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return false;
        });
        List<RemoteProfiles.Profile> remotes = connectedRemotes();
        for (RemoteProfiles.Profile rp : remotes) {
            for (RemoteProfiles.Button btn : rp.buttons) {
                p.add(rp.name + " \u00b7 " + btn.name, "\u203a", r -> {
                    keyEdit = btn.key;
                    return push(keyButtonPage(rp, btn));
                });
            }
        }
        if (remotes.isEmpty()) p.hint(str(R.string.keys_none));
        p.hint(str(R.string.keys_hint));
        return p;
    }

    /** The four press types of one remote button. */
    private AsciiMenu.Page keyButtonPage(RemoteProfiles.Profile rp, RemoteProfiles.Button btn) {
        LauncherConfig.KeyBinding[] b = LauncherConfig.get(this).keys.get(keyCode(btn.key));
        AsciiMenu.Page p = new AsciiMenu.Page(rp.name + " \u00b7 " + btn.name);
        int[] labels = {R.string.keys_single, R.string.keys_double, R.string.keys_triple, R.string.keys_long};
        for (int i = 0; i < labels.length; i++) {
            final int press = i;
            p.add(str(labels[i]), keySummary(b == null ? null : b[i]), r -> push(keyBindingPage(press)));
        }
        if (!btn.defaultBehaviour.isEmpty()) p.hint(getString(R.string.keys_default, btn.defaultBehaviour));
        return p;
    }

    /** The button page of {@link #keyEdit} on a connected remote, else the list. */
    private AsciiMenu.Page remoteKeyPage() {
        for (RemoteProfiles.Profile rp : connectedRemotes()) {
            RemoteProfiles.Button btn = rp.button(keyEdit);
            if (btn != null) return keyButtonPage(rp, btn);
        }
        return remoteKeysPage();
    }

    private String keySummary(LauncherConfig.KeyBinding kb) {
        if (kb == null) return str(R.string.off);
        switch (kb.type) {
            case LauncherConfig.KeyBinding.APP:
                try {
                    return String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(kb.value, 0)));
                } catch (PackageManager.NameNotFoundException e) {
                    return kb.value;
                }
            case LauncherConfig.KeyBinding.ACTION:
                return keyActionLabel(kb.value);
            default:
                return str(R.string.keys_intent_value);
        }
    }

    private String keyActionLabel(String id) {
        if (id.startsWith("workspace-")) return getString(R.string.keys_action_workspace, id.substring(id.length() - 1));
        switch (id) {
            case "walker": return str(R.string.keys_action_walker);
            case "voice": return str(R.string.keys_action_voice);
            case "home": return str(R.string.keys_action_home);
            case "ask": return str(R.string.keys_action_ask);
            case "menu": return str(R.string.keys_action_menu);
            case "screensaver": return str(R.string.keys_action_screensaver);
            case "next-theme": return str(R.string.keys_action_next_theme);
            default: return id;
        }
    }

    /** One press type: off, an app, an OmarchyTV action or an intent. */
    private AsciiMenu.Page keyBindingPage(int press) {
        int[] labels = {R.string.keys_single, R.string.keys_double, R.string.keys_triple, R.string.keys_long};
        LauncherConfig.KeyBinding[] b = LauncherConfig.get(this).keys.get(keyCode(keyEdit));
        LauncherConfig.KeyBinding cur = b == null ? null : b[press];
        AsciiMenu.Page p = new AsciiMenu.Page(str(labels[press]) + "  \u00b7  " + keyEdit);
        p.add(str(R.string.off), null, r -> saveKeyBinding(press, null, null));
        p.add(str(R.string.keys_app), "\u203a", r -> push(keyAppPage(press)));
        p.add(str(R.string.keys_action), "\u203a", r -> push(keyActionPage(press)));
        p.add(str(R.string.keys_intent), null, r -> {
            handler.post(() -> keyIntentDialog(press, cur != null && LauncherConfig.KeyBinding.INTENT.equals(cur.type) ? cur.value : ""));
            return false;
        });
        return p;
    }

    /** Launchable (leanback) apps by label. */
    private AsciiMenu.Page keyAppPage(int press) {
        PackageManager pm = getPackageManager();
        java.util.TreeMap<String, String> apps = new java.util.TreeMap<>();   // "label\0pkg" -> pkg, sorted by label
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            if (ri.activityInfo == null || ri.activityInfo.packageName.equals(getPackageName())) continue;
            apps.put(String.valueOf(ri.loadLabel(pm)).toLowerCase(java.util.Locale.ROOT) + "\0" + ri.activityInfo.packageName,
                    ri.activityInfo.packageName + "\0" + ri.loadLabel(pm));
        }
        AsciiMenu.Page p = new AsciiMenu.Page(str(R.string.keys_app));
        for (String v : apps.values()) {
            String[] pkgLabel = v.split("\0", 2);
            p.add(pkgLabel[1], null, r -> saveKeyBinding(press, LauncherConfig.KeyBinding.APP, pkgLabel[0]));
        }
        return p;
    }

    private AsciiMenu.Page keyActionPage(int press) {
        AsciiMenu.Page p = new AsciiMenu.Page(str(R.string.keys_action));
        for (String id : LauncherConfig.KEY_ACTIONS) {
            p.add(keyActionLabel(id), null, r -> saveKeyBinding(press, LauncherConfig.KeyBinding.ACTION, id));
        }
        return p;
    }

    /** Writes keys.<key>.<press> (type null = remove it) and reopens the Remote buttons page on that row. */
    private boolean saveKeyBinding(int press, String type, String value) {
        int[] labels = {R.string.keys_single, R.string.keys_double, R.string.keys_triple, R.string.keys_long};
        String name = keyEdit, pressName = LauncherConfig.KEY_PRESSES[press];
        applyLive("remote-key", str(labels[press]), j -> {
            org.json.JSONObject keys = j.optJSONObject("keys");
            if (keys == null) j.put("keys", keys = new org.json.JSONObject());
            org.json.JSONObject k = keys.optJSONObject(name);
            if (k == null) keys.put(name, k = new org.json.JSONObject());
            if (type == null) k.remove(pressName);
            else k.put(pressName, new org.json.JSONObject().put(type, value));
            if (k.length() == 0) keys.remove(name);
            if (keys.length() == 0) j.remove("keys");
        });
        return false;
    }

    /** Type an intent URI (Intent.toUri(URI_INTENT_SCHEME)); checked with Intent.parseUri. */
    private void keyIntentDialog(int press, String current) {
        android.widget.EditText e = new android.widget.EditText(this);
        e.setSingleLine(true);
        e.setText(current);
        e.setSelection(e.getText().length());
        e.setHint("intent:#Intent;action=android.settings.SETTINGS;end");
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.keys_intent_title)
                .setView(e)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String v = e.getText().toString().trim();
                    try {
                        if (!v.startsWith("intent:")) throw new java.net.URISyntaxException(v, "not an intent URI");
                        Intent.parseUri(v, Intent.URI_INTENT_SCHEME);
                    } catch (java.net.URISyntaxException ex) {
                        Toast.makeText(this, R.string.keys_intent_bad, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    saveKeyBinding(press, LauncherConfig.KeyBinding.INTENT, v);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static <T> T next(T[] values, T current) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) return values[(i + 1) % values.length];
        return values[0];
    }

    /** Saves a change and rebuilds the screen behind the menu, which reopens on the same row (live preview). */
    private void applyLive(String page, String row, LauncherConfig.Edit edit) {
        LauncherConfig.update(this, edit);
        reopenPage = page;
        reopenRow = row;
        builtWith = null;
        if (menu != null) menu.dismiss();
        recreate();
    }

    private void showMenu(String pageKey, String row) {
        if (menu != null && menu.isShowing()) menu.dismiss();
        menu = new AsciiMenu(this, 420);
        menu.setOnDismiss(() -> {
            if (linkConfigPending && !linkRefreshing && builtWith != null) {
                linkConfigPending = false;
                handler.post(this::onLinkConfig);
            }
        });
        AsciiMenu.Page main = mainPage();
        AsciiMenu.Page page = pageKey == null ? null : page(pageKey);
        if (page == null) menu.showBelow(btnMenu, main);
        else {
            menu.showBelow(btnMenu, main, page);
            if (row != null) menu.focusRow(row);
        }
    }

    private AsciiMenu.Page page(String key) {
        switch (key) {
            case "launcher": return launcherPage();
            case "layout": return layoutPage();
            case "wallpaper": return wallpaperPage();
            case "themes": return themesPage();
            case "arrange": return arrangePage();
            case "workspaces": return workspacesPage();
            case "screensaver": return screensaverPage();
            case "hidden": return hiddenPage();
            case "link": return linkPage();
            case "ai": return aiPage();
            case "widgets": return widgetsPage();
            case "tv": return tvPage();
            case "webapps": return webAppsPage();
            case "assistant": return assistantPage();
            case "grok-bot": return grokBotPage();
            case "ambilight": return ambilightPage();
            case "lights": return lightsPage();
            case "wireguard": return wireguardPage();
            case "plugins": return pluginsPage();
            case "remote-keys": return remoteKeysPage();
            case "remote-key": return remoteKeyPage();
            default: return null;
        }
    }

    /** v2.13: Cinemarchy's optional official YouTube embed for @dhh37 (off = YouTube app). */
    private boolean cinemarchyEmbedOn() {
        org.json.JSONObject c = LauncherConfig.readJson(this).optJSONObject("cinemarchy");
        return c != null && c.optBoolean("embedPlayer", false);
    }

    private AsciiMenu.Page mainPage() {
        return new AsciiMenu.Page(str(R.string.menu_title))
                .add(str(R.string.search_entry), "[mic]", r -> {
                    handler.post(this::startVoiceSearch);
                    return false;
                })
                .add(str(R.string.cinemarchy_hub), "›", r -> {
                    open(new Intent(this, CinemarchyActivity.class));
                    return false;
                })
                .add("Cinemarchy · play @dhh37 inside (YouTube embed)", str(cinemarchyEmbedOn() ? R.string.on : R.string.off), r -> {
                    boolean n = !cinemarchyEmbedOn();
                    CinemarchyActivity.setEmbedPlayer(this, n);
                    r.setValue(str(n ? R.string.on : R.string.off));
                    return true;
                })
                .add(str(R.string.grok_bot_hub), "›", r -> push(grokBotPage()))
                .add("Plugins (SpaceX TV · Grok Bot)", "›", r -> push(pluginsPage()))
                .add(str(R.string.ask_entry), "›", r -> {
                    open(new Intent(this, AskActivity.class));
                    return false;
                })
                .add(str(R.string.menu_launcher), "›", r -> push(launcherPage()))
                .add(str(R.string.menu_layout), "›", r -> push(layoutPage()))
                .add(str(R.string.set_tv), "›", r -> push(tvPage()))
                .add(str(R.string.web_apps), "›", r -> push(webAppsPage()))
                .add(str(R.string.ask_settings), "›", r -> push(assistantPage()))
                .add(str(R.string.menu_wallpaper), "›", r -> push(wallpaperPage()))
                .add(str(R.string.menu_reorder), null, r -> {
                    Toast.makeText(this, R.string.reorder_hint, Toast.LENGTH_LONG).show();
                    return false;
                })
                .add(str(R.string.menu_screensaver), "›", r -> push(screensaverPage()))
                .add(str(R.string.amb_title), "›", r -> push(ambilightPage()))
                .add(str(R.string.ai_settings), "›", r -> push(aiPage()))
                .add(str(R.string.set_widgets), "›", r -> push(widgetsPage()))
                .add(str(R.string.menu_link), "›", r -> {
                    OmarchyLink.openPairing();   // new code each time the page opens
                    LauncherConfig.update(this, j -> {
                        link(j).remove("pairingCode");   // v2 draft kept these in the public file
                        link(j).remove("token");
                        link(j).remove("pc");
                    });
                    builtWith = LauncherConfig.get(this);
                    return push(linkPage());
                })
                .add("WireGuard VPN", "›", r -> push(wireguardPage()))
                .add(str(R.string.settings), null, r -> {
                    open(new Intent(Settings.ACTION_SETTINGS));
                    return false;
                })
                .add(str(R.string.menu_about), null, r -> {
                    handler.post(this::showAbout);
                    return false;
                });
    }

    // ---- WireGuard VPN (official com.wireguard.android app; requires "Allow remote control apps") ----
    private static final String WG_PKG = "com.wireguard.android";
    private static final String WG_RECV = WG_PKG + ".model.TunnelManager$IntentReceiver";


    private AsciiMenu.Page pluginsPage() {
        LauncherConfig cfg = LauncherConfig.get(this);
        String on = str(R.string.on), off = str(R.string.off);
        AsciiMenu.Page p = new AsciiMenu.Page("Plugins · v2.13 Sandbox");
        p.hint("Declarative JSON plugins · MIT credited · zero ADB/shell access");
        String lSx = "SpaceX TV (com.sighmon.spacex-tv)";
        p.add(lSx, cfg.pluginSpacexTv ? on : off, r -> {
            boolean n = !cfg.pluginSpacexTv;
            applyLive("plugins", lSx, j -> {
                org.json.JSONObject pl = j.optJSONObject("plugins");
                if (pl == null) j.put("plugins", pl = new org.json.JSONObject());
                pl.put("spacexTv", n);
            });
            return true;
        });
        p.add("Open SpaceX TV launches & streams", "🚀 ›", r -> {
            handler.post(this::showSpacexMenu);
            return false;
        });
        String lGb = "Grok Bot Companion (TV) · experimental (@tcballard, MIT)";
        p.add(lGb, cfg.pluginGrokbot ? on : off, r -> {
            boolean n = !cfg.pluginGrokbot;
            applyLive("plugins", lGb, j -> {
                org.json.JSONObject pl = j.optJSONObject("plugins");
                if (pl == null) j.put("plugins", pl = new org.json.JSONObject());
                pl.put("grokbot", n);
            });
            return true;
        });
        p.add("Open Grok Bot Companion (TV)", "[ ◉ ◉ ] ›", r -> {
            handler.post(this::showGrokbotCompanionDialog);
            return false;
        });
        return p;
    }

    private void showSpacexMenu() {
        if (menu != null && menu.isShowing()) menu.dismiss();
        menu = new AsciiMenu(this, 680);
        AsciiMenu.Page p = new AsciiMenu.Page("SpaceX TV · com.sighmon.spacex-tv (@sighmon · MIT)");
        p.hint("Upcoming launches, Starship flight tests & X broadcasts");
        String[][] items = {
            {"🚀 SpaceX launches · next-launch countdown", "https://www.spacex.com/launches/"},
            {"🛰 SpaceX broadcasts on X", "https://x.com/SpaceX"},
            {"🎬 Starship (spacex.com)", "https://www.spacex.com/vehicles/starship/"},
            {"📺 Open Cinemarchy SpaceX / Sci-Fi Channel", "cinemarchy"}
        };
        for (String[] it : items) {
            final String title = it[0], url = it[1];
            p.add(title, "▶", r -> {
                if ("cinemarchy".equals(url)) {
                    open(new Intent(this, CinemarchyActivity.class));
                } else {
                    open(new Intent(this, WebAppActivity.class).putExtra("url", url).putExtra("name", "SpaceX TV"));
                }
                return false;
            });
        }
        menu.showBelow(btnMenu, p);
    }

    /** Request id of the question sent to the PC's Grok Bot companion bridge, until its answer/error. */
    private String pendingGrokbotId;

    /** Stores the line shown in the tile's bubble (plugins.grokbotMessage, 240 chars like the Link route). */
    private void setGrokbotBubble(String line) {
        String v = line.length() > 240 ? line.substring(0, 239) + "…" : line;
        LauncherConfig.update(this, j -> {
            org.json.JSONObject pl = j.optJSONObject("plugins");
            if (pl == null) j.put("plugins", pl = new org.json.JSONObject());
            pl.put("grokbotMessage", v);
        });
        builtWith = LauncherConfig.get(this);
        if (home != null) home.bind(builtWith, adapter);
    }

    private void onGrokbotCompanion() {
        builtWith = LauncherConfig.get(this);
        CompanionChatActivity.noteBubble(this, builtWith);
        if (companion != null) companion.bind(builtWith);
        if (home != null) home.bind(builtWith, adapter);
    }

    /**
     * v2.14: an answer that lands while the companion chat is closed (asked from the chat, which was left before
     * the PC replied) goes into that bot's history and lights the unread dot. Answers to the home dialog keep
     * their own dialog (onGrokbotReply).
     */
    private void keepCompanionReply(String type, org.json.JSONObject d) {
        if (!"grokbot_answer".equals(type) || CompanionChatActivity.visible) return;
        if (pendingGrokbotId != null && pendingGrokbotId.equals(d.optString("id"))) return;
        String mode = d.optString("mode");
        String text = d.optString("text", "");
        String body = "sent".equals(mode) ? "Sent ✓ The answer appears on the PC companion (its webhook replies there)."
                : "ipc".equals(mode) || text.isEmpty() ? "" : text;
        LauncherConfig cfg = LauncherConfig.get(this);
        CompanionChatActivity.deliver(this, cfg, d.optString("bot", ""), body);
        if (companion != null) companion.bind(cfg);
    }

    private void onGrokbotReply(String type, org.json.JSONObject d) {
        if (pendingGrokbotId == null || !pendingGrokbotId.equals(d.optString("id"))) return;
        pendingGrokbotId = null;
        String bot = d.optString("bot", "Grok Bot");
        String body;
        if ("grokbot_answer".equals(type)) {
            body = "ipc".equals(d.optString("mode")) || d.optString("text", "").isEmpty()
                    ? "Sent to the companion on the PC. The reply is in its desktop bubble (no reply channel in IPC-only mode)."
                    : d.optString("text", "");
            if (!"ipc".equals(d.optString("mode"))) setGrokbotBubble(body);
        } else {
            String code = d.optString("error", "failed");
            body = "not_connected".equals(code) ? "No PC connected over omarchy-link."
                    : "busy".equals(code) ? "One question every 3 seconds."
                    : d.optString("message", code);
        }
        if (!resumed) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle(("grokbot_answer".equals(type) ? bot : "Grok Bot companion") + " · experimental")
                .setMessage(body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private CompanionOverlay companion;

    private static org.json.JSONObject companionPlugins(org.json.JSONObject j) throws org.json.JSONException {
        org.json.JSONObject pl = j.optJSONObject("plugins");
        if (pl == null) j.put("plugins", pl = new org.json.JSONObject());
        return pl;
    }

    /** v2.13 floating companion: created once on the root, re-bound on resume and when the PC mirror changes. */
    private void bindCompanion() {
        if (root == null) return;
        if (companion == null) {
            companion = new CompanionOverlay(this);
            companion.setOnClickListener(v -> openCompanionChat(false));
            root.addView(companion);
        }
        companion.bind(LauncherConfig.get(this));
    }

    /** The voice key goes to the Grok Bot companion: chosen (the default) and the companion plugin is on. */
    private boolean voiceToCompanion() {
        LauncherConfig cfg = LauncherConfig.get(this);
        return "companion".equals(cfg.voiceKey) && cfg.pluginGrokbot;
    }

    private void openCompanionChat(boolean voice) {
        if (companion != null) companion.setArmed(false);
        open(new Intent(this, CompanionChatActivity.class).putExtra("voice", voice));
    }

    /** Long-press Menu arms the companion (it never takes D-pad focus by itself); OK opens the chat. */
    private boolean companionKey(KeyEvent e) {
        if (companion == null || companion.getVisibility() != View.VISIBLE) return false;
        int kc = e.getKeyCode();
        if (kc == KeyEvent.KEYCODE_MENU) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 1) {
                companion.setArmed(!companion.isArmed());
                swallowKeyUp = kc;
            }
            return e.getRepeatCount() > 0 || companion.isArmed();
        }
        if (!companion.isArmed()) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            swallowKeyUp = kc;
            openCompanionChat(false);
        } else if (kc == KeyEvent.KEYCODE_SEARCH || kc == KeyEvent.KEYCODE_VOICE_ASSIST) {
            swallowKeyUp = kc;
            openCompanionChat(true);
        } else {
            companion.setArmed(false);
            swallowKeyUp = kc;
        }
        return true;
    }

    private void showGrokbotCompanionDialog() {
        LauncherConfig cfg = LauncherConfig.get(this);
        android.widget.EditText e = new android.widget.EditText(this);
        e.setSingleLine(true);
        // Bridge = omarchy-link pushed the companion's bot and a PC is connected: the question goes to the PC.
        final boolean bridge = cfg.grokbotBotName != null && !cfg.grokbotBotName.isEmpty() && OmarchyLink.hasPc();
        String who = bridge ? cfg.grokbotBotName : "Grok Bot";
        e.setHint(bridge ? "Ask " + who + " on the PC companion…" : "Ask Grok Bot…");
        String state = bridge ? "Mirroring " + who + " from @tcballard's Grok Bot Desktop Companion on the PC ("
                + ("relay".equals(cfg.grokbotBotMode) ? "replies come back here" : "replies stay in the desktop bubble") + ")."
                : "Not linked to the desktop companion: questions open the Ask screen. Install omarchy-link and "
                + "@tcballard's companion on the PC to mirror the bot here.";
        String msg = (cfg.grokbotMessage != null && !cfg.grokbotMessage.isEmpty() ? "Last: " + cfg.grokbotMessage + "\n\n" : "")
                + state + "\n\nBuilt for @tcballard's Grok Bot Desktop Companion v0.1.0 (MIT)"
                + (bridge && cfg.grokbotBotVersion != null && !cfg.grokbotBotVersion.isEmpty()
                ? " · companion v" + cfg.grokbotBotVersion : "") + " · built for v0.1.0.";
        new android.app.AlertDialog.Builder(this)
                .setTitle("Grok Bot Companion (TV)")
                .setMessage(msg)
                .setView(e)
                .setPositiveButton(bridge ? "Ask " + who : "Ask Grok Bot", (d, w) -> {
                    String q = e.getText().toString().trim();
                    if (q.isEmpty()) return;
                    if (q.length() > 240) q = q.substring(0, 240);
                    if (bridge) {
                        pendingGrokbotId = java.util.UUID.randomUUID().toString();
                        setGrokbotBubble("… " + q);
                        OmarchyLink.grokbotAsk(pendingGrokbotId, q, null);
                    } else {
                        setGrokbotBubble(q);
                        open(new Intent(this, AskActivity.class).putExtra("cmd", q));
                    }
                })
                .setNeutralButton("Open Grok WebApp", (d, w) -> open(new Intent(this, WebAppActivity.class)
                        .putExtra("url", "https://grok.com").putExtra("name", "Grok Bot")))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private AsciiMenu.Page wireguardPage() {
        String tun = getSharedPreferences("omarchytv", MODE_PRIVATE).getString("wg_tunnel", "wg0");
        boolean installed = false;
        try { getPackageManager().getPackageInfo(WG_PKG, 0); installed = true; } catch (Exception ignored) {}
        final boolean inst = installed;
        AsciiMenu.Page p = new AsciiMenu.Page("WireGuard VPN");
        p.hint("Official WireGuard app · enable Advanced → Allow remote control apps");
        p.add("Tunnel name", tun, r -> { handler.post(this::wgTunnelDialog); return false; });
        p.add("Connect (" + tun + " up)", inst ? "▶" : "install app", r -> { wgBroadcast(tun, true); return false; });
        p.add("Disconnect (" + tun + " down)", inst ? "■" : "install app", r -> { wgBroadcast(tun, false); return false; });
        p.add("Open WireGuard app", inst ? "›" : "not installed", r -> {
            Intent i = getPackageManager().getLaunchIntentForPackage(WG_PKG);
            if (i != null) open(i);
            else Toast.makeText(this, "Install WireGuard from Play Store / F-Droid first", Toast.LENGTH_LONG).show();
            return false;
        });
        return p;
    }

    private void wgBroadcast(String tun, boolean up) {
        Intent i = new Intent(WG_PKG + (up ? ".action.SET_TUNNEL_UP" : ".action.SET_TUNNEL_DOWN"));
        i.setClassName(WG_PKG, WG_RECV);
        i.putExtra("tunnel", tun);
        sendBroadcast(i);
        Toast.makeText(this, "WireGuard: " + tun + (up ? " up" : " down") + " requested", Toast.LENGTH_SHORT).show();
    }

    private void wgTunnelDialog() {
        android.content.SharedPreferences sp = getSharedPreferences("omarchytv", MODE_PRIVATE);
        android.widget.EditText e = new android.widget.EditText(this);
        e.setSingleLine(true);
        e.setText(sp.getString("wg_tunnel", "wg0"));
        new android.app.AlertDialog.Builder(this).setTitle("WireGuard tunnel name").setView(e)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String v = e.getText().toString().trim().replaceAll("[^a-zA-Z0-9_=+.-]", "");
                    if (!v.isEmpty() && v.length() <= 15) sp.edit().putString("wg_tunnel", v).apply();
                    showMenu("wireguard", null);
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private boolean push(AsciiMenu.Page p) {
        menu.push(p);
        return true;
    }

    static String promptText(LauncherConfig c) {
        return c.promptUser + "@" + c.promptHost + ":~$";
    }

    /** Prompt name / host name with the TV keyboard; sanitised to [a-z0-9._-], max 16 (empty = default). */
    private void promptDialog(boolean host) {
        LauncherConfig cfg = LauncherConfig.get(this);
        android.widget.EditText e = new android.widget.EditText(this);
        e.setSingleLine(true);
        e.setText(host ? cfg.promptHost : cfg.promptUser);
        e.setSelection(e.getText().length());
        e.setHint(host ? "omarchytv" : "user");
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(16)});
        String row = str(host ? R.string.set_prompt_host : R.string.set_prompt_name);
        new android.app.AlertDialog.Builder(this)
                .setTitle(row)
                .setView(e)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String v = LauncherConfig.promptPart(e.getText().toString(), host ? "omarchytv" : "user");
                    applyLive("launcher", row, j -> {
                        org.json.JSONObject p = j.optJSONObject("prompt");
                        if (p == null) j.put("prompt", p = new org.json.JSONObject());
                        p.put(host ? "host" : "user", v);
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private AsciiMenu.Page launcherPage() {
        LauncherConfig cfg = LauncherConfig.get(this);
        String[] langs = {"auto", "en", "it", "de"};
        String[] sizes = {"small", "medium", "large"};
        String[] logos = {"omarchy-tv", "omarchy", "cinemarchy", "original"};
        AsciiMenu.Page p = new AsciiMenu.Page(str(R.string.menu_launcher));
        String lLang = str(R.string.set_language), lSize = str(R.string.set_ui_size), lLogo = str(R.string.set_logo),
                lAcc = str(R.string.set_accent);
        p.add(lLang, langLabel(cfg.language), r -> {
            String n = next(langs, cfg.language);
            applyLive("launcher", lLang, j -> j.put("language", n));
            return true;
        });
        p.add(lSize, sizeLabel(cfg.uiSize), r -> {
            String n = next(sizes, cfg.uiSize);
            applyLive("launcher", lSize, j -> j.put("uiSize", n));
            return true;
        });
        p.add(lLogo, logoLabel(cfg.logo), r -> {
            String n = next(logos, cfg.logo);
            applyLive("launcher", lLogo, j -> j.put("logo", n));
            return true;
        });
        // Theme = an Omarchy stock theme (bundled palettes); the PC's Omarchy Link sync overrides it with the live one.
        String[] themes = Theme.names(this);
        p.add(lAcc, Theme.cur().name, r -> {
            String n = next(themes, Theme.cur().name);
            applyLive("launcher", lAcc, j -> {
                org.json.JSONObject t = new org.json.JSONObject();
                t.put("name", n);
                t.put("source", "tv");
                j.put("theme", t);
                j.put("wallpaper_bg", 0);
            });
            return true;
        });
        // v2.10: the old per-pane toggles (clock, ai, sysinfo, ...) drove the removed pane layer and did nothing on the
        // home; every home panel is switched in Widgets (workspace 5).
        p.add(str(R.string.set_widgets), "›", r -> push(widgetsPage()));
        // Only with a remote that has a profile in assets/remotes.json: nothing to set up on other TVs.
        if (!connectedRemotes().isEmpty()) p.add(str(R.string.menu_remote_keys), "\u203a", r -> push(remoteKeysPage()));
        p.add(str(R.string.set_prompt_name), cfg.promptUser, r -> {
            handler.post(() -> promptDialog(false));
            return false;
        });
        p.add(str(R.string.set_prompt_host), cfg.promptHost, r -> {
            handler.post(() -> promptDialog(true));
            return false;
        });
        // "Originale": stock Sony home + screensaver and back, through the TV's own ADB (opt-in, see Originale).
        p.add(str(R.string.set_originale), str(Originale.isOriginal(this) ? R.string.on : R.string.off), r -> {
            boolean back = Originale.isOriginal(this);
            Toast.makeText(this, R.string.originale_wait, Toast.LENGTH_LONG).show();
            new Thread(() -> {
                String msg;
                try {
                    msg = back ? str(Originale.toOmarchy(this) ? R.string.originale_omarchy : R.string.originale_reverted)
                            : str(R.string.originale_on);
                } catch (Throwable e) {
                    msg = str(R.string.originale_no_adb);
                    handler.post(() -> Originale.fallback(this));
                }
                String m = msg;
                handler.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
            }, "originale").start();
            return false;
        });
        // Google TV keeps Home for its own launcher: this accessibility service opens OmarchyTV over it (HomeOverride).
        p.add(str(R.string.set_home_override), str(HomeOverride.enabled() ? R.string.on : R.string.off), r -> {
            open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return false;
        });
        p.add(str(R.string.set_hidden), String.valueOf(cfg.hidden.size()), r -> push(hiddenPage()));
        p.add(str(R.string.set_workspaces), "›", r -> push(workspacesPage()));
        p.add(str(R.string.set_reset_order), null, r -> {
            LauncherConfig.update(this, j -> {
                j.remove("order");
                j.remove("dock");
            });
            Toast.makeText(this, R.string.order_reset, Toast.LENGTH_SHORT).show();
            builtWith = LauncherConfig.get(this);
            reloadApps();
            return false;
        });
        return p;
    }

    /** "> home": OmarchyTV is not the default home any more (Omarchy menu card, nothing changes by itself). */
    private void homePrompt() {
        if (!resumed || (menu != null && menu.isShowing())) return;
        AsciiMenu.Page p = new AsciiMenu.Page("home  ·  " + str(R.string.home_check_title));
        p.hint(str(R.string.home_check_hint));
        p.add(str(R.string.home_check_restore), null, r -> {
            Intent i = new Intent(Settings.ACTION_HOME_SETTINGS);
            if (i.resolveActivity(getPackageManager()) != null) open(i);
            else {
                // Android TV often has no "Default home" screen: the HOME chooser ("Always"), or the kit's script.
                Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
                open(Intent.createChooser(home, str(R.string.home_check_title)));
                Toast.makeText(this, R.string.home_check_adb, Toast.LENGTH_LONG).show();
            }
            return false;
        });
        // Not now / Don't ask again: back to whatever home the TV has set (nothing changed).
        p.add(str(R.string.home_check_later), null, r -> {
            handler.post(() -> moveTaskToBack(true));
            return false;
        });
        p.add(str(R.string.home_check_never), null, r -> {
            HomeCheck.never(this);
            handler.post(() -> moveTaskToBack(true));
            return false;
        });
        menu = new AsciiMenu(this, 900);   // v2.10: wide enough that the hint line is not cut with "…"
        menu.showBelow(btnMenu, p);
    }

    /** "Medium · 5 per row". */
    private String appSizeLabel(String size) {
        int i = java.util.Arrays.asList(LauncherConfig.APP_SIZES).indexOf(size);
        int[] names = {R.string.size_large, R.string.size_medium, R.string.size_small, R.string.size_compact};
        return getString(names[Math.max(0, i)]) + "  ·  " + getString(R.string.per_row, Math.max(0, i) + 4);
    }

    /** Hyprland-style geometry, each a few remote-friendly steps; OK goes to the next step (wraps). */
    private AsciiMenu.Page layoutPage() {
        LauncherConfig cfg = LauncherConfig.get(this);
        AsciiMenu.Page p = new AsciiMenu.Page(str(R.string.menu_layout) + "  ·  " + str(R.string.step_hint));
        Float[] scales = {0.85f, 1.0f, 1.15f, 1.3f};
        Float[] tiles = {0.85f, 0.92f, 1.0f, 1.1f};   // 1.1 is the widest that fits 4 columns with a gutter
        Integer[] gaps = {0, 5, 8, 12, 16};
        Integer[] borders = {1, 2, 3, 4};
        String lText = str(R.string.set_text), lTile = str(R.string.set_tiles), lGap = str(R.string.set_gaps),
                lBorder = str(R.string.set_border), lTrans = str(R.string.set_transparency);
        Integer[] trans = new Integer[Surfaces.STEPS.length];
        for (int i = 0; i < trans.length; i++) trans[i] = Surfaces.STEPS[i];
        p.add(lText, steps(scales, cfg.fontScale), r -> {
            float n = nextF(scales, cfg.fontScale);
            applyLive("layout", lText, j -> geometry(j).put("fontScale", n));
            return true;
        });
        String lSize = str(R.string.set_app_size);
        p.add(lSize, appSizeLabel(cfg.appSize), r -> {
            String n = next(LauncherConfig.APP_SIZES, cfg.appSize);
            applyLive("layout", lSize, j -> geometry(j).put("appSize", n));
            return true;
        });
        p.add(lTile, steps(tiles, cfg.tileScale), r -> {
            float n = nextF(tiles, cfg.tileScale);
            applyLive("layout", lTile, j -> geometry(j).put("tileScale", n));
            return true;
        });
        p.add(lGap, stepsI(gaps, cfg.gapDp), r -> {
            int n = next(gaps, cfg.gapDp);
            applyLive("layout", lGap, j -> geometry(j).put("gaps", n));
            return true;
        });
        p.add(lBorder, stepsI(borders, cfg.borderDp), r -> {
            int n = next(borders, cfg.borderDp);
            applyLive("layout", lBorder, j -> geometry(j).put("border", n));
            return true;
        });
        p.add(lTrans, stepsI(trans, cfg.transparency) + "%", r -> {
            int n = next(trans, cfg.transparency);
            applyLive("layout", lTrans, j -> geometry(j).put("transparency", n));
            return true;
        });
        String lDim = str(R.string.set_wallpaper_dim);
        p.add(lDim, dimLabel(cfg.wallpaperDim), r -> {
            String n = next(Surfaces.DIM, cfg.wallpaperDim);
            applyLive("layout", lDim, j -> geometry(j).put("wallpaperDim", n));
            return true;
        });
        String lBurn = "OLED anti-burn-in drift";
        p.add(lBurn, str(cfg.antiBurnIn ? R.string.on : R.string.off), r -> {
            boolean n = !cfg.antiBurnIn;
            applyLive("layout", lBurn, j -> j.put("anti_burn_in", n));
            return true;
        });
        String lAmb = "Ambilight / Bias lighting";
        p.add(lAmb, cfg.ambilightMode, r -> {
            String n = AmbilightController.nextMode(cfg.ambilightMode);
            applyLive("layout", lAmb, j -> j.put("ambilight_mode", n));
            return true;
        });
        return p;
    }

    // ---- v2.13 Ambilight & Lights (AmbilightController + lights.LightsHub) ------------------------

    private String ambModeLabel(String m) {
        switch (m) {
            case "theme": return str(R.string.amb_mode_theme);
            case "hybrid": return str(R.string.amb_mode_hybrid);
            case "video": return str(R.string.amb_mode_video);
            case "cinema": return str(R.string.amb_mode_cinema);
            default: return str(R.string.off);
        }
    }

    /** Runs a blocking probe off the main thread and shows the result in the row (never a secret). */
    private void probe(AsciiMenu.Row row, java.util.concurrent.Callable<String> job) {
        row.setValue("…");
        com.omarchytv.home.lights.Lights.run(() -> {
            String res;
            try {
                res = job.call();
            } catch (Throwable t) {
                res = "error";
            }
            String out = res;
            handler.post(() -> row.setValue(out));
        });
    }

    /** One-line text dialog; {@code secret} hides the input and never pre-fills a stored secret. */
    private void textDialog(String title, String current, String hint, boolean secret, java.util.function.Consumer<String> done) {
        android.widget.EditText e = new android.widget.EditText(this);
        e.setSingleLine(true);
        if (!secret) {
            e.setText(current);
            e.setSelection(e.getText().length());
        }
        e.setHint(hint);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | (secret
                ? android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD : android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setView(e)
                .setPositiveButton(android.R.string.ok, (d, w) -> done.accept(e.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private AsciiMenu.Page ambilightPage() {
        LauncherConfig cfg = LauncherConfig.get(this);
        boolean on = !"off".equals(cfg.ambilightMode);
        AsciiMenu.Page p = new AsciiMenu.Page(str(R.string.amb_title));
        String lOn = str(R.string.amb_enabled), lMode = str(R.string.amb_mode), lBri = str(R.string.amb_brightness),
                lIp = str(R.string.amb_tv_ip), lTest = str(R.string.amb_test), lAuto = str(R.string.amb_autodetect);
        p.add(lOn, str(on ? R.string.on : R.string.off), r -> {
            String n = on ? "off" : "hybrid";
            applyLive("ambilight", lOn, j -> j.put("ambilight_mode", n));
            return true;
        });
        if (on) {
            p.add(lMode, ambModeLabel(cfg.ambilightMode), r -> {
                String n = AmbilightController.nextEnabledMode(cfg.ambilightMode);
                applyLive("ambilight", lMode, j -> j.put("ambilight_mode", n));
                return true;
            });
            Integer[] bris = {10, 25, 40, 55, 70, 85, 100};
            p.add(lBri, cfg.ambilightBrightness + "%", r -> {
                int cur = cfg.ambilightBrightness;
                int n = bris[0];
                for (Integer b : bris) if (b > cur) { n = b; break; }
                int nn = n;
                applyLive("ambilight", lBri, j -> j.put("ambilight_brightness", nn));
                return true;
            });
        }
        p.add(lIp, cfg.ambilightTvIp, r -> {
            handler.post(() -> textDialog(lIp, cfg.ambilightTvIp, "127.0.0.1", false, v -> {
                String ip = v.isEmpty() ? "127.0.0.1" : v;
                if (!ip.matches("[0-9A-Za-z.:-]{1,64}")) {
                    Toast.makeText(this, R.string.amb_bad_ip, Toast.LENGTH_SHORT).show();
                    return;
                }
                applyLive("ambilight", lIp, j -> j.put("ambilight_tv_ip", ip));
            }));
            return false;
        });
        p.add(lAuto, "›", r -> {
            String cur = cfg.ambilightTvIp;
            r.setValue("…");
            com.omarchytv.home.lights.Lights.run(() -> {
                String found = AmbilightController.autoDetect(cur);
                handler.post(() -> {
                    if (found == null) r.setValue(str(R.string.amb_not_found));
                    else if (found.equals(cur)) r.setValue("ok · " + found);
                    else applyLive("ambilight", lIp, j -> j.put("ambilight_tv_ip", found));
                });
            });
            return true;
        });
        p.add(lTest, "›", r -> {
            String ip = cfg.ambilightTvIp;
            probe(r, () -> AmbilightController.testTv(ip));
            return true;
        });
        p.hint(str(R.string.amb_hint_follow));
        String hub = com.omarchytv.home.lights.LightsStore.hub(this);
        p.add(str(R.string.lights_hub), hubLabel(hub), r -> push(lightsPage()));
        return p;
    }

    private String hubLabel(String hub) {
        if ("hue".equals(hub)) return "Philips Hue";
        if ("homeassistant".equals(hub)) return "Home Assistant";
        return str(R.string.lights_none);
    }

    private AsciiMenu.Page lightsPage() {
        String hub = com.omarchytv.home.lights.LightsStore.hub(this);
        AsciiMenu.Page p = new AsciiMenu.Page(str(R.string.lights_hub));
        String lType = str(R.string.lights_type);
        p.add(lType, hubLabel(hub), r -> {
            String n = next(new String[]{"none", "hue", "homeassistant"}, hub);
            com.omarchytv.home.lights.LightsStore.setHub(this, n);
            reopenAt("lights", lType);
            return true;
        });
        if ("hue".equals(hub)) addHueRows(p);
        else if ("homeassistant".equals(hub)) addHaRows(p);
        else p.hint(str(R.string.lights_hint_none));
        if (!"none".equals(hub)) {
            p.add(str(R.string.lights_push), "›", r -> {
                AmbilightController.syncTheme(this);
                com.omarchytv.home.lights.LightsHub h = com.omarchytv.home.lights.Lights.current(this);
                Theme t = Theme.cur();
                LauncherConfig c = LauncherConfig.get(this);
                com.omarchytv.home.lights.Lights.push(this, t.accent, t.accent2, t.bg, c.ambilightBrightness, false);
                r.setValue(h != null && h.configured() ? "✓" : str(R.string.lights_incomplete));
                return true;
            });
            p.add(str(R.string.lights_test), "›", r -> {
                com.omarchytv.home.lights.LightsHub h = com.omarchytv.home.lights.Lights.current(this);
                probe(r, () -> h == null ? "none" : h.test());
                return true;
            });
            // Destructive: separated at the bottom and always confirmed.
            p.hint("──");
            p.add(str(R.string.lights_forget), null, r -> {
                handler.post(() -> new android.app.AlertDialog.Builder(this)
                        .setTitle(R.string.lights_forget)
                        .setMessage(R.string.lights_forget_confirm)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.lights_forget, (d, w) -> {
                            com.omarchytv.home.lights.LightsStore.forget(this);
                            reopenAt("lights", null);
                        })
                        .show());
                return false;
            });
        }
        return p;
    }

    private void reopenAt(String page, String row) {
        if (menu != null) menu.dismiss();
        handler.post(() -> showMenu(page, row));
    }

    private void addHueRows(AsciiMenu.Page p) {
        android.content.Context c = this;
        String ip = com.omarchytv.home.lights.LightsStore.get(c, "hue_ip");
        boolean paired = com.omarchytv.home.lights.LightsStore.hasSecret(c, "hue_user");
        String groupName = com.omarchytv.home.lights.LightsStore.get(c, "hue_group_name");
        String lDisc = str(R.string.hue_discover), lIp = str(R.string.hue_ip), lPair = str(R.string.hue_pair), lArea = str(R.string.hue_area);
        p.add(lDisc, "›", r -> {
            r.setValue("…");
            com.omarchytv.home.lights.Lights.run(() -> {
                java.util.List<String> ips = com.omarchytv.home.lights.HueBridge.discover();
                handler.post(() -> {
                    if (ips.isEmpty()) {
                        r.setValue(str(R.string.amb_not_found));
                        return;
                    }
                    String[] arr = ips.toArray(new String[0]);
                    new android.app.AlertDialog.Builder(this).setTitle(lDisc).setItems(arr, (d, w) -> {
                        com.omarchytv.home.lights.LightsStore.put(c, "hue_ip", arr[w]);
                        reopenAt("lights", lPair);
                    }).show();
                    r.setValue(ips.size() + " found");
                });
            });
            return true;
        });
        p.add(lIp, ip.isEmpty() ? "—" : ip, r -> {
            handler.post(() -> textDialog(lIp, ip, "192.168.1.20", false, v -> {
                if (!v.matches("[0-9A-Za-z.:-]{1,64}")) {
                    Toast.makeText(this, R.string.amb_bad_ip, Toast.LENGTH_SHORT).show();
                    return;
                }
                com.omarchytv.home.lights.LightsStore.put(c, "hue_ip", v);
                reopenAt("lights", lPair);
            }));
            return false;
        });
        if (!ip.isEmpty()) {
            p.add(lPair, paired ? str(R.string.hue_paired) : str(R.string.hue_press_button), r -> {
                r.setValue("…");
                com.omarchytv.home.lights.Lights.run(() -> {
                    com.omarchytv.home.lights.HueBridge.PairResult res = com.omarchytv.home.lights.HueBridge.pair(ip);
                    if (res.username != null) com.omarchytv.home.lights.LightsStore.put(c, "hue_user", res.username);
                    handler.post(() -> {
                        if (res.username != null) reopenAt("lights", lArea);
                        else r.setValue(res.error);
                    });
                });
                return true;
            });
            p.hint(str(R.string.hue_pair_hint));
        }
        if (paired) {
            p.add(lArea, groupName.isEmpty() ? "—" : groupName, r -> {
                r.setValue("…");
                com.omarchytv.home.lights.HueBridge b = new com.omarchytv.home.lights.HueBridge(ip,
                        com.omarchytv.home.lights.LightsStore.get(c, "hue_user"), "");
                com.omarchytv.home.lights.Lights.run(() -> {
                    java.util.List<com.omarchytv.home.lights.HueBridge.Group> gs;
                    try {
                        gs = b.groups();
                    } catch (Exception e) {
                        gs = new java.util.ArrayList<>();
                    }
                    java.util.List<com.omarchytv.home.lights.HueBridge.Group> groups = gs;
                    handler.post(() -> {
                        if (groups.isEmpty()) {
                            r.setValue(str(R.string.amb_not_found));
                            return;
                        }
                        String[] labels = new String[groups.size()];
                        for (int i = 0; i < labels.length; i++) labels[i] = groups.get(i).label();
                        new android.app.AlertDialog.Builder(this).setTitle(lArea).setItems(labels, (d, w) -> {
                            com.omarchytv.home.lights.LightsStore.put(c, "hue_group", groups.get(w).id);
                            com.omarchytv.home.lights.LightsStore.put(c, "hue_group_name", groups.get(w).name);
                            reopenAt("lights", lArea);
                        }).show();
                        r.setValue(groupName.isEmpty() ? "—" : groupName);
                    });
                });
                return true;
            });
        }
    }

    private void addHaRows(AsciiMenu.Page p) {
        android.content.Context c = this;
        String url = com.omarchytv.home.lights.LightsStore.get(c, "ha_url");
        String ents = com.omarchytv.home.lights.LightsStore.get(c, "ha_entities");
        boolean tok = com.omarchytv.home.lights.LightsStore.hasSecret(c, "ha_token");
        String lUrl = str(R.string.ha_url), lTok = str(R.string.ha_token), lEnt = str(R.string.ha_entities);
        p.add(lUrl, url.isEmpty() ? "—" : url, r -> {
            handler.post(() -> textDialog(lUrl, url, "http://homeassistant.local:8123", false, v -> {
                if (!v.matches("https?://[^\\s]{1,200}")) {
                    Toast.makeText(this, R.string.amb_bad_ip, Toast.LENGTH_SHORT).show();
                    return;
                }
                com.omarchytv.home.lights.LightsStore.put(c, "ha_url", v);
                reopenAt("lights", lTok);
            }));
            return false;
        });
        p.add(lTok, tok ? "••••••" : "—", r -> {
            handler.post(() -> textDialog(lTok, "", "long-lived access token", true, v -> {
                if (v.isEmpty()) return;
                com.omarchytv.home.lights.LightsStore.put(c, "ha_token", v);
                reopenAt("lights", lEnt);
            }));
            return false;
        });
        p.add(lEnt, ents.isEmpty() ? "—" : ents, r -> {
            handler.post(() -> textDialog(lEnt, ents, "light.living_room, light.tv_strip", false, v -> {
                com.omarchytv.home.lights.LightsStore.put(c, "ha_entities", v);
                reopenAt("lights", lEnt);
            }));
            return false;
        });
    }

    /** "Also use as system screensaver": enable/disable our DreamService components (own components only). */
    private void applyDreams(boolean on) {
        PackageManager pm = getPackageManager();
        for (Class<?> c : new Class<?>[]{OmarchyDream.class, ClockDream.class}) {
            // v2.10: OmarchyDream stays enabled (manifest default) so DreamManager always finds it; ClockDream follows the setting.
            int state = on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : c == OmarchyDream.class ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            android.content.ComponentName cn = new android.content.ComponentName(this, c);
            try {
                if (pm.getComponentEnabledSetting(cn) != state) pm.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /**
     * Startup: keep the dreams enabled for anyone who already picked one as the TV screensaver (older installs
     * set screensaver_components over adb), otherwise follow the setting (default off). Runs off the UI thread.
     */
    private void syncDreams() {
        final boolean wanted = LauncherConfig.get(this).screensaverSystem;
        final Context app = getApplicationContext();
        new Thread(() -> {
            boolean on = wanted;
            if (!on) {
                try {
                    String cur = Settings.Secure.getString(app.getContentResolver(), "screensaver_components");
                    on = cur != null && cur.contains(app.getPackageName() + "/");
                } catch (RuntimeException ignored) {
                }
            }
            final boolean fOn = on;
            handler.post(() -> applyDreams(fOn));
        }, "dreams").start();
    }

    private String dimLabel(String v) {
        switch (Surfaces.dimLevel(v)) {
            case 1: return str(R.string.dim_light);
            case 2: return str(R.string.dim_medium);
            default: return str(R.string.dim_off);
        }
    }

    private static org.json.JSONObject geometry(org.json.JSONObject j) throws org.json.JSONException {
        org.json.JSONObject g = j.optJSONObject("geometry");
        if (g == null) {
            g = new org.json.JSONObject();
            j.put("geometry", g);
        }
        return g;
    }

    private static float nextF(Float[] values, float current) {
        int best = 0;
        for (int i = 0; i < values.length; i++) if (Math.abs(values[i] - current) < Math.abs(values[best] - current)) best = i;
        return values[(best + 1) % values.length];
    }

    /** "[■■□□] 100%" style step meter. */
    private static String steps(Float[] values, float current) {
        int best = 0;
        for (int i = 0; i < values.length; i++) if (Math.abs(values[i] - current) < Math.abs(values[best] - current)) best = i;
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) b.append(i <= best ? '■' : '□');
        return b.append("] ").append(Math.round(current * 100)).append('%').toString();
    }

    private static String stepsI(Integer[] values, int current) {
        StringBuilder b = new StringBuilder("[");
        for (Integer v : values) b.append(v <= current ? '■' : '□');
        return b.append("] ").append(current).append("dp").toString();
    }

    /**
     * "workspaces": numbers or names in the bar, then the bar's workspaces by position and the hidden ones;
     * OK on one opens move / hide / rename.
     */
    private AsciiMenu.Page workspacesPage() {
        LauncherConfig cfg = LauncherConfig.get(this);
        AsciiMenu.Page p = new AsciiMenu.Page(str(R.string.set_workspaces));
        String lLabels = str(R.string.ws_labels);
        p.add(lLabels, str(cfg.workspaceNamesInBar ? R.string.ws_labels_names : R.string.ws_labels_numbers), r -> {
            boolean names = !cfg.workspaceNamesInBar;
            applyLive("workspaces", lLabels, j -> workspacesJson(j).put("labels", names ? "names" : "numbers"));
            return true;
        });
        for (int n : cfg.workspaceOrder) {
            p.add(cfg.workspaceName(n), String.valueOf(cfg.positionOf(n)), r -> push(workspacePage(n)));
        }
        for (int n = 1; n <= LauncherConfig.WORKSPACE_IDS.length; n++) {
            final int ws = n;
            if (!cfg.workspaceShown(ws)) p.add(cfg.workspaceName(ws), str(R.string.off), r -> push(workspacePage(ws)));
        }
        p.add(str(R.string.ws_reset), null, r -> {
            applyLive("workspaces", str(R.string.ws_reset), j -> j.remove("workspaces"));
            return false;
        });
        p.hint(str(R.string.ws_hint));
        return p;
    }

    /** One workspace: move it left / right in the bar, hide it or show it again (at the end), rename it. Home can't be hidden. */
    private AsciiMenu.Page workspacePage(int n) {
        LauncherConfig cfg = LauncherConfig.get(this);
        List<Integer> order = new ArrayList<>(cfg.workspaceOrder);
        int pos = order.indexOf(n);
        AsciiMenu.Page p = new AsciiMenu.Page(cfg.workspaceName(n));
        if (pos > 0) p.add(str(R.string.ws_move_left), null, r -> saveWorkspaceOrder(n, order, () -> Collections.swap(order, pos, pos - 1)));
        if (pos >= 0 && pos < order.size() - 1) p.add(str(R.string.ws_move_right), null, r -> saveWorkspaceOrder(n, order, () -> Collections.swap(order, pos, pos + 1)));
        if (n != 1 && pos >= 0) p.add(str(R.string.ws_hide), null, r -> saveWorkspaceOrder(n, order, () -> order.remove(pos)));
        if (pos < 0) p.add(str(R.string.ws_show), null, r -> saveWorkspaceOrder(n, order, () -> order.add(n)));
        p.add(str(R.string.ws_rename), null, r -> {
            handler.post(() -> renameWorkspaceDialog(n));
            return false;
        });
        return p;
    }

    /** Applies {@code change} to {@code order}, saves it as "workspaces": {"order"} and reopens the list on workspace {@code n}. */
    private boolean saveWorkspaceOrder(int n, List<Integer> order, Runnable change) {
        change.run();
        org.json.JSONArray names = new org.json.JSONArray();
        for (int ws : order) names.put(LauncherConfig.workspaceId(ws));
        applyLive("workspaces", LauncherConfig.get(this).workspaceName(n), j -> workspacesJson(j).put("order", names));
        return false;
    }

    /** "workspaces": {"names"}: an empty name (or the id itself) goes back to the id. */
    private void renameWorkspaceDialog(int n) {
        String id = LauncherConfig.workspaceId(n);
        android.widget.EditText e = new android.widget.EditText(this);
        e.setSingleLine(true);
        e.setText(LauncherConfig.get(this).workspaceName(n));
        e.setSelection(e.getText().length());
        e.setHint(id);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(12)});
        new android.app.AlertDialog.Builder(this)
                .setTitle(str(R.string.ws_rename) + "  ·  " + id)
                .setView(e)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String v = LauncherConfig.workspaceNamePart(e.getText().toString());
                    boolean reset = v.isEmpty() || v.equals(id);
                    applyLive("workspaces", reset ? id : v, j -> {
                        org.json.JSONObject names = workspacesJson(j).optJSONObject("names");
                        if (names == null) workspacesJson(j).put("names", names = new org.json.JSONObject());
                        if (reset) names.remove(id);
                        else names.put(id, v);
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** omarchytv.json "workspaces", created when missing. */
    private static org.json.JSONObject workspacesJson(org.json.JSONObject j) throws org.json.JSONException {
        org.json.JSONObject w = j.optJSONObject("workspaces");
        if (w == null) j.put("workspaces", w = new org.json.JSONObject());
        return w;
    }

    /** v2.10 "Disponi riquadri": pick a home panel, move it with the D-pad (OK saves, Back cancels), or reset. */
    private AsciiMenu.Page arrangePage() {
        AsciiMenu.Page p = new AsciiMenu.Page(str(R.string.v210_arrange));
        int[] names = {R.string.v210_w_term, R.string.v210_w_weather, R.string.v210_w_pc, R.string.v210_w_ai, R.string.v210_w_deploys};
        for (int i = 0; i < HomeV29.PANEL_IDS.length; i++) {
            final String id = HomeV29.PANEL_IDS[i];
            View v = home.panelView(id);
            boolean shown = v != null && v.getVisibility() == View.VISIBLE;
            p.add(str(names[i]), shown ? "›" : str(R.string.off), r -> {
                if (!shown) return true;
                if (menu != null) menu.dismiss();
                setWorkspace(1);
                home.post(() -> {
                    if (home.editPanel(id)) Toast.makeText(this, R.string.v210_arrange_hint, Toast.LENGTH_LONG).show();
                });
                return false;
            });
        }
        p.add(str(R.string.v210_arrange_reset), null, r -> {
            if (menu != null) menu.dismiss();
            sysSet(j -> j.remove("panelPos"));
            return false;
        });
        p.hint(str(R.string.v210_arrange_hint));
        return p;
    }

    private android.view.TextureView introView;
    private android.media.MediaPlayer introPlayer;
    private String lastIntroKey;

    private void stopWallpaperIntro() {
        if (introPlayer != null) {
            try { introPlayer.stop(); } catch (Throwable ignored) {}
            try { introPlayer.release(); } catch (Throwable ignored) {}
            introPlayer = null;
        }
        if (introView != null && root != null) {
            // Null first: removeView destroys the TextureView, whose callback calls this method again.
            View v = introView;
            introView = null;
            root.removeView(v);
        }
    }

    private void playWallpaperIntro(LauncherConfig cfg) {
        if (!cfg.wallpaperIntro || root == null) {
            stopWallpaperIntro();
            return;
        }
        String mp4 = WallpaperView.introAssetFor(this, cfg.wallpaper, cfg.wallpaperBg);
        if (mp4 == null) {
            stopWallpaperIntro();
            return;
        }
        String key = Theme.cur().name + ":" + mp4;
        if (key.equals(lastIntroKey)) return;
        lastIntroKey = key;
        stopWallpaperIntro();
        java.io.File tmp = new java.io.File(getCacheDir(), "wp_intro.mp4");
        try (java.io.InputStream in = getAssets().open(mp4);
             java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } catch (Throwable t) {
            return;
        }
        android.view.TextureView tv = new android.view.TextureView(this);
        tv.setFocusable(false);
        tv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        introView = tv;
        root.addView(tv, 1, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tv.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface, int width, int height) {
                try {
                    android.media.MediaPlayer mp = new android.media.MediaPlayer();
                    introPlayer = mp;
                    mp.setSurface(new android.view.Surface(surface));
                    mp.setDataSource(tmp.getAbsolutePath());
                    mp.setVolume(0f, 0f);
                    mp.setLooping(false);
                    mp.setOnCompletionListener(m -> {
                        if (introView != null) {
                            introView.animate().alpha(0f).setDuration(300).withEndAction(MainActivity.this::stopWallpaperIntro).start();
                        } else {
                            stopWallpaperIntro();
                        }
                    });
                    mp.setOnErrorListener((m, what, extra) -> {
                        stopWallpaperIntro();
                        return true;
                    });
                    mp.setOnPreparedListener(android.media.MediaPlayer::start);
                    mp.prepareAsync();
                } catch (Throwable e) {
                    stopWallpaperIntro();
                }
            }
            @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture s, int w, int h) {}
            @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture s) { stopWallpaperIntro(); return true; }
            @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture s) {}
        });
    }

    /** v2.10: every bundled Omarchy theme (like the PC's theme menu), then its backgrounds. */
    private AsciiMenu.Page themesPage() {
        LauncherConfig cfg = LauncherConfig.get(this);
        AsciiMenu.Page p = new AsciiMenu.Page(str(R.string.v210_sys_themes));
        String lBg = str(R.string.menu_wallpaper), lSync = str(R.string.wp_sync_pc);
        String lRandTheme = str(R.string.set_random_theme), lRandWp = str(R.string.set_random_wallpaper);
        p.add(lBg, "›", r -> push(wallpaperPage()));
        p.add(lRandTheme, str(cfg.randomTheme ? R.string.on : R.string.off), r -> {
            boolean n = !cfg.randomTheme;
            applyLive("themes", lRandTheme, j -> j.put("random_theme", n));
            return true;
        });
        p.add(lRandWp, str(cfg.randomWallpaper ? R.string.on : R.string.off), r -> {
            boolean n = !cfg.randomWallpaper;
            applyLive("themes", lRandWp, j -> j.put("random_wallpaper", n));
            return true;
        });
        p.add(lSync, str(cfg.syncPcWallpaper ? R.string.on : R.string.off), r -> {
            boolean n = !cfg.syncPcWallpaper;
            applyLive("themes", lSync, j -> j.put("syncPcWallpaper", n));
            return true;
        });
        for (String n : Theme.names(this)) {
            p.add(n, !cfg.randomTheme && n.equals(Theme.cur().name) ? str(R.string.mark_current) : (n.equals(Theme.cur().name) ? "●" : ""), r -> {
                applyLive("themes", n, j -> {
                    LauncherConfig.lockTheme(j, n);
                    j.put("wallpaper_bg", 0);
                    if ("procedural".equals(j.optString("wallpaper")) || j.optString("wallpaper").startsWith("wallpapers/")) {
                        j.put("wallpaper", "omarchy");
                    }
                });
                return true;
            });
        }
        // v2.10.1: themes pushed from the PC (omarchy-tv themes push), each background selectable.
        java.io.File ld = linkDir();
        for (String n : com.omarchytv.home.link.CustomThemes.names(ld)) {
            java.io.File[] bgs = com.omarchytv.home.link.CustomThemes.backgrounds(ld, n);
            int count = Math.max(1, bgs.length);
            for (int i = 0; i < count; i++) {
                final int idx = i;
                String label = "★ " + n + (bgs.length > 1 ? " · " + (i + 1) + "/" + bgs.length : "");
                p.add(label, n.equals(Theme.cur().name) ? str(R.string.mark_current) : "", r -> {
                    applyLive("themes", label, j -> {
                        com.omarchytv.home.link.CustomThemes.select(ld, n, idx, j);
                        j.put("random_theme", false);
                        j.put("random_wallpaper", false);
                    });
                    return true;
                });
            }
        }
        return p;
    }

    /** Same folder as OmarchyLink.wallpaperDir(): the link's files (custom themes live in themes/). */
    private java.io.File linkDir() {
        java.io.File d = getExternalFilesDir(null);
        return new java.io.File(d != null ? d : getFilesDir(), "link");
    }

    private AsciiMenu.Page wallpaperPage() {
        LauncherConfig cfg = LauncherConfig.get(this);
        AsciiMenu.Page p = new AsciiMenu.Page(str(R.string.menu_wallpaper));
        String lRandWp = str(R.string.set_random_wallpaper);
        p.add(lRandWp, str(cfg.randomWallpaper ? R.string.on : R.string.off), r -> {
            boolean n = !cfg.randomWallpaper;
            applyLive("wallpaper", lRandWp, j -> j.put("random_wallpaper", n));
            return true;
        });
        boolean omarchy = !"procedural".equals(cfg.wallpaper) && !cfg.wallpaper.startsWith("/") && !cfg.wallpaper.startsWith("wallpapers/");
        String[] bgs = WallpaperView.backgrounds(this, Theme.cur().name);
        int cur = bgs.length > 0 ? cfg.wallpaperBg % bgs.length : 0;
        String lNext = str(R.string.wp_next);
        if (bgs.length > 1) p.add(lNext, (cur + 1) + "/" + bgs.length, r -> {
            int n = (cur + 1) % bgs.length;
            applyLive("wallpaper", lNext, j -> LauncherConfig.lockWallpaper(j, "omarchy", n));
            return true;
        });
        for (int i = 0; i < bgs.length; i++) {
            final int idx = i;
            String f = bgs[i].substring(bgs[i].lastIndexOf('/') + 1).replaceFirst("^\\d+-", "").replaceFirst("\\.webp$", "");
            String label = Theme.cur().name + " · " + f;
            p.add(label, !cfg.randomWallpaper && omarchy && idx == cur ? str(R.string.mark_current) : (omarchy && idx == cur ? "●" : ""), r -> {
                applyLive("wallpaper", label, j -> LauncherConfig.lockWallpaper(j, "omarchy", idx));
                return true;
            });
        }
        p.add(str(R.string.wp_procedural), "procedural".equals(cfg.wallpaper) ? str(R.string.mark_current) : "", r -> {
            applyLive("wallpaper", str(R.string.wp_procedural), j -> LauncherConfig.lockWallpaper(j, "procedural", 0));
            return true;
        });
        String lSync = str(R.string.wp_sync_pc);
        p.add(lSync, str(cfg.syncPcWallpaper ? R.string.on : R.string.off), r -> {
            boolean n = !cfg.syncPcWallpaper;
            applyLive("wallpaper", lSync, j -> j.put("syncPcWallpaper", n));
            return true;
        });
        return p;
    }

    private String idleLabel(int sec) {
        if (sec <= 0) return str(R.string.ss_never);
        if (sec % 60 == 0) return (sec / 60) + " min";
        return String.format(java.util.Locale.getDefault(), "%.1f min", sec / 60f);
    }

    private AsciiMenu.Page screensaverPage() {
        LauncherConfig cfg = LauncherConfig.get(this);
        String[] logos = {"aquarium", "aquarium-classic", "omarchy-tv", "omarchy", "cinemarchy", "original"};
        String[] sizes = {"small", "medium", "large"};
        String lLogo = str(R.string.set_logo), lSize = str(R.string.ss_size), lClock = str(R.string.ss_clock);
        AsciiMenu.Page p = new AsciiMenu.Page(str(R.string.menu_screensaver));
        p.add(str(R.string.ss_preview), null, r -> {
            open(new Intent(this, ScreensaverPreviewActivity.class));
            return false;
        });
        p.add(lLogo, logoLabel(cfg.screensaverArt), r -> {
            String n = next(logos, cfg.screensaverArt);
            applyLive("screensaver", lLogo, j -> screensaver(j).put("art", n).put("mode", n.startsWith("aquarium") ? "aquarium" : "logo"));
            return true;
        });
        p.add(lSize, sizeLabel(cfg.screensaverSize), r -> {
            String n = next(sizes, cfg.screensaverSize);
            applyLive("screensaver", lSize, j -> screensaver(j).put("size", n));
            return true;
        });
        String[] speeds = {"slow", "normal", "fast"};
        String lSpeed = str(R.string.ss_speed);
        p.add(lSpeed, speedLabel(cfg.screensaverSpeed), r -> {
            String n = next(speeds, cfg.screensaverSpeed);
            applyLive("screensaver", lSpeed, j -> screensaver(j).put("speed", n));
            return true;
        });
        p.add(lClock, str(cfg.screensaverClock ? R.string.on : R.string.off), r -> {
            boolean n = !cfg.screensaverClock;
            applyLive("screensaver", lClock, j -> screensaver(j).put("clock", n));
            return true;
        });
        String lSound = "Aquarium ST3 Synth Audio";
        p.add(lSound, str(cfg.saverSound ? R.string.on : R.string.off), r -> {
            boolean n = !cfg.saverSound;
            applyLive("screensaver", lSound, j -> screensaver(j).put("sound", n));
            return true;
        });
        // v2.10: "Avvia salvaschermo dopo": 1 min, 2,5 min (default, as Omarchy), 5 min, 10 min, never.
        Integer[] idles = {60, 150, 300, 600, 0};
        String lIdle = str(R.string.ss_idle), lSys = str(R.string.ss_system_use);
        p.add(lIdle, idleLabel(cfg.screensaverIdleSec), r -> {
            int n = next(idles, cfg.screensaverIdleSec);
            applyLive("screensaver", lIdle, j -> screensaver(j).put("idleSec", n));
            return true;
        });
        p.add(lSys, str(cfg.screensaverSystem ? R.string.on : R.string.off), r -> {
            boolean n = !cfg.screensaverSystem;
            applyDreams(n);
            applyLive("screensaver", lSys, j -> screensaver(j).put("system", n));
            return true;
        });
        if (cfg.screensaverSystem) {
            p.add(str(R.string.ss_system), null, r -> {
                open(new Intent(Settings.ACTION_DREAM_SETTINGS));
                return false;
            });
        }
        return p;
    }

    // ---- Omarchy Link (omarchy-link/docs/PROTOCOL.md; server in OmarchyLink) --------------------------

    static final int LINK_PORT = com.omarchytv.home.link.LinkServer.PORT;
    /** Settings page on screen that a Link event may re-render: "link", "ai" or null. */
    private String menuPage;

    /**
     * Connect Omarchy Linux. While this page is visible pairing is open (at most 5 minutes) and the mDNS TXT
     * says pair=1. The code lives in memory only; tokens in internal storage; omarchytv.json gets link.pcs[].
     */
    private AsciiMenu.Page linkPage() {
        String code = OmarchyLink.currentCode();
        if (code == null) code = OmarchyLink.openPairing();
        java.util.List<com.omarchytv.home.link.LinkStore.Pc> pcs = OmarchyLink.pcs();
        String ip = tvAddress();
        AsciiMenu.Page p = new AsciiMenu.Page(str(R.string.menu_link));
        menuPage = "link";
        p.onLeave = () -> {
            if (linkRefreshing) return;
            menuPage = null;
            OmarchyLink.closePairing();
        };
        p.add(str(R.string.link_tv_ip), ip == null ? str(R.string.offline) : ip + ":" + LINK_PORT, r -> true);
        p.add(str(R.string.link_code), code.substring(0, 3) + " " + code.substring(3), r -> true);
        p.add(str(R.string.link_status), !pcs.isEmpty() ? getString(R.string.link_paired_n, pcs.size()) : str(R.string.link_waiting), r -> true);
        for (com.omarchytv.home.link.LinkStore.Pc pc : pcs) {
            final String id = pc.id;
            String where = pc.lastAddress == null ? "" : " · " + pc.lastAddress;
            String state = str(OmarchyLink.connected(id) ? R.string.link_online : R.string.link_offline)
                    + " · " + str(R.string.link_forget_short);
            p.add(pc.name + where, state, r -> {
                OmarchyLink.forget(id);   // sends "unpaired", closes its sockets, deletes the token
                return true;              // the "pcs" event re-renders this page
            });
        }
        // v2.10: Send to TV (cast) is off by default.
        String lCast = str(R.string.v210_cast);
        p.add(lCast, str(LauncherConfig.get(this).castEnabled ? R.string.on : R.string.off), r -> {
            boolean n = !LauncherConfig.get(this).castEnabled;
            builtWith = LauncherConfig.update(this, j -> {
                org.json.JSONObject c = j.optJSONObject("cast");
                if (c == null) j.put("cast", c = new org.json.JSONObject());
                c.put("enabled", n);
            });
            CastRouter.reset();
            r.setValue(str(n ? R.string.on : R.string.off));
            return true;
        });
        p.add(str(R.string.link_new_code), null, r -> {
            OmarchyLink.openPairing();
            refreshMenu("link");
            return true;
        });
        p.add(str(R.string.link_service), OmarchyLink.running() ? str(R.string.link_service_on) : str(R.string.link_service_off), r -> true);
        // Optional extras the PC can use (PROTOCOL.md §6): Back key and the app in front.
        p.add(str(R.string.link_remote), str(OmarchyRemote.enabled() ? R.string.on : R.string.off), r -> {
            open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return false;
        });
        p.add(str(R.string.link_usage), str(usageAccess() ? R.string.on : R.string.off), r -> {
            open(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            return false;
        });
        return p;
    }

    private static org.json.JSONObject pcWidgets(org.json.JSONObject j) throws org.json.JSONException {
        org.json.JSONObject w = j.optJSONObject("pcWidgets");
        if (w == null) j.put("pcWidgets", w = new org.json.JSONObject());
        return w;
    }

    /** Omarchy widgets: PC panes (all / each), what happens when they do not fit, the "> omarchy" shortcuts row. */
    private AsciiMenu.Page widgetsPage() {
        LauncherConfig cfg = LauncherConfig.get(this);
        AsciiMenu.Page p = new AsciiMenu.Page(str(R.string.set_widgets));
        String lPanes = str(R.string.set_pc_panes), lOver = str(R.string.set_pane_overflow), lSc = str(R.string.set_pc_shortcuts),
                lOff = str(R.string.ai_offline_row);
        // v2.10: each home widget can be switched off ("No" really hides it); the clock widget is gone (bar only).
        String[][] hw = {{"term", str(R.string.v210_w_term)}, {"weather", str(R.string.v210_w_weather)}, {"pc", str(R.string.v210_w_pc)},
                {"ai", str(R.string.v210_w_ai)}, {"deploys", str(R.string.v210_w_deploys)}};
        boolean[] hwOn = {cfg.wTerm, cfg.wWeather, cfg.wPc, cfg.wAi, cfg.wDeploys};
        for (int i = 0; i < hw.length; i++) {
            final String id = hw[i][0], label = hw[i][1];
            final boolean on = hwOn[i];
            p.add(label, str(on ? R.string.on : R.string.off), r -> {
                applyLive("widgets", label, j -> {
                    org.json.JSONObject o = j.optJSONObject("homeWidgets");
                    if (o == null) j.put("homeWidgets", o = new org.json.JSONObject());
                    o.put(id, !on);
                });
                return true;
            });
        }
        p.add(lSc, cfg.shortcuts.isEmpty() ? "—" : str(cfg.shortcutsOn ? R.string.on : R.string.off), r -> {
            boolean n = !cfg.shortcutsOn;
            applyLive("widgets", lSc, j -> pcWidgets(j).put("shortcuts", n));
            return true;
        });
        p.add(lOff, str("hide".equals(cfg.aiOffline) ? R.string.ai_offline_hide : R.string.ai_offline_stale), r -> {
            String n = "hide".equals(cfg.aiOffline) ? "stale" : "hide";
            applyLive("widgets", lOff, j -> {
                org.json.JSONObject ap = j.optJSONObject("aiPane");
                if (ap == null) j.put("aiPane", ap = new org.json.JSONObject());
                ap.put("offline", n);
            });
            return true;
        });
        if (cfg.pcPanes.isEmpty() && cfg.shortcuts.isEmpty()) p.hint(str(R.string.widgets_none));
        return p;
    }

    private boolean usageAccess() {
        android.app.AppOpsManager ops = getSystemService(android.app.AppOpsManager.class);
        @SuppressWarnings("deprecation")
        int mode = ops == null ? android.app.AppOpsManager.MODE_ERRORED
                : ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName());
        return mode == android.app.AppOpsManager.MODE_ALLOWED;
    }

    /**
     * AI credits: one switch per source the PC mirrors (omarchytv.json "aiSources", only when the PC sent it).
     * Flipping one sends "ai_sources" with the complete enabled list; the PC answers and re-pushes the credits,
     * so the switch shows the new state only once the PC confirmed it (an error reverts it with a message).
     */
    private AsciiMenu.Page aiPage() {
        LauncherConfig cfg = LauncherConfig.get(this);
        AsciiMenu.Page p = new AsciiMenu.Page(str(R.string.ai_settings));
        menuPage = "ai";
        p.onLeave = () -> {
            if (!linkRefreshing) menuPage = null;
        };
        p.add(str(R.string.grok_bot_hub), "›", r -> push(grokBotPage()));
        String lOff = str(R.string.ai_offline_row);
        p.add(lOff, str("hide".equals(cfg.aiOffline) ? R.string.ai_offline_hide : R.string.ai_offline_stale), r -> {
            String n = "hide".equals(cfg.aiOffline) ? "stale" : "hide";
            applyLive("ai", lOff, j -> {
                org.json.JSONObject ap = j.optJSONObject("aiPane");
                if (ap == null) j.put("aiPane", ap = new org.json.JSONObject());
                ap.put("offline", n);
            });
            return true;
        });
        if (cfg.aiSources.isEmpty()) {
            p.hint(str(R.string.ai_empty));
            return p;
        }
        p.hint(str(R.string.ai_sources_hint));
        for (LauncherConfig.AiSource src : cfg.aiSources) {
            String value = !src.available ? str(R.string.ai_src_missing) : str(src.enabled ? R.string.on : R.string.off);
            p.add(src.name, value, r -> {
                if (!OmarchyLink.connected()) {
                    Toast.makeText(this, R.string.ask_not_connected, Toast.LENGTH_LONG).show();
                    return true;
                }
                org.json.JSONArray enabled = new org.json.JSONArray();
                for (LauncherConfig.AiSource o : cfg.aiSources) {
                    boolean on = o.id.equals(src.id) ? !src.enabled : o.enabled;
                    if (on) enabled.put(o.id);
                }
                String id = Long.toHexString(new java.security.SecureRandom().nextLong());
                org.json.JSONObject req;
                try {
                    req = new org.json.JSONObject().put("id", id).put("enabled", enabled);
                } catch (org.json.JSONException e) {
                    return true;
                }
                pendingSourcesId = id;
                r.setValue("…");
                OmarchyLink.aiSources(req, err -> {
                    if (err == null || !id.equals(pendingSourcesId)) return;
                    pendingSourcesId = null;
                    r.setValue(value);
                    Toast.makeText(this, "busy".equals(err) ? R.string.ask_busy : R.string.ask_not_connected, Toast.LENGTH_SHORT).show();
                });
                return true;
            });
        }
        return p;
    }

    private static org.json.JSONObject link(org.json.JSONObject j) throws org.json.JSONException {
        org.json.JSONObject l = j.optJSONObject("link");
        if (l == null) {
            l = new org.json.JSONObject();
            j.put("link", l);
        }
        return l;
    }

    /** IPv4 address of the active network (Wi-Fi or Ethernet), or null. */
    private String tvAddress() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null || cm.getActiveNetwork() == null) return null;
        android.net.LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
        if (lp == null) return null;
        for (android.net.LinkAddress a : lp.getLinkAddresses()) {
            if (a.getAddress() instanceof java.net.Inet4Address) return a.getAddress().getHostAddress();
        }
        return null;
    }

    private static org.json.JSONObject screensaver(org.json.JSONObject j) throws org.json.JSONException {
        org.json.JSONObject s = j.optJSONObject("screensaver");
        if (s == null) {
            s = new org.json.JSONObject();
            j.put("screensaver", s);
        }
        return s;
    }

    private AsciiMenu.Page hiddenPage() {
        LauncherConfig cfg = LauncherConfig.get(this);
        AsciiMenu.Page p = new AsciiMenu.Page(str(R.string.set_hidden));
        if (cfg.hidden.isEmpty()) p.add(str(R.string.hidden_none), null, r -> true);
        for (String raw : cfg.hidden) {
            String basePkg = raw.contains("/") ? raw.substring(0, raw.indexOf('/')) : raw;
            String label = basePkg;
            if (raw.startsWith("web:")) {
                String wid = raw.substring(4);
                LauncherConfig.WebApp bw = LauncherConfig.findBuiltinAiWebApp(wid);
                label = bw != null ? bw.name : wid;
                for (LauncherConfig.WebApp w : cfg.webapps) if (w.id.equals(wid)) label = w.name;
            } else {
                try {
                    label = String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(basePkg, 0)));
                } catch (Exception ignored) {
                }
            }
            p.add(label, str(R.string.unhide), r -> {
                List<String> list = new ArrayList<>(cfg.hidden);
                list.remove(raw);
                list.remove(basePkg);
                applyLive("hidden", null, j -> j.put("hidden", LauncherConfig.array(list)));
                return true;
            });
        }
        return p;
    }

    private String langLabel(String l) {
        if ("en".equals(l)) return str(R.string.lang_en);
        if ("it".equals(l)) return str(R.string.lang_it);
        if ("de".equals(l)) return str(R.string.lang_de);
        return str(R.string.lang_auto);
    }

    private String sizeLabel(String s) {
        return str("small".equals(s) ? R.string.size_small : "large".equals(s) ? R.string.size_large : R.string.size_medium);
    }

    private String speedLabel(String s) {
        return str("slow".equals(s) ? R.string.speed_slow : "fast".equals(s) ? R.string.speed_fast : R.string.speed_normal);
    }

    /**
     * Grok Bot & AI hub: works like Omarchy on desktop — open Grok Bot (or Grok / ChatGPT / Claude / Hermes)
     * directly in WebAppActivity (:web process with persistent cookies/DOM storage & D-pad cursor), or use
     * paired PC "> ask" / "> agents" and the TV MCP server (:47800/mcp).
     */
    private AsciiMenu.Page grokBotPage() {
        AsciiMenu.Page p = new AsciiMenu.Page(str(R.string.grok_bot_hub));
        LauncherConfig.WebApp grokBot = LauncherConfig.BUILTIN_AI_WEBAPPS[0];
        LauncherConfig gc = LauncherConfig.get(this);
        p.add("Chat with the Grok Bot companion", "OK ›", r -> {
            handler.post(() -> openCompanionChat(false));
            return false;
        });
        String lOv = "Floating companion on the home screen";
        p.add(lOv, str(gc.companionOverlay ? R.string.on : R.string.off), r -> {
            boolean n = !gc.companionOverlay;
            applyLive("grok-bot", lOv, j -> companionPlugins(j).put("companionOverlay", n));
            return true;
        });
        String lCorner = "Companion corner";
        p.add(lCorner, gc.companionCorner, r -> {
            String n = CompanionPlacement.next(gc.companionCorner);
            applyLive("grok-bot", lCorner, j -> companionPlugins(j).put("companionCorner", n));
            return true;
        });
        String lOff = "Companion offset (sideways · up/down, dp)";
        p.add(lOff, gc.companionOffsetX + " · " + gc.companionOffsetY, r -> {
            int[] steps = {0, 24, 48, 96, 160};
            int i = 0;
            while (i < steps.length && steps[i] != gc.companionOffsetX) i++;
            int nx = steps[(i + 1) % steps.length];
            applyLive("grok-bot", lOff, j -> companionPlugins(j).put("companionOffsetX", nx).put("companionOffsetY", nx / 2));
            return true;
        });
        String lShape = "Companion shape (Tom Ballard's 14)";
        p.add(lShape, gc.companionShape.isEmpty() ? "follow PC bot" : gc.companionShape, r -> {
            // "" (follow the PC bot) → blob → cloud → … → pebble → "".
            String n = gc.companionShape.isEmpty() ? GrokbotAvatar.NAMES_FIRST
                    : GrokbotAvatar.index(gc.companionShape) == GrokbotAvatar.COUNT - 1 ? "" : GrokbotAvatar.next(gc.companionShape);
            applyLive("grok-bot", lShape, j -> companionPlugins(j).put("companionShape", n));
            return true;
        });
        String lColor = "Companion colour";
        p.add(lColor, gc.companionColor.isEmpty() ? "follow PC bot" : gc.companionColor, r -> {
            int i = GrokbotAvatar.colorIndex(gc.companionColor);
            String n = i + 1 < GrokbotAvatar.COLOR_NAMES.length ? GrokbotAvatar.COLOR_NAMES[i + 1] : "";
            applyLive("grok-bot", lColor, j -> companionPlugins(j).put("companionColor", n));
            return true;
        });
        String lSize = "Companion size";
        p.add(lSize, gc.companionSize, r -> {
            String n = CompanionPlacement.nextSize(gc.companionSize);
            applyLive("grok-bot", lSize, j -> companionPlugins(j).put("companionSize", n));
            return true;
        });
        String lBadge = "Companion name badge";
        p.add(lBadge, str(gc.companionBadge ? R.string.on : R.string.off), r -> {
            boolean n = !gc.companionBadge;
            applyLive("grok-bot", lBadge, j -> companionPlugins(j).put("companionBadge", n));
            return true;
        });
        String lAnim = "Companion animation (bob, blink, look around, sleep, drift)";
        p.add(lAnim, str(gc.companionAnim ? R.string.on : R.string.off), r -> {
            boolean n = !gc.companionAnim;
            applyLive("grok-bot", lAnim, j -> companionPlugins(j).put("companionAnim", n));
            return true;
        });
        String lAgent = "Companion agent";
        p.add(lAgent, "ask".equals(gc.companionAgent) ? "OmarchyTV Ask" : "Grok Bot (PC companion)", r -> {
            String n = "ask".equals(gc.companionAgent) ? "grokbot" : "ask";
            applyLive("grok-bot", lAgent, j -> companionPlugins(j).put("companionAgent", n));
            return true;
        });
        p.hint("Long-press Menu on the remote to reach the companion, then OK to chat.");
        p.add("Grok Bot (Omarchy TUI)", "tui ›", r -> {
            LauncherConfig.update(this, j -> LauncherConfig.ensureWebApp(j, grokBot, true));
            builtWith = LauncherConfig.get(this);
            reloadApps();
            if (home != null) home.bind(builtWith, adapter);
            AskActivity.open(this);
            return false;
        });
        p.add(str(R.string.grok_bot_open), "grok.com ›", r -> {
            LauncherConfig.update(this, j -> LauncherConfig.ensureWebApp(j, grokBot, true));
            builtWith = LauncherConfig.get(this);
            reloadApps();
            if (home != null) home.bind(builtWith, adapter);
            WebAppActivity.open(this, grokBot);
            return false;
        });
        p.add("> agents", OmarchyLink.connected() ? "pc ›" : "›", r -> {
            open(new Intent(this, AgentActivity.class));
            return false;
        });
        for (int i = 1; i < LauncherConfig.BUILTIN_AI_WEBAPPS.length; i++) {
            final LauncherConfig.WebApp w = LauncherConfig.BUILTIN_AI_WEBAPPS[i];
            String host = android.net.Uri.parse(w.url).getHost();
            p.add(w.name, (host == null ? "" : host) + " ›", r -> {
                LauncherConfig.update(this, j -> LauncherConfig.ensureWebApp(j, w, false));
                builtWith = LauncherConfig.get(this);
                reloadApps();
                if (home != null) home.bind(builtWith, adapter);
                WebAppActivity.open(this, w);
                return false;
            });
        }
        p.add(str(R.string.grok_bot_pin_all), "+", r -> {
            applyLive("grok-bot", str(R.string.grok_bot_pin_all), j -> {
                for (int i = 0; i < LauncherConfig.BUILTIN_AI_WEBAPPS.length; i++) {
                    LauncherConfig.ensureWebApp(j, LauncherConfig.BUILTIN_AI_WEBAPPS[i], i < 2);
                }
            });
            return true;
        });
        String ip = tvAddress();
        p.add(str(R.string.grok_bot_mcp), (ip != null ? ip : "127.0.0.1") + ":" + LINK_PORT + "/mcp", r -> true);
        p.add(str(R.string.ask_settings), "›", r -> push(assistantPage()));
        return p;
    }

    private String logoLabel(String l) {
        if ("aquarium".equals(l)) return "Omarchy Aquarium (homage)";
        if ("aquarium-classic".equals(l)) return "Aquarium Classic";
        if ("cinemarchy".equals(l)) return "CINEMARCHY";
        return str("omarchy".equals(l) ? R.string.logo_omarchy : "original".equals(l) ? R.string.logo_original : R.string.logo_omarchy_tv);
    }

    private String accentLabel(String a) {
        return str("omarchy".equals(a) ? R.string.accent_omarchy : "green".equals(a) ? R.string.accent_green : R.string.accent_tokyo);
    }

    /** About & licences: version, then the bundled NOTICE (third-party components, Omarchy attribution). */
    private void showAbout() {
        View panel = Theme.applied(getLayoutInflater().inflate(R.layout.popup_about, null, false));
        TextView body = panel.findViewById(R.id.aboutText);
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        StringBuilder b = new StringBuilder(getString(R.string.about_version, version)).append("\n\n");
        try (java.io.InputStream in = getAssets().open("licenses/NOTICE.txt")) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            b.append(new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException ignored) {
        }
        body.setText(b);
        PopupWindow pw = new PopupWindow(panel, (int) (720 * density), (int) (420 * density), true);
        pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pw.setAnimationStyle(0);
        pw.showAtLocation(root, Gravity.CENTER, 0, 0);
        panel.findViewById(R.id.aboutScroll).requestFocus();
    }

    // ---- tile context menu + move mode ------------------------------------------------------------

    @Override
    public void onTileLongPress(int position) {
        if (movingPos >= 0) return;
        String pkg = adapter.packageAt(position);
        if (pkg == null) return;
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(position);
        if (vh == null) return;
        if (menu != null && menu.isShowing()) menu.dismiss();
        menu = new AsciiMenu(this, 300);
        AsciiMenu.Page p = new AsciiMenu.Page(adapter.labelAt(position));
        p.add(str(R.string.ctx_move), "←↑↓→", r -> {
            grid.post(() -> startMove(position));
            return false;
        });
        p.add(str(R.string.ctx_hide), null, r -> {
            LauncherConfig.update(this, j -> {
                List<String> list = new ArrayList<>(LauncherConfig.get(this).hidden);
                if (!list.contains(pkg)) list.add(pkg);
                j.put("hidden", LauncherConfig.array(list));
            });
            builtWith = LauncherConfig.get(this);
            reloadApps();
            grid.post(() -> focusTile(Math.min(position, adapter.getItemCount() - 1)));
            return false;
        });
        LauncherConfig.WebApp webApp = adapter.webAt(position);
        if (webApp != null) {
            p.add(str(R.string.web_remove), null, r -> {
                removeWebApp(webApp.id);
                return false;
            });
        } else {
            p.add(str(R.string.ctx_info), null, r -> {
                AppAdapter.openAppInfo(this, pkg);
                return false;
            });
        }
        // Only user-installed apps: system/preinstalled apps (and the TV's own) are never offered here.
        if (!adapter.isSystemAt(position)) {
            p.add(str(R.string.ctx_uninstall), null, r -> {
                try {
                    startActivity(new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + pkg)));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
                return false;
            });
        }
        menu.showBelow(vh.itemView.findViewById(R.id.card), p);
    }

    private void startMove(int position) {
        movingPos = position;
        moveSnapshot = adapter.packagesInOrder();
        moveHint.setVisibility(View.VISIBLE);
        panes.setVisibility(View.INVISIBLE);   // the hint sits where the panes are
        TextFx.type(moveHint, getString(R.string.move_hint), 260);
        glass.setMoving(true);
        focusTile(position);
    }

    private void finishMove(boolean save) {
        if (movingPos < 0) return;
        int at = movingPos;
        movingPos = -1;
        moveHint.setVisibility(View.GONE);
        panes.setVisibility(paneViews.isEmpty() ? View.GONE : View.VISIBLE);
        glass.setMoving(false);
        if (save) {
            List<String> pkgs = adapter.packagesInOrder();
            int d = Math.min(COLUMNS, pkgs.size());
            List<String> dock = new ArrayList<>(pkgs.subList(0, d));
            List<String> rest = new ArrayList<>(pkgs.subList(d, pkgs.size()));
            builtWith = LauncherConfig.update(this, j -> {
                j.put("dock", LauncherConfig.array(dock));
                j.put("order", LauncherConfig.array(rest));
            });
            Toast.makeText(this, R.string.move_saved, Toast.LENGTH_SHORT).show();
            focusTile(at);
        } else if (moveSnapshot != null) {
            String key = adapter.keyAt(at);
            adapter.restoreOrder(moveSnapshot);
            Toast.makeText(this, R.string.move_cancelled, Toast.LENGTH_SHORT).show();
            grid.post(() -> focusTile(adapter.positionOf(key)));
        }
        moveSnapshot = null;
    }

    // ---- in-launcher screensaver --------------------------------------------------------------------
    // Shown only after N minutes without remote input while this home screen is resumed and focused (no menu,
    // no move mode; ask / voice / other apps / Live TV pause the activity, so the timer is off there).
    // Any key dismisses it back to home and is swallowed. One Handler callback, no wakelock, no
    // FLAG_KEEP_SCREEN_ON: the TV's own sleep timer still applies.

    /** v2.10: the screensaver is ScreensaverPreviewActivity in its own process (":saver"); true while it is shown. */
    private boolean saverShown;
    private int swallowKeyUp = -1;
    private final Runnable idle = this::onIdle;

    private void resetIdle() {
        handler.removeCallbacks(idle);
        int sec = LauncherConfig.get(this).screensaverIdleSec;   // 0 = never
        if (resumed && sec > 0) handler.postDelayed(idle, sec * 1000L);
    }

    private void onIdle() {
        if (!resumed) return;
        if ((menu != null && menu.isShowing()) || !hasWindowFocus() || movingPos >= 0 || walker != null) {
            resetIdle();   // something else is on screen: wait a full timeout after it closes
            return;
        }
        startSaver();
    }

    private void startSaver() {
        saverShown = true;
        OmarchyLink.setScreensaver(true);
        try {
            startActivity(new Intent(this, ScreensaverPreviewActivity.class));
            overridePendingTransition(0, 0);
        } catch (Exception e) {
            hideSaver();
        }
    }

    private void hideSaver() {
        if (!saverShown) return;
        saverShown = false;
        OmarchyLink.setScreensaver(false);
    }

    private final java.util.Set<Integer> loggedKeys = new java.util.HashSet<>();

    /** v2.10: record which remote keys reach the app on this TV (1-5, CH±, voice), once per key per process. */
    private void logKeyOnce(int kc) {
        boolean interesting = (kc >= KeyEvent.KEYCODE_0 && kc <= KeyEvent.KEYCODE_9) || kc == KeyEvent.KEYCODE_CHANNEL_UP
                || kc == KeyEvent.KEYCODE_CHANNEL_DOWN || kc == KeyEvent.KEYCODE_SEARCH || kc == KeyEvent.KEYCODE_VOICE_ASSIST
                || kc == KeyEvent.KEYCODE_ASSIST || kc == KeyEvent.KEYCODE_PAGE_UP || kc == KeyEvent.KEYCODE_PAGE_DOWN;
        if (interesting && loggedKeys.add(kc)) android.util.Log.i("OmarchyTV", "tasto ricevuto dal launcher: " + KeyEvent.keyCodeToString(kc));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);   // HOME pressed while on home: wake up from the screensaver
        if (intent.getBooleanExtra(HomeCheck.EXTRA_ASK, false)) setIntent(intent);   // HomeCheck from boot
        if (Intent.ACTION_ASSIST.equals(intent.getAction())) setIntent(intent);   // mic key, handled in onResume
        hideSaver();
        resetIdle();
        if (intent.hasExtra(EXTRA_ACTION)) {   // a key binding, not the HOME key: onResume runs it
            setIntent(intent);
            return;
        }
        if (home != null && walker == null && (menu == null || !menu.isShowing())) {   // HOME key: back to workspace 1
            if (workspace != 1) setWorkspace(1);
            home.onHomeShown();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (swallowKeyUp == e.getKeyCode() && e.getAction() == KeyEvent.ACTION_UP) {
            swallowKeyUp = -1;
            return true;
        }
        resetIdle();
        if (companion != null) companion.poke();   // any key wakes a sleeping companion
        if (walker == null && movingPos < 0 && (menu == null || !menu.isShowing()) && companionKey(e)) return true;
        if (walker != null) {
            if (walker.onKey(e)) return true;
            return super.dispatchKeyEvent(e);
        }
        if (movingPos < 0 && home != null && (menu == null || !menu.isShowing())) {
            int kc = e.getKeyCode();
            logKeyOnce(kc);
            if (home.movingApp >= 0) return home.onKey(e);
            // Voice / search key: the Grok Bot companion by voice (v2.14 default), Walker, or the system assistant if chosen.
            if (kc == KeyEvent.KEYCODE_SEARCH || kc == KeyEvent.KEYCODE_VOICE_ASSIST || kc == KeyEvent.KEYCODE_ASSIST) {
                if (voiceToCompanion()) {
                    if (e.getAction() == KeyEvent.ACTION_UP) openCompanionChat(true);
                    return true;
                }
                if ("launcher".equals(LauncherConfig.get(this).voiceKey)) {
                    if (e.getAction() == KeyEvent.ACTION_UP) openWalker();
                    return true;
                }
            }
            // v2.10 workspaces: 1-5 jump, CH+/CH− next/previous (only reaches us while the launcher is in front).
            if (kc >= KeyEvent.KEYCODE_1 && kc <= KeyEvent.KEYCODE_5) {
                int n = LauncherConfig.get(this).workspaceAt(kc - KeyEvent.KEYCODE_0);   // the key is the bar position
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && n > 0) setWorkspace(n);
                return true;
            }
            if (kc == KeyEvent.KEYCODE_CHANNEL_UP || kc == KeyEvent.KEYCODE_CHANNEL_DOWN || kc == KeyEvent.KEYCODE_PAGE_UP || kc == KeyEvent.KEYCODE_PAGE_DOWN) {
                boolean up = kc == KeyEvent.KEYCODE_CHANNEL_UP || kc == KeyEvent.KEYCODE_PAGE_DOWN;
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) setWorkspace(LauncherConfig.get(this).nextWorkspace(workspace, up ? 1 : -1));
                return true;
            }
            if (workspace != 1 && kc == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) setWorkspace(1);
                return true;
            }
            if (home.onKey(e)) return true;
        }
        if (movingPos < 0) return super.dispatchKeyEvent(e);
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int count = adapter.getItemCount();
        int to = -1;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down && movingPos > 0) to = movingPos - 1;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && movingPos < count - 1) to = movingPos + 1;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down && movingPos - COLUMNS >= 0) to = movingPos - COLUMNS;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && movingPos + COLUMNS < count) to = movingPos + COLUMNS;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!down) finishMove(true);
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (!down) finishMove(false);
                return true;
            case KeyEvent.KEYCODE_HOME:
                return super.dispatchKeyEvent(e);
            default:
                return true;   // swallow everything else while a tile is lifted
        }
        if (to >= 0) {
            adapter.move(movingPos, to);
            movingPos = to;
            final int target = to;
            grid.post(() -> focusTile(target));
        }
        return true;
    }

    // ---- info panes -------------------------------------------------------------------------------

    /** Pane views in both rows, in display order (built-in panes first, then PC panes). */
    private final List<View> paneViews = new ArrayList<>();
    private static final int MAX_PANES_PER_ROW = 5;
    /** Second pane row: shown with the dock row focused, faded out once the grid scrolls under it. */
    private LinearLayout paneRow2;

    private LinearLayout paneRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.END;
        row.setLayoutParams(lp);
        return row;
    }

    private View newPane(ViewGroup parent, Object tag, String title) {
        View v = Theme.applied(getLayoutInflater().inflate(R.layout.pane, parent, false));
        v.setBackground(paneBackground(Surfaces.surface(LauncherConfig.get(this).transparency)));
        platesSig = "";
        v.setTag(tag);
        // Same height for all panes in the row, like tiled windows.
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ((LinearLayout.LayoutParams) v.getLayoutParams()).setMarginStart((int) (10 * density));
        ((TextView) v.findViewById(R.id.paneTitle)).setText("─ " + title + " ─");
        return v;
    }

    /**
     * Top-right panes: the built-in ones (clock, weather, AI credits, sysinfo) in row 1, then the panes the PC
     * pushed (link.panes). PC panes go into row 1 while they fit next to the wordmark, then into a second row
     * (or are skipped: setting), and row 2 only shows when it clears the dock. The row never overflows.
     */
    private void buildPanes() {
        panes.removeAllViews();
        paneRow2 = null;
        paneViews.clear();
        if (home != null) {   // v2.10: the old clock / AI panes layer is gone; HomeV29 has its own widgets
            panes.setVisibility(View.GONE);
            return;
        }
        LauncherConfig cfg = LauncherConfig.get(this);
        LinearLayout row1 = paneRow();
        panes.addView(row1);
        for (String id : cfg.panes) {
            if (!"clock".equals(id) && !"ai".equals(id) && !"sysinfo".equals(id) && !"weather".equals(id) && !"agents".equals(id)) continue;
            // Weather is in the default row but has no location out of the box (nothing personal in the
            // public build): skip it until "weather" {lat, lon} is set, unless the user listed it explicitly.
            if ("weather".equals(id) && Double.isNaN(cfg.weatherLat) && cfg.panes.equals(defaultPanes())) continue;
            int title = "clock".equals(id) ? R.string.pane_clock : "ai".equals(id) ? R.string.pane_ai
                    : "sysinfo".equals(id) ? R.string.pane_sysinfo : "agents".equals(id) ? R.string.pane_agents : R.string.pane_weather;
            View v = newPane(row1, id, getString(title));
            if ("clock".equals(id)) ((TextView) v.findViewById(R.id.paneBody)).setLineSpacing(0, 0.95f);
            if ("agents".equals(id)) setupAgentsPane(v);
            if ("ai".equals(id) || "agents".equals(id)) {
                // Compact: smaller text so clock, weather and AI credits fit in one row.
                ((TextView) v.findViewById(R.id.paneBody)).setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f);
                v.setMinimumWidth(0);
            }
            paneViews.add(v);
            // The agents pane can be wide and tall: it is placed by the same fit as the PC panes (row 1 if it
            // fits next to the wordmark, else row 2, else skipped), ahead of them.
            if ("agents".equals(id)) agentsFit = v;
            else row1.addView(v);
        }
        List<View> pc = new ArrayList<>();
        if (agentsFit != null) pc.add(agentsFit);
        if (cfg.pcPanesOn) {
            for (LauncherConfig.PcPane pp : cfg.pcPanes) {
                if (cfg.hiddenPcPanes.contains(pp.id)) continue;
                View v = newPane(row1, pp, pp.title.toLowerCase(java.util.Locale.ROOT));
                TextView body = v.findViewById(R.id.paneBody);
                body.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f);   // same compact text as AI credits
                v.setMinimumWidth(0);
                pc.add(v);
                paneViews.add(v);
            }
        }
        buildingPanes = true;
        updatePanes();   // texts first: the fit below measures them
        buildingPanes = false;
        fitPcPanes(row1, pc);
        agentsFit = null;
        panes.setVisibility(paneViews.isEmpty() ? View.GONE : View.VISIBLE);
        if (cfg.panes.contains("weather")) Weather.refreshIfStale(this, cfg.weatherLat, cfg.weatherLon, this::updatePanes);
    }

    private void fitPcPanes(LinearLayout row1, List<View> pc) {
        if (pc.isEmpty()) return;
        LauncherConfig cfg = LauncherConfig.get(this);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        View left = findViewById(R.id.leftBlock);
        left.measure(unspec, unspec);
        int gap = (int) (10 * density);
        int budget1 = dm.widthPixels - (int) (56 * density) - (int) (64 * density) - left.getMeasuredWidth() - (int) (32 * density);
        int budget2 = dm.widthPixels - (int) (112 * density);
        int used1 = 0, h1 = 0;
        for (int i = 0; i < row1.getChildCount(); i++) {
            View v = row1.getChildAt(i);
            v.measure(unspec, unspec);
            used1 += v.getMeasuredWidth() + gap;
            h1 = Math.max(h1, v.getMeasuredHeight());
        }
        LinearLayout row2 = null;
        int used2 = 0, h2 = 0;
        for (View v : pc) {
            if (v.getVisibility() == View.GONE) {   // hidden agents pane: takes no room (re-fit when it shows)
                row1.addView(v);
                continue;
            }
            v.measure(unspec, unspec);
            int w = v.getMeasuredWidth() + gap;
            if (row2 == null && row1.getChildCount() < MAX_PANES_PER_ROW && used1 + w <= budget1) {
                row1.addView(v);
                used1 += w;
                h1 = Math.max(h1, v.getMeasuredHeight());
                continue;
            }
            if ("row2".equals(cfg.paneOverflow)) {
                if (row2 == null) {
                    row2 = paneRow();
                    ((LinearLayout.LayoutParams) row2.getLayoutParams()).topMargin = gap;
                }
                if (row2.getChildCount() < MAX_PANES_PER_ROW && used2 + w <= budget2) {
                    row2.addView(v);
                    used2 += w;
                    h2 = Math.max(h2, v.getMeasuredHeight());
                    continue;
                }
            }
            paneViews.remove(v);   // skipped: no room
        }
        if (row2 == null || row2.getChildCount() == 0) return;
        // Row 2 must end above the dock band and the "> app" hero line.
        int dockRow = (int) ((AppAdapter.rowHeightDp(cfg) + 6 + 12) * density);
        int limit = dm.heightPixels - dockRow - (int) ((30 + 14 + 20) * density);
        int bottom = (int) (48 * density) + h1 + gap + h2;
        if (bottom <= limit) {
            panes.addView(row2);
            paneRow2 = row2;
            row2.setAlpha(focusSection == 0 ? 1f : 0f);
        }
        else for (int i = 0; i < row2.getChildCount(); i++) paneViews.remove(row2.getChildAt(i));
    }

    private List<String> defaultPanes() {
        return java.util.Arrays.asList(getResources().getStringArray(R.array.default_panes));
    }

    private void updatePanes() {
        if (panes == null) return;
        LauncherConfig c = LauncherConfig.get(this);
        for (View v : paneViews) {
            TextView body = v.findViewById(R.id.paneBody);
            Object tag = v.getTag();
            if (tag instanceof LauncherConfig.PcPane) {
                LauncherConfig.PcPane pp = (LauncherConfig.PcPane) tag;
                boolean connected = OmarchyLink.connected();
                long since = pp.at > 0 ? pp.at : c.widgetsAt;
                long age = since > 0 ? System.currentTimeMillis() / 1000 - since : 0;
                // Connected: dim after ttl, hide after 2 x ttl (PROTOCOL.md). Not connected: last data marked
                // "not connected", or hidden (same setting as AI credits).
                boolean hide = connected ? age > 2L * pp.ttl : "hide".equals(c.aiOffline);
                v.setVisibility(hide ? View.GONE : View.VISIBLE);
                body.setAlpha(connected && age > pp.ttl ? 0.55f : 1f);
                body.setText(pcPaneText(pp, !connected, c.accent));
                continue;
            }
            String id = (String) tag;
            if ("agents".equals(id)) updateAgentsPane(v, c);
            else if ("clock".equals(id)) body.setText(clockText());
            else if ("ai".equals(id)) {
                // PC off: keep the last numbers, marked stale, or hide the pane (setting); never wait for the PC.
                boolean hide = "hide".equals(c.aiOffline) && pcOffline(c);
                v.setVisibility(hide ? View.GONE : View.VISIBLE);
                body.setText(aiText());
            }
            else if ("sysinfo".equals(id)) body.setText(sysinfoText());
            else body.setText(weatherText());
        }
    }

    // ---- agents pane (PROTOCOL.md §6 Agents) ---------------------------------------------------------

    private View agentsFit;
    private boolean buildingPanes;

    /** Shows/hides the agents pane; a change re-runs the pane fit (rare: data arrives, PC goes away). */
    private void setAgentsVisible(View v, boolean show) {
        int want = show ? View.VISIBLE : View.GONE;
        if (v.getVisibility() == want) return;
        v.setVisibility(want);
        if (!buildingPanes) handler.post(() -> {
            if (resumed && v.getParent() != null) buildPanes();
        });
    }

    /** The agents pane, when it is in the pane rows. */
    private View agentsPane() {
        for (View v : paneViews) if ("agents".equals(v.getTag())) return v;
        return null;
    }

    /** Focusable like a window: the Hyprland active border when focused; OK opens the full agent view. */
    private void setupAgentsPane(View v) {
        v.setFocusable(true);
        android.graphics.drawable.GradientDrawable border = new android.graphics.drawable.GradientDrawable();
        border.setColor(0);
        border.setStroke(Math.max(1, Math.round(LauncherConfig.get(this).borderDp * density)), Theme.cur().activeBorder);
        android.graphics.drawable.StateListDrawable fg = new android.graphics.drawable.StateListDrawable();
        fg.addState(new int[]{android.R.attr.state_focused}, border);
        v.setForeground(fg);
        v.setOnClickListener(x -> {
            Intent i = new Intent(this, AgentActivity.class);
            com.omarchytv.home.link.Agents a = com.omarchytv.home.link.Agents.parse(OmarchyLink.agents());
            if (!a.list.isEmpty()) i.putExtra(AgentActivity.EXTRA_AGENT, a.list.get(0).id);   // PC order: needs input first
            open(i);
        });
    }

    private void updateAgentsPane() {
        View v = agentsPane();
        if (v != null) updateAgentsPane(v, LauncherConfig.get(this));
    }

    /** Ticks the elapsed timers once a second, only while home is resumed and an agent is working. */
    private final Runnable agentsTick = this::updateAgentsPane;

    /**
     * One line per agent: status glyph, name, current step, elapsed. Hidden while the PC reports the monitor off
     * or no agents; with the PC off it says "not connected" (only on TVs that ever got agent data), and nothing
     * runs: no timer, no request.
     */
    private void updateAgentsPane(View v, LauncherConfig c) {
        handler.removeCallbacks(agentsTick);
        TextView body = v.findViewById(R.id.paneBody);
        boolean connected = OmarchyLink.connected();
        if (!connected) {
            setAgentsVisible(v, c.agentsAt > 0);
            android.text.SpannableString off = new android.text.SpannableString(getString(R.string.link_offline));
            off.setSpan(new android.text.style.ForegroundColorSpan(Theme.m(0xFFFF9E64)), 0, off.length(), 0);
            body.setText(off);
            return;
        }
        com.omarchytv.home.link.Agents a = com.omarchytv.home.link.Agents.parse(OmarchyLink.agents());
        boolean show = a.enabled && !a.list.isEmpty();
        if (!show && v.hasFocus()) findViewById(R.id.btnSettings).requestFocus();
        setAgentsVisible(v, show);
        if (!show) return;
        body.setText(agentsText(a, System.currentTimeMillis()));
        if (resumed && a.anyLive()) handler.postDelayed(agentsTick, 1000);
    }

    CharSequence agentsText(com.omarchytv.home.link.Agents a, long now) {
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        int nameW = 10;   // fixed columns: the pane keeps its size while agents come and go
        for (com.omarchytv.home.link.Agents.Agent g : a.list) {
            if (b.length() > 0) b.append('\n');
            int line = b.length();
            b.append(styled(AgentUi.glyph(g.status), AgentUi.color(g.status))).append(' ');
            String name = String.format(java.util.Locale.ROOT, "%-" + nameW + "s", AgentUi.clip(g.name, 10));
            b.append(styled(name, LauncherConfig.get(this).accent)).append(' ');
            String step = g.needsInput ? getString(R.string.agent_needs_input)
                    : g.step.isEmpty() ? AgentUi.word(this, g.status) : g.step;
            String el = com.omarchytv.home.link.Agents.span(a.elapsedNow(g, now));
            b.append(String.format(java.util.Locale.ROOT, "%-24s", AgentUi.clip(step, 24))).append(' ');
            int d = b.length();
            b.append(String.format(java.util.Locale.ROOT, "%3s", el));
            b.setSpan(new android.text.style.ForegroundColorSpan(Theme.cur().fgDim), d, b.length(), 0);
            if (g.needsInput) {
                // Needs input: the whole row in the selection colour, text in yellow (Omarchy urgent).
                b.setSpan(new android.text.style.BackgroundColorSpan(Theme.withAlpha(Theme.cur().yellow, 0.16f)), line, b.length(), 0);
                b.setSpan(new android.text.style.ForegroundColorSpan(Theme.cur().yellow), line + 2 + name.length() + 1, d, 0);
            }
        }
        return b;
    }

    /** "label  value" lines, text only (never HTML); label in the accent like the AI credit names. */
    private CharSequence pcPaneText(LauncherConfig.PcPane pp, boolean offline, int accent) {
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        if (offline) {
            b.append(getString(R.string.link_offline));
            b.setSpan(new android.text.style.ForegroundColorSpan(Theme.m(0xFFFF9E64)), 0, b.length(), 0);
        }
        int w = 0;
        for (String[] l : pp.lines) w = Math.max(w, Math.min(8, l[0].length()));
        for (String[] l : pp.lines) {
            if (b.length() > 0) b.append('\n');
            String label = l[0].length() > 8 ? l[0].substring(0, 8) : l[0];
            String value = l[1].length() > 24 ? l[1].substring(0, 23) + "…" : l[1];
            if (w > 0) {
                b.append(styled(String.format(java.util.Locale.ROOT, "%-" + w + "s", label), accent)).append(' ');
            }
            b.append(value);
        }
        return b;
    }

    /** Ticks on the minute boundary so the big pane clock never lags the bar clock. */
    private static long untilNextMinute() {
        return MINUTE_MS - System.currentTimeMillis() % MINUTE_MS + 50;
    }

    private CharSequence clockText() {
        java.util.Date now = new java.util.Date();
        String time = android.text.format.DateFormat.getTimeFormat(this).format(now);
        String date = android.text.format.DateFormat.format(getString(R.string.date_format), now).toString();
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(time);
        // Big clock: 4x the pane text (48sp at UI size medium; sp, so it follows UI size and text size).
        b.setSpan(new android.text.style.RelativeSizeSpan(4f), 0, b.length(), 0);
        b.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, b.length(), 0);
        b.setSpan(new android.text.style.ForegroundColorSpan(LauncherConfig.get(this).accent), 0, b.length(), 0);
        b.append('\n').append(date);
        return b;
    }

    /** Older than this, an AI entry is marked stale (the PC plugin pushes every few minutes while running). */
    private static final long AI_STALE_MS = 60 * 60_000L;

    /**
     * AI credits pane: entries pushed by the Omarchy Linux plugin into omarchytv.json "ai". Display only:
     * the TV never sees API keys and never calls any AI provider.
     */
    private CharSequence aiText() {
        LauncherConfig cfg = LauncherConfig.get(this);
        if (cfg.ai.isEmpty()) {
            android.text.SpannableString e = new android.text.SpannableString(str(R.string.ai_empty));
            e.setSpan(new android.text.style.ForegroundColorSpan(Theme.m(0xFF7F88B3)), 0, e.length(), 0);
            return e;
        }
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        long now = System.currentTimeMillis();
        boolean offline = pcOffline(cfg);
        if (offline) {
            b.append(getString(R.string.ai_pc_offline));   // last data stays below, unchanged
            b.setSpan(new android.text.style.ForegroundColorSpan(Theme.m(0xFFFF9E64)), 0, b.length(), 0);
        }
        int nameW = 4;
        for (LauncherConfig.AiEntry a : cfg.ai) nameW = Math.max(nameW, Math.min(8, a.name.length()));
        for (int i = 0; i < cfg.ai.size(); i++) {
            LauncherConfig.AiEntry a = cfg.ai.get(i);
            if (b.length() > 0) b.append('\n');
            String name = a.name.length() > 8 ? a.name.substring(0, 8) : a.name;
            b.append(styled(String.format(java.util.Locale.ROOT, "%-" + nameW + "s", name), cfg.accent)).append(' ');
            double f = a.fraction();
            if (f >= 0) {
                int filled = (int) Math.round(f * 5);
                int color = f >= 0.9 ? Theme.m(0xFFF7768E) : f >= 0.7 ? Theme.m(0xFFFF9E64) : Theme.m(0xFF9ECE6A);
                b.append('[');
                int s0 = b.length();
                for (int k = 0; k < 5; k++) b.append(k < filled ? '■' : '□');
                b.setSpan(new android.text.style.ForegroundColorSpan(color), s0, b.length(), 0);
                b.append("] ").append(String.format(java.util.Locale.ROOT, "%3d%%", Math.round(f * 100)));
            } else if (f == -2) {
                // A number the PC could not read (JSON null): "?", never 0.
                b.append(a.usedUnknown() ? "?" : amount(a.used, a.unit) + "/?");
            } else {
                // No limit known: show the amount instead of a bar.
                b.append(amount(a.used, a.unit));
            }
            int dim = b.length();
            if (a.resetsAt > now) b.append(" ↻").append(shortSpan(a.resetsAt - now));
            if (a.updatedAt > 0 && now - a.updatedAt > AI_STALE_MS) {
                b.setSpan(new android.text.style.ForegroundColorSpan(Theme.m(0xFF7F88B3)), dim, b.length(), 0);
                int st = b.length();
                b.append(' ').append(getString(R.string.ai_stale, shortSpan(now - a.updatedAt)));
                b.setSpan(new android.text.style.ForegroundColorSpan(Theme.m(0xFFFF9E64)), st, b.length(), 0);
            } else {
                b.setSpan(new android.text.style.ForegroundColorSpan(Theme.m(0xFF7F88B3)), dim, b.length(), 0);
            }
        }
        return b;
    }

    /** Credits came from a PC that is not connected now (aiAt known). In-memory check, no I/O, no waiting. */
    private static boolean pcOffline(LauncherConfig cfg) {
        return !cfg.ai.isEmpty() && cfg.aiAt > 0 && !OmarchyLink.connected();
    }

    static String amountText(double v, String unit) {
        return amount(v, unit);
    }

    private static String amount(double v, String unit) {
        if ("$".equals(unit)) return String.format(java.util.Locale.ROOT, "$%.2f", v);
        if ("tokens".equals(unit)) {
            if (v >= 1e6) return String.format(java.util.Locale.ROOT, "%.1fM", v / 1e6);
            if (v >= 1e3) return String.format(java.util.Locale.ROOT, "%.0fk", v / 1e3);
            return String.valueOf((long) v);
        }
        String n = v == Math.rint(v) ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.1f", v);
        return unit.isEmpty() ? n : n + " " + unit;
    }

    /** "45m", "5h", "3d". */
    private static String shortSpan(long ms) {
        long m = ms / 60_000L;
        if (m < 60) return Math.max(1, m) + "m";
        if (m < 48 * 60) return (m / 60) + "h";
        return (m / 1440) + "d";
    }

    /** fastfetch-style key/value list; cheap reads only (no shell, no polling). */
    private CharSequence sysinfoText() {
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        int key = LauncherConfig.get(this).accent;
        addKv(b, key, str(R.string.sys_os), "Android " + android.os.Build.VERSION.RELEASE);
        addKv(b, key, str(R.string.sys_host), android.os.Build.MODEL);
        long up = android.os.SystemClock.elapsedRealtime() / 60_000L;
        addKv(b, key, str(R.string.sys_uptime), getString(R.string.uptime_fmt, (int) (up / 60), (int) (up % 60)));
        android.app.ActivityManager am = getSystemService(android.app.ActivityManager.class);
        if (am != null) {
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            addKv(b, key, str(R.string.sys_memory), getString(R.string.mib_fmt,
                    (int) ((mi.totalMem - mi.availMem) >> 20), (int) (mi.totalMem >> 20)));
        }
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        b.append(styled(str(R.string.sys_display), key)).append("  ").append(dm.widthPixels + "x" + dm.heightPixels);
        return b;
    }

    private static CharSequence styled(String s, int color) {
        android.text.SpannableString sp = new android.text.SpannableString(String.format("%-8s", s));
        sp.setSpan(new android.text.style.ForegroundColorSpan(color), 0, sp.length(), 0);
        return sp;
    }

    private static void addKv(android.text.SpannableStringBuilder b, int color, String k, String v) {
        b.append(styled(k, color)).append("  ").append(v).append('\n');
    }

    private CharSequence weatherText() {
        LauncherConfig cfg = LauncherConfig.get(this);
        if (Double.isNaN(cfg.weatherLat) || Double.isNaN(cfg.weatherLon)) return str(R.string.weather_unset);
        org.json.JSONObject w = Weather.cached(this);
        if (w == null) return str(R.string.weather_loading);
        org.json.JSONObject cur = w.optJSONObject("current");
        org.json.JSONObject daily = w.optJSONObject("daily");
        if (cur == null) return str(R.string.weather_offline);
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        if (!cfg.weatherPlace.isEmpty()) b.append(cfg.weatherPlace).append('\n');
        int start = b.length();
        b.append(String.valueOf(Weather.temp(cur.optDouble("temperature_2m"), cfg.tempF))).append(cfg.tempF ? "°F" : "°C");
        b.setSpan(new android.text.style.RelativeSizeSpan(1.8f), start, b.length(), 0);
        b.setSpan(new android.text.style.ForegroundColorSpan(cfg.accent), start, b.length(), 0);
        b.append("  ").append(str(Weather.describe(cur.optInt("weather_code"))));
        if (daily != null && daily.optJSONArray("temperature_2m_max") != null) {
            b.append('\n').append(getString(R.string.weather_minmax,
                    (int) Weather.temp(daily.optJSONArray("temperature_2m_min").optDouble(0), cfg.tempF),
                    (int) Weather.temp(daily.optJSONArray("temperature_2m_max").optDouble(0), cfg.tempF)));
        }
        b.append('\n').append(getString(R.string.weather_wind, (int) Math.round(cur.optDouble("wind_speed_10m"))));
        return b;
    }

    // ---- > tv ----------------------------------------------------------------------------------------

    private DockDecoration dock;
    private TvAdapter tv;
    /** 0 dock, 1 apps grid, 2 tv: which section prompt blinks. */
    private int focusSection;

    private void reloadApps() {
        adapter.reload();
        dock.appCount = adapter.getItemCount();
        applyShortcuts();
    }

    /** "> omarchy": PC-pushed shortcuts (link.shortcuts), hidden when none or switched off. */
    private void applyShortcuts() {
        LauncherConfig cfg = LauncherConfig.get(this);
        sc.set(cfg.shortcutsOn ? cfg.shortcuts : java.util.Collections.<LauncherConfig.Shortcut>emptyList());
        sc.setOffset(adapter.getItemCount());
        tv.setOffset(adapter.getItemCount() + sc.getItemCount());
    }

    @Override
    public void onShortcutFocused(int globalPos, View card) {
        onTileFocused(globalPos, card);
    }

    @Override
    public boolean onShortcutVertical(int index, int keyCode) {
        int apps = adapter.getItemCount(), n = sc.tiles();
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (index >= COLUMNS) focusGlobal(apps + 1 + index - COLUMNS);
            else if (apps > 0) focusTile(Math.min(((apps - 1) / COLUMNS) * COLUMNS + index, apps - 1));
            return true;
        }
        if (index / COLUMNS < (n - 1) / COLUMNS) {
            focusGlobal(apps + 1 + Math.min(index + COLUMNS, n - 1));
            return true;
        }
        int t = tv.tile();
        if (t >= 0) focusGlobal(tv.offset() + t);
        return true;   // last row: stay put
    }

    /**
     * app: launch it; view: ACTION_VIEW on an https / vnd.youtube / nflx URI (the server already filtered it);
     * pc: send the "shortcut" event, which the PC maps to a command from its own config. Never waits on the PC.
     */
    @Override
    public void onShortcutClick(LauncherConfig.Shortcut s) {
        switch (s.type) {
            case "app": {
                PackageManager pm = getPackageManager();
                Intent i = pm.getLeanbackLaunchIntentForPackage(s.pkg);
                if (i == null) i = pm.getLaunchIntentForPackage(s.pkg);
                if (i == null) Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                else open(i);
                break;
            }
            case "view": {
                String u = s.uri;
                if (!(u.startsWith("https://") || u.startsWith("vnd.youtube:") || u.startsWith("nflx://"))) return;
                Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u));
                if (!s.pkg.isEmpty()) {
                    i.setPackage(s.pkg);
                    if (i.resolveActivity(getPackageManager()) == null) i.setPackage(null);
                }
                open(i);
                break;
            }
            default:
                if (!OmarchyLink.connected()) {
                    Toast.makeText(this, R.string.link_offline, Toast.LENGTH_SHORT).show();
                    return;
                }
                OmarchyLink.shortcut(s.command);
                Toast.makeText(this, R.string.sc_sent, Toast.LENGTH_SHORT).show();
        }
    }

    private void applyTv() {
        tv.setOffset(adapter.getItemCount() + sc.getItemCount());
        tv.setShown(LauncherConfig.get(this).tvEnabled && liveTvIntent() != null);
    }

    /**
     * The TV's own Live TV app as its launcher would start it (Sony: com.sony.dtv.tvx), so it opens on the
     * last channel watched. Falls back to any Live TV handler (TvContract.Channels content). Null when the
     * TV has neither (e.g. an emulator or a box without a tuner).
     */
    /** Detects the installed Live TV package from config.xml (Sony, Sharp AQUOS, Philips TP Vision, Google TV, etc.). */
    static String liveTvPackage(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String> candidates = new ArrayList<>();
        String primary = c.getString(R.string.live_tv_package).trim();
        if (!primary.isEmpty()) candidates.add(primary);
        for (String pkg : c.getResources().getStringArray(R.array.live_tv_packages)) {
            pkg = pkg.trim();
            if (!pkg.isEmpty() && !candidates.contains(pkg)) candidates.add(pkg);
        }
        // Prefer an installed candidate with a direct Leanback or standard launch intent.
        for (String pkg : candidates) {
            if (pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null) {
                return pkg;
            }
        }
        for (String pkg : candidates) {
            try {
                pm.getPackageInfo(pkg, 0);
                return pkg;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return primary;
    }

    private Intent liveTvAppIntent() {
        PackageManager pm = getPackageManager();
        List<String> pkgs = new ArrayList<>();
        if (!liveTvPkg.isEmpty()) pkgs.add(liveTvPkg);
        for (String pkg : getResources().getStringArray(R.array.live_tv_packages)) {
            pkg = pkg.trim();
            if (!pkg.isEmpty() && !pkgs.contains(pkg)) pkgs.add(pkg);
        }
        for (String pkg : pkgs) {
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i == null) {
                Intent tvAct = new Intent(Intent.ACTION_MAIN).setClassName(pkg, pkg + ".TvActivity");
                if (tvAct.resolveActivity(pm) != null) i = tvAct;
            }
            if (i != null) return i;
        }
        return null;
    }

    private Intent systemTunerIntent() {
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim == null) return null;
        PackageManager pm = getPackageManager();
        String tunerId = null;
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getType() == TvInputInfo.TYPE_TUNER) {
                    boolean sys = info.getServiceInfo() != null
                            && info.getServiceInfo().applicationInfo != null
                            && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    if (tunerId == null || (sys && info.getId().compareTo(tunerId) < 0)) {
                        tunerId = info.getId();
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        if (tunerId == null) return null;
        Intent base = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel");
        if (!liveTvPkg.isEmpty()) {
            Intent scoped = new Intent(base).setPackage(liveTvPkg);
            if (scoped.resolveActivity(pm) != null) return scoped;
        }
        return base.resolveActivity(pm) != null ? base : null;
    }

    private Intent liveTvIntent() {
        Intent app = liveTvAppIntent();
        if (app != null) return app;
        Intent tuner = systemTunerIntent();
        if (tuner != null) return tuner;
        PackageManager pm = getPackageManager();
        Intent any = new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI);
        return any.resolveActivity(pm) != null ? any : null;
    }

    /** Focus any position in the grid (apps or the TV tile). */
    private void focusGlobal(int pos) {
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            if (card != null) {
                if (card.isFocused()) onTileFocused(pos, card);
                else card.requestFocus();
            }
            return;
        }
        grid.scrollToPosition(pos);
        grid.post(() -> {
            RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(pos);
            View card = h != null ? h.itemView.findViewById(R.id.card) : null;
            if (card != null) card.requestFocus();
        });
    }

    @Override
    public void onTvFocused(int globalPos, View card) {
        onTileFocused(globalPos, card);
    }

    @Override
    public void onTvClick() {
        // 1. Try dedicated OEM / Android TV Live TV app activity first (Sony, Sharp, Philips PlayTV, etc.).
        if (tryOpen(liveTvAppIntent())) return;
        // 2. If the user watches TV via an external digibox/STB on HDMI (e.g. Philips TPM171E in Issue #2),
        //    open the single connected HDMI/CEC source directly, or switch to Workspace 2 (Sources) if multiple.
        List<HomeV29.Source> hdmi = hdmiSources();
        if (hdmi.size() == 1) {
            hdmi.get(0).open.run();
            return;
        }
        if (hdmi.size() > 1) {
            if (home != null && LauncherConfig.get(this).workspaceShown(2)) setWorkspace(2);
            else showInputs();
            return;
        }
        // 3. Fall back to the system DVB/ATSC tuner passthrough URI or generic TIF channels URI.
        if (tryOpen(systemTunerIntent())) return;
        if (tryOpen(new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI))) return;
        Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onTvUp() {
        if (sc.tiles() > 0) {   // first tile of the last "> omarchy" row
            focusGlobal(adapter.getItemCount() + 1 + ((sc.tiles() - 1) / COLUMNS) * COLUMNS);
            return;
        }
        // Back to the first column of the last app row (the TV tile starts there).
        int count = adapter.getItemCount();
        if (count > 0) focusTile(((count - 1) / COLUMNS) * COLUMNS);
    }

    private AsciiMenu.Page tvPage() {
        LauncherConfig cfg = LauncherConfig.get(this);
        AsciiMenu.Page p = new AsciiMenu.Page(str(R.string.set_tv));
        String lOn = str(R.string.set_tv_show);
        p.add(lOn, str(cfg.tvEnabled ? R.string.on : R.string.off), r -> {
            boolean n = !cfg.tvEnabled;
            applyLive("tv", lOn, j -> {
                j.remove("channels");   // v2.2 channel list, no longer used
                org.json.JSONObject t = j.optJSONObject("tv");
                if (t == null) j.put("tv", t = new org.json.JSONObject());
                t.put("enabled", n);
            });
            return true;
        });
        if (liveTvIntent() == null) p.hint(str(R.string.no_inputs));
        return p;
    }

    // ---- v2.3: web apps, voice search, > ask --------------------------------------------------------

    private static final int REQ_VOICE_SEARCH = 11;

    private AsciiMenu.Page webAppsPage() {
        LauncherConfig cfg = LauncherConfig.get(this);
        AsciiMenu.Page p = new AsciiMenu.Page(str(R.string.web_apps));
        p.add(str(R.string.web_add), "+", r -> {
            handler.post(this::addWebAppDialog);
            return false;
        });
        for (LauncherConfig.WebApp w : cfg.webapps) {
            String host = android.net.Uri.parse(w.url).getHost();
            p.add(w.name + "  " + (host == null ? "" : host), str(R.string.web_remove_short), r -> {
                removeWebApp(w.id);
                return false;
            });
        }
        return p;
    }

    /** Writes the effective list once (so removing the default X sticks), then edits it. */
    private void editWebApps(java.util.function.Consumer<org.json.JSONArray> edit) {
        builtWith = LauncherConfig.update(this, j -> {
            org.json.JSONArray a = j.optJSONArray("webapps");
            if (a == null) {
                a = new org.json.JSONArray();
                for (LauncherConfig.WebApp w : LauncherConfig.get(this).webapps) {
                    a.put(new org.json.JSONObject().put("id", w.id).put("name", w.name).put("url", w.url).put("ua", w.ua));
                }
            }
            edit.accept(a);
            j.put("webapps", a);
        });
        reloadApps();
        applyTv();
    }

    private void removeWebApp(String id) {
        editWebApps(a -> {
            for (int i = a.length() - 1; i >= 0; i--) {
                org.json.JSONObject o = a.optJSONObject(i);
                if (o != null && id.equals(LauncherConfig.slug(o.optString("id", o.optString("name"))))) a.remove(i);
            }
        });
    }

    /** Name + URL with the TV keyboard (its mic does dictation). */
    private void addWebAppDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * density);
        box.setPadding(pad, pad, pad, 0);
        android.widget.EditText name = new android.widget.EditText(this);
        name.setHint(R.string.web_name_hint);
        name.setSingleLine(true);
        android.widget.EditText url = new android.widget.EditText(this);
        url.setHint("https://");
        url.setText("https://");
        url.setSingleLine(true);
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(name);
        box.addView(url);
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.web_add)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String n = name.getText().toString().trim(), u = url.getText().toString().trim();
                    if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
                    if (n.isEmpty() || u.length() < 12) return;
                    final String fn = n, fu = u;
                    editWebApps(a -> {
                        try {
                            a.put(new org.json.JSONObject().put("name", fn).put("url", fu).put("ua", "mobile"));
                        } catch (org.json.JSONException ignored) {
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private AsciiMenu.Page assistantPage() {
        LauncherConfig cfg = LauncherConfig.get(this);
        AsciiMenu.Page p = new AsciiMenu.Page(str(R.string.ask_settings));
        String lProv = str(R.string.ask_provider), lTts = str(R.string.ask_tts), lKey = str(R.string.voice_key),
                lAskKey = str(R.string.ask_key);
        String provName = "—";
        for (LauncherConfig.Provider pr : cfg.aiProviders) if (pr.id.equals(cfg.assistantProvider)) provName = pr.name;
        if ("—".equals(provName) && !cfg.aiProviders.isEmpty()) provName = cfg.aiProviders.get(0).name;
        p.add(lProv, cfg.aiProviders.isEmpty() ? str(R.string.ask_need_link_short) : provName, r -> {
            if (cfg.aiProviders.isEmpty()) return true;
            int cur = 0;
            for (int i = 0; i < cfg.aiProviders.size(); i++) if (cfg.aiProviders.get(i).id.equals(cfg.assistantProvider)) cur = i;
            String next = cfg.aiProviders.get((cur + 1) % cfg.aiProviders.size()).id;
            applyLive("assistant", lProv, j -> assistantJson(j).put("provider", next));
            return true;
        });
        p.add(lTts, str(cfg.assistantTts ? R.string.on : R.string.off), r -> {
            boolean n = !cfg.assistantTts;
            applyLive("assistant", lTts, j -> assistantJson(j).put("tts", n));
            return true;
        });
        // The mic / Search key: the Grok Bot companion (default) → Walker's > search → Google Assistant.
        p.add(lKey, str("companion".equals(cfg.voiceKey) ? R.string.voice_key_companion
                : "launcher".equals(cfg.voiceKey) ? R.string.voice_key_launcher : R.string.voice_key_assistant), r -> {
            String n = "companion".equals(cfg.voiceKey) ? "launcher" : "launcher".equals(cfg.voiceKey) ? "assistant" : "companion";
            applyLive("assistant", lKey, j -> j.put("voiceKey", n));
            return true;
        });
        String[] keys = {"none", "red", "green", "yellow", "blue"};
        p.add(lAskKey, cfg.askKey, r -> {
            String n = keys[0];
            for (int i = 0; i < keys.length; i++) if (keys[i].equals(cfg.askKey)) n = keys[(i + 1) % keys.length];
            final String fn = n;
            applyLive("assistant", lAskKey, j -> assistantJson(j).put("key", fn));
            return true;
        });
        return p;
    }

    static org.json.JSONObject assistantJson(org.json.JSONObject j) throws org.json.JSONException {
        org.json.JSONObject a = j.optJSONObject("assistant");
        if (a == null) {
            a = new org.json.JSONObject();
            j.put("assistant", a);
        }
        return a;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        LauncherConfig cfg = LauncherConfig.get(this);
        // Only when the user chose it: otherwise the key falls through to the system (Google Assistant).
        if (keyCode == KeyEvent.KEYCODE_SEARCH && voiceToCompanion() && movingPos < 0) {
            openCompanionChat(true);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_SEARCH && "launcher".equals(cfg.voiceKey) && movingPos < 0) {
            startVoiceSearch();
            return true;
        }
        int ask = "red".equals(cfg.askKey) ? KeyEvent.KEYCODE_PROG_RED : "green".equals(cfg.askKey) ? KeyEvent.KEYCODE_PROG_GREEN
                : "yellow".equals(cfg.askKey) ? KeyEvent.KEYCODE_PROG_YELLOW : "blue".equals(cfg.askKey) ? KeyEvent.KEYCODE_PROG_BLUE : -1;
        if (keyCode == ask) {
            open(new Intent(this, AskActivity.class));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** "> search": the system speech recognizer (Google's on Android TV), Italian or English. */
    private void startVoiceSearch() {
        Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, speechLanguage(this))
                .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, getString(R.string.search_prompt));
        try {
            startActivityForResult(i, REQ_VOICE_SEARCH);
        } catch (ActivityNotFoundException e) {
            typedSearch();   // no recognizer: type instead (the TV keyboard may still offer its mic)
        }
    }

    static String speechLanguage(Context c) {
        String lang = c.getResources().getConfiguration().getLocales().get(0).getLanguage();
        return "it".equals(lang) ? "it-IT" : "de".equals(lang) ? "de-DE" : "en-US";
    }

    private void typedSearch() {
        android.widget.EditText q = new android.widget.EditText(this);
        q.setSingleLine(true);
        new android.app.AlertDialog.Builder(this).setTitle(R.string.search_entry).setView(q)
                .setPositiveButton(android.R.string.ok, (d, w) -> runSearch(java.util.Collections.singletonList(q.getText().toString())))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VOICE_SEARCH || resultCode != RESULT_OK || data == null) return;
        List<String> alts = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
        if (walkerVoice && walker != null && alts != null && !alts.isEmpty()) {
            walkerVoice = false;
            walker.setQuery(alts.get(0));
            return;
        }
        walkerVoice = false;
        if (alts != null && !alts.isEmpty()) runSearch(alts);
    }

    /** Fuzzy-matches apps, web apps and channels; launches a clear winner, else shows the matches. */
    private void runSearch(List<String> alts) {
        List<VoiceMatch.Candidate> cands = new ArrayList<>();
        for (int i = 0; i < adapter.getItemCount(); i++) {
            final int pos = i;
            cands.add(new VoiceMatch.Candidate(adapter.labelAt(i), () -> adapter.launchAt(pos), adapter.labelAt(i)));
        }
        if (LauncherConfig.get(this).tvEnabled) {
            LauncherConfig vc = LauncherConfig.get(this);
            if (vc.shortcutsOn) for (LauncherConfig.Shortcut x : vc.shortcuts) cands.add(new VoiceMatch.Candidate(x.label, () -> onShortcutClick(x), x.label));
            cands.add(new VoiceMatch.Candidate(str(R.string.tv_tile), this::onTvClick, "tv", "televisione", "live tv", "fernsehen", "tivù"));
        }
        List<VoiceMatch.Candidate> best = VoiceMatch.rank(alts, cands);
        if (!best.isEmpty() && best.get(0).score >= 0.85 && (best.size() == 1 || best.get(1).score < best.get(0).score - 0.08)) {
            best.get(0).action.run();
            return;
        }
        if (menu != null && menu.isShowing()) menu.dismiss();
        menu = new AsciiMenu(this, 420);
        AsciiMenu.Page p = new AsciiMenu.Page("search: " + alts.get(0));
        if (best.isEmpty()) p.add(str(R.string.search_none), null, r -> true);
        for (VoiceMatch.Candidate c : best) {
            p.add(c.label, String.format(java.util.Locale.ROOT, "%d%%", Math.round(c.score * 100)), r -> {
                c.action.run();
                return false;
            });
        }
        p.add(str(R.string.search_again), "[mic]", r -> {
            handler.post(this::startVoiceSearch);
            return false;
        });
        menu.showBelow(btnMenu, p);
    }
}
