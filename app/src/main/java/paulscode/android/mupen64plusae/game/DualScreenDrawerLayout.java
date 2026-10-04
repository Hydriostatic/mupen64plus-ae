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
import android.content.ContextWrapper;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.drawerlayout.widget.DrawerLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * A DrawerLayout that, on dual-screen devices (e.g. the AYN Thor), moves the in-game menu
 * drawer onto the secondary display instead of sliding it in from the left.
 *
 * All the usual DrawerLayout calls made by GameActivity (openDrawer, closeDrawer, isDrawerOpen
 * and the DrawerListener callbacks) keep the same meaning, so the rest of the activity does not
 * need to know which mode is in use: "drawer open" still means "menu active, emulator paused".
 *
 * If no second display is present, or it disappears while playing, the classic side drawer is
 * used.
 */
public class DualScreenDrawerLayout extends DrawerLayout
{
    private static final String TAG = "DualScreenDrawer";

    private final List<DrawerListener> mListeners = new ArrayList<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /** The drawer child from the XML layout (holds the GameSidebar). */
    private View mDrawerView;
    private ViewGroup.LayoutParams mDrawerLayoutParams;

    private SecondScreenMenu mPresentation;
    private boolean mSecondScreenWanted = false;
    private boolean mMenuOpen = false;
    private boolean mIntentionalDismiss = false;
    private boolean mBypassForwarding = false;
    private DisplayManager mDisplayManager;

    private final DisplayManager.DisplayListener mDisplayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
            // Only move the menu over while it is closed so we don't yank it from under the user
            if (mPresentation == null && !isDrawerOpen(Gravity.START)) {
                enterSecondScreenMode();
            }
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            if (mPresentation != null && mPresentation.getDisplay().getDisplayId() == displayId) {
                leaveSecondScreenMode();
            }
        }

        @Override
        public void onDisplayChanged(int displayId) {
        }
    };

    public DualScreenDrawerLayout(@NonNull Context context) {
        super(context);
    }

    public DualScreenDrawerLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public DualScreenDrawerLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    /**
     * Enable or disable showing the menu on the second screen. Call this from the activity's
     * onCreate after setContentView and before the first openDrawer call.
     */
    public void setSecondScreenEnabled(boolean enabled)
    {
        mSecondScreenWanted = enabled;

        if (mDisplayManager == null) {
            mDisplayManager = (DisplayManager) getContext().getSystemService(Context.DISPLAY_SERVICE);
        }

        if (enabled) {
            mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
            enterSecondScreenMode();
        } else {
            mDisplayManager.unregisterDisplayListener(mDisplayListener);
            leaveSecondScreenMode();
        }
    }

    /** True while the menu lives on the second screen. */
    public boolean isUsingSecondScreen()
    {
        return mPresentation != null;
    }

    // ---------------------------------------------------------------------------------------------
    // Mode switching
    // ---------------------------------------------------------------------------------------------

    private void enterSecondScreenMode()
    {
        if (!mSecondScreenWanted || mPresentation != null) return;

        Activity activity = getActivity();
        Display target = findSecondaryDisplay(activity);
        if (activity == null || target == null) {
            Log.i(TAG, "No secondary display found, using the side drawer");
            return;
        }

        // Find the drawer child (the one with a horizontal gravity)
        if (mDrawerView == null) {
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                ViewGroup.LayoutParams lp = child.getLayoutParams();
                if (lp instanceof DrawerLayout.LayoutParams &&
                        (((DrawerLayout.LayoutParams) lp).gravity & Gravity.HORIZONTAL_GRAVITY_MASK) != 0) {
                    mDrawerView = child;
                    mDrawerLayoutParams = lp;
                    break;
                }
            }
        }
        if (mDrawerView == null) return;

        boolean wasOpen = super.isDrawerOpen(Gravity.START);
        if (wasOpen) {
            super.closeDrawer(Gravity.START, false);
        }

        removeView(mDrawerView);

        Log.i(TAG, "Showing in-game menu on display " + target.getDisplayId() + " (" + target.getName() + ")");
        mPresentation = new SecondScreenMenu(activity, target, this, mDrawerView);
        mPresentation.setOnDismissListener(dialog -> {
            if (!mIntentionalDismiss) {
                // Display went away or the system dismissed us: fall back to the side drawer
                mHandler.post(this::leaveSecondScreenMode);
            }
        });
        mMenuOpen = wasOpen;
        mPresentation.setMenuOpen(mMenuOpen);

        if (isAttachedToWindow() && getWindowVisibility() == View.VISIBLE) {
            showPresentation();
        }
    }

    private void leaveSecondScreenMode()
    {
        if (mPresentation == null) return;

        SecondScreenMenu presentation = mPresentation;
        mPresentation = null;

        mIntentionalDismiss = true;
        try {
            presentation.detachMenu();
            presentation.dismiss();
        } catch (Exception e) {
            Log.w(TAG, "Unable to dismiss second screen menu", e);
        } finally {
            mIntentionalDismiss = false;
        }

        if (mDrawerView != null && mDrawerView.getParent() == null) {
            addView(mDrawerView, mDrawerLayoutParams);
        }

        if (mMenuOpen) {
            mMenuOpen = false;
            // Re-open as a regular drawer; DrawerLayout will notify the listeners itself
            post(() -> super.openDrawer(Gravity.START));
        }
    }

    private void showPresentation()
    {
        if (mPresentation == null || mPresentation.isShowing()) return;
        try {
            mPresentation.show();
        } catch (Exception e) {
            // e.g. WindowManager.InvalidDisplayException
            Log.w(TAG, "Couldn't show menu on second screen, using side drawer", e);
            leaveSecondScreenMode();
        }
    }

    @Nullable
    private Display findSecondaryDisplay(@Nullable Activity activity)
    {
        if (mDisplayManager == null || activity == null) return null;

        @SuppressWarnings("deprecation")
        int ownDisplayId = activity.getWindowManager().getDefaultDisplay().getDisplayId();

        // Prefer displays that the system says are meant for presentations
        for (Display d : mDisplayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (d.getDisplayId() != ownDisplayId && d.isValid()) return d;
        }
        // Some devices expose their second built-in panel without that category
        for (Display d : mDisplayManager.getDisplays()) {
            if (d.getDisplayId() != ownDisplayId && d.isValid() &&
                    (d.getFlags() & Display.FLAG_PRIVATE) == 0) return d;
        }
        return null;
    }

    @Nullable
    private Activity getActivity()
    {
        Context context = getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // Window lifecycle
    // ---------------------------------------------------------------------------------------------

    @Override
    protected void onAttachedToWindow()
    {
        super.onAttachedToWindow();
        if (getWindowVisibility() == View.VISIBLE) {
            showPresentation();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility)
    {
        super.onWindowVisibilityChanged(visibility);
        if (mPresentation == null) return;

        if (visibility == View.VISIBLE) {
            showPresentation();
        } else if (mPresentation.isShowing()) {
            mPresentation.hide();
        }
    }

    @Override
    protected void onDetachedFromWindow()
    {
        if (mDisplayManager != null) {
            mDisplayManager.unregisterDisplayListener(mDisplayListener);
        }
        if (mPresentation != null) {
            mIntentionalDismiss = true;
            try {
                mPresentation.dismiss();
            } catch (Exception ignored) {
            } finally {
                mIntentionalDismiss = false;
            }
        }
        super.onDetachedFromWindow();
    }

    // ---------------------------------------------------------------------------------------------
    // DrawerLayout API used by GameActivity
    // ---------------------------------------------------------------------------------------------

    @Override
    public void addDrawerListener(@NonNull DrawerListener listener)
    {
        mListeners.add(listener);
        super.addDrawerListener(listener);
    }

    @Override
    public void removeDrawerListener(@NonNull DrawerListener listener)
    {
        mListeners.remove(listener);
        super.removeDrawerListener(listener);
    }

    @Override
    public void openDrawer(int gravity)
    {
        if (mPresentation == null) {
            super.openDrawer(gravity);
            return;
        }
        if (mMenuOpen) return;

        mMenuOpen = true;
        mPresentation.setMenuOpen(true);
        dispatchMenuState(true);
    }

    @Override
    public void openDrawer(int gravity, boolean animate)
    {
        if (mPresentation == null) super.openDrawer(gravity, animate);
        else openDrawer(gravity);
    }

    @Override
    public void closeDrawer(int gravity)
    {
        if (mPresentation == null) {
            super.closeDrawer(gravity);
            return;
        }
        if (!mMenuOpen) return;

        mMenuOpen = false;
        mPresentation.setMenuOpen(false);
        dispatchMenuState(false);
    }

    @Override
    public void closeDrawer(int gravity, boolean animate)
    {
        if (mPresentation == null) super.closeDrawer(gravity, animate);
        else closeDrawer(gravity);
    }

    @Override
    public void closeDrawers()
    {
        if (mPresentation == null) super.closeDrawers();
        else closeDrawer(Gravity.START);
    }

    @Override
    public boolean isDrawerOpen(int drawerGravity)
    {
        if (mPresentation == null) return super.isDrawerOpen(drawerGravity);
        return mMenuOpen;
    }

    @Override
    public boolean isDrawerVisible(int drawerGravity)
    {
        if (mPresentation == null) return super.isDrawerVisible(drawerGravity);
        return mMenuOpen;
    }

    /**
     * Listener callbacks are posted, like DrawerLayout does after its animation, so that a
     * listener added later in onCreate still receives the initial "opened" event.
     */
    private void dispatchMenuState(final boolean open)
    {
        mHandler.post(() -> {
            if (mPresentation == null || mMenuOpen != open) return;
            View drawer = mDrawerView != null ? mDrawerView : this;
            for (DrawerListener l : new ArrayList<>(mListeners)) {
                l.onDrawerSlide(drawer, open ? 1f : 0f);
                if (open) l.onDrawerOpened(drawer);
                else l.onDrawerClosed(drawer);
                l.onDrawerStateChanged(STATE_IDLE);
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Input routing between the two windows
    // ---------------------------------------------------------------------------------------------

    /**
     * Keys reaching the game window while the menu is open are sent to the menu on the second
     * screen, so controller navigation of the menu works no matter which display has focus.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event)
    {
        if (mPresentation != null && mMenuOpen && !mBypassForwarding && mPresentation.isShowing()) {
            return mPresentation.dispatchKeyEvent(event);
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * Send a key event from the second screen to the game window. Android moves input focus to
     * whichever display was touched last, so after tapping the menu screen the controller's
     * buttons would otherwise go to the menu instead of the game.
     */
    boolean forwardKeyToGame(KeyEvent event)
    {
        Activity activity = getActivity();
        if (activity == null) return false;
        mBypassForwarding = true;
        try {
            return activity.dispatchKeyEvent(event);
        } finally {
            mBypassForwarding = false;
        }
    }

    boolean forwardMotionToGame(MotionEvent event)
    {
        Activity activity = getActivity();
        if (activity == null) return false;
        mBypassForwarding = true;
        try {
            return activity.dispatchGenericMotionEvent(event);
        } finally {
            mBypassForwarding = false;
        }
    }

    boolean isMenuOpen()
    {
        return mMenuOpen;
    }
}
