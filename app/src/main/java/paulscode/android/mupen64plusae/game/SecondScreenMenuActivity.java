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

    /** The menu is a centered column at most this wide. */
    private static final int MENU_MAX_WIDTH_DP = 480;

    private static WeakReference<DualScreenDrawerLayout> sHost = new WeakReference<>(null);

    static void setHost(DualScreenDrawerLayout host)
    {
        sHost = new WeakReference<>(host);
    }

    private DualScreenDrawerLayout mHost;
    private View mMenuView;
    private View mTopBar;
    private TextView mHint;
    private FrameLayout mMenuContainer;
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

        // Centered column: as wide as the screen, up to MENU_MAX_WIDTH_DP
        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int columnWidth = Math.min(screenWidth, Math.round(MENU_MAX_WIDTH_DP * dp));
        FrameLayout.LayoutParams columnLp = new FrameLayout.LayoutParams(
                columnWidth, ViewGroup.LayoutParams.MATCH_PARENT);
        columnLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(column, columnLp);

        // Game list only: a Back bar while a game's options (or the opened menu) have the buttons
        LinearLayout topBar = new LinearLayout(ctx);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(pad, pad / 2, pad, pad / 2);
        topBar.setBackgroundColor(0xFF202020);

        TextView title = new TextView(ctx);
        title.setText(R.string.app_name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
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

        // In-game only: which screen the controller buttons drive right now
        TextView hint = new TextView(ctx);
        hint.setTextColor(0xFFDDDDDD);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(pad, pad / 2, pad, pad / 2);
        hint.setBackgroundColor(0xFF202020);
        hint.setVisibility(inGame ? View.VISIBLE : View.GONE);
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
        if (mMenuView != null && mMenuView.getParent() instanceof ViewGroup) {
            ((ViewGroup) mMenuView.getParent()).removeView(mMenuView);
        }
        mMenuView = null;
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
        mHint.setBackgroundColor(controllerOnMenu ? 0xFF1E4A7A : 0xFF202020);
    }

    /** Give the menu list controller focus, with a visible highlight even after touches. */
    void focusMenuForController()
    {
        if (mMenuView == null) return;
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
        // Never close this screen with Back; Back is routed by the host in dispatchKeyEvent
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
        detachMenu();
        DualScreenDrawerLayout host = mHost;
        mHost = null;
        if (host != null) {
            host.onMenuScreenGone(this, mFinishingByHost || isChangingConfigurations());
        }
        super.onDestroy();
    }
}
