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

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

import paulscode.android.mupen64plusae.R;

/**
 * Banjo-Tooie pause screen for the second screen (BC), in a wooden storybook style:
 * a world-name sign, eight tiles for the current world, a resources bar and a tab bar
 * (Main Menu, Moves, Jinjos, Totals, Save and Quit, Options).
 *
 * Everything is drawn in code and laid out from the view size, so it fits the AYN Thor's
 * bottom screen (1080x1240) and scales to others. Game icons come from {@link BanjoTooieIcons}
 * (loaded from the player's ROM) with simple drawn shapes as fallback.
 */
public class BanjoTooiePauseView extends View
{
    private static final String TAG = "BanjoTooiePause";
    private static final long REFRESH_MS = 200;

    // Wood palette
    private static final int WOOD_DARK = 0xFF3A2210, WOOD_MID = 0xFF6B3F1C, WOOD_LIGHT = 0xFF8E5A2C,
            WOOD_HI = 0xFFA8703A, PLATE = 0xFF4A2B13, PLATE_EDGE = 0xFF2A170A, CREAM = 0xFFF8E6C2,
            GOLD = 0xFFFFC93C, GOLD_DARK = 0xFFB5780F, BOLT = 0xFF5E5A55, LEAF = 0xFF3F8F2C;
    private static final int TITLE_BLUE = 0xFF1F4FE0, TITLE_RED = 0xFFE3261B, TITLE_EDGE = 0xFFFFD43B;

    private static final int TAB_MAIN = 0, TAB_MOVES = 1, TAB_JINJOS = 2, TAB_TOTALS = 3,
            TAB_SAVE = 4, TAB_OPTIONS = 5;
    private static final String[] TAB_NAMES = {"Main Menu", "Moves", "Jinjos", "Totals", "Save and Quit", "Options"};
    private static final String MOVES_ICON = "bt_moves", TOTALS_ICON = "bt_totals";
    private static final String[] TAB_ICONS = {BanjoTooieIcons.JIGGY, MOVES_ICON, BanjoTooieIcons.JINJO,
            TOTALS_ICON, BanjoTooieArt.TAB_BAG, BanjoTooieArt.TAB_MENU};

    private static final String[] EGG_ICONS = {BanjoTooieIcons.EGG_BLUE, BanjoTooieIcons.EGG_FIRE,
            BanjoTooieIcons.EGG_GRENADE, BanjoTooieIcons.EGG_ICE, BanjoTooieIcons.EGG_CLOCKWORK};
    private static final int[] EGG_INDEX = {BanjoTooieStats.BLUE_EGGS, BanjoTooieStats.FIRE_EGGS,
            BanjoTooieStats.GRENADE_EGGS, BanjoTooieStats.ICE_EGGS, BanjoTooieStats.CLOCKWORK_EGGS};

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final BanjoTooieStats mStats = new BanjoTooieStats();
    private BanjoTooieStats.Snapshot mSnapshot = new BanjoTooieStats.Snapshot();
    private final Runnable mOpenMenu, mSaveAndQuit;
    private boolean mRunning = false;
    private long mPlayMs = 0, mLastTick = 0;

    private int mTab = TAB_MAIN;
    private int mEggType = 0;
    private float mScroll = 0, mScrollMax = 0;

    // Hit areas, filled while drawing
    private final RectF[] mTabRects = new RectF[6];
    private final RectF mTitleRect = new RectF(), mEggRect = new RectF(), mContentRect = new RectF();
    private final RectF mConfirmRect = new RectF(), mCancelRect = new RectF();

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final Rect mSrc = new Rect();
    private final RectF mBox = new RectF();
    private final Typeface mHeavy;

    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) return;
            long now = SystemClock.elapsedRealtime();
            if (mLastTick != 0 && mSnapshot.valid) mPlayMs += now - mLastTick;
            mLastTick = now;
            if (isShown()) {
                mSnapshot = mStats.read(mSnapshot);
                invalidate();
            }
            mHandler.postDelayed(this, REFRESH_MS);
        }
    };

    /**
     * @param openMenu    "Options": show the emulator's in-game menu instead
     * @param saveAndQuit "Save and Quit": save to the current slot and leave the game
     */
    public BanjoTooiePauseView(Context context, Runnable openMenu, Runnable saveAndQuit)
    {
        super(context);
        mOpenMenu = openMenu;
        mSaveAndQuit = saveAndQuit;
        Typeface black = Typeface.create("sans-serif-black", Typeface.BOLD);
        mHeavy = black != null ? black : Typeface.DEFAULT_BOLD;
        mText.setTypeface(mHeavy);
        for (int i = 0; i < mTabRects.length; i++) mTabRects[i] = new RectF();
        loadArt();
    }

    // Wooden pieces cut from the M64-DS template, and the player's icon set
    private Bitmap mBg, mSign, mTile, mResources, mTabArt;

    private Bitmap bmp(int id)
    {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            return BitmapFactory.decodeResource(getResources(), id, o);
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "Couldn't load art", e);
            return null;
        }
    }

    private void loadArt()
    {
        mBg = bmp(R.drawable.bt_bg);
        mSign = bmp(R.drawable.bt_sign);
        mTile = bmp(R.drawable.bt_tile);
        mResources = bmp(R.drawable.bt_resources);
        mTabArt = bmp(R.drawable.bt_tab);
        if (BanjoTooieIcons.get(BanjoTooieIcons.JIGGY) == null) {
            BanjoTooieIcons.put(BanjoTooieIcons.JIGGY, bmp(R.drawable.bt_jiggy));
            BanjoTooieIcons.put(BanjoTooieIcons.NOTE, bmp(R.drawable.bt_note));
            BanjoTooieIcons.put(BanjoTooieIcons.HONEYCOMB, bmp(R.drawable.bt_honeycomb));
            BanjoTooieIcons.put(BanjoTooieIcons.PAGE, bmp(R.drawable.bt_page));
            BanjoTooieIcons.put(BanjoTooieIcons.FEATHER_RED, bmp(R.drawable.bt_feather_red));
            BanjoTooieIcons.put(BanjoTooieIcons.FEATHER_GOLD, bmp(R.drawable.bt_feather_gold));
            BanjoTooieIcons.put(BanjoTooieIcons.EGG_BLUE, bmp(R.drawable.bt_egg_blue));
            BanjoTooieIcons.put(BanjoTooieIcons.EGG_FIRE, bmp(R.drawable.bt_egg_fire));
            BanjoTooieIcons.put(BanjoTooieIcons.EGG_GRENADE, bmp(R.drawable.bt_egg_grenade));
            BanjoTooieIcons.put(BanjoTooieIcons.EGG_ICE, bmp(R.drawable.bt_egg_ice));
            BanjoTooieIcons.put(BanjoTooieIcons.EGG_CLOCKWORK, bmp(R.drawable.bt_egg_clockwork));
            BanjoTooieIcons.put(BanjoTooieArt.CLOCK, bmp(R.drawable.bt_clock));
            BanjoTooieIcons.put(MOVES_ICON, bmp(R.drawable.bt_moves));
        }
    }

    /**
     * Draw a piece as a 9-slice: the corners (bolts, leaves, the RESOURCES sign) keep their
     * shape at {@code cornerScale}; only the plain wood in between stretches.
     */
    private void nine(Canvas c, Bitmap b, int il, int it, int ir, int ib, RectF d, float cornerScale)
    {
        int bw = b.getWidth(), bh = b.getHeight();
        float dl = il * cornerScale, dt = it * cornerScale, dr = ir * cornerScale, db = ib * cornerScale;
        int[] sx = {0, il, bw - ir, bw};
        int[] sy = {0, it, bh - ib, bh};
        float[] dx = {d.left, d.left + dl, d.right - dr, d.right};
        float[] dy = {d.top, d.top + dt, d.bottom - db, d.bottom};
        mPaint.setShader(null);
        mPaint.setAlpha(255);
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
                if (dx[x + 1] <= dx[x] || dy[y + 1] <= dy[y]) continue;
                mSrc.set(sx[x], sy[y], sx[x + 1], sy[y + 1]);
                mDst.set(dx[x], dy[y], dx[x + 1], dy[y + 1]);
                c.drawBitmap(b, mSrc, mDst, mPaint);
            }
        }
    }

    private final RectF mDst = new RectF();

    // ---------------------------------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c)
    {
        final float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        final float pad = w * 0.022f;

        // Frame + background boards
        if (mBg != null) {
            mBox.set(0, 0, w, h);
            nine(c, mBg, 40, 40, 40, 40, mBox, w / mBg.getWidth());
        } else {
            wood(c, 0, 0, w, h, 0, WOOD_DARK, 0xFF2C190B, 11);
            wood(c, pad * 0.6f, pad * 0.6f, w - pad * 0.6f, h - pad * 0.6f, pad, WOOD_MID, 0xFF55311A, 21);
        }

        // Vertical layout: sign | content | resources | tabs (art pieces keep their proportions)
        float signH = mSign != null ? (w - pad * 2) * mSign.getHeight() / mSign.getWidth() : h * 0.15f;
        float resH = mResources != null ? (w - pad * 2) * mResources.getHeight() / mResources.getWidth() : h * 0.095f;
        float tabW = (w - pad - (w - pad) * 0.012f * 5) / 6;
        float tabH = mTabArt != null ? Math.min(h * 0.16f, tabW * mTabArt.getHeight() / mTabArt.getWidth()) : h * 0.155f;
        float signTop = pad, signBottom = signTop + signH;
        float tabTop = h - pad - tabH, resBottom = tabTop - pad * 0.7f, resTop = resBottom - resH;
        float contentTop = signBottom + pad * 0.7f;
        float contentBottom = (mTab == TAB_MAIN ? resTop : tabTop) - pad * 0.7f;

        drawSign(c, pad, signTop, w - pad, signBottom, title());
        mContentRect.set(pad, contentTop, w - pad, contentBottom);

        if (!mSnapshot.valid) {
            drawCentered(c, "Waiting for the game…", mContentRect.centerX(), mContentRect.centerY(), h * 0.035f, CREAM, w * 0.8f);
        } else {
            switch (mTab) {
                case TAB_MOVES: drawMoves(c, mContentRect); break;
                case TAB_JINJOS: drawJinjos(c, mContentRect); break;
                case TAB_TOTALS: drawTotals(c, mContentRect); break;
                case TAB_SAVE: drawSaveConfirm(c, mContentRect); break;
                default: drawMain(c, mContentRect); break;
            }
        }
        if (mTab == TAB_MAIN && mSnapshot.valid) drawResources(c, pad, resTop, w - pad, resBottom);
        drawTabs(c, pad * 0.5f, tabTop, w - pad * 0.5f, h - pad * 0.6f);
    }

    private String title()
    {
        switch (mTab) {
            case TAB_MOVES: return "Moves";
            case TAB_JINJOS: return "Jinjos";
            case TAB_TOTALS: return "Totals";
            case TAB_SAVE: return "Save and Quit";
            default:
                int world = mSnapshot.world;
                return world >= 0 && world < BanjoTooieStats.WORLDS.length
                        ? BanjoTooieStats.WORLDS[world] : "Banjo-Tooie";
        }
    }

    /** The wooden sign with the two-colour bouncy title. */
    private void drawSign(Canvas c, float l, float t, float r, float b, String title)
    {
        float h = b - t;
        mTitleRect.set(l, t, r, b);
        if (mSign != null) {
            mBox.set(l, t, r, b);
            nine(c, mSign, 300, 60, 300, 60, mBox, (r - l) / mSign.getWidth());
        } else {
            wood(c, l, t, r, b, h * 0.22f, WOOD_LIGHT, WOOD_MID, 5);
            bevel(c, l, t, r, b, h * 0.22f);
            bolt(c, l + h * 0.16f, t + h * 0.2f, h * 0.06f);
            bolt(c, r - h * 0.16f, t + h * 0.2f, h * 0.06f);
            bolt(c, l + h * 0.16f, b - h * 0.2f, h * 0.06f);
            bolt(c, r - h * 0.16f, b - h * 0.2f, h * 0.06f);
            leaves(c, l + h * 0.05f, t + h * 0.05f, h * 0.42f, false);
            leaves(c, r - h * 0.05f, t + h * 0.05f, h * 0.42f, true);
        }

        // Words: first one blue, the rest red; letters bob and tilt a little
        String[] words = title.toUpperCase(Locale.US).split(" ", 2);
        float maxW = (r - l) - h * 1.2f;
        float size = h * 0.56f;
        mText.setTextSize(size);
        float total = mText.measureText(title.toUpperCase(Locale.US)) * 1.06f;
        if (total > maxW) { size *= maxW / total; mText.setTextSize(size); total = maxW; }
        float x = (l + r) / 2 - total / 2;
        float base = (t + b) / 2 + size * 0.36f;
        int letter = 0;
        for (int wi = 0; wi < words.length; wi++) {
            String word = (wi > 0 ? " " : "") + words[wi];
            int fill = wi == 0 ? TITLE_BLUE : TITLE_RED;
            for (int i = 0; i < word.length(); i++) {
                String ch = String.valueOf(word.charAt(i));
                float cw = mText.measureText(ch) * 1.06f;
                if (!ch.equals(" ")) {
                    float tilt = (letter % 2 == 0 ? -5f : 5f), bob = (letter % 3 - 1) * size * 0.04f;
                    c.save();
                    c.rotate(tilt, x + cw / 2, base - size * 0.35f);
                    outlinedText(c, ch, x, base + bob, size, fill, TITLE_EDGE, 0xFF3A1E05);
                    c.restore();
                    letter++;
                }
                x += cw;
            }
        }
    }

    private void drawMain(Canvas c, RectF area)
    {
        BanjoTooieStats.Snapshot s = mSnapshot;
        int w = s.world;
        boolean inWorld = w >= 0 && w < BanjoTooieStats.WORLD_COUNT;

        String[] labels = {"Jiggy", "Honeycomb", "Notes", "Glowbo", "Jinjo", "Moves", "Time", "Cheato Pages"};
        String[] icons = {BanjoTooieIcons.JIGGY, BanjoTooieIcons.HONEYCOMB, BanjoTooieIcons.NOTE,
                BanjoTooieIcons.GLOWBO, BanjoTooieIcons.JINJO, MOVES_ICON, BanjoTooieArt.CLOCK, BanjoTooieIcons.PAGE};
        String[] values = new String[8];
        if (inWorld) {
            values[0] = s.worldJiggies[w] + "/" + BanjoTooieStats.JIGGIES_PER_WORLD;
            values[1] = s.worldHoneycombs[w] + "/" + BanjoTooieStats.WORLD_HONEYCOMB_FLAGS[w].length;
            values[2] = s.worldNotes[w] + "/" + BanjoTooieStats.NOTES_PER_WORLD;
            values[3] = s.worldGlowbos[w] + "/" + BanjoTooieStats.WORLD_GLOWBO_FLAGS[w].length;
            values[4] = s.worldJinjos[w] + "/" + BanjoTooieStats.WORLD_JINJO_FLAGS[w].length;
            values[5] = s.worldMoves[w] + "/" + BanjoTooieStats.WORLD_MOVE_FLAGS[w].length;
            values[7] = s.worldPages[w] + "/" + BanjoTooieStats.WORLD_PAGE_FLAGS[w].length;
        } else {
            // Not in a known world yet: whole-game totals
            values[0] = s.jiggies + "/90";
            values[1] = s.emptyHoneycombs + "/25";
            values[2] = s.notes + "/900";
            values[3] = String.valueOf(s.glowbos);
            values[4] = s.jinjos + "/45";
            values[5] = s.movesTotal + "/24";
            values[7] = s.cheatoPages + "/25";
        }
        values[6] = formatTime(mPlayMs);

        float gap = area.width() * 0.018f;
        float tw = (area.width() - gap * 3) / 4, th = (area.height() - gap) / 2;
        for (int i = 0; i < 8; i++) {
            int col = i % 4, row = i / 4;
            float l = area.left + col * (tw + gap), t = area.top + row * (th + gap);
            tile(c, l, t, l + tw, t + th, labels[i], icons[i], values[i]);
        }
    }

    /** One wooden tile: label plate, icon in a round well, value plate. */
    private void tile(Canvas c, float l, float t, float r, float b, String label, String icon, String value)
    {
        float w = r - l, h = b - t;
        float labelCy, labelW, labelSize, valueTop, valueBottom, iconTop;
        if (mTile != null) {
            // The template tile: label plate in its top band, bolts in the corners
            float ts = w / mTile.getWidth();
            mBox.set(l, t, r, b);
            nine(c, mTile, 56, 80, 56, 52, mBox, ts);
            labelCy = t + 40 * ts;
            labelW = 205 * ts;
            labelSize = 30 * ts;
            valueBottom = b - 12 * ts;
            valueTop = valueBottom - 60 * ts;
            iconTop = t + 72 * ts;
        } else {
            wood(c, l, t, r, b, w * 0.08f, WOOD_LIGHT, WOOD_MID, (int) (l + t));
            bevel(c, l, t, r, b, w * 0.08f);
            float bs = w * 0.035f;
            bolt(c, l + w * 0.09f, t + w * 0.09f, bs);
            bolt(c, r - w * 0.09f, t + w * 0.09f, bs);
            bolt(c, l + w * 0.09f, b - w * 0.09f, bs);
            bolt(c, r - w * 0.09f, b - w * 0.09f, bs);
            float lp = h * 0.17f;
            plate(c, l + w * 0.17f, t + h * 0.05f, r - w * 0.17f, t + h * 0.05f + lp);
            labelCy = t + h * 0.05f + lp / 2;
            labelW = w * 0.62f;
            labelSize = lp * 0.58f;
            valueBottom = b - h * 0.06f;
            valueTop = valueBottom - h * 0.19f;
            iconTop = t + h * 0.25f;
        }
        drawCentered(c, label.toUpperCase(Locale.US), (l + r) / 2, labelCy, labelSize, CREAM, labelW);

        // Icon in a round well, between the label and the value
        float wellH = valueTop - iconTop;
        float cx = (l + r) / 2, cy = iconTop + wellH / 2, rad = Math.min(w * 0.36f, wellH * 0.46f);
        mPaint.setShader(new RadialGradient(cx, cy - rad * 0.3f, rad * 1.3f, 0xFF5C3518, 0xFF2E1909, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, rad, mPaint);
        mPaint.setShader(null);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(rad * 0.12f);
        mPaint.setColor(0xFF7E4E25);
        c.drawCircle(cx, cy, rad, mPaint);
        mPaint.setStyle(Paint.Style.FILL);
        float is = rad * 1.45f;
        mBox.set(cx - is / 2, cy - is / 2, cx + is / 2, cy + is / 2);
        icon(c, icon, mBox);

        // Value plate (between the bottom bolts)
        float vp = valueBottom - valueTop;
        plate(c, l + w * 0.15f, valueTop, r - w * 0.15f, valueBottom);
        drawCentered(c, value, (l + r) / 2, (valueTop + valueBottom) / 2, vp * 0.74f, 0xFFFFFFFF, w * 0.66f);
    }

    private void drawResources(Canvas c, float l, float t, float r, float b)
    {
        float h = b - t, w = r - l;
        float sw;
        if (mResources != null) {
            // Template bar: the RESOURCES sign is in its left 450px
            float rs = w / mResources.getWidth();
            mBox.set(l, t, r, b);
            nine(c, mResources, 455, 30, 60, 30, mBox, rs);
            sw = 450 * rs;
        } else {
            wood(c, l, t, r, b, h * 0.25f, WOOD_LIGHT, WOOD_MID, 77);
            bevel(c, l, t, r, b, h * 0.25f);
            sw = w * 0.30f;
            plate(c, l + h * 0.12f, t + h * 0.14f, l + sw, b - h * 0.14f);
            bolt(c, l + h * 0.28f, (t + b) / 2, h * 0.07f);
            bolt(c, l + sw - h * 0.16f, (t + b) / 2, h * 0.07f);
            drawCentered(c, "RESOURCES", l + sw / 2 + h * 0.06f, (t + b) / 2, h * 0.34f, 0xFFF2D49A, sw - h * 0.7f);
        }

        BanjoTooieStats.Snapshot s = mSnapshot;
        int egg = EGG_INDEX[mEggType];
        String[] icons = {BanjoTooieIcons.FEATHER_RED, BanjoTooieIcons.FEATHER_GOLD, EGG_ICONS[mEggType]};
        int[] counts = {s.consumables[BanjoTooieStats.RED_FEATHERS], s.consumables[BanjoTooieStats.GOLD_FEATHERS], s.consumables[egg]};
        float cellL = l + sw + h * 0.2f, cellW = (r - h * 0.15f - cellL) / 3;
        for (int i = 0; i < 3; i++) {
            float cl = cellL + i * cellW;
            if (mResources == null) plate(c, cl + h * 0.06f, t + h * 0.14f, cl + cellW - h * 0.06f, b - h * 0.14f);
            float is = h * 0.62f;
            mBox.set(cl + cellW * 0.12f, (t + b) / 2 - is / 2, cl + cellW * 0.12f + is, (t + b) / 2 + is / 2);
            icon(c, icons[i], mBox);
            drawCentered(c, String.valueOf(counts[i]), cl + cellW * 0.12f + is + (cellW * 0.88f - is) / 2,
                    (t + b) / 2, h * 0.42f, 0xFFFFFFFF, cellW * 0.88f - is - h * 0.1f);
            if (i == 2) mEggRect.set(cl, t, cl + cellW, b);
        }
    }

    private void drawTabs(Canvas c, float l, float t, float r, float b)
    {
        float gap = (r - l) * 0.012f;
        float tw = (r - l - gap * 5) / 6, h = b - t;
        for (int i = 0; i < 6; i++) {
            float tl = l + i * (tw + gap);
            mTabRects[i].set(tl, t, tl + tw, b);
            boolean sel = i == mTab;
            if (sel) {
                mPaint.setColor(0x66FFC93C);
                mBox.set(tl - gap * 0.6f, t - gap * 0.6f, tl + tw + gap * 0.6f, b + gap * 0.3f);
                c.drawRoundRect(mBox, tw * 0.12f, tw * 0.12f, mPaint);
            }
            if (mTabArt != null) {
                mBox.set(tl, t, tl + tw, b);
                nine(c, mTabArt, 40, 40, 40, 40, mBox, tw / mTabArt.getWidth());
                if (!sel) {
                    mPaint.setColor(0x33000000); // unselected tabs a little darker
                    c.drawRoundRect(mBox, tw * 0.08f, tw * 0.08f, mPaint);
                }
            } else {
                wood(c, tl, t, tl + tw, b, tw * 0.1f, sel ? 0xFFE0A447 : WOOD_LIGHT, sel ? 0xFFB57426 : WOOD_MID, 31 + i);
                bevel(c, tl, t, tl + tw, b, tw * 0.1f);
            }
            if (sel) {
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(tw * 0.035f);
                mPaint.setColor(GOLD);
                mBox.set(tl, t, tl + tw, b);
                c.drawRoundRect(mBox, tw * 0.1f, tw * 0.1f, mPaint);
                mPaint.setStyle(Paint.Style.FILL);
            }
            float is = Math.min(tw * 0.55f, h * 0.5f);
            mBox.set(tl + tw / 2 - is / 2, t + h * 0.1f, tl + tw / 2 + is / 2, t + h * 0.1f + is);
            icon(c, TAB_ICONS[i], mBox);
            drawCentered(c, TAB_NAMES[i], tl + tw / 2, b - h * 0.19f, h * 0.2f, sel ? 0xFFFFF4D6 : CREAM, tw * 0.9f);
        }
    }

    // --- Detail pages -----------------------------------------------------------------------------

    private void drawMoves(Canvas c, RectF area)
    {
        BanjoTooieStats.Snapshot s = mSnapshot;
        board(c, area);
        float row = area.height() / 13.5f;
        float colW = area.width() / 2;
        c.save();
        c.clipRect(area);
        float y0 = area.top + row * 0.7f - mScroll;
        float[] colY = {y0, y0};
        for (int w = 0; w < BanjoTooieStats.WORLD_COUNT; w++) {
            int col = colY[0] <= colY[1] ? 0 : 1;
            float x = area.left + col * colW + colW * 0.06f;
            float y = colY[col];
            drawLeft(c, BanjoTooieStats.WORLDS[w].toUpperCase(Locale.US), x, y, row * 0.5f, GOLD, colW * 0.88f);
            y += row * 0.85f;
            String[] names = BanjoTooieStats.WORLD_MOVE_NAMES[w];
            for (int i = 0; i < names.length; i++) {
                boolean on = s.movesLearned[w] != null && i < s.movesLearned[w].length && s.movesLearned[w][i];
                check(c, x + row * 0.3f, y, row * 0.28f, on);
                drawLeft(c, names[i], x + row * 0.75f, y, row * 0.46f, on ? 0xFFFFFFFF : 0xFFB59C77, colW * 0.78f);
                y += row * 0.78f;
            }
            colY[col] = y + row * 0.35f;
        }
        mScrollMax = Math.max(0, Math.max(colY[0], colY[1]) + mScroll - area.bottom);
        c.restore();
        drawCentered(c, s.movesTotal + " / 24 moves", area.centerX(), area.bottom - row * 0.45f, row * 0.45f, CREAM, area.width());
    }

    private void drawJinjos(Canvas c, RectF area)
    {
        BanjoTooieStats.Snapshot s = mSnapshot;
        board(c, area);
        float row = area.height() / 11f;
        for (int w = 0; w < BanjoTooieStats.WORLD_COUNT; w++) {
            float y = area.top + row * (0.8f + w);
            float x = area.left + area.width() * 0.05f;
            mBox.set(x, y - row * 0.38f, x + row * 0.76f, y + row * 0.38f);
            icon(c, BanjoTooieIcons.JINJO, mBox);
            drawLeft(c, BanjoTooieStats.WORLDS[w], x + row, y, row * 0.42f, CREAM, area.width() * 0.55f);
            int max = BanjoTooieStats.WORLD_JINJO_FLAGS[w].length, got = s.worldJinjos[w];
            for (int i = 0; i < max; i++) {
                float dx = area.right - area.width() * 0.05f - (max - i) * row * 0.62f;
                pip(c, dx + row * 0.3f, y, row * 0.22f, i < got);
            }
        }
        float y = area.top + row * 10.1f;
        drawCentered(c, "Jinjos " + s.jinjos + "/45   ·   Families rescued " + s.jinjoFamilies + "/9",
                area.centerX(), y, row * 0.42f, GOLD, area.width() * 0.94f);
    }

    private void drawTotals(Canvas c, RectF area)
    {
        BanjoTooieStats.Snapshot s = mSnapshot;
        board(c, area);
        String[] icons = {BanjoTooieIcons.JIGGY, BanjoTooieIcons.NOTE, BanjoTooieIcons.JINJO,
                BanjoTooieIcons.PAGE, BanjoTooieIcons.HONEYCOMB, BanjoTooieIcons.GLOWBO};
        float row = area.height() / 11.3f;
        float nameW = area.width() * 0.34f;
        float colW = (area.width() * 0.96f - nameW) / icons.length;
        float x0 = area.left + area.width() * 0.03f;
        for (int i = 0; i < icons.length; i++) {
            float cx = x0 + nameW + colW * (i + 0.5f);
            float is = row * 0.8f;
            mBox.set(cx - is / 2, area.top + row * 0.15f, cx + is / 2, area.top + row * 0.15f + is);
            icon(c, icons[i], mBox);
        }
        for (int w = 0; w <= BanjoTooieStats.WORLD_COUNT; w++) {
            boolean total = w == BanjoTooieStats.WORLD_COUNT;
            float y = area.top + row * (1.6f + w) + (total ? row * 0.25f : 0);
            if (!total && w == s.world) {
                mPaint.setColor(0x40FFC93C);
                mBox.set(x0, y - row * 0.46f, area.right - area.width() * 0.03f, y + row * 0.46f);
                c.drawRoundRect(mBox, row * 0.2f, row * 0.2f, mPaint);
            }
            if (total) {
                mPaint.setColor(0x80FFC93C);
                c.drawRect(x0, y - row * 0.62f, area.right - area.width() * 0.03f, y - row * 0.57f, mPaint);
            }
            String name = total ? "Total" : BanjoTooieStats.WORLDS[w];
            drawLeft(c, name, x0 + row * 0.15f, y, row * 0.4f, total ? GOLD : CREAM, nameW - row * 0.2f);
            int[] v = total
                    ? new int[]{s.jiggies, s.notes, s.jinjos, s.cheatoPages, s.emptyHoneycombs, s.glowbos}
                    : new int[]{s.worldJiggies[w], s.worldNotes[w], s.worldJinjos[w], s.worldPages[w],
                    s.worldHoneycombs[w], s.worldGlowbos[w]};
            for (int i = 0; i < v.length; i++) {
                drawCentered(c, String.valueOf(v[i]), x0 + nameW + colW * (i + 0.5f), y, row * 0.42f,
                        total ? GOLD : 0xFFFFFFFF, colW * 0.95f);
            }
        }
    }

    private void drawSaveConfirm(Canvas c, RectF area)
    {
        board(c, area);
        float h = area.height();
        drawCentered(c, "Save to the current slot", area.centerX(), area.top + h * 0.25f, h * 0.07f, CREAM, area.width() * 0.9f);
        drawCentered(c, "and return to the game list?", area.centerX(), area.top + h * 0.36f, h * 0.07f, CREAM, area.width() * 0.9f);
        float bw = area.width() * 0.38f, bh = h * 0.2f, by = area.top + h * 0.55f;
        mConfirmRect.set(area.centerX() - bw - area.width() * 0.03f, by, area.centerX() - area.width() * 0.03f, by + bh);
        mCancelRect.set(area.centerX() + area.width() * 0.03f, by, area.centerX() + bw + area.width() * 0.03f, by + bh);
        wood(c, mConfirmRect.left, mConfirmRect.top, mConfirmRect.right, mConfirmRect.bottom, bh * 0.2f, 0xFFE0A447, 0xFFB57426, 3);
        bevel(c, mConfirmRect.left, mConfirmRect.top, mConfirmRect.right, mConfirmRect.bottom, bh * 0.2f);
        drawCentered(c, "Save and Quit", mConfirmRect.centerX(), mConfirmRect.centerY(), bh * 0.3f, 0xFF2A1405, bw * 0.9f);
        wood(c, mCancelRect.left, mCancelRect.top, mCancelRect.right, mCancelRect.bottom, bh * 0.2f, WOOD_LIGHT, WOOD_MID, 4);
        bevel(c, mCancelRect.left, mCancelRect.top, mCancelRect.right, mCancelRect.bottom, bh * 0.2f);
        drawCentered(c, "Cancel", mCancelRect.centerX(), mCancelRect.centerY(), bh * 0.3f, CREAM, bw * 0.9f);
    }

    // --- Building blocks ----------------------------------------------------------------------------

    /** Rounded wood panel with grain lines (seeded so it doesn't flicker). */
    private void wood(Canvas c, float l, float t, float r, float b, float radius, int top, int bottom, int seed)
    {
        mBox.set(l, t, r, b);
        mPaint.setShader(new LinearGradient(0, t, 0, b, top, bottom, Shader.TileMode.CLAMP));
        c.drawRoundRect(mBox, radius, radius, mPaint);
        mPaint.setShader(null);
        c.save();
        mPath.reset();
        mPath.addRoundRect(mBox, radius, radius, Path.Direction.CW);
        c.clipPath(mPath);
        Random rnd = new Random(seed * 7919L + 17);
        mPaint.setStyle(Paint.Style.STROKE);
        float h = b - t;
        int lines = Math.max(3, (int) (h / 9f));
        for (int i = 0; i < lines; i++) {
            float y = t + h * rnd.nextFloat();
            mPaint.setStrokeWidth(Math.max(1f, h * (0.004f + rnd.nextFloat() * 0.01f)));
            mPaint.setColor(rnd.nextBoolean() ? 0x22000000 : 0x14FFFFFF);
            mPath.reset();
            mPath.moveTo(l, y);
            float wob = h * 0.02f;
            mPath.cubicTo(l + (r - l) * 0.33f, y + wob * (rnd.nextFloat() - 0.5f) * 4,
                    l + (r - l) * 0.66f, y + wob * (rnd.nextFloat() - 0.5f) * 4, r, y + wob * (rnd.nextFloat() - 0.5f) * 2);
            c.drawPath(mPath, mPaint);
        }
        mPaint.setStyle(Paint.Style.FILL);
        c.restore();
    }

    /** Light top edge, dark bottom edge, thin dark outline. */
    private void bevel(Canvas c, float l, float t, float r, float b, float radius)
    {
        float sw = Math.max(1.5f, (b - t) * 0.025f);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(sw);
        mPaint.setColor(0xFF24130A);
        mBox.set(l, t, r, b);
        c.drawRoundRect(mBox, radius, radius, mPaint);
        mPaint.setStrokeWidth(sw * 0.8f);
        mPaint.setColor(0x33FFFFFF);
        mBox.set(l + sw, t + sw, r - sw, b - sw);
        c.drawRoundRect(mBox, radius * 0.85f, radius * 0.85f, mPaint);
        mPaint.setStyle(Paint.Style.FILL);
    }

    private void plate(Canvas c, float l, float t, float r, float b)
    {
        float rad = (b - t) * 0.25f;
        mBox.set(l, t, r, b);
        mPaint.setShader(new LinearGradient(0, t, 0, b, 0xFF3A2010, PLATE, Shader.TileMode.CLAMP));
        c.drawRoundRect(mBox, rad, rad, mPaint);
        mPaint.setShader(null);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(Math.max(1f, (b - t) * 0.05f));
        mPaint.setColor(PLATE_EDGE);
        c.drawRoundRect(mBox, rad, rad, mPaint);
        mPaint.setStyle(Paint.Style.FILL);
    }

    /** Dark inset board behind the detail pages. */
    private void board(Canvas c, RectF a)
    {
        float rad = a.width() * 0.03f;
        mPaint.setColor(0xCC2A170A);
        c.drawRoundRect(a, rad, rad, mPaint);
        bevel(c, a.left, a.top, a.right, a.bottom, rad);
    }

    private void bolt(Canvas c, float x, float y, float r)
    {
        mPaint.setShader(new RadialGradient(x - r * 0.3f, y - r * 0.3f, r * 1.2f, 0xFFB8B2A8, BOLT, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r, mPaint);
        mPaint.setShader(null);
        mPaint.setColor(0xFF2A2724);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(r * 0.25f);
        c.drawCircle(x, y, r, mPaint);
        mPaint.setStyle(Paint.Style.FILL);
    }

    /** A few simple leaves in a sign corner. */
    private void leaves(Canvas c, float x, float y, float size, boolean right)
    {
        float dir = right ? -1 : 1;
        for (int i = 0; i < 3; i++) {
            float ang = (right ? 180 : 0) + dir * (-20 + i * 35);
            c.save();
            c.translate(x, y);
            c.rotate(ang);
            mPaint.setShader(new LinearGradient(0, 0, size, 0, 0xFF6CC24A, LEAF, Shader.TileMode.CLAMP));
            mBox.set(0, -size * 0.17f, size * (0.9f - i * 0.1f), size * 0.17f);
            c.drawOval(mBox, mPaint);
            mPaint.setShader(null);
            mPaint.setColor(0x55205A12);
            mPaint.setStrokeWidth(size * 0.03f);
            c.drawLine(0, 0, size * (0.85f - i * 0.1f), 0, mPaint);
            c.restore();
        }
    }

    private void check(Canvas c, float cx, float cy, float r, boolean on)
    {
        mPaint.setColor(on ? GOLD : 0xFF4E3620);
        c.drawCircle(cx, cy, r, mPaint);
        if (on) {
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(r * 0.35f);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setColor(0xFF3A2008);
            mPath.reset();
            mPath.moveTo(cx - r * 0.5f, cy);
            mPath.lineTo(cx - r * 0.1f, cy + r * 0.4f);
            mPath.lineTo(cx + r * 0.55f, cy - r * 0.45f);
            c.drawPath(mPath, mPaint);
            mPaint.setStyle(Paint.Style.FILL);
        }
    }

    private void pip(Canvas c, float cx, float cy, float r, boolean on)
    {
        mPaint.setColor(on ? GOLD : 0xFF4E3620);
        c.drawCircle(cx, cy, r, mPaint);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(r * 0.2f);
        mPaint.setColor(on ? GOLD_DARK : 0xFF2A170A);
        c.drawCircle(cx, cy, r, mPaint);
        mPaint.setStyle(Paint.Style.FILL);
    }

    /** The game's icon if loaded, else a drawn shape. */
    private void icon(Canvas c, String key, RectF box)
    {
        Bitmap bmp = BanjoTooieIcons.get(key);
        if (bmp != null) {
            float scale = Math.min(box.width() / bmp.getWidth(), box.height() / bmp.getHeight());
            float iw = bmp.getWidth() * scale, ih = bmp.getHeight() * scale;
            mSrc.set(0, 0, bmp.getWidth(), bmp.getHeight());
            RectF dst = new RectF(box.centerX() - iw / 2, box.centerY() - ih / 2, box.centerX() + iw / 2, box.centerY() + ih / 2);
            mPaint.setShader(null);
            mPaint.setAlpha(255);
            c.drawBitmap(bmp, mSrc, dst, mPaint);
            return;
        }
        if (MOVES_ICON.equals(key)) {
            hexFlower(c, box);
        } else if (TOTALS_ICON.equals(key)) {
            RectF a = new RectF(box.left, box.top + box.height() * 0.1f, box.left + box.width() * 0.62f, box.bottom - box.height() * 0.1f);
            BanjoTooieArt.draw(c, BanjoTooieIcons.JIGGY, a, mPaint);
            RectF b = new RectF(box.left + box.width() * 0.42f, box.top, box.right, box.top + box.height() * 0.62f);
            BanjoTooieArt.draw(c, BanjoTooieIcons.HONEYCOMB, b, mPaint);
        } else {
            BanjoTooieArt.draw(c, key, box, mPaint);
        }
        mPaint.setShader(null);
        mPaint.setAlpha(255);
        mPaint.setStyle(Paint.Style.FILL);
    }

    /** Four golden hexagons around a hole (the "moves" icon). */
    private void hexFlower(Canvas c, RectF box)
    {
        float s = Math.min(box.width(), box.height()), cx = box.centerX(), cy = box.centerY(), r = s * 0.2f;
        float[][] at = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (float[] a : at) {
            float x = cx + a[0] * r * 1.15f, y = cy + a[1] * r * 1.15f;
            mPath.reset();
            for (int k = 0; k < 6; k++) {
                double ang = Math.toRadians(60 * k + 30);
                float px = x + (float) (r * Math.cos(ang)), py = y + (float) (r * Math.sin(ang));
                if (k == 0) mPath.moveTo(px, py); else mPath.lineTo(px, py);
            }
            mPath.close();
            mPaint.setShader(new LinearGradient(0, y - r, 0, y + r, 0xFFFFE07A, 0xFFE09A12, Shader.TileMode.CLAMP));
            c.drawPath(mPath, mPaint);
            mPaint.setShader(null);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(r * 0.12f);
            mPaint.setColor(0xFF9A5A08);
            c.drawPath(mPath, mPaint);
            mPaint.setStyle(Paint.Style.FILL);
        }
    }

    private void outlinedText(Canvas c, String s, float x, float base, float size, int fill, int edge, int outer)
    {
        mText.setTextSize(size);
        mText.setTextAlign(Paint.Align.LEFT);
        mText.setStyle(Paint.Style.STROKE);
        mText.setStrokeJoin(Paint.Join.ROUND);
        mText.setStrokeWidth(size * 0.22f);
        mText.setColor(outer);
        c.drawText(s, x, base, mText);
        mText.setStrokeWidth(size * 0.12f);
        mText.setColor(edge);
        c.drawText(s, x, base, mText);
        mText.setStyle(Paint.Style.FILL);
        mText.setColor(fill);
        c.drawText(s, x, base, mText);
    }

    /** Centered text with a dark outline, shrunk to fit maxWidth. */
    private void drawCentered(Canvas c, String s, float cx, float cy, float size, int color, float maxWidth)
    {
        textAt(c, s, cx, cy, size, color, maxWidth, Paint.Align.CENTER);
    }

    private void drawLeft(Canvas c, String s, float x, float cy, float size, int color, float maxWidth)
    {
        textAt(c, s, x, cy, size, color, maxWidth, Paint.Align.LEFT);
    }

    private void textAt(Canvas c, String s, float x, float cy, float size, int color, float maxWidth, Paint.Align align)
    {
        mText.setTextSize(size);
        float w = mText.measureText(s);
        if (w > maxWidth && w > 0) {
            size *= maxWidth / w;
            mText.setTextSize(size);
        }
        mText.setTextAlign(align);
        Paint.FontMetrics fm = mText.getFontMetrics();
        float base = cy - (fm.ascent + fm.descent) / 2;
        mText.setStyle(Paint.Style.STROKE);
        mText.setStrokeJoin(Paint.Join.ROUND);
        mText.setStrokeWidth(size * 0.16f);
        mText.setColor(0xE0180C03);
        c.drawText(s, x, base, mText);
        mText.setStyle(Paint.Style.FILL);
        mText.setColor(color);
        c.drawText(s, x, base, mText);
        mText.setTextAlign(Paint.Align.LEFT);
    }

    private static String formatTime(long ms)
    {
        long sec = ms / 1000;
        return String.format(Locale.US, "%d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60);
    }

    // ---------------------------------------------------------------------------------------------
    // Touch
    // ---------------------------------------------------------------------------------------------

    private float mDownX, mDownY, mLastY;
    private boolean mDragging = false;
    private final Runnable mLongPress = this::saveMemorySnapshot;

    @Override
    public boolean onTouchEvent(MotionEvent e)
    {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = x; mDownY = y; mLastY = y; mDragging = false;
                if (mTitleRect.contains(x, y)) mHandler.postDelayed(mLongPress, 800);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(y - mDownY) > getHeight() * 0.02f) {
                    mDragging = true;
                    mHandler.removeCallbacks(mLongPress);
                }
                if (mDragging && mTab == TAB_MOVES && mContentRect.contains(mDownX, mDownY)) {
                    mScroll = Math.max(0, Math.min(mScrollMax, mScroll - (y - mLastY)));
                    invalidate();
                }
                mLastY = y;
                return true;
            case MotionEvent.ACTION_UP:
                mHandler.removeCallbacks(mLongPress);
                if (!mDragging) onTap(x, y);
                return true;
            case MotionEvent.ACTION_CANCEL:
                mHandler.removeCallbacks(mLongPress);
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void onTap(float x, float y)
    {
        for (int i = 0; i < mTabRects.length; i++) {
            if (mTabRects[i].contains(x, y)) {
                if (i == TAB_OPTIONS) {
                    if (mOpenMenu != null) mOpenMenu.run();
                } else {
                    mTab = i;
                    mScroll = 0;
                }
                invalidate();
                return;
            }
        }
        if (mTab == TAB_MAIN && mEggRect.contains(x, y)) {
            mEggType = (mEggType + 1) % EGG_ICONS.length; // tap the egg to see the other egg types
            invalidate();
        } else if (mTab == TAB_SAVE) {
            if (mConfirmRect.contains(x, y)) {
                mTab = TAB_MAIN;
                if (mSaveAndQuit != null) mSaveAndQuit.run();
            } else if (mCancelRect.contains(x, y)) {
                mTab = TAB_MAIN;
            }
            invalidate();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Memory snapshot (long-press the world sign): for adding the game's own icons
    // ---------------------------------------------------------------------------------------------

    private boolean mSaving = false;

    private void saveMemorySnapshot()
    {
        if (mSaving) return;
        mSaving = true;
        final Context ctx = getContext().getApplicationContext();
        final String name = "bt_memory_" +
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".bin";
        Toast.makeText(getContext(), "Saving memory snapshot…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String result;
            try {
                byte[] all = new byte[BanjoTooieStats.RDRAM_SIZE];
                final int chunk = 0x10000;
                byte[] buf = new byte[chunk];
                for (int off = 0; off < all.length; off += chunk) {
                    if (!BanjoTooieStats.readRaw(off, buf, chunk)) throw new IllegalStateException("game not running");
                    System.arraycopy(buf, 0, all, off, chunk);
                }
                result = write(ctx, name, all);
            } catch (Exception ex) {
                Log.e(TAG, "Snapshot failed", ex);
                result = null;
            }
            final String msg = result != null ? "Saved " + result : "Couldn't save the memory snapshot";
            mHandler.post(() -> {
                mSaving = false;
                Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
            });
        }, "BT-snapshot").start();
    }

    private static String write(Context ctx, String name, byte[] data) throws Exception
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = ctx.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Mupen64BT");
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("no download uri");
            try (OutputStream os = resolver.openOutputStream(uri)) {
                if (os == null) throw new IllegalStateException("no output stream");
                os.write(data);
            }
            return "Download/Mupen64BT/" + name;
        }
        File dir = new File(ctx.getExternalFilesDir(null), "Mupen64BT");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, name);
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(data);
        }
        return f.getAbsolutePath();
    }

    // ---------------------------------------------------------------------------------------------
    // Refresh
    // ---------------------------------------------------------------------------------------------

    @Override
    protected void onAttachedToWindow()
    {
        super.onAttachedToWindow();
        mRunning = true;
        mLastTick = 0;
        mHandler.removeCallbacks(mRefresh);
        mHandler.post(mRefresh);
    }

    @Override
    protected void onDetachedFromWindow()
    {
        mRunning = false;
        mHandler.removeCallbacks(mRefresh);
        mHandler.removeCallbacks(mLongPress);
        super.onDetachedFromWindow();
    }
}
