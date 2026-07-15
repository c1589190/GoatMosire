package com.goatmosire.service;

/**
 * Simplex-like 2D gradient noise.
 * Shared by {@link MapGenerator} and {@link ContourQueryEngine}.
 */
final class SimplexNoise {
    private final long seed;

    SimplexNoise(long seed) {
        this.seed = seed;
    }

    double noise2(double x, double y) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
        double xf = x - xi, yf = y - yi;
        double n00 = dotGrid(xi, yi, xf, yf);
        double n10 = dotGrid(xi + 1, yi, xf - 1, yf);
        double n01 = dotGrid(xi, yi + 1, xf, yf - 1);
        double n11 = dotGrid(xi + 1, yi + 1, xf - 1, yf - 1);
        double u = smooth(xf), v = smooth(yf);
        return lerp(lerp(n00, n10, u), lerp(n01, n11, u), v);
    }

    private double dotGrid(int ix, int iy, double dx, double dy) {
        long h = hash(ix, iy);
        double angle = (h & 0xFFFF) * (2.0 * Math.PI / 65536.0);
        return Math.cos(angle) * dx + Math.sin(angle) * dy;
    }

    private long hash(int x, int y) {
        long h = seed;
        h = h * 6364136223846793005L + x;
        h = h * 6364136223846793005L + y;
        h = (h ^ (h >>> 33)) * 0xFF51AFD7ED558CCDL;
        h = (h ^ (h >>> 33)) * 0xC4CEB9FE1A85EC53L;
        return h ^ (h >>> 33);
    }

    private static double smooth(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double a, double b, double t) {
        return a + t * (b - a);
    }
}
