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
 * A DrawerLayout that, on dual-screen devices (e.g. the AYN Thor), moves its drawer (the app
 * menu, or the in-game menu) onto the secondary display instead of sliding it in from the left.
 *
 * The menu is shown by launching {@link SecondScreenMenuActivity} on the other display. (The
 * Presentation API can't be used for this: Android only allows it on displays flagged as
 * external presentation screens, which built-in second panels usually are not.)
 *
 * Two modes:
 * <ul>
 * <li><b>App menus</b> (game list): "drawer open" keeps its usual meaning (a game's options or
 *     the main menu has the buttons); closing it gives the buttons back to the game list.</li>
 * <li><b>In-game</b>: the menu is always live and never pauses the game. The controller drives
 *     the game; Back/Menu hands the controller to the menu (game keeps running), and Back/Menu
 *     again hands it back.</li>
 * </ul>
 *
 * The second screen never goes blank while the app is in use: when there is no menu to show
 * (e.g. while a game is starting) it shows its plain background. It can't be closed from the
 * second screen (Back never closes it, and if Android closes it or covers it with something
 * else it is brought straight back); it is closed only when the user leaves the app.
 *
 * If no usable second display is present, or it goes away, the classic side drawer is used.
 */
public class DualScreenDrawerLayout extends DrawerLayout
{
    private static final String TAG = "DualScreenDrawer";

    /** Show a short message at each step (for testing on new devices). */
    static final boolean DIAGNOSTICS = false;

    private static final long LAUNCH_TIMEOUT_MS = 4000;

    /** How long the grey second screen may sit unused before we assume the app was left. */
    private static final long LEFT_APP_TIMEOUT_MS = 3000;

    /** Only explain a failure once per process, so it doesn't nag on every game launch. */
    private static boolean sFailureShown = false;

    private final List<DrawerListener> mListeners = new ArrayList<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /** The drawer child from the XML layout (holds the GameSidebar). */
    private View mDrawerView;
    private ViewGroup.LayoutParams mDrawerLayoutParams;

    private boolean mSecondScreenWanted = false;
    /** In-game mode: menu always live, never pauses, controller toggled with Back/Menu. */
    private boolean mInGame = true;
    private Class<? extends SecondScreenMenuActivity> mMenuActivityClass = SecondScreenMenuActivity.class;
    private boolean mSecondScreenActive = false;
    private int mTargetDisplayId = -1;
    private SecondScreenMenuActivity mMenuScreen;
    private boolean mLaunchPending = false;
    private boolean mHostVisible = false;

    private boolean mMenuOpen = false;
    /** In-game only: the controller currently drives the menu instead of the game. */
    private boolean mControllerOnMenu = false;
    private boolean mBypassForwarding = false;
    /** Back/Menu down was used to toggle the controller; swallow the matching up. */
    private boolean mSwallowToggleUp = false;
    private DisplayManager mDisplayManager;

    /** Times the menu screen had to be reopened recently, to give up if Android keeps refusing. */
    private final long[] mRelaunchTimes = new long[4];
    private int mRelaunchIndex = 0;
    private long mLastBringToFront = 0;

    /** Told when the menus move to the second screen or back to the side drawer. */
    public interface OnSecondScreenModeChangedListener
    {
        void onSecondScreenModeChanged(boolean usingSecondScreen);
    }

    private OnSecondScreenModeChangedListener mModeListener;

    /** Something on the second screen was closed or covered: make sure the menu is in front. */
    private final Runnable mEnsureMenuInFront = this::ensureMenuScreenInFront;
    private final Runnable mOnPageStopped = () -> {
        mHandler.removeCallbacks(mEnsureMenuInFront);
        mHandler.postDelayed(mEnsureMenuInFront, 500);
    };

    /** Fires if the menu screen didn't come up after we asked Android to open it. */
    private final Runnable mLaunchTimeout = () -> {
        if (mLaunchPending && mMenuScreen == null && mSecondScreenActive) {
            onMenuScreenFailed("Android didn't open the menu on screen " + mTargetDisplayId +
                    " (no reply after " + (LAUNCH_TIMEOUT_MS / 1000) + "s)");
        }
    };

    /** Closes the grey second screen once it's clear the user has left the app. */
    private final Runnable mLeftAppCheck = () -> {
        // Still in the app if one of its pages covers this one (e.g. the ROM scanner)
        if (!mHostVisible && mMenuScreen != null && mMenuScreen.isShownToUser() &&
                !SecondScreen.isAnyPageShown()) {
            Log.i(TAG, "App left; closing second screen");
            finishMenuScreen();
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
     * In-game menu on the second screen. Call this from the activity's onCreate after
     * setContentView and after looking up the drawer's views (they are moved out of this window).
     */
    public void setSecondScreenEnabled(boolean enabled)
    {
        setSecondScreenEnabled(enabled, false, SecondScreenMenuActivity.class);
    }

    /**
     * @param appMenus      true for the app's menus (game list); false for the in-game menu
     * @param menuActivity  the activity hosting the menu; it must run in the same process as the
     *                      caller, since it displays the caller's own views
     */
    public void setSecondScreenEnabled(boolean enabled, boolean appMenus,
                                       Class<? extends SecondScreenMenuActivity> menuActivity)
    {
        mSecondScreenWanted = enabled;
        mInGame = !appMenus;
        mMenuActivityClass = menuActivity;

        if (mDisplayManager == null) {
            mDisplayManager = (DisplayManager) getContext().getSystemService(Context.DISPLAY_SERVICE);
        }

        if (enabled) {
            SecondScreen.track(getContext());
            SecondScreen.addPageStoppedListener(mOnPageStopped);
            mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
            enterSecondScreenMode(true);
        } else {
            SecondScreen.removePageStoppedListener(mOnPageStopped);
            mDisplayManager.unregisterDisplayListener(mDisplayListener);
            leaveSecondScreenMode();
        }
    }

    public void setOnSecondScreenModeChangedListener(@Nullable OnSecondScreenModeChangedListener listener)
    {
        mModeListener = listener;
        if (listener != null) listener.onSecondScreenModeChanged(mSecondScreenActive);
    }

    private void notifyModeChanged()
    {
        if (mModeListener != null) mModeListener.onSecondScreenModeChanged(mSecondScreenActive);
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
            if (explainFailure && DIAGNOSTICS) {
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

        Log.i(TAG, "Showing menu on display " + target.getDisplayId() + " (" + target.getName() + ")");
        status("opening menu on screen " + target.getDisplayId() + " (" + target.getName() + ")");
        mTargetDisplayId = target.getDisplayId();
        mSecondScreenActive = true;
        mMenuOpen = wasOpen;
        mControllerOnMenu = false;

        launchMenuScreen();
        notifyModeChanged();
    }

    private void leaveSecondScreenMode()
    {
        if (!mSecondScreenActive) return;
        mSecondScreenActive = false;
        mLaunchPending = false;
        mControllerOnMenu = false;
        mHandler.removeCallbacks(mLeftAppCheck);

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
        notifyModeChanged();
    }

    /**
     * Keep the menu up on the second screen while this window is showing: reopen it if it was
     * closed, and bring it back in front if something else took over that screen (but not over
     * one of our own pages that was opened there, like a settings page).
     */
    private void ensureMenuScreenInFront()
    {
        if (!mSecondScreenActive || !mHostVisible || mLaunchPending) return;
        Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) return;

        if (mMenuScreen == null) {
            launchMenuScreen();
            return;
        }
        if (mMenuScreen.isStartedState() || SecondScreen.isPageShownOn(mTargetDisplayId)) return;

        long now = android.os.SystemClock.uptimeMillis();
        if (now - mLastBringToFront < 1000) {
            mHandler.removeCallbacks(mEnsureMenuInFront);
            mHandler.postDelayed(mEnsureMenuInFront, 1000);
            return;
        }
        mLastBringToFront = now;

        Log.i(TAG, "Second screen was covered; bringing the menu back");
        Intent intent = new Intent(activity, mMenuActivityClass);
        intent.putExtra(SecondScreenMenuActivity.EXTRA_DISPLAY_ID, mTargetDisplayId);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION |
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(mTargetDisplayId);
        try {
            activity.startActivity(intent, options.toBundle());
        } catch (Exception e) {
            Log.w(TAG, "Couldn't bring the menu back on display " + mTargetDisplayId, e);
        }
    }

    /** Returns false if the menu screen has been reopened too often in a short time. */
    private boolean allowRelaunch()
    {
        long now = android.os.SystemClock.uptimeMillis();
        long oldest = mRelaunchTimes[mRelaunchIndex];
        if (oldest != 0 && now - oldest < 10000) return false;
        mRelaunchTimes[mRelaunchIndex] = now;
        mRelaunchIndex = (mRelaunchIndex + 1) % mRelaunchTimes.length;
        return true;
    }

    private void launchMenuScreen()
    {
        if (!mSecondScreenActive || mMenuScreen != null || mLaunchPending) return;

        Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) return;

        SecondScreenMenuActivity.setHost(this);

        Intent intent = new Intent(activity, mMenuActivityClass);
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
        mHandler.removeCallbacks(mLeftAppCheck);
        if (mMenuScreen != null) {
            SecondScreenMenuActivity screen = mMenuScreen;
            mMenuScreen = null;
            screen.finishFromHost();
        }
    }

    /** Show the menu on the second screen if this window is showing, otherwise the grey screen. */
    private void syncMenuScreen()
    {
        if (mMenuScreen == null) return;
        if (mHostVisible) {
            mMenuScreen.attachMenu(mDrawerView);
            mMenuScreen.setMenuOpen(mMenuOpen);
            updateControllerHint();
        } else {
            mMenuScreen.detachMenu();
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
        syncMenuScreen();
    }

    /** The second screen came back into view (e.g. the game's own second screen closed). */
    void onMenuScreenShown(SecondScreenMenuActivity screen)
    {
        if (screen != mMenuScreen) return;
        if (!mHostVisible) {
            mHandler.removeCallbacks(mLeftAppCheck);
            mHandler.postDelayed(mLeftAppCheck, LEFT_APP_TIMEOUT_MS);
        }
    }

    void onMenuScreenGone(SecondScreenMenuActivity screen, boolean expected)
    {
        if (screen != mMenuScreen) return;
        mMenuScreen = null;

        if (!expected && mSecondScreenActive) {
            if (allowRelaunch()) {
                // The two screens belong together: reopen it right away (or as soon as this
                // window shows again)
                Log.i(TAG, "Second screen menu was closed; reopening it");
                status("menu screen was closed, reopening");
                mHandler.removeCallbacks(mEnsureMenuInFront);
                mHandler.postDelayed(mEnsureMenuInFront, 300);
            } else {
                // Android keeps closing it: go back to the side drawer
                Log.i(TAG, "Second screen menu keeps closing, using the side drawer");
                status("menu screen keeps closing, using side menu");
                leaveSecondScreenMode();
            }
        }
    }

    /** The menu screen went into the background (e.g. something else opened on that screen). */
    void onMenuScreenStopped(SecondScreenMenuActivity screen)
    {
        if (screen != mMenuScreen) return;
        mHandler.removeCallbacks(mEnsureMenuInFront);
        mHandler.postDelayed(mEnsureMenuInFront, 500);
    }

    /**
     * Back from the second screen's system back handling (gesture/predictive back, where no Back
     * key arrives): the same as the Back key, and never closes the second screen.
     */
    void onBackFromMenuScreen()
    {
        if (mInGame) {
            long now = android.os.SystemClock.uptimeMillis();
            handleInGameToggle(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0));
            handleInGameToggle(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0));
            return;
        }
        Activity activity = getActivity();
        if (activity instanceof androidx.activity.ComponentActivity) {
            ((androidx.activity.ComponentActivity) activity).getOnBackPressedDispatcher().onBackPressed();
        }
    }

    void onMenuScreenFailed(String reason)
    {
        mLaunchPending = false;
        // Don't keep retrying for this session
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
        Display target = SecondScreen.findMenuDisplay(activity);
        // Nothing to move if this screen already is the menu screen
        if (target == null || target.getDisplayId() == SecondScreen.displayOf(activity)) return null;
        return target;
    }

    private String describeDisplays(@NonNull Activity activity)
    {
        StringBuilder sb = new StringBuilder("this on " + SecondScreen.displayOf(activity) + "; displays:");
        if (mDisplayManager != null) {
            for (Display d : mDisplayManager.getDisplays()) {
                sb.append(' ').append(d.getDisplayId()).append('=').append(d.getName())
                        .append("/0x").append(Integer.toHexString(d.getFlags()));
            }
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
    // Window lifecycle: the second screen follows this window's visibility
    // ---------------------------------------------------------------------------------------------

    @Override
    protected void onWindowVisibilityChanged(int visibility)
    {
        super.onWindowVisibilityChanged(visibility);
        mHostVisible = visibility == View.VISIBLE;
        if (!mSecondScreenActive) return;

        if (mHostVisible) {
            mHandler.removeCallbacks(mLeftAppCheck);
            if (mMenuScreen == null) launchMenuScreen();
            else {
                syncMenuScreen();
                mOnPageStopped.run();
            }
        } else {
            // Another screen of ours (e.g. the game) or the home screen covers this window:
            // keep the second screen, grey, until we know which
            if (mMenuScreen != null) {
                mMenuScreen.detachMenu();
                mHandler.removeCallbacks(mLeftAppCheck);
                mHandler.postDelayed(mLeftAppCheck, LEFT_APP_TIMEOUT_MS);
            }
        }
    }

    @Override
    protected void onDetachedFromWindow()
    {
        if (mDisplayManager != null) {
            mDisplayManager.unregisterDisplayListener(mDisplayListener);
        }
        SecondScreen.removePageStoppedListener(mOnPageStopped);
        mHandler.removeCallbacks(mEnsureMenuInFront);
        finishMenuScreen();
        super.onDetachedFromWindow();
    }

    // ---------------------------------------------------------------------------------------------
    // DrawerLayout API used by the activities
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
    // Controller routing between the two screens
    // ---------------------------------------------------------------------------------------------

    /** Whether controller buttons currently drive the second-screen menu. */
    boolean isControllerOnMenu()
    {
        return mInGame ? mControllerOnMenu : mMenuOpen;
    }

    boolean isInGame()
    {
        return mInGame;
    }

    private static boolean isToggleKey(int keyCode)
    {
        return keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU;
    }

    /**
     * In-game: Back/Menu hands the controller to the menu or back to the game. The game keeps
     * running either way. Returns true if the event was used for that.
     */
    private boolean handleInGameToggle(KeyEvent event)
    {
        if (!mInGame || !isToggleKey(event.getKeyCode())) return false;

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                mControllerOnMenu = !mControllerOnMenu;
                mSwallowToggleUp = true;
                if (mMenuScreen != null) {
                    updateControllerHint();
                    if (mControllerOnMenu) mMenuScreen.focusMenuForController();
                }
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP && mSwallowToggleUp) {
            mSwallowToggleUp = false;
            return true;
        }
        return true;
    }

    private void updateControllerHint()
    {
        if (mMenuScreen != null) mMenuScreen.showControllerHint(mInGame, mControllerOnMenu);
    }

    /** Keys arriving at this (main screen) window. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event)
    {
        if (!mSecondScreenActive || mBypassForwarding) return super.dispatchKeyEvent(event);

        if (handleInGameToggle(event)) return true;

        // Game list: Back/Menu belong to the list screen (e.g. Back leaves a game's options)
        if (!mInGame && isToggleKey(event.getKeyCode())) return super.dispatchKeyEvent(event);

        if (isControllerOnMenu() && mMenuScreen != null) {
            // Never pass these on to the game. Returning "unhandled" for keys the menu ignores
            // lets Android send its fallback key (e.g. A -> select), which comes back here too.
            return mMenuScreen.dispatchKeyToMenu(event);
        }
        return super.dispatchKeyEvent(event);
    }

    /** Analog sticks / d-pad axes arriving at this window. */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event)
    {
        if (mSecondScreenActive && !mBypassForwarding && mInGame && mControllerOnMenu) {
            // The controller is on the menu: don't move the character
            if (mMenuScreen != null) mMenuScreen.dispatchMotionToMenu(event);
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    /**
     * A key that arrived at the second-screen window (it gets input focus once it's touched).
     * Returns true if it was handled here; false means the menu should handle it itself.
     */
    boolean onKeyFromMenuScreen(KeyEvent event)
    {
        if (handleInGameToggle(event)) return true;

        // Back from the menu screen: the activity decides (e.g. game list: back to the list)
        if (!mInGame && isToggleKey(event.getKeyCode())) {
            forwardKeyToGame(event);
            return true;
        }
        if (!isControllerOnMenu()) {
            forwardKeyToGame(event);
            return true;
        }
        return false;
    }

    boolean onMotionFromMenuScreen(MotionEvent event)
    {
        if (!isControllerOnMenu()) {
            forwardMotionToGame(event);
            return true;
        }
        return false;
    }

    private boolean forwardKeyToGame(KeyEvent event)
    {
        Activity activity = getActivity();
        if (activity == null) return false;

        // Forwarded keys skip Android's "leave touch mode" step, which would leave the focus
        // highlight invisible after the screen was touched. Do it here.
        if (event.getAction() == KeyEvent.ACTION_DOWN && isInTouchMode()) {
            View focus = activity.getCurrentFocus();
            if (focus != null) focus.requestFocusFromTouch();
        }

        mBypassForwarding = true;
        try {
            return activity.dispatchKeyEvent(event);
        } finally {
            mBypassForwarding = false;
        }
    }

    private boolean forwardMotionToGame(MotionEvent event)
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
