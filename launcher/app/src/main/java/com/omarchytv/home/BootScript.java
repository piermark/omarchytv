package com.omarchytv.home;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * v2.14 boot screen: the script that {@link BootScreenView} plays when the TV starts. Pure Java (no Android
 * imports) so the timing and the text are unit-tested in {@code BootScriptTest}. The LG webOS / Samsung Tizen
 * app plays the same script from {@code webos/app/boot.js} (a test checks that both say the same lines).
 *
 * About 7 s in total, skippable with any key:
 * <ol>
 * <li>CRT power-on: a bright line opens to full screen;</li>
 * <li>a 90s BIOS POST with an OTV block badge top right (as the old Energy Star logo), memory counting up;</li>
 * <li>systemd lines as Arch prints them; some first show the {@code [ *** ]} start-job spinner, then {@code [  OK  ]}.
 *     Network and omarchy-link really wait for the TV's state ({@link Live}), up to {@link #MAX_WAIT_MS};</li>
 * <li>auto-login, the prompt types {@code omarchy-tv}, and the OMARCHY TV block wordmark (as on Home) draws row by row;</li>
 * <li>fade to Home.</li>
 * </ol>
 * Everything shown is true or an obvious joke (ads, Couch): model, RAM, display mode, network, PC and app count
 * are the TV's own.
 */
final class BootScript {

    /** How a line is drawn. */
    enum Kind {
        /** Plain text (fgDim). */
        POST,
        /** BIOS line whose trailing number counts up from 0 while it animates. */
        MEMTEST,
        /** systemd line: while it animates "[ *** ] A start job is running for {@link Line#detail}", then "[  OK  ] text". */
        OK,
        /** Bold heading in the accent colour ("Welcome to Omarchy TV!"). */
        TITLE,
        /** Shell prompt; {@link Line#detail} is typed one character at a time after it. */
        PROMPT,
        /** One row of the block wordmark (filled cells are U+2588); "OMARCHY" in accent, "TV" in accent2. */
        LOGO,
        /** Info line under the wordmark (fg). */
        INFO,
        /** Empty line (a pause). */
        BLANK,
        /** Clears the screen before the next line, with a short flicker (BIOS → systemd). */
        CLEAR
    }

    static final class Line {
        final Kind kind;
        final String text;
        /** PROMPT: the command typed. OK: the start-job name shown by the spinner (null = no spinner). */
        final String detail;
        /** Time (ms from the start) at which the line appears. */
        final int at;
        /** How long the line animates (memory count, typing, spinner); 0 for instant lines. */
        final int anim;
        /** OK lines with live data: the {@link Live} key ("net", "pc"); null for fixed lines. */
        final String key;
        /** Longest wait (ms) after the spinner for the live result before the line gives up. */
        final int maxWait;
        /** Live result, set by {@link #clock(int)} when the job finishes (null until then). */
        Status result;

        Line(Kind kind, String text, String detail, int at, int anim) {
            this(kind, text, detail, at, anim, null, 0);
        }

        Line(Kind kind, String text, String detail, int at, int anim, String key, int maxWait) {
            this.kind = kind;
            this.text = text;
            this.detail = detail;
            this.at = at;
            this.anim = anim;
            this.key = key;
            this.maxWait = maxWait;
        }

        /** Text to show once done: the live result's, else the fixed one. */
        String doneText() {
            return result != null ? result.text : text;
        }

        /** False when a live job ended badly (drawn "[ WARN ]"). */
        boolean doneOk() {
            return result == null || result.ok;
        }
    }

    /** The outcome of a live job: drawn "[  OK  ] text" or "[ WARN ] text". */
    static final class Status {
        final boolean ok;
        final String text;

        Status(boolean ok, String text) {
            this.ok = ok;
            this.text = text;
        }
    }

    /**
     * Real data the boot screen waits for while the spinner turns. {@link #get} returns null while the answer
     * is not known yet; with {@code timedOut} it must answer (the line then shows what is known).
     * Keys: "net" (network up, its name and address), "pc" (omarchy-link and the paired PC).
     */
    interface Live {
        Status get(String key, boolean timedOut);
    }

    /** Text columns that must fit the screen width (the wordmark is 66 wide). */
    static final int COLUMNS = 72;
    /** CRT power-on before the first line. */
    static final int CRT_MS = 520;
    /** Flicker after a CLEAR. */
    static final int FLICKER_MS = 140;
    /** Pause after the last line before the fade starts. */
    static final int HOLD_MS = 900;
    /** Fade from the boot screen to Home. */
    static final int FADE_MS = 400;
    /** The wordmark drawn at the end, and the badge in the BIOS corner. */
    static final String WORDMARK = "OMARCHY TV", BADGE = "OTV";

    final List<Line> lines;
    /** Badge rows (BlockFont, filled cells as U+2588), drawn top right during the BIOS. */
    final List<String> badge;
    /** The BIOS ends here (the CLEAR line): the badge is drawn before this. */
    final int biosEnd;
    /** Column where "TV" starts in each LOGO row (coloured accent2). */
    final int logoSplit;
    /** When the fade to Home starts (ms from the start). */
    final int fadeAt;
    /** When the boot screen is gone (ms from the start). */
    final int endAt;

    private final Live live;
    /** Real ms the script clock has been held back while live jobs waited. */
    private int held;
    private int waitStart = -1;

    private BootScript(List<Line> lines, List<String> badge, int biosEnd, int logoSplit, int fadeAt, Live live) {
        this.live = live;
        this.lines = Collections.unmodifiableList(lines);
        this.badge = Collections.unmodifiableList(badge);
        this.biosEnd = biosEnd;
        this.logoSplit = logoSplit;
        this.fadeAt = fadeAt;
        this.endAt = fadeAt + FADE_MS;
    }

    /**
     * @param version launcher versionName ("2.14.0")
     * @param theme   active Omarchy theme name ("tokyo-night")
     * @param model   TV model ("Sony BRAVIA 4K GB"), may be empty
     * @param ramMb   total RAM of the TV in MiB (0 if unknown: the memory line is left out)
     * @param display display mode ("3840x2160 @ 60Hz"), may be empty (the line is left out)
     * @param apps    apps installed on the TV (negative if unknown: the line is left out)
     * @param user    prompt user ("user", omarchytv.json prompt.user)
     * @param host    prompt host ("omarchytv")
     * @param live    real network / PC state, asked while their spinners turn (null: fixed texts)
     */
    static BootScript build(String version, String theme, String model, long ramMb, String display, int apps,
                            String user, String host, Live live) {
        version = clean(version, "dev");
        theme = clean(theme, "tokyo-night");
        model = clean(model, "Android TV");
        display = clean(display, "");
        user = clean(user, "user");
        host = clean(host, "omarchytv");

        List<Line> out = new ArrayList<>();
        int t = CRT_MS;

        // BIOS POST: real version, model, RAM and display mode; the ads line is the joke.
        t = add(out, Kind.POST, fit("OmarchyBIOS v" + version + "  Release 2026, MIT licensed"), null, t, 0, 90);
        t = add(out, Kind.POST, fit("Copyright (C) Omarchy TV contributors"), null, t, 0, 200);
        t = add(out, Kind.BLANK, "", null, t, 0, 0);
        t = add(out, Kind.POST, fit("System Model   : " + model), null, t, 0, 160);
        if (ramMb > 0) {
            String mem = String.format(Locale.ROOT, "Memory Testing : %d MB OK", ramMb);
            t = add(out, Kind.MEMTEST, fit(mem), null, t, 650, 700);
        }
        if (!display.isEmpty()) t = add(out, Kind.POST, fit("Display Mode   : " + display), null, t, 0, 180);
        t = add(out, Kind.POST, fit("Detecting ads ............... none"), null, t, 0, 300);
        t = add(out, Kind.BLANK, "", null, t, 0, 0);
        t = add(out, Kind.POST, fit("Press any key to skip"), null, t, 0, 380);
        int biosEnd = t;
        t = add(out, Kind.CLEAR, "", null, t, 0, FLICKER_MS);

        // systemd, Arch style. Network and omarchy-link spin until the TV really knows (or give up).
        t = add(out, Kind.TITLE, "Welcome to Omarchy TV!", null, t, 0, 220);
        t = add(out, Kind.BLANK, "", null, t, 0, 0);
        t = ok(out, "Reached target Local File Systems.", null, null, t);
        t = ok(out, "Started Network Manager.", "Network Manager", "net", t);
        t = ok(out, "Loaded theme " + theme + ".", null, null, t);
        t = ok(out, "Started omarchy-link.service.", "omarchy-link.service", "pc", t);
        t = ok(out, "Started Hyprland-style workspaces 1-5.", null, null, t);
        if (apps >= 0) t = ok(out, "Indexed " + apps + (apps == 1 ? " app." : " apps."), null, null, t);
        t = ok(out, "Mounted /media/cinemarchy.", null, null, t);
        t = ok(out, "Masked ads.service.", "ads.service", null, t);
        t = ok(out, "Reached target Couch.", null, null, t);
        t = add(out, Kind.BLANK, "", null, t, 0, 100);

        // Auto-login, the prompt types omarchy-tv, the wordmark draws itself.
        t = add(out, Kind.POST, fit(host + " login: " + user + " (automatic login)"), null, t, 0, 200);
        String cmd = "omarchy-tv";
        int typing = cmd.length() * 55;
        t = add(out, Kind.PROMPT, fit(user + "@" + host + ":~$ "), cmd, t, typing, typing + 160);
        t = add(out, Kind.BLANK, "", null, t, 0, 0);
        for (String row : blockRows(WORDMARK)) t = add(out, Kind.LOGO, row, null, t, 0, 85);
        t = add(out, Kind.BLANK, "", null, t, 0, 0);
        t = add(out, Kind.INFO, fit("theme " + theme + "  ·  v" + version), null, t, 0, 0);

        return new BootScript(out, blockRows(BADGE), biosEnd, BlockFont.splitColumn(WORDMARK), t + HOLD_MS, live);
    }

    /** Most a live job waits after its spinner before showing what it knows. */
    static final int MAX_WAIT_MS = 2500;
    /** Spinner time of a start job. */
    static final int SPIN_MS = 420;

    private static int ok(List<Line> out, String text, String job, String key, int t) {
        int spin = job != null ? SPIN_MS : 0;
        out.add(new Line(Kind.OK, fit(text), job, t, spin, key, key != null ? MAX_WAIT_MS : 0));
        return t + spin + 110;
    }

    /**
     * Script time for {@code realMs} since the start. When a live line's spinner ends before its answer is
     * known, the script holds there (the spinner keeps turning) until {@link Live} answers or
     * {@link Line#maxWait} passes; the rest of the script then follows from that point.
     */
    int clock(int realMs) {
        int s = realMs - held;
        for (Line l : lines) {
            if (l.key == null || l.result != null) continue;
            int end = l.at + l.anim;
            if (s < end) break;
            if (waitStart < 0) waitStart = realMs;
            boolean timedOut = realMs - waitStart >= l.maxWait;
            Status st = null;
            try {
                st = live != null ? live.get(l.key, timedOut) : new Status(true, l.text);
            } catch (RuntimeException ignored) {   // a failing probe must never stop the boot screen
                timedOut = true;
            }
            if (st == null && !timedOut) {
                held = realMs - end;
                return end;
            }
            l.result = st != null ? new Status(st.ok, fit(clean(st.text, l.text))) : new Status(false, l.text);
            if (realMs - waitStart > 0) held = realMs - end;   // it waited: go on from the end of the spinner
            waitStart = -1;
            s = realMs - held;
        }
        return s;
    }

    /** Lines visible at {@code ms} (CLEAR wipes everything before it). */
    List<Line> visibleAt(int ms) {
        List<Line> shown = new ArrayList<>();
        for (Line l : lines) {
            if (l.at > ms) break;
            if (l.kind == Kind.CLEAR) shown.clear();
            else shown.add(l);
        }
        return shown;
    }

    /** 0..1 power-on progress (1 once the CRT has opened). */
    static float crt(int ms) {
        return Math.max(0f, Math.min(1f, ms / (float) CRT_MS));
    }

    /** True during the dark half of the flicker right after a CLEAR. */
    boolean flickering(int ms) {
        for (Line l : lines) {
            if (l.kind == Kind.CLEAR && ms >= l.at && ms < l.at + FLICKER_MS) return ((ms - l.at) / 35) % 2 == 0;
        }
        return false;
    }

    static float progress(Line l, int ms) {
        if (l.anim <= 0) return 1f;
        return Math.max(0f, Math.min(1f, (ms - l.at) / (float) l.anim));
    }

    /** Characters of a PROMPT's command typed by {@code ms}. */
    static int typedChars(Line l, int ms) {
        if (l.kind != Kind.PROMPT || l.detail == null) return 0;
        return Math.round(progress(l, ms) * l.detail.length());
    }

    /** True while an OK line is still on its start-job spinner. */
    static boolean spinning(Line l, int ms) {
        return l.kind == Kind.OK && l.detail != null && (progress(l, ms) < 1f || (l.key != null && l.result == null));
    }

    private static final String[] SPIN = {"*     ", "**    ", "***   ", " ***  ", "  *** ", "   ***", "    **", "     *",
            "    **", "   ***", "  *** ", " ***  ", "***   ", "**    "};

    /** The 6 characters between the brackets of a spinning line, as systemd draws them. */
    static String spinner(int ms) {
        return SPIN[Math.max(0, ms / 60) % SPIN.length];
    }

    /** The MEMTEST line's text at {@code ms}: the number counts up in 64 MB steps to its final value. */
    static String memText(Line l, int ms) {
        if (l.kind != Kind.MEMTEST || progress(l, ms) >= 1f) return l.text;
        int i = l.text.indexOf(": ");
        int j = l.text.indexOf(" MB");
        if (i < 0 || j < i) return l.text;
        long total;
        try {
            total = Long.parseLong(l.text.substring(i + 2, j).trim());
        } catch (NumberFormatException e) {
            return l.text;
        }
        long now = (long) (total * progress(l, ms)) / 64 * 64;
        return l.text.substring(0, i + 2) + now + " MB";
    }

    /** BlockFont rows with '#' as full blocks. */
    static List<String> blockRows(String text) {
        List<String> rows = new ArrayList<>();
        for (char[] r : BlockFont.render(text)) rows.add(new String(r).replace('#', '█'));
        return rows;
    }

    private static int add(List<Line> out, Kind k, String text, String detail, int at, int anim, int next) {
        out.add(new Line(k, text, detail, at, anim));
        return at + next;
    }

    private static String clean(String s, String def) {
        if (s == null) return def;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c != 0x7f) b.append(c);   // no control characters from config or Build.MODEL
        }
        String r = b.toString().trim();
        return r.isEmpty() ? def : r;
    }

    private static String fit(String s) {
        return s.length() <= 64 ? s : s.substring(0, 63) + "…";
    }
}
