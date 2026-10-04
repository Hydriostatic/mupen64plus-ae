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
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Live Banjo-Tooie stats for the second screen: current character and health, collectibles and
 * ammo. Refreshes itself a few times per second while it is on screen.
 */
public class BanjoTooieStatsView extends ScrollView
{
    private static final long REFRESH_MS = 200;

    private static final int GOLD = 0xFFF5B82E;
    private static final int EMPTY = 0xFF5A5A5A;
    private static final int TILE_BG = 0xFF3D3D3D;
    private static final int LABEL = 0xFFBBBBBB;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final BanjoTooieStats mStats = new BanjoTooieStats();
    private BanjoTooieStats.Snapshot mSnapshot = new BanjoTooieStats.Snapshot();
    private boolean mRunning = false;

    private final float mDp;
    private TextView mCharacter, mHealth, mWaiting, mDebug;
    private TextView mJiggies, mNotes, mJinjos, mPages, mHoneycombs, mGlowbos, mDoubloons;
    private TextView mBlueEggs, mFireEggs, mGrenadeEggs, mIceEggs, mClockworkEggs, mRedFeathers, mGoldFeathers;
    private View mContent;

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
        setFillViewport(true);
        build(context);
    }

    private int dp(float v) { return Math.round(v * mDp); }

    private void build(Context ctx)
    {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        addView(root, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mWaiting = text(ctx, 16, LABEL, false);
        mWaiting.setText("Waiting for the game…");
        mWaiting.setGravity(Gravity.CENTER);
        mWaiting.setPadding(0, dp(40), 0, dp(40));
        root.addView(mWaiting);

        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        mContent = content;
        root.addView(content);

        // Character + health
        mCharacter = text(ctx, 22, Color.WHITE, true);
        mCharacter.setGravity(Gravity.CENTER);
        content.addView(mCharacter);
        mHealth = text(ctx, 26, GOLD, false);
        mHealth.setGravity(Gravity.CENTER);
        mHealth.setPadding(0, dp(2), 0, dp(10));
        content.addView(mHealth);

        content.addView(section(ctx, "Collectibles"));
        mJiggies = tile(ctx, content, "Jiggies", null);
        mNotes = tile(ctx, (LinearLayout) mJiggies.getTag(), "Notes", null);
        mJinjos = tile(ctx, content, "Jinjos", null);
        mPages = tile(ctx, (LinearLayout) mJinjos.getTag(), "Cheato Pages", null);
        mHoneycombs = tile(ctx, content, "Empty Honeycombs", null);
        mGlowbos = tile(ctx, (LinearLayout) mHoneycombs.getTag(), "Glowbos", null);
        mDoubloons = tile(ctx, content, "Doubloons", null);
        tile(ctx, (LinearLayout) mDoubloons.getTag(), "", null).setVisibility(View.INVISIBLE);

        content.addView(section(ctx, "Eggs & Feathers"));
        mBlueEggs = tile(ctx, content, "Blue Eggs", null);
        mFireEggs = tile(ctx, (LinearLayout) mBlueEggs.getTag(), "Fire Eggs", null);
        mGrenadeEggs = tile(ctx, content, "Grenade Eggs", null);
        mIceEggs = tile(ctx, (LinearLayout) mGrenadeEggs.getTag(), "Ice Eggs", null);
        mClockworkEggs = tile(ctx, content, "Clockwork Eggs", null);
        tile(ctx, (LinearLayout) mClockworkEggs.getTag(), "", null).setVisibility(View.INVISIBLE);
        mRedFeathers = tile(ctx, content, "Red Feathers", null);
        mGoldFeathers = tile(ctx, (LinearLayout) mRedFeathers.getTag(), "Gold Feathers", null);

        // Raw values, small, for checking the memory addresses on a real console
        mDebug = text(ctx, 10, 0xFF808080, false);
        mDebug.setGravity(Gravity.CENTER);
        mDebug.setPadding(0, dp(12), 0, 0);
        content.addView(mDebug);

        content.setVisibility(View.GONE);
    }

    private TextView text(Context ctx, int sp, int color, boolean bold)
    {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView section(Context ctx, String title)
    {
        TextView t = text(ctx, 13, GOLD, true);
        t.setText(title.toUpperCase());
        t.setPadding(dp(4), dp(12), 0, dp(4));
        return t;
    }

    /**
     * Adds a tile to a row. If {@code parent} is the content column, a new row is started; the
     * returned value view's tag is that row, so a second tile can be added next to it.
     */
    private TextView tile(Context ctx, LinearLayout parent, String label, String unused)
    {
        LinearLayout row;
        if (parent == mContent) {
            row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            parent.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            row = parent;
        }

        LinearLayout tile = new LinearLayout(ctx);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(TILE_BG);
        bg.setCornerRadius(dp(8));
        tile.setBackground(bg);

        TextView l = text(ctx, 12, LABEL, false);
        l.setText(label);
        tile.addView(l);
        TextView v = text(ctx, 22, Color.WHITE, true);
        v.setText("–");
        tile.addView(v);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        row.addView(tile, lp);
        v.setTag(row);
        return v;
    }

    private static String of(int value, int max)
    {
        return value + " / " + max;
    }

    private void update()
    {
        BanjoTooieStats.Snapshot s = mStats.read(mSnapshot);
        mSnapshot = s;

        if (!s.valid) {
            mWaiting.setVisibility(View.VISIBLE);
            mContent.setVisibility(View.GONE);
            return;
        }
        mWaiting.setVisibility(View.GONE);
        mContent.setVisibility(View.VISIBLE);

        String name = BanjoTooieStats.characterName(s.character);
        mCharacter.setText(name != null ? name : "");

        if (name != null && s.maxHealth > 0 && s.maxHealth <= 30) {
            int cur = Math.max(0, Math.min(s.health, s.maxHealth));
            SpannableStringBuilder sb = new SpannableStringBuilder();
            for (int i = 0; i < s.maxHealth; i++) {
                int start = sb.length();
                sb.append("⬢");
                sb.setSpan(new ForegroundColorSpan(i < cur ? GOLD : EMPTY), start, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            mHealth.setText(sb);
            mHealth.setVisibility(View.VISIBLE);
        } else {
            mHealth.setVisibility(View.GONE);
        }

        mJiggies.setText(of(s.jiggies, 90));
        mNotes.setText(of(s.notes, 900));
        mJinjos.setText(of(s.jinjos, 45));
        mPages.setText(of(s.cheatoPages, 25));
        mHoneycombs.setText(of(s.emptyHoneycombs, 25));
        mGlowbos.setText(String.valueOf(s.glowbos));
        mDoubloons.setText(of(s.doubloons, 30));

        int[] c = s.consumables;
        mBlueEggs.setText(String.valueOf(c[BanjoTooieStats.BLUE_EGGS]));
        mFireEggs.setText(String.valueOf(c[BanjoTooieStats.FIRE_EGGS]));
        mGrenadeEggs.setText(String.valueOf(c[BanjoTooieStats.GRENADE_EGGS]));
        mIceEggs.setText(String.valueOf(c[BanjoTooieStats.ICE_EGGS]));
        mClockworkEggs.setText(String.valueOf(c[BanjoTooieStats.CLOCKWORK_EGGS]));
        mRedFeathers.setText(String.valueOf(c[BanjoTooieStats.RED_FEATHERS]));
        mGoldFeathers.setText(String.valueOf(c[BanjoTooieStats.GOLD_FEATHERS]));

        mDebug.setText(String.format("char 0x%02X · hp %d %d %d · flags @%08X",
                s.character, s.healthBytes[0], s.healthBytes[1], s.healthBytes[2], s.flagBlockAddress));
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
