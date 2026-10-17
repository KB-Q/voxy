package me.cortex.voxy.autotest;

import com.mojang.blaze3d.platform.NativeImage;

public final class ImageMetrics {
    //Fraction of pixels whose largest channel difference exceeds the threshold
    public static double differingFraction(NativeImage a, NativeImage b, int threshold) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return -1;
        long differing = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (maxChannelDiff(a.getPixelRGBA(x, y), b.getPixelRGBA(x, y)) > threshold) differing++;
            }
        }
        return differing / (double) ((long) a.getWidth() * a.getHeight());
    }

    //Mean absolute per-channel difference over the colour channels, in 0..255
    public static double meanAbsDiff(NativeImage a, NativeImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return -1;
        long total = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int p = a.getPixelRGBA(x, y), q = b.getPixelRGBA(x, y);
                for (int s = 0; s < 24; s += 8) total += Math.abs(((p >> s) & 0xFF) - ((q >> s) & 0xFF));
            }
        }
        return total / (3.0 * a.getWidth() * a.getHeight());
    }

    private static int maxChannelDiff(int p, int q) {
        int m = 0;
        for (int s = 0; s < 24; s += 8) m = Math.max(m, Math.abs(((p >> s) & 0xFF) - ((q >> s) & 0xFF)));
        return m;
    }
}
