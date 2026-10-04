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
package paulscode.android.mupen64plusae;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.view.menu.MenuBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BETA: the second screen as a game card (dual-screen devices such as the AYN Thor).
 *
 * The "Game" tab shows the game highlighted on the main screen (cover, name, region, its
 * settings) with big buttons to play it; it follows the highlight on the main screen as it moves.
 * The other tabs are the app's menus (Settings, Profiles, Tools, Help, About) as tiles.
 *
 * Controller: L1/R1 switch tabs, the d-pad moves between buttons, A presses.
 */
public class GameCardPanel extends LinearLayout
{
    /** What the panel needs from the game list screen. */
    public interface Host
    {
        /** Run one of the game's actions (resume, restart, settings, ...) on that game. */
        void onGameCardAction(@NonNull GalleryItem item, @NonNull MenuItem action);

        /** Run an item of the app menu. */
        boolean onOptionsItemSelected(@NonNull MenuItem item);

        /** The app menu (already adjusted for this device). */
        @Nullable Menu getAppMenu();

        /** Read a game's settings. Called on a background thread. */
        @NonNull Details loadDetails(@NonNull GalleryItem item);

        boolean isAndroidTv();
    }

    /** A game's settings, as shown on the tiles. */
    public static class Details
    {
        public String emulationProfile;
        public String video;
        public String controllerProfile;
    }

    private static final int ACCENT = 0xFF3FE0C5;
    private static final int TEXT = 0xFFF2F6F8;
    private static final int TEXT_DIM = 0xFFAFC2CC;
    private static final long FOCUS_DEBOUNCE_MS = 120;

    private static final ExecutorService sLoader = Executors.newSingleThreadExecutor();

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Host mHost;

    private final List<TextView> mTabViews = new ArrayList<>();
    private final List<MenuItem> mTabGroups = new ArrayList<>(); // null entry = Game tab
    private int mTab = 0;

    private FrameLayout mContent;
    private View mGamePage;
    private View mGameEmpty;
    private View mGameCard;
    private ImageView mCover;
    private TextView mTitle;
    private TextView mMeta;
    private TextView mRomName;
    private LinearLayout mButtons;
    private TextView mTileEmulation;
    private TextView mTileVideo;
    private TextView mTileController;
    private TextView mTileLastPlayed;
    private View mTilesRow;
    private ScrollView mMenuPage;
    private LinearLayout mMenuTiles;

    private Menu mGameMenu;
    private GalleryItem mItem;
    private GalleryItem mPendingItem;
    private boolean mShowingMore = false;

    private final Runnable mApplyPending = () -> {
        if (mPendingItem != null) applyGame(mPendingItem);
    };

    public GameCardPanel(Context context, @Nullable AttributeSet attrs)
    {
        super(context, attrs);
        setOrientation(VERTICAL);
        setBackgroundColor(Color.TRANSPARENT);
        int pad = dp(10);
        setPadding(pad, pad, pad, pad);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
    }

    private int dp(float v)
    {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    // ---------------------------------------------------------------------------------------------
    // Setup
    // ---------------------------------------------------------------------------------------------

    /** Build the panel. Call once the app menu is set up. */
    @SuppressLint("RestrictedApi")
    public void setHost(@NonNull Host host)
    {
        mHost = host;
        removeAllViews();
        mTabViews.clear();
        mTabGroups.clear();

        mGameMenu = new MenuBuilder(getContext());
        new android.view.MenuInflater(getContext()).inflate(R.menu.gallery_game_drawer, mGameMenu);
        if (host.isAndroidTv()) mGameMenu.removeItem(R.id.menuItem_createShortcut);

        buildTabBar();

        mContent = new FrameLayout(getContext());
        LayoutParams contentLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        contentLp.topMargin = dp(8);
        addView(mContent, contentLp);

        buildGamePage();
        buildMenuPage();
        selectTab(0, false);
        showNoGame();
    }

    private void buildTabBar()
    {
        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        bar.setBackground(glass(0x33FFFFFF, 0x22FFFFFF, dp(14)));

        addTab(bar, getContext().getString(R.string.gameCard_tabGame), R.drawable.ic_controller, null);

        Menu appMenu = mHost.getAppMenu();
        if (appMenu != null) {
            for (int i = 0; i < appMenu.size(); i++) {
                MenuItem item = appMenu.getItem(i);
                if (item.hasSubMenu() && item.isVisible()) {
                    addTab(bar, String.valueOf(item.getTitle()), 0, item);
                }
            }
        }

        TextView beta = new TextView(getContext());
        beta.setText(R.string.gameCard_beta);
        beta.setTextColor(ACCENT);
        beta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        beta.setTypeface(Typeface.DEFAULT_BOLD);
        beta.setLetterSpacing(0.15f);
        beta.setPadding(dp(6), 0, dp(4), 0);
        bar.addView(beta, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        addView(bar, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addTab(LinearLayout bar, String title, int iconRes, @Nullable MenuItem group)
    {
        final int index = mTabViews.size();
        TextView tab = new TextView(getContext());
        tab.setText(title);
        tab.setSingleLine(true);
        tab.setEllipsize(TextUtils.TruncateAt.END);
        tab.setGravity(Gravity.CENTER);
        tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tab.setPadding(dp(6), dp(7), dp(6), dp(7));
        tab.setFocusable(true);
        tab.setBackground(buttonBackground(false, dp(10)));
        Drawable icon = iconRes != 0 ? getResources().getDrawable(iconRes, null)
                : group != null ? group.getIcon() : null;
        if (icon != null) {
            icon = icon.mutate();
            int size = dp(16);
            icon.setBounds(0, 0, size, size);
            tab.setCompoundDrawables(icon, null, null, null);
            tab.setCompoundDrawablePadding(dp(5));
        }
        tab.setOnClickListener(v -> selectTab(index, true));
        mTabViews.add(tab);
        mTabGroups.add(group);
        LayoutParams lp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, index == 0 ? 1.25f : 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        bar.addView(tab, lp);
    }

    private void buildGamePage()
    {
        Context ctx = getContext();
        LinearLayout page = new LinearLayout(ctx);
        page.setOrientation(VERTICAL);

        // Card: cover | name and details | buttons
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(HORIZONTAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setBackground(glass(0x2EFFFFFF, 0x30FFFFFF, dp(18)));
        mGameCard = card;

        mCover = new ImageView(ctx);
        mCover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mCover.setAdjustViewBounds(false);
        mCover.setClipToOutline(true);
        GradientDrawable coverBg = new GradientDrawable();
        coverBg.setCornerRadius(dp(12));
        coverBg.setColor(0x22000000);
        mCover.setBackground(coverBg);
        card.addView(mCover, new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.32f));

        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(VERTICAL);
        info.setPadding(dp(12), dp(2), dp(10), dp(2));

        mTitle = new TextView(ctx);
        mTitle.setTextColor(TEXT);
        mTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mTitle.setMaxLines(3);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        mTitle.setAutoSizeTextTypeUniformWithConfiguration(13, 24, 1, TypedValue.COMPLEX_UNIT_SP);
        info.addView(mTitle, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mMeta = new TextView(ctx);
        mMeta.setTextColor(TEXT_DIM);
        mMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LayoutParams metaLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = dp(6);
        info.addView(mMeta, metaLp);

        mRomName = new TextView(ctx);
        mRomName.setTextColor(0x99AFC2CC);
        mRomName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        mRomName.setSingleLine(true);
        mRomName.setEllipsize(TextUtils.TruncateAt.END);
        LayoutParams romLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        romLp.topMargin = dp(4);
        info.addView(mRomName, romLp);

        card.addView(info, new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.38f));

        mButtons = new LinearLayout(ctx);
        mButtons.setOrientation(VERTICAL);
        card.addView(mButtons, new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.30f));

        page.addView(card, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Tiles: the game's settings
        LinearLayout tiles = new LinearLayout(ctx);
        tiles.setOrientation(HORIZONTAL);
        mTilesRow = tiles;
        mTileEmulation = addTile(tiles, R.string.gameCard_tileEmulation, R.drawable.ic_circuit, true);
        mTileVideo = addTile(tiles, R.string.gameCard_tileVideo, R.drawable.ic_settings, true);
        mTileController = addTile(tiles, R.string.gameCard_tileController, R.drawable.ic_controller, true);
        mTileLastPlayed = addTile(tiles, R.string.gameCard_tileLastPlayed, R.drawable.ic_play, false);
        LayoutParams tilesLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tilesLp.topMargin = dp(8);
        page.addView(tiles, tilesLp);

        // Nothing highlighted yet
        TextView empty = new TextView(ctx);
        empty.setText(R.string.gameCard_selectGame);
        empty.setTextColor(TEXT_DIM);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        empty.setGravity(Gravity.CENTER);
        empty.setBackground(glass(0x1FFFFFFF, 0x22FFFFFF, dp(18)));
        mGameEmpty = empty;

        FrameLayout holder = new FrameLayout(ctx);
        holder.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        holder.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        mGamePage = holder;
        mContent.addView(holder, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private TextView addTile(LinearLayout row, int labelRes, int iconRes, boolean opensSettings)
    {
        Context ctx = getContext();
        LinearLayout tile = new LinearLayout(ctx);
        tile.setOrientation(HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(8), dp(8), dp(8), dp(8));
        tile.setBackground(opensSettings ? buttonBackground(false, dp(14))
                : glass(0x26FFFFFF, 0x22FFFFFF, dp(14)));
        tile.setFocusable(opensSettings);
        if (opensSettings) {
            tile.setOnClickListener(v -> runGameAction(R.id.menuItem_settings));
        }

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(TEXT));
        tile.addView(icon, new LayoutParams(dp(18), dp(18)));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(VERTICAL);
        texts.setPadding(dp(6), 0, 0, 0);

        TextView label = new TextView(ctx);
        label.setText(labelRes);
        label.setTextColor(TEXT);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(label);

        TextView value = new TextView(ctx);
        value.setTextColor(TEXT_DIM);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(value);

        tile.addView(texts, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LayoutParams lp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        row.addView(tile, lp);
        return value;
    }

    private void buildMenuPage()
    {
        mMenuPage = new ScrollView(getContext());
        mMenuPage.setFillViewport(true);
        mMenuPage.setVerticalScrollBarEnabled(false);
        mMenuTiles = new LinearLayout(getContext());
        mMenuTiles.setOrientation(VERTICAL);
        mMenuPage.addView(mMenuTiles, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mContent.addView(mMenuPage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ---------------------------------------------------------------------------------------------
    // Tabs
    // ---------------------------------------------------------------------------------------------

    /** Next/previous tab (L1/R1). */
    public void switchTab(int delta, boolean focus)
    {
        if (mTabViews.isEmpty()) return;
        int n = mTabViews.size();
        selectTab(((mTab + delta) % n + n) % n, focus);
    }

    public void selectTab(int index, boolean focus)
    {
        if (index < 0 || index >= mTabViews.size()) return;
        mTab = index;
        for (int i = 0; i < mTabViews.size(); i++) {
            TextView tab = mTabViews.get(i);
            boolean on = i == index;
            tab.setBackground(buttonBackground(on, dp(10)));
            tab.setTextColor(on ? TEXT : TEXT_DIM);
            tab.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            Drawable icon = tab.getCompoundDrawables()[0];
            if (icon != null) icon.setTint(on ? ACCENT : TEXT_DIM);
        }

        MenuItem group = mTabGroups.get(index);
        if (group == null) {
            mGamePage.setVisibility(VISIBLE);
            mMenuPage.setVisibility(GONE);
        } else {
            fillMenuPage(group);
            mGamePage.setVisibility(GONE);
            mMenuPage.setVisibility(VISIBLE);
        }
        if (focus) focusDefault();
    }

    public boolean isGameTab()
    {
        return mTab == 0;
    }

    /** Tiles for one group of the app menu, two per row. */
    private void fillMenuPage(MenuItem group)
    {
        mMenuTiles.removeAllViews();
        List<MenuItem> items = new ArrayList<>();
        SubMenu sub = group.getSubMenu();
        if (sub != null) {
            for (int i = 0; i < sub.size(); i++) {
                if (sub.getItem(i).isVisible()) items.add(sub.getItem(i));
            }
        }
        // Menu entries without a group go where they fit best
        Menu appMenu = mHost.getAppMenu();
        if (appMenu != null) {
            MenuItem extra = null;
            if (group.getItemId() == R.id.menuItem_tools) extra = appMenu.findItem(R.id.menuItem_refreshRoms);
            if (group.getItemId() == R.id.menuItem_settings) extra = appMenu.findItem(R.id.menuItem_localeOverride);
            if (extra != null && extra.isVisible()) {
                if (extra.getItemId() == R.id.menuItem_refreshRoms) items.add(0, extra);
                else items.add(extra);
            }
        }

        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(getContext());
                row.setOrientation(HORIZONTAL);
                LayoutParams rowLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.bottomMargin = dp(6);
                mMenuTiles.addView(row, rowLp);
            }
            final MenuItem item = items.get(i);
            TextView tile = makeButton(String.valueOf(item.getTitle()), item.getIcon(), false);
            tile.setMinHeight(dp(54));
            tile.setOnClickListener(v -> mHost.onOptionsItemSelected(item));
            LayoutParams lp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            row.addView(tile, lp);
        }
        if (row != null && row.getChildCount() == 1) {
            View spacer = new View(getContext());
            row.addView(spacer, new LayoutParams(0, 1, 1f));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The game
    // ---------------------------------------------------------------------------------------------

    /**
     * Show this game on the card (it follows the highlight on the main screen). Quick highlight
     * moves are merged so scrolling through the list stays smooth.
     */
    public void showGame(@Nullable GalleryItem item, boolean switchToGameTab)
    {
        if (switchToGameTab && mTab != 0) selectTab(0, false);
        if (item == null) {
            showNoGame();
            return;
        }
        mPendingItem = item;
        mHandler.removeCallbacks(mApplyPending);
        if (mItem == null || !mItem.md5.equals(item.md5)) {
            mHandler.postDelayed(mApplyPending, FOCUS_DEBOUNCE_MS);
        } else {
            applyGame(item);
        }
    }

    /** Show it right away (the game was picked, not just passed over). */
    public void showGameNow(@NonNull GalleryItem item)
    {
        if (mTab != 0) selectTab(0, false);
        mHandler.removeCallbacks(mApplyPending);
        applyGame(item);
    }

    @Nullable
    public GalleryItem getGame()
    {
        return mItem;
    }

    private void showNoGame()
    {
        mItem = null;
        if (mGameEmpty != null) mGameEmpty.setVisibility(VISIBLE);
        if (mGameCard != null) ((View) mGameCard.getParent()).setVisibility(INVISIBLE);
    }

    private void applyGame(@NonNull GalleryItem item)
    {
        mPendingItem = null;
        boolean same = mItem != null && mItem.md5.equals(item.md5);
        mItem = item;
        mGameEmpty.setVisibility(GONE);
        ((View) mGameCard.getParent()).setVisibility(VISIBLE);

        mTitle.setText(item.displayName != null ? item.displayName : item.toString());
        mMeta.setText(regionName(item) + "  ·  Nintendo 64");
        mRomName.setText(item.goodName != null && !item.goodName.equals(item.displayName) ? item.goodName : "");
        mTileLastPlayed.setText(lastPlayedText(item.lastPlayed));

        if (!same) {
            mShowingMore = false;
            fillButtons();
            mCover.setImageResource(R.drawable.default_coverart);
            mTileEmulation.setText("…");
            mTileVideo.setText("…");
            mTileController.setText("…");
            loadInBackground(item);
        }
    }

    private void loadInBackground(final GalleryItem item)
    {
        final Host host = mHost;
        final String artPath = item.artPath;
        final int maxSize = dp(320);
        sLoader.execute(() -> {
            Bitmap cover = null;
            if (!TextUtils.isEmpty(artPath) && new File(artPath).exists()) {
                cover = decodeScaled(artPath, maxSize);
            }
            Details details;
            try {
                details = host.loadDetails(item);
            } catch (Throwable t) {
                details = new Details();
            }
            final Bitmap finalCover = cover;
            final Details finalDetails = details;
            mHandler.post(() -> {
                if (mItem == null || !mItem.md5.equals(item.md5)) return;
                if (finalCover != null) mCover.setImageDrawable(new BitmapDrawable(getResources(), finalCover));
                String none = getContext().getString(R.string.gameCard_none);
                mTileEmulation.setText(orElse(finalDetails.emulationProfile, none));
                mTileVideo.setText(orElse(finalDetails.video, none));
                mTileController.setText(orElse(finalDetails.controllerProfile, none));
            });
        });
    }

    private static String orElse(String s, String fallback)
    {
        return TextUtils.isEmpty(s) ? fallback : s;
    }

    @Nullable
    private static Bitmap decodeScaled(String path, int maxSize)
    {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= maxSize && bounds.outHeight / (sample * 2) >= maxSize) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(path, opts);
    }

    /** The big buttons: play, restart, game settings, and "More" for the rest. */
    private void fillButtons()
    {
        mButtons.removeAllViews();
        List<MenuItem> shown = new ArrayList<>();
        if (!mShowingMore) {
            for (int id : new int[] {R.id.menuItem_resume, R.id.menuItem_start, R.id.menuItem_settings}) {
                MenuItem m = mGameMenu.findItem(id);
                if (m != null) shown.add(m);
            }
        } else {
            for (int i = 0; i < mGameMenu.size(); i++) {
                int id = mGameMenu.getItem(i).getItemId();
                if (id != R.id.menuItem_resume && id != R.id.menuItem_start && id != R.id.menuItem_settings) {
                    shown.add(mGameMenu.getItem(i));
                }
            }
        }

        boolean first = true;
        for (final MenuItem m : shown) {
            TextView b = makeButton(String.valueOf(m.getTitle()), m.getIcon(), first && !mShowingMore);
            b.setOnClickListener(v -> runGameAction(m.getItemId()));
            addButton(b);
            first = false;
        }

        TextView toggle = makeButton(getContext().getString(mShowingMore ? R.string.gameCard_less
                : R.string.gameCard_more), null, false);
        toggle.setOnClickListener(v -> {
            mShowingMore = !mShowingMore;
            fillButtons();
            if (mButtons.getChildCount() > 0) mButtons.getChildAt(0).requestFocus();
        });
        addButton(toggle);
    }

    private void addButton(TextView b)
    {
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.setMargins(0, dp(3), 0, dp(3));
        mButtons.addView(b, lp);
    }

    private void runGameAction(int id)
    {
        if (mItem == null || mHost == null) return;
        MenuItem m = mGameMenu.findItem(id);
        if (m != null) mHost.onGameCardAction(mItem, m);
    }

    // ---------------------------------------------------------------------------------------------
    // Controller
    // ---------------------------------------------------------------------------------------------

    /** Put the controller focus on the most useful thing on the current tab. */
    public void focusDefault()
    {
        View target = null;
        if (mTab == 0) {
            if (mItem != null && mButtons.getChildCount() > 0) target = mButtons.getChildAt(0);
        } else if (mMenuTiles.getChildCount() > 0) {
            ViewGroup row = (ViewGroup) mMenuTiles.getChildAt(0);
            if (row.getChildCount() > 0) target = row.getChildAt(0);
        }
        if (target == null && !mTabViews.isEmpty()) target = mTabViews.get(mTab);
        if (target != null) target.requestFocusFromTouch();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event)
    {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                switchTab(code == KeyEvent.KEYCODE_BUTTON_L1 ? -1 : 1, true);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // ---------------------------------------------------------------------------------------------
    // Look
    // ---------------------------------------------------------------------------------------------

    private TextView makeButton(String text, @Nullable Drawable icon, boolean primary)
    {
        TextView b = new TextView(getContext());
        b.setText(text);
        b.setTextColor(TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, primary ? 14 : 12);
        if (primary) b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setMaxLines(2);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setPadding(dp(10), dp(4), dp(8), dp(4));
        b.setFocusable(true);
        b.setClickable(true);
        b.setBackground(primary ? primaryBackground() : buttonBackground(false, dp(12)));
        if (icon != null && icon.getConstantState() != null) {
            Drawable d = icon.getConstantState().newDrawable(getResources()).mutate();
            int size = dp(18);
            d.setBounds(0, 0, size, size);
            d.setTint(primary ? Color.WHITE : TEXT);
            b.setCompoundDrawables(d, null, null, null);
            b.setCompoundDrawablePadding(dp(8));
        }
        return b;
    }

    /** Frosted glass panel. */
    private static GradientDrawable glass(int fill, int stroke, float radius)
    {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        g.setStroke(2, stroke);
        return g;
    }

    /** Glass button; teal outline when highlighted by the controller or selected. */
    private Drawable buttonBackground(boolean selected, float radius)
    {
        GradientDrawable normal = glass(selected ? 0x4D3FE0C5 : 0x1FFFFFFF,
                selected ? ACCENT : 0x30FFFFFF, radius);
        GradientDrawable focused = glass(0x553FE0C5, ACCENT, radius);
        focused.setStroke(dp(2), ACCENT);
        GradientDrawable pressed = glass(0x803FE0C5, ACCENT, radius);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_pressed}, pressed);
        states.addState(new int[] {android.R.attr.state_focused}, focused);
        states.addState(new int[] {}, normal);
        return states;
    }

    private Drawable primaryBackground()
    {
        GradientDrawable normal = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xCC1FB89F, 0xCC3FE0C5});
        normal.setCornerRadius(dp(12));
        normal.setStroke(2, 0x88FFFFFF);
        GradientDrawable focused = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xFF1FB89F, 0xFF5FF0D8});
        focused.setCornerRadius(dp(12));
        focused.setStroke(dp(2), Color.WHITE);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_pressed}, focused);
        states.addState(new int[] {android.R.attr.state_focused}, focused);
        states.addState(new int[] {}, normal);
        return states;
    }

    // ---------------------------------------------------------------------------------------------
    // Text
    // ---------------------------------------------------------------------------------------------

    private String regionName(GalleryItem item)
    {
        if (item.countryCode == null) return "";
        switch (item.countryCode) {
            case USA: return "USA";
            case JAPAN: return "Japan";
            case JAPAN_USA: return "Japan / USA";
            case JAPAN_KOREA: return "Japan / Korea";
            case KOREA: return "Korea";
            case GERMANY: return "Germany";
            case FRANCE: return "France";
            case ITALY: return "Italy";
            case SPAIN: return "Spain";
            case AUSTRALIA:
            case AUSTRALIA_ALT: return "Australia";
            case EUROPE_1: case EUROPE_2: case EUROPE_3: case EUROPE_4: case EUROPE_5: case EUROPE_6:
                return "Europe";
            case DEMO: return "Demo";
            case BETA: return "Beta";
            default: return "";
        }
    }

    private String lastPlayedText(int lastPlayedSeconds)
    {
        Context ctx = getContext();
        if (lastPlayedSeconds <= 0) return ctx.getString(R.string.gameCard_never);
        long days = (System.currentTimeMillis() / 1000 - lastPlayedSeconds) / 86400;
        if (days <= 0) return ctx.getString(R.string.gameCard_today);
        if (days == 1) return ctx.getString(R.string.gameCard_yesterday);
        return ctx.getString(R.string.gameCard_daysAgo, (int) days);
    }
}
