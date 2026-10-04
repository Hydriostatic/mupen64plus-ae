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

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import paulscode.android.mupen64plusae.GameSidebar;
import paulscode.android.mupen64plusae.R;

/**
 * The in-game menu shown on a secondary display (e.g. the bottom screen of the AYN Thor).
 *
 * While the game is running the menu is visible but dimmed; tapping it pauses the game and makes
 * the menu active, exactly like opening the side drawer. "Resume game" (or Back / Menu) closes it.
 */
class SecondScreenMenu extends Presentation
{
    private final DualScreenDrawerLayout mHost;
    private final View mMenuView;

    private View mTopBar;
    private View mDimOverlay;
    private FrameLayout mMenuContainer;
    private boolean mMenuOpen = false;

    SecondScreenMenu(Context outerContext, android.view.Display display,
                     DualScreenDrawerLayout host, View menuView)
    {
        super(outerContext, display);
        mHost = host;
        mMenuView = menuView;
        setCancelable(false);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        final Context ctx = getContext();
        final float dp = ctx.getResources().getDisplayMetrics().density;
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

        TextView paused = new TextView(ctx);
        paused.setText(R.string.secondScreenMenu_paused);
        paused.setTextColor(Color.WHITE);
        paused.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        topBar.addView(paused, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button resume = new Button(ctx);
        resume.setText(R.string.secondScreenMenu_resume);
        resume.setFocusable(false); // keep controller focus on the menu list
        resume.setOnClickListener(v -> mHost.closeDrawer(Gravity.START));
        topBar.addView(resume, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mTopBar = topBar;
        column.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The real GameSidebar, moved over from the game window
        mMenuContainer = new FrameLayout(ctx);
        if (mMenuView.getParent() instanceof ViewGroup) {
            ((ViewGroup) mMenuView.getParent()).removeView(mMenuView);
        }
        mMenuContainer.addView(mMenuView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
            mHost.openDrawer(Gravity.START);
            focusMenu();
        });
        mDimOverlay = dim;
        root.addView(dim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        applyMenuState();
    }

    void setMenuOpen(boolean open)
    {
        mMenuOpen = open;
        applyMenuState();
        if (open) focusMenu();
    }

    private void applyMenuState()
    {
        if (mTopBar == null) return; // not created yet; onCreate will apply it
        mTopBar.setVisibility(mMenuOpen ? View.VISIBLE : View.GONE);
        mDimOverlay.setVisibility(mMenuOpen ? View.GONE : View.VISIBLE);
    }

    private void focusMenu()
    {
        if (mMenuView instanceof ViewGroup) {
            View sidebar = mMenuView.findViewById(R.id.gameSidebar);
            if (sidebar instanceof GameSidebar) {
                sidebar.requestFocus();
                return;
            }
        }
        mMenuView.requestFocus();
    }

    /** Give the menu view back so it can return to the side drawer. */
    void detachMenu()
    {
        if (mMenuContainer != null) {
            mMenuContainer.removeView(mMenuView);
        } else if (mMenuView.getParent() instanceof ViewGroup) {
            ((ViewGroup) mMenuView.getParent()).removeView(mMenuView);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event)
    {
        final int keyCode = event.getKeyCode();

        // Menu/Back are handled by the game activity (it opens/closes the menu), and while the
        // game is running every key belongs to the game, even if this screen has input focus.
        if (!mHost.isMenuOpen() || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) {
            return mHost.forwardKeyToGame(event);
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event)
    {
        if (!mHost.isMenuOpen()) {
            return mHost.forwardMotionToGame(event);
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public void onDisplayRemoved()
    {
        // DualScreenDrawerLayout's dismiss listener moves the menu back to the side drawer
        super.onDisplayRemoved();
    }
}
