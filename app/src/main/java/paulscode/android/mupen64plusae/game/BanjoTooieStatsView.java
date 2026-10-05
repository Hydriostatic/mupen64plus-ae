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
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Live Banjo-Tooie stats for the second screen, styled like a little in-game pause screen:
 *
 * - Overview: the current character in a medallion with its health as glowing honeycombs, and a
 *   two-column grid of totals (notes, Jiggies, honeycombs, Jinjos, feathers, Glowbos, pages, time).
 * - Bag: eggs and feathers with fill bars, and every character's health.
 * - Worlds: Jiggies and notes per world.
 * - Gear: hands the controller to the emulator menu (same as Back).
 *
 * Refreshes itself a few times per second while it is on screen.
 */
public class BanjoTooieStatsView extends FrameLayout
{
    private static final long REFRESH_MS = 200;

    // Palette: dark slate panels, warm gold, wooden tabs
    private static final int BG_TOP = 0xB81A1E23, BG_BOTTOM = 0xB80E1013; // see-through: app icon behind
    private static final int PANEL_TOP = 0xFF22272D, PANEL_BOTTOM = 0xFF171A1E, PANEL_EDGE = 0xFF353B43;
    private static final int GOLD = 0xFFF7B731, GOLD_LIGHT = 0xFFFFE08A;
    private static final int WHITE = 0xFFF8F4EC, MUTED = 0xFF9AA0A8;
    private static final int TRACK = 0xFF2C3036;

    private static final int PAGE_OVERVIEW = 0, PAGE_BAG = 1, PAGE_WORLDS = 2;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final BanjoTooieStats mStats = new BanjoTooieStats();
    private BanjoTooieStats.Snapshot mSnapshot = new BanjoTooieStats.Snapshot();
    private boolean mRunning = false;
    private final float mDp;
    private final Runnable mOpenMenu;

    private final View[] mPages = new View[3];
    private final TabView[] mTabs = new TabView[4];
    private View mWaiting, mBody;

    /** Time the game has been running with this panel up (shown with the clock). */
    private long mPlayMs = 0, mLastTick = 0;

    private interface Binder { void bind(BanjoTooieStats.Snapshot s); }
    private final List<Binder> mBinders = new ArrayList<>();

    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) return;
            long now = SystemClock.elapsedRealtime();
            if (mLastTick != 0 && mSnapshot.valid) mPlayMs += now - mLastTick;
            mLastTick = now;
            if (isShown()) update();
            mHandler.postDelayed(this, REFRESH_MS);
        }
    };

    public BanjoTooieStatsView(Context context)
    {
        this(context, null);
    }

    /** @param openMenu run by the gear tab (hand the controller to the menu), may be null */
    public BanjoTooieStatsView(Context context, Runnable openMenu)
    {
        super(context);
        mDp = context.getResources().getDisplayMetrics().density;
        mOpenMenu = openMenu;
        mText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {BG_TOP, BG_BOTTOM}));
        build(context);
    }

    private int dp(float v) { return Math.round(v * mDp); }

    // ---------------------------------------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------------------------------------

    private void build(Context ctx)
    {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(10));
        addView(root, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout body = new FrameLayout(ctx);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LabelView waiting = new LabelView(ctx, "Waiting for the game…", MUTED, 0.5f);
        mWaiting = waiting;
        body.addView(waiting, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40), android.view.Gravity.CENTER));

        FrameLayout pages = new FrameLayout(ctx);
        mBody = pages;
        body.addView(pages, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mPages[PAGE_OVERVIEW] = buildOverview(ctx);
        mPages[PAGE_BAG] = buildBag(ctx);
        mPages[PAGE_WORLDS] = buildWorlds(ctx);
        for (View page : mPages) {
            pages.addView(page, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // Tab bar
        LinearLayout tabs = new LinearLayout(ctx);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        String[] icons = {BanjoTooieArt.TAB_MAP, BanjoTooieArt.TAB_BAG, BanjoTooieArt.TAB_WORLDS, BanjoTooieArt.TAB_MENU};
        for (int i = 0; i < 4; i++) {
            TabView tab = new TabView(ctx, icons[i]);
            final int index = i;
            tab.setOnClickListener(v -> {
                if (index == 3) {
                    if (mOpenMenu != null) mOpenMenu.run();
                } else {
                    showPage(index);
                }
            });
            mTabs[i] = tab;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(4), dp(10), dp(4), 0);
            tabs.addView(tab, lp);
        }
        root.addView(tabs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        showPage(PAGE_OVERVIEW);
    }

    private void showPage(int page)
    {
        for (int i = 0; i < mPages.length; i++) {
            mPages[i].setVisibility(i == page ? View.VISIBLE : View.GONE);
        }
        for (int i = 0; i < mTabs.length; i++) mTabs[i].setSelected(i == page);
        if (mSnapshot.valid) update();
    }

    private GradientDrawable panelBg()
    {
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {PANEL_TOP, PANEL_BOTTOM});
        bg.setCornerRadius(dp(18));
        bg.setStroke(Math.max(1, dp(1.5f)), PANEL_EDGE);
        return bg;
    }

    private View buildOverview(Context ctx)
    {
        LinearLayout page = new LinearLayout(ctx);
        page.setOrientation(LinearLayout.VERTICAL);

        HeroView hero = new HeroView(ctx);
        hero.setOnClickListener(v -> showPage(PAGE_BAG));
        hero.setOnLongClickListener(v -> { saveMemorySnapshot(); return true; });
        page.addView(hero, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.36f));
        mBinders.add(hero::bind);

        // Stats grid: two columns of four, with a divider
        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        grid.setBackground(panelBg());
        grid.setPadding(dp(8), dp(10), dp(8), dp(10));
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.64f);
        gridLp.topMargin = dp(10);
        page.addView(grid, gridLp);

        StatCell[] left = {
                new StatCell(ctx, BanjoTooieIcons.NOTE, s -> s.notes + " / 900"),
                new StatCell(ctx, BanjoTooieIcons.JIGGY, s -> s.jiggies + " / 90"),
                new StatCell(ctx, BanjoTooieIcons.HONEYCOMB, s -> s.emptyHoneycombs + " / 25"),
                new StatCell(ctx, BanjoTooieIcons.JINJO, s -> s.jinjos + " / 45"),
        };
        StatCell[] right = {
                new StatCell(ctx, BanjoTooieIcons.FEATHER_RED, s -> ammo(s, BanjoTooieStats.RED_FEATHERS)),
                new StatCell(ctx, BanjoTooieIcons.GLOWBO, s -> s.glowbos + " / " + BanjoTooieStats.GLOWBO_FLAGS.length),
                new StatCell(ctx, BanjoTooieIcons.PAGE, s -> s.cheatoPages + " / 25"),
                new StatCell(ctx, BanjoTooieArt.CLOCK, s -> formatTime(mPlayMs)),
        };
        grid.addView(column(ctx, left), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        View divider = new View(ctx);
        divider.setBackgroundColor(PANEL_EDGE);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(Math.max(1, dp(1.5f)), ViewGroup.LayoutParams.MATCH_PARENT);
        divLp.setMargins(dp(6), dp(6), dp(6), dp(6));
        grid.addView(divider, divLp);
        grid.addView(column(ctx, right), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return page;
    }

    private static String ammo(BanjoTooieStats.Snapshot s, int index)
    {
        int count = s.consumables[index];
        return count + " / " + Math.max(BanjoTooieStats.capacity(index), count);
    }

    private LinearLayout column(Context ctx, StatCell[] cells)
    {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        for (StatCell cell : cells) {
            col.addView(cell, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            mBinders.add(cell::bind);
        }
        return col;
    }

    private ScrollView scrollPage(Context ctx, LinearLayout content)
    {
        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private LinearLayout section(Context ctx, LinearLayout content, String title)
    {
        LabelView label = new LabelView(ctx, title, GOLD, 0f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30));
        lp.setMargins(dp(6), dp(6), 0, 0);
        content.addView(label, lp);
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(panelBg());
        panel.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.bottomMargin = dp(6);
        content.addView(panel, plp);
        return panel;
    }

    private View buildBag(Context ctx)
    {
        LinearLayout content = new LinearLayout(ctx);
        LinearLayout ammo = section(ctx, content, "EGGS & FEATHERS");
        for (Ammo a : AMMO) {
            BarRow row = new BarRow(ctx, a.icon, a.name, a.color);
            ammo.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
            mBinders.add(s -> {
                int count = s.consumables[a.index];
                int cap = Math.max(BanjoTooieStats.capacity(a.index), count);
                row.set(count, cap, count + " / " + cap, false);
            });
        }
        LinearLayout chars = section(ctx, content, "CHARACTERS");
        for (int id : BanjoTooieStats.PLAYABLE_CHARACTERS) {
            String n = BanjoTooieStats.characterName(id);
            if (n == null) continue;
            CharacterRow row = new CharacterRow(ctx, id, n);
            chars.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
            mBinders.add(row::bind);
        }
        return scrollPage(ctx, content);
    }

    private View buildWorlds(Context ctx)
    {
        LinearLayout content = new LinearLayout(ctx);
        LinearLayout panel = section(ctx, content, "WORLDS");
        for (int w = 0; w < BanjoTooieStats.WORLD_COUNT; w++) {
            WorldRow row = new WorldRow(ctx, BanjoTooieStats.WORLDS[w]);
            panel.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
            final int world = w;
            mBinders.add(s -> row.set(s.worldJiggies[world], s.worldNotes[world]));
        }
        return scrollPage(ctx, content);
    }

    // ---------------------------------------------------------------------------------------------
    // Data for the bag page
    // ---------------------------------------------------------------------------------------------

    private static final class Ammo
    {
        final int index; final String icon, name; final int color;
        Ammo(int index, String icon, String name, int color)
        {
            this.index = index; this.icon = icon; this.name = name; this.color = color;
        }
    }

    private static final Ammo[] AMMO = {
            new Ammo(BanjoTooieStats.BLUE_EGGS, BanjoTooieIcons.EGG_BLUE, "Blue Eggs", 0xFF4F8DE8),
            new Ammo(BanjoTooieStats.FIRE_EGGS, BanjoTooieIcons.EGG_FIRE, "Fire Eggs", 0xFFE8562F),
            new Ammo(BanjoTooieStats.GRENADE_EGGS, BanjoTooieIcons.EGG_GRENADE, "Grenade Eggs", 0xFF9AA03A),
            new Ammo(BanjoTooieStats.ICE_EGGS, BanjoTooieIcons.EGG_ICE, "Ice Eggs", 0xFF8FDDF0),
            new Ammo(BanjoTooieStats.CLOCKWORK_EGGS, BanjoTooieIcons.EGG_CLOCKWORK, "Clockwork Eggs", 0xFFC8C8C8),
            new Ammo(BanjoTooieStats.RED_FEATHERS, BanjoTooieIcons.FEATHER_RED, "Red Feathers", 0xFFE04A3A),
            new Ammo(BanjoTooieStats.GOLD_FEATHERS, BanjoTooieIcons.FEATHER_GOLD, "Gold Feathers", 0xFFF2C94A),
    };

    private interface Text { String get(BanjoTooieStats.Snapshot s); }

    private static String formatTime(long ms)
    {
        long sec = ms / 1000;
        return String.format(Locale.US, "%d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60);
    }

    // ---------------------------------------------------------------------------------------------
    // Drawing helpers
    // ---------------------------------------------------------------------------------------------

    private final Rect mSrc = new Rect();

    /** The game's icon if it was loaded from the ROM, else the built-in vector one. */
    private void drawIcon(Canvas c, String key, RectF box, Paint p)
    {
        Bitmap icon = key != null ? BanjoTooieIcons.get(key) : null;
        if (icon != null) {
            float scale = Math.min(box.width() / icon.getWidth(), box.height() / icon.getHeight());
            float iw = icon.getWidth() * scale, ih = icon.getHeight() * scale;
            mSrc.set(0, 0, icon.getWidth(), icon.getHeight());
            RectF dst = new RectF(box.centerX() - iw / 2, box.centerY() - ih / 2, box.centerX() + iw / 2, box.centerY() + ih / 2);
            p.setShader(null);
            p.setAlpha(255);
            c.drawBitmap(icon, mSrc, dst, p);
            return;
        }
        if (key != null) BanjoTooieArt.draw(c, key, box, p);
    }

    /** Draw text left- or centre-aligned, vertically centred, shrunk to fit maxWidth. */
    private void drawText(Canvas c, String text, float x, float cy, float size, float maxWidth,
                          int color, boolean center)
    {
        mText.setTextSize(size);
        float w = mText.measureText(text);
        if (w > maxWidth && w > 0) {
            mText.setTextSize(size * maxWidth / w);
        }
        mText.setColor(color);
        mText.setShadowLayer(dp(2), 0, dp(1.5f), 0xCC000000);
        mText.setTextAlign(center ? Paint.Align.CENTER : Paint.Align.LEFT);
        Paint.FontMetrics fm = mText.getFontMetrics();
        c.drawText(text, x, cy - (fm.ascent + fm.descent) / 2, mText);
    }

    /** Soft gold glow behind a shape. */
    private static void glow(Canvas c, Paint p, float cx, float cy, float r, int alpha)
    {
        p.setShader(new RadialGradient(cx, cy, r, (alpha << 24) | 0xFFC040, 0x00FFC040, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);
    }

    // ---------------------------------------------------------------------------------------------
    // Views
    // ---------------------------------------------------------------------------------------------

    /** Plain line of text. */
    private final class LabelView extends View
    {
        private final String mLabel; private final int mColor; private final float mAlign;

        /** align: 0 = left, 0.5 = centre */
        LabelView(Context ctx, String label, int color, float align)
        {
            super(ctx); mLabel = label; mColor = color; mAlign = align;
        }

        @Override
        protected void onDraw(Canvas c)
        {
            float size = Math.min(getHeight() * 0.6f, dp(16));
            drawText(c, mLabel, mAlign > 0 ? getWidth() / 2f : 0, getHeight() / 2f, size, getWidth(), mColor, mAlign > 0);
        }
    }

    /** Top of the overview: character medallion over a panel holding the health honeycombs. */
    private final class HeroView extends View
    {
        private int mCharacter = -1, mHealth, mMax;
        private String mName = "";
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF mRect = new RectF();

        HeroView(Context ctx) { super(ctx); }

        void bind(BanjoTooieStats.Snapshot s)
        {
            String n = BanjoTooieStats.characterName(s.character);
            if (s.character != mCharacter || s.health != mHealth || s.maxHealth != mMax) {
                mCharacter = s.character; mHealth = s.health; mMax = s.maxHealth;
                mName = n != null ? n : "";
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas c)
        {
            float w = getWidth(), h = getHeight();
            float medal = Math.min(h * 0.42f, w * 0.26f);   // medallion diameter
            float panelTop = medal * 0.55f;
            float radius = dp(18);

            // Panel
            mRect.set(0, panelTop, w, h);
            mPaint.setShader(new LinearGradient(0, panelTop, 0, h, PANEL_TOP, PANEL_BOTTOM, Shader.TileMode.CLAMP));
            mPaint.setStyle(Paint.Style.FILL);
            c.drawRoundRect(mRect, radius, radius, mPaint);
            mPaint.setShader(null);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(dp(1.5f));
            mPaint.setColor(PANEL_EDGE);
            c.drawRoundRect(mRect, radius, radius, mPaint);

            // Medallion: dark notch with a gold ring, character inside
            float cx = w / 2, cy = medal / 2 + dp(2), r = medal / 2;
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(PANEL_TOP);
            c.drawCircle(cx, cy, r + dp(6), mPaint);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(dp(1.5f));
            mPaint.setColor(PANEL_EDGE);
            c.drawArc(new RectF(cx - r - dp(6), cy - r - dp(6), cx + r + dp(6), cy + r + dp(6)), 180, 180, false, mPaint);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setShader(new RadialGradient(cx, cy - r * 0.3f, r * 1.2f, 0xFF3A3226, 0xFF15120E, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, mPaint);
            mPaint.setShader(null);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(r * 0.09f);
            mPaint.setShader(new LinearGradient(0, cy - r, 0, cy + r, GOLD_LIGHT, 0xFFB8780E, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r * 0.95f, mPaint);
            mPaint.setShader(null);
            mPaint.setStyle(Paint.Style.FILL);
            Bitmap portrait = mCharacter > 0 ? BanjoTooieIcons.get(BanjoTooieIcons.character(mCharacter)) : null;
            mRect.set(cx - r * 0.78f, cy - r * 0.78f, cx + r * 0.78f, cy + r * 0.78f);
            if (portrait != null) {
                drawIcon(c, BanjoTooieIcons.character(mCharacter), mRect, mPaint);
            } else {
                drawText(c, initials(mName), cx, cy, r * 0.8f, r * 1.4f, GOLD_LIGHT, true);
            }

            // Character name
            float nameY = panelTop + medal * 0.45f + dp(10);
            drawText(c, mName, cx, nameY, Math.min(dp(17), h * 0.10f), w - dp(32), WHITE, true);

            // Health capsule
            float capTop = nameY + dp(14);
            float capBottom = h - dp(14);
            if (capBottom - capTop < dp(24)) capTop = capBottom - dp(24);
            mRect.set(dp(16), capTop, w - dp(16), capBottom);
            float capR = mRect.height() / 2;
            mPaint.setShader(new LinearGradient(0, capTop, 0, capBottom, 0xFF0E1013, 0xFF1C2025, Shader.TileMode.CLAMP));
            c.drawRoundRect(mRect, capR, capR, mPaint);
            mPaint.setShader(null);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(dp(1));
            mPaint.setColor(0xFF2E333A);
            c.drawRoundRect(mRect, capR, capR, mPaint);
            mPaint.setStyle(Paint.Style.FILL);

            int max = (mMax > 0 && mMax <= 30) ? mMax : 0;
            if (max == 0) return;
            int cur = Math.max(0, Math.min(mHealth, max));
            float pad = mRect.height() * 0.10f;
            float avail = mRect.width() - pad * 4;
            float size = Math.min(mRect.height() - pad * 2, avail / max);
            float gap = max > 1 ? Math.min(size * 0.12f, (avail - size * max) / (max - 1)) : 0;
            float total = size * max + gap * (max - 1);
            float x0 = mRect.centerX() - total / 2, y0 = mRect.centerY() - size / 2;
            for (int i = 0; i < max; i++) {
                float x = x0 + i * (size + gap);
                boolean full = i < cur;
                if (full) glow(c, mPaint, x + size / 2, y0 + size / 2, size * 0.75f, 0x70);
                Bitmap icon = BanjoTooieIcons.get(BanjoTooieIcons.HEALTH);
                if (icon != null) {
                    mPaint.setAlpha(full ? 255 : 70);
                    c.drawBitmap(icon, null, new RectF(x, y0, x + size, y0 + size), mPaint);
                    mPaint.setAlpha(255);
                } else {
                    BanjoTooieArt.healthHex(c, x, y0, size, mPaint, full);
                }
            }
        }

        private String initials(String name)
        {
            if (name.isEmpty()) return "?";
            StringBuilder b = new StringBuilder();
            for (String part : name.split("[ &-]+")) {
                if (!part.isEmpty() && b.length() < 2) b.append(Character.toUpperCase(part.charAt(0)));
            }
            return b.toString();
        }
    }

    /** One icon + "count / total" in the overview grid. */
    private final class StatCell extends View
    {
        private final String mIcon; private final Text mGetter;
        private String mValue = "–";
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF mBox = new RectF();

        StatCell(Context ctx, String icon, Text getter) { super(ctx); mIcon = icon; mGetter = getter; }

        void bind(BanjoTooieStats.Snapshot s)
        {
            String v = mGetter.get(s);
            if (!v.equals(mValue)) { mValue = v; invalidate(); }
            else if (BanjoTooieIcons.get(mIcon) != null) invalidate(); // icons may arrive later
        }

        @Override
        protected void onDraw(Canvas c)
        {
            float w = getWidth(), h = getHeight();
            float icon = Math.min(h * 0.72f, w * 0.32f);
            float left = w * 0.06f;
            mBox.set(left, (h - icon) / 2, left + icon, (h + icon) / 2);
            drawIcon(c, mIcon, mBox, mPaint);
            float tx = mBox.right + w * 0.07f;
            drawText(c, mValue, tx, h / 2, Math.min(h * 0.40f, dp(30)), w - tx - dp(4), WHITE, false);
        }
    }

    /** Icon, name, fill bar and count (eggs & feathers). */
    private final class BarRow extends View
    {
        private final String mIcon, mName; private final int mColor;
        private float mFraction; private String mValue = "";
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF mBox = new RectF();

        BarRow(Context ctx, String icon, String name, int color) { super(ctx); mIcon = icon; mName = name; mColor = color; }

        void set(int value, int max, String text, boolean force)
        {
            float f = max > 0 ? Math.min(1f, value / (float) max) : 0f;
            if (force || f != mFraction || !text.equals(mValue)) { mFraction = f; mValue = text; invalidate(); }
        }

        @Override
        protected void onDraw(Canvas c)
        {
            float w = getWidth(), h = getHeight();
            float icon = h * 0.70f;
            mBox.set(0, (h - icon) / 2, icon, (h + icon) / 2);
            drawIcon(c, mIcon, mBox, mPaint);
            float x = icon + dp(12);
            float valueW = w * 0.26f;
            float barRight = w - valueW - dp(10);
            drawText(c, mName, x, h * 0.32f, dp(15), barRight - x, WHITE, false);
            mBox.set(x, h * 0.62f, barRight, h * 0.62f + dp(8));
            float r = mBox.height() / 2;
            mPaint.setShader(null);
            mPaint.setColor(TRACK);
            c.drawRoundRect(mBox, r, r, mPaint);
            if (mFraction > 0) {
                mBox.right = mBox.left + Math.max(mBox.height(), mBox.width() * mFraction);
                mPaint.setShader(new LinearGradient(0, mBox.top, 0, mBox.bottom,
                        BanjoTooieArt.lighter(mColor), mColor, Shader.TileMode.CLAMP));
                c.drawRoundRect(mBox, r, r, mPaint);
                mPaint.setShader(null);
            }
            mText.setTextSize(dp(18));
            drawText(c, mValue, w - valueW / 2, h / 2, dp(18), valueW, WHITE, true);
        }
    }

    /** Character badge, name, small health honeycombs and current/max. */
    private final class CharacterRow extends View
    {
        private final int mId; private final String mName;
        private int mCur, mMax; private boolean mCurrent;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF mBox = new RectF();

        CharacterRow(Context ctx, int id, String name) { super(ctx); mId = id; mName = name; }

        void bind(BanjoTooieStats.Snapshot s)
        {
            int max = s.allMaxHealth[mId], cur = s.allHealth[mId];
            boolean current = s.character == mId;
            if (max != mMax || cur != mCur || current != mCurrent) {
                mMax = max; mCur = cur; mCurrent = current;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas c)
        {
            float w = getWidth(), h = getHeight();
            float badge = h * 0.70f;
            float cy = h / 2;
            if (mCurrent) {
                mBox.set(-dp(6), dp(2), w + dp(6), h - dp(2));
                mPaint.setColor(0x33F7B731);
                c.drawRoundRect(mBox, dp(12), dp(12), mPaint);
            }
            mBox.set(0, cy - badge / 2, badge, cy + badge / 2);
            if (BanjoTooieIcons.get(BanjoTooieIcons.character(mId)) != null) {
                drawIcon(c, BanjoTooieIcons.character(mId), mBox, mPaint);
            } else {
                mPaint.setShader(null);
                mPaint.setColor(0xFF2A2620);
                c.drawCircle(mBox.centerX(), cy, badge / 2, mPaint);
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(dp(2));
                mPaint.setColor(mCurrent ? GOLD : 0xFF6E5A3A);
                c.drawCircle(mBox.centerX(), cy, badge / 2 - dp(1), mPaint);
                mPaint.setStyle(Paint.Style.FILL);
                drawText(c, mName.substring(0, 1), mBox.centerX(), cy, badge * 0.5f, badge, GOLD_LIGHT, true);
            }
            float x = badge + dp(12);
            float valueW = w * 0.20f;
            drawText(c, mName, x, h * 0.30f, dp(15), w - valueW - x, mCurrent ? GOLD_LIGHT : WHITE, false);
            int max = (mMax > 0 && mMax <= 30) ? mMax : 0;
            if (max > 0) {
                float size = Math.min(h * 0.32f, (w - valueW - x - dp(8)) / max);
                for (int i = 0; i < max; i++) {
                    BanjoTooieArt.healthHex(c, x + i * size * 1.05f, h * 0.56f, size, mPaint, i < mCur);
                }
            }
            drawText(c, max > 0 ? mCur + "/" + max : "—", w - valueW / 2, cy, dp(17), valueW, WHITE, true);
        }
    }

    /** World name with Jiggies x/10 and notes x/100. */
    private final class WorldRow extends View
    {
        private final String mName; private int mJiggies = -1, mNotes = -1;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF mBox = new RectF();

        WorldRow(Context ctx, String name) { super(ctx); mName = name; }

        void set(int jiggies, int notes)
        {
            if (jiggies != mJiggies || notes != mNotes) { mJiggies = jiggies; mNotes = notes; invalidate(); }
        }

        @Override
        protected void onDraw(Canvas c)
        {
            float w = getWidth(), h = getHeight();
            boolean done = mJiggies >= 10 && mNotes >= 100;
            drawText(c, mName, 0, h * 0.28f, dp(15), w, done ? GOLD_LIGHT : WHITE, false);
            float half = w / 2;
            item(c, BanjoTooieIcons.JIGGY, mJiggies, 10, 0, half - dp(8), h);
            item(c, BanjoTooieIcons.NOTE, mNotes, 100, half + dp(8), w, h);
            if (getParent() instanceof ViewGroup && ((ViewGroup) getParent()).indexOfChild(this)
                    < ((ViewGroup) getParent()).getChildCount() - 1) {
                mPaint.setShader(null);
                mPaint.setColor(PANEL_EDGE);
                c.drawRect(0, h - dp(1), w, h, mPaint);
            }
        }

        private void item(Canvas c, String icon, int value, int max, float left, float right, float h)
        {
            float icon_ = h * 0.34f, cy = h * 0.68f;
            mBox.set(left, cy - icon_ / 2, left + icon_, cy + icon_ / 2);
            drawIcon(c, icon, mBox, mPaint);
            String text = Math.max(0, value) + "/" + max;
            float tx = mBox.right + dp(6), textW = dp(52);
            drawText(c, text, tx, cy, dp(14), textW, WHITE, false);
            mBox.set(tx + textW + dp(4), cy - dp(3), right, cy + dp(3));
            if (mBox.width() <= dp(10)) return;
            mPaint.setShader(null);
            mPaint.setColor(TRACK);
            c.drawRoundRect(mBox, dp(3), dp(3), mPaint);
            float f = Math.max(0, Math.min(1f, value / (float) max));
            if (f > 0) {
                mBox.right = mBox.left + Math.max(mBox.height(), mBox.width() * f);
                mPaint.setColor(f >= 1f ? GOLD_LIGHT : GOLD);
                c.drawRoundRect(mBox, dp(3), dp(3), mPaint);
            }
        }
    }

    /** Wooden tab button; the selected one glows gold. */
    private final class TabView extends View
    {
        private final String mIcon;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF mBox = new RectF();

        TabView(Context ctx, String icon)
        {
            super(ctx);
            mIcon = icon;
            setClickable(true);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec)
        {
            int w = MeasureSpec.getSize(widthSpec);
            setMeasuredDimension(w, Math.min(w, dp(96)));
        }

        @Override
        public void setSelected(boolean selected)
        {
            super.setSelected(selected);
            invalidate();
        }

        @Override
        protected void drawableStateChanged()
        {
            super.drawableStateChanged();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c)
        {
            float w = getWidth(), h = getHeight();
            float inset = dp(4), r = dp(14);
            boolean sel = isSelected(), down = isPressed();
            mBox.set(inset, inset, w - inset, h - inset);

            if (sel) {
                // Glow: a few widening, fading strokes
                mPaint.setStyle(Paint.Style.STROKE);
                for (int i = 4; i >= 1; i--) {
                    mPaint.setStrokeWidth(dp(2.5f * i));
                    mPaint.setColor((0x22 + (4 - i) * 0x10) << 24 | 0xFFB020);
                    c.drawRoundRect(mBox, r, r, mPaint);
                }
                mPaint.setStyle(Paint.Style.FILL);
            }

            // Wood (or gold when selected)
            mPaint.setShader(new LinearGradient(0, mBox.top, 0, mBox.bottom,
                    sel ? new int[] {0xFFFFD86A, 0xFFF0A02A, 0xFFC8740E} : new int[] {0xFF6A4A30, 0xFF4E3522, 0xFF3A271A},
                    null, Shader.TileMode.CLAMP));
            c.drawRoundRect(mBox, r, r, mPaint);
            mPaint.setShader(null);
            if (!sel) {
                // A little grain
                mPaint.setColor(0x18000000);
                mPaint.setStrokeWidth(dp(1));
                for (int i = 1; i < 6; i++) {
                    float y = mBox.top + mBox.height() * i / 6f;
                    c.drawLine(mBox.left + dp(6), y, mBox.right - dp(6), y + dp(2) * ((i % 2) * 2 - 1), mPaint);
                }
            }
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(dp(2));
            mPaint.setColor(sel ? 0xFFFFE9A6 : 0xFF2A1C12);
            c.drawRoundRect(mBox, r, r, mPaint);
            mPaint.setStyle(Paint.Style.FILL);
            if (down) {
                mPaint.setColor(0x33000000);
                c.drawRoundRect(mBox, r, r, mPaint);
            }

            float icon = Math.min(mBox.width(), mBox.height()) * 0.62f;
            RectF box = new RectF(mBox.centerX() - icon / 2, mBox.centerY() - icon / 2,
                    mBox.centerX() + icon / 2, mBox.centerY() + icon / 2);
            BanjoTooieArt.draw(c, mIcon, box, mPaint);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Memory snapshot (developer aid)
    // ---------------------------------------------------------------------------------------------

    private boolean mSaving = false;

    /**
     * Save the whole N64 memory to Download/Mupen64BT/ so it can be analysed (finding the game's
     * own icons and HUD code). Runs in the background; the game keeps running.
     * Long-press the character medallion to use it.
     */
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
                for (int off = 0; off < all.length; off += chunk) {
                    byte[] buf = new byte[chunk];
                    if (!BanjoTooieStats.readRaw(off, buf, chunk)) throw new IllegalStateException("game not running");
                    System.arraycopy(buf, 0, all, off, chunk);
                }
                result = write(ctx, name, all);
            } catch (Exception e) {
                Log.e("BanjoTooieStats", "Snapshot failed", e);
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
        } else {
            File dir = new File(ctx.getExternalFilesDir(null), "Mupen64BT");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, name);
            try (FileOutputStream os = new FileOutputStream(f)) {
                os.write(data);
            }
            return f.getAbsolutePath();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Refresh
    // ---------------------------------------------------------------------------------------------

    private void update()
    {
        mSnapshot = mStats.read(mSnapshot);
        boolean valid = mSnapshot.valid;
        mWaiting.setVisibility(valid ? View.GONE : View.VISIBLE);
        mBody.setVisibility(valid ? View.VISIBLE : View.INVISIBLE);
        if (!valid) return;
        for (Binder b : mBinders) b.bind(mSnapshot);
    }

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
        super.onDetachedFromWindow();
    }
}
