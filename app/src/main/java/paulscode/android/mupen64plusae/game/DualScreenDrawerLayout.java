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
import android.content.BroadcastReceiver;
import android.content.ContextWrapper;
import android.content.IntentFilter;
import android.os.Build;
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
 * (e.g. while a game is starting) it shows a plain grey screen. It is closed only when the user
 * leaves the app.
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
    /** Controller navigation for keys handed over from the second screen to this one. */
    private final ControllerNav mHostNav = new ControllerNav();
    /** Back/Menu down was used to toggle the controller; swallow the matching up. */
    private boolean mSwallowToggleUp = false;
    /** In-game only: optional live info (from an expansion) shown instead of the menu. */
    private View mInfoPanel;
    private DisplayManager mDisplayManager;

    /** Fires if the menu screen didn't come up after we asked Android to open it. */
    private final Runnable mLaunchTimeout = () -> {
        if (mLaunchPending && mMenuScreen == null && mSecondScreenActive) {
            onMenuScreenFailed("Android didn't open the menu on screen " + mTargetDisplayId +
                    " (no reply after " + (LAUNCH_TIMEOUT_MS / 1000) + "s)");
        }
    };

    /**
     * Closes the second screen once the user has really left the app (e.g. Home). While any
     * screen of the app is still showing (the game, a settings page, a file picker opened by the
     * app...) it stays, grey if there is nothing to show.
     */
    private final Runnable mLeftAppCheck = new Runnable() {
        @Override
        public void run() {
            if (SecondScreen.sSystemPickerOpen || hostFinishing()) return;
            if (mHostVisible || mMenuScreen == null) return;
            Boolean appVisible = SecondScreen.isAppVisible(getContext());
            if (appVisible == null) {
                // Older Android: can't tell, use the screen's own state
                if (mMenuScreen.isShownToUser()) {
                    Log.i(TAG, "App left; closing second screen");
                    finishMenuScreen();
                }
            } else if (!appVisible) {
                Log.i(TAG, "App left; closing second screen");
                finishMenuScreen();
            } else {
                mHandler.postDelayed(this, LEFT_APP_TIMEOUT_MS);
            }
        }
    };

    /**
     * The second screen was covered. If it wasn't by the app itself (a page opened there, the
     * game's own second screen) the user pressed Home there: both screens go home together.
     */
    /** The host screen is closing (e.g. Exit from the game): nothing is "leaving the app". */
    private boolean hostFinishing()
    {
        Activity a = getActivity();
        return a == null || a.isFinishing() || a.isDestroyed();
    }

    private final Runnable mCoveredCheck = () -> {
        if (SecondScreen.sSystemPickerOpen || hostFinishing()) return;
        if (!mHostVisible || mMenuScreen == null || mMenuScreen.isFinishing() || mMenuScreen.isShownToUser()) return;
        Boolean covered = SecondScreen.isMenuCoveredByOtherApp(getContext(), mMenuScreen.getTaskId());
        if (covered != null && covered) {
            Log.i(TAG, "Home on the second screen; taking both screens home");
            leaveApp(true);
        }
    };

    void onMenuScreenStopped(SecondScreenMenuActivity screen)
    {
        if (screen != mMenuScreen || !mHostVisible) return;
        mHandler.removeCallbacks(mCoveredCheck);
        mHandler.postDelayed(mCoveredCheck, 300);
    }

    // ---------------------------------------------------------------------------------------------
    // Leaving the app: both screens together
    // ---------------------------------------------------------------------------------------------

    static final String ACTION_APP_LEFT = "paulscode.android.mupen64plusae.SECOND_SCREEN_APP_LEFT";

    /** Other screens of the app (also in the game's process) close their second screen too. */
    private final BroadcastReceiver mAppLeftReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            finishMenuScreen();
        }
    };
    private boolean mReceiverRegistered = false;

    private void registerAppLeftReceiver()
    {
        if (mReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(ACTION_APP_LEFT);
        if (Build.VERSION.SDK_INT >= 33) {
            getContext().registerReceiver(mAppLeftReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            getContext().registerReceiver(mAppLeftReceiver, filter);
        }
        mReceiverRegistered = true;
    }

    /**
     * Call from the activity's onUserLeaveHint(): the user pressed Home / Recents on the main
     * screen. The second screen goes away at the same moment.
     */
    public void onUserLeftApp()
    {
        if (SecondScreen.sSystemPickerOpen) return;
        if (mSecondScreenActive) leaveApp(false);
    }

    /** Home / Recents pressed while the second screen had the focus. */
    void onUserLeftAppFromSecondScreen()
    {
        if (SecondScreen.sSystemPickerOpen) return;
        if (mSecondScreenActive) leaveApp(true);
    }

    private void leaveApp(boolean sendMainScreenHome)
    {
        if (hostFinishing()) {
            // Closing this screen (e.g. Exit from the game) is not the user going home
            finishMenuScreen();
            return;
        }
        finishMenuScreen();
        Context ctx = getContext();
        Intent intent = new Intent(ACTION_APP_LEFT);
        intent.setPackage(ctx.getPackageName());
        ctx.sendBroadcast(intent);
        if (sendMainScreenHome) {
            Activity activity = getActivity();
            if (activity != null) activity.moveTaskToBack(true);
        }
    }

    /** Give the main screen the controller's input focus (the newly opened second screen took it). */
    private final Runnable mFocusMainScreen = () -> {
        if (mHostVisible && mMenuScreen != null && !isControllerOnMenu()) {
            Activity activity = getActivity();
            if (activity != null) SecondScreen.bringTaskToFront(activity, activity.getTaskId());
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
            mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
            registerAppLeftReceiver();
            enterSecondScreenMode(true);
        } else {
            mDisplayManager.unregisterDisplayListener(mDisplayListener);
            leaveSecondScreenMode();
        }
    }

    /**
     * In-game: show this view on the second screen while playing, instead of the menu. Back/Menu
     * (or tapping the hint line) switches between it and the menu.
     */
    public void setInfoPanel(View panel)
    {
        mInfoPanel = panel;
        if (mMenuScreen != null && mHostVisible) syncMenuScreen();
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
        mMenuOpen = wasOpen && !mInGame;
        mControllerOnMenu = false;

        launchMenuScreen();
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
            mMenuScreen.attachInfoPanel(mInGame ? mInfoPanel : null);
            mMenuScreen.setMenuOpen(mMenuOpen);
            updateControllerHint();
        } else {
            mMenuScreen.detachMenu();
            mMenuScreen.attachInfoPanel(null);
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
        mHandler.removeCallbacks(mFocusMainScreen);
        mHandler.postDelayed(mFocusMainScreen, 250);
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
            // Closed by the system or the user from the other screen: go back to the side drawer
            Log.i(TAG, "Second screen menu was closed, using the side drawer");
            status("menu screen was closed by the system, using side menu");
            leaveSecondScreenMode();
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
            else syncMenuScreen();
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
        if (mReceiverRegistered) {
            try {
                getContext().unregisterReceiver(mAppLeftReceiver);
            } catch (Exception ignored) {
            }
            mReceiverRegistered = false;
        }
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
        // In a game the menu is always on the second screen: "opening" it must not pause the
        // game (GameActivity pauses on onDrawerOpened, and the sound stops with it)
        if (mInGame) return;

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
                mSwallowToggleUp = true;
                toggleControllerTarget();
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
        if (mMenuScreen == null) return;
        mMenuScreen.showControllerHint(mInGame, mControllerOnMenu);
        mMenuScreen.showInfoPanel(mInGame && !mControllerOnMenu);
    }

    /** In-game: the hint line was tapped; same as pressing Back/Menu. */
    void toggleControllerTarget()
    {
        if (!mInGame) return;
        mControllerOnMenu = !mControllerOnMenu;
        updateControllerHint();
        if (mControllerOnMenu && mMenuScreen != null) mMenuScreen.focusMenuForController();
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
        if (mSecondScreenActive && !mBypassForwarding && isControllerOnMenu()) {
            // The controller is on the menu: move its selection (and not the character)
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
            boolean handled = activity.dispatchKeyEvent(event);
            // Game list: Android doesn't move the selection for keys handed over from the other
            // screen, so do it (the game handles its own keys)
            if (!handled && !mInGame) handled = mHostNav.onKey(activity, event);
            return handled;
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
            boolean handled = activity.dispatchGenericMotionEvent(event);
            if (!handled && !mInGame) handled = mHostNav.onMotion(activity, event);
            return handled;
        } finally {
            mBypassForwarding = false;
        }
    }

    boolean isMenuOpen()
    {
        return mMenuOpen;
    }
}
