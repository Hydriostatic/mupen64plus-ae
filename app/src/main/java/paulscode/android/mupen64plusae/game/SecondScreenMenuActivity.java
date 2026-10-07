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
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.widget.ImageView;

import paulscode.android.mupen64plusae.GameSidebar;

import paulscode.android.mupen64plusae.R;

/**
 * Shows a menu on the secondary display (e.g. the bottom screen of the AYN Thor).
 *
 * The menu views themselves belong to the activity on the main screen (same process); this
 * activity only provides a window on the second display to show them in. When there is no menu
 * to show it stays up as a plain grey screen, so the second screen never goes blank.
 */
public class SecondScreenMenuActivity extends Activity
{
    private static final String TAG = "SecondScreenMenu";
    static final String EXTRA_DISPLAY_ID = "displayId";

    /** Background of the second screen, also shown when there's no menu on it. */
    static final int BACKGROUND_GREY = 0xFF303030;

    private static WeakReference<DualScreenDrawerLayout> sHost = new WeakReference<>(null);

    static void setHost(DualScreenDrawerLayout host)
    {
        sHost = new WeakReference<>(host);
    }

    /** The live second-screen menu of this process, used to open pages on the second screen. */
    private static WeakReference<SecondScreenMenuActivity> sCurrent = new WeakReference<>(null);

    static SecondScreenMenuActivity current()
    {
        SecondScreenMenuActivity a = sCurrent.get();
        return a != null && !a.isFinishing() && !a.isDestroyed() ? a : null;
    }

    private DualScreenDrawerLayout mHost;
    /** Backgrounds replaced to make the menus see-through, restored when the menu goes back. */
    private final Map<View, Drawable> mSavedBackgrounds = new HashMap<>();
    private View mMenuView;
    private View mTopBar;
    private TextView mHint;
    private FrameLayout mMenuContainer;
    private FrameLayout mInfoContainer;
    private View mInfoPanel;
    private boolean mMenuOpen = false;
    private boolean mFinishingByHost = false;
    private boolean mShown = false;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(BACKGROUND_GREY));

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
        sCurrent = new WeakReference<>(this);
        registerBackCallback();
        buildLayout();
        if (DualScreenDrawerLayout.DIAGNOSTICS) {
            android.widget.Toast.makeText(this, "2nd screen: menu opened here (screen " + actualDisplay + ")",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
        mHost.onMenuScreenReady(this);
    }

    private void buildLayout()
    {
        final Context ctx = this;
        final float dp = getResources().getDisplayMetrics().density;
        final int pad = Math.round(12 * dp);
        final boolean inGame = mHost.isInGame();

        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(BACKGROUND_GREY);

        if (!inGame) {
            // App menus: the bright cartridge-era theme (cream, confetti, colour bands)
            root.setBackground(new paulscode.android.mupen64plusae.N64Theme.BackgroundDrawable(dp));
        }

        // In-game: the app icon behind everything, blurred and half transparent
        ImageView logo = new ImageView(ctx);
        logo.setImageResource(R.mipmap.ic_launcher_foreground);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setAlpha(0.5f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            float radius = 10 * dp;
            logo.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL));
        }
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int logoSize = Math.round(Math.min(screenWidth, screenHeight) * 1.1f);
        if (inGame) root.addView(logo, new FrameLayout.LayoutParams(logoSize, logoSize, Gravity.CENTER));

        // The menus use the whole (small) screen
        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Game list only: a Back bar while a game's options (or the opened menu) have the buttons
        LinearLayout topBar = new LinearLayout(ctx);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(pad, pad / 4, pad / 2, pad / 4);
        topBar.setBackgroundColor(0xB0181818);

        TextView title = new TextView(ctx);
        title.setText(R.string.app_name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        topBar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button back = new Button(ctx);
        back.setText(R.string.secondScreenMenu_back);
        back.setFocusable(false); // keep controller focus on the menu list
        back.setOnClickListener(v -> {
            if (mHost != null) mHost.closeDrawer(Gravity.START);
        });
        topBar.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mTopBar = topBar;
        mTopBar.setVisibility(View.GONE);
        column.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mMenuContainer = new FrameLayout(ctx);
        column.addView(mMenuContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // In-game live info (from an expansion), shown instead of the menu while playing
        mInfoContainer = new FrameLayout(ctx);
        mInfoContainer.setVisibility(View.GONE);
        column.addView(mInfoContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // In-game only: which screen the controller buttons drive right now
        TextView hint = new TextView(ctx);
        hint.setTextColor(0xFFDDDDDD);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(pad, pad / 3, pad, pad / 3);
        hint.setBackgroundColor(0xB0181818);
        hint.setVisibility(inGame ? View.VISIBLE : View.GONE);
        hint.setClickable(true);
        hint.setOnClickListener(v -> {
            if (mHost != null) mHost.toggleControllerTarget();
        });
        mHint = hint;
        column.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        showControllerHint(inGame, false);
    }

    /** Put the host's menu (the drawer contents) into this window. Safe to call repeatedly. */
    void attachMenu(View menuView)
    {
        if (mMenuContainer == null || menuView == null) return;
        styleForSecondScreen(menuView);
        if (menuView.getParent() == mMenuContainer) return;
        if (menuView.getParent() instanceof ViewGroup) {
            ((ViewGroup) menuView.getParent()).removeView(menuView);
        }
        mMenuView = menuView;
        mMenuContainer.addView(menuView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Hand the menu back; this screen then shows plain grey. */
    void detachMenu()
    {
        if (mMenuView != null) restoreStyle(mMenuView);
        if (mMenuView != null && mMenuView.getParent() instanceof ViewGroup) {
            ((ViewGroup) mMenuView.getParent()).removeView(mMenuView);
        }
        mMenuView = null;
    }

    /** Make the menus see-through (so the app icon shows behind them) and compact. */
    private void styleForSecondScreen(View view)
    {
        if (view == mMenuView || mMenuView == null) {
            makeSeeThrough(view, null);
        }
        compact(view, true);
    }

    private void makeSeeThrough(View view, Drawable replacement)
    {
        if (!mSavedBackgrounds.containsKey(view)) mSavedBackgrounds.put(view, view.getBackground());
        view.setBackground(replacement);
        if (view instanceof ViewGroup && !(view instanceof GameSidebar)) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c instanceof GameSidebar) makeSeeThrough(c, new ColorDrawable(0x40000000));
            }
        }
    }

    private void restoreStyle(View view)
    {
        for (Map.Entry<View, Drawable> e : mSavedBackgrounds.entrySet()) {
            e.getKey().setBackground(e.getValue());
        }
        mSavedBackgrounds.clear();
        compact(view, false);
    }

    private static void compact(View view, boolean compact)
    {
        if (view instanceof GameSidebar) {
            ((GameSidebar) view).setCompact(compact);
        } else if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) compact(g.getChildAt(i), compact);
        }
    }

    private void detachInfoPanel()
    {
        if (mInfoPanel != null && mInfoPanel.getParent() instanceof ViewGroup) {
            ((ViewGroup) mInfoPanel.getParent()).removeView(mInfoPanel);
        }
        mInfoPanel = null;
    }

    /** Put (or, with null, remove) the host's live info panel. */
    void attachInfoPanel(View panel)
    {
        if (mInfoContainer == null) return;
        if (panel == mInfoPanel && (panel == null || panel.getParent() == mInfoContainer)) return;
        if (mInfoPanel != null && mInfoPanel.getParent() instanceof ViewGroup) {
            ((ViewGroup) mInfoPanel.getParent()).removeView(mInfoPanel);
        }
        mInfoPanel = panel;
        if (panel != null) {
            if (panel.getParent() instanceof ViewGroup) {
                ((ViewGroup) panel.getParent()).removeView(panel);
            }
            mInfoContainer.addView(panel, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            showInfoPanel(false);
        }
    }

    /** Show the info panel instead of the menu (only if there is one). */
    void showInfoPanel(boolean show)
    {
        if (mInfoContainer == null) return;
        boolean info = show && mInfoPanel != null;
        mInfoContainer.setVisibility(info ? View.VISIBLE : View.GONE);
        mMenuContainer.setVisibility(info ? View.GONE : View.VISIBLE);
    }

    void setMenuOpen(boolean open)
    {
        mMenuOpen = open;
        if (mTopBar != null) {
            boolean inGame = mHost != null && mHost.isInGame();
            mTopBar.setVisibility(open && !inGame ? View.VISIBLE : View.GONE);
        }
        if (open && mHost != null && !mHost.isInGame()) focusMenuForController();
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
        mHint.setBackgroundColor(controllerOnMenu ? 0xE01E4A7A : 0xB0181818);
    }

    /** Give the menu list controller focus, with a visible highlight even after touches. */
    void focusMenuForController()
    {
        if (mMenuView == null) return;
        View target = mMenuView.findViewById(R.id.gameSidebar);
        if (target == null || target.getVisibility() != View.VISIBLE) {
            target = mMenuView.findViewById(R.id.drawerNavigation);
        }
        if (target == null || target.getVisibility() != View.VISIBLE) {
            target = mMenuView.findViewById(R.id.galleryHomePanel);
        }
        if (target == null || target.getVisibility() != View.VISIBLE) target = mMenuView;
        // requestFocusFromTouch leaves touch mode, so the selected item is highlighted
        target.requestFocusFromTouch();
    }

    void finishFromHost()
    {
        mFinishingByHost = true;
        detachMenu();
        detachInfoPanel();
        mHost = null;
        // Also closes any settings page opened on top of it on this screen
        finishAndRemoveTask();
    }

    boolean isShownToUser()
    {
        return mShown;
    }

    private final ControllerNav mNav = new ControllerNav();

    /** A key that belongs to this menu (from either screen). */
    boolean dispatchKeyToMenu(KeyEvent event)
    {
        if (event.getAction() == KeyEvent.ACTION_DOWN && mMenuView != null) {
            View focus = getCurrentFocus();
            if (focus == null || focus.isInTouchMode()) focusMenuForController();
        }
        boolean handled = super.dispatchKeyEvent(event);
        // Move the selection / select / back like Android does in a focused window
        if (!handled) handled = mNav.onKey(this, event);
        return handled;
    }

    boolean dispatchMotionToMenu(MotionEvent event)
    {
        boolean handled = super.dispatchGenericMotionEvent(event);
        if (!handled) handled = mNav.onMotion(this, event);
        return handled;
    }

    /** Keys arriving at this window directly (it has input focus after being touched). */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event)
    {
        if (mHost != null && mHost.onKeyFromMenuScreen(event)) return true;
        return dispatchKeyToMenu(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event)
    {
        if (mHost != null && mHost.onMotionFromMenuScreen(event)) return true;
        return dispatchMotionToMenu(event);
    }

    /**
     * Back must never close this screen or send it to the background (that would leave the main
     * screen alone). It is routed to the host like a Back key press instead.
     */
    private void routeBack()
    {
        dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed()
    {
        routeBack();
    }

    private Object mBackCallback;

    private void registerBackCallback()
    {
        if (Build.VERSION.SDK_INT >= 33) {
            android.window.OnBackInvokedCallback cb = this::routeBack;
            mBackCallback = cb;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
        }
    }

    // Home pressed while this screen had the focus: the main screen goes home with it
    private long mOwnLaunchUntil = 0;

    @SuppressWarnings("deprecation")
    @Override
    public void startActivityForResult(android.content.Intent intent, int requestCode, Bundle options)
    {
        mOwnLaunchUntil = android.os.SystemClock.uptimeMillis() + 1500;
        super.startActivityForResult(intent, requestCode, options);
    }

    @Override
    protected void onUserLeaveHint()
    {
        super.onUserLeaveHint();
        if (android.os.SystemClock.uptimeMillis() < mOwnLaunchUntil) return;
        if (mHost != null) mHost.onUserLeftAppFromSecondScreen();
    }

    @Override
    protected void onStop()
    {
        super.onStop();
        // Something covered this screen. If the main screen is still showing the app, bring the
        // menu back (it must never be left alone)
        if (!isFinishing() && mHost != null) mHost.onMenuScreenStopped(this);
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
        if (sCurrent.get() == this) sCurrent = new WeakReference<>(null);
        if (Build.VERSION.SDK_INT >= 33 && mBackCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (android.window.OnBackInvokedCallback) mBackCallback);
        }
        detachMenu();
        detachInfoPanel();
        DualScreenDrawerLayout host = mHost;
        mHost = null;
        if (host != null) {
            host.onMenuScreenGone(this, mFinishingByHost || isChangingConfigurations());
        }
        super.onDestroy();
    }
}
