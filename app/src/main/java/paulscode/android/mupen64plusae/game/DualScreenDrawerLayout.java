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
import android.app.ActivityOptions;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.drawerlayout.widget.DrawerLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * A DrawerLayout that, on dual-screen devices (e.g. the AYN Thor), moves the in-game menu
 * drawer onto the secondary display instead of sliding it in from the left.
 *
 * The menu is shown by launching {@link SecondScreenMenuActivity} on the other display. (The
 * Presentation API can't be used for this: Android only allows it on displays flagged as
 * external presentation screens, which built-in second panels usually are not.)
 *
 * All the usual DrawerLayout calls made by GameActivity (openDrawer, closeDrawer, isDrawerOpen
 * and the DrawerListener callbacks) keep the same meaning, so the rest of the activity does not
 * need to know which mode is in use: "drawer open" still means "menu active, emulator paused".
 *
 * If no usable second display is present, or it goes away while playing, the classic side drawer
 * is used and a short message explains why.
 */
public class DualScreenDrawerLayout extends DrawerLayout
{
    private static final String TAG = "DualScreenDrawer";

    /** Show a short message at each step (for testing on new devices). */
    private static final boolean DIAGNOSTICS = true;

    private static final long LAUNCH_TIMEOUT_MS = 4000;

    /** Only explain a failure once per process, so it doesn't nag on every game launch. */
    private static boolean sFailureShown = false;

    private final List<DrawerListener> mListeners = new ArrayList<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /** The drawer child from the XML layout (holds the GameSidebar). */
    private View mDrawerView;
    private ViewGroup.LayoutParams mDrawerLayoutParams;

    private boolean mSecondScreenWanted = false;
    private boolean mSecondScreenActive = false;
    private int mTargetDisplayId = -1;
    private SecondScreenMenuActivity mMenuScreen;
    private boolean mLaunchPending = false;

    private boolean mMenuOpen = false;
    private boolean mBypassForwarding = false;
    private DisplayManager mDisplayManager;

    /** Fires if the menu screen didn't come up after we asked Android to open it. */
    private final Runnable mLaunchTimeout = () -> {
        if (mLaunchPending && mMenuScreen == null && mSecondScreenActive) {
            onMenuScreenFailed("Android didn't open the menu on screen " + mTargetDisplayId +
                    " (no reply after " + (LAUNCH_TIMEOUT_MS / 1000) + "s)");
        }
    };

    private final DisplayManager.DisplayListener mDisplayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
            // Only move the menu over while it is closed so we don't yank it from under the user
            if (!mSecondScreenActive && !isDrawerOpen(Gravity.START)) {
                enterSecondScreenMode(false);
            }
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            if (mSecondScreenActive && displayId == mTargetDisplayId) {
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
            enterSecondScreenMode(true);
        } else {
            mDisplayManager.unregisterDisplayListener(mDisplayListener);
            leaveSecondScreenMode();
        }
    }

    /** True while the menu lives on the second screen. */
    public boolean isUsingSecondScreen()
    {
        return mSecondScreenActive;
    }

    // ---------------------------------------------------------------------------------------------
    // Mode switching
    // ---------------------------------------------------------------------------------------------

    private void enterSecondScreenMode(boolean explainFailure)
    {
        if (!mSecondScreenWanted || mSecondScreenActive) return;

        Activity activity = getActivity();
        if (activity == null) return;

        Display target = findSecondaryDisplay(activity);
        status("found screens: " + describeDisplays(activity));
        if (target == null) {
            Log.i(TAG, "No usable secondary display. " + describeDisplays(activity));
            if (explainFailure) {
                showFailure("no second screen found (" + describeDisplays(activity) + ")");
            }
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
        status("opening menu on screen " + target.getDisplayId() + " (" + target.getName() + ")");
        mTargetDisplayId = target.getDisplayId();
        mSecondScreenActive = true;
        mMenuOpen = wasOpen;

        launchMenuScreen();
    }

    private void leaveSecondScreenMode()
    {
        if (!mSecondScreenActive) return;
        mSecondScreenActive = false;
        mLaunchPending = false;

        if (mMenuScreen != null) {
            SecondScreenMenuActivity screen = mMenuScreen;
            mMenuScreen = null;
            screen.finishFromHost();
        }

        if (mDrawerView != null) {
            if (mDrawerView.getParent() instanceof ViewGroup) {
                ((ViewGroup) mDrawerView.getParent()).removeView(mDrawerView);
            }
            addView(mDrawerView, mDrawerLayoutParams);
        }

        if (mMenuOpen) {
            mMenuOpen = false;
            // Re-open as a regular drawer; DrawerLayout will notify the listeners itself
            post(() -> super.openDrawer(Gravity.START));
        }
    }

    private void launchMenuScreen()
    {
        if (!mSecondScreenActive || mMenuScreen != null || mLaunchPending) return;

        Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) return;

        SecondScreenMenuActivity.setHost(this);

        Intent intent = new Intent(activity, SecondScreenMenuActivity.class);
        intent.putExtra(SecondScreenMenuActivity.EXTRA_DISPLAY_ID, mTargetDisplayId);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);

        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(mTargetDisplayId);

        try {
            mLaunchPending = true;
            activity.startActivity(intent, options.toBundle());
            // Android doesn't report a refused launch on another display, so check ourselves
            mHandler.removeCallbacks(mLaunchTimeout);
            mHandler.postDelayed(mLaunchTimeout, LAUNCH_TIMEOUT_MS);
        } catch (Exception e) {
            Log.w(TAG, "Couldn't open menu on display " + mTargetDisplayId, e);
            mLaunchPending = false;
            onMenuScreenFailed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void finishMenuScreen()
    {
        mLaunchPending = false;
        mHandler.removeCallbacks(mLaunchTimeout);
        if (mMenuScreen != null) {
            SecondScreenMenuActivity screen = mMenuScreen;
            mMenuScreen = null;
            screen.finishFromHost();
        }
    }

    // Called by SecondScreenMenuActivity --------------------------------------------------------

    void onMenuScreenReady(SecondScreenMenuActivity screen)
    {
        mLaunchPending = false;
        mHandler.removeCallbacks(mLaunchTimeout);
        status("menu screen is up");

        if (!mSecondScreenActive) {
            screen.finishFromHost();
            return;
        }
        if (mMenuScreen != null && mMenuScreen != screen) {
            mMenuScreen.finishFromHost();
        }
        mMenuScreen = screen;
        screen.attachMenu(mDrawerView);
        screen.setMenuOpen(mMenuOpen);
    }

    void onMenuScreenGone(SecondScreenMenuActivity screen, boolean expected)
    {
        if (screen != mMenuScreen) return;
        mMenuScreen = null;

        if (!expected && mSecondScreenActive) {
            // Closed by the system or the user from the other screen: go back to the side drawer
            Log.i(TAG, "Second screen menu was closed, using the side drawer");
            status("menu screen was closed by the system, using side menu");
            leaveSecondScreenMode();
        }
    }

    void onMenuScreenFailed(String reason)
    {
        mLaunchPending = false;
        // Don't keep retrying for this game session
        mSecondScreenWanted = false;
        leaveSecondScreenMode();
        showFailure(reason);
    }

    // Helpers -----------------------------------------------------------------------------------

    /** Diagnostic messages while second-screen support is being tested on real hardware. */
    private void status(String message)
    {
        Log.i(TAG, message);
        if (!DIAGNOSTICS) return;
        Toast.makeText(getContext(), "2nd screen: " + message, Toast.LENGTH_LONG).show();
    }

    private void showFailure(String reason)
    {
        if (sFailureShown && !DIAGNOSTICS) return;
        sFailureShown = true;
        Toast.makeText(getContext(), "Second screen menu not available: " + reason,
                Toast.LENGTH_LONG).show();
    }

    @Nullable
    private Display findSecondaryDisplay(@NonNull Activity activity)
    {
        if (mDisplayManager == null) return null;

        @SuppressWarnings("deprecation")
        int ownDisplayId = activity.getWindowManager().getDefaultDisplay().getDisplayId();

        Display fallback = null;
        for (Display d : mDisplayManager.getDisplays()) {
            if (d.getDisplayId() == ownDisplayId || !d.isValid()) continue;
            if ((d.getFlags() & Display.FLAG_PRIVATE) != 0) continue;
            // Prefer built-in panels (like the Thor's bottom screen) over cast/HDMI displays
            if ((d.getFlags() & Display.FLAG_PRESENTATION) == 0) return d;
            if (fallback == null) fallback = d;
        }
        if (fallback != null) return fallback;

        // The game may be running on a secondary display itself; then use the main one
        if (ownDisplayId != Display.DEFAULT_DISPLAY) {
            Display main = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
            if (main != null && main.isValid()) return main;
        }
        return null;
    }

    private String describeDisplays(@NonNull Activity activity)
    {
        @SuppressWarnings("deprecation")
        int ownDisplayId = activity.getWindowManager().getDefaultDisplay().getDisplayId();
        StringBuilder sb = new StringBuilder("game on " + ownDisplayId + "; displays:");
        for (Display d : mDisplayManager.getDisplays()) {
            sb.append(' ').append(d.getDisplayId()).append('=').append(d.getName())
                    .append("/0x").append(Integer.toHexString(d.getFlags()));
        }
        return sb.toString();
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
    // Window lifecycle: the menu screen follows the game's visibility
    // ---------------------------------------------------------------------------------------------

    @Override
    protected void onWindowVisibilityChanged(int visibility)
    {
        super.onWindowVisibilityChanged(visibility);
        if (!mSecondScreenActive) return;

        if (visibility == View.VISIBLE) {
            launchMenuScreen();
        } else {
            // Leaving the game (home, recents, exit): take the menu off the other screen too
            finishMenuScreen();
        }
    }

    @Override
    protected void onDetachedFromWindow()
    {
        if (mDisplayManager != null) {
            mDisplayManager.unregisterDisplayListener(mDisplayListener);
        }
        finishMenuScreen();
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
        if (!mSecondScreenActive) {
            super.openDrawer(gravity);
            return;
        }
        if (mMenuOpen) return;

        mMenuOpen = true;
        if (mMenuScreen != null) mMenuScreen.setMenuOpen(true);
        dispatchMenuState(true);
    }

    @Override
    public void openDrawer(int gravity, boolean animate)
    {
        if (!mSecondScreenActive) super.openDrawer(gravity, animate);
        else openDrawer(gravity);
    }

    @Override
    public void closeDrawer(int gravity)
    {
        if (!mSecondScreenActive) {
            super.closeDrawer(gravity);
            return;
        }
        if (!mMenuOpen) return;

        mMenuOpen = false;
        if (mMenuScreen != null) mMenuScreen.setMenuOpen(false);
        dispatchMenuState(false);
    }

    @Override
    public void closeDrawer(int gravity, boolean animate)
    {
        if (!mSecondScreenActive) super.closeDrawer(gravity, animate);
        else closeDrawer(gravity);
    }

    @Override
    public void closeDrawers()
    {
        if (!mSecondScreenActive) super.closeDrawers();
        else closeDrawer(Gravity.START);
    }

    @Override
    public boolean isDrawerOpen(int drawerGravity)
    {
        if (!mSecondScreenActive) return super.isDrawerOpen(drawerGravity);
        return mMenuOpen;
    }

    @Override
    public boolean isDrawerVisible(int drawerGravity)
    {
        if (!mSecondScreenActive) return super.isDrawerVisible(drawerGravity);
        return mMenuOpen;
    }

    /**
     * Listener callbacks are posted, like DrawerLayout does after its animation, so that a
     * listener added later in onCreate still receives the initial "opened" event.
     */
    private void dispatchMenuState(final boolean open)
    {
        mHandler.post(() -> {
            if (!mSecondScreenActive || mMenuOpen != open) return;
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
    // Input routing between the two screens
    // ---------------------------------------------------------------------------------------------

    /**
     * Keys reaching the game window while the menu is open are sent to the menu on the second
     * screen, so controller navigation of the menu works no matter which display has focus.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event)
    {
        if (mSecondScreenActive && mMenuOpen && !mBypassForwarding && mMenuScreen != null) {
            return mMenuScreen.dispatchKeyEvent(event);
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * Send a key event from the second screen to the game. Android moves input focus to whichever
     * display was touched last, so after tapping the menu screen the controller's buttons would
     * otherwise go to the menu instead of the game.
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
