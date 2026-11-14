package com.omarchytv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.InputStream;

/**
 * v2.20 Home wallpaper: a slideshow of still fractal images (our own art, bundled in assets/fractals).
 * No live GL on Home. The live FractalView stays on the Radiomarchy full-screen view and screensaver.
 */
final class FractalSlides {
    static final String DIR = "fractals/";
    static final int INTERVAL_MS = 12000, FADE_MS = 1600;
    static final int WIDTH = 1280, HEIGHT = 720;

    static final String[] FILES = {
            "01-mandelbrot-seahorses.jpg",
            "02-mandelbrot-elephants.jpg",
            "03-mandelbrot-spiral.jpg",
            "04-julia-dendrite.jpg",
            "05-julia-rabbit.jpg",
            "06-julia-silk.jpg",
            "07-burning-ship.jpg",
            "08-tricorn.jpg",
            "09-newton.jpg",
            "10-multibrot.jpg",
            "11-kaliset.jpg",
            "12-mandelbrot-antenna.jpg"
    };

    /** Four RGB triples (0..1) per slide, in the same order as FractalView palettes. */
    static final float[][] PALS = {
            { 0.4235f, 0.0471f, 0.8118f, 0.45f, 0.08f, 0.42f, 0.2235f, 0.1569f, 0.2902f, 0.95f, 0.8f, 0.7f },
            { 0.2078f, 0.1843f, 0.0588f, 0.45f, 0.22f, 0.05f, 0.6118f, 0.4745f, 0.3647f, 1.0f, 0.95f, 0.7f },
            { 0.8078f, 0.8431f, 1.0f, 0.48f, 0.64f, 0.97f, 0.1098f, 0.1961f, 0.2392f, 0.49f, 0.81f, 1.0f },
            { 0.0863f, 0.2078f, 0.1882f, 0.2f, 0.4f, 0.7f, 0.3686f, 0.498f, 0.9529f, 0.95f, 0.98f, 1.0f },
            { 0.3294f, 0.1412f, 0.1412f, 1.0f, 0.82f, 0.25f, 1.0f, 0.6118f, 1.0f, 0.36f, 0.36f, 1.0f },
            { 0.6118f, 0.3373f, 1.0f, 0.35f, 0.15f, 0.55f, 0.1765f, 0.1725f, 0.298f, 0.95f, 0.85f, 0.7f },
            { 0.04f, 0.0f, 0.02f, 0.45f, 0.02f, 0.1f, 0.9f, 0.15f, 0.2f, 1.0f, 0.8f, 0.55f },
            { 0.0392f, 0.2471f, 0.0667f, 0.08f, 0.32f, 0.18f, 0.3529f, 0.549f, 0.4f, 0.95f, 0.9f, 0.45f },
            { 0.098f, 1.0f, 0.8353f, 1.0f, 0.82f, 0.25f, 1.0f, 0.0392f, 0.4588f, 0.36f, 0.36f, 1.0f },
            { 0.2275f, 0.1961f, 0.1059f, 0.55f, 0.2f, 0.08f, 0.7098f, 0.3451f, 0.2784f, 1.0f, 0.88f, 0.65f },
            { 0.1647f, 0.2118f, 0.0235f, 0.1f, 0.4f, 0.38f, 0.6314f, 0.902f, 0.9608f, 0.95f, 0.9f, 0.55f },
            { 0.1176f, 0.1961f, 0.2392f, 0.02f, 0.28f, 0.42f, 0.2118f, 0.5137f, 0.9294f, 0.85f, 0.95f, 0.8f }
    };

    private FractalSlides() {
    }

    static int count() {
        return FILES.length;
    }

    static String asset(int i) {
        return DIR + FILES[Math.floorMod(i, FILES.length)];
    }

    static float[] pal(int i) {
        return PALS[Math.floorMod(i, PALS.length)];
    }

    /** Accent and a second accent, contrast-safe against bg. */
    static int[] accents(int i, int bg) {
        float[] p = pal(i);
        int a = FractalView.ensureContrast(FractalView.rgb(p[0], p[1], p[2]), bg);
        int a2 = FractalView.ensureContrast(FractalView.rgb(p[6], p[7], p[8]), bg);
        return new int[] { a, a2 };
    }

    /** inSampleSize for the 1280×720 stills: 2 on a 2 GB-class TV, 1 otherwise (keeps the fallback sharp). */
    static int sampleSize(long ramBytes) {
        return FractalView.tightRam(ramBytes) ? 2 : 1;
    }

    static Bitmap decode(Context c, int i) {
        long ram = 0;
        try {
            ram = FractalView.totalRam(c);
        } catch (Throwable ignored) {
        }
        return decode(c, i, sampleSize(ram));
    }

    static Bitmap decode(Context c, int i, int sample) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        o.inSampleSize = Math.max(1, sample);
        try (InputStream in = c.getAssets().open(asset(i))) {
            return BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean catalogOk() {
        if (FILES.length < 8 || FILES.length != PALS.length) return false;
        if (INTERVAL_MS < 10_000 || INTERVAL_MS > 15_000) return false;
        if (FADE_MS < 800 || FADE_MS >= INTERVAL_MS) return false;
        for (float[] p : PALS) if (p == null || p.length != 12) return false;
        return true;
    }
}
