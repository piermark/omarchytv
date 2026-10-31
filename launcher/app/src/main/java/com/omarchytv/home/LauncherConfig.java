package com.omarchytv.home;

import android.content.Context;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Launcher settings, read from an optional local JSON file that overrides res/values/config.xml.
 * File: <external files dir>/omarchytv.json, i.e. /sdcard/Android/data/com.omarchytv.home/files/omarchytv.json
 * (writable over adb without root; a future LAN companion such as "Omarchy Link" writes the same file).
 * The home screen re-reads it on every resume when its modification time changed. Schema: see PLUGINS.md.
 */
final class LauncherConfig {

    static final String FILE_NAME = "omarchytv.json";

    final List<String> dock;
    /** Saved order of the non-dock apps (package names), written by move mode. */
    final List<String> order;
    final List<String> hidden;
    /** "auto" (follow the TV), "en", "it", "de". */
    final String language;
    /** Info panes shown top right: "clock", "sysinfo", "weather". */
    final List<String> panes;
    final double weatherLat, weatherLon;
    /** AI usage pushed by the PC plugin (omarchytv.json "ai"); the TV never holds API keys. */
    final List<AiEntry> ai;
    /** Omarchy-style web apps (omarchytv.json "webapps"); default: X. */
    final List<WebApp> webapps;
    /** "assistant" (default: the mic / Search key stays with Google Assistant) or "launcher" (opens > search on Home). */
    final String voiceKey;
    /** Remote key bindings (omarchytv.json "keys"), keyed by Android keycode; see {@link #keyBindings}. */
    final java.util.Map<Integer, KeyBinding[]> keys;

    /** What one press type of a remote key does: type is "app" (package), "intent" (URI string) or "action" (id). */
    static final class KeyBinding {
        static final String APP = "app", INTENT = "intent", ACTION = "action";
        final String type, value;

        KeyBinding(String type, String value) {
            this.type = type;
            this.value = value;
        }
    }

    /** Press types, in the order of the KeyBinding arrays: 1, 2, 3 quick presses, then a long press. */
    static final String[] KEY_PRESSES = {"single", "double", "triple", "long"};
    static final int LONG_PRESS = 3;
    /** OmarchyTV actions a key can run (see MainActivity.runKeyAction). */
    static final String[] KEY_ACTIONS = {"walker", "voice", "home", "ask", "menu", "screensaver", "next-theme",
            "workspace-1", "workspace-2", "workspace-3", "workspace-4", "workspace-5"};

    /** Maps a key name without the KEYCODE_ prefix to an Android keycode; 0 or less = unknown. */
    interface KeyNames {
        int code(String name);
    }

    /**
     * Parses omarchytv.json "keys": {"BUTTON_12": {"single": {"app": "pkg"}, "double": {"action": "walker"}, ...}}.
     * Unknown key names, bindings that are not exactly one of app / intent / action, and unknown action ids are
     * dropped; a key with nothing left is not in the result (so the system keeps handling it).
     */
    static java.util.Map<Integer, KeyBinding[]> keyBindings(JSONObject keys, KeyNames names) {
        java.util.Map<Integer, KeyBinding[]> out = new java.util.LinkedHashMap<>();
        if (keys == null) return out;
        for (java.util.Iterator<String> it = keys.keys(); it.hasNext(); ) {
            String name = it.next();
            JSONObject presses = keys.optJSONObject(name);
            int code = names.code(name.trim());
            if (presses == null || code <= 0) continue;
            KeyBinding[] b = out.containsKey(code) ? out.get(code) : new KeyBinding[KEY_PRESSES.length];
            boolean any = false;
            for (int i = 0; i < KEY_PRESSES.length; i++) {
                KeyBinding kb = keyBinding(presses.optJSONObject(KEY_PRESSES[i]));
                if (kb != null) b[i] = kb;
                any |= b[i] != null;
            }
            if (any) out.put(code, b);
        }
        return out;
    }

    private static KeyBinding keyBinding(JSONObject o) {
        if (o == null) return null;
        KeyBinding found = null;
        for (String type : new String[]{KeyBinding.APP, KeyBinding.INTENT, KeyBinding.ACTION}) {
            if (!o.has(type)) continue;
            if (found != null) return null;   // two kinds at once: ambiguous
            Object v = o.opt(type);
            String s = v instanceof String ? ((String) v).trim() : "";
            boolean ok = !s.isEmpty() && (!type.equals(KeyBinding.INTENT) || s.startsWith("intent:"))
                    && (!type.equals(KeyBinding.ACTION) || Arrays.asList(KEY_ACTIONS).contains(s));
            if (!ok) return null;
            found = new KeyBinding(type, s);
        }
        return found;
    }

    /** True if some press count above {@code count} (1..3) has a binding, i.e. it is worth waiting for another press. */
    static boolean keyWaitsForMore(KeyBinding[] b, int count) {
        for (int c = count + 1; c <= 3; c++) if (b[c - 1] != null) return true;
        return false;
    }

    /** The binding to run for {@code count} quick presses (1..3): that one, else the highest bound count below it. */
    static KeyBinding keyPressBinding(KeyBinding[] b, int count) {
        for (int c = Math.min(count, 3); c >= 1; c--) if (b[c - 1] != null) return b[c - 1];
        return null;
    }

    static final class WebApp {
        final String id, name, url, icon, ua;

        WebApp(String id, String name, String url, String icon, String ua) {
            this.id = id;
            this.name = name;
            this.url = url;
            this.icon = icon;
            this.ua = ua;
        }
    }

    /** Built-in AI & Grok Bot WebApps (like Omarchy desktop webapps, zero API cost, persistent login in :web). */
    static final WebApp[] BUILTIN_AI_WEBAPPS = new WebApp[]{
            new WebApp("grok-bot", "Grok Bot", "https://grok.com", "G", "desktop"),
            new WebApp("grok", "Grok (X)", "https://x.com/i/grok", "X", "desktop"),
            new WebApp("chatgpt", "ChatGPT", "https://chatgpt.com", "C", "desktop"),
            new WebApp("claude", "Claude", "https://claude.ai", "A", "desktop"),
            new WebApp("hermes", "Hermes", "https://hermes.nousresearch.com", "H", "desktop"),
    };

    static WebApp findBuiltinAiWebApp(String idOrName) {
        if (idOrName == null) return null;
        String k = idOrName.trim().toLowerCase(java.util.Locale.ROOT);
        if (k.startsWith("web:")) k = k.substring(4);
        for (WebApp w : BUILTIN_AI_WEBAPPS) {
            if (w.id.equalsIgnoreCase(k) || w.name.equalsIgnoreCase(k)) return w;
        }
        return null;
    }

    /** Ensures a WebApp exists in {@code root.webapps} and optionally pins {@code web:<id>} in {@code root.dock}. */
    static void ensureWebApp(JSONObject root, WebApp w, boolean pinToDock) throws Exception {
        JSONArray wa = root.optJSONArray("webapps");
        if (wa == null) {
            wa = new JSONArray();
            wa.put(new JSONObject().put("id", "x").put("name", "X").put("url", "https://x.com").put("ua", "mobile"));
            root.put("webapps", wa);
        }
        boolean found = false;
        for (int i = 0; i < wa.length(); i++) {
            JSONObject o = wa.optJSONObject(i);
            if (o != null && w.id.equals(slug(o.optString("id", o.optString("name"))))) {
                found = true;
                break;
            }
        }
        if (!found) {
            JSONObject o = new JSONObject().put("id", w.id).put("name", w.name).put("url", w.url).put("ua", w.ua);
            if (w.icon != null && !w.icon.isEmpty()) o.put("icon", w.icon);
            wa.put(o);
        }
        if (pinToDock) {
            JSONArray dock = root.optJSONArray("dock");
            if (dock == null) {
                dock = new JSONArray();
                root.put("dock", dock);
            }
            String key = "web:" + w.id;
            boolean inDock = false;
            for (int i = 0; i < dock.length(); i++) {
                if (key.equals(dock.optString(i))) {
                    inDock = true;
                    break;
                }
            }
            if (!inDock) dock.put(key);
        }
    }

    static String slug(String s) {
        String out = s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return out.isEmpty() ? "app" : out;
    }

    /** "> ask" assistant: providers announced by the PC (link.aiProviders), chosen provider, TTS, optional colour key. */
    final List<Provider> aiProviders;
    final String assistantProvider, askKey;
    final boolean assistantTts;

    static final class Provider {
        final String id, name;

        Provider(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** A pane pushed by the PC (PUT /v1/widgets → link.panes). Text only. */
    static final class PcPane {
        final String id, title;
        final int ttl;
        final List<String[]> lines;
        /** Optional per-pane timestamp (unix seconds, link.panes[].at); 0 = use link.widgetsAt. */
        final long at;

        PcPane(String id, String title, int ttl, List<String[]> lines) {
            this(id, title, ttl, lines, 0L);
        }

        PcPane(String id, String title, int ttl, List<String[]> lines, long at) {
            this.id = id;
            this.title = title;
            this.ttl = ttl;
            this.lines = lines;
            this.at = at;
        }
    }

    /** A shortcut pushed by the PC (link.shortcuts): type app | view | pc. */
    static final class Shortcut {
        final String id, label, type, pkg, uri, command;

        Shortcut(String id, String label, String type, String pkg, String uri, String command) {
            this.id = id;
            this.label = label;
            this.type = type;
            this.pkg = pkg;
            this.uri = uri;
            this.command = command;
        }
    }

    final List<PcPane> pcPanes;
    final List<Shortcut> shortcuts;
    /** Unix seconds of the last PUT /v1/widgets (link.widgetsAt), 0 if unknown. */
    final long widgetsAt;
    /** omarchytv.json "pcWidgets": {"panes": true, "shortcuts": true, "overflow": "row2"|"skip", "hidden": [ids]}. */
    final boolean pcPanesOn, shortcutsOn;
    final String paneOverflow;
    final List<String> hiddenPcPanes;

    /** Omarchy Link server on/off (omarchytv.json "link": {"enabled": false}); default on. */
    final boolean linkEnabled;
    /** AI pane when no PC is connected and the data is old: "stale" (dim, "pc offline · 3h ago") or "hide". */
    final String aiOffline;
    /** Unix seconds of the last PUT /v1/ai (omarchytv.json "aiAt"), 0 if never. */
    final long aiAt;
    /** Everything except the keys the Link service rewrites often; equal = no rebuild needed. */
    final String restSig;
    /** Everything the panes/menus show from the PC except the agent list (which updates in place). */
    final String dataSig;
    /** Unix seconds of the last PUT /v1/agents (link.agentsAt), 0 = this TV never saw the agent monitor. */
    final long agentsAt;

    /** "> tv" section with the single Live TV tile (omarchytv.json "tv": {"enabled": true}). */
    final boolean tvEnabled;

    /** Workspace names for "workspaces": {"order"}, index + 1 = the workspace (1 home · 2 tv · 3 apps · 4 pc · 5 system). */
    static final String[] WORKSPACE_IDS = {"home", "tv", "apps", "pc", "system"};

    /**
     * Workspaces in bar order (omarchytv.json "workspaces": {"order": ["home", "apps", "pc", "system"]}): the bar
     * numbers them 1..n by position, the 1-5 keys and CH+/CH− follow it, and the ones left out are hidden.
     * Default all five in their usual order; home is always kept (put first when missing).
     */
    final List<Integer> workspaceOrder;

    /** The bar shows workspace names instead of numbers ("workspaces": {"labels": "names"}; default "numbers"). */
    final boolean workspaceNamesInBar;

    /** Renamed workspaces ("workspaces": {"names": {"pc": "cachy"}}), by workspace number; the rest use their id. */
    private final java.util.Map<Integer, String> workspaceNames;

    /** omarchytv.json "barWeather": true puts icon + temperature in the bar, left of the status icons (default off). */
    final boolean barWeather;

    /**
     * Panes the TV fetches itself (omarchytv.json "remotePanes": {"url": "https://…", "refreshSec": 300}): same
     * body as PUT /v1/widgets, panes only. Empty url (default) = off. See {@link RemotePanes}.
     */
    final String remotePanesUrl;
    final int remotePanesRefreshSec;

    /**
     * AI-pane sources the PC can mirror (omarchytv.json "aiSources", pushed with PUT /v1/ai). Present only when
     * the PC accepts the "ai_sources" event; the TV then shows one switch per source. Ids are whatever the PC sends.
     */
    final List<AiSource> aiSources;

    static final class AiSource {
        final String id, name;
        final boolean enabled, available;

        AiSource(String id, String name, boolean enabled, boolean available) {
            this.id = id;
            this.name = name;
            this.enabled = enabled;
            this.available = available;
        }
    }

    private static double num(JSONObject o, String key, double fallback) {
        if (!o.has(key)) return fallback;
        if (o.isNull(key)) return Double.NaN;
        return o.optDouble(key, Double.NaN);
    }

    static final class AiEntry {
        final String name, unit;
        final double used, limit;
        /** Epoch ms, or 0 when missing. */
        final long resetsAt, updatedAt;

        AiEntry(String name, double used, double limit, String unit, long resetsAt, long updatedAt) {
            this.name = name;
            this.used = used;
            this.limit = limit;
            this.unit = unit;
            this.resetsAt = resetsAt;
            this.updatedAt = updatedAt;
        }

        /** PC's own one-line text (mirrored bar widgets), or "". */
        String detail = "";
        /** 0..1 left, from the PC; NaN when not sent. */
        double percentRemaining = Double.NaN;

        /** "used" is JSON null / missing: show "?" (never 0). */
        boolean usedUnknown() {
            return Double.isNaN(used);
        }

        /** 0..1, -1 when there is no limit to compare with, or -2 when a number is unknown ("?"). */
        double fraction() {
            if (!Double.isNaN(percentRemaining)) return Math.max(0, Math.min(1, 1 - percentRemaining));
            if (Double.isNaN(used)) return -2;
            if ("%".equals(unit)) return Double.isNaN(limit) ? Math.max(0, Math.min(1, used / 100)) : Math.max(0, Math.min(1, used / (limit > 0 ? limit : 100)));
            if (Double.isNaN(limit)) return -2;
            return limit > 0 ? Math.max(0, Math.min(1, used / limit)) : -1;
        }
    }

    /** ISO-8601 with Z or +hh:mm offset, optional fraction, for API 24-25 (no java.time). 0 if unparseable. */
    static long isoCompat(String s) {
        try {
            String t = s.trim();
            int ms = 0;
            int dot = t.indexOf('.', 19);
            if (dot > 0) {
                int end = dot + 1;
                while (end < t.length() && Character.isDigit(t.charAt(end))) end++;
                ms = Integer.parseInt((t.substring(dot + 1, end) + "000").substring(0, 3));
                t = t.substring(0, dot) + t.substring(end);
            }
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US);
            java.util.Date d = f.parse(t);
            return d == null ? 0L : d.getTime() + ms;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Epoch seconds, epoch ms, or an ISO-8601 string ("2026-10-19T00:10:00Z", with offset too). 0 if unknown. */
    static long parseTime(Object o) {
        try {
            if (o instanceof Number) {
                long v = ((Number) o).longValue();
                return v < 100_000_000_000L ? v * 1000L : v;
            }
            if (o instanceof String && !((String) o).isEmpty()) {
                String s = ((String) o).trim();
                if (s.matches("\\d+")) return parseTime(Long.parseLong(s));
                if (android.os.Build.VERSION.SDK_INT >= 26) return java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli();
                return isoCompat(s);   // java.time is missing on Android 7.x (minSdk 24, issue #56)
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }
    final String weatherPlace;
    /** "tempUnit": "C" (default) or "F"; Open-Meteo answers stay in °C, converted only for display. */
    final boolean tempF;
    final String wallpaper;          // "omarchy", "procedural", or an absolute image path
    /** Opt-in PC active wallpaper sync (default false: use bundled MIT theme wallpaper / Liftoff Sunset fallback). */
    final boolean syncPcWallpaper;
    /** v2.13: play animated MP4 intro (e.g. cumulus, last-horizon) before settling on still wallpaper. */
    final boolean wallpaperIntro;
    /** v2.13: rotate among animated themes on Home resume/boot (default true; locked false on manual pick). */
    final boolean randomTheme;
    /** v2.13: rotate among animated wallpapers/intros on Home resume/boot (default true; locked false on manual pick). */
    final boolean randomWallpaper;
    /** v2.13 Plugins Engine: SpaceX TV (com.sighmon.spacex-tv) & Grok Bot Companion (io.github.tcballard.grokbot-desktop). */
    final boolean pluginSpacexTv;
    final boolean pluginGrokbot;
    final String grokbotMessage;
    /** Experimental: the bot @tcballard's Grok Bot Desktop Companion shows on the PC, pushed by omarchy-link
     *  ("" = not mirrored). Shape/colour names from his bots.json; fill 0 = use the theme accent. */
    final String grokbotBotName, grokbotBotShape, grokbotBotMode, grokbotBotVersion;
    final int grokbotBotFill;
    /** v2.13 floating Grok Bot companion on the home screen: on/off, corner, extra offset (dp). */
    final boolean companionOverlay;
    final String companionCorner;
    final int companionOffsetX, companionOffsetY;
    /** v2.14 companion look, as on webOS/Tizen: shape / colour ("" follows the PC bot; else one of Tom Ballard's
     *  14 shapes / 11 colours), size (small, medium, large), name badge, animation (bob, blink, look around,
     *  sleep), and agent ("grokbot" = the PC companion when linked, "ask" = OmarchyTV's Ask). */
    final String companionShape, companionColor, companionSize, companionAgent;
    final boolean companionBadge, companionAnim;
    static final String[] ANIMATED_THEMES = {"last-horizon", "cumulus"};
    static final String[][] ANIMATED_INTROS = {
            {"last-horizon", "0", "wallpapers/last-horizon/01-eyes-wide.webp"},
            {"cumulus", "0", "wallpapers/cumulus/01-cloud-town-path.webp"},
            {"cumulus", "1", "wallpapers/cumulus/02-cottage.webp"},
            {"cumulus", "2", "wallpapers/cumulus/03-airship.webp"},
            {"cumulus", "3", "wallpapers/cumulus/04-lighthouse.webp"}
    };
    /** v2.13: OLED anti-burn-in pixel shift / periodic subtle drift. */
    final boolean antiBurnIn;
    /** v2.13: Ambient lighting / Ambilight mode: "off" (default), "theme", "hybrid", "video". */
    final String ambilightMode;
    /** v2.13: Optional WLED / Hyperion.NG bridge host on LAN. */
    final String ambilightHost;
    /** v2.13: Ambilight / hub brightness 0..100 (default 70). */
    final int ambilightBrightness;
    /** v2.13: Philips JointSpace host ("127.0.0.1" = this TV, or a LAN IP). */
    final String ambilightTvIp;
    /** v2.13: Screensaver mode ("logo" default or "aquarium"). */
    final String screensaverMode;
    /** v2.10: which of the current theme's official backgrounds (assets/wallpapers/<theme>/), like Omarchy next-background. */
    final int wallpaperBg;
    /** The resolved Omarchy theme (see Theme). */
    final Theme theme;
    final int accent;                // focus border start, walker border, active workspace underline
    final int accent2;               // focus border end, "TV" gradient end
    final String logo;               // "omarchy-tv" (default), "omarchy" or "original" (see Art)
    final String screensaverArt;     // defaults to logo
    final boolean screensaverClock;
    /** v2.13: procedural ST3 synth audio for Aquarium screensaver (default true). */
    final boolean saverSound;
    /** v2.9: screensaver.txt pushed by the PC (link.screensaver, PUT /v1/screensaver); "" = OMARCHY TV logo. */
    final String screensaverText;
    /** Logo width as a share of the screen: "small" 45%, "medium" 56%, "large" 68% (default). */
    final String screensaverSize;
    /** Effect animation speed: "slow", "normal" (default), "fast". */
    final String screensaverSpeed;
    /** Launcher UI size: "small", "medium" (default), "large". */
    final String uiSize;
    /** Hyprland-style geometry, adjustable in settings: text scale, tile scale, gaps (dp), border (dp). */
    final float fontScale, tileScale;
    /**
     * Layout → App size (geometry "appSize"): large / medium / small / compact = 4 / 5 / 6 / 7 tiles per row.
     * The base banner shrinks with the column count (198dp × 4 / columns, 16:9); tileScale stays a fine adjust.
     */
    final String appSize;
    final int columns;
    static final String[] APP_SIZES = {"large", "medium", "small", "compact"};
    final int gapDp, borderDp;
    /** Settings → Layout → Transparency: 0-60 % for pane / menu / header backgrounds (see Surfaces). */
    final int transparency;
    /** Layout → Wallpaper dimming: "off" (default: wallpaper untouched, like the Omarchy desktop), "light", "medium". */
    final String wallpaperDim;
    /** Screensaver: minutes of no remote input on the home screen before it shows (1/2/5/10/15). Legacy. */
    final int screensaverIdleMin;
    /** v2.10: seconds of no remote input on the home before the screensaver (60/150/300/600; 0 = never). Default 150 (Omarchy). */
    final int screensaverIdleSec;
    /** v2.10: animated OMARCHY TV logo on home open (default on). */
    final boolean logoAnim;
    /** v2.10: text tiles (terminal style) instead of banners (default off). */
    final boolean textTiles;
    /** v2.10: home widgets on/off: weather, pc, ai, deploys (default all on). */
    final boolean wWeather, wPc, wAi, wDeploys, wTerm;
    /** v2.10 "Disponi riquadri": panel id -> {cellX, cellY} offset on a 12x8 screen grid (empty = default layout). */
    final java.util.Map<String, int[]> panelPos;
    /** v2.10: Send to TV (cast) is off until switched on (omarchytv.json "cast": {"enabled": true}). */
    final boolean castEnabled;
    /** Screensaver: also offer it as the TV's system screensaver (DreamService components enabled). */
    final boolean screensaverSystem;
    /** Home prompt "<user>@<host>:~$" (omarchytv.json "prompt": {"user", "host"}), sanitised by {@link #promptPart}. */
    final String promptUser, promptHost;
    /** Widget ids in bar order, e.g. "omarchy.clock". Parsed and kept for the plugin contract; see PLUGINS.md. */
    final List<String> widgets;
    final long sourceModified;

    private static LauncherConfig cached;

    LauncherConfig(Context c, JSONObject j, long modified) {
        dock = strings(j, "dock", c != null ? Arrays.asList(c.getResources().getStringArray(R.array.dock_apps)) : Collections.<String>emptyList());
        hidden = strings(j, "hidden", c != null ? Arrays.asList(c.getResources().getStringArray(R.array.hidden_apps)) : Collections.<String>emptyList());
        order = strings(j, "order", Collections.<String>emptyList());
        language = j != null ? j.optString("language", "auto").trim() : "auto";
        panes = strings(j, "panes", c != null ? Arrays.asList(c.getResources().getStringArray(R.array.default_panes)) : Collections.<String>emptyList());
        List<AiEntry> aiList = new ArrayList<>();
        JSONArray aa = j != null ? j.optJSONArray("ai") : null;
        if (aa != null) {
            for (int i = 0; i < aa.length() && aiList.size() < 6; i++) {
                JSONObject e = aa.optJSONObject(i);
                if (e == null || e.optString("name").trim().isEmpty()) continue;
                // JSON null means "unknown" (PROTOCOL.md §6): keep it as NaN, shown as "?", never as 0.
                AiEntry en = new AiEntry(e.optString("name").trim(), num(e, "used", 0), num(e, "limit", 0),
                        e.optString("unit", "").trim(), parseTime(e.opt("resetsAt")), parseTime(e.opt("updatedAt")));
                en.detail = e.optString("detail", "").trim();
                if (e.has("percentRemaining") && !e.isNull("percentRemaining")) en.percentRemaining = e.optDouble("percentRemaining", Double.NaN);
                aiList.add(en);
            }
        }
        ai = Collections.unmodifiableList(aiList);
        List<AiSource> srcs = new ArrayList<>();
        JSONArray sa = j != null ? j.optJSONArray("aiSources") : null;
        if (sa != null) {
            for (int i = 0; i < sa.length() && srcs.size() < 16; i++) {
                JSONObject o = sa.optJSONObject(i);
                if (o == null || o.optString("id").isEmpty()) continue;
                srcs.add(new AiSource(o.optString("id"), o.optString("name", o.optString("id")), o.optBoolean("enabled"),
                        o.optBoolean("available", true)));
            }
        }
        aiSources = Collections.unmodifiableList(srcs);
        JSONObject lkc = j != null ? j.optJSONObject("link") : null;
        screensaverText = lkc != null ? lkc.optString("screensaver", "") : "";
        List<PcPane> pp = new ArrayList<>();
        JSONArray lpa = lkc != null ? lkc.optJSONArray("panes") : null;
        if (lpa != null) {
            for (int i = 0; i < lpa.length() && pp.size() < 8; i++) {
                JSONObject o = lpa.optJSONObject(i);
                if (o == null || o.optString("id").isEmpty()) continue;
                List<String[]> ls = new ArrayList<>();
                JSONArray la = o.optJSONArray("lines");
                for (int k = 0; la != null && k < la.length() && ls.size() < 8; k++) {
                    JSONObject l = la.optJSONObject(k);
                    if (l != null) ls.add(new String[]{l.optString("label", ""), l.optString("value", "")});
                }
                pp.add(new PcPane(o.optString("id"), o.optString("title", o.optString("id")), o.optInt("ttl", 180), ls, Math.max(0L, o.optLong("at", 0L))));
            }
        }
        pcPanes = Collections.unmodifiableList(pp);
        List<Shortcut> sc = new ArrayList<>();
        JSONArray lsa = lkc != null ? lkc.optJSONArray("shortcuts") : null;
        if (lsa != null) {
            for (int i = 0; i < lsa.length() && sc.size() < 16; i++) {
                JSONObject o = lsa.optJSONObject(i);
                JSONObject a = o != null ? o.optJSONObject("action") : null;
                if (a == null) continue;
                String type = a.optString("type");
                if (!type.equals("app") && !type.equals("view") && !type.equals("pc")) continue;
                sc.add(new Shortcut(o.optString("id"), o.optString("label", o.optString("id")), type,
                        a.optString("package", ""), a.optString("uri", ""), a.optString("command", "")));
            }
        }
        shortcuts = Collections.unmodifiableList(sc);
        widgetsAt = lkc != null ? lkc.optLong("widgetsAt", 0) : 0;
        JSONObject wg = j != null ? j.optJSONObject("pcWidgets") : null;
        pcPanesOn = wg == null || wg.optBoolean("panes", true);
        shortcutsOn = wg == null || wg.optBoolean("shortcuts", true);
        paneOverflow = wg != null && "skip".equals(wg.optString("overflow")) ? "skip" : "row2";
        hiddenPcPanes = strings(wg, "hidden", Collections.<String>emptyList());
        linkEnabled = lkc == null || lkc.optBoolean("enabled", true);
        JSONObject ap = j != null ? j.optJSONObject("aiPane") : null;
        aiOffline = ap != null && "hide".equals(ap.optString("offline")) ? "hide" : "stale";
        aiAt = j != null ? j.optLong("aiAt", 0) : 0;
        StringBuilder sig = new StringBuilder();
        if (j != null) {
            for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) {
                String k = it.next();
                if (k.equals("ai") || k.equals("aiAt") || k.equals("aiSources") || k.equals("keys")) continue;
                Object v = j.opt(k);
                if (k.equals("link") && v instanceof JSONObject) {
                    // only link.enabled matters for the screen; pcs/panes/providers change without a rebuild
                    v = ((JSONObject) v).opt("enabled");
                }
                sig.append(k).append('=').append(v).append('\u0001');
            }
        }
        restSig = sig.toString();
        JSONObject lk2 = j != null ? j.optJSONObject("link") : null;
        agentsAt = lk2 != null ? lk2.optLong("agentsAt", 0) : 0;
        StringBuilder ds = new StringBuilder();
        if (j != null) ds.append(j.opt("ai")).append('\u0001').append(j.opt("aiAt")).append('\u0001').append(j.opt("aiSources")).append('\u0001');
        if (lk2 != null) {
            for (java.util.Iterator<String> it = lk2.keys(); it.hasNext(); ) {
                String k = it.next();
                if (!k.equals("agents") && !k.equals("agentsAt")) ds.append(k).append('=').append(lk2.opt(k)).append('\u0001');
            }
        }
        dataSig = ds.toString();
        List<WebApp> web = new ArrayList<>();
        JSONArray wa2 = j != null ? j.optJSONArray("webapps") : null;
        if (wa2 == null) {
            web.add(new WebApp("grok-bot", "Grok Bot", "https://grok.com", "G", "desktop"));
            web.add(new WebApp("x", "X", "https://x.com", "", "mobile"));
        } else {
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (int i = 0; i < wa2.length() && web.size() < 50; i++) {
                JSONObject o = wa2.optJSONObject(i);
                if (o == null) continue;
                String url = o.optString("url").trim(), name = o.optString("name").trim();
                if (!(url.startsWith("https://") || url.startsWith("http://")) || name.isEmpty()) continue;
                String id = slug(o.optString("id", name));
                if (!ids.add(id)) continue;
                web.add(new WebApp(id, name, url, o.optString("icon", "").trim(), "desktop".equals(o.optString("ua")) ? "desktop" : "mobile"));
            }
        }
        webapps = Collections.unmodifiableList(web);
        List<Provider> provs = new ArrayList<>();
        JSONObject lk = j != null ? j.optJSONObject("link") : null;
        JSONArray pa = lk != null ? lk.optJSONArray("aiProviders") : null;
        if (pa != null) {
            for (int i = 0; i < pa.length() && provs.size() < 16; i++) {
                JSONObject o = pa.optJSONObject(i);
                if (o != null && !o.optString("id").isEmpty()) provs.add(new Provider(o.optString("id"), o.optString("name", o.optString("id"))));
            }
        }
        aiProviders = Collections.unmodifiableList(provs);
        JSONObject as = j != null ? j.optJSONObject("assistant") : null;
        assistantProvider = as != null ? as.optString("provider", "") : "";
        assistantTts = as != null && as.optBoolean("tts", false);
        askKey = as != null ? as.optString("key", "none") : "none";
        // v2.14: the voice key opens the Grok Bot companion by default; "launcher" (Walker, the v2.10 default) and
        // "assistant" (Google Assistant) stay selectable.
        String vk = j != null ? j.optString("voiceKey", "companion") : "companion";
        voiceKey = "assistant".equals(vk) || "launcher".equals(vk) ? vk : "companion";
        keys = Collections.unmodifiableMap(keyBindings(j != null ? j.optJSONObject("keys") : null,
                n -> android.view.KeyEvent.keyCodeFromString("KEYCODE_" + n)));
        JSONObject tv = j != null ? j.optJSONObject("tv") : null;
        tvEnabled = tv == null || tv.optBoolean("enabled", true);
        JSONObject workspaces = j != null ? j.optJSONObject("workspaces") : null;
        workspaceOrder = Collections.unmodifiableList(workspaceOrder(workspaces));
        workspaceNamesInBar = workspaces != null && "names".equals(workspaces.optString("labels"));
        workspaceNames = Collections.unmodifiableMap(workspaceNames(workspaces));
        JSONObject rp = j != null ? j.optJSONObject("remotePanes") : null;
        String rpUrl = rp != null ? rp.optString("url", "").trim() : "";
        remotePanesUrl = rpUrl.startsWith("https://") && rpUrl.length() <= 512 ? rpUrl : "";
        remotePanesRefreshSec = rp != null ? (int) clamp(rp.optInt("refreshSec", 300), 300, 86_400) : 300;
        barWeather = j != null && j.optBoolean("barWeather", false);
        JSONObject wx = j != null ? j.optJSONObject("weather") : null;
        weatherLat = wx != null ? wx.optDouble("lat", Double.NaN) : Double.NaN;
        weatherLon = wx != null ? wx.optDouble("lon", Double.NaN) : Double.NaN;
        weatherPlace = wx != null ? wx.optString("place", "") : "";
        tempF = j != null && "F".equalsIgnoreCase(j.optString("tempUnit", "C").trim());
        String defWp = c != null ? c.getString(R.string.wallpaper).trim() : "omarchy";
        wallpaper = j != null ? j.optString("wallpaper", defWp).trim() : defWp;
        syncPcWallpaper = j != null && j.optBoolean("syncPcWallpaper", false);
        wallpaperIntro = j == null || j.optBoolean("wallpaper_intro", j.optBoolean("wallpaperIntro", true));
        randomTheme = j == null || j.optBoolean("random_theme", j.optBoolean("randomTheme", true));
        randomWallpaper = j == null || j.optBoolean("random_wallpaper", j.optBoolean("randomWallpaper", true));
        JSONObject plObj = j != null ? j.optJSONObject("plugins") : null;
        pluginSpacexTv = plObj == null || plObj.optBoolean("spacexTv", true);
        pluginGrokbot = plObj == null || plObj.optBoolean("grokbot", true);
        grokbotMessage = plObj != null ? plObj.optString("grokbotMessage", "") : "";
        JSONObject gbBot = plObj != null ? plObj.optJSONObject("grokbotBot") : null;
        grokbotBotName = gbBot != null ? gbBot.optString("shortName", gbBot.optString("name", "")) : "";
        grokbotBotShape = gbBot != null ? gbBot.optString("shape", "bean") : "bean";
        grokbotBotMode = gbBot != null ? gbBot.optString("mode", "offline") : "offline";
        grokbotBotVersion = gbBot != null && !gbBot.isNull("version") ? gbBot.optString("version", "") : "";
        int gbFill = 0;
        try {
            String fs = gbBot != null ? gbBot.optString("fill", "") : "";
            if (fs.matches("#[0-9A-Fa-f]{6}")) gbFill = 0xFF000000 | Integer.parseInt(fs.substring(1), 16);
        } catch (NumberFormatException ignored) {
        }
        grokbotBotFill = gbFill;
        companionOverlay = pluginGrokbot && (plObj == null || plObj.optBoolean("companionOverlay", true));
        companionCorner = CompanionPlacement.corner(plObj != null ? plObj.optString("companionCorner", "bottom-right") : "bottom-right");
        companionOffsetX = CompanionPlacement.offset(plObj != null ? plObj.optInt("companionOffsetX", 0) : 0);
        companionOffsetY = CompanionPlacement.offset(plObj != null ? plObj.optInt("companionOffsetY", 0) : 0);
        String cShape = plObj != null ? plObj.optString("companionShape", "").trim() : "";
        companionShape = GrokbotAvatar.known(cShape) ? GrokbotAvatar.resolve(cShape) : "";
        String cColor = plObj != null ? plObj.optString("companionColor", "").trim() : "";
        int cColorIdx = GrokbotAvatar.colorIndex(cColor);
        companionColor = cColorIdx >= 0 ? GrokbotAvatar.COLOR_NAMES[cColorIdx] : "";
        companionSize = CompanionPlacement.size(plObj != null ? plObj.optString("companionSize", "medium") : "medium");
        companionBadge = plObj == null || plObj.optBoolean("companionBadge", true);
        companionAnim = plObj == null || plObj.optBoolean("companionAnim", true);
        companionAgent = plObj != null && "ask".equals(plObj.optString("companionAgent", "grokbot")) ? "ask" : "grokbot";
        antiBurnIn = j == null || j.optBoolean("anti_burn_in", j.optBoolean("antiBurnIn", true));
        String ambMode = j != null ? j.optString("ambilight_mode", j.optString("ambilightMode", "off")).trim() : "off";
        if (!"theme".equals(ambMode) && !"hybrid".equals(ambMode) && !"video".equals(ambMode) && !"cinema".equals(ambMode)) ambMode = "off";
        ambilightMode = ambMode;
        ambilightHost = j != null ? j.optString("ambilight_host", j.optString("ambilightHost", "")).trim() : "";
        ambilightBrightness = j != null ? Math.max(0, Math.min(100, j.optInt("ambilight_brightness", 70))) : 70;
        String tvIp = j != null ? j.optString("ambilight_tv_ip", "127.0.0.1").trim() : "127.0.0.1";
        ambilightTvIp = tvIp.matches("[0-9A-Za-z.:-]{1,64}") ? tvIp : "127.0.0.1";
        wallpaperBg = j != null ? Math.max(0, j.optInt("wallpaper_bg", 0)) : 0;
        JSONObject theme = j != null ? j.optJSONObject("theme") : null;
        if (theme == null && j != null && j.opt("theme") instanceof String) {
            try { theme = new JSONObject().put("name", j.optString("theme", "last-horizon")); } catch (Exception ignored) {}
        }
        // All colours come from the Omarchy theme (pushed colours > bundled stock palette > Last Horizon default).
        this.theme = Theme.resolve(c, theme);
        accent = this.theme.accent;
        accent2 = this.theme.accent2;
        JSONObject ss = j != null ? j.optJSONObject("screensaver") : null;
        String defLogo = c != null ? c.getString(R.string.logo).trim() : "omarchy-tv";
        logo = j != null ? j.optString("logo", defLogo).trim() : defLogo;
        String defaultSsArt = (ss != null && ss.has("mode") && !"aquarium".equals(ss.optString("mode"))) ? logo : "aquarium";
        screensaverArt = ss != null ? ss.optString("art", defaultSsArt).trim() : "aquarium";
        boolean aqArt = "aquarium".equals(screensaverArt) || "aquarium-classic".equals(screensaverArt);
        String ssMode = ss != null ? ss.optString("mode", aqArt ? "aquarium" : "logo").trim() : "aquarium";
        if (aqArt) ssMode = "aquarium";
        screensaverMode = ssMode;
        boolean defSsClock = c != null && c.getResources().getBoolean(R.bool.screensaver_clock);
        screensaverClock = ss != null ? ss.optBoolean("clock", defSsClock) : defSsClock;
        saverSound = ss == null || ss.optBoolean("sound", j != null ? j.optBoolean("saverSound", true) : true);
        uiSize = j != null ? j.optString("uiSize", "medium").trim() : "medium";
        JSONObject geo = j != null ? j.optJSONObject("geometry") : null;
        fontScale = clamp(geo != null ? (float) geo.optDouble("fontScale", 1.0) : 1f, 0.8f, 1.4f);
        tileScale = clamp(geo != null ? (float) geo.optDouble("tileScale", 1.0) : 1f, 0.8f, 1.1f);
        int defCols = c != null ? Math.max(4, Math.min(7, c.getResources().getInteger(R.integer.columns))) : 5;
        String size = geo != null ? geo.optString("appSize", "").trim() : "";
        int idx = Arrays.asList(APP_SIZES).indexOf(size);
        appSize = idx >= 0 ? size : APP_SIZES[defCols - 4];
        columns = idx >= 0 ? idx + 4 : defCols;
        gapDp = (int) clamp(geo != null ? geo.optInt("gaps", 5) : 5, 0, 20);   // Hyprland gaps_in = 5 (Omarchy looknfeel)
        borderDp = (int) clamp(geo != null ? geo.optInt("border", 2) : 2, 1, 4);
        transparency = (int) clamp(geo != null ? geo.optInt("transparency", Surfaces.DEFAULT) : Surfaces.DEFAULT, 5, 60);
        wallpaperDim = geo != null ? geo.optString("wallpaperDim", "off") : "off";
        screensaverSize = ss != null ? ss.optString("size", "large").trim() : "large";
        String sspd = ss != null ? ss.optString("speed", "normal").trim().toLowerCase(java.util.Locale.ROOT) : "normal";
        screensaverSpeed = ("slow".equals(sspd) || "fast".equals(sspd)) ? sspd : "normal";
        int idle = ss != null ? ss.optInt("idleMin", 5) : 5;
        screensaverIdleMin = idle == 1 || idle == 2 || idle == 10 || idle == 15 ? idle : 5;
        screensaverSystem = ss != null && ss.optBoolean("system", false);
        int isec = ss != null ? ss.optInt("idleSec", 150) : 150;
        screensaverIdleSec = isec == 0 || isec == 60 || isec == 150 || isec == 300 || isec == 600 ? isec : 150;
        logoAnim = j == null || j.optBoolean("logoAnim", true);
        textTiles = j != null && j.optBoolean("textTiles", false);
        JSONObject hw = j != null ? j.optJSONObject("homeWidgets") : null;
        wWeather = hw == null || hw.optBoolean("weather", true);
        wPc = hw == null || hw.optBoolean("pc", true);
        wAi = hw == null || hw.optBoolean("ai", true);
        wDeploys = hw == null || hw.optBoolean("deploys", true);
        wTerm = hw == null || hw.optBoolean("term", true);
        java.util.Map<String, int[]> ppos = new java.util.HashMap<>();
        JSONObject pj = j != null ? j.optJSONObject("panelPos") : null;
        if (pj != null) for (java.util.Iterator<String> it = pj.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONArray a = pj.optJSONArray(k);
            if (a != null && a.length() == 2) ppos.put(k, new int[]{a.optInt(0), a.optInt(1)});
        }
        panelPos = Collections.unmodifiableMap(ppos);
        JSONObject cst = j != null ? j.optJSONObject("cast") : null;
        castEnabled = cst != null && cst.optBoolean("enabled", false);
        JSONObject pr = j != null ? j.optJSONObject("prompt") : null;
        promptUser = promptPart(pr != null ? pr.optString("user", "") : "", "user");
        promptHost = promptPart(pr != null ? pr.optString("host", "") : "", "omarchytv");
        List<String> w = new ArrayList<>();
        JSONArray wa = j != null ? j.optJSONArray("widgets") : null;
        if (wa != null) {
            for (int i = 0; i < wa.length(); i++) {
                Object o = wa.opt(i);
                if (o instanceof JSONObject) {
                    JSONObject wo = (JSONObject) o;
                    if (wo.optBoolean("enabled", true) && !wo.optString("id").isEmpty()) w.add(wo.optString("id"));
                } else if (o instanceof String) {
                    w.add((String) o);
                }
            }
        } else {
            w.addAll(Arrays.asList("omarchy.menu", "omarchy.workspaces", "omarchy.clock", "omarchytv.inputs", "omarchy.network", "omarchytv.settings"));
        }
        widgets = Collections.unmodifiableList(w);
        sourceModified = modified;
    }

    /** True if {@code pkg} is hidden (matches exact package or legacy {@code pkg/activity} entries). */
    boolean isHidden(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (hidden.contains(pkg)) return true;
        String prefix = pkg + "/";
        for (String h : hidden) {
            if (h != null && h.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Rotates theme and/or animated wallpaper/intro in {@code j} according to {@code random_theme} and
     * {@code random_wallpaper} flags. Returns true if {@code j} was modified.
     */
    static boolean applyRandomRotation(JSONObject j, java.util.Random rng) {
        if (j == null || rng == null) return false;
        boolean rt = j.optBoolean("random_theme", j.optBoolean("randomTheme", true));
        boolean rw = j.optBoolean("random_wallpaper", j.optBoolean("randomWallpaper", true));
        if (!rt && !rw) return false;
        try {
            JSONObject curThemeObj = j.optJSONObject("theme");
            String curTheme = curThemeObj != null ? curThemeObj.optString("name", "last-horizon") : j.optString("theme", "last-horizon");
            int curBg = j.optInt("wallpaper_bg", 0);
            String curWp = j.optString("wallpaper", "omarchy");
            if (rt && rw) {
                int curIdx = -1;
                for (int i = 0; i < ANIMATED_INTROS.length; i++) {
                    if (ANIMATED_INTROS[i][0].equals(curTheme) && Integer.parseInt(ANIMATED_INTROS[i][1]) == curBg) {
                        curIdx = i;
                        break;
                    }
                }
                int pick = rng.nextInt(ANIMATED_INTROS.length);
                if (ANIMATED_INTROS.length > 1 && pick == curIdx) {
                    pick = (pick + 1 + rng.nextInt(ANIMATED_INTROS.length - 1)) % ANIMATED_INTROS.length;
                }
                String[] entry = ANIMATED_INTROS[pick];
                JSONObject t = curThemeObj != null ? new JSONObject(curThemeObj.toString()) : new JSONObject();
                t.put("name", entry[0]);
                t.put("source", "tv");
                j.put("theme", t);
                j.put("wallpaper", "omarchy");
                j.put("wallpaper_bg", Integer.parseInt(entry[1]));
                return true;
            } else if (rt) {
                String nextTheme = ANIMATED_THEMES[rng.nextInt(ANIMATED_THEMES.length)];
                if (ANIMATED_THEMES.length > 1 && nextTheme.equals(curTheme)) {
                    for (String cand : ANIMATED_THEMES) {
                        if (!cand.equals(curTheme)) { nextTheme = cand; break; }
                    }
                }
                JSONObject t = curThemeObj != null ? new JSONObject(curThemeObj.toString()) : new JSONObject();
                t.put("name", nextTheme);
                t.put("source", "tv");
                j.put("theme", t);
                return true;
            } else {
                int curIdx = -1;
                for (int i = 0; i < ANIMATED_INTROS.length; i++) {
                    if (ANIMATED_INTROS[i][2].equals(curWp)
                            || ("omarchy".equals(curWp) && ANIMATED_INTROS[i][0].equals(curTheme) && Integer.parseInt(ANIMATED_INTROS[i][1]) == curBg)) {
                        curIdx = i;
                        break;
                    }
                }
                int pick = rng.nextInt(ANIMATED_INTROS.length);
                if (ANIMATED_INTROS.length > 1 && pick == curIdx) {
                    pick = (pick + 1 + rng.nextInt(ANIMATED_INTROS.length - 1)) % ANIMATED_INTROS.length;
                }
                String[] entry = ANIMATED_INTROS[pick];
                if (entry[0].equals(curTheme)) {
                    j.put("wallpaper", "omarchy");
                    j.put("wallpaper_bg", Integer.parseInt(entry[1]));
                } else {
                    j.put("wallpaper", entry[2]);
                    j.put("wallpaper_bg", Integer.parseInt(entry[1]));
                }
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    static void lockTheme(JSONObject j, String themeName) throws org.json.JSONException {
        JSONObject t = new JSONObject();
        t.put("name", themeName);
        t.put("source", "tv");
        j.put("theme", t);
        j.put("random_theme", false);
    }

    static void lockWallpaper(JSONObject j, String wallpaper, int bgIdx) throws org.json.JSONException {
        j.put("wallpaper", wallpaper);
        j.put("wallpaper_bg", bgIdx);
        j.put("random_wallpaper", false);
    }

    static float sizeFraction(String size) {
        if ("small".equals(size)) return 0.45f;
        if ("medium".equals(size)) return 0.56f;
        return 0.68f;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Wraps a base context with the configured language, UI scale and text scale. The UI scale is
     * computed from the real screen: layouts are designed for a 960dp-wide screen (a 1920x1080 surface
     * at density 320), so any TV gets the same proportions, times 0.9 / 1.0 / 1.12 for small / medium / large.
     */
    static Context wrap(Context base) {
        try {
            LauncherConfig cfg = get(base);
            android.content.res.Configuration conf = new android.content.res.Configuration(base.getResources().getConfiguration());
            android.util.DisplayMetrics dm = base.getResources().getDisplayMetrics();
            int widthPx = Math.max(dm.widthPixels, dm.heightPixels);
            float mult = "small".equals(cfg.uiSize) ? 0.9f : "large".equals(cfg.uiSize) ? 1.12f : 1f;
            int dpi = Math.round(widthPx / 960f * 160f * mult);
            if (dpi >= 120 && dpi <= 960) conf.densityDpi = dpi;
            conf.fontScale = conf.fontScale * cfg.fontScale;
            if (!"auto".equals(cfg.language) && !cfg.language.isEmpty()) {
                java.util.Locale loc = new java.util.Locale(cfg.language);
                java.util.Locale.setDefault(loc);
                conf.setLocale(loc);
                conf.setLayoutDirection(loc);
            }
            return base.createConfigurationContext(conf);
        } catch (Exception e) {
            return base;
        }
    }

    static File file(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        return new File(dir, FILE_NAME);
    }

    /** Current config; re-parsed when the JSON file changed since the last call. Never throws. */
    /** "workspaces": {"order"} as workspace numbers: known names once each, home first when left out. */
    static List<Integer> workspaceOrder(JSONObject ws) {
        JSONArray names = ws != null ? ws.optJSONArray("order") : null;
        List<Integer> order = new ArrayList<>();
        if (names == null) {
            for (int n = 1; n <= WORKSPACE_IDS.length; n++) order.add(n);
            return order;
        }
        for (int i = 0; i < names.length(); i++) {
            int n = Arrays.asList(WORKSPACE_IDS).indexOf(names.optString(i).trim().toLowerCase(java.util.Locale.ROOT)) + 1;
            if (n > 0 && !order.contains(n)) order.add(n);
        }
        if (!order.contains(1)) order.add(0, 1);
        return order;
    }

    /** "workspaces": {"names"} by workspace number: known ids only, names cleaned by {@link #workspaceNamePart}. */
    static java.util.Map<Integer, String> workspaceNames(JSONObject ws) {
        JSONObject names = ws != null ? ws.optJSONObject("names") : null;
        java.util.Map<Integer, String> out = new java.util.HashMap<>();
        for (int n = 1; names != null && n <= WORKSPACE_IDS.length; n++) {
            String v = workspaceNamePart(names.optString(WORKSPACE_IDS[n - 1], ""));
            if (!v.isEmpty()) out.put(n, v);
        }
        return out;
    }

    /** A workspace name as typed: control characters dropped, trimmed, at most 12 characters ("" = use the id). */
    static String workspaceNamePart(String raw) {
        String v = raw == null ? "" : raw.replaceAll("\\p{Cntrl}", "").trim();
        return v.length() > 12 ? v.substring(0, 12).trim() : v;
    }

    /** The name shown for workspace {@code n}: its custom name, else its id. */
    String workspaceName(int n) {
        String v = workspaceNames.get(n);
        return v != null ? v : workspaceId(n);
    }

    static String workspaceId(int n) {
        return n >= 1 && n <= WORKSPACE_IDS.length ? WORKSPACE_IDS[n - 1] : "";
    }

    boolean workspaceShown(int n) {
        return workspaceOrder.contains(n);
    }

    /** The workspace at bar position {@code pos} (1-based), or 0 when there is none. */
    int workspaceAt(int pos) {
        return pos >= 1 && pos <= workspaceOrder.size() ? workspaceOrder.get(pos - 1) : 0;
    }

    /** Bar position (1-based) of workspace {@code n}, or 0 when it's hidden. */
    int positionOf(int n) {
        return workspaceOrder.indexOf(n) + 1;
    }

    /** The workspace next to {@code from} in bar order, {@code step} ±1, wrapping around; home when it's hidden. */
    int nextWorkspace(int from, int step) {
        int pos = positionOf(from);
        if (pos == 0) return 1;
        int count = workspaceOrder.size();
        return workspaceOrder.get(((pos - 1 + step) % count + count) % count);
    }

    static synchronized LauncherConfig get(Context c) {
        Context app = c.getApplicationContext();
        File f = file(app);
        long mod = f.isFile() ? f.lastModified() : 0L;
        if (cached != null && cached.sourceModified == mod) return cached;
        JSONObject j = mod != 0L ? readJson(app) : null;
        cached = new LauncherConfig(app, j, mod);
        return cached;
    }

    /** Reads the JSON file as an object (empty when missing or malformed). */
    static JSONObject readJson(Context c) {
        File f = file(c.getApplicationContext());
        if (!f.isFile() || f.length() > 256 * 1024) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            return new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    interface Edit {
        void apply(JSONObject json) throws Exception;
    }

    /**
     * Read-modify-write of omarchytv.json (atomic rename), keeping keys this version doesn't know, so a
     * PC-side plugin and the launcher can edit the same file. Returns the fresh config.
     */
    /** Like {@link #update} but throws, so the Link service can answer 4xx/5xx instead of a silent no-op. */
    static synchronized void updateOrThrow(Context c, Edit edit) throws Exception {
        Context app = c.getApplicationContext();
        JSONObject j = readJson(app);
        if (!j.has("version")) j.put("version", 1);
        edit.apply(j);
        File f = file(app);
        File tmp = new File(f.getParentFile(), FILE_NAME + ".tmp");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            out.write(j.toString(2).getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new java.io.IOException("cannot write " + f);
        }
        cached = null;
    }

    static synchronized LauncherConfig update(Context c, Edit edit) {
        Context app = c.getApplicationContext();
        JSONObject j = readJson(app);
        try {
            if (!j.has("version")) j.put("version", 1);
            edit.apply(j);
            File f = file(app);
            File tmp = new File(f.getParentFile(), FILE_NAME + ".tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                out.write(j.toString(2).getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
        } catch (Exception ignored) {
        }
        cached = null;
        return get(app);
    }

    static JSONArray array(List<String> items) {
        JSONArray a = new JSONArray();
        for (String s : items) a.put(s);
        return a;
    }

    private static List<String> strings(JSONObject j, String key, List<String> fallback) {
        JSONArray a = j != null ? j.optJSONArray(key) : null;
        if (a == null) return fallback;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "").trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static int color(JSONObject o, String key, int fallback) {
        if (o == null || !o.has(key)) return fallback;
        try {
            return Color.parseColor(o.optString(key));
        } catch (Exception e) {
            return fallback;
        }
    }


    /** Lower case, only [a-z0-9._-], at most 16 characters; empty → {@code def}. */
    static String promptPart(String in, String def) {
        StringBuilder b = new StringBuilder();
        String l = in == null ? "" : in.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < l.length() && b.length() < 16; i++) {
            char ch = l.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '.' || ch == '_' || ch == '-') b.append(ch);
        }
        return b.length() == 0 ? def : b.toString();
    }
}
