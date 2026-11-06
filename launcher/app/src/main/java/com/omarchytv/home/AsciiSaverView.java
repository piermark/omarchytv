package com.omarchytv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;

/**
 * Native re-creation of Omarchy's screensaver (bin/omarchy-screensaver), plus the v2.13
 * Omarchy Cyber-TUI Aquarium mode ("aquarium").
 * <ul>
 *   <li>Logo modes ("omarchy-tv", "omarchy", "cinemarchy", "original"): loops terminal-text-effect intros
 *       (decrypt, rain, beams, print, slide, expand, matrix, wipe, burn) over the half-block art on a character grid.</li>
 *   <li>Aquarium mode ("aquarium"): multi-layered Omarchy Cyber-TUI ocean using all active Omarchy theme colors
 *       (bg, fg, accent, accent2, fgDim, cyan, green, magenta, yellow) with animated water surface shimmer,
 *       TUI telemetry header, parallax deep-sea creatures (half-block Leviathan Whale, Cyber Reef Shark with OMARCHY
 *       emblem, bioluminescent Jellyfish with swaying tentacles, Anglerfish with pulsing lantern, autonomous mini-sub
 *       with sonar cone, schooling fish), rising bubble streams, swaying multi-segment kelp forests, coral reefs,
 *       a sunken retro terminal displaying a glowing [OMARCHY] emblem, and a crab walking sideways along the seabed.</li>
 * </ul>
 */
public class AsciiSaverView extends View {

    private static final int FX_DECRYPT = 0, FX_RAIN = 1, FX_BEAMS = 2, FX_PRINT = 3, FX_SLIDE = 4, FX_EXPAND = 5,
            FX_MATRIX = 6, FX_WIPE = 7, FX_BURN = 8;
    static final String[] FX_NAMES = {"decrypt", "rain", "beams", "print", "slide", "expand", "matrix", "wipe", "burn"};
    private static final long IN_MS = 1800, HOLD_MS = 5000, OUT_MS = 700;
    private final int[] stops;
    private static final int LUT = 96;
    private final int[] gradient = new int[LUT];

    private final Random random = new Random();
    private final Paint block = new Paint();
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clock = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint aqPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final DateFormat timeFormat;

    // Art as cells.
    private int cols, rows;
    private int[] cellRow, cellCol;
    private char[] cellChar;
    private float[] seed;
    private float[] seed2;

    private float cellW, cellH, originX, originY, driftX, driftY;
    private int effect = FX_DECRYPT;
    private long cycleStart = -1;
    private boolean running;
    private boolean showClock = true;
    private float widthFraction = 0.68f;
    private long frozenAt = -1;
    private boolean aquariumMode;
    private String statusOverride;
    private volatile boolean soundOn = true;
    private volatile boolean synthRunning = false;
    private Thread synthThread;
    private final float[] vuAmps = new float[]{0.45f, 0.55f, 0.40f, 0.30f};
    /** v2.13: "aquarium" = homage edition (default), "aquarium-classic" = the original v2.13 look and tune. */
    private boolean classicAquarium;
    private AquariumHomage homage;
    private long homageStartMs = SystemClock.uptimeMillis();
    private long hudBoostUntil;

    static boolean isAquarium(LauncherConfig cfg) {
        return "aquarium".equals(cfg.screensaverArt) || "aquarium-classic".equals(cfg.screensaverArt) || "aquarium".equals(cfg.screensaverMode);
    }

    public AsciiSaverView(Context context, AttributeSet attrs) {
        super(context, attrs);
        LauncherConfig cfg = LauncherConfig.get(context);
        stops = new int[]{cfg.accent, cfg.accent2, cfg.theme.cyan};
        for (int i = 0; i < LUT; i++) {
            float t = i / (float) LUT * stops.length;
            int a = stops[(int) t % stops.length], b = stops[((int) t + 1) % stops.length];
            gradient[i] = lerpColor(a, b, t - (int) t);
        }
        Typeface tf;
        try {
            tf = CastUi.font(getContext());
        } catch (Exception e) {
            tf = Typeface.MONOSPACE;
        }
        glyph.setTypeface(tf);
        glyph.setTextAlign(Paint.Align.CENTER);
        clock.setTypeface(tf);
        clock.setTextAlign(Paint.Align.CENTER);
        clock.setLetterSpacing(0.06f);
        clock.setColor(cfg.theme.fg);
        aqPaint.setTypeface(tf);
        aqPaint.setTextAlign(Paint.Align.LEFT);
        timeFormat = android.text.format.DateFormat.getTimeFormat(context);
        aquariumMode = isAquarium(cfg);
        classicAquarium = "aquarium-classic".equals(cfg.screensaverArt);
        soundOn = cfg.saverSound;
        homage = new AquariumHomage(tf, 75f);
        loadArt(Art.load(context, cfg.screensaverArt));
    }

    void setShowClock(boolean show) {
        showClock = show;
    }

    void setStatusOverride(String status) {
        this.statusOverride = status;
        invalidate();
    }

    void setAquariumMode(boolean on) {
        this.aquariumMode = on;
        if (running) {
            if (on && (soundOn || !classicAquarium)) startSynth();
            else stopSynth();
        }
        invalidate();
    }

    public boolean toggleSound() {
        soundOn = !soundOn;
        hudBoostUntil = SystemClock.uptimeMillis() + 6000;
        if (running && aquariumMode && !classicAquarium) {
            invalidate(); // homage synth keeps its clock running and just outputs silence while muted
            return soundOn;
        }
        if (running && aquariumMode) {
            if (soundOn) startSynth();
            else stopSynth();
        }
        invalidate();
        return soundOn;
    }

    public boolean isSoundOn() {
        return soundOn;
    }

    public boolean isAquariumMode() {
        return aquariumMode;
    }

    private float clockK() {
        return 0.41f * Math.min(1f, 8f / Math.max(1, rows));
    }

    private float clockRows() {
        return showClock ? 1.6f + 1.15f * clockK() * rows : 0f;
    }

    void setWidthFraction(float f) {
        widthFraction = Math.max(0.2f, Math.min(0.85f, f));
        if (getWidth() > 0) onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
    }

    void freeze(int fx, long atMs, long seedValue) {
        random.setSeed(seedValue);
        effect = fx;
        frozenAt = atMs;
        newCycle(false);
        invalidate();
    }

    void start() {
        running = true;
        frozenAt = -1;
        LauncherConfig cfg = LauncherConfig.get(getContext());
        aquariumMode = isAquarium(cfg);
        boolean wasClassic = classicAquarium;
        classicAquarium = "aquarium-classic".equals(cfg.screensaverArt);
        if (wasClassic != classicAquarium) stopSynth();
        soundOn = cfg.saverSound;
        homageStartMs = SystemClock.uptimeMillis();
        stopSynth();
        if (aquariumMode && (soundOn || !classicAquarium)) startSynth();
        else stopSynth();
        newCycle(true);
        invalidate();
    }

    void stop() {
        running = false;
        stopSynth();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopSynth();
        super.onDetachedFromWindow();
    }

    private synchronized void startSynth() {
        if (synthRunning) return;
        synthRunning = true;
        final AquariumHomage hz = homage;
        Runnable body = classicAquarium ? this::runSt3SynthLoop : () -> hz.runSynth(new AquariumHomage.Gate() {
            @Override
            public boolean playing() {
                return soundOn && aquariumMode && running;
            }

            @Override
            public boolean alive() {
                return synthRunning && !Thread.currentThread().isInterrupted();
            }
        });
        synthThread = new Thread(body, "Omarchy-ST3-Synth");
        synthThread.setDaemon(true);
        synthThread.start();
    }

    private synchronized void stopSynth() {
        synthRunning = false;
        if (synthThread != null) {
            synthThread.interrupt();
            synthThread = null;
        }
    }

    /**
     * Real-time procedural 4-channel ScreamTracker 3 (ST3 / Purple Motion "Second Reality" style)
     * underwater synth engine:
     * CH1: Deep PWM/sawtooth bassline in C Dorian (C2, Eb2, F2, G2, Bb2)
     * CH2: Crystalline 16th-note tracker arpeggio (Cm9 / Abmaj7 / Fm7 / G7)
     * CH3: Dreamy detuned underwater pad lead
     * CH4: Periodic deep-sea bio-sonar ping (1320 Hz + echo) & soft hydro-percussion pulse
     */
    private void runSt3SynthLoop() {
        final int sr = 22050;
        final int bufSamples = 1024;
        AudioTrack track = null;
        try {
            int minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBuf, bufSamples * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.play();
            short[] pcm = new short[bufSamples];
            final double[] bassNotes = {65.41, 77.78, 87.31, 98.00, 116.54, 98.00, 87.31, 77.78};
            final double[][] arpChords = {
                    {261.63, 311.13, 392.00, 466.16, 587.33}, // Cm9
                    {207.65, 261.63, 311.13, 392.00, 466.16}, // Abmaj7
                    {174.61, 207.65, 261.63, 311.13, 392.00}, // Fm7
                    {196.00, 246.94, 293.66, 349.23, 392.00}  // G7
            };
            final double[] padNotes = {130.81, 155.56, 174.61, 196.00};
            long sampleIdx = 0;
            double p1 = 0, p2 = 0, p3a = 0, p3b = 0, p4 = 0;
            double lp1 = 0;
            while (synthRunning && !Thread.currentThread().isInterrupted()) {
                if (!soundOn || !aquariumMode || !running) {
                    Thread.sleep(60);
                    continue;
                }
                double sum1 = 0, sum2 = 0, sum3 = 0, sum4 = 0;
                for (int i = 0; i < bufSamples; i++) {
                    double t = sampleIdx / (double) sr;
                    sampleIdx++;
                    int step16 = (int) (t * 8.0); // 120 BPM 16th notes (8 steps/sec)
                    int bar = (step16 / 16) % 4;
                    double stepFrac = (t * 8.0) - step16;

                    // CH1: Warm PWM + low-pass bassline
                    double f1 = bassNotes[(step16 / 2) % bassNotes.length];
                    p1 = (p1 + f1 / sr) % 1.0;
                    double pwm = (p1 < (0.48 + 0.25 * Math.sin(t * 1.7)) ? 0.55 : -0.55) * Math.exp(-stepFrac * 1.9);
                    lp1 += 0.14 * (pwm - lp1);
                    double c1 = lp1;

                    // CH2: ST3 Tracker Arpeggio (cycles 50Hz / 16th notes)
                    double[] chord = arpChords[bar];
                    double f2 = chord[(step16 + ((int) (t * 32.0) % 3)) % chord.length];
                    p2 = (p2 + f2 / sr) % 1.0;
                    double env2 = Math.exp(-stepFrac * 3.4);
                    double c2 = (Math.sin(p2 * Math.PI * 2.0) * 0.65 + (p2 < 0.5 ? 0.25 : -0.25)) * env2 * 0.38;

                    // CH3: Dreamy detuned underwater pad
                    double f3 = padNotes[bar];
                    p3a = (p3a + f3 / sr) % 1.0;
                    p3b = (p3b + (f3 * 1.006) / sr) % 1.0;
                    double c3 = (Math.sin(p3a * Math.PI * 2.0) + Math.sin(p3b * Math.PI * 2.0)) * 0.22
                            * (0.65 + 0.35 * Math.sin(t * 0.85));

                    // CH4: Bio-sonar ping (every 4 seconds + echo at 0.375s)
                    double pingPhase = t % 4.0;
                    double c4 = 0;
                    if (pingPhase < 0.75) {
                        p4 = (p4 + 1318.5 / sr) % 1.0;
                        double envPing = pingPhase < 0.375
                                ? Math.exp(-pingPhase * 11.0)
                                : 0.45 * Math.exp(-(pingPhase - 0.375) * 12.0);
                        c4 = Math.sin(p4 * Math.PI * 2.0) * envPing * 0.55;
                    }

                    sum1 += Math.abs(c1);
                    sum2 += Math.abs(c2);
                    sum3 += Math.abs(c3);
                    sum4 += Math.abs(c4);

                    double mix = (c1 * 0.42 + c2 * 0.34 + c3 * 0.32 + c4 * 0.38);
                    int val = (int) Math.max(-32767, Math.min(32767, mix * 14500.0));
                    pcm[i] = (short) val;
                }
                vuAmps[0] = (float) Math.min(1.0, (sum1 / bufSamples) * 3.2);
                vuAmps[1] = (float) Math.min(1.0, (sum2 / bufSamples) * 4.5);
                vuAmps[2] = (float) Math.min(1.0, (sum3 / bufSamples) * 3.6);
                vuAmps[3] = (float) Math.min(1.0, (sum4 / bufSamples) * 4.8);
                track.write(pcm, 0, bufSamples);
            }
        } catch (Throwable ignored) {
        } finally {
            if (track != null) {
                try {
                    track.stop();
                } catch (Throwable ignored) {
                }
                try {
                    track.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void loadArt(String text) {
        String[] lines = Art.lines(text);
        int first = 0, last = lines.length - 1;
        List<int[]> cells = new ArrayList<>();
        StringBuilder chars = new StringBuilder();
        cols = 0;
        rows = Math.max(0, last - first + 1);
        for (int r = first; r <= last; r++) {
            String l = lines[r];
            cols = Math.max(cols, l.length());
            for (int c = 0; c < l.length(); c++) {
                char ch = l.charAt(c);
                if (ch == ' ' || ch == '\t') continue;
                cells.add(new int[]{r - first, c});
                chars.append(ch);
            }
        }
        int n = cells.size();
        cellRow = new int[n];
        cellCol = new int[n];
        cellChar = chars.toString().toCharArray();
        seed = new float[n];
        seed2 = new float[n];
        for (int i = 0; i < n; i++) {
            cellRow[i] = cells.get(i)[0];
            cellCol[i] = cells.get(i)[1];
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cellW = Math.max(4f, w * widthFraction / Math.max(1, cols));
        float maxH = h * 0.8f / (rows + clockRows()) / 2f;
        cellW = Math.min(cellW, maxH);
        cellH = cellW * 2f;
        glyph.setTextSize(cellH * 0.8f);
        clock.setTextSize(Math.max(clockK() * rows * cellH, 14f));
        placeArt();
    }

    private float salt;

    private void newCycle(boolean pickEffect) {
        if (pickEffect) {
            int next = random.nextInt(FX_NAMES.length - 1);
            effect = next >= effect ? next + 1 : next;
        }
        salt = random.nextFloat() * 100f;
        for (int i = 0; i < seed.length; i++) {
            seed[i] = random.nextFloat();
            seed2[i] = random.nextFloat();
        }
        cycleStart = SystemClock.uptimeMillis();
        placeArt();
    }

    private void placeArt() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float margin = cellW * 1.6f;
        float artW = cols * cellW, artH = (rows + clockRows()) * cellH;
        float freeX = Math.max(0, w - artW - 2 * margin), freeY = Math.max(0, h - artH - 2 * margin);
        originX = margin + freeX * random.nextFloat();
        originY = margin + freeY * random.nextFloat();
        float ang = (float) (random.nextFloat() * Math.PI * 2);
        driftX = (float) Math.cos(ang);
        driftY = (float) Math.sin(ang);
    }

    private final Paint cornerClock = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int cornerMinute = -1;
    private float cornerDx, cornerDy;

    /** Screensaver > Clock over the Aquarium: small HH:MM bottom right, nudged each minute against burn-in. */
    private void drawCornerClock(Canvas canvas) {
        if (!showClock || statusOverride != null || getWidth() == 0) return;
        Date now = new Date();
        int minute = (int) (now.getTime() / 60000L);
        if (minute != cornerMinute) {
            cornerMinute = minute;
            cornerDx = random.nextFloat();
            cornerDy = random.nextFloat();
        }
        float size = getHeight() * 0.05f;
        cornerClock.setTypeface(clock.getTypeface());
        cornerClock.setTextAlign(Paint.Align.RIGHT);
        cornerClock.setTextSize(size);
        cornerClock.setColor(clock.getColor());
        cornerClock.setAlpha(150);
        float x = getWidth() * (0.97f - 0.04f * cornerDx);
        float y = getHeight() * (0.97f - 0.04f * cornerDy);
        canvas.drawText(timeFormat.format(now), x, y, cornerClock);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (aquariumMode) {
            if (classicAquarium) drawAquarium(canvas);
            else drawHomage(canvas);
            drawCornerClock(canvas);   // #83: the Aquarium returned before the clock, so Clock = on did nothing here
            return;
        }
        canvas.drawColor(Color.BLACK);
        if (cellRow == null || cellRow.length == 0 || getWidth() == 0) return;
        long now = SystemClock.uptimeMillis();
        String spd = LauncherConfig.get(getContext()).screensaverSpeed;
        float mult = "slow".equals(spd) ? 0.6f : "fast".equals(spd) ? 1.75f : 1.0f;
        long t = frozenAt >= 0 ? frozenAt : Math.round((now - cycleStart) * mult);
        long total = IN_MS + HOLD_MS + OUT_MS;
        if (frozenAt < 0 && t >= total) {
            newCycle(true);
            t = 0;
        }
        float phase = (now % 24000L) / 24000f;
        if (frozenAt >= 0) phase = 0.1f;
        float hold = Math.max(0f, Math.min(1f, (t - IN_MS) / (float) HOLD_MS));
        float ox = originX + driftX * hold * cellW * 0.6f;
        float oy = originY + driftY * hold * cellW * 0.6f;

        float pIn = Math.min(1f, t / (float) IN_MS);
        float pOut = t > IN_MS + HOLD_MS ? Math.min(1f, (t - IN_MS - HOLD_MS) / (float) OUT_MS) : 0f;

        int n = cellRow.length;
        for (int i = 0; i < n; i++) {
            int r = cellRow[i], c = cellCol[i];
            float x = ox + c * cellW, y = oy + r * cellH;
            int finalColor = gradient[(int) (((c / (float) cols) * 0.8f + phase) * LUT) % LUT];
            if (pOut > 0f) {
                float die = seed[i];
                if (pOut > die) continue;
                if (pOut > die - 0.12f) {
                    drawGlyph(canvas, x, y, Theme.m(0xFF565F89));
                    continue;
                }
                drawCell(canvas, cellChar[i], x, y, finalColor);
                continue;
            }
            if (pIn >= 1f) {
                drawCell(canvas, cellChar[i], x, y, finalColor);
                continue;
            }
            drawIntro(canvas, i, r, c, x, y, pIn, finalColor);
        }

        if (showClock || statusOverride != null) {
            String time = statusOverride != null ? statusOverride : timeFormat.format(new Date());
            float cy = oy + (rows + 1.6f) * cellH + clock.getTextSize() * 0.80f;
            int a = (int) (255 * Math.min(1f, pIn * 1.5f) * (1f - pOut));
            clock.setAlpha(a);
            canvas.drawText(time, ox + cols * cellW / 2f, cy, clock);
        }

        if (frozenAt >= 0 || !running) return;
        boolean animating = t < IN_MS || t > IN_MS + HOLD_MS;
        postInvalidateDelayed(animating ? 33 : 250);
    }

    // ---- Omarchy Cyber-TUI Aquarium · Future Crew "Second Reality" (Assembly '93) Edition --------

    private static final String[] WHALE_SPRITE = {
            "       .---.   .·°ºO                                 ",
            "   ___/     \\___                                     ",
            "  /   '-----'   \\____________________________________",
            " /  ●   ▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄       /  \\",
            "|      █████████████████████████████████████=====(    |",
            " \\      ▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀       \\__/",
            "  \\___,-----,___/¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯"
    };

    private static final String[] SHARK_SPRITE = {
            "              /|                ",
            "     _______ / |       |\\       ",
            "   /  ●   OMARCHY      | \\  /|  ",
            "  <   ═══════════════  |  \\/ |  ",
            "   \\________   ________|  /\\ |  ",
            "            \\ |        | /  \\|  ",
            "             \\|        |/       "
    };

    private static final String[] SUB_SPRITE = {
            "         _[||]_          ",
            "    .---'======'---.     ",
            "   | [OMARCHY-ROV] ●|===▶",
            "    '---.______.---'     ",
            "       /  /  \\  \\        "
    };

    private static final String[] ANGLER_SPRITE = {
            "     .---.               ",
            "    /     \\  ___         ",
            "   *       `/   \\___/|   ",
            "          /  ●  /    |   ",
            "         <  ▲▲▲      |   ",
            "          \\_____\\___\\|   "
    };

    private static final String[][] JELLY_FRAMES = {
            {
                    "   .─▀▀▀─.   ",
                    "  (  ● ●  )  ",
                    "   `─┬─┬─'   ",
                    "    / │ \\    ",
                    "   ~  ~  ~   "
            },
            {
                    "  .──▀▀▀──.  ",
                    " (   ● ●   ) ",
                    "  `──┬─┬──'  ",
                    "    │ │ │    ",
                    "    ~ ~ ~    "
            }
    };

    private static final String[] SUNKEN_TERM = {
            "  ┌───────────────────────────────┐  ",
            "  │ █▀█ █▀▄▀█ ▄▀█ █▀█ █▀▀ █ █ █ █ │  ",
            "  │ █▄█ █ ▀ █ █▀█ █▀▄ █▄▄ █▀█  █  │  ",
            "  └──────┴─────────────────┴──────┘  "
    };

    private static final String SECOND_REALITY_SCROLL =
            "   ★ OMARCHY TV · SECOND REALITY ABYSS 1993//2026 · CODE & PIXELS BY MARCO PORRO · GREETZ TO FUTURE CREW & DHH · 60FPS REALTIME ANSI ENGINE ★   ";

    // Octahedron / Glenz polyhedron vertices & edges for 3D rotating vector object
    private static final float[][] GLENZ_V = {
            {0f, 1.25f, 0f}, {0f, -1.25f, 0f},
            {1.15f, 0f, 0f}, {-1.15f, 0f, 0f},
            {0f, 0f, 1.15f}, {0f, 0f, -1.15f}
    };
    private static final int[][] GLENZ_E = {
            {0, 2}, {0, 3}, {0, 4}, {0, 5},
            {1, 2}, {1, 3}, {1, 4}, {1, 5},
            {2, 4}, {4, 3}, {3, 5}, {5, 2}
    };

    private final Paint hudLine = new Paint(Paint.ANTI_ALIAS_FLAG);

    private void drawHomage(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        double sec = (frozenAt >= 0 ? frozenAt : (now - homageStartMs)) / 1000.0;
        homage.draw(canvas, sec, soundOn, statusOverride, now < hudBoostUntil ? 1f : 0f);
        if (running && frozenAt < 0) postInvalidateDelayed(33); // 30 fps cap: it is a screensaver, keep cheap boxes cool
    }

    private void drawAquarium(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        Theme th = Theme.cur();
        canvas.drawColor(th.bgDarker | 0xFF000000);

        int gridCols = 112;
        int gridRows = 36;
        float cw = w / (float) gridCols;
        float ch = h / (float) gridRows;
        aqPaint.setTextSize(Math.min(cw * 1.65f, ch * 0.88f));

        long now = SystemClock.uptimeMillis();
        float sec = now / 1000f;
        // anti burn-in: slow whole-scene pixel shift (a few px per minute) + HUD that fades out and returns
        canvas.save();
        canvas.translate(Math.round(Math.sin(sec / 53f) * 7 + Math.sin(sec / 211f) * 5), Math.round(Math.cos(sec / 67f) * 5));
        float hudCyc = (sec + 3) % 45f;
        float hudA = hudCyc < 10 ? 1 : (hudCyc < 12 ? 1 - (hudCyc - 10) / 2 : (hudCyc > 43 ? (hudCyc - 43) / 2 : 0));
        if (statusOverride != null || now < hudBoostUntil) hudA = 1;

        // 1. TUI Top Status Header + ScreamTracker 3 (ST3 · PM/FC) 4-channel VU meters
        String hdr = statusOverride != null
                ? ("[ omarchy://abyss-node · " + statusOverride + " · theme:" + th.name + " ]")
                : ("[ omarchy://abyss-node · depth 420m · bio-sonar: ONLINE · link: 10Gbps · theme:" + th.name + " ]");
        aqPaint.setColor(th.fgBright);
        aqPaint.setAlpha(Math.round(255 * hudA));
        canvas.drawText(hdr, cw * 1.5f, ch * 1.0f, aqPaint);

        String[] vuBars = {"▁▂▃▄▅▆▇", "▁▂▃▄▅▆▇", "▁▂▃▄▅▆▇", "▁▂▃▄▅▆▇"};
        StringBuilder st3 = new StringBuilder("[ ST3·PM/FC ");
        for (int chIdx = 0; chIdx < 4; chIdx++) {
            int vLen;
            if (!soundOn) {
                vLen = 1;
            } else if (synthRunning) {
                float live = vuAmps[chIdx] * 0.65f + (float) Math.abs(Math.sin(sec * (4.2f + chIdx * 1.3f) + chIdx)) * 0.45f;
                vLen = 1 + Math.min(6, Math.round(live * 6f));
            } else {
                vLen = 2 + (int) (Math.abs(Math.sin(sec * (4.2f + chIdx * 1.3f) + chIdx)) * 5.9f);
            }
            st3.append("C").append(chIdx + 1).append(":").append(vuBars[chIdx], 0, Math.min(7, vLen)).append(' ');
        }
        st3.append(soundOn ? "· 125 BPM · ♪ ST3 AUDIO: ON (M) ]" : "· 125 BPM · ♪ ST3 AUDIO: MUTED (M) ]");
        aqPaint.setColor(soundOn ? th.green : th.fgDim);
        aqPaint.setAlpha(Math.round(255 * hudA));
        canvas.drawText(st3.toString(), cw * 1.5f, ch * 2.0f, aqPaint); // own second row: never overlaps the header

        // 2. VGA / Amiga Copperbar Raster Lines + 2D Interference Plasma Waves (░ ▒ ▓ █)
        int[] copperPalette = {th.accent, th.accent2, th.cyan, th.magenta, th.green, th.yellow};
        for (int r = 0; r < 5; r++) {
            float rasterY = ch * (2.45f + r * 0.16f + (float) Math.sin(sec * 3.2f + r * 0.7f) * 0.12f);
            hudLine.setColor(Theme.withAlpha(copperPalette[(r + (int) (sec * 4f)) % copperPalette.length], 0.55f));
            hudLine.setStrokeWidth(Math.max(2f, ch * 0.11f));
            canvas.drawLine(0, rasterY, w, rasterY, hudLine);
        }

        char[] plasmaGlyphs = {'░', '▒', '▓', '█', '▓', '▒'};
        for (int pr = 0; pr < 2; pr++) {
            StringBuilder pl = new StringBuilder();
            for (int c = 0; c < gridCols; c++) {
                double v = Math.sin(c * 0.14 + sec * 2.4) + Math.cos(pr * 0.9 - sec * 1.8) + Math.sin((c + pr * 6) * 0.09 + sec * 3.1);
                int idx = Math.abs((int) Math.round((v + 3.0) * 2.2)) % plasmaGlyphs.length;
                pl.append(plasmaGlyphs[idx]);
            }
            aqPaint.setColor(pr == 0 ? th.cyan : th.accent2);
            canvas.drawText(pl.toString(), 0, ch * (3.55f + pr * 0.82f), aqPaint);
        }

        // 3. Second Reality Sine-Wave Scroller across the upper abyss
        int scrollOffset = ((int) (sec * 14f)) % SECOND_REALITY_SCROLL.length();
        float charPhase = (sec * 14f) - (int) (sec * 14f);
        for (int c = 0; c < 66; c++) {
            char sc = SECOND_REALITY_SCROLL.charAt((scrollOffset + c) % SECOND_REALITY_SCROLL.length());
            if (sc == ' ') continue;
            float sx = (22f + c - charPhase) * cw;
            float sy = (6.05f + (float) Math.sin(sec * 3.8f + c * 0.22f) * 0.4f) * ch;
            aqPaint.setColor(c % 3 == 0 ? th.yellow : (c % 2 == 0 ? th.cyan : th.fgBright));
            canvas.drawText(String.valueOf(sc), sx, sy, aqPaint);
        }

        // 4. Left & Right Bathymetry Depth Gauge Ticks (100m .. 400m)
        aqPaint.setColor(th.fgDim);
        for (int dg = 1; dg <= 4; dg++) {
            float dy = (5.5f + dg * 5.2f) * ch;
            canvas.drawText((dg * 100) + "m ──", cw * 0.6f, dy, aqPaint);
            canvas.drawText("── " + (dg * 100) + "m", cw * (gridCols - 7.8f), dy, aqPaint);
        }

        // 5. Sweeping Bio-Sonar Radar Scanline (glides across the tank)
        float sonarX = ((sec * 18f) % (gridCols + 20) - 10) * cw;
        hudLine.setColor(Theme.withAlpha(th.cyan, 0.08f));
        hudLine.setStrokeWidth(cw * 2.2f);
        canvas.drawLine(sonarX, ch * 7.0f, sonarX, ch * 31.5f, hudLine);
        hudLine.setColor(Theme.withAlpha(th.green, 0.22f));
        hudLine.setStrokeWidth(2f);
        canvas.drawLine(sonarX, ch * 7.0f, sonarX, ch * 31.5f, hudLine);
        canvas.save();
        canvas.clipRect(-20, ch * 6.6f, cw * (gridCols - 9), h); // creatures never cover the depth ruler labels

        // 6. Deep Background Layer: Schools of small fish (60fps sub-pixel swimming + animated tails)
        String[] fishFramesR = {"<°)))><", "<°)))>{", "<°)))>("};
        String[] fishFramesL = {"><(((°>", "}<(((°>", ")<(((°>"};
        int tailFrame = ((int) (sec * 9f)) % 3;
        for (int s = 0; s < 9; s++) {
            boolean dirRight = (s % 2 == 0);
            float speed = 3.4f + (s % 3) * 1.15f;
            float fx = dirRight
                    ? (((sec * speed + s * 17f) % (gridCols + 16)) - 8f) * cw
                    : ((gridCols - ((sec * speed + s * 19f) % (gridCols + 16))) + 4f) * cw;
            float fy = (7.9f + (s * 2.3f) % 15.0f + (float) Math.sin(sec * 2.6f + s * 1.3f) * 0.65f) * ch;
            aqPaint.setColor(s % 3 == 0 ? th.cyan : (s % 2 == 0 ? th.fgDim : th.accent));
            String fGlyph = dirRight ? fishFramesR[(tailFrame + s) % 3] : fishFramesL[(tailFrame + s) % 3];
            canvas.drawText(fGlyph + "  " + fGlyph, fx, fy, aqPaint);
        }

        // 7. Mid-depth Layer: Leviathan Half-Block Whale + Bio-Sonar Telemetry Tag
        float whaleX = (gridCols - ((sec * 4.6f) % (gridCols + 64))) * cw;
        float whaleY = (7.6f + (float) Math.sin(sec * 1.1f) * 0.85f) * ch;
        drawSprite(canvas, WHALE_SPRITE, whaleX, whaleY, cw, ch, th.accent2, th.cyan);
        if (Math.abs((whaleX + 20 * cw) - sonarX) < 18 * cw) {
            aqPaint.setColor(th.green);
            canvas.drawText("[id:leviathan · 3.2kt · sonar-lock]", whaleX + 8 * cw, whaleY - 0.35f * ch, aqPaint);
        }

        // 8. Rotating 3D Glenz Vector Polyhedron floating above the Sunken [OMARCHY] Core
        drawGlenzPolyhedron(canvas, 55.5f * cw, 19.2f * ch, ch * 3.6f, sec, th);

        // 9. Bioluminescent Jellyfish (60fps sinusoidal bobbing + pulsing bell)
        int jFrame = ((int) (sec * 2.8f)) % 2;
        for (int j = 0; j < 3; j++) {
            float jx = (16f + j * 34f + (float) Math.sin(sec * 0.9f + j * 2.1f) * 4.5f) * cw;
            float jy = (11.5f + j * 2.9f + (float) Math.cos(sec * 1.8f + j) * 1.65f) * ch;
            drawSprite(canvas, JELLY_FRAMES[jFrame], jx, jy, cw, ch, j % 2 == 0 ? th.magenta : th.cyan, th.accent);
            if (Math.abs((jx + 5 * cw) - sonarX) < 12 * cw) {
                aqPaint.setColor(th.yellow);
                canvas.drawText("[id:cyber-jelly · bio-lum]", jx, jy - 0.3f * ch, aqPaint);
            }
        }

        // 10. Foreground Layer: Cyber Reef Shark with [OMARCHY] emblem
        float sharkX = (((sec * 8.8f) % (gridCols + 44)) - 34f) * cw;
        float sharkY = (14.2f + (float) Math.sin(sec * 1.6f) * 0.95f) * ch;
        drawSprite(canvas, SHARK_SPRITE, sharkX, sharkY, cw, ch, th.accent, th.fgBright);

        // 11. Deep Trench Layer: Anglerfish with pulsing lantern + Autonomous [OMARCHY-ROV] Submarine
        float angX = (gridCols - ((sec * 5.8f + 40f) % (gridCols + 36))) * cw;
        float angY = (20.8f + (float) Math.cos(sec * 1.4f) * 0.75f) * ch;
        drawSprite(canvas, ANGLER_SPRITE, angX, angY, cw, ch, th.magenta, th.yellow);

        float subX = (((sec * 6.5f + 52f) % (gridCols + 48)) - 28f) * cw;
        float subY = (22.2f + (float) Math.sin(sec * 1.2f) * 0.45f) * ch;
        drawSprite(canvas, SUB_SPRITE, subX, subY, cw, ch, th.yellow, th.cyan);
        aqPaint.setColor(lerpColor(th.yellow, th.bgDarker, 0.35f));
        canvas.drawText("░░▒▒▓▓██ [id:omarchy-rov-01 · scanning]", subX + 25f * cw, subY + 2f * ch, aqPaint);

        // 12. Rising Bubble Streams (· ° o O) at smooth 60fps float coordinates
        char[] bChars = {'·', '°', 'o', 'O'};
        aqPaint.setColor(th.cyan);
        for (int b = 0; b < 20; b++) {
            float byRow = 31f - ((sec * (2.8f + (b % 4) * 0.55f) + b * 3.7f) % 27f);
            float bxCol = (7f + (b * 13) % (gridCols - 10)) + (float) Math.sin(sec * 3.2f + b) * 0.95f;
            int bIdx = Math.min(3, Math.max(0, (int) ((31f - byRow) / 7f)));
            canvas.drawText(String.valueOf(bChars[bIdx]), bxCol * cw, byRow * ch, aqPaint);
        }

        canvas.restore();
        // 13. 3D Perspective Checkerboard Vector Seabed Floor (Future Crew homage)
        float horizonY = 27.6f * ch;
        float floorBottomY = 35.6f * ch;
        hudLine.setStrokeWidth(1.4f);
        hudLine.setColor(Theme.withAlpha(th.accent, 0.32f));
        for (int rz = 0; rz <= 5; rz++) {
            float frac = (float) Math.pow(rz / 5f, 1.45);
            float ry = horizonY + frac * (floorBottomY - horizonY);
            canvas.drawLine(0, ry, w, ry, hudLine);
        }
        float ScrollGrid = (sec * 1.8f) % 1f;
        for (int gx = -10; gx <= 22; gx++) {
            float topX = (gridCols * 0.5f + (gx - 6f + ScrollGrid) * 4.5f) * cw;
            float botX = (gridCols * 0.5f + (gx - 6f + ScrollGrid) * 14.5f) * cw;
            canvas.drawLine(topX, horizonY, botX, floorBottomY, hudLine);
        }

        // 14. Sunken [OMARCHY · ABYSS CORE] Monolith + Subsea Fiber-Optic Data Conduit
        drawSprite(canvas, SUNKEN_TERM, (41f + (float) Math.sin(sec / 97f) * 22f) * cw, 26.8f * ch, cw, ch, th.accent, th.green);

        StringBuilder fiber = new StringBuilder("[═══╬═══ omarchy-link://fiber-trunk ═══");
        int pulsePos = ((int) (sec * 16f)) % 28;
        for (int fc = 0; fc < 28; fc++) {
            fiber.append(fc == pulsePos || fc == (pulsePos + 14) % 28 ? '⚡' : '═');
        }
        fiber.append("╬═══ 10Gbps ═══]");
        aqPaint.setColor(th.cyan);
        canvas.drawText(fiber.toString(), (14f + (float) Math.sin(sec / 71f) * 12f) * cw, 33.4f * ch, aqPaint);

        // 15. Swaying Multi-Segment Kelp Forests + Coral Reefs + Sideways Crab
        int[] kelpCols = {4, 8, 19, 23, 35, 76, 80, 92, 96, 104};
        for (int k = 0; k < kelpCols.length; k++) {
            int kc = kelpCols[k];
            int kh = 6 + (k % 4);
            aqPaint.setColor(k % 2 == 0 ? th.green : th.cyan);
            for (int seg = 0; seg < kh; seg++) {
                float swayX = (float) Math.sin(sec * 2.8f + seg * 0.55f + k) * 0.65f * cw;
                String glyphSeg = (((int) (sec * 2.5f) + seg + k) % 2 == 0) ? "( " : " )";
                canvas.drawText(glyphSeg, kc * cw + swayX, (32.4f - seg * 0.85f) * ch, aqPaint);
            }
        }

        aqPaint.setColor(th.magenta);
        canvas.drawText("ΨΨ🪸ΨΨ", 11f * cw, 32.3f * ch, aqPaint);
        aqPaint.setColor(th.yellow);
        canvas.drawText("✿❖✿❖", 28f * cw, 32.3f * ch, aqPaint);
        aqPaint.setColor(th.accent2);
        canvas.drawText("Ψ❖Ψ🪸Ψ", 83f * cw, 32.3f * ch, aqPaint);

        float crabCol = 62f + (float) Math.sin(sec * 1.1f) * 14f;
        String crab = (((int) (sec * 6f)) % 2 == 0) ? "(\\/)(°,,°)(\\/)" : "( /)(°,,°)(\\ )";
        aqPaint.setColor(th.red);
        canvas.drawText(crab, crabCol * cw, 34.4f * ch, aqPaint);
        canvas.restore();

        if (running && frozenAt < 0) {
            postInvalidateOnAnimation();
        }
    }

    private void drawGlenzPolyhedron(Canvas canvas, float cx, float cy, float scale, float sec, Theme th) {
        float ax = sec * 1.15f, ay = sec * 1.65f;
        float cosX = (float) Math.cos(ax), sinX = (float) Math.sin(ax);
        float cosY = (float) Math.cos(ay), sinY = (float) Math.sin(ay);
        float[] px = new float[GLENZ_V.length];
        float[] py = new float[GLENZ_V.length];
        for (int i = 0; i < GLENZ_V.length; i++) {
            float x = GLENZ_V[i][0], y = GLENZ_V[i][1], z = GLENZ_V[i][2];
            float x1 = x * cosY - z * sinY;
            float z1 = x * sinY + z * cosY;
            float y2 = y * cosX - z1 * sinX;
            float z2 = y * sinX + z1 * cosX;
            float persp = 3.2f / (3.2f + z2 * 0.45f);
            px[i] = cx + x1 * scale * persp;
            py[i] = cy + y2 * scale * persp;
        }
        hudLine.setStrokeWidth(2.2f);
        for (int e = 0; e < GLENZ_E.length; e++) {
            int a = GLENZ_E[e][0], b = GLENZ_E[e][1];
            hudLine.setColor(e % 2 == 0 ? th.cyan : th.magenta);
            canvas.drawLine(px[a], py[a], px[b], py[b], hudLine);
        }
        aqPaint.setColor(th.fgBright);
        for (int i = 0; i < GLENZ_V.length; i++) {
            canvas.drawText("◆", px[i] - 4f, py[i] + 4f, aqPaint);
        }
    }

    private void drawSprite(Canvas canvas, String[] lines, float x, float y, float cw, float ch, int mainColor, int hiColor) {
        for (int r = 0; r < lines.length; r++) {
            String line = lines[r];
            float ry = y + r * ch * 0.88f;
            if (line.indexOf('●') >= 0 || line.indexOf('*') >= 0 || line.contains("OMARCHY")) {
                aqPaint.setColor(hiColor);
            } else {
                aqPaint.setColor(mainColor);
            }
            canvas.drawText(line, x, ry, aqPaint);
        }
    }

    private void drawIntro(Canvas canvas, int i, int r, int c, float x, float y, float p, int finalColor) {
        float s = seed[i];
        switch (effect) {
            case FX_DECRYPT: {
                float appear = s * 0.35f, lock = 0.45f + 0.5f * seed2[i];
                if (p < appear) return;
                if (p < lock) {
                    drawGlyph(canvas, x, y, (seed2[i] > 0.5f ? Theme.m(0xFF565F89) : Theme.m(0xFF414868)));
                } else {
                    drawCell(canvas, cellChar[i], x, y, lerpColor(Theme.m(0xFFC0CAF5), finalColor, ease((p - lock) * 4f)));
                }
                return;
            }
            case FX_RAIN: {
                float start = s * 0.65f, dur = 0.3f;
                if (p < start) return;
                float q = Math.min(1f, (p - start) / dur);
                float fromY = -cellH * (2 + seed2[i] * 6) - (y - getTop());
                float yy = y + fromY * (1f - easeIn(q));
                int col = q < 1f ? Theme.m(0xFF7DCFFF) : lerpColor(0xFFFFFFFF, finalColor, Math.min(1f, (p - start - dur) * 6f));
                drawCell(canvas, cellChar[i], x, yy, col);
                return;
            }
            case FX_BEAMS: {
                float rowDelay = r / (float) Math.max(1, rows) * 0.35f;
                float pos = (p - rowDelay) / 0.5f;
                float cellPos = (r % 2 == 0) ? c / (float) cols : 1f - c / (float) cols;
                float since = pos - cellPos;
                if (since < 0f) return;
                int col = since < 0.06f ? 0xFFFFFFFF : lerpColor(Theme.m(0xFF7DCFFF), finalColor, Math.min(1f, since * 3f));
                drawCell(canvas, cellChar[i], x, y, col);
                return;
            }
            case FX_PRINT: {
                float order = (r * cols + c) / (float) (rows * cols);
                if (p * 1.1f < order) {
                    int cursorRow = Math.min(rows - 1, (int) (p * 1.1f * rows));
                    if (r == cursorRow && Math.abs(p * 1.1f - order) < 1.5f / (rows * cols)) {
                        drawCell(canvas, '\u2588', x, y, Theme.m(0xFFC0CAF5));
                    }
                    return;
                }
                drawCell(canvas, cellChar[i], x, y, lerpColor(Theme.m(0xFFC0CAF5), finalColor, Math.min(1f, (p * 1.1f - order) * 5f)));
                return;
            }
            case FX_SLIDE: {
                float start = r / (float) Math.max(1, rows) * 0.4f;
                float q = Math.min(1f, Math.max(0f, (p - start) / 0.55f));
                if (q <= 0f) return;
                float dir = r % 2 == 0 ? -1f : 1f;
                float xx = x + dir * getWidth() * (1f - ease(q));
                drawCell(canvas, cellChar[i], xx, y, lerpColor(Theme.m(0xFF414868), finalColor, q));
                return;
            }
            case FX_MATRIX: {
                float delay = hash(c * 12.9898f + salt) * 0.45f;
                float head = (p - delay) / 0.5f * (rows + 4);
                float since = head - r;
                if (since < 0f) return;
                if (since < 1f) drawGlyph(canvas, x, y, Theme.m(0xFFC0CAF5));
                else if (since < 4f) drawGlyph(canvas, x, y, lerpColor(Theme.m(0xFF9ECE6A), Theme.m(0xFF414868), (since - 1f) / 3f));
                else drawCell(canvas, cellChar[i], x, y, lerpColor(Theme.m(0xFF9ECE6A), finalColor, Math.min(1f, (since - 4f) / 3f)));
                return;
            }
            case FX_WIPE: {
                float order = (c / (float) cols + r / (float) Math.max(1, rows)) / 2f;
                float since = p * 1.25f - order;
                if (since < 0f) return;
                int col = since < 0.04f ? Theme.m(0xFF7DCFFF) : lerpColor(Theme.m(0xFFC0CAF5), finalColor, Math.min(1f, since * 5f));
                drawCell(canvas, cellChar[i], x, y, col);
                return;
            }
            case FX_BURN: {
                float ignite = (1f - r / (float) Math.max(1, rows)) * 0.45f + s * 0.3f;
                float since = p - ignite;
                if (since < 0f) {
                    drawCell(canvas, cellChar[i], x, y, Theme.m(0xFF292E42));
                } else if (since < 0.08f) {
                    drawGlyph(canvas, x, y, lerpColor(Theme.m(0xFFF7768E), Theme.m(0xFFFF9E64), since / 0.08f));
                } else if (since < 0.16f) {
                    drawCell(canvas, cellChar[i], x, y, lerpColor(Theme.m(0xFFE0AF68), finalColor, (since - 0.08f) / 0.08f));
                } else {
                    drawCell(canvas, cellChar[i], x, y, finalColor);
                }
                return;
            }
            case FX_EXPAND:
            default: {
                float cx = (getWidth() - cols * cellW) / 2f + cols * cellW / 2f, cy = getHeight() / 2f;
                float q = ease(Math.min(1f, Math.max(0f, (p - s * 0.25f) / 0.7f)));
                if (q <= 0f) return;
                float xx = cx + (x - cx) * q, yy = cy + (y - cy) * q;
                if (q < 1f) drawGlyph(canvas, xx, yy, lerpColor(Theme.m(0xFFBB9AF7), finalColor, q));
                else drawCell(canvas, cellChar[i], xx, yy, finalColor);
            }
        }
    }

    private void drawCell(Canvas canvas, char ch, float x, float y, int color) {
        block.setColor(color);
        switch (ch) {
            case '\u2588': canvas.drawRect(x, y, x + cellW + 0.5f, y + cellH + 0.5f, block); break;
            case '\u2584': canvas.drawRect(x, y + cellH / 2f, x + cellW + 0.5f, y + cellH + 0.5f, block); break;
            case '\u2580': canvas.drawRect(x, y, x + cellW + 0.5f, y + cellH / 2f + 0.5f, block); break;
            case '\u258C': canvas.drawRect(x, y, x + cellW / 2f + 0.5f, y + cellH + 0.5f, block); break;
            case '\u2590': canvas.drawRect(x + cellW / 2f, y, x + cellW + 0.5f, y + cellH + 0.5f, block); break;
            default:
                glyph.setColor(color);
                canvas.drawText(String.valueOf(ch), x + cellW / 2f, y + cellH * 0.78f, glyph);
        }
    }

    private void drawGlyph(Canvas canvas, float x, float y, int color) {
        glyph.setColor(color);
        char g = TextFx.GLYPHS.charAt(random.nextInt(TextFx.GLYPHS.length()));
        canvas.drawText(String.valueOf(g), x + cellW / 2f, y + cellH * 0.78f, glyph);
    }

    private static float hash(float v) {
        double h = Math.sin(v) * 43758.5453;
        return (float) (h - Math.floor(h));
    }

    private static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }

    private static float easeIn(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t;
    }

    static int lerpColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }
}
