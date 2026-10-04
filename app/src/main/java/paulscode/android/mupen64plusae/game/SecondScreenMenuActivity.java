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

import paulscode.android.mupen64plusae.GameSidebar;
import paulscode.android.mupen64plusae.R;

/**
 * Hosts the in-game menu on a secondary display (e.g. the bottom screen of the AYN Thor).
 *
 * The menu views themselves belong to GameActivity (same process); this activity only provides a
 * window on the second display to show them in. While the game runs the menu is dimmed; tapping
 * it pauses the game and activates the menu. "Resume game" (or Back / Menu) closes it.
 */
public class SecondScreenMenuActivity extends Activity
{
    private static final String TAG = "SecondScreenMenu";
    static final String EXTRA_DISPLAY_ID = "displayId";

    private static WeakReference<DualScreenDrawerLayout> sHost = new WeakReference<>(null);

    static void setHost(DualScreenDrawerLayout host)
    {
        sHost = new WeakReference<>(host);
    }

    private DualScreenDrawerLayout mHost;
    private View mMenuView;
    private View mTopBar;
    private View mDimOverlay;
    private FrameLayout mMenuContainer;
    private boolean mMenuOpen = false;
    private boolean mFinishingByHost = false;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        mHost = sHost.get();
        if (mHost == null) {
            // Game is gone (e.g. restored after process death); nothing to show
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

        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Top bar: "Paused" + "Resume game", only visible while the menu is active
        LinearLayout topBar = new LinearLayout(ctx);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(pad, pad / 2, pad, pad / 2);
        topBar.setBackgroundColor(0xFF202020);

        final boolean alwaysActive = mHost.isAlwaysActive();

        TextView paused = new TextView(ctx);
        paused.setText(alwaysActive ? R.string.app_name : R.string.secondScreenMenu_paused);
        paused.setTextColor(Color.WHITE);
        paused.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        topBar.addView(paused, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button resume = new Button(ctx);
        resume.setText(alwaysActive ? R.string.secondScreenMenu_back : R.string.secondScreenMenu_resume);
        resume.setFocusable(false); // keep controller focus on the menu list
        resume.setOnClickListener(v -> {
            if (mHost != null) mHost.closeDrawer(Gravity.START);
        });
        topBar.addView(resume, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mTopBar = topBar;
        column.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mMenuContainer = new FrameLayout(ctx);
        column.addView(mMenuContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Dim layer shown while the game runs; tap it to pause and open the menu
        TextView dim = new TextView(ctx);
        dim.setText(R.string.secondScreenMenu_tapToOpen);
        dim.setTextColor(Color.WHITE);
        dim.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        dim.setGravity(Gravity.CENTER);
        dim.setPadding(pad, pad, pad, pad);
        dim.setBackgroundColor(0xB0000000);
        dim.setClickable(true);
        dim.setOnClickListener(v -> {
            if (mHost != null) {
                mHost.openDrawer(Gravity.START);
                focusMenu();
            }
        });
        mDimOverlay = dim;
        root.addView(dim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        applyMenuState();
    }

    /** Called by the host to put the GameSidebar (with its container) into this window. */
    void attachMenu(View menuView)
    {
        if (mMenuContainer == null || menuView == null) return;
        if (menuView.getParent() instanceof ViewGroup) {
            ((ViewGroup) menuView.getParent()).removeView(menuView);
        }
        mMenuView = menuView;
        mMenuContainer.addView(menuView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Hand the menu views back so they can go to the side drawer or a new window. */
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
        applyMenuState();
        if (open) focusMenu();
    }

    void finishFromHost()
    {
        mFinishingByHost = true;
        detachMenu();
        mHost = null;
        finish();
    }

    private void applyMenuState()
    {
        if (mTopBar == null) return;
        // App menus are always usable; the in-game menu is dimmed until opened (game paused)
        boolean alwaysActive = mHost != null && mHost.isAlwaysActive();
        mTopBar.setVisibility(mMenuOpen ? View.VISIBLE : View.GONE);
        mDimOverlay.setVisibility(mMenuOpen || alwaysActive ? View.GONE : View.VISIBLE);
    }

    private void focusMenu()
    {
        if (mMenuView == null) return;
        View sidebar = mMenuView.findViewById(R.id.gameSidebar);
        if (sidebar instanceof GameSidebar) sidebar.requestFocus();
        else mMenuView.requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event)
    {
        if (mHost == null) return super.dispatchKeyEvent(event);

        final int keyCode = event.getKeyCode();
        // Back/Menu are handled by the game (it opens/closes the menu), and while the game is
        // running every key belongs to the game, even if this screen has input focus.
        if (!mHost.isMenuOpen() || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) {
            return mHost.forwardKeyToGame(event);
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event)
    {
        if (mHost != null && !mHost.isMenuOpen()) {
            return mHost.forwardMotionToGame(event);
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed()
    {
        // Never close this screen with Back; Back is forwarded to the game in dispatchKeyEvent
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
