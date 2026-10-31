package com.omarchytv.home;

/**
 * Tom Ballard's Grok Bot avatar on Android TV: his 14 shapes, 11-colour palette, eyes and poses, from
 * {@link GrokbotAvatarData} (generated from the vendored MIT copy of his qml/shapes.js, palette.js and face.js,
 * v0.1.0, commit a2efb00; (c) 2026 Tom Ballard). The math below is a line-by-line Java port of his face.js
 * {@code eye}, {@code rotation}, {@code look}, {@code fitAt}, {@code centroid} and {@code rotatePoints}, so the TV
 * draws what webOS/Tizen (and his desktop companion) draw. Pure Java, no Android types, so it can be unit-tested.
 */
final class GrokbotAvatar {
    static final int COUNT = GrokbotAvatarData.NAMES.length;
    static final String NAMES_FIRST = GrokbotAvatarData.NAMES[0];
    private static final float[][] POINTS = new float[COUNT][];
    private static final float[][] EYES = new float[COUNT][];
    private static final float[][] LOOK = new float[COUNT][];
    private static float[] stem;

    // face.js constants (the look measured from his recording of Bob).
    static final float LOOK_X = 0.41f, LOOK_UP = 0.31f, LOOK_DOWN = 0.29f;
    static final float FRONT_SEP = 0.5f, FRONT_LENGTH = 0.6f, FRONT_WIDTH = 0.29f;
    static final float PICKER_ROLL = -25f;
    static final int FIT_STEPS = 9;

    private GrokbotAvatar() {
    }

    static float[] parse(String csv) {
        String[] p = csv.split(",");
        float[] out = new float[p.length];
        for (int i = 0; i < p.length; i++) out[i] = Float.parseFloat(p[i].trim());
        return out;
    }

    // ---- shapes ----

    /** Index of a shape or alias name in his NAMES order, or -1 when he has no such name. */
    static int index(String name) {
        String n = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < COUNT; i++) if (GrokbotAvatarData.NAMES[i].equals(n)) return i;
        String[] a = GrokbotAvatarData.SHAPE_ALIASES;
        for (int i = 0; i + 1 < a.length; i += 2) {
            if (a[i].equals(n)) {
                for (int k = 0; k < COUNT; k++) if (GrokbotAvatarData.NAMES[k].equals(a[i + 1])) return k;
            }
        }
        return -1;
    }

    static boolean known(String name) {
        return index(name) >= 0;
    }

    /** His resolve(): the canonical name, "blob" for anything unknown. */
    static String resolve(String name) {
        int i = index(name);
        return GrokbotAvatarData.NAMES[i < 0 ? 0 : i];
    }

    static String next(String name) {
        int i = index(name);
        return GrokbotAvatarData.NAMES[(i + 1) % COUNT];
    }

    /** Outline as x0, y0, x1, y1… (120 points, shape units, y down). */
    static synchronized float[] points(int i) {
        if (POINTS[i] == null) POINTS[i] = parse(GrokbotAvatarData.POINTS[i]);
        return POINTS[i];
    }

    /** Two resting eyes: x, y, length, width, angle (degrees), twice. */
    static synchronized float[] eyes(int i) {
        if (EYES[i] == null) EYES[i] = parse(GrokbotAvatarData.EYES[i]);
        return EYES[i];
    }

    /** face x, face y, size, then 81 fit values. */
    static synchronized float[] look(int i) {
        if (LOOK[i] == null) LOOK[i] = parse(GrokbotAvatarData.LOOK[i]);
        return LOOK[i];
    }

    static synchronized float[] stem() {
        if (stem == null) stem = parse(GrokbotAvatarData.STEM);
        return stem;
    }

    static boolean hasStem(int i) {
        return GrokbotAvatarData.NAMES[i].equals(GrokbotAvatarData.STEM_SHAPE);
    }

    // ---- palette ----

    static final String[] COLOR_NAMES = colorNames();

    private static String[] colorNames() {
        String[] c = GrokbotAvatarData.COLORS;
        String[] out = new String[c.length / 4];
        for (int i = 0; i < out.length; i++) out[i] = c[i * 4];
        return out;
    }

    /** Row of a colour or alias name in his palette, or -1. */
    static int colorIndex(String name) {
        String n = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
        String[] a = GrokbotAvatarData.COLOR_ALIASES;
        for (int i = 0; i + 1 < a.length; i += 2) if (a[i].equals(n)) n = a[i + 1];
        for (int i = 0; i < COLOR_NAMES.length; i++) if (COLOR_NAMES[i].equals(n)) return i;
        return -1;
    }

    static int argb(String hex) {
        return 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
    }

    /** His fillFor(name, dark = true): the TV is a dark surface, so black draws as white. Unknown = orange. */
    static int fill(String name) {
        int i = colorIndex(name);
        if (i < 0) i = colorIndex("orange");
        String[] c = GrokbotAvatarData.COLORS;
        return argb(c[i * 4 + 2].isEmpty() ? c[i * 4 + 1] : c[i * 4 + 2]);
    }

    /** Eye colour on the TV: his CUTOUT, or black on the white-drawn black (same as webos/app/companion.js). */
    static int eye(String name) {
        int i = colorIndex(name);
        if (i < 0) return argb(GrokbotAvatarData.CUTOUT);
        String[] c = GrokbotAvatarData.COLORS;
        return argb(c[i * 4 + 2].isEmpty() ? c[i * 4 + 3] : c[i * 4 + 1]);
    }

    /** Eye colour for a raw fill (a PC bot's hex): light eyes on a very dark body. */
    static int eyeForFill(int argb) {
        float l = (0.2126f * ((argb >> 16) & 255) + 0.7152f * ((argb >> 8) & 255) + 0.0722f * (argb & 255)) / 255f;
        return l < 0.16f ? 0xFFF4F4F5 : argb(GrokbotAvatarData.CUTOUT);
    }

    // ---- poses: tilt, height, open ----

    static float[] pose(String name) {
        String[] p = GrokbotAvatarData.POSES;
        for (int i = 0; i + 3 < p.length; i += 4) {
            if (p[i].equals(name)) {
                return new float[]{Float.parseFloat(p[i + 1]), Float.parseFloat(p[i + 2]), Float.parseFloat(p[i + 3])};
            }
        }
        return new float[]{0f, 1f, 1f};   // idle
    }

    // ---- face.js ----

    /** eye(spec, rad, open, heightScale) → {w, h, r} in pixels; spec = length, width of one eye. */
    static float[] eyeBox(float length, float width, float rad, float open, float heightScale) {
        float o = Float.isNaN(open) ? 0f : Math.max(0f, Math.min(1f, open));
        float baseW = Math.max(1.6f, width * rad);
        float fullH = length * rad * (heightScale > 0 ? heightScale : 1f);
        float lid = Math.max(1.4f, rad * 0.04f);
        float h = Math.max(lid, fullH * o);
        float w = baseW * (o < 0.3f ? 1f + (0.3f - o) * 0.6f : 1f);
        return new float[]{w, h, Math.min(w, h) / 2f};
    }

    /** rotation(spec, extraTilt): degrees to turn an upright rectangle so its long axis lies along angle. */
    static float rotation(float angle, float extraTilt) {
        return angle - 90f + (Float.isNaN(extraTilt) ? 0f : extraTilt);
    }

    static float lookRoll(float gx, float gy) {
        float up = Math.max(0f, -gy);
        float side = gx > 0 ? -10f * gx : gx < 0 ? -3f * gx : 0f;
        return -5f * up - 20f * up * Math.abs(gx) + side * (1f - up);
    }

    static float[] rotate(float x, float y, float deg) {
        double r = deg * Math.PI / 180.0;
        float c = (float) Math.cos(r), s = (float) Math.sin(r);
        return new float[]{x * c - y * s, x * s + y * c};
    }

    /** Area centroid of a closed outline (flat x, y pairs). */
    static float[] centroid(float[] pts) {
        int n = pts.length / 2;
        double area = 0, cx = 0, cy = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double ax = pts[i * 2], ay = pts[i * 2 + 1], bx = pts[j * 2], by = pts[j * 2 + 1];
            double cross = ax * by - bx * ay;
            area += cross;
            cx += (ax + bx) * cross;
            cy += (ay + by) * cross;
        }
        if (Math.abs(area) < 1e-9) return new float[]{0f, 0f};
        return new float[]{(float) (cx / (3 * area)), (float) (cy / (3 * area))};
    }

    static float[] rotatePoints(float[] pts, float deg, float cx, float cy) {
        if (Math.abs(deg) < 1e-6f) return pts;
        float[] out = new float[pts.length];
        for (int i = 0; i + 1 < pts.length; i += 2) {
            float[] p = rotate(pts[i] - cx, pts[i + 1] - cy, deg);
            out[i] = cx + p[0];
            out[i + 1] = cy + p[1];
        }
        return out;
    }

    /** fitAt(fit, gx, gy): share of a gaze the eyes can take; fit starts at offset 3 of a look row. */
    static float fitAt(float[] look, float gx, float gy) {
        if (look == null || look.length != 3 + FIT_STEPS * FIT_STEPS) return 1f;
        int n = FIT_STEPS - 1;
        float fx = (Math.max(-1f, Math.min(1f, gx)) + 1f) / 2f * n;
        float fy = (Math.max(-1f, Math.min(1f, gy)) + 1f) / 2f * n;
        int i = Math.min(n - 1, (int) Math.floor(fx));
        int j = Math.min(n - 1, (int) Math.floor(fy));
        float u = fx - i, v = fy - j;
        return (at(look, i, j) * (1 - u) + at(look, i + 1, j) * u) * (1 - v)
                + (at(look, i, j + 1) * (1 - u) + at(look, i + 1, j + 1) * u) * v;
    }

    private static float at(float[] look, int a, int b) {
        return look[3 + b * FIT_STEPS + a];
    }

    /** look(points, params, gx, gy): the face turned toward a gaze (each -1..1, +y down). */
    static final class Look {
        float roll, turn, cx, cy;
        /** x, y, length, width, angle for each of the two eyes, in the picker's frame. */
        final float[] eyes = new float[10];
    }

    static Look look(int shape, float gx, float gy) {
        if (Float.isNaN(gx)) gx = 0f;
        if (Float.isNaN(gy)) gy = 0f;
        float[] params = look(shape);
        float fit = fitAt(params, gx, gy);
        float x = Math.max(-1f, Math.min(1f, gx * fit));
        float y = Math.max(-1f, Math.min(1f, gy * fit));
        float[] c = centroid(points(shape));
        float k = params[2];
        float roll = lookRoll(x, y);
        float ox = k * LOOK_X * x, oy = k * (y < 0 ? LOOK_UP : LOOK_DOWN) * y;
        float[] pair = rotate(params[0] + ox, params[1] + oy, roll);
        float sep = k * (FRONT_SEP - 0.07f * Math.abs(x));
        float[] half = rotate(sep / 2f, 0f, roll);
        float length = k * FRONT_LENGTH * (1f - 0.12f * Math.abs(x)) * (1f - 0.07f * Math.abs(y));
        float width = k * FRONT_WIDTH * (1f - 0.3f * Math.abs(x));
        float angle = 90f + 1.05f * roll;
        Look l = new Look();
        l.roll = roll;
        l.turn = roll - PICKER_ROLL;
        l.cx = c[0];
        l.cy = c[1];
        for (int e = 0, side = -1; side <= 1; side += 2, e++) {
            l.eyes[e * 5] = c[0] + pair[0] + side * half[0];
            l.eyes[e * 5 + 1] = c[1] + pair[1] + side * half[1];
            l.eyes[e * 5 + 2] = length;
            l.eyes[e * 5 + 3] = width * (1f - 0.18f * x * side);   // the far eye is the narrower one
            l.eyes[e * 5 + 4] = angle;
        }
        return l;
    }
}
