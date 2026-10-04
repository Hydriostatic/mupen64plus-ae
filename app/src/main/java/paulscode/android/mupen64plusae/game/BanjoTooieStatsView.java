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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Live Banjo-Tooie stats for the second screen, laid out like a little pause menu:
 * an overview page (current character and health, ammo, collection totals), and pages you tap
 * into for every character's health and for all eggs and feathers.
 *
 * Refreshes itself a few times per second while it is on screen.
 */
public class BanjoTooieStatsView extends FrameLayout
{
    private static final long REFRESH_MS = 200;

    // Warm "storybook" palette
    private static final int BG = 0xFF24190F;
    private static final int CARD = 0xFF3E2B19;
    private static final int CARD_HI = 0xFF5A3E22;
    private static final int GOLD = 0xFFF2B43A;
    private static final int CREAM = 0xFFF6E7C8;
    private static final int MUTED = 0xFFB59C77;
    private static final int EMPTY = 0xFF5C4630;

    private static final int PAGE_OVERVIEW = 0, PAGE_CHARACTERS = 1, PAGE_AMMO = 2;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final BanjoTooieStats mStats = new BanjoTooieStats();
    private BanjoTooieStats.Snapshot mSnapshot = new BanjoTooieStats.Snapshot();
    private boolean mRunning = false;
    private final float mDp;

    private int mPage = PAGE_OVERVIEW;
    private final View[] mPages = new View[3];
    private TextView mTitle, mBack, mWaiting;
    private View mBody;

    /** Things refreshed on each update. */
    private interface Binder { void bind(BanjoTooieStats.Snapshot s); }
    private final List<Binder> mBinders = new ArrayList<>();

    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) return;
            if (isShown()) update();
            mHandler.postDelayed(this, REFRESH_MS);
        }
    };

    public BanjoTooieStatsView(Context context)
    {
        super(context);
        mDp = context.getResources().getDisplayMetrics().density;
        setBackgroundColor(BG);
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
        addView(root, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Header: back (on detail pages) + title
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(10), dp(12), dp(6));
        mBack = text(ctx, 18, GOLD, true);
        mBack.setText("‹ Back");
        mBack.setPadding(0, dp(4), dp(16), dp(4));
        mBack.setOnClickListener(v -> showPage(PAGE_OVERVIEW));
        header.addView(mBack);
        mTitle = text(ctx, 20, GOLD, true);
        header.addView(mTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header);

        mWaiting = text(ctx, 16, MUTED, false);
        mWaiting.setText("Waiting for the game…");
        mWaiting.setGravity(Gravity.CENTER);
        root.addView(mWaiting, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout body = new FrameLayout(ctx);
        mBody = body;
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mPages[PAGE_OVERVIEW] = buildOverview(ctx);
        mPages[PAGE_CHARACTERS] = buildCharacters(ctx);
        mPages[PAGE_AMMO] = buildAmmo(ctx);
        for (View page : mPages) {
            body.addView(page, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        showPage(PAGE_OVERVIEW);
    }

    private void showPage(int page)
    {
        mPage = page;
        for (int i = 0; i < mPages.length; i++) {
            mPages[i].setVisibility(i == page ? View.VISIBLE : View.GONE);
        }
        mBack.setVisibility(page == PAGE_OVERVIEW ? View.GONE : View.VISIBLE);
        mTitle.setText(page == PAGE_CHARACTERS ? "Characters" : page == PAGE_AMMO ? "Eggs & Feathers" : "Banjo-Tooie");
        if (mSnapshot.valid) update();
    }

    private ScrollView scrollPage(Context ctx, LinearLayout content)
    {
        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10), dp(4), dp(10), dp(12));
        scroll.addView(content, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private View buildOverview(Context ctx)
    {
        LinearLayout content = new LinearLayout(ctx);

        // Current character + health (tap: all characters)
        LinearLayout charCard = card(ctx, true);
        charCard.setOrientation(LinearLayout.HORIZONTAL);
        charCard.setGravity(Gravity.CENTER_VERTICAL);
        IconView portrait = new IconView(ctx, null, "?", GOLD);
        charCard.addView(portrait, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout charText = new LinearLayout(ctx);
        charText.setOrientation(LinearLayout.VERTICAL);
        charText.setPadding(dp(12), 0, 0, 0);
        TextView name = text(ctx, 20, CREAM, true);
        charText.addView(name);
        HexBar health = new HexBar(ctx);
        charText.addView(health, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)));
        charCard.addView(charText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        charCard.addView(chevron(ctx));
        charCard.setOnClickListener(v -> showPage(PAGE_CHARACTERS));
        content.addView(charCard, cardLp());
        mBinders.add(s -> {
            String n = BanjoTooieStats.characterName(s.character);
            name.setText(n != null ? n : "—");
            portrait.set(BanjoTooieIcons.character(s.character), n != null ? n.substring(0, 1) : "?");
            health.set(s.health, s.maxHealth);
        });

        // Eggs & feathers strip (tap: details)
        LinearLayout ammoCard = card(ctx, true);
        ammoCard.setOrientation(LinearLayout.VERTICAL);
        LinearLayout ammoTop = new LinearLayout(ctx);
        ammoTop.setOrientation(LinearLayout.HORIZONTAL);
        ammoTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView ammoTitle = text(ctx, 13, GOLD, true);
        ammoTitle.setText("EGGS & FEATHERS");
        ammoTop.addView(ammoTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ammoTop.addView(chevron(ctx));
        ammoCard.addView(ammoTop);
        LinearLayout ammoRow = new LinearLayout(ctx);
        ammoRow.setOrientation(LinearLayout.HORIZONTAL);
        ammoRow.setPadding(0, dp(6), 0, 0);
        for (Ammo a : AMMO) {
            LinearLayout cell = new LinearLayout(ctx);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.addView(new IconView(ctx, a.icon, a.letter, a.color), new LinearLayout.LayoutParams(dp(28), dp(28)));
            TextView count = text(ctx, 15, CREAM, true);
            count.setGravity(Gravity.CENTER);
            cell.addView(count);
            ammoRow.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            mBinders.add(s -> count.setText(String.valueOf(s.consumables[a.index])));
        }
        ammoCard.addView(ammoRow);
        ammoCard.setOnClickListener(v -> showPage(PAGE_AMMO));
        content.addView(ammoCard, cardLp());

        // Collection totals
        TextView totals = text(ctx, 13, GOLD, true);
        totals.setText("TOTALS");
        totals.setPadding(dp(6), dp(10), 0, dp(2));
        content.addView(totals);
        Total[] list = {
                new Total(BanjoTooieIcons.JIGGY, "Jiggies", "J", GOLD, 90, s -> s.jiggies),
                new Total(BanjoTooieIcons.NOTE, "Notes", "♪", 0xFFE86A3A, 900, s -> s.notes),
                new Total(BanjoTooieIcons.JINJO, "Jinjos", "j", 0xFF6BC46B, 45, s -> s.jinjos),
                new Total(BanjoTooieIcons.PAGE, "Cheato Pages", "P", 0xFFD8C6A0, 25, s -> s.cheatoPages),
                new Total(BanjoTooieIcons.HONEYCOMB, "Honeycombs", "H", 0xFFF0C24A, 25, s -> s.emptyHoneycombs),
                new Total(BanjoTooieIcons.GLOWBO, "Glowbos", "G", 0xFF9C7BE0, 0, s -> s.glowbos),
                new Total(BanjoTooieIcons.DOUBLOON, "Doubloons", "D", 0xFFE0B84A, 30, s -> s.doubloons),
        };
        LinearLayout row = null;
        for (int i = 0; i < list.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                content.addView(row);
            }
            row.addView(totalTile(ctx, list[i]), tileLp());
        }
        if (list.length % 2 == 1 && row != null) {
            View spacer = new View(ctx);
            row.addView(spacer, tileLp());
        }
        return scrollPage(ctx, content);
    }

    private View buildCharacters(Context ctx)
    {
        LinearLayout content = new LinearLayout(ctx);
        for (int id : BanjoTooieStats.PLAYABLE_CHARACTERS) {
            String n = BanjoTooieStats.characterName(id);
            if (n == null) continue;
            LinearLayout rowCard = card(ctx, false);
            rowCard.setOrientation(LinearLayout.HORIZONTAL);
            rowCard.setGravity(Gravity.CENTER_VERTICAL);
            rowCard.addView(new IconView(ctx, BanjoTooieIcons.character(id), n.substring(0, 1), GOLD),
                    new LinearLayout.LayoutParams(dp(40), dp(40)));
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(10), 0, 0, 0);
            TextView label = text(ctx, 16, CREAM, true);
            label.setText(n);
            col.addView(label);
            HexBar bar = new HexBar(ctx);
            col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)));
            rowCard.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView value = text(ctx, 16, CREAM, true);
            rowCard.addView(value);
            content.addView(rowCard, cardLp());
            final int cid = id;
            mBinders.add(s -> {
                int max = s.allMaxHealth[cid], cur = s.allHealth[cid];
                bar.set(cur, max);
                value.setText(max > 0 ? cur + "/" + max : "—");
                boolean current = s.character == cid;
                setCardColor(rowCard, current ? CARD_HI : CARD, current);
            });
        }
        return scrollPage(ctx, content);
    }

    private View buildAmmo(Context ctx)
    {
        LinearLayout content = new LinearLayout(ctx);
        for (Ammo a : AMMO) {
            LinearLayout rowCard = card(ctx, false);
            rowCard.setOrientation(LinearLayout.HORIZONTAL);
            rowCard.setGravity(Gravity.CENTER_VERTICAL);
            rowCard.addView(new IconView(ctx, a.icon, a.letter, a.color), new LinearLayout.LayoutParams(dp(40), dp(40)));
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(10), 0, dp(10), 0);
            TextView label = text(ctx, 16, CREAM, true);
            label.setText(a.name);
            col.addView(label);
            MeterBar meter = new MeterBar(ctx, a.color);
            col.addView(meter, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));
            rowCard.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView value = text(ctx, 18, CREAM, true);
            rowCard.addView(value);
            content.addView(rowCard, cardLp());
            mBinders.add(s -> {
                int count = s.consumables[a.index];
                int cap = Math.max(BanjoTooieStats.capacity(a.index), count);
                meter.set(count, cap);
                value.setText(count + " / " + cap);
            });
        }
        return scrollPage(ctx, content);
    }

    // ---------------------------------------------------------------------------------------------
    // Building blocks
    // ---------------------------------------------------------------------------------------------

    private static final class Ammo
    {
        final int index; final String icon, name, letter; final int color;
        Ammo(int index, String icon, String name, String letter, int color)
        {
            this.index = index; this.icon = icon; this.name = name; this.letter = letter; this.color = color;
        }
    }

    private static final Ammo[] AMMO = {
            new Ammo(BanjoTooieStats.BLUE_EGGS, BanjoTooieIcons.EGG_BLUE, "Blue Eggs", "B", 0xFF4F8DE8),
            new Ammo(BanjoTooieStats.FIRE_EGGS, BanjoTooieIcons.EGG_FIRE, "Fire Eggs", "F", 0xFFE8562F),
            new Ammo(BanjoTooieStats.GRENADE_EGGS, BanjoTooieIcons.EGG_GRENADE, "Grenade Eggs", "G", 0xFF8A8F3A),
            new Ammo(BanjoTooieStats.ICE_EGGS, BanjoTooieIcons.EGG_ICE, "Ice Eggs", "I", 0xFF8FDDF0),
            new Ammo(BanjoTooieStats.CLOCKWORK_EGGS, BanjoTooieIcons.EGG_CLOCKWORK, "Clockwork Eggs", "C", 0xFFC0C0C0),
            new Ammo(BanjoTooieStats.RED_FEATHERS, BanjoTooieIcons.FEATHER_RED, "Red Feathers", "R", 0xFFD83A3A),
            new Ammo(BanjoTooieStats.GOLD_FEATHERS, BanjoTooieIcons.FEATHER_GOLD, "Gold Feathers", "G", 0xFFF2C94A),
    };

    private interface Getter { int get(BanjoTooieStats.Snapshot s); }

    private static final class Total
    {
        final String icon, name, letter; final int color, max; final Getter getter;
        Total(String icon, String name, String letter, int color, int max, Getter getter)
        {
            this.icon = icon; this.name = name; this.letter = letter; this.color = color;
            this.max = max; this.getter = getter;
        }
    }

    private View totalTile(Context ctx, Total t)
    {
        LinearLayout tile = card(ctx, false);
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.addView(new IconView(ctx, t.icon, t.letter, t.color), new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(8), 0, 0, 0);
        TextView label = text(ctx, 11, MUTED, false);
        label.setText(t.name);
        col.addView(label);
        TextView value = text(ctx, 18, CREAM, true);
        col.addView(value);
        tile.addView(col);
        mBinders.add(s -> {
            int v = t.getter.get(s);
            value.setText(t.max > 0 ? v + "/" + t.max : String.valueOf(v));
        });
        return tile;
    }

    private LinearLayout card(Context ctx, boolean tappable)
    {
        LinearLayout c = new LinearLayout(ctx);
        c.setPadding(dp(12), dp(10), dp(12), dp(10));
        setCardColor(c, CARD, false);
        c.setClickable(tappable);
        return c;
    }

    private void setCardColor(View v, int color, boolean highlight)
    {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(highlight ? 3 : 2), highlight ? GOLD : 0xFF6E5233);
        v.setBackground(bg);
    }

    private LinearLayout.LayoutParams cardLp()
    {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(5), dp(4), dp(5));
        return lp;
    }

    private LinearLayout.LayoutParams tileLp()
    {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        return lp;
    }

    private TextView chevron(Context ctx)
    {
        TextView t = text(ctx, 22, GOLD, true);
        t.setText("›");
        t.setPadding(dp(8), 0, 0, 0);
        return t;
    }

    private TextView text(Context ctx, int sp, int color, boolean bold)
    {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Shows the game's icon for a key once loaded, otherwise a plain coloured badge with a letter. */
    private final class IconView extends View
    {
        private String mKey, mLetter;
        private final int mColor;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Rect mSrc = new Rect();
        private final RectF mDst = new RectF();

        IconView(Context ctx, String key, String letter, int color)
        {
            super(ctx);
            mKey = key; mLetter = letter; mColor = color;
            mBinders.add(s -> invalidate()); // icons may arrive later
        }

        void set(String key, String letter)
        {
            if (!key.equals(mKey) || !letter.equals(mLetter)) {
                mKey = key; mLetter = letter;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas)
        {
            float w = getWidth(), h = getHeight();
            Bitmap icon = mKey != null ? BanjoTooieIcons.get(mKey) : null;
            if (icon != null) {
                float scale = Math.min(w / icon.getWidth(), h / icon.getHeight());
                float iw = icon.getWidth() * scale, ih = icon.getHeight() * scale;
                mSrc.set(0, 0, icon.getWidth(), icon.getHeight());
                mDst.set((w - iw) / 2, (h - ih) / 2, (w + iw) / 2, (h + ih) / 2);
                canvas.drawBitmap(icon, mSrc, mDst, mPaint);
                return;
            }
            float r = Math.min(w, h) / 2f;
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(mColor);
            canvas.drawCircle(w / 2, h / 2, r * 0.92f, mPaint);
            mPaint.setColor(0x55000000);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(r * 0.12f);
            canvas.drawCircle(w / 2, h / 2, r * 0.86f, mPaint);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(0xFF1E140A);
            mPaint.setTextSize(r * 1.05f);
            mPaint.setTypeface(Typeface.DEFAULT_BOLD);
            mPaint.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics fm = mPaint.getFontMetrics();
            canvas.drawText(mLetter, w / 2, h / 2 - (fm.ascent + fm.descent) / 2, mPaint);
        }
    }

    /** Row of hexagons, filled up to the current health (uses the game's honeycomb icon if loaded). */
    private final class HexBar extends View
    {
        private int mCur, mMax;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Path mHex = new Path();

        HexBar(Context ctx) { super(ctx); }

        void set(int cur, int max)
        {
            if (max > 30) max = 0;
            cur = Math.max(0, Math.min(cur, max));
            if (cur != mCur || max != mMax) {
                mCur = cur; mMax = max;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas)
        {
            if (mMax <= 0) return;
            float h = getHeight();
            float size = Math.min(h, (getWidth() - 4f) / mMax);
            float r = size * 0.48f;
            Bitmap icon = BanjoTooieIcons.get(BanjoTooieIcons.HEALTH);
            for (int i = 0; i < mMax; i++) {
                float cx = size * i + size / 2, cy = h / 2;
                boolean full = i < mCur;
                if (icon != null) {
                    mPaint.setAlpha(full ? 255 : 70);
                    canvas.drawBitmap(icon, null, new RectF(cx - r, cy - r, cx + r, cy + r), mPaint);
                    continue;
                }
                mHex.reset();
                for (int k = 0; k < 6; k++) {
                    double a = Math.toRadians(60 * k + 30);
                    float x = cx + (float) (r * Math.cos(a)), y = cy + (float) (r * Math.sin(a));
                    if (k == 0) mHex.moveTo(x, y); else mHex.lineTo(x, y);
                }
                mHex.close();
                mPaint.setAlpha(255);
                mPaint.setStyle(Paint.Style.FILL);
                mPaint.setColor(full ? GOLD : EMPTY);
                canvas.drawPath(mHex, mPaint);
            }
        }
    }

    /** Simple filled bar. */
    private final class MeterBar extends View
    {
        private float mFraction;
        private final int mColor;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();

        MeterBar(Context ctx, int color) { super(ctx); mColor = color; }

        void set(int value, int max)
        {
            float f = max > 0 ? Math.min(1f, value / (float) max) : 0f;
            if (f != mFraction) { mFraction = f; invalidate(); }
        }

        @Override
        protected void onDraw(Canvas canvas)
        {
            float h = getHeight(), w = getWidth();
            mPaint.setColor(EMPTY);
            mRect.set(0, h * 0.2f, w, h * 0.8f);
            canvas.drawRoundRect(mRect, h, h, mPaint);
            mPaint.setColor(mColor);
            mRect.set(0, h * 0.2f, w * mFraction, h * 0.8f);
            canvas.drawRoundRect(mRect, h, h, mPaint);
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
        mBody.setVisibility(valid ? View.VISIBLE : View.GONE);
        if (!valid) return;
        for (Binder b : mBinders) b.bind(mSnapshot);
    }

    @Override
    protected void onAttachedToWindow()
    {
        super.onAttachedToWindow();
        mRunning = true;
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
