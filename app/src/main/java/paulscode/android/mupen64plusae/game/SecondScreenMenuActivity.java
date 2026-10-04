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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import paulscode.android.mupen64plusae.GameSidebar;
import paulscode.android.mupen64plusae.R;

/**
 * Shows a menu on the secondary display (e.g. the bottom screen of the AYN Thor).
 *
 * The menu views themselves belong to the activity on the main screen (same process); this
 * activity only provides a window on the second display to show them in. When there is no menu
 * to show it stays up with just its background, so the second screen never goes blank.
 *
 * Layout, filling the whole screen at its own resolution (1240x1080 on the Thor):
 * <ul>
 * <li>a clear N64 shell in the background, blurred, behind everything;</li>
 * <li>a fixed band at the top with the header picture (the app logo, or the selected game's
 *     cover), always full width and never stretched; it stays put while the menu scrolls;</li>
 * <li>the menu list below it, scaled to this screen's density.</li>
 * </ul>
 */
public class SecondScreenMenuActivity extends Activity
{
    private static final String TAG = "SecondScreenMenu";
    static final String EXTRA_DISPLAY_ID = "displayId";

    /** Background behind the shell picture, also shown when there's no menu on it. */
    static final int BACKGROUND_COLOR = 0xFF0D1116;

    /** How strong the blur behind the menus is, from 0 (none) to 1 (MAX_BLUR_DP). */
    private static final float BLUR_AMOUNT = 0.70f;
    private static final float MAX_BLUR_DP = 30f;

    /** Darkening over the blurred shell, so the menu text stays readable. */
    private static final int MENU_SCRIM = 0x66000000;

    /** Height of the header band, as a part of the screen height. */
    private static final float HEADER_HEIGHT_FRACTION = 0.30f;

    /** Which part of the logo stays in view when it's cropped to the band (0 = top, 1 = bottom). */
    private static final float LOGO_FOCUS_Y = 0.30f;

    private static WeakReference<DualScreenDrawerLayout> sHost = new WeakReference<>(null);

    static void setHost(DualScreenDrawerLayout host)
    {
        sHost = new WeakReference<>(host);
    }

    private DualScreenDrawerLayout mHost;
    private View mMenuView;
    private TextView mBackChip;
    private TextView mHint;
    private ScaledFrame mMenuContainer;
    private CropImageView mHeaderImage;
    private ImageView mHeaderBackdrop;
    private View mHeaderBand;
    private boolean mMenuOpen = false;
    private boolean mFinishingByHost = false;
    private boolean mShown = false;
    private boolean mStarted = false;
    private Object mBackCallback;

    /** What the header shows right now, so it's only redone when that changes. */
    private Drawable mHeaderSource;
    private boolean mHeaderSourceIsLogo;

    /** The borrowed menu's own look, put back when it leaves this screen. */
    private final android.util.SparseArray<Drawable> mSavedBackgrounds = new android.util.SparseArray<>();

    private final ViewTreeObserver.OnGlobalLayoutListener mLayoutListener = this::refreshHeader;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(BACKGROUND_COLOR));

        mHost = sHost.get();
        if (mHost == null) {
            // Host is gone (e.g. restored after process death); nothing to show
            mFinishingByHost = true;
            finish();
            return;
        }

        @SuppressWarnings("deprecation")
        int actualDisplay = getWindowManager().getDefaultDisplay().getDisplayId();
        int expectedDisplay = getIntent().getIntExtra(EXTRA_DISPLAY_ID, -1);
        if (actualDisplay != expectedDisplay) {
            Log.w(TAG, "Launched on display " + actualDisplay + " instead of " + expectedDisplay);
            DualScreenDrawerLayout host = mHost;
            mHost = null;
            mFinishingByHost = true;
            finish();
            host.onMenuScreenFailed("Android opened it on display " + actualDisplay +
                    " instead of " + expectedDisplay);
            return;
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        registerBackHandler();
        buildLayout();
        if (DualScreenDrawerLayout.DIAGNOSTICS) {
            android.widget.Toast.makeText(this, "2nd screen: menu opened here (screen " + actualDisplay + ")",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
        mHost.onMenuScreenReady(this);
    }

    /**
     * Android 13+ can deliver Back as a system back event instead of a key (predictive back). Take
     * it here too, so it never closes this screen.
     */
    private void registerBackHandler()
    {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        android.window.OnBackInvokedCallback callback = () -> {
            if (mHost != null) mHost.onBackFromMenuScreen();
        };
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
        mBackCallback = callback;
    }

    private float dp(float value)
    {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private void buildLayout()
    {
        final Context ctx = this;
        final DisplayMetrics metrics = getResources().getDisplayMetrics();
        final int pad = Math.round(dp(12));
        final boolean inGame = mHost.isInGame();

        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(BACKGROUND_COLOR);

        // Background: the clear N64 shell, filling the screen (cropped, never stretched), blurred
        ImageView shell = new ImageView(ctx);
        shell.setScaleType(ImageView.ScaleType.CENTER_CROP);
        shell.setImageResource(R.drawable.n64_clear_shell);
        blur(shell, BLUR_AMOUNT * dp(MAX_BLUR_DP));
        root.addView(shell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View scrim = new View(ctx);
        scrim.setBackgroundColor(MENU_SCRIM);
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Header band: full width, fixed height, stays at the top while the menu scrolls
        FrameLayout header = new FrameLayout(ctx);
        header.setClipChildren(true);
        int headerHeight = Math.round(metrics.heightPixels * HEADER_HEIGHT_FRACTION);

        mHeaderBackdrop = new ImageView(ctx);
        mHeaderBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mHeaderBackdrop.setVisibility(View.GONE);
        header.addView(mHeaderBackdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mHeaderImage = new CropImageView(ctx);
        header.addView(mHeaderImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Soft edge between the header and the menu
        View fade = new View(ctx);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0x99000000, 0x00000000}));
        FrameLayout.LayoutParams fadeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(dp(18)));
        fadeLp.gravity = Gravity.BOTTOM;
        header.addView(fade, fadeLp);

        // Game list only: Back, while a game's options (or the opened menu) have the buttons
        mBackChip = new TextView(ctx);
        mBackChip.setText(R.string.secondScreenMenu_back);
        mBackChip.setTextColor(Color.WHITE);
        mBackChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        mBackChip.setPadding(pad * 3 / 2, pad / 2, pad * 3 / 2, pad / 2);
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setColor(0xAA000000);
        chipBg.setCornerRadius(dp(20));
        chipBg.setStroke(Math.round(dp(1)), 0x66FFFFFF);
        mBackChip.setBackground(chipBg);
        mBackChip.setFocusable(false); // keep controller focus on the menu list
        mBackChip.setOnClickListener(v -> {
            if (mHost != null) mHost.closeDrawer(Gravity.START);
        });
        mBackChip.setVisibility(View.GONE);
        FrameLayout.LayoutParams chipLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.gravity = Gravity.TOP | Gravity.END;
        chipLp.setMargins(pad, pad, pad, pad);
        header.addView(mBackChip, chipLp);

        mHeaderBand = header;
        column.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, headerHeight));

        // The menu, filling the rest of the screen
        mMenuContainer = new ScaledFrame(ctx);
        column.addView(mMenuContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // In-game only: which screen the controller buttons drive right now
        TextView hint = new TextView(ctx);
        hint.setTextColor(0xFFDDDDDD);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(pad, pad / 2, pad, pad / 2);
        hint.setBackgroundColor(0x99000000);
        hint.setVisibility(inGame ? View.VISIBLE : View.GONE);
        mHint = hint;
        column.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        showLogo();
        showControllerHint(inGame, false);
    }

    // ---------------------------------------------------------------------------------------------
    // Blur
    // ---------------------------------------------------------------------------------------------

    /** Blur a picture. Android 12+ does it live; older versions get a pre-blurred copy. */
    private static void blur(ImageView view, float radiusPx)
    {
        if (radiusPx <= 0) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP));
        } else {
            Drawable d = view.getDrawable();
            Drawable blurred = blurredCopy(view.getResources(), d, radiusPx);
            if (blurred != null) view.setImageDrawable(blurred);
        }
    }

    /** Cheap blur for old Android versions: shrink the picture a lot, then let it be scaled up. */
    @Nullable
    private static Drawable blurredCopy(android.content.res.Resources res, @Nullable Drawable d, float radiusPx)
    {
        if (d == null || d.getIntrinsicWidth() <= 0 || d.getIntrinsicHeight() <= 0) return null;
        float shrink = Math.max(2f, radiusPx / 2f);
        int w = Math.max(1, Math.round(d.getIntrinsicWidth() / shrink));
        int h = Math.max(1, Math.round(d.getIntrinsicHeight() / shrink));
        Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(small);
        Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable(res).mutate() : d;
        copy.setBounds(0, 0, w, h);
        copy.draw(canvas);
        BitmapDrawable result = new BitmapDrawable(res, small);
        result.setFilterBitmap(true);
        return result;
    }

    private void setBackdrop(@Nullable Drawable cover)
    {
        if (cover == null || cover.getConstantState() == null) {
            mHeaderBackdrop.setImageDrawable(null);
            mHeaderBackdrop.setVisibility(View.GONE);
            return;
        }
        mHeaderBackdrop.setImageDrawable(cover.getConstantState().newDrawable(getResources()).mutate());
        mHeaderBackdrop.setColorFilter(0x88000000, android.graphics.PorterDuff.Mode.SRC_ATOP);
        blur(mHeaderBackdrop, dp(24));
        mHeaderBackdrop.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------------------------------------
    // Header band
    // ---------------------------------------------------------------------------------------------

    private void showLogo()
    {
        mHeaderImage.setImageResource(R.drawable.second_screen_logo);
        mHeaderImage.setCrop(true, LOGO_FOCUS_Y);
        setBackdrop(null);
    }

    /** The visible sidebar of the borrowed menu: the selected game's, or the main menu. */
    @Nullable
    private GameSidebar visibleSidebar()
    {
        if (mMenuView == null) return null;
        View game = mMenuView.findViewById(R.id.gameSidebar);
        if (game instanceof GameSidebar && game.getVisibility() == View.VISIBLE) return (GameSidebar) game;
        View main = mMenuView.findViewById(R.id.drawerNavigation);
        if (main instanceof GameSidebar && main.getVisibility() == View.VISIBLE) return (GameSidebar) main;
        return game instanceof GameSidebar ? (GameSidebar) game : null;
    }

    /**
     * Show the visible sidebar's picture in the header band: the app logo cropped to fill the
     * band, or the game's whole cover (never cropped or stretched) over a blurred copy of itself.
     * The game's name isn't shown: the cover already says which game it is.
     */
    private void refreshHeader()
    {
        if (mHeaderImage == null) return;

        // The game card (beta) has its own cover and tabs: it uses the whole screen
        View card = visibleGameCard();
        int bandVisibility = card != null ? View.GONE : View.VISIBLE;
        if (mHeaderBand != null && mHeaderBand.getVisibility() != bandVisibility) {
            mHeaderBand.setVisibility(bandVisibility);
        }
        if (card != null) return;
        GameSidebar sidebar = visibleSidebar();
        Drawable source = sidebar != null ? sidebar.getHeaderImage() : null;
        boolean isLogo = sidebar == null || sidebar.isHeaderImageLogo() || source == null;
        if (source == mHeaderSource && isLogo == mHeaderSourceIsLogo) return;
        mHeaderSource = source;
        mHeaderSourceIsLogo = isLogo;

        if (isLogo) {
            showLogo();
        } else {
            Drawable copy = source.getConstantState() != null ?
                    source.getConstantState().newDrawable(getResources()) : source;
            mHeaderImage.setImageDrawable(copy);
            mHeaderImage.setCrop(false, 0.5f);
            setBackdrop(source);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The borrowed menu
    // ---------------------------------------------------------------------------------------------

    /** Put the host's menu (the drawer contents) into this window. Safe to call repeatedly. */
    void attachMenu(View menuView)
    {
        if (mMenuContainer == null || menuView == null) return;
        if (menuView.getParent() == mMenuContainer) return;
        if (menuView.getParent() instanceof ViewGroup) {
            ((ViewGroup) menuView.getParent()).removeView(menuView);
        }
        mMenuView = menuView;

        // Lay it out at this screen's own density (the views were made for the main screen's)
        float menuDensity = menuView.getResources().getDisplayMetrics().density;
        float scale = getResources().getDisplayMetrics().density / menuDensity;
        mMenuContainer.setScale(Math.max(0.5f, Math.min(3f, scale)));

        styleForThisScreen(menuView, true);
        mMenuContainer.addView(menuView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        menuView.getViewTreeObserver().addOnGlobalLayoutListener(mLayoutListener);
        mHeaderSource = null;
        refreshHeader();
    }

    /** Hand the menu back; this screen then shows just its background. */
    void detachMenu()
    {
        if (mMenuView != null) {
            mMenuView.getViewTreeObserver().removeOnGlobalLayoutListener(mLayoutListener);
            if (mMenuView.getParent() instanceof ViewGroup) {
                ((ViewGroup) mMenuView.getParent()).removeView(mMenuView);
            }
            styleForThisScreen(mMenuView, false);
        }
        mMenuView = null;
        if (mHeaderImage != null) {
            mHeaderSource = null;
            showLogo();
            if (mHeaderBand != null) mHeaderBand.setVisibility(View.VISIBLE);
        }
    }

    /**
     * See-through menu (so the blurred shell shows behind it) with the header moved out of the
     * list into the fixed band; or, when the menu goes back to the main screen, its usual look.
     */
    private void styleForThisScreen(View menuView, boolean here)
    {
        styleView(menuView, here);
        for (int id : new int[] {R.id.drawerNavigation, R.id.gameSidebar}) {
            View v = menuView.findViewById(id);
            if (!(v instanceof GameSidebar)) continue;
            GameSidebar sidebar = (GameSidebar) v;
            styleView(sidebar, here);
            sidebar.setCacheColorHint(here ? Color.TRANSPARENT : Color.BLACK);
            sidebar.setHeaderShown(!here);
            sidebar.setOnHeaderChangedListener(here ? () -> {
                mHeaderSource = null;
                refreshHeader();
            } : null);
        }
    }

    private void styleView(View v, boolean here)
    {
        int key = System.identityHashCode(v);
        if (here) {
            if (mSavedBackgrounds.indexOfKey(key) < 0) mSavedBackgrounds.put(key, v.getBackground());
            v.setBackgroundColor(Color.TRANSPARENT);
        } else if (mSavedBackgrounds.indexOfKey(key) >= 0) {
            v.setBackground(mSavedBackgrounds.get(key));
            mSavedBackgrounds.remove(key);
        }
    }

    void setMenuOpen(boolean open)
    {
        mMenuOpen = open;
        if (mBackChip != null) {
            boolean inGame = mHost != null && mHost.isInGame();
            mBackChip.setVisibility(open && !inGame ? View.VISIBLE : View.GONE);
        }
        if (open && mHost != null && !mHost.isInGame()) focusMenuForController();
        refreshHeader();
    }

    void showControllerHint(boolean inGame, boolean controllerOnMenu)
    {
        if (mHint == null) return;
        if (!inGame) {
            mHint.setVisibility(View.GONE);
            return;
        }
        mHint.setVisibility(View.VISIBLE);
        mHint.setText(controllerOnMenu ? R.string.secondScreenMenu_hintOnMenu
                : R.string.secondScreenMenu_hintOnGame);
        mHint.setBackgroundColor(controllerOnMenu ? 0xCC1E4A7A : 0x99000000);
    }

    @Nullable
    private View visibleGameCard()
    {
        if (mMenuView == null) return null;
        View card = mMenuView.findViewById(R.id.gameCardPanel);
        return card != null && card.getVisibility() == View.VISIBLE ? card : null;
    }

    /** Give the menu list controller focus, with a visible highlight even after touches. */
    void focusMenuForController()
    {
        if (mMenuView == null) return;
        View card = visibleGameCard();
        if (card instanceof paulscode.android.mupen64plusae.GameCardPanel) {
            ((paulscode.android.mupen64plusae.GameCardPanel) card).focusDefault();
            return;
        }
        View target = mMenuView.findViewById(R.id.gameSidebar);
        if (target == null || target.getVisibility() != View.VISIBLE) {
            target = mMenuView.findViewById(R.id.drawerNavigation);
        }
        if (target == null || target.getVisibility() != View.VISIBLE) target = mMenuView;
        // requestFocusFromTouch leaves touch mode, so the selected item is highlighted
        target.requestFocusFromTouch();
    }

    void finishFromHost()
    {
        mFinishingByHost = true;
        detachMenu();
        mHost = null;
        finish();
    }

    boolean isShownToUser()
    {
        return mShown;
    }

    /** True between onStart and onStop: this screen is in front on its display. */
    boolean isStartedState()
    {
        return mStarted;
    }

    /** A key from the main screen that belongs to this menu. */
    boolean dispatchKeyToMenu(KeyEvent event)
    {
        if (event.getAction() == KeyEvent.ACTION_DOWN && mMenuView != null) {
            View focus = getCurrentFocus();
            if (focus == null || focus.isInTouchMode()) focusMenuForController();
        }
        return super.dispatchKeyEvent(event);
    }

    boolean dispatchMotionToMenu(MotionEvent event)
    {
        return super.dispatchGenericMotionEvent(event);
    }

    /** Keys arriving at this window directly (it has input focus after being touched). */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event)
    {
        if (mHost != null && mHost.onKeyFromMenuScreen(event)) return true;
        // Back is never allowed to reach Android's default handling (which would close this)
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            dispatchKeyToMenu(event);
            return true;
        }
        return dispatchKeyToMenu(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event)
    {
        if (mHost != null && mHost.onMotionFromMenuScreen(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed()
    {
        // Never close this screen with Back; Back is routed by the host
        if (mHost != null) mHost.onBackFromMenuScreen();
    }

    @Override
    protected void onNewIntent(Intent intent)
    {
        // Brought back to the front by the host; nothing else to do
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onStart()
    {
        super.onStart();
        mStarted = true;
    }

    @Override
    protected void onStop()
    {
        mStarted = false;
        super.onStop();
        if (mHost != null && !isFinishing()) mHost.onMenuScreenStopped(this);
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        mShown = true;
        if (mHost != null) mHost.onMenuScreenShown(this);
    }

    @Override
    protected void onPause()
    {
        mShown = false;
        super.onPause();
    }

    @Override
    protected void onDestroy()
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && mBackCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (android.window.OnBackInvokedCallback) mBackCallback);
        }
        detachMenu();
        DualScreenDrawerLayout host = mHost;
        mHost = null;
        if (host != null) {
            host.onMenuScreenGone(this, mFinishingByHost || isChangingConfigurations());
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------------------------------------
    // Helper views
    // ---------------------------------------------------------------------------------------------

    /**
     * Lays its child out as if the screen had the child's own density, then scales it to fit,
     * so views made for the main screen come out at the right size here.
     */
    static class ScaledFrame extends FrameLayout
    {
        private float mScale = 1f;

        ScaledFrame(Context context)
        {
            super(context);
        }

        void setScale(float scale)
        {
            if (scale == mScale) return;
            mScale = scale;
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)
        {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getSize(heightMeasureSpec);
            int childWidth = Math.round(width / mScale);
            int childHeight = Math.round(height / mScale);
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom)
        {
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.layout(0, 0, child.getMeasuredWidth(), child.getMeasuredHeight());
                child.setPivotX(0);
                child.setPivotY(0);
                child.setScaleX(mScale);
                child.setScaleY(mScale);
            }
        }

        @Override
        public void onViewRemoved(View child)
        {
            super.onViewRemoved(child);
            // The menu goes back to the main screen at its normal size
            child.setScaleX(1f);
            child.setScaleY(1f);
        }
    }

    /**
     * A picture that either fills its whole area without stretching (cropping the overflow, and
     * keeping the part at the given height in view), or fits entirely inside it, centered.
     */
    static class CropImageView extends ImageView
    {
        private boolean mCrop = true;
        private float mFocusY = 0.5f;

        CropImageView(Context context)
        {
            super(context);
            setScaleType(ScaleType.MATRIX);
        }

        void setCrop(boolean crop, float focusY)
        {
            mCrop = crop;
            mFocusY = focusY;
            updateMatrix();
        }

        @Override
        public void setImageDrawable(@Nullable Drawable drawable)
        {
            super.setImageDrawable(drawable);
            updateMatrix();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh)
        {
            super.onSizeChanged(w, h, oldw, oldh);
            updateMatrix();
        }

        private void updateMatrix()
        {
            Drawable d = getDrawable();
            int vw = getWidth() - getPaddingLeft() - getPaddingRight();
            int vh = getHeight() - getPaddingTop() - getPaddingBottom();
            if (d == null || vw <= 0 || vh <= 0) return;
            int dw = d.getIntrinsicWidth();
            int dh = d.getIntrinsicHeight();
            if (dw <= 0 || dh <= 0) return;

            float scale = mCrop ? Math.max(vw / (float) dw, vh / (float) dh)
                    : Math.min(vw / (float) dw, vh / (float) dh);
            float dx = (vw - dw * scale) / 2f;
            float dy = mCrop ? (vh - dh * scale) * mFocusY : (vh - dh * scale) / 2f;

            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate(Math.round(dx), Math.round(dy));
            setImageMatrix(m);
        }
    }
}
