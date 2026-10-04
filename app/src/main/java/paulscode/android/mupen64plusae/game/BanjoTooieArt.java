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

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Simple vector icons for the stats panel, drawn in code (generic shapes, nothing taken from the
 * game). Used whenever the game's own icon for a key hasn't been loaded from the ROM.
 */
final class BanjoTooieArt
{
    /** Extra keys for the tab bar and timer (not game icons). */
    static final String TAB_MAP = "tab_map", TAB_BAG = "tab_bag", TAB_WORLDS = "tab_worlds",
            TAB_MENU = "tab_menu", CLOCK = "clock";

    private BanjoTooieArt() {}

    private static final Path P = new Path();
    private static final RectF R = new RectF();

    /** Draw the icon for key into box (keeps aspect, centred). Returns false if unknown. */
    static boolean draw(Canvas c, String key, RectF box, Paint p)
    {
        float s = Math.min(box.width(), box.height());
        float x = box.centerX() - s / 2, y = box.centerY() - s / 2;
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setAlpha(255);
        switch (key) {
            case BanjoTooieIcons.NOTE: note(c, x, y, s, p); return true;
            case BanjoTooieIcons.JIGGY: jiggy(c, x, y, s, p, 0xFFFFD25A, 0xFFD88A12); return true;
            case TAB_WORLDS: jiggy(c, x, y, s, p, 0xFFF1D9A8, 0xFFB98E55); return true;
            case BanjoTooieIcons.HONEYCOMB: honeycomb(c, x, y, s, p); return true;
            case BanjoTooieIcons.HEALTH: healthHex(c, x, y, s, p, true); return true;
            case BanjoTooieIcons.JINJO: jinjo(c, x, y, s, p); return true;
            case BanjoTooieIcons.FEATHER_RED: feather(c, x, y, s, p, 0xFFFF5A4A, 0xFFB3141E); return true;
            case BanjoTooieIcons.FEATHER_GOLD: feather(c, x, y, s, p, 0xFFFFE27A, 0xFFD0901A); return true;
            case BanjoTooieIcons.GLOWBO: glowbo(c, x, y, s, p); return true;
            case BanjoTooieIcons.PAGE: page(c, x, y, s, p); return true;
            case BanjoTooieIcons.DOUBLOON: coin(c, x, y, s, p); return true;
            case BanjoTooieIcons.EGG_BLUE: egg(c, x, y, s, p, 0xFF8EC3FF, 0xFF2D63C8); return true;
            case BanjoTooieIcons.EGG_FIRE: egg(c, x, y, s, p, 0xFFFFC04A, 0xFFE0381E); return true;
            case BanjoTooieIcons.EGG_ICE: egg(c, x, y, s, p, 0xFFE6FBFF, 0xFF69C6E6); return true;
            case BanjoTooieIcons.EGG_GRENADE: egg(c, x, y, s, p, 0xFFC9CF6A, 0xFF5E6420); return true;
            case BanjoTooieIcons.EGG_CLOCKWORK: egg(c, x, y, s, p, 0xFFF2F2F2, 0xFF8A8A8A); return true;
            case CLOCK: clock(c, x, y, s, p); return true;
            case TAB_MAP: map(c, x, y, s, p); return true;
            case TAB_BAG: bag(c, x, y, s, p); return true;
            case TAB_MENU: gear(c, x, y, s, p); return true;
            default: return false;
        }
    }

    private static void vgrad(Paint p, float top, float bottom, int light, int dark)
    {
        p.setShader(new LinearGradient(0, top, 0, bottom, light, dark, Shader.TileMode.CLAMP));
    }

    private static void outline(Canvas c, Path path, Paint p, float w, int color)
    {
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(color);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
    }

    // --- Collectibles -------------------------------------------------------------------------

    private static void note(Canvas c, float x, float y, float s, Paint p)
    {
        // Two beamed eighth notes
        P.reset();
        P.addOval(new RectF(x + s * 0.08f, y + s * 0.66f, x + s * 0.40f, y + s * 0.90f), Path.Direction.CW);
        P.addOval(new RectF(x + s * 0.56f, y + s * 0.56f, x + s * 0.88f, y + s * 0.80f), Path.Direction.CW);
        P.addRect(x + s * 0.32f, y + s * 0.22f, x + s * 0.40f, y + s * 0.78f, Path.Direction.CW);
        P.addRect(x + s * 0.80f, y + s * 0.12f, x + s * 0.88f, y + s * 0.68f, Path.Direction.CW);
        Path beam = new Path();
        beam.moveTo(x + s * 0.32f, y + s * 0.20f);
        beam.lineTo(x + s * 0.88f, y + s * 0.08f);
        beam.lineTo(x + s * 0.88f, y + s * 0.24f);
        beam.lineTo(x + s * 0.32f, y + s * 0.36f);
        beam.close();
        P.addPath(beam);
        vgrad(p, y, y + s, 0xFFFFE07A, 0xFFE09A16);
        c.drawPath(P, p);
        outline(c, P, p, s * 0.035f, 0xFF7A4A08);
    }

    private static void jiggy(Canvas c, float x, float y, float s, Paint p, int light, int dark)
    {
        // A chunky plus-shaped puzzle piece
        float a = s * 0.30f, b = s * 0.70f, k = s * 0.13f;
        P.reset();
        P.addRoundRect(new RectF(x + a, y + s * 0.10f, x + b, y + s * 0.90f), k, k, Path.Direction.CW);
        P.addRoundRect(new RectF(x + s * 0.10f, y + a, x + s * 0.90f, y + b), k, k, Path.Direction.CW);
        P.setFillType(Path.FillType.WINDING);
        Path u = new Path();
        u.op(P, Path.Op.UNION);
        vgrad(p, y, y + s, light, dark);
        c.drawPath(u, p);
        outline(c, u, p, s * 0.035f, darker(dark));
        p.setShader(null);
        p.setColor(0x55FFFFFF);
        c.drawCircle(x + s * 0.40f, y + s * 0.36f, s * 0.06f, p);
    }

    private static void hexPath(Path path, float cx, float cy, float r)
    {
        path.reset();
        for (int i = 0; i < 6; i++) {
            double ang = Math.toRadians(60 * i);
            float px = cx + (float) (r * Math.cos(ang)), py = cy + (float) (r * Math.sin(ang));
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.close();
    }

    private static void honeycomb(Canvas c, float x, float y, float s, Paint p)
    {
        float cx = x + s / 2, cy = y + s / 2;
        hexPath(P, cx, cy, s * 0.44f);
        Path inner = new Path();
        hexPath(inner, cx, cy, s * 0.24f);
        P.op(inner, Path.Op.DIFFERENCE);
        vgrad(p, y, y + s, 0xFFFFDC6A, 0xFFE08A10);
        c.drawPath(P, p);
        outline(c, P, p, s * 0.03f, 0xFF7A4A08);
    }

    /** Health honeycomb; empty ones are drawn dim. */
    static void healthHex(Canvas c, float x, float y, float s, Paint p, boolean full)
    {
        float cx = x + s / 2, cy = y + s / 2;
        hexPath(P, cx, cy, s * 0.47f);
        if (full) {
            p.setShader(new RadialGradient(cx, cy - s * 0.1f, s * 0.6f,
                    new int[] {0xFFFFE9A0, 0xFFF7B731, 0xFFC97A0C}, new float[] {0f, 0.55f, 1f},
                    Shader.TileMode.CLAMP));
        } else {
            vgrad(p, y, y + s, 0xFF4A3A28, 0xFF2C2218);
        }
        c.drawPath(P, p);
        outline(c, P, p, s * 0.06f, full ? 0xFFFFD45A : 0xFF5C4A34);
        Path inner = new Path();
        hexPath(inner, cx, cy, s * 0.30f);
        outline(c, inner, p, s * 0.025f, full ? 0x66A05A00 : 0x33000000);
    }

    private static void jinjo(Canvas c, float x, float y, float s, Paint p)
    {
        // Little round critter with two arms up
        P.reset();
        P.addOval(new RectF(x + s * 0.28f, y + s * 0.30f, x + s * 0.72f, y + s * 0.92f), Path.Direction.CW);
        P.addOval(new RectF(x + s * 0.30f, y + s * 0.08f, x + s * 0.70f, y + s * 0.46f), Path.Direction.CW);
        Path u = new Path();
        u.op(P, Path.Op.UNION);
        vgrad(p, y, y + s, 0xFFFFB0C8, 0xFFE0577E);
        c.drawPath(u, p);
        outline(c, u, p, s * 0.03f, 0xFF8A2346);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.09f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0xFFE0577E);
        c.drawLine(x + s * 0.30f, y + s * 0.52f, x + s * 0.12f, y + s * 0.30f, p);
        c.drawLine(x + s * 0.70f, y + s * 0.52f, x + s * 0.88f, y + s * 0.30f, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(x + s * 0.42f, y + s * 0.26f, s * 0.065f, p);
        c.drawCircle(x + s * 0.58f, y + s * 0.26f, s * 0.065f, p);
        p.setColor(0xFF1A1A1A);
        c.drawCircle(x + s * 0.43f, y + s * 0.27f, s * 0.03f, p);
        c.drawCircle(x + s * 0.57f, y + s * 0.27f, s * 0.03f, p);
    }

    private static void feather(Canvas c, float x, float y, float s, Paint p, int light, int dark)
    {
        P.reset();
        P.moveTo(x + s * 0.14f, y + s * 0.92f);
        P.cubicTo(x + s * 0.20f, y + s * 0.50f, x + s * 0.50f, y + s * 0.10f, x + s * 0.90f, y + s * 0.06f);
        P.cubicTo(x + s * 0.86f, y + s * 0.42f, x + s * 0.56f, y + s * 0.78f, x + s * 0.14f, y + s * 0.92f);
        P.close();
        p.setShader(new LinearGradient(x + s, y, x, y + s, light, dark, Shader.TileMode.CLAMP));
        c.drawPath(P, p);
        outline(c, P, p, s * 0.03f, darker(dark));
        Path spine = new Path();
        spine.moveTo(x + s * 0.08f, y + s * 0.98f);
        spine.quadTo(x + s * 0.44f, y + s * 0.52f, x + s * 0.84f, y + s * 0.12f);
        outline(c, spine, p, s * 0.035f, 0xCCFFFFFF);
    }

    private static void glowbo(Canvas c, float x, float y, float s, Paint p)
    {
        float cx = x + s / 2, cy = y + s * 0.55f;
        p.setShader(new RadialGradient(cx, cy, s * 0.48f, 0x88B58CFF, 0x00B58CFF, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, s * 0.48f, p);
        p.setShader(new RadialGradient(cx - s * 0.08f, cy - s * 0.1f, s * 0.32f,
                0xFFF0E4FF, 0xFF7A4FD8, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, s * 0.28f, p);
        p.setShader(null);
        p.setColor(0xFFFFFFFF);
        star(c, x + s * 0.74f, y + s * 0.20f, s * 0.12f, p);
    }

    private static void star(Canvas c, float cx, float cy, float r, Paint p)
    {
        Path st = new Path();
        st.moveTo(cx, cy - r);
        st.quadTo(cx, cy, cx + r, cy);
        st.quadTo(cx, cy, cx, cy + r);
        st.quadTo(cx, cy, cx - r, cy);
        st.quadTo(cx, cy, cx, cy - r);
        c.drawPath(st, p);
    }

    private static void page(Canvas c, float x, float y, float s, Paint p)
    {
        P.reset();
        P.moveTo(x + s * 0.20f, y + s * 0.08f);
        P.lineTo(x + s * 0.64f, y + s * 0.08f);
        P.lineTo(x + s * 0.82f, y + s * 0.26f);
        P.lineTo(x + s * 0.82f, y + s * 0.92f);
        P.lineTo(x + s * 0.20f, y + s * 0.92f);
        P.close();
        vgrad(p, y, y + s, 0xFFFFF4D6, 0xFFD9B97A);
        c.drawPath(P, p);
        outline(c, P, p, s * 0.03f, 0xFF7A5A2A);
        Path fold = new Path();
        fold.moveTo(x + s * 0.64f, y + s * 0.08f);
        fold.lineTo(x + s * 0.64f, y + s * 0.26f);
        fold.lineTo(x + s * 0.82f, y + s * 0.26f);
        outline(c, fold, p, s * 0.03f, 0xFF7A5A2A);
        p.setColor(0xFF9C7A44);
        p.setStrokeWidth(s * 0.035f);
        for (int i = 0; i < 4; i++) {
            float ly = y + s * (0.40f + i * 0.13f);
            c.drawLine(x + s * 0.30f, ly, x + s * (i == 3 ? 0.56f : 0.72f), ly, p);
        }
    }

    private static void coin(Canvas c, float x, float y, float s, Paint p)
    {
        float cx = x + s / 2, cy = y + s / 2;
        p.setShader(new RadialGradient(cx - s * 0.1f, cy - s * 0.1f, s * 0.5f,
                0xFFFFF0A8, 0xFFD39212, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, s * 0.42f, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.04f);
        p.setColor(0xFF8A5A08);
        c.drawCircle(cx, cy, s * 0.42f, p);
        c.drawCircle(cx, cy, s * 0.30f, p);
        p.setStyle(Paint.Style.FILL);
    }

    private static void egg(Canvas c, float x, float y, float s, Paint p, int light, int dark)
    {
        P.reset();
        P.moveTo(x + s * 0.5f, y + s * 0.06f);
        P.cubicTo(x + s * 0.80f, y + s * 0.06f, x + s * 0.88f, y + s * 0.62f, x + s * 0.82f, y + s * 0.74f);
        P.cubicTo(x + s * 0.74f, y + s * 0.96f, x + s * 0.26f, y + s * 0.96f, x + s * 0.18f, y + s * 0.74f);
        P.cubicTo(x + s * 0.12f, y + s * 0.62f, x + s * 0.20f, y + s * 0.06f, x + s * 0.5f, y + s * 0.06f);
        P.close();
        p.setShader(new RadialGradient(x + s * 0.40f, y + s * 0.36f, s * 0.6f, light, dark, Shader.TileMode.CLAMP));
        c.drawPath(P, p);
        outline(c, P, p, s * 0.03f, darker(dark));
        p.setShader(null);
        p.setColor(0x88FFFFFF);
        c.drawOval(new RectF(x + s * 0.34f, y + s * 0.20f, x + s * 0.46f, y + s * 0.38f), p);
    }

    private static void clock(Canvas c, float x, float y, float s, Paint p)
    {
        float cx = x + s / 2, cy = y + s * 0.56f, r = s * 0.36f;
        p.setShader(null);
        p.setColor(0xFFE23A2E);
        c.drawCircle(x + s * 0.24f, y + s * 0.16f, s * 0.13f, p);
        c.drawCircle(x + s * 0.76f, y + s * 0.16f, s * 0.13f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.06f);
        p.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(cx - r * 0.7f, cy + r * 0.8f, cx - r * 0.95f, y + s * 0.98f, p);
        c.drawLine(cx + r * 0.7f, cy + r * 0.8f, cx + r * 0.95f, y + s * 0.98f, p);
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.4f, 0xFFFF6A5A, 0xFFB01A14, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);
        p.setColor(0xFFFFF8EE);
        c.drawCircle(cx, cy, r * 0.76f, p);
        p.setColor(0xFF222222);
        p.setStrokeWidth(s * 0.05f);
        c.drawLine(cx, cy, cx, cy - r * 0.55f, p);
        c.drawLine(cx, cy, cx + r * 0.38f, cy + r * 0.12f, p);
        c.drawCircle(cx, cy, s * 0.04f, p);
    }

    // --- Tab icons ----------------------------------------------------------------------------

    private static void map(Canvas c, float x, float y, float s, Paint p)
    {
        float[] xs = {0.10f, 0.37f, 0.63f, 0.90f};
        int[] cols = {0xFF6FBF5A, 0xFF4FA3D8, 0xFFE8C04A};
        for (int i = 0; i < 3; i++) {
            P.reset();
            float t = (i % 2 == 0) ? 0.14f : 0.22f, b = (i % 2 == 0) ? 0.86f : 0.92f;
            float t2 = (i % 2 == 0) ? 0.22f : 0.14f, b2 = (i % 2 == 0) ? 0.92f : 0.86f;
            P.moveTo(x + s * xs[i], y + s * t);
            P.lineTo(x + s * xs[i + 1], y + s * t2);
            P.lineTo(x + s * xs[i + 1], y + s * b2);
            P.lineTo(x + s * xs[i], y + s * b);
            P.close();
            vgrad(p, y, y + s, lighter(cols[i]), cols[i]);
            c.drawPath(P, p);
            outline(c, P, p, s * 0.03f, 0xFF3A2A18);
        }
        p.setShader(null);
        p.setColor(0xFFD8342A);
        c.drawCircle(x + s * 0.55f, y + s * 0.48f, s * 0.07f, p);
    }

    private static void bag(Canvas c, float x, float y, float s, Paint p)
    {
        P.reset();
        P.moveTo(x + s * 0.36f, y + s * 0.28f);
        P.cubicTo(x + s * 0.10f, y + s * 0.42f, x + s * 0.10f, y + s * 0.92f, x + s * 0.50f, y + s * 0.92f);
        P.cubicTo(x + s * 0.90f, y + s * 0.92f, x + s * 0.90f, y + s * 0.42f, x + s * 0.64f, y + s * 0.28f);
        P.close();
        vgrad(p, y, y + s, 0xFFE2C9A0, 0xFF9C7A50);
        c.drawPath(P, p);
        outline(c, P, p, s * 0.03f, 0xFF4A3420);
        p.setShader(null);
        p.setColor(0xFFC9AE84);
        R.set(x + s * 0.32f, y + s * 0.10f, x + s * 0.68f, y + s * 0.30f);
        c.drawRoundRect(R, s * 0.08f, s * 0.08f, p);
        p.setColor(0xFF6A4A2A);
        c.drawRect(x + s * 0.34f, y + s * 0.26f, x + s * 0.66f, y + s * 0.32f, p);
        p.setColor(0xFF5A3E22);
        c.drawRect(x + s * 0.48f, y + s * 0.50f, x + s * 0.52f, y + s * 0.74f, p);
        c.drawRect(x + s * 0.44f, y + s * 0.54f, x + s * 0.56f, y + s * 0.57f, p);
        c.drawRect(x + s * 0.44f, y + s * 0.64f, x + s * 0.56f, y + s * 0.67f, p);
    }

    private static void gear(Canvas c, float x, float y, float s, Paint p)
    {
        float cx = x + s / 2, cy = y + s / 2;
        P.reset();
        int teeth = 8;
        for (int i = 0; i < teeth * 4; i++) {
            double ang = Math.PI * 2 * i / (teeth * 4);
            float r = (i % 4 == 0 || i % 4 == 1) ? s * 0.44f : s * 0.34f;
            float px = cx + (float) (r * Math.cos(ang)), py = cy + (float) (r * Math.sin(ang));
            if (i == 0) P.moveTo(px, py); else P.lineTo(px, py);
        }
        P.close();
        Path hole = new Path();
        hole.addCircle(cx, cy, s * 0.15f, Path.Direction.CW);
        P.op(hole, Path.Op.DIFFERENCE);
        vgrad(p, y, y + s, 0xFFD8D2C6, 0xFF8A8276);
        c.drawPath(P, p);
        outline(c, P, p, s * 0.03f, 0xFF3A342C);
    }

    // --- Colour helpers -----------------------------------------------------------------------

    static int darker(int c)
    {
        return 0xFF000000 | (((c >> 16 & 0xFF) * 6 / 10) << 16) | (((c >> 8 & 0xFF) * 6 / 10) << 8) | ((c & 0xFF) * 6 / 10);
    }

    static int lighter(int c)
    {
        int r = c >> 16 & 0xFF, g = c >> 8 & 0xFF, b = c & 0xFF;
        r += (255 - r) / 3; g += (255 - g) / 3; b += (255 - b) / 3;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
