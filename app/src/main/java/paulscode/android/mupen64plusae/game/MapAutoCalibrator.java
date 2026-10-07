/*
 * Mupen64PlusAE, an N64 emulator for the Android platform
 *
 * This file is part of Mupen64PlusAE.
 *
 * Mupen64PlusAE is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Mupen64PlusAE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Mupen64PlusAE. If
 * not, see <http://www.gnu.org/licenses/>.
 */
package paulscode.android.mupen64plusae.game;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Works out where game coordinates land on a map picture without the user's help.
 *
 * It collects spots that must be on solid ground (where the player has walked, and where the
 * level's objects stand) and fits them to the opaque part of the picture: the 8 ways the picture
 * could be turned/flipped, then the scale and offset that put the most spots on the island.
 * The guess gets better the more of the level the player has seen.
 */
final class MapAutoCalibrator
{
    private static final int GRID = 160;          // mask resolution (cells across the long side)
    private static final float CELL = 40f;        // game units: spots closer than this count once
    private static final int MAX_POINTS = 900;
    static final int MIN_POINTS = 12;

    private final boolean[] mMask;
    private final int mMaskW, mMaskH;
    private final float mMaskScale;               // map pixels per mask cell
    private final float mIslandL, mIslandT, mIslandR, mIslandB; // opaque area, map pixels

    private final List<float[]> mPoints = new ArrayList<>();
    private final Set<Long> mCells = new HashSet<>();
    private int mFittedCount = -1;
    private double[] mAffine;
    private float mScore;

    MapAutoCalibrator(Bitmap map)
    {
        int w = map.getWidth(), h = map.getHeight();
        mMaskScale = Math.max(w, h) / (float) GRID;
        mMaskW = Math.max(1, Math.round(w / mMaskScale));
        mMaskH = Math.max(1, Math.round(h / mMaskScale));
        mMask = new boolean[mMaskW * mMaskH];
        int minX = mMaskW, minY = mMaskH, maxX = -1, maxY = -1;
        for (int y = 0; y < mMaskH; y++) {
            for (int x = 0; x < mMaskW; x++) {
                int px = Math.min(w - 1, (int) ((x + 0.5f) * mMaskScale));
                int py = Math.min(h - 1, (int) ((y + 0.5f) * mMaskScale));
                boolean solid = (map.getPixel(px, py) >>> 24) > 128;
                mMask[y * mMaskW + x] = solid;
                if (solid) {
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) { minX = 0; minY = 0; maxX = mMaskW - 1; maxY = mMaskH - 1; }
        mIslandL = minX * mMaskScale; mIslandT = minY * mMaskScale;
        mIslandR = (maxX + 1) * mMaskScale; mIslandB = (maxY + 1) * mMaskScale;
    }

    /** A spot on solid ground (game x, z). Returns true if it was new. */
    boolean add(float x, float z)
    {
        if (Float.isNaN(x) || Float.isNaN(z) || Math.abs(x) > 1e6f || Math.abs(z) > 1e6f) return false;
        if (x == 0 && z == 0) return false; // unplaced objects sit at the origin
        long cell = (((long) Math.floor(x / CELL)) << 32) ^ (((long) Math.floor(z / CELL)) & 0xFFFFFFFFL);
        if (!mCells.add(cell)) return false;
        if (mPoints.size() >= MAX_POINTS) mPoints.remove(0);
        mPoints.add(new float[]{x, z});
        return true;
    }

    int size() { return mPoints.size(); }

    /** Saved points "x,z;x,z;..." (to keep improving across sessions). */
    String save()
    {
        StringBuilder sb = new StringBuilder();
        for (float[] p : mPoints) {
            if (sb.length() > 0) sb.append(';');
            sb.append(Math.round(p[0])).append(',').append(Math.round(p[1]));
        }
        return sb.toString();
    }

    void load(String saved)
    {
        if (saved == null || saved.isEmpty()) return;
        for (String pair : saved.split(";")) {
            int c = pair.indexOf(',');
            if (c <= 0) continue;
            try {
                add(Float.parseFloat(pair.substring(0, c)), Float.parseFloat(pair.substring(c + 1)));
            } catch (NumberFormatException ignored) {}
        }
    }

    /** The current best guess (px = a0*x + a1*z + a2, py = a3*x + a4*z + a5), or null. */
    double[] affine()
    {
        if (mPoints.size() < MIN_POINTS) return null;
        // Refit when there's noticeably more to go on (a fit takes a moment)
        int n = mPoints.size();
        if (mAffine == null || n >= mFittedCount + Math.max(6, mFittedCount / 8)) fit();
        return mAffine;
    }

    float score() { return mScore; }

    private boolean solid(double px, double py)
    {
        int x = (int) (px / mMaskScale), y = (int) (py / mMaskScale);
        return x >= 0 && y >= 0 && x < mMaskW && y < mMaskH && mMask[y * mMaskW + x];
    }

    private static float percentile(float[] v, float p)
    {
        float[] s = v.clone();
        Arrays.sort(s);
        int i = Math.max(0, Math.min(s.length - 1, Math.round(p * (s.length - 1))));
        return s[i];
    }

    private void fit()
    {
        mFittedCount = mPoints.size();
        int n = mPoints.size();
        float[] xs = new float[n], zs = new float[n];
        for (int i = 0; i < n; i++) { xs[i] = mPoints.get(i)[0]; zs[i] = mPoints.get(i)[1]; }

        double[] best = null;
        float bestScore = -1;
        // 8 orientations: u/v come from x/z, maybe swapped, maybe mirrored
        for (int o = 0; o < 8; o++) {
            boolean swap = (o & 4) != 0, flipU = (o & 1) != 0, flipV = (o & 2) != 0;
            float[] us = new float[n], vs = new float[n];
            for (int i = 0; i < n; i++) {
                float u = swap ? zs[i] : xs[i], v = swap ? xs[i] : zs[i];
                us[i] = flipU ? -u : u;
                vs[i] = flipV ? -v : v;
            }
            float u0 = percentile(us, 0.02f), u1 = percentile(us, 0.98f);
            float v0 = percentile(vs, 0.02f), v1 = percentile(vs, 0.98f);
            if (u1 - u0 < 1 || v1 - v0 < 1) continue;
            // Start: the spots' extent fills the island's extent, then search around it
            double baseSu = (mIslandR - mIslandL) / (u1 - u0), baseSv = (mIslandB - mIslandT) / (v1 - v0);
            double cu = (u0 + u1) / 2.0, cv = (v0 + v1) / 2.0;
            double ci = (mIslandL + mIslandR) / 2.0, cj = (mIslandT + mIslandB) / 2.0;
            double iw = mIslandR - mIslandL, ih = mIslandB - mIslandT;
            for (double ks : new double[]{0.7, 0.8, 0.9, 1.0}) {
                for (double ka = 0.85; ka <= 1.16; ka += 0.075) {   // aspect
                    double su = baseSu * ks * ka, sv = baseSv * ks / ka;
                    for (double dx = -0.15; dx <= 0.151; dx += 0.05) {
                        for (double dy = -0.15; dy <= 0.151; dy += 0.05) {
                            double ox = ci + dx * iw - cu * su, oy = cj + dy * ih - cv * sv;
                            int in = 0;
                            for (int i = 0; i < n; i++) if (solid(us[i] * su + ox, vs[i] * sv + oy)) in++;
                            // Prefer more spots on the island; break ties toward a bigger fit
                            float sc = in / (float) n + (float) ks * 0.001f;
                            if (sc > bestScore) {
                                bestScore = sc;
                                // Back to x/z: u = (swap ? z : x) * (flipU ? -1 : 1), same for v
                                double ux = swap ? 0 : (flipU ? -su : su), uz = swap ? (flipU ? -su : su) : 0;
                                double vx = swap ? (flipV ? -sv : sv) : 0, vz = swap ? 0 : (flipV ? -sv : sv);
                                best = new double[]{ux, uz, ox, vx, vz, oy};
                            }
                        }
                    }
                }
            }
        }
        if (best != null) best = refine(xs, zs, best);
        mAffine = best;
        mScore = Math.max(0, bestScore);
    }

    private int inside(float[] xs, float[] zs, double[] a)
    {
        int in = 0;
        for (int i = 0; i < xs.length; i++) {
            if (solid(a[0] * xs[i] + a[1] * zs[i] + a[2], a[3] * xs[i] + a[4] * zs[i] + a[5])) in++;
        }
        return in;
    }

    /** Small steps around the best coarse fit: scale each axis and shift a little. */
    private double[] refine(float[] xs, float[] zs, double[] a)
    {
        double mx = 0, mz = 0;
        for (int i = 0; i < xs.length; i++) { mx += xs[i]; mz += zs[i]; }
        mx /= xs.length; mz /= xs.length;
        double iw = mIslandR - mIslandL, ih = mIslandB - mIslandT;
        double[] best = a;
        int bestIn = inside(xs, zs, a);
        double[] ks = {0.96, 0.98, 1.0, 1.02, 1.04};
        for (int pass = 0; pass < 3; pass++) {
            double[] base = best;
            // Centre of the spots on the map, so scaling keeps it in place
            double cpx = base[0] * mx + base[1] * mz + base[2], cpy = base[3] * mx + base[4] * mz + base[5];
            for (double kx : ks) {
                for (double ky : ks) {
                    for (double dx = -0.03; dx <= 0.031; dx += 0.015) {
                        for (double dy = -0.03; dy <= 0.031; dy += 0.015) {
                            double[] t = {base[0] * kx, base[1] * kx, 0, base[3] * ky, base[4] * ky, 0};
                            t[2] = cpx + dx * iw - (t[0] * mx + t[1] * mz);
                            t[5] = cpy + dy * ih - (t[3] * mx + t[4] * mz);
                            int in = inside(xs, zs, t);
                            if (in > bestIn) { bestIn = in; best = t; }
                        }
                    }
                }
            }
        }
        return best;
    }
}
